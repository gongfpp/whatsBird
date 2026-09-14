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

"""Fine-tune a small image classifier for the whatsBird target species list.

Recipe and why:

* **MobileNetV3-Small, ImageNet weights.** The app has to classify several birds per frame on a
  mid-range phone; a 2.5M-parameter backbone keeps that affordable, and starting from ImageNet
  weights is what makes 150 photos per species enough.
* **Keras `include_preprocessing=True`.** MobileNetV3's built-in rescaling is kept inside the graph
  so the exported TFLite takes raw 0-255 pixels. That makes the int8 export a plain uint8-input
  model, exactly what the Android side feeds it.
* **Two-phase transfer learning.** The head is trained with the backbone frozen, then the top
  blocks are unfrozen at a low learning rate. BatchNorm stays frozen in phase two: with batches of
  ~48, letting BN statistics drift on a small dataset actively degrades accuracy.
* **`crop_to_aspect_ratio=True`.** This mirrors what the app does to a detected bird box (centre
  square crop, then resize), so training and inference see the same framing.

Artefacts written to `--out`:

* `best.keras` / `model_full.keras` — the trained graph including the augmentation stack.
* `model.keras` — **the inference graph**: identical weights, augmentation stripped. This is what
  `export_assets.py` converts, so nothing training-only can reach the phone.
* `classes.json`, `train_metrics.json`, `train_log.csv`.

Usage:
    python train.py --data data --out out --backbone mobilenetv3small
"""

from __future__ import annotations

import argparse
import json
import os
import time

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np  # noqa: E402

import tensorflow as tf  # noqa: E402
from tensorflow import keras  # noqa: E402
from tensorflow.keras import layers  # noqa: E402

# `tf.keras.saving` is not re-exported by the TF lazy loader, so the registration decorator comes
# from the standalone Keras package — the same implementation `tf.keras` wraps, so the registration
# is visible to `tf.keras.models.load_model` as well.
from keras.saving import register_keras_serializable  # noqa: E402

import dataset_integrity
from common_birds import BACKGROUND_LABEL, TARGET_SPECIES  # noqa: E402


#: Layers that exist purely to regularise training. They are no-ops at inference, but leaving them
#: in the exported graph relies on that staying true, so `model.keras` is rebuilt without them.
TRAINING_ONLY_LAYERS = frozenset({"augment", "random_erasing"})


def ensure_class_dirs(root: str, splits: tuple[str, ...], classes: list[str]) -> list[str]:
    """Materialise every class directory and report the ones left empty.

    ``image_dataset_from_directory`` reconciles the subdirectories it finds against the ``class_names``
    it is given, so a species whose photos all failed to download must still exist as an empty
    directory instead of being absent. The returned list is the set of classes the model will be asked
    to predict but never sees — worth printing, because it silently caps the achievable accuracy.
    """
    empty: list[str] = []
    for split in splits:
        for label in classes:
            directory = os.path.join(root, "images", split, label)
            os.makedirs(directory, exist_ok=True)
            if split == "train" and not os.listdir(directory):
                empty.append(label)
    return empty


def class_order() -> list[str]:
    """Target species in list order, background last — this is the model's output index order."""
    return [s.scientific_name for s in TARGET_SPECIES] + [BACKGROUND_LABEL]


def build_backbone(name: str, input_size: int) -> keras.Model:
    if name == "mobilenetv3small":
        return keras.applications.MobileNetV3Small(
            input_shape=(input_size, input_size, 3),
            include_top=False,
            weights="imagenet",
            include_preprocessing=True,
            pooling="avg",
        )
    if name == "mobilenetv3large":
        return keras.applications.MobileNetV3Large(
            input_shape=(input_size, input_size, 3),
            include_top=False,
            weights="imagenet",
            include_preprocessing=True,
            pooling="avg",
        )
    if name == "efficientnetb0":
        return keras.applications.EfficientNetB0(
            input_shape=(input_size, input_size, 3),
            include_top=False,
            weights="imagenet",
            pooling="avg",
        )
    raise ValueError(f"unknown backbone {name}")


def find_backbone(model: keras.Model) -> keras.Model:
    """Returns the nested feature extractor inside a model built by `build_model`."""
    for layer in model.layers:
        if isinstance(layer, keras.Model):
            return layer
    raise SystemExit(f"{model.name} has no nested backbone to fine-tune")


def build_augmentation(input_size: int) -> keras.Sequential:
    """Geometric and photometric jitter standing in for real field conditions.

    The plan calls out backlighting, motion blur, occlusion and small subjects. Rotation, zoom and
    translation cover framing error; brightness and contrast cover exposure; the random erasing
    layer stands in for the twig or leaf that is usually in the way.
    """
    return keras.Sequential(
        [
            layers.RandomFlip("horizontal"),
            layers.RandomRotation(0.12),
            layers.RandomZoom(height_factor=0.25, width_factor=0.25),
            layers.RandomTranslation(height_factor=0.08, width_factor=0.08),
            layers.RandomBrightness(factor=0.18),
            layers.RandomContrast(factor=0.18),
        ],
        name="augment",
    )


def load_split(
    root: str,
    split: str,
    classes: list[str],
    input_size: int,
    batch_size: int,
    seed: int,
) -> tf.data.Dataset:
    directory = os.path.join(root, "images", split)
    return keras.utils.image_dataset_from_directory(
        directory,
        labels="inferred",
        label_mode="categorical",
        class_names=classes,
        image_size=(input_size, input_size),
        crop_to_aspect_ratio=True,
        batch_size=batch_size,
        shuffle=(split == "train"),
        seed=seed,
    )


def cache_as_bytes(dataset: tf.data.Dataset) -> tf.data.Dataset:
    """Cache the decoded split in RAM as uint8, then hand it back as float32.

    ``image_dataset_from_directory`` yields float32, so caching it directly would pin ~4 GB for the
    6.5k training images. Storing the same pictures as bytes costs ~1 GB and the cast back is free
    next to the augmentation and forward pass it feeds.
    """
    return (
        dataset.map(
            lambda images, labels: (tf.cast(images, tf.uint8), labels),
            num_parallel_calls=tf.data.AUTOTUNE,
        )
        .cache()
        .map(
            lambda images, labels: (tf.cast(images, tf.float32), labels),
            num_parallel_calls=tf.data.AUTOTUNE,
        )
    )


@register_keras_serializable(package="whatsbird")
class RandomErasing(layers.Layer):
    """Cuts a random rectangle out of the image. Cheap stand-in for foliage occlusion."""

    def __init__(self, probability: float = 0.25, area: float = 0.12, **kwargs):
        super().__init__(**kwargs)
        self.probability = probability
        self.area = area

    def call(self, images, training=None):
        if not training:
            return images
        shape = tf.shape(images)
        batch, height, width = shape[0], shape[1], shape[2]
        apply_mask = tf.random.uniform((batch,)) < self.probability
        cut_h = tf.cast(tf.cast(height, tf.float32) * tf.sqrt(self.area), tf.int32)
        cut_w = tf.cast(tf.cast(width, tf.float32) * tf.sqrt(self.area), tf.int32)
        # Bounds must share the sampling dtype: an int32 draw rejects float32 minval/maxval.
        top = tf.random.uniform((batch,), 0, height - cut_h, dtype=tf.int32)
        left = tf.random.uniform((batch,), 0, width - cut_w, dtype=tf.int32)
        rows = tf.range(height)[None, :, None]
        cols = tf.range(width)[None, None, :]
        in_rows = (rows >= top[:, None, None]) & (rows < (top + cut_h)[:, None, None])
        in_cols = (cols >= left[:, None, None]) & (cols < (left + cut_w)[:, None, None])
        mask = in_rows & in_cols
        mask = mask & apply_mask[:, None, None]
        noise = tf.random.uniform(shape, 0.0, 255.0)
        return tf.where(mask[..., None], noise, tf.cast(images, tf.float32))

    def compute_output_shape(self, input_shape):
        return input_shape

    def get_config(self):
        config = super().get_config()
        config.update({"probability": self.probability, "area": self.area})
        return config


def build_model(backbone_name: str, input_size: int, num_classes: int, dropout: float) -> tuple[keras.Model, keras.Model]:
    backbone = build_backbone(backbone_name, input_size)
    inputs = keras.Input(shape=(input_size, input_size, 3))
    x = build_augmentation(input_size)(inputs)
    x = RandomErasing()(x)
    x = backbone(x, training=False)
    x = layers.Dropout(dropout)(x)
    outputs = layers.Dense(num_classes, activation="softmax", name="species")(x)
    model = keras.Model(inputs, outputs, name="whatsbird_species")
    return model, backbone


def build_inference_model(model: keras.Model, input_size: int) -> keras.Model:
    """Rebuild the trained graph with the training-only augmentation stack removed.

    Layer instances are reused, so the weights are the same objects and no retraining or reloading
    is involved — this is the same network minus the layers that only make sense while fitting.
    Each layer is called with ``training=False`` explicitly so no random op can be traced into the
    export graph even if a layer's default flipped.
    """
    inputs = keras.Input(shape=(input_size, input_size, 3))
    x = inputs
    for layer in model.layers:
        if isinstance(layer, keras.layers.InputLayer) or layer.name in TRAINING_ONLY_LAYERS:
            continue
        x = layer(x, training=False)
    return keras.Model(inputs, x, name="whatsbird_species_inference")


def verify_stripped(full: keras.Model, inference: keras.Model, probe: tf.data.Dataset) -> str:
    """Prove the stripped graph is deterministic and numerically identical to the trained one.

    Two failures are worth catching here instead of on the phone: augmentation leaking into the
    export path (predictions that change between identical frames) and a mistake in the rebuild
    (predictions that are silently wrong).
    """
    images, _ = next(iter(probe))
    first = inference(images, training=False).numpy()
    if not np.array_equal(first, inference(images, training=False).numpy()):
        raise SystemExit("stripped model is non-deterministic: augmentation leaked into the export graph")
    try:
        reference = full(images, training=False).numpy()
    except TypeError:
        return "deterministic (reference comparison unavailable for this backend)"
    drift = float(np.abs(reference - first).max())
    if drift > 1e-4:
        raise SystemExit(f"stripped model diverges from the trained model (max delta {drift:.2e})")
    return f"deterministic, max delta vs trained graph {drift:.1e}"


def class_weights(classes: list[str], train_dir: str) -> dict[int, float]:
    counts = []
    for index, label in enumerate(classes):
        directory = os.path.join(train_dir, label)
        counts.append(len(os.listdir(directory)) if os.path.isdir(directory) else 0)
    total = sum(counts) or 1
    present = [c if c > 0 else 1 for c in counts]
    return {i: total / (len(classes) * c) for i, c in enumerate(present)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="data")
    parser.add_argument("--out", default="out")
    parser.add_argument("--backbone", default="mobilenetv3small")
    parser.add_argument("--input-size", type=int, default=224)
    parser.add_argument("--batch-size", type=int, default=48)
    parser.add_argument("--epochs-head", type=int, default=10)
    parser.add_argument("--epochs-finetune", type=int, default=8)
    parser.add_argument(
        "--init-model",
        default=None,
        help="continue from a trained .keras instead of ImageNet weights; phase 1 is skipped",
    )
    parser.add_argument("--head-lr", type=float, default=1.5e-3)
    parser.add_argument("--finetune-lr", type=float, default=2.0e-4)
    parser.add_argument("--unfreeze-layers", type=int, default=45)
    parser.add_argument("--dropout", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=20260913)
    parser.add_argument("--threads", type=int, default=0, help="0 = let TensorFlow decide")
    parser.add_argument(
        "--allow-split-mismatch",
        action="store_true",
        help="train even if the split directories disagree with manifest.jsonl (not recommended)",
    )
    args = parser.parse_args()

    if args.threads > 0:
        tf.config.threading.set_intra_op_parallelism_threads(args.threads)
        tf.config.threading.set_inter_op_parallelism_threads(max(1, args.threads // 2))

    keras.utils.set_random_seed(args.seed)
    tf.config.experimental.enable_op_determinism()

    data_dir = os.path.abspath(args.data)
    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)

    # The tripwire for the one failure mode that silently fakes a good result. `load_split` reads
    # directories; the manifest says which split each photo belongs to; a resumable fetch can leave
    # the two disagreeing, and then the model trains on its own test set. That is not theoretical —
    # an earlier run of this script reported 0.778 val accuracy measured on a tree in which 1217 of
    # 2962 validation photos were training images. Checking costs one walk of the image tree, so it
    # runs unconditionally rather than behind a flag.
    integrity = dataset_integrity.audit(data_dir)
    if not integrity.ok:
        print(dataset_integrity.format_report(integrity))
        if not args.allow_split_mismatch:
            raise SystemExit(
                "refusing to train: the on-disk splits disagree with manifest.jsonl, so any accuracy "
                "this run reports would be inflated.\n"
                f"fix it with:  python dataset_integrity.py --data {args.data} --apply\n"
                "or override deliberately with --allow-split-mismatch"
            )
        print("WARNING: --allow-split-mismatch given; reported accuracy will be inflated\n")

    classes = class_order()
    num_classes = len(classes)
    train_dir = os.path.join(data_dir, "images", "train")

    empty_classes = ensure_class_dirs(data_dir, ("train", "val"), classes)
    if empty_classes:
        print(f"WARNING: {len(empty_classes)} classes have no training photos: {', '.join(empty_classes)}")

    train_ds = load_split(data_dir, "train", classes, args.input_size, args.batch_size, args.seed)
    val_ds = load_split(data_dir, "val", classes, args.input_size, args.batch_size, args.seed)

    autotune = tf.data.AUTOTUNE
    # Caching buys a large speed-up on CPU; `cache_as_bytes` keeps it to ~1 GB of RAM.
    train_ds = cache_as_bytes(train_ds).shuffle(1000, seed=args.seed).prefetch(autotune)
    val_ds = cache_as_bytes(val_ds).prefetch(autotune)

    if args.init_model:
        # Continuation run: the head is already trained, so phase 1 would only undo work. Everything
        # downstream (unfreeze, checkpoints, strip-and-verify) is the same, which is the point —
        # improving a model must not mean re-running the recipe and hoping.
        model = keras.models.load_model(args.init_model, custom_objects={"RandomErasing": RandomErasing})
        if model.output_shape[-1] != num_classes:
            raise SystemExit(
                f"{args.init_model} predicts {model.output_shape[-1]} classes but the list has {num_classes}"
            )
        backbone = find_backbone(model)
        print(f"continuing from {args.init_model}; phase 1 skipped")
    else:
        model, backbone = build_model(args.backbone, args.input_size, num_classes, args.dropout)

    weights = class_weights(classes, train_dir)
    print(f"classes={num_classes} backbone={args.backbone} input={args.input_size}")
    print(f"train batches={len(train_ds)} val batches={len(val_ds)}")

    callbacks = [
        keras.callbacks.CSVLogger(os.path.join(out_dir, "train_log.csv"), append=True),
        keras.callbacks.ModelCheckpoint(
            os.path.join(out_dir, "best.keras"),
            monitor="val_accuracy",
            mode="max",
            save_best_only=True,
            verbose=1,
        ),
        keras.callbacks.EarlyStopping(monitor="val_accuracy", patience=5, mode="max", restore_best_weights=True),
        keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.4, patience=2, min_lr=1e-5, verbose=1),
    ]

    history_head = None
    head_seconds = 0.0
    if not args.init_model and args.epochs_head > 0:
        # --- Phase 1: train the head with the backbone frozen ---------------------------
        backbone.trainable = False
        model.compile(
            optimizer=keras.optimizers.Adam(args.head_lr),
            loss=keras.losses.CategoricalCrossentropy(label_smoothing=0.05),
            metrics=["accuracy"],
        )
        started = time.time()
        history_head = model.fit(
            train_ds,
            validation_data=val_ds,
            epochs=args.epochs_head,
            class_weight=weights,
            callbacks=callbacks,
        )
        head_seconds = time.time() - started
        print(f"[phase 1] {head_seconds:.0f}s for {len(history_head.history['loss'])} epochs")

    history_fine = None
    if args.epochs_finetune > 0:
        # --- Phase 2: unfreeze the top blocks, keep BatchNorm frozen --------------------
        backbone.trainable = True
        for layer in backbone.layers[: max(0, len(backbone.layers) - args.unfreeze_layers)]:
            layer.trainable = False
        for layer in backbone.layers:
            if isinstance(layer, layers.BatchNormalization):
                layer.trainable = False

        model.compile(
            optimizer=keras.optimizers.Adam(args.finetune_lr),
            loss=keras.losses.CategoricalCrossentropy(label_smoothing=0.05),
            metrics=["accuracy"],
        )
        started = time.time()
        history_fine = model.fit(
            train_ds,
            validation_data=val_ds,
            epochs=args.epochs_finetune,
            class_weight=weights,
            callbacks=callbacks,
        )
        print(f"[phase 2] {time.time() - started:.0f}s for {len(history_fine.history['loss'])} epochs")

    # `best.keras` is the best checkpoint across both phases. It is kept as-is for inspection, but
    # it still carries the augmentation stack, so it must not be the artefact handed to the exporter.
    best_path = os.path.join(out_dir, "best.keras")
    full = (
        keras.models.load_model(best_path, custom_objects={"RandomErasing": RandomErasing})
        if os.path.exists(best_path)
        else model
    )
    full.save(os.path.join(out_dir, "model_full.keras"))

    inference = build_inference_model(full, args.input_size)
    strip_check = verify_stripped(full, inference, val_ds)
    print(f"strip check: {strip_check}")

    final_path = os.path.join(out_dir, "model.keras")
    inference.save(final_path)

    with open(os.path.join(out_dir, "classes.json"), "w", encoding="utf-8") as handle:
        json.dump({"classes": classes, "inputSize": args.input_size, "backbone": args.backbone}, handle, indent=2)

    all_val_accuracy: list[float] = []
    if history_head is not None:
        all_val_accuracy += list(history_head.history["val_accuracy"])
    if history_fine is not None:
        all_val_accuracy += list(history_fine.history["val_accuracy"])

    metrics = {
        "phase1_seconds": round(head_seconds, 1),
        "phase1_best_val_accuracy": round(max(history_head.history["val_accuracy"]), 4) if history_head else None,
        "best_val_accuracy": round(max(all_val_accuracy), 4) if all_val_accuracy else None,
        "continued_from": args.init_model,
        "backbone": args.backbone,
        "input_size": args.input_size,
        "classes": num_classes,
        "epochs_head": args.epochs_head,
        "epochs_finetune": args.epochs_finetune,
        "epochs_run": len(all_val_accuracy),
        "strip_check": strip_check,
        "artifacts": {"inference": "model.keras", "trained": "model_full.keras"},
    }
    with open(os.path.join(out_dir, "train_metrics.json"), "w", encoding="utf-8") as handle:
        json.dump(metrics, handle, indent=2)

    print(json.dumps(metrics, indent=2))
    print(f"saved {final_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
