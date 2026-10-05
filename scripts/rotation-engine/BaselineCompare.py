#!/usr/bin/env python3
"""Capture or compare the report CSVs used by rotation-engine baseline gates."""

from __future__ import annotations

import argparse
import csv
import math
import shutil
import sys
from pathlib import Path


REPORTS = (
    "equity",
    "tradebook",
    "holdings",
    "trade_ledger",
    "daily_portfolio",
    "yearly",
    "rebalances",
)


def report_paths(directory: Path, prefix: str) -> list[Path]:
    return [directory / f"{prefix}_{name}.csv" for name in REPORTS]


def capture(source: Path, destination: Path, prefix: str) -> int:
    sources = report_paths(source, prefix)
    missing = [path for path in sources if not path.is_file()]
    if missing:
        for path in missing:
            print(f"MISSING {path}", file=sys.stderr)
        return 1

    destination.mkdir(parents=True, exist_ok=True)
    for path in sources:
        shutil.copyfile(path, destination / path.name)
        print(f"CAPTURED {path.name}")
    return 0


def as_float(value: str) -> float | None:
    try:
        return float(value)
    except ValueError:
        return None


def compare_file(baseline: Path, candidate: Path, tolerance: float) -> tuple[bool, int, float, str]:
    if not baseline.is_file() or not candidate.is_file():
        return False, 0, 0.0, "missing file"

    with baseline.open(newline="", encoding="utf-8") as left_file:
        left_rows = list(csv.reader(left_file))
    with candidate.open(newline="", encoding="utf-8") as right_file:
        right_rows = list(csv.reader(right_file))

    if not left_rows or not right_rows:
        return False, 0, 0.0, "empty CSV"
    if left_rows[0] != right_rows[0]:
        return False, max(0, len(left_rows) - 1), 0.0, "header or column order differs"
    if len(left_rows) != len(right_rows):
        return False, max(0, len(left_rows) - 1), 0.0, (
            f"row count differs: baseline={len(left_rows) - 1}, candidate={len(right_rows) - 1}"
        )

    max_delta = 0.0
    mismatch_count = 0
    first_mismatch = ""
    for row_index, (left_row, right_row) in enumerate(zip(left_rows[1:], right_rows[1:]), start=2):
        if len(left_row) != len(right_row):
            mismatch_count += 1
            if not first_mismatch:
                first_mismatch = f"row {row_index}: column count differs"
            continue
        for column_index, (left_value, right_value) in enumerate(zip(left_row, right_row)):
            left_number = as_float(left_value)
            right_number = as_float(right_value)
            if left_number is not None and right_number is not None:
                if math.isnan(left_number) and math.isnan(right_number):
                    continue
                delta = abs(left_number - right_number)
                max_delta = max(max_delta, delta)
                equal = delta <= tolerance
            else:
                equal = left_value == right_value
            if not equal:
                mismatch_count += 1
                if not first_mismatch:
                    first_mismatch = (
                        f"row {row_index}, column {column_index + 1}: "
                        f"{left_value!r} != {right_value!r}"
                    )

    return mismatch_count == 0, len(left_rows) - 1, max_delta, (
        first_mismatch if mismatch_count else ""
    )


def compare(baseline: Path, candidate: Path, prefix: str, tolerance: float) -> int:
    failures = 0
    for path in report_paths(baseline, prefix):
        candidate_path = candidate / path.name
        passed, row_count, max_delta, detail = compare_file(path, candidate_path, tolerance)
        state = "PASS" if passed else "FAIL"
        suffix = f"; {detail}" if detail else ""
        print(f"{state} {path.name}: {row_count} rows, max numeric delta={max_delta:.12g}{suffix}")
        failures += not passed
    print(f"Result: {'PASS' if failures == 0 else f'{failures} report(s) differ'}")
    return 0 if failures == 0 else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    capture_parser = subparsers.add_parser("capture", help="copy the seven gate CSVs into a baseline folder")
    capture_parser.add_argument("source", type=Path)
    capture_parser.add_argument("destination", type=Path)
    capture_parser.add_argument("--prefix", default="rotation")

    compare_parser = subparsers.add_parser("compare", help="compare candidate reports to a baseline")
    compare_parser.add_argument("baseline", type=Path)
    compare_parser.add_argument("candidate", type=Path)
    compare_parser.add_argument("--prefix", default="rotation")
    compare_parser.add_argument("--tolerance", type=float, default=1e-9)

    args = parser.parse_args()
    if args.command == "capture":
        return capture(args.source, args.destination, args.prefix)
    if args.tolerance < 0.0:
        parser.error("--tolerance must be non-negative")
    return compare(args.baseline, args.candidate, args.prefix, args.tolerance)


if __name__ == "__main__":
    raise SystemExit(main())