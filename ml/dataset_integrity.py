"""Split-integrity auditing for a fetched dataset.

`train.py` builds its datasets with `image_dataset_from_directory`, which reads *directories*.
`fetch_inat.py` is resumable and writes a manifest, so an interrupted fetch leaves the previous
run's files on disk. If the resumed run computes different split boundaries — one flaky connection
changes how many photos of a species survive, which moves the train/val/test cut — the same photo can
sit in `train/` (old run) while the manifest calls it a `test` case (new run). The model then trains
on its own test set and every number the run reports is fiction.

That is not hypothetical. `data2`'s first fetch was killed during the background stage, and the
resumed run left 2283 files in `train/` that its own manifest assigned to `val`/`test` (1121 of them
test cases), while `val/` held 1217 training photos. The run reported 0.778 val accuracy on that
tree; the number measured leakage, not learning.

The manifest is the authority on which split a photo belongs to. These helpers make checking that
cheap enough to run before every training job, and quarantine the offenders instead of deleting them
so an audit is always reversible.

Usage:
    python dataset_integrity.py --data data2            # report only
    python dataset_integrity.py --data data2 --apply    # move offenders to data2/quarantine/
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
from dataclasses import dataclass, field

SPLITS = ("train", "val", "test")
IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png")


@dataclass
class Audit:
    data_dir: str
    manifest_counts: dict[str, int] = field(default_factory=dict)
    #: disk split -> raw file count. This is what `image_dataset_from_directory` reports, and it can
    #: exceed the unique-name count when one photo id was written under two class directories.
    files_on_disk: dict[str, int] = field(default_factory=dict)
    #: disk split -> unique filenames on disk
    on_disk: dict[str, int] = field(default_factory=dict)
    #: disk split -> filenames the manifest does not mention at all
    orphans: dict[str, set[str]] = field(default_factory=dict)
    #: disk split -> {filename: the split the manifest assigns it to}
    misplaced: dict[str, dict[str, str]] = field(default_factory=dict)
    #: filename -> the splits it physically appears in, for files present in more than one split
    cross_split_duplicates: dict[str, list[str]] = field(default_factory=dict)
    #: split -> manifest entries whose file is not on disk. The other direction of the same check:
    #: anything that resolves manifest paths (`export_assets.py`, `sweep_threshold.py`) dies on these.
    missing: dict[str, list[str]] = field(default_factory=dict)
    has_manifest: bool = True

    @property
    def ok(self) -> bool:
        """True when the directories agree with the manifest (or there is no manifest to check)."""
        if not self.has_manifest:
            return True
        if self.cross_split_duplicates:
            return False
        if any(self.missing.get(s) for s in SPLITS):
            return False
        return not any(self.orphans.get(s) for s in SPLITS) and not any(
            self.misplaced.get(s) for s in SPLITS
        )

    @property
    def problem_count(self) -> int:
        return (
            sum(len(v) for v in self.orphans.values())
            + sum(len(v) for v in self.misplaced.values())
            + sum(len(v) for v in self.missing.values())
        )


def manifest_path(data_dir: str) -> str:
    return os.path.join(data_dir, "manifest.jsonl")


def load_records(data_dir: str) -> list[dict]:
    path = manifest_path(data_dir)
    if not os.path.isfile(path):
        return []
    records: list[dict] = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


def load_manifest(data_dir: str) -> dict[str, str]:
    """Maps image filename -> split. The filename is the iNaturalist photo id, so it identifies the
    source photo no matter which split directory a copy happens to sit in."""
    return {os.path.basename(record["file"]): record["split"] for record in load_records(data_dir)}


def scan_tree(data_dir: str) -> dict[str, list[str]]:
    """Every image file per split, duplicates included — a duplicate is itself the finding."""
    found: dict[str, list[str]] = {split: [] for split in SPLITS}
    for split in SPLITS:
        root = os.path.join(data_dir, "images", split)
        if not os.path.isdir(root):
            continue
        for directory, _, filenames in os.walk(root):
            for name in filenames:
                if name.lower().endswith(IMAGE_SUFFIXES):
                    found[split].append(name)
    return found


def audit(data_dir: str) -> Audit:
    records = load_records(data_dir)
    mapping = load_manifest(data_dir)
    found = scan_tree(data_dir)
    result = Audit(
        data_dir=data_dir,
        has_manifest=bool(records),
        manifest_counts=_counts(record["split"] for record in records),
        files_on_disk={split: len(names) for split, names in found.items()},
        on_disk={split: len(set(names)) for split, names in found.items()},
    )
    for split in SPLITS:
        names = set(found[split])
        result.orphans[split] = {name for name in names if name not in mapping}
        result.misplaced[split] = {
            name: mapping[name] for name in names if name in mapping and mapping[name] != split
        }
    where = {split: set(found[split]) for split in SPLITS}
    seen: dict[str, list[str]] = {}
    for name in {name for names in where.values() for name in names}:
        splits = sorted(split for split in SPLITS if name in where[split])
        if len(splits) > 1:
            seen[name] = splits
    result.cross_split_duplicates = seen
    for split in SPLITS:
        result.missing[split] = [
            os.path.normpath(record["file"])
            for record in records
            if record["split"] == split
            and not os.path.isfile(os.path.join(data_dir, os.path.normpath(record["file"])))
        ]
    result.has_manifest = bool(records)
    return result


def _counts(values) -> dict[str, int]:
    counts: dict[str, int] = {}
    for value in values:
        counts[value] = counts.get(value, 0) + 1
    return counts


def format_report(result: Audit) -> str:
    lines: list[str] = []
    if not result.has_manifest:
        lines.append(
            f"no manifest.jsonl under {result.data_dir} — split integrity not checkable "
            "(expected only for throwaway datasets)"
        )
        return "\n".join(lines)
    lines.append(
        f"manifest: {sum(result.manifest_counts.values())} records "
        + ", ".join(f"{k}={v}" for k, v in sorted(result.manifest_counts.items()))
    )
    for split in SPLITS:
        orphans = result.orphans[split]
        misplaced = result.misplaced[split]
        missing = result.missing[split]
        files = result.files_on_disk[split]
        names = result.on_disk[split]
        extra = "" if files == names else f" ({files - names} of them duplicate names)"
        lines.append(
            f"[{split}] {files} files{extra} | orphans {len(orphans)} | "
            f"misplaced {len(misplaced)} | missing {len(missing)}"
        )
        if misplaced:
            by_target = _counts(misplaced.values())
            lines.append(
                "    misplaced photos the manifest assigns elsewhere: "
                + ", ".join(f"{k}={v}" for k, v in sorted(by_target.items()))
                + "  <-- these are LEAKS: the model trains on them"
            )
    if result.cross_split_duplicates:
        sample = list(result.cross_split_duplicates.items())[:3]
        lines.append(
            f"{len(result.cross_split_duplicates)} file(s) exist in more than one split — the same "
            "source photo is both training and test data: "
            + ", ".join(f"{name} in {'/'.join(splits)}" for name, splits in sample)
        )
    if any(result.missing.values()):
        lines.append(
            f"{sum(len(v) for v in result.missing.values())} manifest record(s) point at files that "
            "are not on disk — anything that resolves manifest paths (export_assets.py, "
            "sweep_threshold.py) fails on these; --apply rewrites the manifest to match the tree"
        )
    if result.ok:
        lines.append("split integrity OK: every file on disk matches its manifest split")
    else:
        lines.append(
            f"split integrity BROKEN: {result.problem_count} discrepancies between the tree and the "
            "manifest — any accuracy measured on this tree is inflated"
        )
    return "\n".join(lines)


def reconcile_manifest(data_dir: str) -> tuple[int, int]:
    """Rewrites ``manifest.jsonl`` to list exactly the files present on disk: returns (kept, dropped).

    The tree and the manifest are two halves of the same claim, and fixing one side alone leaves the
    dataset unusable: quarantine a duplicated photo and every manifest-driven consumer — `export_assets.py`,
    `sweep_threshold.py` — dies with ``NotFoundError`` on a path that no longer exists. That is exactly
    how this function came to exist. Whatever the manifest cannot point at is not part of the dataset.
    """
    records = load_records(data_dir)
    if not records:
        return (0, 0)
    keep = [
        record
        for record in records
        if os.path.isfile(os.path.join(data_dir, os.path.normpath(record["file"])))
    ]
    if len(keep) == len(records):
        return (len(keep), 0)
    with open(manifest_path(data_dir), "w", encoding="utf-8") as handle:
        for record in keep:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    return (len(keep), len(records) - len(keep))


def quarantine(data_dir: str, result: Audit, apply: bool = True) -> int:
    """Moves offending files under ``<data_dir>/quarantine/<disk_split>/<class>/``.

    Moving rather than deleting keeps the audit reversible and keeps the cleanup out of the
    bulk-delete confirmation path, where it silently did nothing last time.
    """
    moved = 0
    for split in SPLITS:
        offenders = set(result.orphans[split]) | set(result.misplaced[split])
        if not offenders:
            continue
        root = os.path.join(data_dir, "images", split)
        for directory, _, filenames in os.walk(root):
            for name in filenames:
                if name not in offenders:
                    continue
                source = os.path.join(directory, name)
                relative = os.path.relpath(source, os.path.join(data_dir, "images"))
                target = os.path.join(data_dir, "quarantine", relative)
                if not apply:
                    print(f"  would move {relative}")
                    moved += 1
                    continue
                os.makedirs(os.path.dirname(target), exist_ok=True)
                shutil.move(source, target)
                moved += 1
    if apply:
        _, dropped = reconcile_manifest(data_dir)
        if dropped:
            print(f"dropped {dropped} manifest record(s) whose file is no longer on disk")
    return moved


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="data2")
    parser.add_argument("--apply", action="store_true", help="move offenders instead of only reporting")
    parser.add_argument("--json-out", default=None)
    args = parser.parse_args()

    data_dir = os.path.abspath(args.data)
    result = audit(data_dir)
    print(format_report(result))

    if args.apply and not result.ok:
        print(f"\nrepairing {result.problem_count} discrepancies (quarantine + manifest reconcile)")
        moved = quarantine(data_dir, result, apply=True)
        print(f"moved {moved} files")
        after = audit(data_dir)
        print()
        print(format_report(after))
        result = after
    elif not result.ok:
        print("\nrun again with --apply to quarantine the offenders")

    if args.json_out:
        payload = {
            "data": data_dir,
            "manifest_counts": result.manifest_counts,
            "files_on_disk": result.files_on_disk,
            "on_disk": result.on_disk,
            "orphans": {k: len(v) for k, v in result.orphans.items()},
            "misplaced": {k: len(v) for k, v in result.misplaced.items()},
            "cross_split_duplicates": len(result.cross_split_duplicates),
            "missing": {k: len(v) for k, v in result.missing.items()},
            "ok": result.ok,
        }
        with open(args.json_out, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
