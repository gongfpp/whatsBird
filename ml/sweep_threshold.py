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

"""Sweep the display threshold — and the still-photo score margin — for coverage versus precision.

The app's display threshold decides how often a species name appears at all. Picking it by taste
would be arbitrary, and the acceptance-style numbers in the development plan — coverage of shown
names, how many of them are right, how often an out-of-list bird gets a listed name — are all
functions of this number. This runs the held-out test set once, records each image's top prediction,
its score and the runner-up's score, and then tabulates those numbers at every operating point.

Two operating points matter, not one:

* the **display threshold**, applied on both the preview and the still path;
* the **still margin** (`BirdPipeline.STILL_MARGIN`), applied *only* on the still path, where a
  single frame cannot be outvoted and so a close call is withheld instead. It was introduced at
  0.15 on judgement, and neither the model card nor the threshold sweep ever measured it — every
  number published so far describes margin = 0. Running it here is what turns it into a decision.

Both are measured on whole test photographs (centre-cropped the way training crops), not on detector
crops, so treat the absolute numbers as a lower bound on what the app achieves: the app feeds the
classifier a padded detection box.

Usage:
    python sweep_threshold.py --model out2/classifier_float16.tflite --data data
"""

from __future__ import annotations

import argparse
import json
import os

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np  # noqa: E402

from common_birds import DISTRACTOR_BIRDS  # noqa: E402
from export_assets import TfliteRunner, class_order, load_test_records, make_image_pipeline  # noqa: E402

THRESHOLDS = [round(0.20 + 0.05 * step, 2) for step in range(13)]


def collect(model_path: str, data_dir: str) -> tuple[list[dict], int]:
    runner = TfliteRunner(open(model_path, "rb").read())
    classes = class_order()
    background_index = len(classes) - 1
    target_indices = set(range(background_index))

    rows: list[dict] = []
    records = load_test_records(data_dir)
    pipeline = make_image_pipeline(records, data_dir, runner.input_size)
    for image, record in zip(pipeline, records):
        scores = runner.predict(image.numpy())
        ranked = np.argsort(-scores)
        predicted = int(ranked[0])
        # The runner-up has to be recorded here rather than recomputed later: the still-photo margin
        # asks how far ahead the winner was, and that gap is not recoverable from the winner alone.
        runner_up = float(scores[ranked[1]])
        true_label = record["class_label"]
        true_index = classes.index(true_label) if true_label in classes else background_index
        taxon = record["taxon_name"]
        if true_index != background_index:
            kind = "target"
        elif taxon in DISTRACTOR_BIRDS:
            kind = "distractor"
        else:
            kind = "non_bird"
        rows.append(
            {
                "kind": kind,
                "true_index": true_index,
                "predicted": predicted,
                "score": float(scores[predicted]),
                "runner_up": runner_up,
                "listed_prediction": predicted in target_indices,
                "correct": predicted == true_index,
            }
        )
    return rows, background_index


def summarise(rows: list[dict], threshold: float, margin: float = 0.0) -> dict:
    """Coverage/precision at one operating point.

    [margin] reproduces `BirdPipeline.evaluateStill`: the winner must also lead the runner-up by at
    least this much. At the default 0.0 this is exactly the number the model card publishes.
    """

    def shown(row: dict) -> bool:
        return (
            row["listed_prediction"]
            and row["score"] >= threshold
            and row["score"] - row["runner_up"] >= margin
        )

    targets = [r for r in rows if r["kind"] == "target"]
    distractors = [r for r in rows if r["kind"] == "distractor"]
    non_birds = [r for r in rows if r["kind"] == "non_bird"]
    shown_targets = [r for r in targets if shown(r)]

    return {
        "threshold": threshold,
        "coverage": len(shown_targets) / len(targets) if targets else 0.0,
        "precision": sum(1 for r in shown_targets if r["correct"]) / len(shown_targets) if shown_targets else 0.0,
        "shown_overall": sum(1 for r in rows if shown(r)) / len(rows),
        "out_of_list_false": sum(1 for r in distractors if shown(r)) / len(distractors) if distractors else 0.0,
        "non_bird_false": sum(1 for r in non_birds if shown(r)) / len(non_birds) if non_birds else 0.0,
    }


def recommend(table: list[dict], min_precision: float, max_false: float) -> dict:
    """Highest coverage that still keeps the shown names trustworthy."""
    eligible = [
        row
        for row in table
        if row["precision"] >= min_precision and row["out_of_list_false"] <= max_false and row["coverage"] > 0
    ]
    if eligible:
        return max(eligible, key=lambda row: row["coverage"])
    return max(table, key=lambda row: row["coverage"] if row["precision"] >= min_precision else 0.0)


def recommend_still(
    rows: list[dict],
    thresholds: list[float],
    margins: list[float],
    min_precision: float,
    max_false: float,
) -> tuple[dict, list[dict]]:
    """Searches threshold × margin together.

    Choosing the margin first and the threshold second would hide the trade between them: holding
    precision fixed, a larger margin buys back precision and can therefore pay for a *lower*
    threshold, which is usually the better trade for a still photo — the user pointed the camera at
    one bird on purpose, and "no name" is a worse answer than a hedged one.

    Returns the winning operating point and the per-margin frontier (the best threshold at each
    margin), which is what makes the choice reviewable rather than asserted.
    """
    frontier: list[dict] = []
    for margin in margins:
        table = [summarise(rows, threshold, margin) for threshold in thresholds]
        best = recommend(table, min_precision, max_false)
        frontier.append({"margin": margin, **best})

    eligible = [
        row
        for row in frontier
        if row["precision"] >= min_precision and row["out_of_list_false"] <= max_false and row["coverage"] > 0
    ]
    pick = max(eligible, key=lambda row: row["coverage"]) if eligible else max(frontier, key=lambda r: r["coverage"])
    return pick, frontier


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="out2/classifier_float16.tflite")
    parser.add_argument("--data", default="data")
    parser.add_argument("--min-precision", type=float, default=0.80)
    parser.add_argument("--max-false", type=float, default=0.15)
    parser.add_argument(
        "--margins",
        default="0,0.05,0.10,0.15,0.20",
        help="still-photo score margins to consider",
    )
    parser.add_argument(
        "--rows-out",
        default=None,
        help="dump the per-image rows so later runs can retabulate without another inference pass",
    )
    parser.add_argument(
        "--rows-in",
        default=None,
        help="load per-image rows instead of running the test set (the slow part)",
    )
    parser.add_argument(
        "--grid",
        action="store_true",
        help="print the full threshold x margin surface, not just each margin's best threshold",
    )
    parser.add_argument("--json-out", default=None)
    args = parser.parse_args()

    if args.rows_in:
        with open(args.rows_in, encoding="utf-8") as handle:
            payload = json.load(handle)
        rows = payload["rows"]
        print(f"loaded {len(rows)} rows from {args.rows_in}")
    else:
        rows, _ = collect(args.model, args.data)
        if args.rows_out:
            with open(args.rows_out, "w", encoding="utf-8") as handle:
                json.dump({"rows": rows}, handle)
    accuracy = sum(1 for r in rows if r["correct"]) / len(rows)
    print(f"{len(rows)} test images, top-1 accuracy {accuracy:.3f} (threshold-independent)")
    print()
    print(f"{'thr':>5} {'coverage':>9} {'precision':>10} {'labels shown':>13} {'oob false':>10} {'non-bird false':>15}")
    table = []
    for threshold in THRESHOLDS:
        row = summarise(rows, threshold)
        table.append(row)
        print(
            f"{row['threshold']:>5.2f} {row['coverage']:>9.1%} {row['precision']:>10.1%} "
            f"{row['shown_overall']:>13.1%} {row['out_of_list_false']:>10.1%} {row['non_bird_false']:>15.1%}"
        )

    pick = recommend(table, args.min_precision, args.max_false)
    print()
    print(
        f"recommended threshold {pick['threshold']:.2f}: coverage {pick['coverage']:.1%}, "
        f"precision {pick['precision']:.1%}, out-of-list false reports {pick['out_of_list_false']:.1%} "
        f"(constraints: precision >= {args.min_precision:.0%}, out-of-list false <= {args.max_false:.0%})"
    )

    margins = [float(value) for value in args.margins.split(",") if value.strip()]
    still_pick, frontier = recommend_still(rows, THRESHOLDS, margins, args.min_precision, args.max_false)
    print()
    print("still-photo margin (BirdPipeline.STILL_MARGIN), at each margin's best threshold:")
    print(f"{'margin':>7} {'thr':>5} {'coverage':>9} {'precision':>10} {'oob false':>10}")
    for row in frontier:
        print(
            f"{row['margin']:>7.2f} {row['threshold']:>5.2f} {row['coverage']:>9.1%} "
            f"{row['precision']:>10.1%} {row['out_of_list_false']:>10.1%}"
        )

    if args.grid:
        # The frontier above hides the actual question: the margin is only ever observed to bind on
        # low-scoring samples, because a winner above ~0.6 over 52 classes has already outrun its
        # runner-up. Printing coverage/precision as a pair at every point is what lets someone decide
        # whether the margin earns its keep at the threshold they actually intend to ship.
        print()
        print("coverage / precision at every (threshold, margin):")
        header = " ".join(f"{margin:>13.2f}" for margin in margins)
        print(f"{'thr':>5} {header}")
        for threshold in THRESHOLDS:
            cells = []
            for margin in margins:
                summary = summarise(rows, threshold, margin)
                cells.append(f"{summary['coverage']:>5.1%}/{summary['precision']:>6.1%}")
            print(f"{threshold:>5.2f} " + " ".join(f"{cell:>13}" for cell in cells))

    print()
    print(
        f"recommended still operating point: threshold {still_pick['threshold']:.2f}, "
        f"margin {still_pick['margin']:.2f} -> coverage {still_pick['coverage']:.1%}, "
        f"precision {still_pick['precision']:.1%}"
    )

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as handle:
            json.dump(
                {
                    "accuracy": accuracy,
                    "table": table,
                    "recommended": pick,
                    "still_margin_frontier": frontier,
                    "recommended_still": still_pick,
                },
                handle,
                indent=2,
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
