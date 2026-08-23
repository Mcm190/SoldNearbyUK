package com.soldnearby.app.data

/**
 * Sale count for one full postcode (e.g. "IP2 8HG") over the same fixed 2-year window as
 * [PostcodeSectorPrice], precomputed at build time into the postcode_stats table (see
 * build_seed_data.py). The *fine* tier of the recent-sales heatmap, used above
 * MIN_ZOOM_FOR_FINE_HEAT; [PostcodeSectorPrice] serves everything below it.
 *
 * Weighted by [saleCount] — transactions, not distinct properties. ONSPD gives one coordinate
 * per postcode rather than per address, so a block of flats is a single point here and a single
 * dot on the map, while carrying every one of its sales. That's deliberate: the heatmap is
 * about how much has changed hands, which is exactly the thing the dots can't show.
 *
 * Because there's one coordinate per postcode, [latitude]/[longitude] is not an average of
 * anything — it's the same point the map draws that postcode's dot at, so heat and dots line up
 * exactly rather than to within a sector's mean offset.
 */
data class PostcodeDensity(
    val postcode: String,
    val saleCount: Int,
    val latitude: Double,
    val longitude: Double
)
