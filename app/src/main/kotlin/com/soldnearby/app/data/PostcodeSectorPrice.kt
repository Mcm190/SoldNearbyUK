package com.soldnearby.app.data

/**
 * Aggregated stats for one postcode sector (e.g. "IP2 8", "SW10 2" — the outcode plus the first
 * digit of the incode), over a fixed 2-year window. Precomputed at build time into the
 * sector_stats table (see build_seed_data.py) rather than aggregated per viewport, so these are
 * whole-sector totals — a sector half off the edge of the screen still reports its full count,
 * and its centroid doesn't drift as you pan. Backs the "recent sales" heatmap,
 * which weights by [saleCount] (more sales in the last 2 years = hotter/redder) rather than
 * [averagePriceGbp] — a sector, not a single address, since that's the granularity the heatmap
 * groups at.
 */
data class PostcodeSectorPrice(
    val sector: String,
    val averagePriceGbp: Double,
    val saleCount: Int,
    val latitude: Double,
    val longitude: Double
)
