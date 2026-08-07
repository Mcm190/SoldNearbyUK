# SoldNearbyUK

An Android app that shows a map centered on your location, with dots for
nearby homes and their last sold price — like Google Maps, but for UK house
prices. Pan or zoom anywhere and it reloads for whatever's on screen; zoom
out far enough and the dots disappear rather than clump together, since
individual house-level detail stops meaning much at that scale. The map is
restricted to the UK — there's nothing to show anywhere else, since the
bundled dataset is UK-only — and a small blue dot marks your actual GPS
position (only ever drawn from a real fix, never at the London fallback).
There's a search bar for jumping straight to a postcode or town, and a
Settings screen (a "recent sales only" filter, a recent-sales heatmap
toggle, the data attribution, and a link to support the developer) behind
the gear icon next to it. The optional heatmap shows how many homes have
sold per postcode sector (e.g. "IP2 8", "SW10 2") in the last 2 years, as a
translucent overlay — redder means more sales in that area relative to
whatever's currently on screen, blue means fewer, and it works at any zoom,
including the zoomed-out range where individual dots don't show.

Current state: a working MVP. It's a real, installable, signed debug APK
bundling genuine HM Land Registry sold-price data for **all of England &
Wales, 2016–2025** — 10.08 million real transactions, geocoded against the
official ONS Postcode Directory. The APK is **~760MB**, and after first
launch expect roughly another **~1.6GB** in the app's own storage once it
extracts its working copy — call it ~2.4GB of device storage total. That
first-launch copy is a plain streamed file copy with no on-device
processing (see "Data" under Architecture below), confirmed on a local
emulator to take a few seconds, not minutes.

**Note on this repository:** `seed_data/src/main/assets/seed_prices.db` — the
actual ~1.7GB dataset described above — is **not** included in this git repo.
GitHub hard-rejects any pushed file over 100MB, and the dataset is roughly 17x
that. Two ways to get a build that actually has the data in it:

- Download the prebuilt universal APK from this repo's
  [Releases](../../releases) page (or install from
  [Google Play](https://play.google.com/store/apps/details?id=com.soldnearby.app)) —
  both ship the real dataset baked in.
- Build it yourself: `python3 tools/build_seed_data.py` regenerates
  `seed_prices.db` locally from HM Land Registry's public Price Paid Data (see
  "Reseeding" below) — nothing in this repo depends on a copy existing
  upstream.

An earlier build of this app shipped the full 1995–2025 range (31.1M
rows, ~3.5GB on-device / ~1.96GB APK) and both crashed on launch and,
once that was fixed, crashed again a few minutes in: `PriceDatabase.open()`
used to build the `(postcode, address)` index on-device, in write mode,
over the full row count — which either got killed outright by Android's
low-memory killer (observed using ~2.6GB RSS+swap on a 4GB test device
before being killed) or, if that kill landed mid-transaction, left the
local copy with a stale journal that threw
`SQLiteDatabaseLockedException` on every subsequent open. Both indexes
now ship pre-built (see below), the app opens the file read-only, and
the dataset is trimmed to 10 years — if you want the full 1995–2025
range back, see "Reseeding" below, but be aware it reproduces the size
(not the crash, which is fixed regardless of row count).

## Why not Zoopla or Rightmove?

Both sites' Terms of Use explicitly prohibit scraping/automated data
collection, and both have a history of pursuing legal action against
scrapers. There's also a strictly better option: **HM Land Registry Price
Paid Data** is the actual government register of sale prices — it's the
same underlying data Rightmove and Zoopla license "sold price" figures
from, it's free, updated monthly, and using it directly means no ToS risk
and no fragile scraper to maintain. This project uses that instead.

The one real limitation: Land Registry Price Paid Data only covers England
& Wales. Scotland has an equivalent open dataset from Registers of
Scotland; Northern Ireland's Land & Property Services publishes a price
index but not the same granular per-transaction open data. Worth knowing
if "where you are" turns out to be Edinburgh or Belfast.

## Quick start

Install the toolchain env (already added to `~/.zshrc`, but for the
current shell):

```sh
source ~/Android/env.sh
```

Build and install onto a connected/USB-debugging-enabled device or running
emulator:

```sh
cd ~/Projects/HomeequiMap
./gradlew installDebug
```

Or just build the APK and copy it over manually:

```sh
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk (~760MB currently, signed with the debug key)
```

To open and run it from Android Studio instead, just open this directory
as a project — the Gradle wrapper (`./gradlew`) is already set up, so
Android Studio will sync against the same versions used here.

On first launch the app will ask for location permission, then center the
map on you (falling back to central London if permission is denied) and
show sold-price pins around you — anywhere in England or Wales, since the
bundled dataset is nationwide. Use the search bar to jump to a specific
postcode or town instead.

## Reseeding: change the area or the year range

The bundled dataset (`seed_data/src/main/assets/seed_prices.db` — its own
Play Asset Delivery module, not `app/src/main/assets`; see "Play Store
release signing" and the Architecture section below for why) is generated
by `tools/build_seed_data.py`. It streams Land Registry data and geocodes it
against a local, cached copy of the ONS Postcode Directory (downloaded
once to `tools/onspd_cache/`, ~235MB, then reused — no per-postcode API
calls, so it's fast even at national scale: the full 2022–2025 nationwide
build takes under a minute once ONSPD is cached).

```sh
python3 tools/build_seed_data.py                                    # all of England & Wales, 2025 only — ~100MB
python3 tools/build_seed_data.py --years 2016 2017 2018 2019 2020 2021 2022 2023 2024 2025  # what's currently bundled: 10.08M rows, ~1.6GB raw / ~760MB APK
python3 tools/build_seed_data.py --years 1995 1996 1997 1998 1999 2000 2001 2002 2003 2004 2005 2006 2007 2008 2009 2010 2011 2012 2013 2014 2015 2016 2017 2018 2019 2020 2021 2022 2023 2024 2025  # full history: 31.1M rows, ~3.5GB raw / ~1.9GB APK
python3 tools/build_seed_data.py --outcode SW19                     # just one postcode area — fast, tiny file
./gradlew installDebug                                               # rebuild the app with the new data
```

Reseeding is safe to do even on a device/emulator that already has an
older copy installed: the app compares a small `seed_prices.version`
sidecar (row count) bundled next to the `.db` against a marker it wrote
on-device last time it copied the asset, and re-copies whenever they
differ. Without that check, an install that already extracted an old
copy into its own storage would keep using it forever, even after an
update ships a completely different dataset.

Two independent knobs:
- `--outcode` (e.g. OX4, SW19, M20) restricts to one postcode area — mainly
  useful for fast local testing, since it skips straight to a tiny file.
  Omit it for nationwide.
- `--years` controls how far back to pull. More years = denser coverage
  (most homes don't sell every year, so a single year leaves real gaps
  outside city centres) but a bigger file. One real caveat at wide year
  ranges: the "% above/below average" comparison in the app doesn't adjust
  for house-price inflation between years, so mixing decades together
  makes that comparison a much rougher approximation than mixing two or
  three adjacent years does — a 1995 sale sitting next to a 2025 one in
  the same average is comparing very different markets.

The script streams and writes one year at a time rather than buffering
everything before writing anything — a multi-decade pull (e.g.
`--years 1995 1996 ... 2025`, ~30 million rows) holds roughly one year's
worth of rows plus the ONSPD lookup in memory at once, not the full
dataset. An earlier version of this script accumulated every year in a
single Python list first, which quietly got OOM-killed partway through a
31-year run with no traceback — worth knowing if you're ever tempted to
"simplify" it back to that shape.

## Monthly dataset updates & publishing an update

The bundled dataset is a snapshot, not a live feed (see "Known limitations"
below) — HM Land Registry keeps adding newly-registered transactions to
each year's Price Paid Data file throughout the year, so keeping the app
current means periodically re-pulling and reshipping, not applying some
incremental diff. `tools/update_seed_data.py` wraps `build_seed_data.py`
for exactly that recurring case — it re-pulls the same year range that's
currently bundled (10 years, ending this year, matching `build_seed_data.py`'s
own default) so a monthly cron job or reminder doesn't need to hand-carry
the exact `--years` list every time:

```sh
python3 tools/update_seed_data.py                  # refresh, last 10 years ending this year
python3 tools/update_seed_data.py --years-back 5    # a narrower window instead
python3 tools/update_seed_data.py --outcode OX4     # quick local test of the refresh itself
```

That rebuilds `seed_prices.db` and `seed_prices.version` in place, exactly
like a manual reseed. To get the refreshed data into an update users
actually receive:

1. **Bump the version** in `app/build.gradle.kts` — `versionCode` must
   increase for Play Store (or any) update to be accepted; bump
   `versionName` too (e.g. `0.1.0` → `0.1.1`) so it's visible in Settings.
2. **Rebuild**: `./gradlew bundleRelease` for a Play Store upload (produces
   `app/build/outputs/bundle/release/app-release.aab`), or
   `./gradlew assembleDebug` if you're sideloading rather than publishing
   through Play.
3. **Publish**: upload the `.aab` to Play Console as a new release, or
   reinstall the APK directly for sideloaded installs — `PriceDatabase.open()`
   detects the changed `seed_prices.version` sidecar and re-copies the new
   data automatically on next launch, so no separate "clear data" step is
   needed on the device.

Release builds need a real signing key before they're publishable — see
the next section if you haven't set that up yet.

## Play Asset Delivery: why the dataset isn't a plain asset

Google Play caps a base module's compressed download at **200MB**. The
bundled `seed_prices.db` alone is currently ~730MB compressed — nowhere
close to fitting as a plain `app/src/main/assets` file, regardless of the
signing question above. `seed_data/` is a separate Gradle module (the
`com.android.asset-pack` plugin, wired into `app`'s `assetPacks` list in
`app/build.gradle.kts`) using **install-time delivery**: it's downloaded
and installed together with the app, not fetched later on-demand, since
`PriceDatabase.open()` reads it synchronously the moment the app first
opens. Install-time asset pack contents are exposed through the exact
same `Context.getAssets()` the base module uses — that's what
"install-time" means — so `PriceDatabase.kt` needed no code changes at
all for this move; it still just does `context.assets.open("seed_prices.db")`.

This was verified by actually building device-specific split APKs from a
real `.aab` via `bundletool` (`build-apks` + `install-apks`, not the
Gradle `installDebug` shortcut, which bypasses real bundle-splitting) and
confirming `split_seed_data.apk` installs as its own split alongside
`base.apk`, with the base module's own uncompressed size around 79MB —
comfortably under the 200MB cap.

Practically, this is transparent: `tools/build_seed_data.py` and
`tools/update_seed_data.py` write straight to
`seed_data/src/main/assets/seed_prices.db`, `./gradlew bundleRelease`
picks it up automatically, and Play Console handles serving the right
asset pack to each device.

## Play Store release signing

`app/build.gradle.kts` reads release-signing credentials from
`local.properties` (already gitignored, so the keystore and its passwords
never end up in the repo) and only wires up a `signingConfig` for the
`release` build type if they're present — without them, `bundleRelease`
still builds, just unsigned/unpublishable, which is what happens today.

One-time setup:

1. **Generate an upload keystore** (do this yourself, in your own
   terminal, rather than pasting passwords into any chat — this is
   effectively a permanent password since Play Store ties future updates
   to it):

   ```sh
   keytool -genkeypair -v -keystore ~/keystores/soldnearbyuk-release.jks \
     -alias soldnearbyuk -keyalg RSA -keysize 2048 -validity 10000
   ```

   Keep the resulting `.jks` file **outside the repo** (e.g. `~/keystores/`,
   as above) so there's no chance of it ever getting swept up by a `git
   add`. Back it up somewhere durable — if it's lost, you cannot ship
   updates to an already-published app under the same listing ever again;
   you'd have to publish as a new app instead.

2. **Add the four properties to `local.properties`** (create the file if
   it doesn't exist — it's already gitignored):

   ```properties
   RELEASE_STORE_FILE=/home/you/keystores/soldnearbyuk-release.jks
   RELEASE_STORE_PASSWORD=...
   RELEASE_KEY_ALIAS=soldnearbyuk
   RELEASE_KEY_PASSWORD=...
   ```

   `RELEASE_STORE_FILE` can be an absolute path (as above) or relative to
   the repo root.

3. **Build a signed bundle**: `./gradlew bundleRelease` now produces a
   signed `app/build/outputs/bundle/release/app-release.aab`, ready to
   upload to Play Console.

On first upload, Play Console will offer **Play App Signing** — accept it
(it's the default and Google's recommended path): Google then holds the
actual app-signing key and re-signs what you upload, so this keystore only
ever needs to function as your *upload* key, and losing it later is
recoverable through Play Console's key-reset process rather than fatal.

## Architecture

- **UI**: Kotlin + Jetpack Compose. `ui/MapScreen.kt` (map, search,
  markers) and `ui/SettingsScreen.kt` (recency filter, attribution),
  switched between with plain Compose state in `MainActivity.kt` — no
  navigation library needed for two screens. Settings overlays on top of
  the map rather than replacing it, so panning/zoom position survives a
  trip there and back. `ui/SearchBar.kt` combines the postcode/town
  search field and the settings entry point into one bar, deliberately
  not two independently floating buttons that could end up overlapping.
- **Map**: [MapLibre](https://maplibre.org) (open-source Mapbox GL fork)
  rendering [OpenFreeMap](https://openfreemap.org)'s "liberty" vector
  style (light) / "dark" (dark, following the system theme) — a free,
  public, no-API-key vector tile service built on OpenStreetMap +
  OpenMapTiles data. No Google Cloud account or billing setup required.
  Markers are a small custom dot (`res/drawable/ic_price_dot.xml` via
  `util/MarkerIcons.kt`), not the SDK's default pin. Reloads happen off
  `MapLibreMap.OnCameraIdleListener`, querying the map's actual visible
  bounds (`projection.visibleRegion.latLngBounds`) rather than a fixed
  radius, so results scale with zoom instead of the same small real-world
  area looking like a shrinking clump once you've zoomed out past it. Each
  idle event cancels any still-running query from the previous one before
  starting its own, so a fling or fast pinch-zoom doesn't leave several
  queries racing to redraw the map. Below `MIN_ZOOM_FOR_DOTS` (12.5)
  markers are cleared entirely rather than shown — individual sold-price
  dots stop being legible clutter above that, the same reason most map
  apps hide or cluster POI-level markers at low zoom.
  The map itself is bounds-locked to the UK
  (`MapLibreMap.setLatLngBoundsForCameraTarget` + `setMinZoomPreference` in
  `MapScreen.kt`'s `onMapReady`) — this app's data doesn't cover anywhere
  else, so there's nothing useful to pan or zoom out to. A small blue dot
  (`MY_LOCATION_SOURCE_ID`/`MY_LOCATION_LAYER_ID`, a `GeoJsonSource` +
  `SymbolLayer` rather than a `Marker` annotation, since
  `refreshVisibleProperties()`'s `removeAnnotations()` calls would otherwise
  wipe it out on every pan/zoom) marks the last real GPS fix; it's cleared
  rather than drawn at all if permission is denied or the fix never
  resolved, so it never implies a location that isn't real.
  `location/LocationTracker.kt` wraps Play Services'
  FusedLocationProviderClient for "where you are";
  `location/GeocodeSearch.kt` resolves search text — postcodes/outcodes
  via postcodes.io, place names via Nominatim. ONSPD gives one coordinate
  per postcode, not per address, so a block of flats can have dozens of
  separate sales at the identical lat/lng; rather than spiraling those out
  into a jittered cluster of dots (the earlier approach), each such point
  now renders as a single larger red dot, and tapping it opens
  `ui/AddressPickerSheet.kt` to choose which address it stands in for. A
  point with only one sale keeps the plain green dot and opens
  `ui/PropertyDetailCard.kt` directly. The query cap that feeds this is
  500 per view (`PriceDatabase.findInBounds`'s `limit`), raised from an
  earlier, more conservative 200 once completeness (every sold property
  visible, not just a sampled subset) became the priority over raw marker
  count. The optional recent-sales heatmap is a second, independent
  MapLibre layer (`HeatmapLayer` + `GeoJsonSource`, added once in
  `onMapReady` and updated in place via `setGeoJson` rather than
  removed/re-added every refresh) fed by
  `PropertyRepository.salesDensityBySector` — one point per postcode
  sector at its average lat/lng, weighted by that sector's sale count
  over the last 2 years, normalized 0..1 *against whatever else is
  currently loaded* (see `updateHeatmapSource` in `MapScreen.kt`), which
  is what makes "redder = more sales" mean something as you pan to a
  quieter or busier area rather than washing out against a fixed
  nationwide scale. That 2-year window is fixed and independent of the
  "recent sales only" dot filter — one is a heatmap-only concept of
  "recent", the other controls which dots are shown at all. It has no
  zoom floor of its own (sector aggregates are a cheap SQL `GROUP BY`,
  not a per-row fetch), so it's useful precisely where dots aren't shown
  yet — the `SettingsScreen.kt` toggle (`AppSettings.heatmapEnabled`)
  turns it on/off; off, the source is just cleared to empty rather than
  the layer being removed.
- **Data**: `data/PriceDatabase.kt` reads the bundled SQLite file directly
  (plain `SQLiteDatabase`, not Room — the file is built externally by the
  Python script, so there's no Room schema-hash to keep in sync with it).
  On first launch it streams the bundled asset to the app's own storage
  and opens that copy `OPEN_READONLY` — nothing but that one streamed
  copy happens on-device; both indexes ship pre-built in the asset itself
  (see `create_schema`/the end of `main()` in `build_seed_data.py`). That
  used to be a `CREATE INDEX IF NOT EXISTS` run in write mode on every
  fresh install, over however many rows were bundled — fine at a few
  million rows, but at the full 31M-row history it either got killed
  outright by the OS for the memory pressure it caused, or, if that kill
  landed mid-transaction, left the on-device copy with a stale journal
  that threw `SQLiteDatabaseLockedException` on the next launch. `open()`
  also compares a `seed_prices.version` sidecar (row count) against a
  marker it wrote last time it copied the asset, and re-copies whenever
  they differ — otherwise reseeding would silently do nothing for any
  install that already extracted an older copy into its own storage.
  Results are ordered by distance from the query centre, not by date —
  sorting by recency instead would mean the row limit quietly fills up
  with only the newest year in any area with enough turnover, hiding
  older (but still nearby) sales entirely. `historyForAddress` backs the
  "Sale history" section of the detail card — every recorded transaction
  for one exact (postcode, address) pair, newest first, regardless of the
  recency filter (history is specifically about the past); it relies on
  the pre-built `idx_sold_properties_address` index — the alternative was
  a full scan of the whole table on every tap. `averagePriceBySector`
  backs the recent-sales heatmap via `PropertyRepository.salesDensityBySector`
  (which weights by sale count, not price): postcodes are always
  "outcode, space, one digit, two letters", so `substr(postcode, 1,
  length(postcode) - 2)` gives the sector regardless of the outcode's own
  variable length, and the existing `(latitude, longitude)` index still
  narrows the `WHERE` clause before SQLite groups the results.
  `data/PropertyRepository.kt` is the seam to swap for a network-backed
  implementation later; the UI only depends on its shape.
  `data/AppSettings.kt` + `SettingsRepository` persist the recency-filter
  and heatmap-toggle settings via SharedPreferences.
- **Branding**: the launcher icon (`res/mipmap-*/ic_launcher_foreground.png`,
  cropped from the top-level `logo.jpg`) and the splash screen
  (`res/drawable-nodpi/splash_image.png`, from the top-level
  `d0f0ce94-*.png` mockup) are both raster art, not the placeholder vector
  pin the project started with. The splash is wired up the pre-Android-12
  way — `Theme.SoldNearby.Splash` (see `themes.xml`) is the manifest theme
  for `MainActivity`, so the artwork is the window background from the
  very first frame, before any Kotlin runs; `MainActivity.onCreate` swaps
  back to `Theme.SoldNearby` right before `setContent`, so it's on screen
  for exactly as long as cold start actually takes, not a fixed timer.
  Deliberately not the AndroidX Core SplashScreen API: that API only
  allows an icon over a flat/gradient colour by Android 12+ design policy,
  which can't reproduce a fully custom, textured design like this one.
- **ETL**: `tools/build_seed_data.py`, pure standard library (no pip
  installs needed). Geocodes against a locally cached ONS Postcode
  Directory rather than calling any live API per postcode, so it stays
  fast and considerate even at nationwide scale. Streams and writes one
  year at a time to keep memory bounded regardless of how many years are
  requested (see "Reseeding" above).

## Licensing / attribution

All required, and split across two places: a small always-visible credit
on the map itself (`R.string.map_attribution_text`) for the map tiles
specifically — OpenStreetMap's licence expects that one to stay
"reasonably likely to be seen" on the map, not tucked away — plus the
fuller version in Settings (`R.string.attribution_text`) covering
everything, including Land Registry:

- **HM Land Registry Price Paid Data** — Crown copyright and database
  right, [Open Government Licence
  v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/).
- **OpenStreetMap** map data — © OpenStreetMap contributors, [Open
  Database License](https://www.openstreetmap.org/copyright).
- **OpenMapTiles** — the vector tile schema OpenFreeMap's styles are
  built from.
- **OpenFreeMap** — the free, public tile hosting itself. Explicitly "no
  limits, no registration, no API keys" per their own terms, funded by
  donations — worth a look at [openfreemap.org](https://openfreemap.org)
  if this project ever generates enough traffic to want to contribute
  back.

One thing to watch if this were ever CARTO's basemaps instead: their
hosted tiles technically respond without a key, but their own LICENSE.md
explicitly restricts free use to non-profit grants — the tiles working
when you `curl` them isn't the same as them being licensed for it, which
is exactly why OpenFreeMap was picked here instead.

## Known limitations / where this goes next

- **Bundled and static, not live.** The dataset is a snapshot baked in at
  build time, not a live feed — reflects whatever it was seeded with, and
  won't include next month's sales without a rebuild + reinstall (see
  "Monthly dataset updates & publishing an update" above for that
  process). Scaling past "reseed and republish" to something that updates
  itself means a real backend (e.g. Postgres/PostGIS, ingesting Price
  Paid Data monthly, exposing `/prices?lat=&lng=&radius=`) instead of a
  file shipped in the APK. `PropertyRepository` is the seam for that swap.
- **No real spatial index.** Queries filter by a plain `(latitude,
  longitude)` compound index, not a true 2D spatial index — SQLite's
  R-Tree module would be the standard fix, but it's confirmed **not**
  compiled into Android's bundled SQLite (checked directly against the
  AOSP build config), so that path isn't available here. In practice this
  is fine at the current scale — a bounding-box query still only has to
  filter within one latitude band, not the whole table — but it's the
  first thing to revisit if map panning ever feels sluggish in dense
  cities as the dataset grows further.
- **Deprecated marker API.** `MapLibreMap.addMarker`/`MarkerOptions` still
  work (that's how the dot markers are drawn) but are deprecated upstream
  in favor of the [Annotation
  Plugin](https://github.com/maplibre/maplibre-plugins-android), which
  also adds real clustering — worth switching to if 500 markers at once
  (the current `PriceDatabase.findInBounds` `limit`) ever visibly lags on
  a real device; this hasn't been tested on one from this environment.
- **The zoom cutoff is a hardcoded guess.** `MIN_ZOOM_FOR_DOTS = 12.5` in
  `MapScreen.kt` is a reasonable-looking number, not a measured one —
  adjust it directly if dots disappear later or earlier than feels right.
- **Some houses will never have a dot, and that's expected.** A "last
  sold price" map can only ever show properties that have actually sold
  within the covered window — same as Rightmove/Zoopla's own sold-price
  layers. UK homes turn over roughly every 20-23 years on average, so
  even across the full 1995–2025 window, something like a quarter of
  properties genuinely have no Land Registry sale on record — not a
  missing-data bug, just houses that haven't changed hands recently
  enough. No data source closes that gap without either estimating a
  value (risky — it'd be a guess presented next to real transaction
  data) or licensing something like Council Tax bands, which turned out
  not to be available in bulk for free when checked for this project.
- **Want the literal Google Maps look?** Swap `org.maplibre.gl:android-sdk`
  for `com.google.maps.android:maps-compose` and `MapLibreView` in
  `MapScreen.kt` for `GoogleMap { }`. You'll need a Maps SDK for Android
  API key from Google Cloud Console (free tier; requires a billing account
  on file even though map display itself isn't metered).
- **Play Store.** Release signing is wired up (see "Play Store release
  signing" above) but only takes effect once you've generated a keystore
  and populated `local.properties` — until then, `bundleRelease` builds an
  unsigned, unpublishable bundle. The base-module size problem is also
  handled (see "Play Asset Delivery" above). Still needed before
  publishing: a privacy policy (mandatory for apps requesting location
  permission), and filling out Play Console's Data Safety form and content
  rating questionnaire.
- **EPC data** (floor area, energy rating) is also free/open via
  [epc.opendatacommunities.org](https://epc.opendatacommunities.org) and
  joins to Price Paid Data by address — a natural next data source for a
  "price per square metre" feature.

## Toolchain

Installed entirely in `~/Android/` (user-space, no root) rather than
system-wide:

- JDK 17 (Temurin) — `~/Android/jdk-17`
- Android SDK cmdline-tools, platform-tools, platforms 36 & 37,
  build-tools 36.0.0 & 37.0.0 — `~/Android/Sdk`
- Gradle 9.6.1 — only used once to bootstrap `./gradlew`; the project uses
  its own pinned wrapper from here on

`~/Android/env.sh` sets `JAVA_HOME`/`ANDROID_HOME`/`PATH` and is sourced
from `~/.zshrc` automatically for new shells.
