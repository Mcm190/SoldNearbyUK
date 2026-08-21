package com.soldnearby.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.JsonObject
import com.soldnearby.app.R
import com.soldnearby.app.data.AppSettings
import com.soldnearby.app.data.PostcodeSectorPrice
import com.soldnearby.app.data.PropertyRepository
import com.soldnearby.app.data.SoldProperty
import com.soldnearby.app.location.GeocodeSearch
import com.soldnearby.app.location.LocationTracker
import com.soldnearby.app.ui.theme.BrandGreen
import com.soldnearby.app.ui.theme.LocationBlue
import com.soldnearby.app.ui.theme.MultiSaleRed
import com.soldnearby.app.util.createDotIcon
import com.soldnearby.app.util.formatGbp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.util.concurrent.atomic.AtomicInteger

/** Used only until we have a real location fix (or if permission is denied). */
private val LONDON_FALLBACK = LatLng(51.5074, -0.1278)
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

/** This app's data is UK-only (see build_seed_data.py) so nowhere else on the map is ever
 *  useful — a loose box around Great Britain, Northern Ireland, and the surrounding islands,
 *  wide enough to include Shetland and the Channel Islands without allowing a pan out into
 *  open ocean or another country. */
private val UK_BOUNDS = LatLngBounds.from(latNorth = 61.0, lonEast = 2.0, latSouth = 49.8, lonWest = -8.75)
/** Keeps a pinch-out from zooming past roughly "the whole UK fills the screen" — paired with
 *  [UK_BOUNDS], since the bounds alone only restrict the camera *target*, not how far out you
 *  can zoom while centred near an edge. */
private const val UK_MIN_ZOOM = 4.5

/** Below this, individual house dots don't mean much and just look like clutter/a clump —
 *  real map apps hide or cluster POI-level markers at low zoom for the same reason. */
private const val MIN_ZOOM_FOR_DOTS = 12.5

/** Expands the query past exactly what's visible so panning slightly doesn't show an empty
 *  edge before the next reload catches up. */
private const val BOUNDS_BUFFER_FACTOR = 0.25

/** How long a refresh waits before it actually starts work. A pan or a pinch-zoom doesn't fire
 *  one camera-idle event, it fires a burst of them, and each one cancels and replaces the last
 *  (see triggerRefresh) — so without this, a single gesture put a dozen queries into
 *  PropertyRepository's one-at-a-time queue, every one of them already obsolete by the time it
 *  reached the front. Short enough to be imperceptible once the map settles, long enough that a
 *  gesture costs one query instead of a dozen. */
private const val REFRESH_DEBOUNCE_MS = 250L

private const val HEATMAP_SOURCE_ID = "sector-density-heatmap-source"
private const val HEATMAP_LAYER_ID = "sector-density-heatmap-layer"
private const val MY_LOCATION_SOURCE_ID = "my-location-source"
private const val MY_LOCATION_LAYER_ID = "my-location-layer"
private const val MY_LOCATION_IMAGE_ID = "my-location-icon"

/** How often the "you are here" dot re-fetches a fix and moves to follow the user. Only the
 *  dot follows on this cadence — the camera never does, so panning/zooming around the map
 *  stays completely free; it only snaps to the latest fix when the locate-me button is
 *  pressed (see centerOnCurrentLocation). */
private const val MY_LOCATION_POLL_INTERVAL_MS = 1500L

@Composable
fun MapScreen(
    settings: AppSettings,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Follows this app's own "Dark mode" setting, not the system theme (see SoldNearbyTheme) —
    // the map tiles should match the rest of the UI rather than the device default.
    val isDarkTheme = settings.darkModeEnabled
    val keyboardController = LocalSoftwareKeyboardController.current
    // MapLibreView's AndroidView only ever runs its factory once, so the onMapReady callback —
    // and the addOnCameraIdleListener it registers below — closes over whatever this composable's
    // local variables were on that one, first composition, permanently. Without this, every pan
    // or zoom after a settings change (heatmap/recent-only toggle) would re-run
    // refreshVisibleProperties() using the *stale* settings value from first launch, silently
    // reverting the toggle the next time the camera moved. rememberUpdatedState gives that
    // long-lived closure a stable holder whose .value stays current across recompositions,
    // instead of a plain parameter frozen at closure-creation time.
    val currentSettings by rememberUpdatedState(settings)

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var selectedProperty by remember { mutableStateOf<SoldProperty?>(null) }
    var saleHistory by remember { mutableStateOf<List<SoldProperty>>(emptyList()) }
    // Non-null while the user is choosing which address a red (multiple-addresses-at-one-point)
    // dot represents.
    var addressPickerOptions by remember { mutableStateOf<List<SoldProperty>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    var nearbyProperties by remember { mutableStateOf<List<SoldProperty>>(emptyList()) }
    var isZoomedOutTooFar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchError by remember { mutableStateOf<String?>(null) }
    // Constructed once and reused for the life of the screen — PropertyRepository.open() checks
    // for/copies the multi-GB bundled asset and opens a SQLite handle, so building a fresh one
    // on every pan/zoom (as this used to) was real, avoidable I/O on every camera-idle event.
    // Null until that finishes: it's built on Dispatchers.IO rather than directly inside
    // remember{} (which would run on the main thread) because on first launch it's copying a
    // multi-GB file and building an index over tens of millions of rows — doing that
    // synchronously on the main thread is what was causing the app to ANR on startup.
    var repository by remember { mutableStateOf<PropertyRepository?>(null) }
    LaunchedEffect(Unit) {
        repository = withContext(Dispatchers.IO) { PropertyRepository(context) }
    }
    // Marker.id -> the property it represents, for a point with exactly one sale.
    val markerProperties = remember { mutableMapOf<Long, SoldProperty>() }
    // Marker.id -> every sale at a point that stands in for more than one address (ONSPD gives
    // one coordinate per postcode, not per address). Tapping one of these opens a picker instead
    // of a detail card directly.
    val markerGroups = remember { mutableMapOf<Long, List<SoldProperty>>() }
    val singleDotIcon = remember(context) { createDotIcon(context, BrandGreen.toArgb(), diameterDp = 12f) }
    val multiDotIcon = remember(context) { createDotIcon(context, MultiSaleRed.toArgb(), diameterDp = 13f) }
    // Set once, inside onMapReady, when the heatmap source+layer are added to the style. Kept
    // as a source reference (not re-added each refresh) so updating it is a cheap setGeoJson
    // call rather than removing/re-adding a whole layer every time the viewport changes.
    var heatmapSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    // Set once in onMapReady alongside heatmapSource, updated via setGeoJson whenever a fresh
    // GPS fix comes in — kept as its own source/layer rather than a Marker annotation because
    // refreshVisibleProperties() calls removeAnnotations() on every pan/zoom, which would wipe
    // out an annotation-based pin along with the sold-price dots.
    var myLocationSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    // The last position pushed to myLocationSource, kept around so a style swap (dark-mode
    // toggle) — which replaces the whole Style, wiping myLocationSource along with it — can
    // restore the pin on the freshly re-added source instead of leaving it blank until the
    // next GPS fix.
    var lastLocationFix by remember { mutableStateOf<LatLng?>(null) }
    // The map fires a camera-idle event of its own right after the style loads, before we've
    // centred on anything meaningful. This flag stops that one from triggering a real load.
    var hasRequestedInitialCenter by remember { mutableStateOf(false) }
    // Cancelled and replaced on every camera-idle rather than left to run to completion — a
    // fling or a fast pinch-zoom fires several idle events in quick succession, and letting each
    // one's DB query run to completion would mean multiple queries racing to redraw the map with
    // whichever happens to finish last, not the one for where the camera actually ended up.
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    // Incremented by triggerRefresh on every launch, and captured by the coroutine it launches,
    // so a refresh can tell whether it's still the current one when it finishes — see
    // triggerRefresh for what that's for. A plain counter rather than Compose state on purpose:
    // nothing should recompose because a refresh started.
    val refreshGeneration = remember { AtomicInteger(0) }
    // Reused for every fix — both the one-shot pulls (initial centre, locate-me button) and the
    // background poll below — rather than a fresh FusedLocationProviderClient per call.
    val locationTracker = remember(context) { LocationTracker(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasLocationPermission = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // Loads/replaces markers for whatever the camera is currently showing. Queries the map's
    // actual visible area, not a fixed radius — that way results scale with zoom instead of
    // the same small real-world patch looking like a tiny clump once zoomed out far enough to
    // see a much bigger area. Below MIN_ZOOM_FOR_DOTS, skips querying and clears markers
    // entirely, since individual house dots stop being meaningful at that scale anyway.
    // Never moves the camera itself: called after centring on GPS, after a search, and every
    // time the camera-idle listener fires because the user panned or zoomed.
    // Turns per-sector sale counts (last 2 years) into normalized (0..1) heat weights, relative
    // to whatever's currently loaded — the low/high end of the scale is always "quietest/busiest
    // sector on screen right now", not a fixed nationwide scale, so it stays meaningful whether
    // you're looking at central London or a small town, and updates as you pan elsewhere.
    // Building the FeatureCollection is done off the main thread (setGeoJson itself still has to
    // happen on it): zoomed all the way out this is every sector in the country — ~8k points to
    // allocate and serialise — which is enough to drop frames if it runs on the UI thread.
    suspend fun updateHeatmapSource(sectors: List<PostcodeSectorPrice>) {
        if (sectors.isEmpty()) {
            heatmapSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return
        }
        val collection = withContext(Dispatchers.Default) {
            val counts = sectors.map { it.saleCount }
            val minCount = counts.min()
            val range = (counts.max() - minCount).let { if (it <= 0) 1 else it }
            val features = sectors.map { sector ->
                val weight = ((sector.saleCount - minCount).toDouble() / range).coerceIn(0.0, 1.0)
                val properties = JsonObject().apply { addProperty("weight", weight) }
                Feature.fromGeometry(Point.fromLngLat(sector.longitude, sector.latitude), properties)
            }
            FeatureCollection.fromFeatures(features)
        }
        // Deliberately read after the suspension above, not before it: a dark-mode toggle
        // replaces the whole Style mid-refresh, and the GeoJsonSource this held a moment ago
        // would by then be orphaned from a destroyed style. onStyleLoaded repopulates the fresh
        // one itself, so dropping this update is the correct outcome, not a lost frame.
        heatmapSource?.setGeoJson(collection)
    }

    // Empty position clears the pin (permission denied, or a fix that never resolved) rather
    // than leaving a stale one sitting somewhere the user no longer is.
    fun updateMyLocationSource(position: LatLng?) {
        lastLocationFix = position
        val source = myLocationSource ?: return
        if (position == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        } else {
            source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(position.longitude, position.latitude)))
        }
    }

    // Never sets isLoading back to false itself — triggerRefresh owns clearing it, including on
    // the paths through here that don't finish (see its comment).
    suspend fun refreshVisibleProperties() {
        val currentMap = map ?: return
        val repo = repository ?: return
        isLoading = true
        val zoom = currentMap.cameraPosition.zoom
        val bounds = currentMap.projection.visibleRegion.latLngBounds
        val latBuffer = (bounds.latitudeNorth - bounds.latitudeSouth) * BOUNDS_BUFFER_FACTOR
        val lngBuffer = (bounds.longitudeEast - bounds.longitudeWest) * BOUNDS_BUFFER_FACTOR
        val minLat = bounds.latitudeSouth - latBuffer
        val maxLat = bounds.latitudeNorth + latBuffer
        val minLng = bounds.longitudeWest - lngBuffer
        val maxLng = bounds.longitudeEast + lngBuffer

        // Updated before the zoom gate below rather than after it: unlike the dots, a density
        // surface is *most* useful zoomed out, and it now costs effectively nothing at any zoom
        // — it's an in-memory filter over a precomputed table (see
        // PropertyRepository.salesDensityBySector), not the viewport-wide GROUP BY it used to
        // be. That query is why this used to need a zoom floor of its own; without it there's
        // nothing left to gate on.
        updateHeatmapSource(
            if (currentSettings.heatmapEnabled) {
                repo.salesDensityBySector(minLat, maxLat, minLng, maxLng)
            } else {
                emptyList()
            }
        )

        if (zoom < MIN_ZOOM_FOR_DOTS) {
            currentMap.removeAnnotations()
            markerProperties.clear()
            markerGroups.clear()
            nearbyProperties = emptyList()
            isZoomedOutTooFar = true
            hasLoadedOnce = true
            return
        }
        isZoomedOutTooFar = false
        val properties = repo.withinBounds(
            minLat = minLat,
            maxLat = maxLat,
            minLng = minLng,
            maxLng = maxLng,
            recentOnly = currentSettings.recentOnly
        )
        map?.let { m ->
            m.removeAnnotations()
            markerProperties.clear()
            markerGroups.clear()
            // Collapsed to each (postcode, address)'s single most recent sale before grouping
            // by point — a house that's sold more than once has one row per transaction, all
            // at the identical point, and without this they'd get treated as separate
            // "addresses" sharing that point (wrongly triggering the multi-address picker, and
            // wrongly inflating its count) instead of one address with a history. It also used
            // to mean the specific transaction a tap landed on wasn't necessarily the newest
            // one — findInBounds orders by id, not date — so the card's headline price could
            // show an old sale while the sale-history list right underneath it (always fetched
            // fresh, always newest-first) correctly showed a more recent one: the price at the
            // top of the card contradicting its own history.
            val latestPerAddress = properties
                .groupBy { it.postcode to it.address }
                .values
                .map { sales -> sales.maxBy { it.dateOfTransfer } }
            // ONSPD gives one coordinate per postcode, not per address, so every flat in a
            // block shares the same point. One dot per point, not one per property — a group
            // renders as a single larger red dot standing in for all of them, tapping it opens
            // a picker (see AddressPickerSheet) rather than spiraling them out into a cluster.
            latestPerAddress.groupBy { it.latitude to it.longitude }.values.forEach { atSamePoint ->
                val first = atSamePoint.first()
                val position = LatLng(first.latitude, first.longitude)
                if (atSamePoint.size == 1) {
                    val marker = m.addMarker(
                        MarkerOptions()
                            .position(position)
                            .icon(singleDotIcon)
                            .title(formatGbp(first.priceGbp))
                            .snippet(first.address)
                    )
                    markerProperties[marker.id] = first
                } else {
                    val marker = m.addMarker(
                        MarkerOptions()
                            .position(position)
                            .icon(multiDotIcon)
                            .title("${atSamePoint.size} addresses sold here")
                    )
                    markerGroups[marker.id] = atSamePoint
                }
            }
        }
        nearbyProperties = properties
        hasLoadedOnce = true
    }

    // The one path every trigger of a refresh goes through — a pan/zoom, a settings change, the
    // repository finishing init, or a style swap repopulating a wiped heatmap/location source.
    // Cancelling whatever refresh is still in flight before starting the next one matters
    // because refreshVisibleProperties does several unrelated things (heatmap, dots, loading
    // state) that each get applied as soon as their own query returns, not atomically as a
    // whole — without a single shared job to cancel, a slow call that was reading "heatmap on"
    // moments ago could still finish and repaint the heatmap *after* a newer, faster call
    // already cleared it for "heatmap off", silently reverting the toggle the user just made.
    // Also the single owner of isLoading, in a try/finally, because refreshVisibleProperties has
    // no way to clear it on the path that actually matters: cancellation. A cancelled coroutine
    // throws at its next suspension point and never reaches the end of the function, so with the
    // flag cleared inline the spinner's lifetime was really "until the *newest* refresh
    // finishes" — with no timeout and no fallback if that one never did. That's what turned a
    // slow query into an indefinite spinner rather than a slow load. Guarded on the generation
    // so a cancelled refresh can't clear a spinner that now belongs to the one that replaced it;
    // the counter is incremented before the cancel so the outgoing job's finally is already
    // stale by the time it runs.
    fun triggerRefresh() {
        val generation = refreshGeneration.incrementAndGet()
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                delay(REFRESH_DEBOUNCE_MS)
                refreshVisibleProperties()
            } finally {
                if (generation == refreshGeneration.get()) isLoading = false
            }
        }
    }

    suspend fun centerOnCurrentLocation() {
        val currentMap = map ?: return
        hasRequestedInitialCenter = true
        val gpsFix = if (hasLocationPermission) {
            runCatching { locationTracker.currentLocation() }.getOrNull()
                ?.let { (lat, lng) -> LatLng(lat, lng) }
        } else {
            null
        }
        // Only ever reflects a real fix — never drawn at the London fallback, which isn't
        // actually where the user is.
        updateMyLocationSource(gpsFix)
        // Loading happens once this settles and the camera-idle listener below fires —
        // not here — so a manual pan afterwards and a GPS re-centre go through one path.
        currentMap.animateCamera(CameraUpdateFactory.newLatLngZoom(gpsFix ?: LONDON_FALLBACK, 14.5))
    }

    suspend fun performSearch() {
        val query = searchQuery
        val currentMap = map
        if (query.isBlank() || currentMap == null) return
        keyboardController?.hide()
        searchError = null
        isLoading = true
        val result = GeocodeSearch.search(query)
        isLoading = false
        if (result == null) {
            searchError = "Couldn't find \"$query\""
            return
        }
        hasRequestedInitialCenter = true
        currentMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(result.latitude, result.longitude), 14.5))
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    LaunchedEffect(map, hasLocationPermission) {
        centerOnCurrentLocation()
    }

    // Keeps the "you are here" dot following the user as they actually move, instead of the
    // one-shot fix above going stale the moment they walk/drive away from where the app was
    // opened. Deliberately only ever touches the dot (updateMyLocationSource), never the
    // camera — free panning/zooming stays free, and the map only snaps to the latest fix when
    // the locate-me button calls centerOnCurrentLocation() directly.
    LaunchedEffect(map, hasLocationPermission) {
        if (map == null || !hasLocationPermission) return@LaunchedEffect
        while (isActive) {
            delay(MY_LOCATION_POLL_INTERVAL_MS)
            // Skips the fetch (rather than just skipping the camera/UI update) while the app is
            // backgrounded — below STARTED means onStop has already fired, so there's no visible
            // dot to update anyway, and there's no reason to keep pinging GPS for a screen no
            // one can see.
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) continue
            val gpsFix = runCatching { locationTracker.currentLocation() }.getOrNull()
                ?.let { (lat, lng) -> LatLng(lat, lng) }
            if (gpsFix != null) {
                updateMyLocationSource(gpsFix)
            }
        }
    }

    // A settings change (recency filter, heatmap toggle) should reload the current view in
    // place, not jump the camera back to the user's GPS position. Goes through triggerRefresh(),
    // not a direct call, so a rapid toggle-off-then-on can't be clobbered by a slower, now-stale
    // refresh finishing after it (see triggerRefresh's comment).
    LaunchedEffect(settings) {
        if (map != null && hasRequestedInitialCenter) {
            triggerRefresh()
        }
    }

    // Fetches the full sale history for whichever property's card is open. Ignores the recency
    // filter deliberately (see PropertyRepository.historyForAddress) — cleared back to empty as
    // soon as the card closes so a stale history doesn't flash before the next one loads.
    LaunchedEffect(selectedProperty) {
        val property = selectedProperty
        val repo = repository
        saleHistory = if (property != null && repo != null) repo.historyForAddress(property) else emptyList()
    }

    // Covers the case where the camera already settled before the async DB init (above)
    // finished — without this, the map would sit empty until the next manual pan/zoom.
    // If the DB instead becomes ready *before* the camera settles, the existing
    // onCameraIdleListener below already triggers the first load, so this only fires once.
    LaunchedEffect(repository) {
        if (repository != null && map != null && hasRequestedInitialCenter) {
            triggerRefresh()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MapLibreView(
            modifier = Modifier.fillMaxSize(),
            styleUrl = if (isDarkTheme) STYLE_DARK else STYLE_LIGHT,
            onMapReady = { readyMap ->
                // This app's data is UK-only, so panning/zooming out past it has nowhere useful
                // to go — see UK_BOUNDS. Set once — these are camera constraints on the map
                // object itself, not on the (replaceable) style, so they don't need reapplying
                // on a style swap.
                readyMap.setLatLngBoundsForCameraTarget(UK_BOUNDS)
                readyMap.setMinZoomPreference(UK_MIN_ZOOM)

                readyMap.setOnMarkerClickListener { marker ->
                    val single = markerProperties[marker.id]
                    if (single != null) {
                        selectedProperty = single
                    } else {
                        markerGroups[marker.id]?.let { addressPickerOptions = it }
                    }
                    true
                }
                readyMap.addOnCameraIdleListener {
                    if (hasRequestedInitialCenter) {
                        triggerRefresh()
                    }
                }
                map = readyMap
            },
            // Runs after every style load, not just the first — MapLibre's setStyle() (used to
            // switch between STYLE_LIGHT/STYLE_DARK when the dark-mode setting changes) replaces
            // the whole Style object, silently discarding any source/layer added on top of the
            // previous one. So the heatmap and "you are here" sources/layers have to be
            // (re)created here every time, and then immediately repopulated — a fresh
            // GeoJsonSource starts out empty — from whatever data is already known, rather than
            // waiting for the next pan/zoom or GPS fix to fill them back in.
            onStyleLoaded = { _, style ->
                val source = GeoJsonSource(HEATMAP_SOURCE_ID)
                style.addSource(source)
                heatmapSource = source
                style.addLayer(
                    HeatmapLayer(HEATMAP_LAYER_ID, HEATMAP_SOURCE_ID).apply {
                        setProperties(
                            // "weight" is pre-normalized to 0..1 in updateHeatmapSource, relative
                            // to whatever sectors are currently loaded — not a fixed density scale.
                            PropertyFactory.heatmapWeight(Expression.get("weight")),
                            PropertyFactory.heatmapIntensity(
                                Expression.interpolate(
                                    Expression.linear(), Expression.zoom(),
                                    Expression.stop(8, 1.5f), Expression.stop(15, 4f)
                                )
                            ),
                            // salesDensityBySector returns one point per postcode sector, not
                            // one per sale — sparse compared to a per-transaction heatmap would
                            // be. A small radius (the old 20-90px) left each sector as an
                            // isolated soft dot with visible map showing through the gaps
                            // between them, instead of neighbouring sectors' blobs overlapping
                            // into one continuous surface. Much bigger radius is what makes
                            // sparse points read as continuous coverage.
                            PropertyFactory.heatmapRadius(
                                Expression.interpolate(
                                    Expression.linear(), Expression.zoom(),
                                    Expression.stop(8, 60f), Expression.stop(16, 220f)
                                )
                            ),
                            // Subtle: lower ceiling than a typical heatmap (0.7), so it reads as
                            // a tint over the map rather than obscuring it.
                            PropertyFactory.heatmapOpacity(0.45f),
                            PropertyFactory.heatmapColor(
                                Expression.interpolate(
                                    Expression.linear(), Expression.heatmapDensity(),
                                    // Feathers in fully transparent, well before the first
                                    // colour stop, so a blob's edge fades out gradually instead
                                    // of an abrupt transparent-to-blue boundary — that's what
                                    // lets adjacent sectors' blobs blend rather than looking
                                    // like separate circles butted up against each other.
                                    Expression.stop(0, "rgba(0,0,0,0)"),
                                    Expression.stop(0.1, "rgba(33,102,172,0.2)"),
                                    Expression.stop(0.3, "rgba(33,102,172,0.5)"),
                                    Expression.stop(0.45, "rgba(103,169,207,0.65)"),
                                    Expression.stop(0.6, "rgba(253,219,199,0.75)"),
                                    Expression.stop(0.8, "rgba(239,138,98,0.85)"),
                                    Expression.stop(1, "rgba(178,24,43,0.95)")
                                )
                            )
                        )
                    }
                )

                // The "you are here" pin: a separate source/layer (not a Marker annotation)
                // so refreshVisibleProperties()'s removeAnnotations() calls never touch it.
                val locationIcon = createDotIcon(context, LocationBlue.toArgb(), diameterDp = 16f)
                style.addImage(MY_LOCATION_IMAGE_ID, locationIcon.bitmap)
                val locationSource = GeoJsonSource(MY_LOCATION_SOURCE_ID)
                style.addSource(locationSource)
                myLocationSource = locationSource
                style.addLayer(
                    SymbolLayer(MY_LOCATION_LAYER_ID, MY_LOCATION_SOURCE_ID).apply {
                        setProperties(
                            PropertyFactory.iconImage(MY_LOCATION_IMAGE_ID),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true)
                        )
                    }
                )

                // Restores the pin immediately from the last known fix, and repopulates the
                // heatmap by re-running the same query the current viewport/settings would
                // produce anyway — both sources are freshly empty right after being re-added
                // above.
                updateMyLocationSource(lastLocationFix)
                if (hasRequestedInitialCenter) {
                    triggerRefresh()
                }
            }
        )

        // Small corner credit, not a full-width bar — must never sit on top of the
        // locate-me button or the search/settings bar.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            // contentColor is set explicitly here (and everywhere else in this file that uses
            // a Surface with an alpha-adjusted color) because Material3's Surface only infers a
            // readable content color when its `color` is *exactly* one of the ColorScheme's
            // known colors — `.copy(alpha = ...)` breaks that equality check, silently falling
            // back to whatever content color was already ambient (unreadable black text on a
            // dark surface in dark mode) instead of colorScheme.onSurface.
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Text(
                text = stringResource(R.string.map_attribution_text),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(6.dp)
            )
        }

        // Search bar, and (stacked below it in the same Column, never overlapping it) the
        // location-permission banner and any search error — one top-anchored layout instead
        // of several independently floating/absolutely-positioned pieces.
        // windowInsetsPadding(WindowInsets.statusBars) first, *then* the regular 12dp margin —
        // enableEdgeToEdge() (MainActivity) means content draws behind the system status bar,
        // so without this the search bar (and its settings button) render partly underneath
        // it. A fixed padding isn't enough: status bar height varies by device — e.g. a
        // camera-cutout Pixel profile reserves ~136px here, versus ~24px on a plain emulator
        // skin — and on the taller ones a real chunk of the settings button ends up under the
        // status bar, which swallows the touch before the app ever sees it, making the button
        // silently fail to respond despite looking perfectly tappable.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(12.dp)
        ) {
            SearchBar(
                query = searchQuery,
                onQueryChange = {
                    searchQuery = it
                    searchError = null
                },
                onSearch = { scope.launch { performSearch() } },
                onOpenSettings = onOpenSettings
            )

            searchError?.let { error ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (!hasLocationPermission) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.location_permission_rationale),
                            textAlign = TextAlign.Center
                        )
                        Button(
                            modifier = Modifier.padding(top = 8.dp),
                            onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        ) {
                            Text(stringResource(R.string.grant_permission))
                        }
                    }
                }
            }
        }

        if (repository == null) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "Preparing local price data (first launch only)…",
                        modifier = Modifier.padding(top = 12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (hasLoadedOnce && !isLoading) {
            if (isZoomedOutTooFar) {
                Surface(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Text(
                        text = "Zoom in to see sold prices",
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else if (nearbyProperties.isEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    val recencyNote = if (settings.recentOnly) " in the last 12 months" else ""
                    Text(
                        text = "No sold-price data visible here$recencyNote.",
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        addressPickerOptions?.let { options ->
            AddressPickerSheet(
                properties = options,
                onSelect = { property ->
                    selectedProperty = property
                    addressPickerOptions = null
                },
                onDismiss = { addressPickerOptions = null }
            )
        }

        selectedProperty?.let { property ->
            PropertyDetailCard(
                property = property,
                nearbySales = nearbyProperties,
                saleHistory = saleHistory,
                onDismiss = { selectedProperty = null },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Hidden while a property card is open — both sit in the same bottom-right corner,
        // and the card's own Close button already covers "get me out of this".
        if (selectedProperty == null) {
            FloatingActionButton(
                onClick = { scope.launch { centerOnCurrentLocation() } },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_locate),
                    contentDescription = stringResource(R.string.locate_me)
                )
            }
        }
    }
}

/** Embeds the classic MapLibre [MapView] in Compose, forwarding its lifecycle correctly. */
@Composable
private fun MapLibreView(
    modifier: Modifier = Modifier,
    styleUrl: String,
    // Fires exactly once, the first time the underlying MapLibreMap becomes available — for
    // setup that belongs to the map object itself (camera bounds, click/idle listeners), not to
    // whatever style happens to be loaded.
    onMapReady: (MapLibreMap) -> Unit,
    // Fires every time a style finishes loading, including the very first one — for anything
    // added on top of the Style (sources/layers), since MapLibre's setStyle() replaces the whole
    // Style object and silently drops those each time.
    onStyleLoaded: (MapLibreMap, Style) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // onCreate must fire exactly once; `remember` guarantees that, so it's kept out of the
    // lifecycle observer below (which only forwards the events *after* creation).
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var readyMap by remember { mutableStateOf<MapLibreMap?>(null) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Keyed on styleUrl (as well as readyMap, so this doesn't fire before the map exists) rather
    // than called directly from the AndroidView factory below — that factory only ever runs
    // once, so it can't be what reacts to styleUrl changing on a later recomposition (e.g. the
    // dark-mode setting being toggled after the map's already on screen).
    LaunchedEffect(readyMap, styleUrl) {
        val currentMap = readyMap ?: return@LaunchedEffect
        currentMap.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
            onStyleLoaded(currentMap, style)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    onMapReady(map)
                    readyMap = map
                }
            }
        }
    )
}
