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

"""Run the *shipped* models over a photo and print what the app would conclude.

The point is to keep the diagnosis honest. "识别不准" can mean three very different things, and
they need different fixes:

  * the detector never found a bird            -> a detection problem
  * the bird was found but the crop was skipped -> a threshold/size problem in the pipeline
  * the classifier's top-1 was right but the score sat under the display threshold
                                               -> an operating-point problem, not a model problem

Eyeballing the saved `_labeled.jpg` cannot tell those apart, because the app draws "鸟类" for both
"no candidate at all" and "a candidate just under the bar". This script reruns the app's exact
preprocessing — the same detector asset, the same crop padding, the same 224px input, the same
softmax check — and prints the top-5 with the app's verdict for each box.

Usage:
    python diagnose_photo.py <photo> [<photo> ...]
    python diagnose_photo.py --threshold 0.55 --margin 0.15 <photo>
"""

from __future__ import annotations

import argparse
import json
import os
import sys

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

REPO = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.abspath(os.path.join(REPO, os.pardir, "android/app/src/main/assets/models"))
DETECTOR = os.path.join(ASSETS, "bird_detector.tflite")
CLASSIFIER = os.path.join(ASSETS, "bird_classifier.tflite")
DICTIONARY = os.path.join(ASSETS, "species.json")

# Mirrors BirdDetector's still-photo constants.
STILL_SCORE_THRESHOLD = 0.22
MAX_RESULTS = 25
BIRD_CATEGORY = "bird"

# Mirrors BirdPipeline's display rule for a still photo.
DEFAULT_DISPLAY_THRESHOLD = 0.55
DEFAULT_STILL_MARGIN = 0.15


def load_dictionary() -> dict:
    with open(DICTIONARY, encoding="utf-8") as handle:
        return json.load(handle)


def species_name(entry: dict, background_index: int, index: int) -> str:
    if index == background_index:
        return "background（不在清单内）"
    for item in entry["classes"]:
        if item["i"] == index:
            return f"{item['zh']} / {item['en']}"
    return f"<index {index} 不在 species.json 里>"


def detect(image_path: str) -> list:
    """Detects with the bundled asset via the same MediaPipe task the app uses.

    The delegate must be pinned to CPU explicitly. Left alone, the macOS Python wheel picks the
    Metal path and dies inside `TensorsToDetectionsCalculator` with
    `DrishtiMetalHelper ... Check failed: service_ Service is unavailable` — the same calculator
    that aborts the Android app under the GPU delegate. A headless script has no business on the
    GPU anyway, and the detector's answers do not depend on which delegate runs them.
    """
    from mediapipe.tasks import python as mp_python
    from mediapipe.tasks.python import vision
    import mediapipe as mp

    options = vision.ObjectDetectorOptions(
        base_options=mp_python.BaseOptions(
            model_asset_path=DETECTOR,
            delegate=mp_python.BaseOptions.Delegate.CPU,
        ),
        running_mode=vision.RunningMode.IMAGE,
        score_threshold=STILL_SCORE_THRESHOLD,
        max_results=MAX_RESULTS,
    )
    with vision.ObjectDetector.create_from_options(options) as detector:
        result = detector.detect(mp.Image.create_from_file(image_path))

    boxes = []
    for detection in result.detections:
        categories = sorted(detection.categories, key=lambda c: c.score, reverse=True)
        name = (categories[0].category_name or "") if categories else ""
        if name.lower() != BIRD_CATEGORY:
            continue
        bb = detection.bounding_box
        boxes.append(
            {
                "score": float(categories[0].score),
                "box": (bb.origin_x, bb.origin_y, bb.origin_x + bb.width, bb.origin_y + bb.height),
            }
        )
    return boxes


class Classifier:
    """The app's classifier path: float in 0-255, softmax verified, centre-square then scale."""

    def __init__(self) -> None:
        import tensorflow as tf

        with open(CLASSIFIER, "rb") as handle:
            self._interpreter = tf.lite.Interpreter(model_content=handle.read(), num_threads=2)
        self._interpreter.allocate_tensors()
        self._input = self._interpreter.get_input_details()[0]
        self._output = self._interpreter.get_output_details()[0]
        shape = self._input["shape"]
        self.size = int(shape[1])

    def scores(self, crop: Image.Image) -> np.ndarray:
        side = min(crop.size)
        left = (crop.width - side) // 2
        top = (crop.height - side) // 2
        square = crop.crop((left, top, left + side, top + side))
        scaled = square.convert("RGB").resize((self.size, self.size), Image.BILINEAR)

        pixels = np.asarray(scaled, dtype=np.float32)[None, ...]
        self._interpreter.set_tensor(self._input["index"], pixels)
        self._interpreter.invoke()
        raw = self._interpreter.get_tensor(self._output["index"])[0].astype(np.float64)

        # SpeciesClassifier.readScores re-applies softmax when the vector does not look like one.
        if not 0.9 <= raw.sum() <= 1.1:
            shifted = np.exp(raw - raw.max())
            raw = shifted / shifted.sum()
        return raw


def crop_for(image: Image.Image, box: tuple[int, int, int, int], padding_ratio: float) -> Image.Image:
    """BitmapOps.crop: proportional margin, clamped, plus the 24px minimum side."""
    left, top, right, bottom = box
    pad_x = (right - left) * padding_ratio
    pad_y = (bottom - top) * padding_ratio
    left = int(max(0.0, min(1.0, (left - pad_x) / image.width)) * image.width)
    top = int(max(0.0, min(1.0, (top - pad_y) / image.height)) * image.height)
    right = int(min(1.0, max(0.0, (right + pad_x) / image.width)) * image.width)
    bottom = int(min(1.0, max(0.0, (bottom + pad_y) / image.height)) * image.height)
    return image.crop((left, top, right, bottom))


def diagnose(
    path: str,
    classifier: Classifier,
    dictionary: dict,
    threshold: float,
    margin: float,
) -> None:
    image = Image.open(path)
    background_index = dictionary["backgroundClassIndex"]
    padding = dictionary["cropPaddingRatio"]

    print(f"\n=== {os.path.basename(path)}  {image.width}x{image.height} ===")
    boxes = detect(path)
    if not boxes:
        print("检测器没有找到任何鸟（阈值 %.2f）" % STILL_SCORE_THRESHOLD)
        return

    for i, item in enumerate(boxes, 1):
        crop = crop_for(image, item["box"], padding)
        area = (item["box"][2] - item["box"][0]) * (item["box"][3] - item["box"][1]) / (image.width * image.height)
        print(
            f"\n  框{i} 检测={item['score']:.2f} 框={crop.width}x{crop.height}px "
            f"占画面={area * 100:.2f}%"
        )
        if crop.width < 24 or crop.height < 24:
            print("    → 被 BitmapOps.crop 的 24px 下限拒掉：分类器根本不会跑，界面只会显示「鸟类」")
            continue

        scores = classifier.scores(crop)
        order = np.argsort(-scores)[:5]
        top_index, top_score = int(order[0]), float(scores[order[0]])
        second = float(scores[order[1]])
        for rank, index in enumerate(order, 1):
            marker = " " if rank > 1 else "*"
            print(f"    {marker} {scores[index]:6.3f}  {species_name(dictionary, background_index, int(index))}")

        if top_index == background_index:
            verdict = "判定：鸟类（background 胜出，模型认为不在清单内）"
        elif top_score >= threshold and top_score - second >= margin:
            verdict = f"判定：{species_name(dictionary, background_index, top_index)}"
        else:
            reasons = []
            if top_score < threshold:
                reasons.append(f"分数 {top_score:.3f} < 门限 {threshold:.2f}")
            if top_score - second < margin:
                reasons.append(f"与第二名差距 {top_score - second:.3f} < 要求 {margin:.2f}")
            verdict = f"判定：鸟类（{'；'.join(reasons)}）"
        print(f"    → {verdict}")
        if second >= threshold and top_score >= threshold:
            print(f"    （注意：前两名都过了门限，只差区分度——正是 {margin:.2f} 差距门限拦下的情况）")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("photos", nargs="+")
    parser.add_argument("--threshold", type=float, default=DEFAULT_DISPLAY_THRESHOLD)
    parser.add_argument("--margin", type=float, default=DEFAULT_STILL_MARGIN)
    args = parser.parse_args()

    dictionary = load_dictionary()
    classifier = Classifier()
    print(
        f"模型 {dictionary['modelVersion']}，{classifier.size}px，"
        f"门限 {args.threshold:.2f}，差距要求 {args.margin:.2f}"
    )
    for path in args.photos:
        diagnose(path, classifier, dictionary, args.threshold, args.margin)
    return 0


if __name__ == "__main__":
    sys.exit(main())
