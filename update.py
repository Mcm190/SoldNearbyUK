#!/usr/bin/env python3
"""
One-command monthly release: refresh the dataset, bump the version, build a
signed .aab ready to drop into Play Console.

Intended to be run once a month by hand (see README.md's "Monthly dataset
updates & publishing an update"). It chains together the three steps that
process previously required doing separately:

  1. tools/update_seed_data.py  — rolls the bundled 10-year Price Paid Data
     window forward (drops the oldest month, picks up the newest).
  2. Bumps versionCode by 1 and versionName's patch component by 1 in
     app/build.gradle.kts (e.g. 4/"0.1.3" -> 5/"0.1.4").
  3. ./gradlew bundleRelease — builds the signed release bundle.

Usage:
    python3 update.py                  # full monthly refresh + version bump + signed build
    python3 update.py --years-back 5   # narrower dataset window, passed through to step 1
    python3 update.py --outcode OX4    # fast local test of the whole pipeline
    python3 update.py --skip-dataset   # just bump the version and rebuild (dataset unchanged)
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BUILD_GRADLE = ROOT / "app" / "build.gradle.kts"
LOCAL_PROPERTIES = ROOT / "local.properties"
AAB_PATH = ROOT / "app" / "build" / "outputs" / "bundle" / "release" / "app-release.aab"

VERSION_CODE_RE = re.compile(r"(versionCode\s*=\s*)(\d+)")
VERSION_NAME_RE = re.compile(r'(versionName\s*=\s*")(\d+)\.(\d+)\.(\d+)(")')


def banner(step: str, total: int, title: str) -> None:
    print(f"\n=== Step {step}/{total}: {title} ===", file=sys.stderr)


def check_release_signing() -> None:
    """Fails fast (before the slow dataset rebuild) if the release keystore isn't configured.

    Without RELEASE_STORE_FILE etc. in local.properties, app/build.gradle.kts silently
    falls back to an unsigned build type — bundleRelease would still "succeed" but produce
    a .aab Play Console will reject, which isn't obvious until the very last step.
    """
    if not LOCAL_PROPERTIES.exists():
        sys.exit(f"error: {LOCAL_PROPERTIES} not found — release signing isn't configured. "
                  "See \"Play Store release signing\" in README.md.")
    text = LOCAL_PROPERTIES.read_text()
    required = ["RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"]
    missing = [key for key in required if key not in text]
    if missing:
        sys.exit(f"error: {LOCAL_PROPERTIES} is missing {missing} — release signing isn't fully "
                  "configured. See \"Play Store release signing\" in README.md.")
    print(f"Release keystore is configured in {LOCAL_PROPERTIES.name} — bundleRelease will produce a signed .aab.",
          file=sys.stderr)


def refresh_dataset(years_back: int | None, outcode: str | None) -> None:
    cmd = [sys.executable, str(ROOT / "tools" / "update_seed_data.py")]
    if years_back is not None:
        cmd += ["--years-back", str(years_back)]
    if outcode:
        cmd += ["--outcode", outcode]
    print(f"Running: {' '.join(cmd)}", file=sys.stderr)
    subprocess.run(cmd, check=True, cwd=ROOT)
    print("Dataset refresh finished — seed_prices.db and seed_prices.version are up to date.", file=sys.stderr)


def bump_version() -> tuple[int, str]:
    text = BUILD_GRADLE.read_text()

    code_match = VERSION_CODE_RE.search(text)
    name_match = VERSION_NAME_RE.search(text)
    if not code_match or not name_match:
        sys.exit(f"error: couldn't find versionCode/versionName in {BUILD_GRADLE} — check its format hasn't changed.")

    old_code = int(code_match.group(2))
    new_code = old_code + 1
    major, minor, patch = name_match.group(2), name_match.group(3), int(name_match.group(4))
    old_name = f"{major}.{minor}.{patch}"
    new_name = f"{major}.{minor}.{patch + 1}"

    print(f"Bumping versionCode: {old_code} -> {new_code}", file=sys.stderr)
    print(f"Bumping versionName: {old_name} -> {new_name}", file=sys.stderr)

    text = VERSION_CODE_RE.sub(rf"\g<1>{new_code}", text, count=1)
    text = VERSION_NAME_RE.sub(rf"\g<1>{major}.{minor}.{patch + 1}\g<5>", text, count=1)
    BUILD_GRADLE.write_text(text)
    print(f"Wrote updated version to {BUILD_GRADLE}.", file=sys.stderr)
    return new_code, new_name


def build_signed_bundle() -> None:
    AAB_PATH.unlink(missing_ok=True)
    cmd = [str(ROOT / "gradlew"), "bundleRelease"]
    print(f"Running: {' '.join(cmd)}", file=sys.stderr)
    subprocess.run(cmd, check=True, cwd=ROOT)
    if not AAB_PATH.exists():
        sys.exit(f"error: bundleRelease finished but {AAB_PATH} wasn't produced — check the gradle output above.")
    print(f"Built {AAB_PATH}.", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--years-back", type=int, default=None, help="Passed through to tools/update_seed_data.py")
    parser.add_argument("--outcode", default=None, help="Passed through to tools/update_seed_data.py (fast local test)")
    parser.add_argument("--skip-dataset", action="store_true", help="Skip the dataset refresh; just bump version and build")
    args = parser.parse_args()

    total_steps = 3 if args.skip_dataset else 4
    step = 1

    banner(step, total_steps, "Checking release signing is configured")
    check_release_signing()

    if not args.skip_dataset:
        step += 1
        banner(step, total_steps, "Refreshing the 10-year rolling dataset")
        refresh_dataset(args.years_back, args.outcode)
    else:
        print("\n--skip-dataset passed — leaving seed_prices.db as-is.", file=sys.stderr)

    step += 1
    banner(step, total_steps, "Bumping version")
    new_code, new_name = bump_version()

    step += 1
    banner(step, total_steps, "Building signed release bundle")
    build_signed_bundle()

    print(f"\nDone — version {new_name} (versionCode {new_code}), ready to upload:", file=sys.stderr)
    print(f"  {AAB_PATH}", file=sys.stderr)


if __name__ == "__main__":
    main()
