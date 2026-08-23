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
import androidx.lifecycle.repeatOnLifecycle
import com.google.gson.JsonObject
import com.soldnearby.app.R
import com.soldnearby.app.data.AppSettings
import com.soldnearby.app.data.PostcodeDensity
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
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.atomic.AtomicReference

/** Used only until we have a real location fix (or if permission is denied). */
private val LONDON_FALLBACK = LatLng(51.5074, -0.1278)
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

/** Nowhere outside the dataset is ever useful to look at, so the camera target is boxed in to
 *  roughly where the data actually is, plus about 25km of margin on each side.
 *
 *  That's England and Wales — HM Land Registry's Price Paid Data doesn't cover Scotland or
 *  Northern Ireland (they have their own registers), so despite the app's name there is nothing
 *  north of the Scottish border to show. Measured over the current build, every row falls inside
 *  lat 49.895..55.797, lng -6.353..1.763 — Scilly in the south west, Berwick in the north,
 *  Lowestoft in the east — with no outliers beyond.
 *
 *  This used to reach to lat 61 and lng -8.75, a box drawn around the whole UK including
 *  Shetland. Since it only constrains the camera *target*, that let you pan the centre of the
 *  screen hundreds of miles into empty ocean or up to Scotland, where the map is guaranteed to
 *  be blank — the app looking broken when it was really just pointed somewhere it has no data
 *  for. */
private val UK_BOUNDS = LatLngBounds.from(latNorth = 56.05, lonEast = 2.15, latSouth = 49.65, lonWest = -6.75)
/** Keeps a pinch-out from zooming past roughly "the whole dataset fills the screen" — paired
 *  with [UK_BOUNDS], since the bounds alone only restrict the camera *target*, not how far out
 *  you can zoom while centred near an edge.
 *
 *  Deliberately no higher than this: at this zoom the England-and-Wales box is 311 x 375dp, so
 *  it still fits edge to edge across the narrowest screen Android phones realistically have
 *  (320dp). Raising it further would frame the data more tightly on a typical handset but leave
 *  someone on a narrow one unable to see the whole country at once, which is the worse trade. */
private const val UK_MIN_ZOOM = 4.75

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
private const val FINE_HEATMAP_SOURCE_ID = "postcode-density-heatmap-source"
private const val FINE_HEATMAP_LAYER_ID = "postcode-density-heatmap-layer"

/** Where the heatmap stops describing sectors and starts describing individual postcodes, and
 *  where that handover has finished.
 *
 *  One point per postcode *sector* simply runs out of resolution as you zoom in. Measured
 *  against the bundled dataset, a padded viewport holds 3 sector centroids over Ipswich and 30
 *  over central London at zoom 16 — and each of those points sits at the mean of its sector's
 *  sale coordinates, a mean 254-395m and up to 1.6km from the sales it stands for. A handful of
 *  Gaussian blobs at averaged positions can't say which street is busy, which is why the heat
 *  didn't sit over the dots underneath it. Above this the source switches to postcode_stats,
 *  whose points are the exact coordinates the dots are drawn at.
 *
 *  13.5 rather than lower because the fine tier's cost scales with the area on screen: a padded
 *  central-London viewport — the densest in the country — holds ~11k postcodes at 13.5 and ~5.7k
 *  at 14, but ~34k at 12.5. Deliberately *not* equal to [MIN_ZOOM_FOR_DOTS] either, so the dots
 *  appearing and the heat surface changing resolution aren't the same moment on the way in. */
private const val MIN_ZOOM_FOR_FINE_HEAT = 13.5
private const val FINE_HEAT_FULL_ZOOM = 14.0

/** A bound on how many postcode points one refresh will hold, not a shaping choice — unlike
 *  findInBounds' per-cell cap, which deliberately samples the dots, dropping heat points would
 *  understate the very density this layer exists to show.
 *
 *  It must never actually bind, because the query behind it has no ORDER BY: it reads straight
 *  off idx_postcode_stats_lat_lng, so a truncated result is the *southernmost* N points of the
 *  view and the heat would simply stop along a horizontal line partway up the screen. Sizing is
 *  therefore about headroom, not about a typical case.
 *
 *  Measured worst case is ~10.5k, searching the densest z13.5-sized window in the country (inner
 *  London, sampled over the 400 busiest grid cells) against a 1080x2000 phone viewport. This app
 *  also ships for tablets, and a 10-inch one has roughly twice that pixel area and so sees
 *  roughly twice the postcodes — call it ~21k. 50k leaves headroom past even that, on a device
 *  with a viewport around five times a phone's. */
private const val FINE_HEAT_MAX_POINTS = 50_000

/** Ceiling of the heat tint, for both tiers. Deliberately below a typical heatmap's 0.7 so it
 *  reads as a tint over the map rather than obscuring it. */
private const val HEATMAP_MAX_OPACITY = 0.55f
private const val MY_LOCATION_SOURCE_ID = "my-location-source"
private const val MY_LOCATION_LAYER_ID = "my-location-layer"
private const val MY_LOCATION_IMAGE_ID = "my-location-icon"

/** How often the "you are here" dot asks for a fix and moves to follow the user. Only the
 *  dot follows on this cadence — the camera never does, so panning/zooming around the map
 *  stays completely free; it only snaps to the latest fix when the locate-me button is
 *  pressed (see centerOnCurrentLocation). */
private const val MY_LOCATION_UPDATE_INTERVAL_MS = 1500L

/** The zoom the camera settles at whenever it centres on a specific place — the user's
 *  own position, or a search result. */
private const val CENTRED_ZOOM = 14.5

/** How long the locate-me button waits for a brand-new fix before giving up on refining the
 *  position it already snapped to. Only ever affects the *second* of that button's two camera
 *  moves — the first one has already happened by the time this starts. */
private const val LOCATE_FRESH_FIX_TIMEOUT_MS = 8000L

/** A cached fix older than this isn't used to snap the camera. Play Services' "last known"
 *  location has no age limit of its own, and jumping to where the phone was hours ago is worse
 *  than not jumping at all. Generous because the live subscription (see locationUpdates) keeps
 *  the fix current whenever the app is actually on screen — this bound only really matters for
 *  the first press after a cold start. */
private const val LOCATE_CACHED_FIX_MAX_AGE_MS = 120_000L

/** How far a freshly-acquired fix has to be from the one the camera already snapped to before
 *  it's worth animating a second time. Below this the correction isn't visible at [CENTRED_ZOOM]
 *  and the extra movement just reads as the map drifting on its own. */
private const val LOCATE_REFINE_MIN_METRES = 30.0

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
    // The screen's one transient-message slot, shown as a banner under the search bar.
    // Shared by search and by the locate-me button, which is why it isn't named for either.
    var bannerMessage by remember { mutableStateOf<String?>(null) }
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
    // The fine tier's source, added and repopulated on exactly the same terms as heatmapSource
    // above — the two are one surface at two resolutions, cross-faded by zoom.
    var fineHeatmapSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    // Set once in onMapReady alongside heatmapSource, updated via setGeoJson whenever a fresh
    // GPS fix comes in — kept as its own source/layer rather than a Marker annotation because
    // refreshVisibleProperties() calls removeAnnotations() on every pan/zoom, which would wipe
    // out an annotation-based pin along with the sold-price dots.
    var myLocationSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    // The last position pushed to myLocationSource, kept around for two things: a style swap
    // (dark-mode toggle) — which replaces the whole Style, wiping myLocationSource along with
    // it — can restore the pin on the freshly re-added source instead of leaving it blank until
    // the next GPS fix; and the locate-me button reads it to move the camera immediately,
    // without waiting on the location hardware (see centerOnCurrentLocation).
    // Deliberately *not* Compose state: it's written on every fix that arrives, and nothing on
    // screen is derived from it by composition (both readers are callbacks), so making it
    // observable would recompose this whole screen once or twice a second for no redraw.
    val lastLocationFix = remember { AtomicReference<LatLng?>(null) }
    // The map fires a camera-idle event of its own right after the style loads, before we've
    // centred on anything meaningful. This flag stops that one from triggering a real load.
    var hasRequestedInitialCenter by remember { mutableStateOf(false) }
    // Cancelled and replaced on every camera-idle rather than left to run to completion — a
    // fling or a fast pinch-zoom fires several idle events in quick succession, and letting each
    // one's DB query run to completion would mean multiple queries racing to redraw the map with
    // whichever happens to finish last, not the one for where the camera actually ended up.
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    // Whatever centring-on-the-user is currently in flight, cancelled by the next request for
    // one. Without this, a press whose fresh-fix request is still outstanding can resolve after
    // a later press already moved the camera, and yank it back to the older position.
    var locateJob by remember { mutableStateOf<Job?>(null) }
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
    // The two heatmap tiers each turn sale counts into normalized (0..1) heat weights, but they
    // cannot share a formula — their distributions are nothing alike. Across the country a
    // sector holds 1..987 sales (mean 204); a single postcode holds 1..135 (mean 2.27, and the
    // majority hold just one or two). A scale that reads well for one flattens the other.
    //
    // Both scale relative to what's currently loaded rather than to a fixed nationwide range, so
    // the low/high end of the ramp is always "quietest/busiest thing on screen right now" and
    // stays meaningful whether you're looking at central London or a small town.
    //
    // Building the FeatureCollection is done off the main thread (setGeoJson itself still has to
    // happen on it): zoomed out the coarse tier is every sector in the country (~8k points), and
    // the fine tier reaches ~11k over central London — enough allocation and serialisation to
    // drop frames if it ran on the UI thread.
    // Takes a lambda rather than a ready-made list so that the *whole* computation — the sort
    // and percentile pick as well as the per-point weighting — runs off the main thread, not
    // just the feature building. refreshVisibleProperties runs on the main dispatcher, and the
    // fine tier sorts up to FINE_HEAT_MAX_POINTS entries.
    suspend fun buildHeatFeatures(
        points: () -> List<Pair<LatLng, Double>>
    ): FeatureCollection = withContext(Dispatchers.Default) {
        FeatureCollection.fromFeatures(
            points().map { (position, weight) ->
                Feature.fromGeometry(
                    Point.fromLngLat(position.longitude, position.latitude),
                    JsonObject().apply { addProperty("weight", weight) }
                )
            }
        )
    }

    // Coarse tier. Scaled from the 5th percentile up to the outright maximum, through a square
    // root.
    //
    // The floor is a percentile rather than the minimum because a single 1-sale sector otherwise
    // anchors the bottom of the scale, and both ends then become a step function of whether that
    // one sector happens to be on screen — panning it in or out visibly rescaled the whole
    // surface at once.
    //
    // The ceiling used to be the 95th percentile for the same reason, but clipping there meant
    // every sector in the top 5% rendered identically at full heat: over a Greater London view
    // that pinned 61 of 1,196 sectors to 1.0, with the ceiling at 380 sales against a true
    // maximum of 987. Which patch looked hottest was then decided by how tightly those 61 tied
    // points happened to cluster — MapLibre sums overlapping kernels — rather than by sale
    // count, so the busiest area in the country was not the reddest thing on screen.
    //
    // The square root is what makes scaling to the true maximum affordable. Linearly, that one
    // outlier squashes the median London sector to 0.186 and the surface washes out — exactly
    // what the p95 clip was introduced to prevent. sqrt lifts the mid-range back: measured over
    // the same view it puts the median at 0.431 (against 0.485 under the old clip, so no visible
    // washout), drops the former p95 sector from 1.0 to 0.619, and leaves the genuinely busiest
    // sector as the only thing at full heat.
    suspend fun updateHeatmapSource(sectors: List<PostcodeSectorPrice>) {
        if (sectors.isEmpty()) {
            heatmapSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return
        }
        val collection = buildHeatFeatures {
            val sorted = sectors.map { it.saleCount }.sorted()
            val low = sorted[(sorted.size - 1) * 5 / 100]
            val high = sorted.last()
            val range = (high - low).toDouble()
            sectors.map { sector ->
                // range == 0 means every sector in view is equally busy (or there's only one),
                // in which case there's no contrast to show and a flat full weight is the honest
                // rendering — normalizing would divide by zero or wash the lot out to nothing.
                val weight =
                    if (range <= 0.0) 1.0
                    else sqrt(((sector.saleCount - low) / range).coerceIn(0.0, 1.0))
                LatLng(sector.latitude, sector.longitude) to weight
            }
        }
        // Deliberately read after the suspension above, not before it: a dark-mode toggle
        // replaces the whole Style mid-refresh, and the GeoJsonSource this held a moment ago
        // would by then be orphaned from a destroyed style. onStyleLoaded repopulates the fresh
        // one itself, so dropping this update is the correct outcome, not a lost frame.
        heatmapSource?.setGeoJson(collection)
    }

    // Fine tier. Scaled from *zero* up to the 95th percentile — the one place these two
    // functions deliberately disagree.
    //
    // Subtracting a low percentile the way the coarse tier does would be actively wrong here.
    // Per-postcode counts run 1..135 with a mean of 2.27, so the 5th percentile is 1 — and
    // subtracting it would assign weight 0 to every postcode with a single sale, which is most
    // of them. The cool background would vanish and only the handful of busy blocks would render,
    // turning a density surface into scattered dots. Starting at zero keeps one sale meaningfully
    // warm and lets the density come from kernel accumulation across neighbouring postcodes,
    // which is precisely the "busy where lots has sold" signal wanted.
    //
    // The ceiling is a percentile, not the maximum, because the fine tier's outliers are extreme
    // relative to its median: one large block of flats transacting can hold 100+ sales against a
    // typical postcode's 2, and scaling to that would flatten every ordinary street to nothing.
    // Clipping the top few percent costs little here — unlike the coarse tier, the visible peak
    // is carried by how many warm postcodes overlap, not by any single point reaching 1.0.
    suspend fun updateFineHeatmapSource(postcodes: List<PostcodeDensity>) {
        if (postcodes.isEmpty()) {
            fineHeatmapSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return
        }
        val collection = buildHeatFeatures {
            val sorted = postcodes.map { it.saleCount }.sorted()
            // coerceAtLeast(1) guards the divide: a view where every postcode has one sale gives
            // a p95 of 1, and everything there is equally busy anyway.
            val high = sorted[(sorted.size - 1) * 95 / 100].coerceAtLeast(1).toDouble()
            postcodes.map { postcode ->
                val weight = (postcode.saleCount / high).coerceIn(0.0, 1.0)
                LatLng(postcode.latitude, postcode.longitude) to weight
            }
        }
        fineHeatmapSource?.setGeoJson(collection)
    }

    // Empty position clears the pin (permission denied, or a fix that never resolved) rather
    // than leaving a stale one sitting somewhere the user no longer is.
    fun updateMyLocationSource(position: LatLng?) {
        lastLocationFix.set(position)
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

        // Both tiers are updated before the dots' zoom gate below rather than after it: unlike
        // the dots, a density surface is *most* useful zoomed out.
        //
        // The coarse tier stays unconditional because it costs effectively nothing at any zoom —
        // it's an in-memory filter over a precomputed table (see
        // PropertyRepository.salesDensityBySector), not the viewport-wide GROUP BY it used to
        // be. That query is why it used to need a zoom floor of its own.
        //
        // The fine tier is gated, because it is a real bounds query and its cost scales with the
        // area on screen — the very thing the precomputed rollup exists to avoid. Below
        // MIN_ZOOM_FOR_FINE_HEAT it's cleared rather than skipped: its layer has already faded
        // out by then, but leaving a stale collection in the source would keep MapLibre holding
        // (and, on the way back in, briefly drawing) points for wherever the camera used to be.
        val heatEnabled = currentSettings.heatmapEnabled
        updateHeatmapSource(
            if (heatEnabled) repo.salesDensityBySector(minLat, maxLat, minLng, maxLng) else emptyList()
        )
        updateFineHeatmapSource(
            if (heatEnabled && zoom >= MIN_ZOOM_FOR_FINE_HEAT) {
                repo.postcodeDensityWithinBounds(
                    minLat, maxLat, minLng, maxLng, limit = FINE_HEAT_MAX_POINTS
                )
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

    // Moves the camera to the user in two stages, and the first one is the point of the whole
    // arrangement: it uses a position already in hand, so the map starts moving on the same
    // frame as the tap, every time.
    //
    // This used to be one stage — await currentLocation(), then animate — which meant the
    // button did nothing at all until the location hardware produced a brand-new fix. The very
    // first press was reliably instant because startup had just fetched one and the engine was
    // still warm; later presses, after the user had moved around, had to wait out a real
    // acquisition (seconds indoors, sometimes never), and a press that resolved to null
    // *animated the camera to London* and cleared the "you are here" dot on the way past. That
    // is the reported "sometimes it doesn't snap".
    //
    // Stage two still asks for a genuinely fresh fix and corrects the camera if it lands more
    // than LOCATE_REFINE_MIN_METRES from where stage one put it, so accuracy isn't traded away
    // for the responsiveness — it's just no longer on the critical path. A null or timed-out
    // stage two now leaves both camera and dot exactly where stage one put them.
    suspend fun centerOnCurrentLocation(isInitialCentring: Boolean) {
        val currentMap = map ?: return
        // Set before any camera move, not after: it gates the camera-idle listener that loads
        // the markers, and that listener fires when the animation *ends*.
        hasRequestedInitialCenter = true

        // Falling back to London is only ever right for the very first centring, and only when
        // there's nothing better anywhere — it isn't where the user is, so a locate-me press
        // must never end up there.
        fun settleForFallback() {
            if (isInitialCentring) {
                currentMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LONDON_FALLBACK, CENTRED_ZOOM))
            }
        }

        if (!hasLocationPermission) {
            updateMyLocationSource(null)
            settleForFallback()
            return
        }
        if (!isInitialCentring) bannerMessage = null

        // Stage one: the live subscription's latest fix, or failing that Play Services' cache.
        val known = lastLocationFix.get()
            ?: runCatching { locationTracker.lastKnownLocation(LOCATE_CACHED_FIX_MAX_AGE_MS) }
                .getOrNull()?.let { (lat, lng) -> LatLng(lat, lng) }
        if (known != null) {
            updateMyLocationSource(known)
            currentMap.animateCamera(CameraUpdateFactory.newLatLngZoom(known, CENTRED_ZOOM))
        }

        // Stage two: a real acquisition, bounded so a fix that never arrives can't leave this
        // coroutine (and with it the next press's cancellation target) hanging around forever.
        val fresh = withTimeoutOrNull(LOCATE_FRESH_FIX_TIMEOUT_MS) {
            runCatching { locationTracker.currentLocation(highAccuracy = true) }.getOrNull()
        }?.let { (lat, lng) -> LatLng(lat, lng) }

        if (fresh == null) {
            if (known == null) {
                // Nothing cached and nothing acquired: there is no position to snap to, so the
                // button has genuinely done nothing — say so rather than leaving it looking
                // broken (or, as it used to, silently flying the map to London). Usually means
                // location is switched off device-wide, or an emulator with no fix set.
                if (!isInitialCentring) bannerMessage = "Couldn't get your location — check location is turned on."
                settleForFallback()
            }
            return
        }
        updateMyLocationSource(fresh)
        if (known == null || known.distanceTo(fresh) > LOCATE_REFINE_MIN_METRES) {
            currentMap.animateCamera(CameraUpdateFactory.newLatLngZoom(fresh, CENTRED_ZOOM))
        }
    }

    // Every centring goes through here so that each one cancels the last. Loading the markers
    // happens once the camera settles and the camera-idle listener fires — never from in here —
    // so a manual pan and a GPS re-centre go through one path.
    fun requestCenterOnCurrentLocation(isInitialCentring: Boolean) {
        locateJob?.cancel()
        locateJob = scope.launch { centerOnCurrentLocation(isInitialCentring) }
    }

    suspend fun performSearch() {
        val query = searchQuery
        val currentMap = map
        if (query.isBlank() || currentMap == null) return
        keyboardController?.hide()
        bannerMessage = null
        isLoading = true
        val result = GeocodeSearch.search(query)
        isLoading = false
        if (result == null) {
            bannerMessage = "Couldn't find \"$query\""
            return
        }
        hasRequestedInitialCenter = true
        currentMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(result.latitude, result.longitude), CENTRED_ZOOM)
        )
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    LaunchedEffect(map, hasLocationPermission) {
        if (map != null) requestCenterOnCurrentLocation(isInitialCentring = true)
    }

    // Keeps the "you are here" dot following the user as they actually move, instead of the
    // one-shot fix above going stale the moment they walk/drive away from where the app was
    // opened — and, just as importantly, keeps lastLocationFix current so the locate-me button
    // has somewhere accurate to snap to immediately. Deliberately only ever touches the dot
    // (updateMyLocationSource), never the camera: free panning/zooming stays free, and the map
    // only moves when centerOnCurrentLocation is called.
    //
    // repeatOnLifecycle(STARTED) rather than a poll loop that checks the lifecycle itself:
    // below STARTED onStop has already fired, so there's no visible dot to update and no reason
    // to keep the location engine running for a screen no one can see. Ending the collection is
    // what actually unsubscribes (see locationUpdates' awaitClose), where the old loop only
    // skipped its turn and kept the timer going.
    LaunchedEffect(map, hasLocationPermission) {
        if (map == null || !hasLocationPermission) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            locationTracker.locationUpdates(MY_LOCATION_UPDATE_INTERVAL_MS).collect { (lat, lng) ->
                updateMyLocationSource(LatLng(lat, lng))
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
                // Shared by both tiers — the surface should read identically whichever one is
                // drawing it, so this is defined once rather than restated per layer.
                //
                // Stops bunched towards the bottom rather than spread evenly, because the
                // accumulated density is: across one view the quiet edges and the busiest core
                // differ by ~50x, so evenly spaced stops spend most of the ramp on values almost
                // nothing has, showing a hot core over a uniformly blank surround. Roughly
                // logarithmic spacing gives the sparse end somewhere to land, which is what makes
                // a town read as a town rather than as empty map.
                //
                // Still feathers in from fully transparent well before the first colour stop, so
                // a blob's edge fades out gradually instead of an abrupt transparent-to-blue
                // boundary — that's what lets adjacent points' blobs blend rather than looking
                // like separate circles butted up against each other.
                val heatColorRamp = Expression.interpolate(
                    Expression.linear(), Expression.heatmapDensity(),
                    Expression.stop(0, "rgba(0,0,0,0)"),
                    Expression.stop(0.015, "rgba(33,102,172,0.12)"),
                    Expression.stop(0.05, "rgba(33,102,172,0.35)"),
                    Expression.stop(0.12, "rgba(103,169,207,0.55)"),
                    Expression.stop(0.25, "rgba(253,219,199,0.65)"),
                    Expression.stop(0.5, "rgba(239,138,98,0.8)"),
                    Expression.stop(1, "rgba(178,24,43,0.92)")
                )

                val source = GeoJsonSource(HEATMAP_SOURCE_ID)
                style.addSource(source)
                heatmapSource = source
                style.addLayer(
                    HeatmapLayer(HEATMAP_LAYER_ID, HEATMAP_SOURCE_ID).apply {
                        setProperties(
                            // "weight" is pre-normalized to 0..1 in updateHeatmapSource, relative
                            // to whatever sectors are currently loaded — not a fixed density scale.
                            PropertyFactory.heatmapWeight(Expression.get("weight")),
                            // Radius and intensity are one setting in two halves, and the pair is
                            // what stops the country turning into a single red blob when zoomed
                            // out. MapLibre *accumulates* every point's kernel: the density at a
                            // pixel is the sum of (weight x intensity x gaussian) over every
                            // sector within a radius of it, and the colour ramp above is sampled
                            // at min(density, 1) — so once enough kernels overlap, everything is
                            // pinned to the top of the ramp and all contrast is gone.
                            //
                            // How much they overlap is what changes with zoom. Zoomed out, all
                            // ~8k sectors are on screen at once, only a pixel or two apart; the
                            // previous stops (which interpolate() clamps to a flat 60px radius
                            // and 1.5 intensity below zoom 8) put ~1,100 kernels within a radius
                            // of a typical pixel over England, for a density around 30 where the
                            // ramp tops out at 1. Simulated against the real sector_stats table,
                            // that left 79% of the land area at the maximum-red stop at zoom 6.5
                            // — the reported blob, and not really a colour choice at all but an
                            // arithmetic overflow of the accumulation.
                            //
                            // So: shrink the radius sharply as zoom decreases (fewer overlapping
                            // kernels), and drop the intensity along with it (each contributes
                            // less) — enough that the busiest sectors land near 1.0 instead of
                            // 30. Same simulation now puts under 3% of the screen at the top
                            // stop at every zoom, with cities reading as distinct hot spots on a
                            // cool country rather than the whole map reading as one hot spot.
                            // These are eyeballed as well as computed; if the surface ever looks
                            // washed out or blown out again, this pair is the place to adjust.
                            //
                            // Only the stops up to FINE_HEAT_FULL_ZOOM matter now — this layer is
                            // fully faded out above it — but the upper ones are kept so the curve
                            // doesn't extrapolate oddly across the fade itself.
                            PropertyFactory.heatmapIntensity(
                                Expression.interpolate(
                                    Expression.linear(), Expression.zoom(),
                                    Expression.stop(4, 0.16f), Expression.stop(7, 0.27f),
                                    Expression.stop(9, 0.48f), Expression.stop(10.5, 1.25f),
                                    Expression.stop(12, 1.9f), Expression.stop(14, 3.1f),
                                    Expression.stop(16, 3.4f)
                                )
                            ),
                            // salesDensityBySector returns one point per postcode sector, not
                            // one per sale — sparse compared to a per-transaction heatmap would
                            // be. Zoomed in, the radius has to stay well above the on-screen gap
                            // between neighbouring sectors or each one reads as an isolated soft
                            // dot with map showing through between them; zoomed out that gap
                            // collapses to a pixel or two and the same radius is what buries the
                            // country (see above), so it has to collapse with it.
                            PropertyFactory.heatmapRadius(
                                Expression.interpolate(
                                    Expression.linear(), Expression.zoom(),
                                    Expression.stop(4, 9f), Expression.stop(6, 14f),
                                    Expression.stop(8, 24f), Expression.stop(10, 42f),
                                    Expression.stop(12, 80f), Expression.stop(14, 150f),
                                    Expression.stop(16, 220f)
                                )
                            ),
                            // Hands over to the fine tier across MIN_ZOOM_FOR_FINE_HEAT ->
                            // FINE_HEAT_FULL_ZOOM. A cross-fade rather than a hard switch because
                            // the two tiers render visibly different surfaces at the boundary —
                            // swapping them in one frame reads as the map glitching, whereas
                            // fading reads as detail resolving.
                            PropertyFactory.heatmapOpacity(
                                Expression.interpolate(
                                    Expression.linear(), Expression.zoom(),
                                    Expression.stop(MIN_ZOOM_FOR_FINE_HEAT, HEATMAP_MAX_OPACITY),
                                    Expression.stop(FINE_HEAT_FULL_ZOOM, 0f)
                                )
                            ),
                            PropertyFactory.heatmapColor(heatColorRamp)
                        )
                    }
                )

                val fineSource = GeoJsonSource(FINE_HEATMAP_SOURCE_ID)
                style.addSource(fineSource)
                fineHeatmapSource = fineSource
                // Added after the coarse layer so it draws on top through the cross-fade; with
                // the order reversed the outgoing sector blobs would sit over the incoming
                // detail and the handover would look like a smear rather than a sharpening.
                style.addLayer(
                    HeatmapLayer(FINE_HEATMAP_LAYER_ID, FINE_HEATMAP_SOURCE_ID).apply {
                        setProperties(
                            PropertyFactory.heatmapWeight(Expression.get("weight")),
                            // Its own radius/intensity curves, not the coarse tier's. The
                            // accumulation arithmetic described above cuts both ways: those
                            // values are tuned for a few hundred sparse sector points, and this
                            // tier puts ~5k tightly packed postcodes on screen at zoom 14 over
                            // central London — roughly 90m, about 30px, apart. So the radius
                            // tracks the actual point spacing rather than a sector's footprint,
                            // and the intensity has to fall to match the far greater overlap.
                            //
                            // Both curves are solved rather than guessed, by simulating the
                            // shader's own accumulation
                            // (sum of weight x intensity x 0.3989 x exp(-4.5 (d/r)^2), sampled at
                            // min(density, 1)) over the real postcode_stats table. The radius
                            // stops were set to the measured point spacing, then the intensity at
                            // each was solved to put the 99.9th-percentile pixel of a central
                            // London view — the densest in the country — exactly at the top of
                            // the ramp. That leaves 0.1% of screen fully saturated and 0.6-3.6%
                            // warm at every zoom, against the coarse tier's <3% ceiling, with
                            // 33-68% carrying visible tint so it still reads as a surface.
                            //
                            // Calibrating on the densest view is what makes the tier honest
                            // across the country: the same curve peaks at 1.8 over London, 0.8-1.5
                            // over Ipswich and 0.4-0.9 over Oxford, so London is the only place
                            // that fully saturates — which is true of the data.
                            //
                            // A first pass at these was ~5x too low, which simulated out at a peak
                            // density of 0.26-0.37: the busiest street in the country would have
                            // rendered pale peach and nothing would ever have gone red. If the
                            // surface ever looks washed out or blown out, re-run that simulation
                            // rather than nudging by eye — the two failure modes look similar on
                            // screen and only the arithmetic separates them.
                            PropertyFactory.heatmapIntensity(
                                Expression.interpolate(
                                    Expression.linear(), Expression.zoom(),
                                    Expression.stop(MIN_ZOOM_FOR_FINE_HEAT, 0.70f),
                                    Expression.stop(14, 0.65f), Expression.stop(15, 1.3f),
                                    Expression.stop(16, 2.25f), Expression.stop(17, 2.45f),
                                    Expression.stop(18, 2.9f)
                                )
                            ),
                            PropertyFactory.heatmapRadius(
                                Expression.interpolate(
                                    Expression.linear(), Expression.zoom(),
                                    Expression.stop(MIN_ZOOM_FOR_FINE_HEAT, 26f),
                                    Expression.stop(14, 34f), Expression.stop(15, 55f),
                                    Expression.stop(16, 80f), Expression.stop(18, 150f)
                                )
                            ),
                            // The mirror of the coarse layer's fade, so the pair sums to a
                            // roughly constant tint across the handover instead of dipping or
                            // doubling in the middle of it.
                            PropertyFactory.heatmapOpacity(
                                Expression.interpolate(
                                    Expression.linear(), Expression.zoom(),
                                    Expression.stop(MIN_ZOOM_FOR_FINE_HEAT, 0f),
                                    Expression.stop(FINE_HEAT_FULL_ZOOM, HEATMAP_MAX_OPACITY)
                                )
                            ),
                            PropertyFactory.heatmapColor(heatColorRamp)
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
                updateMyLocationSource(lastLocationFix.get())
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
                    bannerMessage = null
                },
                onSearch = { scope.launch { performSearch() } },
                onOpenSettings = onOpenSettings
            )

            bannerMessage?.let { error ->
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
                onClick = { requestCenterOnCurrentLocation(isInitialCentring = false) },
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
