package com.soldnearby.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * MVP data source: a bundled, offline snapshot (see /tools/build_seed_data.py).
 * Swap this class for a network-backed implementation once the dataset needs to cover
 * more than what's bundled — the UI layer only depends on this interface shape.
 */
class PropertyRepository(context: Context) {
    private val db = PriceDatabase.open(context)

    companion object {
        /**
         * Every query in this class runs here, and this dispatcher runs exactly one at a time.
         *
         * They were already serialising — the bundled database is opened read-only and isn't in
         * WAL mode, so Android caps its connection pool at one — just invisibly, deep inside
         * SQLite, and in a way that made cancellation meaningless. Cancelling a coroutine does
         * nothing to a `rawQuery` already executing on a plain `Dispatchers.IO` thread: it runs
         * to completion holding the single connection, and only its *result* gets discarded. So
         * a burst of camera-idle events (see MapScreen.triggerRefresh, which cancels the
         * previous refresh on each one) queued up N full query executions, with the newest —
         * the only one whose result anyone still wanted — last in line behind all the obsolete
         * ones. That backlog is what made a fast pan look like an endless spinner: the map kept
         * "loading" long after the user had stopped moving, because it was still grinding
         * through queries for places they'd already panned away from.
         *
         * Making the serialisation explicit here is what gives cancellation teeth: a refresh
         * cancelled before it reaches the front of this queue never starts its query at all.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val queryDispatcher = Dispatchers.IO.limitedParallelism(1)
    }

    /** Queries whatever's actually on screen, not a fixed radius — that way results scale
     *  with zoom naturally instead of the same small real-world patch looking like a tiny
     *  clump in the middle once you've zoomed out far enough to see a much bigger area. */
    suspend fun withinBounds(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        recentOnly: Boolean
    ): List<SoldProperty> = withContext(queryDispatcher) {
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
    suspend fun historyForAddress(property: SoldProperty): List<SoldProperty> = withContext(queryDispatcher) {
        db.historyForAddress(address = property.address, postcode = property.postcode)
    }

    /** Sale counts per postcode sector within bounds, over the last 2 years — the data behind
     *  the "recent sales heatmap". Always a fixed 2-year window regardless of the "recent sales
     *  only" toggle (that one controls the dots; this is a separate, heatmap-only setting of
     *  what "recent" means). Anchored to the newest sale actually in the bundled dataset, not
     *  today, for the same reason as [recentCutoff] — the data doesn't necessarily reach up to
     *  today, so anchoring on "today" could filter out everything.
     *
     *  Because none of that varies at runtime, the window is applied once at build time and this
     *  is now a filter over an in-memory table rather than a query (see
     *  [PriceDatabase.sectorsWithinBounds]) — which is what let MapScreen drop the zoom floor
     *  that used to be needed to stop this from stalling the whole map. Still off the main
     *  thread: the first call has to read the table in, and the filter itself runs over every
     *  sector in the country. */
    suspend fun salesDensityBySector(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<PostcodeSectorPrice> = withContext(queryDispatcher) {
        db.sectorsWithinBounds(minLat = minLat, maxLat = maxLat, minLng = minLng, maxLng = maxLng)
    }
}
