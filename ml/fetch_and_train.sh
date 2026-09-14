#!/bin/bash
# Fetch (or resume a fetch into) a dataset, verify its splits, then fine-tune the classifier.
#
# Why the integrity step exists: the fetch is resumable by design — `download()` skips any file
# already on disk — so an interrupted run leaves its files behind under whatever split *it* assigned.
# The resumed run computes splits from how many photos per species actually survived, which moves the
# boundaries, so the same photo can end up in `train/` (old run) and `test/` (new manifest) at once.
# `train.py` reads directories, not the manifest, so the model then trains on its own test set. An
# earlier run of exactly this script reported 0.778 val accuracy that was pure leakage: 1217 of 2962
# validation photos were training images, and the number never improved across 14 epochs.
# `train.py` now refuses to start on a tree that disagrees with its manifest, so this check is a
# belt-and-braces duplicate that also *fixes* the tree instead of only reporting it.
#
# Fine-tuning starts from an existing checkpoint rather than from scratch: the head is already trained
# on the same 52 classes and only the data volume changed.
#
# Every python call is `-u`: with stdout on a file rather than a tty, the per-species progress lines
# sit in an 8 KB buffer and a killed run leaves an empty log, which is indistinguishable from a run
# that never started.
#
# Usage: ./fetch_and_train.sh <data-dir> <out-dir> [--init-model <ckpt>] [extra train.py args...]
cd "$(dirname "$0")" || exit 1
PY=${PY:-python3}

DATA=${1:-data2}
OUT=${2:-out4}
shift 2 2>/dev/null || true

echo "=== $(date '+%F %T') fetch into $DATA ==="
"$PY" -u fetch_inat.py --out "$DATA" --per-species 320 --background 1200 --photo-size medium --workers 24
echo "=== $(date '+%F %T') fetch exit=$? ==="

echo "=== $(date '+%F %T') verify splits in $DATA ==="
"$PY" -u dataset_integrity.py --data "$DATA" --apply
integrity=$?
if [ $integrity -ne 0 ]; then
    echo "integrity check failed, refusing to train" >&2
    exit 1
fi

echo "=== $(date '+%F %T') fine-tune into $OUT ==="
"$PY" -u train.py --data "$DATA" --out "$OUT" "$@"
echo "=== $(date '+%F %T') train exit=$? ==="
