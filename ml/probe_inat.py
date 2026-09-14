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

"""Report how many trainable photos iNaturalist has for each target species.

Run this before fetching anything: it answers "is this species list trainable at all?" in under a
minute, instead of discovering a data hole after a long download.

Usage:
    python probe_inat.py [--licenses cc0,cc-by,cc-by-sa]
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from common_birds import TARGET_SPECIES

API = "https://api.inaturalist.org/v1/observations"
USER_AGENT = "whatsBird-ml/0.1 (offline bird identifier; contact: project owner)"


def count(scientific_name: str, licenses: str) -> tuple[int, int]:
    """Returns (research-grade observations with photos under `licenses`, all such photos)."""
    params = {
        "taxon_name": scientific_name,
        "photos": "true",
        "quality_grade": "research",
        "photo_license": licenses,
        "per_page": "0",
    }
    url = f"{API}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=45) as response:
        payload = json.load(response)
    return payload.get("total_results", 0), 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--licenses",
        default="cc0,cc-by,cc-by-sa",
        help="comma-separated iNaturalist photo licence codes",
    )
    parser.add_argument("--threshold", type=int, default=120, help="minimum observations to train on")
    args = parser.parse_args()

    rows: list[tuple[str, str, int, bool]] = []
    for species in TARGET_SPECIES:
        # be gentle with the API
        time.sleep(0.35)
        try:
            total, _ = count(species.scientific_name, args.licenses)
        except (urllib.error.URLError, TimeoutError) as error:
            print(f"  ! {species.scientific_name}: {error}", file=sys.stderr)
            total = -1
        ok = total >= args.threshold
        rows.append((species.scientific_name, species.chinese_name, total, ok))

    rows.sort(key=lambda row: row[2])
    print(f"{'scientific name':38s} {'中文':10s} {'obs':>7s}  trainable")
    for scientific_name, chinese, total, ok in rows:
        marker = "yes" if ok else "NO"
        print(f"{scientific_name:38s} {chinese:10s} {total:7d}  {marker}")

    thin = [row for row in rows if not row[3]]
    print(f"\ntotal species: {len(rows)}; below threshold ({args.threshold}): {len(thin)}")
    if thin:
        print("thin: " + ", ".join(f"{r[1]}({r[2]})" for r in thin))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
