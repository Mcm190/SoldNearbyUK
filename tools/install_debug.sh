#!/usr/bin/env bash
# Installs a debug build onto a connected device/emulator the way Play Store actually
# delivers the app — via the Android App Bundle + its asset packs — rather than
# `./gradlew installDebug`, which builds a plain APK.
#
# Why this exists: seed_prices.db ships in the :seed_data Play Asset Delivery
# install-time asset pack, not app/src/main/assets (see seed_data/build.gradle.kts —
# the dataset alone is well past Google Play's 200MB base-module download cap).
# Asset packs are purely an Android App Bundle concept: `assembleDebug`/`installDebug`
# produce a plain APK and silently do not fold the asset pack's contents back into it.
# The app still installs fine that way, but crashes on first launch with
# `FileNotFoundException: seed_prices.version` — there's nothing in that APK to find.
# Going through the real bundle + bundletool split-install path (what this script does,
# and what Play Console/Android Studio's own "Run" button do under the hood for a
# project with asset packs) is the only way to test locally with the data actually
# present, short of using Android Studio directly.
#
# Usage:
#   tools/install_debug.sh
set -euo pipefail
cd "$(dirname "$0")/.."

BUNDLETOOL_JAR="tools/.bundletool-all.jar"
BUNDLETOOL_VERSION="1.18.3"
if [ ! -f "$BUNDLETOOL_JAR" ]; then
    echo "Downloading bundletool ${BUNDLETOOL_VERSION} (one-time, cached at $BUNDLETOOL_JAR)..." >&2
    curl -sL -o "$BUNDLETOOL_JAR" \
        "https://github.com/google/bundletool/releases/download/${BUNDLETOOL_VERSION}/bundletool-all-${BUNDLETOOL_VERSION}.jar"
fi

AAPT2=$(find "${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools" -name aapt2 | sort -V | tail -1)
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
if [ -z "$AAPT2" ]; then
    echo "Couldn't find aapt2 under \$ANDROID_HOME/build-tools — is the Android SDK set up? (see README Toolchain section)" >&2
    exit 1
fi

echo "Building debug bundle..." >&2
./gradlew :app:bundleDebug

APKS_OUT="/tmp/soldnearbyuk-debug.apks"
rm -f "$APKS_OUT"
echo "Building device-specific APKs from the bundle..." >&2
java -jar "$BUNDLETOOL_JAR" build-apks \
    --bundle=app/build/outputs/bundle/debug/app-debug.aab \
    --output="$APKS_OUT" \
    --connected-device \
    --aapt2="$AAPT2" \
    --adb="$ADB"

echo "Installing onto the connected device..." >&2
java -jar "$BUNDLETOOL_JAR" install-apks --apks="$APKS_OUT" --adb="$ADB"

echo "Done — installed with the seed_data asset pack included." >&2
