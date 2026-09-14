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

"""Download a trainable bird dataset from iNaturalist for the whatsBird classifier.

Two properties matter more than volume here:

* **Licence hygiene.** Only photos whose licence allows redistributing a derived model are kept
  (CC0 / CC-BY by default; CC BY-SA is excluded so the weights can stay under CC BY 4.0 — see
  MODEL_LICENSES.md and the DEFAULT_LICENSES comment below). Every kept photo's licence and
  attribution is written to the manifest so the provenance of the training set stays auditable.
* **Split by observation, not by image.** iNaturalist uploads often contain several near-identical
  frames of the same bird. Splitting per photo would leak the same bird into train and test and
  produce a flattering, meaningless accuracy number, so all photos of one observation land in the
  same split.

Usage:
    python fetch_inat.py --out data --per-species 150 --background 600
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, asdict

import dataset_integrity
from common_birds import BACKGROUND_LABEL, DISTRACTOR_BIRDS, NON_BIRD_TAXA, TARGET_SPECIES

API = "https://api.inaturalist.org/v1/observations"
USER_AGENT = "whatsBird-ml/0.1 (offline bird identifier)"

#: iNaturalist serves a handful of derivatives per photo. "small" is ~240px on the long side, which
#: already exceeds the 224px training input; "medium" is 3x the bytes for detail the classifier
#: cannot use, and download throughput from the open-data bucket is latency-bound, so the smaller
#: derivative is both faster and equivalent in quality here.
PHOTO_SIZES = ("square", "small", "medium", "large", "original")
DEFAULT_PHOTO_SIZE = "small"

#: CC BY-SA is deliberately **excluded** by default. The model weights are published under
#: CC BY 4.0 (see MODEL_LICENSES.md), and CC BY-SA 4.0 only allows an adaptation to move to
#: BY-SA 4.0 or a BY-SA-compatible licence such as GPL-3.0 — never to CC BY or Apache-2.0. So if
#: the weights were ever held to be an adaptation of the training photos, a corpus containing
#: BY-SA photos would make the published licence impossible to satisfy. Dropping BY-SA from the
#: default keeps every future retrain clean; pass --licenses to opt back in deliberately.
DEFAULT_LICENSES = ("cc0", "cc-by")

#: Photos whose derivative URL could not be rewritten to the requested size are dropped. The count
#: is reported at the end of the run so an upstream format change is loud instead of silent.
UNREWRITTEN = {"count": 0, "sample": ""}

#: Photos that a second taxon tried to claim and were refused, because one photo may only ever be
#: one (class, split) — see `emit`. Reported at the end so the overlap rate is visible.
SHARED_PHOTOS = {"count": 0}


@dataclass
class PhotoRecord:
    photo_id: int
    observation_id: int
    taxon_name: str
    class_label: str
    split: str
    file: str
    url: str
    license: str
    attribution: str
    observed_on: str | None
    source: str = "iNaturalist"


def http_json(url: str, retries: int = 4) -> dict:
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except (
            urllib.error.URLError,
            http.client.HTTPException,
            TimeoutError,
            json.JSONDecodeError,
        ) as error:
            last_error = error
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"gave up on {url}: {last_error}")


def download(url: str, destination: str, retries: int = 4) -> bool:
    """Fetches one photo, retrying transient failures.

    The exception list is deliberately broad. A truncated response raises
    ``http.client.IncompleteRead``, which is an ``HTTPException`` — not an ``OSError`` — so the
    original ``(URLError, TimeoutError, OSError)`` tuple let one flaky connection escape the retry
    loop, propagate out of the worker thread and kill a 30-minute fetch that was resumable. Anything
    a single image download can throw should cost one image, never the run.
    """
    if os.path.exists(destination) and os.path.getsize(destination) > 1024:
        return True
    for attempt in range(retries):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=90) as response:
                payload = response.read()
            if len(payload) < 1024:
                return False
            tmp = destination + ".part"
            with open(tmp, "wb") as handle:
                handle.write(payload)
            os.replace(tmp, destination)
            return True
        except (
            urllib.error.URLError,
            http.client.HTTPException,
            TimeoutError,
            OSError,
        ) as error:
            if attempt + 1 == retries:
                print(f"    ! download failed: {url}: {error}", file=sys.stderr)
            time.sleep(1.0 * (attempt + 1))
    return False


def quarantine_strays(out_dir: str) -> int:
    """Moves any file the manifest does not place at that exact path into ``<out>/quarantine/``.

    Re-running a fetch after an interruption is the normal way to resume, and ``download`` skips
    files that already exist. If the second run builds a different split — one flaky connection
    changes how many photos of a species survive, which shifts the event boundaries — the first
    run's leftovers stay on disk under whatever split they were originally given. ``train.py`` reads
    directories, not the manifest, so a leftover sitting in ``train/`` for a photo this run assigned
    to ``test/`` would put a test photo into training and quietly inflate every reported number.

    This used to ``os.remove()`` the strays. Two things were wrong with that: deleting is
    irreversible, and a bulk delete large enough to matter is exactly what the safe-delete guard
    stops — so a fetch that left thousands of strays reported success while the tree stayed dirty.
    Moving is reversible and never trips the guard. The rule itself lives in ``dataset_integrity``
    so the same definition is what ``train.py`` enforces before it starts.
    """
    result = dataset_integrity.audit(out_dir)
    if result.ok:
        return 0
    return dataset_integrity.quarantine(out_dir, result, apply=True)


def photo_url(raw_url: str, size: str) -> str | None:
    """Rewrite a derivative URL to another size, keeping the original extension.

    iNaturalist serves derivatives as either ``.jpg`` or ``.jpeg`` depending on the upload, so both
    have to be recognised. Missing this leaves the URL pointing at the 75x75 ``square`` thumbnail,
    which silently poisons training with upscaled postage stamps — so an unrecognised URL returns
    ``None`` and the caller drops the photo rather than downloading a thumbnail.
    """
    for candidate in PHOTO_SIZES:
        for extension in ("jpg", "jpeg"):
            suffix = f"/{candidate}.{extension}"
            if raw_url.endswith(suffix):
                return raw_url[: -len(suffix)] + f"/{size}.{extension}"
    return None


def collect(
    taxon_name: str,
    licenses: tuple[str, ...],
    need: int,
    size: str = DEFAULT_PHOTO_SIZE,
    max_pages: int = 6,
) -> list[dict]:
    """Collect photo dicts for a taxon, grouped so the caller can split by observation."""
    params_base = {
        "taxon_name": taxon_name,
        "photos": "true",
        "quality_grade": "research",
        "photo_license": ",".join(licenses),
        "per_page": "200",
        "order_by": "votes",
    }
    observations: list[dict] = []
    for page in range(1, max_pages + 1):
        params = dict(params_base, page=str(page))
        payload = http_json(f"{API}?{urllib.parse.urlencode(params)}")
        results = payload.get("results", [])
        if not results:
            break
        for observation in results:
            kept = []
            for photo in observation.get("photos", []):
                code = photo.get("license_code")
                if code not in licenses:
                    continue
                resized = photo_url(photo.get("url", ""), size)
                if resized is None:
                    UNREWRITTEN["count"] += 1
                    UNREWRITTEN["sample"] = photo.get("url", "")
                    continue
                kept.append(
                    {
                        "photo_id": photo.get("id"),
                        "url": resized,
                        "license": code,
                        "attribution": (photo.get("attribution") or "").strip(),
                    }
                )
            if not kept:
                continue
            observations.append(
                {
                    "observation_id": observation.get("id"),
                    "taxon_name": taxon_name,
                    "observed_on": observation.get("observed_on"),
                    "photos": kept,
                }
            )
        total_photos = sum(len(o["photos"]) for o in observations)
        if total_photos >= need:
            break
        time.sleep(0.4)
    return observations


def split_observations(observations: list[dict], rng: random.Random) -> dict[int, str]:
    """80/10/10 by observation id, so no bird appears in two splits."""
    ordered = sorted(observations, key=lambda o: o["observation_id"])
    rng.shuffle(ordered)
    count = len(ordered)
    val_start = int(count * 0.8)
    test_start = int(count * 0.9)
    assignment: dict[int, str] = {}
    for index, observation in enumerate(ordered):
        if index < val_start:
            split = "train"
        elif index < test_start:
            split = "val"
        else:
            split = "test"
        assignment[observation["observation_id"]] = split
    return assignment


def emit(
    label: str,
    observations: list[dict],
    out_dir: str,
    limits: dict[str, int],
    rng: random.Random,
    manifest: list[PhotoRecord],
    used_photos: set[int],
    workers: int = 8,
) -> dict[str, int]:
    """Selects up to `limits[split]` photos per split, then downloads them concurrently.

    Metadata calls stay sequential to be polite to the iNaturalist API; only the image fetches —
    which hit a static CDN — are parallelised.

    ``used_photos`` is shared across every call, so a photo can be claimed by exactly one
    (class, split) for the whole run. Splitting is per taxon, and the same photo id legitimately
    shows up under two taxa — recently split sibling species such as *Turdus naumanni* and
    *Turdus eunomus* cross-list each other's photos, and a distractor species can overlap a target
    one. Without this the same image gets written twice: once as training data for species A and
    once as a test case for species B (which is leakage), or twice inside `train/` under two
    different labels (which teaches the model that one image has two names). Callers run targets
    before background pools, so a photo shared with a distractor is kept as the target's.
    """
    assignment = split_observations(observations, rng)
    planned: list[tuple[dict, str, str]] = []
    counts: dict[str, int] = defaultdict(int)

    for observation in observations:
        split = assignment[observation["observation_id"]]
        target_dir = os.path.join(out_dir, "images", split, label)
        for photo in observation["photos"]:
            if photo["photo_id"] in used_photos:
                SHARED_PHOTOS["count"] += 1
                continue
            if counts[split] >= limits.get(split, 10**9):
                break
            counts[split] += 1
            used_photos.add(photo["photo_id"])
            planned.append(({**photo, "_obs": observation, "_split": split}, split, target_dir))

    written: dict[str, int] = defaultdict(int)
    if not planned:
        return dict(written)

    os.makedirs(planned[0][2], exist_ok=True)
    for _, _, directory in planned:
        os.makedirs(directory, exist_ok=True)

    def work(item: tuple[dict, str, str]) -> PhotoRecord | None:
        photo, split, directory = item
        observation = photo["_obs"]
        destination = os.path.join(directory, f"{photo['photo_id']}.jpg")
        if not download(photo["url"], destination):
            return None
        return PhotoRecord(
            photo_id=photo["photo_id"],
            observation_id=observation["observation_id"],
            taxon_name=observation["taxon_name"],
            class_label=label,
            split=split,
            file=os.path.relpath(destination, out_dir),
            url=photo["url"],
            license=photo["license"],
            attribution=photo["attribution"],
            observed_on=observation.get("observed_on"),
        )

    with ThreadPoolExecutor(max_workers=workers) as pool:
        for record in pool.map(work, planned):
            if record is None:
                continue
            written[record.split] += 1
            manifest.append(record)
    return dict(written)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="data", help="dataset root")
    parser.add_argument("--per-species", type=int, default=150)
    parser.add_argument("--background", type=int, default=600)
    parser.add_argument("--per-distractor", type=int, default=12, help="photos per distractor taxon")
    parser.add_argument(
        "--licenses",
        default=",".join(DEFAULT_LICENSES),
        help="comma-separated iNaturalist photo licence codes",
    )
    parser.add_argument("--seed", type=int, default=20260913)
    parser.add_argument(
        "--photo-size",
        default=DEFAULT_PHOTO_SIZE,
        choices=PHOTO_SIZES,
        help="iNaturalist derivative to download; sizes below 'small' are thumbnails",
    )
    parser.add_argument("--workers", type=int, default=24, help="concurrent image downloads")
    parser.add_argument("--only", default=None, help="comma-separated scientific names; debug subset")
    parser.add_argument(
        "--prune",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="quarantine image files this run's manifest does not place at that path (default: on)",
    )
    args = parser.parse_args()

    if args.only:
        # A debug subset deliberately downloads a handful of species, so everything else on disk is
        # unreferenced by definition. Pruning here would delete a good dataset.
        args.prune = False

    licenses = tuple(code.strip() for code in args.licenses.split(",") if code.strip())
    rng = random.Random(args.seed)
    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)

    targets = TARGET_SPECIES
    if args.only:
        wanted = {name.strip() for name in args.only.split(",")}
        targets = tuple(s for s in TARGET_SPECIES if s.scientific_name in wanted)

    manifest: list[PhotoRecord] = []
    #: Claimed photo ids for the whole run, so one photo ends up in exactly one (class, split).
    used_photos: set[int] = set()
    summary: dict[str, dict[str, int]] = {}

    for index, species in enumerate(targets, start=1):
        limits = {
            "train": int(args.per_species * 0.8),
            "val": int(args.per_species * 0.1),
            "test": int(args.per_species * 0.1),
        }
        observations = collect(species.scientific_name, licenses, args.per_species, args.photo_size)
        written = emit(
            species.scientific_name, observations, out_dir, limits, rng, manifest, used_photos, args.workers
        )
        summary[species.scientific_name] = written
        total = sum(written.values())
        short = "!" if total < args.per_species * 0.6 else " "
        print(
            f"{short}[{index:2d}/{len(targets)}] {species.chinese_name:8s} "
            f"{species.scientific_name:32s} train={written.get('train', 0):4d} "
            f"val={written.get('val', 0):3d} test={written.get('test', 0):3d}",
            flush=True,
        )

    # Background: mostly look-alike birds the list does not cover, plus some non-bird taxa.
    bg_limits_per_taxon = {
        "train": max(2, int(args.per_distractor * 0.8)),
        "val": max(1, int(args.per_distractor * 0.1)),
        "test": max(1, int(args.per_distractor * 0.1)),
    }
    for taxon in DISTRACTOR_BIRDS:
        observations = collect(taxon, licenses, args.per_distractor * 2, args.photo_size)
        emit(BACKGROUND_LABEL, observations, out_dir, bg_limits_per_taxon, rng, manifest, used_photos, args.workers)
        print(f"  [bg bird] {taxon}", flush=True)

    non_bird_limits = {"train": max(20, args.background // (6 * 4)), "val": 8, "test": 8}
    for taxon in NON_BIRD_TAXA:
        observations = collect(taxon, licenses, non_bird_limits["train"] * 4, args.photo_size)
        emit(BACKGROUND_LABEL, observations, out_dir, non_bird_limits, rng, manifest, used_photos, args.workers)
        print(f"  [bg non-bird] {taxon}", flush=True)

    manifest_path = os.path.join(out_dir, "manifest.jsonl")
    # Downloading concurrently makes completion order non-deterministic; sort so re-runs and
    # reviews of the manifest are stable.
    manifest.sort(key=lambda r: (r.class_label, r.split, r.photo_id))
    with open(manifest_path, "w", encoding="utf-8") as handle:
        for record in manifest:
            handle.write(json.dumps(asdict(record), ensure_ascii=False) + "\n")

    strays = quarantine_strays(out_dir) if args.prune else 0

    per_split = defaultdict(int)
    per_class = defaultdict(int)
    per_license = defaultdict(int)
    for record in manifest:
        per_split[record.split] += 1
        per_class[record.class_label] += 1
        per_license[record.license] += 1

    stats = {
        "licenses": list(licenses),
        "photo_size": args.photo_size,
        "total": len(manifest),
        "unresizable_dropped": UNREWRITTEN["count"],
        "shared_photo_refusals": SHARED_PHOTOS["count"],
        "per_split": dict(per_split),
        "per_class": dict(sorted(per_class.items(), key=lambda kv: kv[1])),
        "per_license": dict(per_license),
        "per_species": summary,
    }
    with open(os.path.join(out_dir, "fetch_stats.json"), "w", encoding="utf-8") as handle:
        json.dump(stats, handle, ensure_ascii=False, indent=2)

    print(f"\ntotal images: {len(manifest)}  splits: {dict(per_split)}")
    print(f"licences: {dict(per_license)}")
    if strays:
        print(f"quarantined {strays} file(s) left behind by an earlier run (see {out_dir}/quarantine/)")
    if UNREWRITTEN["count"]:
        print(f"dropped (unresizable URL): {UNREWRITTEN['count']}  e.g. {UNREWRITTEN['sample']}")
    if SHARED_PHOTOS["count"]:
        print(
            f"refused {SHARED_PHOTOS['count']} photo(s) already claimed by another class, so no image "
            "sits in two splits or carries two labels"
        )
    print(f"manifest: {manifest_path}")
    thin = [name for name, count in per_class.items() if count < 40]
    if thin:
        print(f"classes under 40 images ({len(thin)}): {', '.join(thin[:10])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
