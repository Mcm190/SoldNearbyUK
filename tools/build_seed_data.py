#!/usr/bin/env python3
"""
Builds seed_data/src/main/assets/seed_prices.db — the sold-price dataset the
app ships with, packaged in its own Play Asset Delivery install-time asset
pack module rather than app/src/main/assets. Google Play's 200MB base-module
download cap is well below this dataset's size on its own; see
seed_data/build.gradle.kts for why.

Data sources (both free and used within their terms):
  * HM Land Registry Price Paid Data — actual registered sale prices for
    every residential transaction in England & Wales. Crown copyright and
    database right, licensed under the Open Government Licence v3.0:
    https://www.nationalarchives.gov.uk/doc/open-government-licence/
    This is the same underlying data Rightmove/Zoopla show as "sold prices" —
    pulling it directly means no scraping, no ToS risk, and it's the
    authoritative source rather than a portal's cached copy of it.
  * ONS Postcode Directory (ONSPD) — the official postcode-to-coordinate
    lookup for the whole UK, published quarterly by ONS Geography. Downloaded
    once and cached in tools/onspd_cache/, then joined locally — this avoids
    making a million+ live requests to any API for a nationwide build.
    Contains Royal Mail, Ordnance Survey, and ONS IP; free to use per the
    Open Government Licence.

Coverage is bounded by Land Registry: England & Wales only. Scotland has an
equivalent open dataset from Registers of Scotland; Northern Ireland's Land
& Property Services publishes a price index but not the same granular
per-transaction open data — see README.md.

Usage:
    python3 tools/build_seed_data.py --outcode OX4      # one postcode area, fast
    python3 tools/build_seed_data.py                     # all of England & Wales
    python3 tools/build_seed_data.py --years 2023 2024 2025

Each year is streamed, geocoded, and written to SQLite before moving to the
next — nothing accumulates in memory across years. Multi-decade pulls (e.g.
--years 1995..2025, ~30 million rows) hold roughly one year's worth of rows
plus the ~2.7M-entry ONSPD lookup in memory at once, not the full dataset —
an earlier version of this script buffered every year in a Python list
before writing anything, which silently got OOM-killed partway through a
31-year run.

The ONSPD download URL below points at a specific quarterly edition (May
2026) and will need updating once a newer one is published — ONS issues a
new ArcGIS item (new URL) each quarter rather than updating one in place.
Find the current one at https://www.arcgis.com/sharing/rest/search?q=ONS%20Postcode%20Directory%20AND%20owner:ONSGeography_data&f=json
(look for the "CSV Collection" item, then its /data endpoint).
"""
from __future__ import annotations

import argparse
import csv
import datetime
import sqlite3
import sys
import zipfile
from pathlib import Path
import urllib.error
import urllib.request

PPD_URL_TEMPLATE = "https://price-paid-data.publicdata.landregistry.gov.uk/pp-{year}.csv"
ONSPD_URL = "https://www.arcgis.com/sharing/rest/content/items/6fff67d204fd4f339591ed667a6e3642/data"
USER_AGENT = "SoldNearby/0.1 (dev build; https://github.com/)"
INSERT_BATCH_SIZE = 50_000

# Bumped whenever the *shape* of the database changes (a new table, a new column, a new index) —
# as opposed to just its contents. The .version sidecar the app compares against is otherwise
# derived purely from the row count, which doesn't move when a schema change leaves the same
# transactions in place; an install that already had a copy would then keep using its old,
# structurally-outdated one and blow up on the first query that touches whatever was added.
# See PriceDatabase.open().
SEED_SCHEMA_VERSION = 2

# How far back sector_stats aggregates, in years. Anchored to the newest sale actually in the
# dataset rather than to today's date, for the same reason PropertyRepository is: the bundled
# data doesn't necessarily reach up to today, so a "today minus 2 years" cutoff could exclude
# everything. Kept in step with the window PropertyRepository.salesDensityBySector documents.
SECTOR_STATS_YEARS = 2

INSERT_SQL = """
    INSERT INTO sold_properties
        (address, postcode, price_gbp, date_of_transfer, property_type, new_build, tenure, latitude, longitude)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

# HM Land Registry Price Paid Data column order. The CSV has no header row.
# https://www.gov.uk/guidance/about-the-price-paid-data#data-field-descriptions
PPD_COLUMNS = [
    "transaction_id", "price", "date_of_transfer", "postcode", "property_type",
    "new_build", "tenure", "paon", "saon", "street", "locality", "town_city",
    "district", "county", "ppd_category_type", "record_status",
]


def matches_outcode(postcode: str, outcode: str) -> bool:
    parts = postcode.strip().upper().split(" ")
    return len(parts) == 2 and parts[0] == outcode


def download_onspd(cache_dir: Path) -> Path:
    """Downloads and extracts ONSPD into cache_dir, unless already cached there."""
    csv_dir = cache_dir / "Data" / "multi_csv"
    if csv_dir.is_dir() and any(csv_dir.glob("*.csv")):
        print(f"Using cached ONSPD at {csv_dir}", file=sys.stderr)
        return csv_dir

    cache_dir.mkdir(parents=True, exist_ok=True)
    zip_path = cache_dir / "onspd.zip"
    print("Downloading ONS Postcode Directory (~235MB, one-time, cached afterwards)...", file=sys.stderr)
    req = urllib.request.Request(ONSPD_URL, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=120) as response, open(zip_path, "wb") as out:
        while chunk := response.read(1024 * 1024):
            out.write(chunk)
    print("Extracting...", file=sys.stderr)
    with zipfile.ZipFile(zip_path) as zf:
        zf.extractall(cache_dir)
    zip_path.unlink()
    return csv_dir


def load_onspd_lookup(csv_dir: Path) -> dict[str, tuple[float, float]]:
    """Returns {postcode: (lat, lon)} for every UK postcode ONSPD has coordinates for."""
    lookup: dict[str, tuple[float, float]] = {}
    csv_files = sorted(csv_dir.glob("*.csv"))
    print(f"Loading {len(csv_files)} ONSPD area files...", file=sys.stderr)
    for i, csv_file in enumerate(csv_files):
        with open(csv_file, newline="", encoding="utf-8-sig") as f:
            for row in csv.DictReader(f):
                lat_str, lng_str = row["lat"], row["long"]
                if not lat_str or not lng_str:
                    continue  # a small number of non-geographic postcodes have no grid ref
                lookup[row["pcds"].strip().upper()] = (float(lat_str), float(lng_str))
        if (i + 1) % 20 == 0:
            print(f"  loaded {i + 1}/{len(csv_files)} files, {len(lookup):,} postcodes so far...", file=sys.stderr)
    print(f"Loaded {len(lookup):,} postcodes total.", file=sys.stderr)
    return lookup


def stream_transactions(year: int, outcode: str | None):
    """Streams pp-<year>.csv, optionally filtered to one postcode outcode.

    Filters while streaming rather than buffering the whole ~150MB/year file.
    """
    url = PPD_URL_TEMPLATE.format(year=year)
    scope = f"postcode area {outcode!r}" if outcode else "all of England & Wales"
    print(f"Streaming {url} ({scope})...", file=sys.stderr)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    scanned = matched = 0
    with urllib.request.urlopen(req, timeout=60) as response:
        text_stream = (line.decode("utf-8", errors="replace") for line in response)
        for row in csv.reader(text_stream):
            scanned += 1
            if len(row) != len(PPD_COLUMNS):
                continue
            record = dict(zip(PPD_COLUMNS, row))
            if outcode and not matches_outcode(record["postcode"], outcode):
                continue
            matched += 1
            yield record
            if scanned % 500_000 == 0:
                print(f"  scanned {scanned:,} rows, matched {matched:,} so far...", file=sys.stderr)
    print(f"  finished streaming {year}: scanned {scanned:,} rows, matched {matched:,}.", file=sys.stderr)


def format_address(record: dict) -> str:
    house_and_street = " ".join(p for p in (record["paon"], record["street"]) if p).strip()
    parts = [p for p in (record["saon"], house_and_street) if p]
    if record["locality"] and record["locality"].upper() != record["town_city"].upper():
        parts.append(record["locality"])
    if record["town_city"]:
        parts.append(record["town_city"])
    return ", ".join(part.title() for part in parts if part)


def to_row(record: dict, latlng: tuple[float, float]) -> tuple:
    return (
        format_address(record),
        record["postcode"].strip().upper(),
        int(record["price"]),
        record["date_of_transfer"][:10],
        record["property_type"],
        1 if record["new_build"].upper() == "Y" else 0,
        record["tenure"],
        latlng[0],
        latlng[1],
    )


def create_schema(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE sold_properties (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            address TEXT NOT NULL,
            postcode TEXT NOT NULL,
            price_gbp INTEGER NOT NULL,
            date_of_transfer TEXT NOT NULL,
            property_type TEXT NOT NULL,
            new_build INTEGER NOT NULL,
            tenure TEXT NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL
        )
        """
    )


def build_sector_stats(conn: sqlite3.Connection) -> int:
    """Precomputes the per-postcode-sector rollup behind the app's heatmap.

    The app used to derive this live, running the GROUP BY below over whatever was in the
    viewport on every single camera-idle event. That's fine at street level, but the cost
    scales with the area on screen rather than with the number of sectors returned, and
    zoomed out it became a scan of a large fraction of the whole table: measured against a
    9.7M-row build, ~1.6s for Greater London, 4.3s for the south east, and 18.1s for a view
    covering most of England — on a desktop SSD with a warm cache, so substantially worse on
    a phone. Since the app opens this file read-only and non-WAL, Android gives it a
    connection pool of exactly one, so each of those scans also blocked every other query
    behind it; a zoomed-out pan could leave the map showing a spinner long after the user had
    stopped moving and zoomed back in.

    Nothing about the result actually varies at runtime — the window is fixed, it ignores the
    app's "recent sales only" setting by design, and the app weights purely on sale_count — so
    the entire query exists to re-derive constants. Precomputing it collapses the whole thing
    to ~8k rows (a few hundred KB), small enough that the app loads the table into memory once
    and filters it there, never touching SQLite for the heatmap again at any zoom level.

    One deliberate behaviour change: a sector's count is now its true total, whereas the live
    query only counted the rows inside the viewport, so a sector straddling the edge of the
    screen used to report a partial count and a centroid dragged toward the visible part —
    both of which shifted as you panned.
    """
    max_date = conn.execute("SELECT MAX(date_of_transfer) FROM sold_properties").fetchone()[0]
    cutoff = datetime.date.fromisoformat(max_date).replace(
        year=datetime.date.fromisoformat(max_date).year - SECTOR_STATS_YEARS
    ).isoformat()

    conn.execute(
        """
        CREATE TABLE sector_stats (
            sector TEXT PRIMARY KEY,
            sale_count INTEGER NOT NULL,
            average_price_gbp REAL NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL
        )
        """
    )
    # A full postcode is always "outcode, space, one digit, two letters" (e.g. "SW1A 1AA"), so
    # dropping the last two characters gives the sector regardless of the outcode's own variable
    # length ("SW1A 1AA" -> "SW1A 1", "OX4 1AB" -> "OX4 1").
    conn.execute(
        """
        INSERT INTO sector_stats (sector, sale_count, average_price_gbp, latitude, longitude)
        SELECT substr(postcode, 1, length(postcode) - 2) AS sector,
               COUNT(*), AVG(price_gbp), AVG(latitude), AVG(longitude)
        FROM sold_properties
        WHERE date_of_transfer >= ?
        GROUP BY sector
        """,
        (cutoff,),
    )
    conn.commit()
    count = conn.execute("SELECT COUNT(*) FROM sector_stats").fetchone()[0]
    print(f"  {count:,} sectors, aggregated over sales since {cutoff}.", file=sys.stderr)
    return count


def ingest_year(
    conn: sqlite3.Connection,
    year: int,
    outcode: str | None,
    coords: dict[str, tuple[float, float]],
    min_date: str | None = None,
    max_date: str | None = None,
) -> tuple[int, int]:
    """Streams, geocodes, and writes one year's transactions, then commits.

    Never holds more than one batch of rows in memory — this is what lets a
    multi-decade pull run in bounded memory regardless of how many years
    are requested. min_date/max_date (inclusive, "YYYY-MM-DD") filter rows
    by date_of_transfer so a rolling window can start/end mid-year without
    needing to touch the CSV year boundaries themselves.
    """
    batch: list[tuple] = []
    written = skipped_geo = skipped_date = 0
    for record in stream_transactions(year, outcode):
        transfer_date = record["date_of_transfer"][:10]
        if (min_date and transfer_date < min_date) or (max_date and transfer_date > max_date):
            skipped_date += 1
            continue
        latlng = coords.get(record["postcode"].strip().upper())
        if latlng is None:
            skipped_geo += 1
            continue
        batch.append(to_row(record, latlng))
        if len(batch) >= INSERT_BATCH_SIZE:
            conn.executemany(INSERT_SQL, batch)
            written += len(batch)
            batch.clear()
    if batch:
        conn.executemany(INSERT_SQL, batch)
        written += len(batch)
    conn.commit()
    print(
        f"  {year}: wrote {written:,} rows, skipped {skipped_geo:,} (postcode not in ONSPD), "
        f"{skipped_date:,} (outside date window).",
        file=sys.stderr,
    )
    return written, skipped_geo


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--outcode", default=None,
        help="Limit to one UK postcode outcode, e.g. OX4, M20, SW19 (default: all of England & Wales)",
    )
    parser.add_argument(
        "--years", type=int, nargs="+", default=[2025],
        help="Price Paid Data year(s) to pull from (default: %(default)s)",
    )
    parser.add_argument(
        "--min-date", default=None,
        help="Drop transactions before this date (inclusive), format YYYY-MM-DD. "
             "Lets a rolling window start mid-year without excluding the whole year's CSV.",
    )
    parser.add_argument(
        "--max-date", default=None,
        help="Drop transactions after this date (inclusive), format YYYY-MM-DD.",
    )
    parser.add_argument("--output", default=None, help="Output .db path (default: seed_data/src/main/assets/seed_prices.db)")
    parser.add_argument(
        "--onspd-cache", default=None,
        help="ONSPD cache directory (default: tools/onspd_cache next to this script)",
    )
    args = parser.parse_args()

    outcode = args.outcode.strip().upper() if args.outcode else None
    repo_root = Path(__file__).resolve().parent.parent
    output_path = Path(args.output) if args.output else repo_root / "seed_data" / "src" / "main" / "assets" / "seed_prices.db"
    onspd_cache_dir = Path(args.onspd_cache) if args.onspd_cache else repo_root / "tools" / "onspd_cache"

    onspd_csv_dir = download_onspd(onspd_cache_dir)
    coords = load_onspd_lookup(onspd_csv_dir)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.unlink(missing_ok=True)
    conn = sqlite3.connect(output_path)
    create_schema(conn)

    total_written = total_skipped = 0
    failed_years: list[int] = []
    for year in args.years:
        try:
            written, skipped = ingest_year(conn, year, outcode, coords, args.min_date, args.max_date)
            total_written += written
            total_skipped += skipped
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            print(f"  {year}: FAILED ({exc}) — continuing with remaining years.", file=sys.stderr)
            failed_years.append(year)

    if total_written == 0:
        conn.close()
        output_path.unlink(missing_ok=True)
        print(f"No transactions found for {outcode!r} in {args.years}. Nothing written.", file=sys.stderr)
        sys.exit(1)

    print("Building spatial index...", file=sys.stderr)
    conn.execute("CREATE INDEX idx_sold_properties_lat_lng ON sold_properties (latitude, longitude)")
    # Built here rather than left for the app to create on first launch: on-device, this was
    # a write-mode CREATE INDEX over up to 31M rows, which either thrashed low-memory devices
    # badly enough to get killed outright, or — if that kill landed mid-transaction — left the
    # app's copy of the database with a stale journal that threw SQLiteDatabaseLockedException
    # on every subsequent open. Shipping it pre-built means the app only ever opens the file
    # read-only and never runs a transaction against it at all.
    print("Building address index...", file=sys.stderr)
    conn.execute("CREATE INDEX idx_sold_properties_address ON sold_properties (postcode, address)")
    conn.commit()

    print("Building postcode-sector rollup...", file=sys.stderr)
    build_sector_stats(conn)
    conn.close()

    # A small sidecar the app reads to detect "the bundled asset changed" without having to
    # inspect the (possibly compressed, multi-GB) .db itself — see PriceDatabase.kt's open().
    # Row count covers content: it changes for any different --years/--outcode/--min-date/
    # --max-date selection. SEED_SCHEMA_VERSION covers the cases row count can't see — a build
    # that adds a table or an index without changing which transactions are in the file.
    version_path = output_path.with_suffix(".version")
    version_path.write_text(f"{SEED_SCHEMA_VERSION}:{total_written}")

    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"\nWrote {total_written:,} geocoded rows to {output_path} ({size_mb:.1f} MB).", file=sys.stderr)
    if total_skipped:
        print(f"({total_skipped:,} transactions skipped: postcode not found in ONSPD.)", file=sys.stderr)
    if failed_years:
        print(f"Years that failed to download and were skipped entirely: {failed_years}", file=sys.stderr)


if __name__ == "__main__":
    main()
