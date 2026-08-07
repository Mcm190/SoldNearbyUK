#!/usr/bin/env python3
"""
Monthly refresh wrapper around build_seed_data.py.

HM Land Registry republishes each year's Price Paid Data file throughout
the year as transactions get registered (registration lags the actual sale,
sometimes by months), so "updating the dataset" month to month just means
re-pulling the years that are still being added to and rebuilding
seed_prices.db from scratch — there's no incremental/delta format to apply
instead. This script re-runs build_seed_data.py with the same year range
that's currently bundled (10 years, ending this year) so a plain monthly
cron/reminder doesn't need to remember the exact --years list by hand.

Usage:
    python3 tools/update_seed_data.py                  # last 10 years, ending this year
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--years-back", type=int, default=10,
        help="How many years of history to (re)pull, ending this year (default: 10, matching what's currently bundled)",
    )
    parser.add_argument("--outcode", default=None, help="Passed straight through to build_seed_data.py (see its --help)")
    parser.add_argument("--output", default=None, help="Passed straight through to build_seed_data.py (see its --help)")
    args = parser.parse_args()

    current_year = datetime.date.today().year
    years = [str(y) for y in range(current_year - args.years_back + 1, current_year + 1)]

    build_script = Path(__file__).resolve().parent / "build_seed_data.py"
    cmd = [sys.executable, str(build_script), "--years", *years]
    if args.outcode:
        cmd += ["--outcode", args.outcode]
    if args.output:
        cmd += ["--output", args.output]

    print(f"Rebuilding seed_prices.db for {years[0]}-{years[-1]}...", file=sys.stderr)
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
