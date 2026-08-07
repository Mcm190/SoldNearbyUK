package com.soldnearby.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * MVP data source: a bundled, offline snapshot (see /tools/build_seed_data.py).
 * Swap this class for a network-backed implementation once the dataset needs to cover
 * more than what's bundled — the UI layer only depends on this interface shape.
 */
class PropertyRepository(context: Context) {
    private val db = PriceDatabase.open(context)

    /** Queries whatever's actually on screen, not a fixed radius — that way results scale
     *  with zoom naturally instead of the same small real-world patch looking like a tiny
     *  clump in the middle once you've zoomed out far enough to see a much bigger area. */
    suspend fun withinBounds(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        recentOnly: Boolean
    ): List<SoldProperty> = withContext(Dispatchers.IO) {
        val sinceDate = if (recentOnly) recentCutoff() else null
        db.findInBounds(
            minLat = minLat,
            maxLat = maxLat,
            minLng = minLng,
            maxLng = maxLng,
            sinceDate = sinceDate
        )
    }

    /** 12 months before the newest sale actually in the dataset, not 12 months before today —
     *  the bundled data doesn't necessarily reach up to today, so anchoring on "today" could
     *  filter out everything. */
    private fun recentCutoff(): String? =
        db.mostRecentDate()?.let { LocalDate.parse(it).minusYears(1).toString() }

    /** Every recorded sale of this exact address, newest first. Deliberately ignores the
     *  "recent only" setting — history is specifically about the past, so the recency filter
     *  that trims the map view shouldn't also gut this. */
    suspend fun historyForAddress(property: SoldProperty): List<SoldProperty> = withContext(Dispatchers.IO) {
        db.historyForAddress(address = property.address, postcode = property.postcode)
    }

    /** Sale counts per postcode sector within bounds, over the last 2 years — the data behind
     *  the "recent sales heatmap". Always a fixed 2-year window regardless of the "recent sales
     *  only" toggle (that one controls the dots; this is a separate, heatmap-only setting of
     *  what "recent" means). Anchored to the newest sale actually in the bundled dataset, not
     *  today, for the same reason as [recentCutoff] — the data doesn't necessarily reach up to
     *  today, so anchoring on "today" could filter out everything. */
    suspend fun salesDensityBySector(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<PostcodeSectorPrice> = withContext(Dispatchers.IO) {
        val sinceDate = db.mostRecentDate()?.let { LocalDate.parse(it).minusYears(2).toString() }
        db.averagePriceBySector(minLat = minLat, maxLat = maxLat, minLng = minLng, maxLng = maxLng, sinceDate = sinceDate)
    }
}
