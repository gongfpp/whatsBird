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

"""Tests for the dataset split and cross-version leakage guards (no TensorFlow needed).

Run with:  python3 ml/test_integrity.py
"""

from __future__ import annotations

import random
import tempfile
import unittest

import dataset_integrity
import fetch_inat


class StableSplitTest(unittest.TestCase):
    """The hash-bucket split must be a pure function of (seed, observation_id)."""

    def test_same_observation_keeps_its_split(self) -> None:
        for observation_id in (1, 42, 123456789, 987654321):
            expected = fetch_inat.split_bucket(observation_id, seed=20260913)
            for _ in range(20):
                self.assertEqual(expected, fetch_inat.split_bucket(observation_id, seed=20260913))

    def test_assignment_is_independent_of_the_set_it_is_called_on(self) -> None:
        """Adding observations later must not move earlier ones across splits.

        The old shuffle-then-cut assignment re-cut every boundary whenever the set changed, which
        is how the same observation drifted from train to test between dataset versions.
        """
        small = [{"observation_id": i} for i in range(1, 200)]
        large = [{"observation_id": i} for i in range(1, 400)]
        before = fetch_inat.split_observations(small, 20260913)
        after = fetch_inat.split_observations(large, 20260913)
        for observation_id, split in before.items():
            self.assertEqual(split, after[observation_id])

    def test_roughly_80_10_10(self) -> None:
        counts = {"train": 0, "val": 0, "test": 0}
        for observation_id in range(1, 20_000):
            counts[fetch_inat.split_bucket(observation_id, 20260913)] += 1
        self.assertLess(abs(counts["train"] / 20_000 - 0.80), 0.02)
        self.assertLess(abs(counts["val"] / 20_000 - 0.10), 0.02)
        self.assertLess(abs(counts["test"] / 20_000 - 0.10), 0.02)

    def test_seed_changes_the_cut(self) -> None:
        # Not every id moves between two seeds — assert on one that provably does.
        self.assertNotEqual(
            fetch_inat.split_bucket(1, 20260913),
            fetch_inat.split_bucket(1, 424242),
        )
class CrossVersionOverlapTest(unittest.TestCase):

    def setUp(self) -> None:
        self.photo_ids = {"train": {1, 2, 3}, "val": {10}, "test": {20}}
        self.observation_ids = {"train": {100}, "val": {200}, "test": {300}}

    def test_detects_a_photo_the_old_model_trained_on_landing_in_test(self) -> None:
        provenance = {"train_photo_ids": [20], "train_observation_ids": []}
        overlap = dataset_integrity.cross_version_overlap(provenance, self.photo_ids, self.observation_ids)
        self.assertIsNotNone(overlap)
        self.assertEqual({20}, overlap[0])
        self.assertEqual(set(), overlap[1])

    def test_detects_an_observation_level_leak(self) -> None:
        provenance = {"train_photo_ids": [1, 2], "train_observation_ids": [300]}
        overlap = dataset_integrity.cross_version_overlap(provenance, self.photo_ids, self.observation_ids)
        self.assertEqual({300}, overlap[1])

    def test_no_overlap_is_clean(self) -> None:
        provenance = {"train_photo_ids": [1, 2, 3], "train_observation_ids": [100]}
        overlap = dataset_integrity.cross_version_overlap(provenance, self.photo_ids, self.observation_ids)
        self.assertEqual((set(), set()), overlap)

    def test_unknown_history_reports_none(self) -> None:
        provenance = {"train_photo_ids": [], "train_observation_ids": []}
        self.assertIsNone(
            dataset_integrity.cross_version_overlap(provenance, self.photo_ids, self.observation_ids)
        )


class ProvenanceRoundTripTest(unittest.TestCase):

    def test_load_provenance_from_beside_checkpoint(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = dataset_integrity.PROVENANCE_FILE
            path = f"{directory}/{checkpoint}"
            with open(path, "w", encoding="utf-8") as handle:
                handle.write('{"train_photo_ids": [7]}')
            loaded = dataset_integrity.load_provenance(f"{directory}/model.keras")
            self.assertEqual([7], loaded["train_photo_ids"])

    def test_missing_provenance_is_none(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.assertIsNone(dataset_integrity.load_provenance(f"{directory}/model.keras"))


class ManifestIdRoundTripTest(unittest.TestCase):

    def test_ids_come_from_the_manifest_not_the_tree(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            # Only a manifest exists on disk (the tree was never materialised); the ids must still
            # be readable, because provenance records what the model was fed, not what remains.
            with open(f"{directory}/manifest.jsonl", "w", encoding="utf-8") as handle:
                handle.write(
                    '{"file": "images/train/A/1.jpg", "split": "train", "photo_id": 1, '
                    '"observation_id": 100}\n'
                    '{"file": "images/test/A/2.jpg", "split": "test", "photo_id": 2, '
                    '"observation_id": 100}\n'
                )
            photo_ids, observation_ids = dataset_integrity.provenance_ids(directory)
            self.assertEqual({1}, photo_ids["train"])
            self.assertEqual({2}, photo_ids["test"])
            self.assertEqual({100}, observation_ids["train"])
            self.assertEqual({100}, observation_ids["test"])


if __name__ == "__main__":
    random.seed(0)
    unittest.main(verbosity=2)
