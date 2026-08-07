package com.soldnearby.app.data

/**
 * Aggregated stats for one postcode sector (e.g. "IP2 8", "SW10 2" — the outcode plus the first
 * digit of the incode), over whatever's currently in view. Backs the "recent sales" heatmap,
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
