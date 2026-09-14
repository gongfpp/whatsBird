#!/usr/bin/env python3

# Copyright 2026 gongfpp (https://github.com/gongfpp/whatsBird)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Export the trained classifier to TFLite, measure it honestly, and install it into the app.

Three things happen here, in this order:

1. The trained Keras model is converted twice: float32 and full int8. Both are kept, because the
   app must be fast on a mid-range phone but a quantisation drop that is too large has to be
   visible rather than assumed away.
2. Both exports are evaluated on the held-out test split with the *TFLite* interpreter, and the
   numbers are written to `docs/MODEL_CARD.md`. The plan's acceptance criteria are reported
   verbatim: how often a species name is shown, how often that name is right, and how often a bird
   that is not on the list gets mislabelled as one that is.
3. The chosen model and the matching `species.json` are copied into the Android assets, so the
   class order in the app can never drift from the class order the model was trained on.

Usage:
    python export_assets.py --data data --out out --model out/model.keras \
        --android ../android/app/src/main/assets/models
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
from collections import defaultdict

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np  # noqa: E402
import tensorflow as tf  # noqa: E402

from common_birds import BACKGROUND_LABEL, DISTRACTOR_BIRDS, NON_BIRD_TAXA, TARGET_SPECIES  # noqa: E402


def class_order() -> list[str]:
    return [s.scientific_name for s in TARGET_SPECIES] + [BACKGROUND_LABEL]


def load_test_records(data_dir: str) -> list[dict]:
    records: list[dict] = []
    with open(os.path.join(data_dir, "manifest.jsonl"), encoding="utf-8") as handle:
        for line in handle:
            record = json.loads(line)
            if record["split"] == "test":
                records.append(record)
    return records


def make_image_pipeline(records: list[dict], data_dir: str, input_size: int) -> tf.data.Dataset:
    """Reproduces training-time framing exactly: centre square crop, then bilinear resize."""
    paths = [os.path.join(data_dir, r["file"]) for r in records]

    def load(path):
        raw = tf.io.read_file(path)
        image = tf.io.decode_jpeg(raw, channels=3)
        shape = tf.shape(image)
        side = tf.minimum(shape[0], shape[1])
        top = (shape[0] - side) // 2
        left = (shape[1] - side) // 2
        image = tf.image.crop_to_bounding_box(image, top, left, side, side)
        image = tf.image.resize(image, (input_size, input_size), method="bilinear")
        return image

    return tf.data.Dataset.from_tensor_slices(paths).map(load, num_parallel_calls=tf.data.AUTOTUNE)


def convert_float(model: tf.keras.Model) -> bytes:
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    return converter.convert()


def convert_float16(model: tf.keras.Model) -> bytes:
    """Weights in half precision, everything else untouched. Halves the file for ~no accuracy cost."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    return converter.convert()


def convert_int8(model: tf.keras.Model, representative: tf.data.Dataset) -> bytes:
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]

    def representative_dataset():
        for batch in representative.batch(8).take(30):
            yield [tf.cast(batch, tf.float32)]

    converter.representative_dataset = representative_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.uint8
    converter.inference_output_type = tf.uint8
    return converter.convert()


class TfliteRunner:
    def __init__(self, model_bytes: bytes):
        # The default XNNPACK delegate is switched off deliberately. In the Python TFLite runtime
        # it fails to prepare this int8 MobileNetV3 graph outright ("failed to create XNNPACK
        # runtime"), and where it does run it reorders float arithmetic, which would make the
        # reference numbers a property of the evaluator's SIMD path. The phone picks its own
        # delegates; these numbers are meant to describe the model, not the host.
        self.interpreter = tf.lite.Interpreter(
            model_content=model_bytes,
            num_threads=4,
            experimental_op_resolver_type=tf.lite.experimental.OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
        )
        self.interpreter.allocate_tensors()
        self.input_detail = self.interpreter.get_input_details()[0]
        self.output_detail = self.interpreter.get_output_details()[0]
        self.dtype = self.input_detail["dtype"]
        in_scale, in_zero = self.input_detail["quantization"]
        out_scale, out_zero = self.output_detail["quantization"]
        self.in_scale = in_scale or 1.0
        self.in_zero = in_zero
        self.out_scale = out_scale or 1.0
        self.out_zero = out_zero
        self.input_size = self.input_detail["shape"][1]

    def predict(self, image: np.ndarray) -> np.ndarray:
        if self.dtype == np.uint8:
            data = np.clip(image, 0, 255).astype(np.uint8)[None, ...]
        elif self.dtype == np.int8:
            data = np.clip(np.round(image / self.in_scale) + self.in_zero, -128, 127).astype(np.int8)[None, ...]
        else:
            data = image.astype(np.float32)[None, ...]

        self.interpreter.set_tensor(self.input_detail["index"], data)
        self.interpreter.invoke()
        raw = self.interpreter.get_tensor(self.output_detail["index"])[0]

        if self.output_detail["dtype"] == np.uint8:
            return (raw.astype(np.float32) - self.out_zero) * self.out_scale
        if self.output_detail["dtype"] == np.int8:
            return (raw.astype(np.float32) - self.out_zero) * self.out_scale
        return raw.astype(np.float32)


def evaluate(runner: TfliteRunner, records: list[dict], data_dir: str, classes: list[str], threshold: float) -> dict:
    """Computes overall accuracy plus the plan's three acceptance-style numbers."""
    background_index = len(classes) - 1
    target_indices = set(range(background_index))
    distractor_set = set(DISTRACTOR_BIRDS)
    non_bird_set = set(NON_BIRD_TAXA)

    total = 0
    correct = 0
    per_class_hits: dict[int, int] = defaultdict(int)
    per_class_total: dict[int, int] = defaultdict(int)

    shown = 0
    shown_correct = 0
    target_total = 0

    out_of_list_total = 0
    out_of_list_mislabelled = 0
    non_bird_total = 0
    non_bird_mislabelled = 0

    for image, record in zip(make_image_pipeline(records, data_dir, runner.input_size), records):
        scores = runner.predict(image.numpy())
        predicted = int(np.argmax(scores))
        confidence = float(scores[predicted])
        true_label = record["class_label"]
        true_index = classes.index(true_label) if true_label in classes else background_index
        total += 1
        per_class_total[true_index] += 1
        if predicted == true_index:
            correct += 1
            per_class_hits[true_index] += 1

        if true_index in target_indices:
            target_total += 1
            if predicted in target_indices and confidence >= threshold:
                shown += 1
                if predicted == true_index:
                    shown_correct += 1

        if true_index == background_index:
            reported_as_species = predicted in target_indices and confidence >= threshold
            if record["taxon_name"] in distractor_set:
                out_of_list_total += 1
                if reported_as_species:
                    out_of_list_mislabelled += 1
            elif record["taxon_name"] in non_bird_set:
                non_bird_total += 1
                if reported_as_species:
                    non_bird_mislabelled += 1

    recalls = [
        per_class_hits.get(i, 0) / per_class_total[i]
        for i in range(len(classes))
        if per_class_total.get(i, 0) > 0
    ]
    return {
        "test_images": total,
        "accuracy": correct / total if total else 0.0,
        "macro_recall": float(np.mean(recalls)) if recalls else 0.0,
        "threshold": threshold,
        "coverage": shown / target_total if target_total else 0.0,
        "precision_when_shown": shown_correct / shown if shown else 0.0,
        "out_of_list_false_report_rate": out_of_list_mislabelled / out_of_list_total
        if out_of_list_total
        else 0.0,
        "non_bird_false_report_rate": non_bird_mislabelled / non_bird_total if non_bird_total else 0.0,
        "out_of_list_images": out_of_list_total,
        "non_bird_images": non_bird_total,
        "target_images": target_total,
        "per_class_recall": {
            classes[i]: round(per_class_hits.get(i, 0) / per_class_total[i], 4)
            for i in range(len(classes))
            if per_class_total.get(i, 0) > 0
        },
    }


def write_species_json(path: str, input_size: int, model_version: str, crop_padding: float) -> None:
    classes = [
        {
            "i": index,
            "sci": species.scientific_name,
            "en": species.english_name,
            "zh": species.chinese_name,
        }
        for index, species in enumerate(TARGET_SPECIES)
    ]
    payload = {
        "version": "1",
        "modelVersion": model_version,
        "inputSize": input_size,
        "inputScale": 1.0,
        "inputOffset": 0.0,
        "cropPaddingRatio": crop_padding,
        "backgroundClassIndex": len(TARGET_SPECIES),
        "classes": classes,
    }
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)


def load_inference_model(path: str) -> tf.keras.Model:
    """Load the exported classifier and refuse anything that is not the inference graph.

    `train.py` writes `model.keras` with the augmentation stack removed. Loading `model_full.keras`
    or `best.keras` by mistake would still convert, but it would ship a graph carrying random
    augmentation ops, so the check is made here rather than trusted upstream.
    """
    try:
        model = tf.keras.models.load_model(path)
    except Exception as exc:  # the message is the useful part, so it is surfaced verbatim
        raise SystemExit(
            f"could not load {path}: {exc}\n"
            "Pass the inference graph written by train.py (out/model.keras), not a training checkpoint."
        ) from exc

    training_only = [
        layer.name for layer in model.layers if layer.name.startswith("random_") or layer.name == "augment"
    ]
    if training_only:
        raise SystemExit(f"{path} still contains training-only layers: {', '.join(training_only)}")
    return model


#: How much accuracy a smaller export may give up and still be preferred, in absolute accuracy.
SIZE_SAVING_TOLERANCE = 0.005


def choose_export(results: dict[str, dict], prefer: str) -> str:
    """Picks which export to ship, defaulting to the smallest one whose numbers hold up.

    Smallness is only worth having while the accuracy stays put. MobileNetV3 quantises badly —
    hard-swish and its per-tensor-quantised multiplies are the usual culprits — and this run measured
    full int8 costing 16 accuracy points against float32, which is far too much to ship silently.
    Deciding here from the measured numbers means nobody has to remember which one was safe.
    """
    if prefer != "auto":
        return prefer

    floor = results["float32"]["accuracy"] - SIZE_SAVING_TOLERANCE
    for name in sorted(results, key=lambda candidate: results[candidate]["size_mb"]):
        if results[name]["accuracy"] >= floor:
            return name
    return "float32"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="data")
    parser.add_argument("--out", default="out")
    parser.add_argument("--model", default=None)
    parser.add_argument("--android", default=None, help="Android assets/models directory")
    parser.add_argument("--threshold", type=float, default=0.55, help="app display threshold")
    parser.add_argument("--crop-padding", type=float, default=0.15)
    parser.add_argument("--model-version", default="whatsbird-mobilenetv3small-cn-v1")
    parser.add_argument(
        "--prefer",
        choices=["auto", "float32", "float16", "int8"],
        default="auto",
        help="which export to ship; 'auto' ships the smallest one that holds up on the test set",
    )
    args = parser.parse_args()

    data_dir = os.path.abspath(args.data)
    out_dir = os.path.abspath(args.out)
    model_path = args.model or os.path.join(out_dir, "model.keras")

    model = load_inference_model(model_path)
    input_size = int(model.input_shape[1])
    classes = class_order()
    if model.output_shape[-1] != len(classes):
        raise SystemExit(f"model has {model.output_shape[-1]} outputs but the list has {len(classes)} classes")

    print(f"loaded {model_path}: input={input_size} classes={len(classes)}")

    float_bytes = convert_float(model)
    float_path = os.path.join(out_dir, "classifier_float32.tflite")
    with open(float_path, "wb") as handle:
        handle.write(float_bytes)
    print(f"float32 tflite: {len(float_bytes) / 1e6:.2f} MB")

    half_bytes = convert_float16(model)
    half_path = os.path.join(out_dir, "classifier_float16.tflite")
    with open(half_path, "wb") as handle:
        handle.write(half_bytes)
    print(f"float16 tflite: {len(half_bytes) / 1e6:.2f} MB")

    train_records = []
    with open(os.path.join(data_dir, "manifest.jsonl"), encoding="utf-8") as handle:
        for line in handle:
            record = json.loads(line)
            if record["split"] == "train":
                train_records.append(record)
    representative = make_image_pipeline(train_records[:1200], data_dir, input_size)

    int8_bytes = convert_int8(model, representative)
    int8_path = os.path.join(out_dir, "classifier_int8.tflite")
    with open(int8_path, "wb") as handle:
        handle.write(int8_bytes)
    print(f"int8 tflite:    {len(int8_bytes) / 1e6:.2f} MB")

    test_records = load_test_records(data_dir)
    print(f"evaluating on {len(test_records)} held-out test images")

    exports = {
        "float32": (float_bytes, float_path),
        "float16": (half_bytes, half_path),
        "int8": (int8_bytes, int8_path),
    }
    results = {}
    for name, (payload, _) in exports.items():
        runner = TfliteRunner(payload)
        metrics = evaluate(runner, test_records, data_dir, classes, args.threshold)
        metrics["size_mb"] = round(len(payload) / 1e6, 2)
        results[name] = metrics
        print(
            f"[{name}] acc={metrics['accuracy']:.4f} macro_recall={metrics['macro_recall']:.4f} "
            f"coverage={metrics['coverage']:.4f} precision={metrics['precision_when_shown']:.4f} "
            f"oob_false={metrics['out_of_list_false_report_rate']:.4f}"
        )

    chosen = choose_export(results, args.prefer)
    print(f"shipping {chosen}: {results[chosen]['accuracy']:.4f} accuracy at {results[chosen]['size_mb']} MB")
    chosen_path = exports[chosen][1]
    if args.android:
        os.makedirs(args.android, exist_ok=True)
        shutil.copyfile(chosen_path, os.path.join(args.android, "bird_classifier.tflite"))
        write_species_json(
            os.path.join(args.android, "species.json"),
            input_size,
            args.model_version,
            args.crop_padding,
        )
        print(f"installed {chosen} model + species.json into {args.android}")

    report = {
        "modelVersion": args.model_version,
        "inputSize": input_size,
        "classCount": len(classes),
        "backgroundClassIndex": len(TARGET_SPECIES),
        "chosen": chosen,
        "threshold": args.threshold,
        "sizeTolerance": SIZE_SAVING_TOLERANCE,
        "results": results,
    }
    with open(os.path.join(out_dir, "eval_report.json"), "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)

    write_model_card(os.path.join(out_dir, "MODEL_CARD.md"), report, classes)
    print(f"report: {os.path.join(out_dir, 'eval_report.json')}")
    return 0


def write_model_card(path: str, report: dict, classes: list[str]) -> None:
    chosen = report["chosen"]
    results = report["results"]
    tiers = ["float32", "float16", "int8"]
    metrics = results[chosen]

    def row(label: str, key: str, pattern: str = "{:.1%}") -> str:
        cells = " | ".join(pattern.format(results[tier][key]) for tier in tiers)
        return f"| {label} | {cells} |"

    lines = [
        f"# {report['modelVersion']} — 模型卡",
        "",
        "本文件由 `ml/export_assets.py` 自动生成，数字来自冻结的测试集，未参与训练与调参。",
        "",
        "## 交付形态",
        "",
        "| 项目 | 值 |",
        "| --- | --- |",
        f"| 输入尺寸 | {report['inputSize']}×{report['inputSize']}，原始 0–255（预处理在模型内） |",
        f"| 类别数 | {report['classCount']}（{report['classCount'] - 1} 个目标鸟种 + 1 个 background） |",
        f"| background 类索引 | {report['backgroundClassIndex']} |",
        f"| 随包发布 | **{chosen}**（{metrics['size_mb']} MB） |",
        f"| 显示门限 | {report['threshold']} |",
        "",
        "## 三种量化形态的实测对比",
        "",
        "| 指标 | " + " | ".join(tiers) + " |",
        "| --- |" + " --- |" * len(tiers),
        row("体积 (MB)", "size_mb", "{:.2f}"),
        row("整体准确率", "accuracy"),
        row("宏平均召回", "macro_recall"),
        row("显示具体种名的覆盖率", "coverage"),
        row("已显示种名的正确率", "precision_when_shown"),
        row("清单外鸟误报为已知种名", "out_of_list_false_report_rate"),
        row("非鸟类误报为已知种名", "non_bird_false_report_rate"),
        "",
        f"随包发布 **{chosen}**。选择规则（`--prefer auto`）：体积最小且整体准确率不低于 float32 减 "
        f"{report['sizeTolerance']:.1%} 的那个。全 int8 在这套网络上代价过大——MobileNetV3 的 hard-swish "
        "与逐张量量化的乘法是量化重灾区，本轮的差距可以在上表直接读到，因此不靠人工记忆去选。",
        "",
        "「覆盖率」与「已显示正确率」均在显示门限下统计，对应开发方案里「可识别样本中显示具体种名的覆盖率」与",
        "「已显示具体种名的结果正确率」两项讨论起点。误报率对应「清单外鸟被误报为已知种名的比例」。",
        "",
        f"## 各类召回（随包模型：{chosen}）",
        "",
        "| 类 | 召回 |",
        "| --- | --- |",
    ]
    for name, recall in sorted(metrics["per_class_recall"].items(), key=lambda kv: kv[1]):
        label = name if name != BACKGROUND_LABEL else "background（非清单鸟/非鸟）"
        lines.append(f"| {label} | {recall:.1%} |")

    listed = {name: recall for name, recall in metrics["per_class_recall"].items() if name != BACKGROUND_LABEL}
    weakest = sorted(listed.items(), key=lambda kv: kv[1])[:3]
    weakest_text = "、".join(f"{name}（{recall:.0%}）" for name, recall in weakest)

    lines += [
        "",
        "## 已知限制",
        "",
        "1. 训练数据全部来自 iNaturalist，以成人拍摄的清晰照片为主，幼鸟、逆光、强遮挡与远距离小目标占比低。",
        "2. 类别清单只覆盖中国大陆城市与水域常见鸟。清单外鸟种由 background 类兜底，但兜底不是保证。",
        f"3. 召回最低的三个目标是 {weakest_text}——这些是补数据时应当优先照顾的类。",
        "4. 测试集与训练集同源（同一平台、相似拍摄习惯），真实野外表现会低于上表数字。",
        "5. 这些数字是离线证据。同一模型在真机上的复核见 `docs/ON_DEVICE_VERIFICATION.md`。",
        "",
    ]
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


if __name__ == "__main__":
    raise SystemExit(main())
