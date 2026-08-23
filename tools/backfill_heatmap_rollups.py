#!/usr/bin/env python3
"""
Rebuilds the heatmap's two rollup tables (sector_stats, postcode_stats) in place, against a
seed_prices.db that has already been ingested.

Both rollups are pure INSERT ... SELECT over sold_properties — nothing about them depends on
the Land Registry CSVs still being around. So when the rollups change but the transactions
don't (a new tier, a different SECTOR_STATS_YEARS, a fixed aggregate), this retrofits the
change onto an existing build in minutes rather than re-running the multi-hour nationwide
ingest in build_seed_data.py.

It reuses build_seed_data's own functions rather than restating their SQL, so the two can't
drift apart. It also rewrites the .version sidecar, which is what makes an already-installed
app re-copy the asset instead of carrying on with its old, structurally-outdated copy.

Usage:
    python3 tools/backfill_heatmap_rollups.py                     # the shipped asset
    python3 tools/backfill_heatmap_rollups.py --db path/to.db     # somewhere else
"""
from __future__ import annotations

import argparse
import sqlite3
import sys
import time
from pathlib import Path

from build_seed_data import (
    SEED_SCHEMA_VERSION,
    build_postcode_stats,
    build_sector_stats,
    rollup_cutoff,
)

DEFAULT_DB = Path(__file__).resolve().parent.parent / "seed_data/src/main/assets/seed_prices.db"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db", type=Path, default=DEFAULT_DB, help=f"database to rebuild (default: {DEFAULT_DB})")
    args = parser.parse_args()

    if not args.db.exists():
        print(f"No such database: {args.db}", file=sys.stderr)
        return 1

    conn = sqlite3.connect(args.db)
    total_rows = conn.execute("SELECT COUNT(*) FROM sold_properties").fetchone()[0]
    cutoff = rollup_cutoff(conn)
    print(f"{args.db} — {total_rows:,} transactions; rollup window starts {cutoff}.", file=sys.stderr)

    # Dropped rather than left alone so this is re-runnable, and so a shrinking window can't
    # leave stale rows behind. Both tables are derived data — there is nothing here to preserve.
    for table in ("sector_stats", "postcode_stats"):
        conn.execute(f"DROP TABLE IF EXISTS {table}")
    conn.commit()

    started = time.monotonic()
    print("Building postcode-sector rollup (coarse heatmap tier)...", file=sys.stderr)
    build_sector_stats(conn, cutoff)
    print("Building per-postcode rollup (fine heatmap tier)...", file=sys.stderr)
    build_postcode_stats(conn, cutoff)

    # The two tiers are two resolutions of one surface, so their totals have to agree exactly.
    # A mismatch means sales were lost or double-counted between them, which would show up as
    # the heatmap changing meaning as it cross-fades — worth failing the build over.
    sector_total = conn.execute("SELECT SUM(sale_count) FROM sector_stats").fetchone()[0]
    postcode_total = conn.execute("SELECT SUM(sale_count) FROM postcode_stats").fetchone()[0]
    if sector_total != postcode_total:
        print(f"FAILED: tier totals disagree — sector_stats {sector_total:,} vs "
              f"postcode_stats {postcode_total:,}.", file=sys.stderr)
        conn.close()
        return 1
    print(f"  Both tiers agree: {sector_total:,} sales in the window.", file=sys.stderr)
    conn.close()

    # Same format build_seed_data writes. The schema version is what makes an existing install
    # notice: the row count is unchanged by a rollup-only rebuild, so on its own it wouldn't.
    version_path = args.db.with_suffix(".version")
    version_path.write_text(f"{SEED_SCHEMA_VERSION}:{total_rows}")
    print(f"Wrote {version_path} = {SEED_SCHEMA_VERSION}:{total_rows} "
          f"(took {time.monotonic() - started:.0f}s).", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
