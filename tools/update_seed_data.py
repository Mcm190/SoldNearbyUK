#!/usr/bin/env python3
"""
Monthly refresh wrapper around build_seed_data.py.

HM Land Registry republishes each year's Price Paid Data file throughout
the year as transactions get registered (registration lags the actual sale,
sometimes by months), so "updating the dataset" month to month just means
re-pulling the years that are still being added to and rebuilding
seed_prices.db from scratch — there's no incremental/delta format to apply
instead.

The window is a true rolling 10 years *to the month*, not calendar years:
running this today drops everything older than "10 years ago this month"
and picks up whatever new months Land Registry has published since last
time — so each monthly run both adds the newest data and retires the
oldest month, keeping the dataset a constant ~10-year span rather than
growing by a year at a time. Because the underlying CSVs are one file per
calendar year, this still has to download every year touched by the
window, but --min-date (passed to build_seed_data.py) trims the oldest
year down to just the months still inside the window.

Usage:
    python3 tools/update_seed_data.py                  # rolling 10 years, ending this month
    python3 tools/update_seed_data.py --years-back 5    # narrower window
    python3 tools/update_seed_data.py --outcode OX4     # fast local test of the refresh

See "Monthly dataset updates & publishing an update" in README.md for the
full release checklist (version bump, rebuild, upload) after this finishes.
"""
from __future__ import annotations

import argparse
import datetime
import subprocess
import sys
from pathlib import Path


def rolling_min_date(years_back: int, today: datetime.date) -> datetime.date:
    """First day of the month, `years_back` years before `today`.

    E.g. today=2026-08-20, years_back=10 -> 2016-08-01: everything from
    that month onward stays in the window, everything before it is dropped.
    """
    return datetime.date(today.year - years_back, today.month, 1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--years-back", type=int, default=10,
        help="Width of the rolling window in years, ending this month (default: 10)",
    )
    parser.add_argument("--outcode", default=None, help="Passed straight through to build_seed_data.py (see its --help)")
    parser.add_argument("--output", default=None, help="Passed straight through to build_seed_data.py (see its --help)")
    args = parser.parse_args()

    today = datetime.date.today()
    min_date = rolling_min_date(args.years_back, today)
    years = [str(y) for y in range(min_date.year, today.year + 1)]

    build_script = Path(__file__).resolve().parent / "build_seed_data.py"
    cmd = [
        sys.executable, str(build_script),
        "--years", *years,
        "--min-date", min_date.isoformat(),
    ]
    if args.outcode:
        cmd += ["--outcode", args.outcode]
    if args.output:
        cmd += ["--output", args.output]

    print(f"Rebuilding seed_prices.db for {min_date.isoformat()} through {today.isoformat()}...", file=sys.stderr)
    subprocess.run(cmd, check=True)

    print(
        "\nDataset refreshed. Next steps to ship this month's update — see README.md's "
        "\"Monthly dataset updates & publishing an update\" section for detail:\n"
        "  1. Bump versionCode (and versionName) in app/build.gradle.kts.\n"
        "  2. ./gradlew bundleRelease   (or assembleRelease for a sideload APK)\n"
        "  3. Upload app/build/outputs/bundle/release/app-release.aab to Play Console.",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
