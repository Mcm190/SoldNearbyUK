package com.soldnearby.app.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Opens the bundled, pre-built sold-price SQLite database (see /tools/build_seed_data.py).
 * Plain SQLite rather than Room: the database is produced outside the app by the ETL
 * script, so there's no Room schema-hash to keep in lockstep with it.
 */
class PriceDatabase private constructor(private val db: SQLiteDatabase) {

    /**
     * Splits the viewport into cells on a grid aligned to absolute lat/lng multiples of the
     * cell size — not to the viewport's own edges — and caps results per cell, ordered by `id`.
     * Two fixes bundled into one grid, because they're the same mechanism:
     *  - Capping *per cell* rather than once over the whole viewport is what makes sales near
     *    the edges show up at all: a single query ordered by distance-from-centre with one
     *    LIMIT meant rows near the edges were never even fetched once there were more than
     *    `limit` sales near the middle — no amount of post-processing fixes that, since the
     *    fetch itself has to be split up.
     *  - Aligning cells to absolute coordinates (rather than starting the grid at whatever
     *    minLat/minLng the current viewport happens to have) is what makes a dense cluster's
     *    selection *stable* while panning. Cell size still scales with the viewport, so cell
     *    count stays bounded, but with viewport-relative cells, a cell's boundaries — and so
     *    which `perCellLimit` rows out of a crowded street won the cap — shifted on every pan,
     *    even a tiny one. That looked like dots jumping to a different house each time the map
     *    moved, because it was: a different slice of the same cluster won the cap on every
     *    refresh. Ordering by `id` instead of distance-from-cell-centre (which itself moved
     *    every refresh) removes the other half of the same instability.
     *
     * All cells are fetched as one UNION ALL statement rather than one `rawQuery` call per cell
     * (up to (GRID_CELLS_PER_AXIS+1)^2 of them) — that many separate round-trips added enough
     * real wall-clock time per refresh that a fast pan could leave the previous, now-irrelevant
     * refresh still grinding through dozens of queries, competing for the same IO thread as the
     * one for where you actually panned to and making the map feel like it was stuck loading
     * wherever you panned *from*. Each branch is wrapped as a subquery
     * (`SELECT ... FROM (SELECT ... LIMIT ?)`) rather than just parenthesized
     * (`SELECT ... LIMIT ?`) directly — SQLite's compound-select grammar doesn't accept a bare
     * ORDER BY/LIMIT on a parenthesized UNION ALL branch, only on a real subquery.
     *
     * The `NOT EXISTS` clause picks each (postcode, address)'s single most recent row (by
     * date, then by id to break same-date ties — the Price Paid Data source occasionally has
     * two records for one address on the exact same date) *before* the id-ordered LIMIT is
     * applied. Without it, a house that's sold more than once has one row per transaction, all
     * at the identical point — `id` roughly tracks the year each row was ingested, so
     * `ORDER BY id LIMIT` on the raw table systematically favours older sales over newer ones
     * of the same address once a cell has more transactions than its cap, and in any
     * reasonably dense area that's nearly always. That's what let a house's detail card show a
     * years-old, sometimes nominal price (a database with a genuine near-£0 "sale" between,
     * say, family members isn't rare in this data) as the current one, even though the same
     * card's own sale-history list — a separate, unlimited query — correctly showed a more
     * recent sale underneath it.
     */
    fun findInBounds(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        sinceDate: String? = null,
        limit: Int = 500
    ): List<SoldProperty> {
        val dateClause = if (sinceDate != null) "AND date_of_transfer >= ?" else ""
        val cellSql = """
            SELECT $COLUMNS FROM (
                SELECT sp.* FROM sold_properties sp
                WHERE sp.latitude BETWEEN ? AND ? AND sp.longitude BETWEEN ? AND ?
                $dateClause
                AND NOT EXISTS (
                    SELECT 1 FROM sold_properties newer
                    WHERE newer.postcode = sp.postcode AND newer.address = sp.address
                    AND (newer.date_of_transfer > sp.date_of_transfer
                         OR (newer.date_of_transfer = sp.date_of_transfer AND newer.id > sp.id))
                )
                ORDER BY sp.id ASC LIMIT ?
            )
        """.trimIndent()

        val latStep = (maxLat - minLat) / GRID_CELLS_PER_AXIS
        val lngStep = (maxLng - minLng) / GRID_CELLS_PER_AXIS
        val perCellLimit = (limit / (GRID_CELLS_PER_AXIS * GRID_CELLS_PER_AXIS)).coerceAtLeast(1)
        // floor(), not minLat itself: this is what pins cell boundaries to fixed points in
        // absolute space so a small pan mostly reuses the same cells instead of redrawing the
        // whole grid shifted by a few metres.
        val gridOriginLat = floor(minLat / latStep) * latStep
        val gridOriginLng = floor(minLng / lngStep) * lngStep
        val rowCount = ceil((maxLat - gridOriginLat) / latStep).toInt()
        val colCount = ceil((maxLng - gridOriginLng) / lngStep).toInt()

        val cellQueries = mutableListOf<String>()
        val args = mutableListOf<String>()
        for (row in 0 until rowCount) {
            // Clamped to [minLat, maxLat]: grid-aligned cells can overhang the requested bounds
            // at the edges, and the caller (a padded-but-still-bounded viewport) shouldn't get
            // back points from outside what it actually asked for.
            val cellMinLat = (gridOriginLat + latStep * row).coerceAtLeast(minLat)
            val cellMaxLat = (gridOriginLat + latStep * (row + 1)).coerceAtMost(maxLat)
            if (cellMinLat >= cellMaxLat) continue
            for (col in 0 until colCount) {
                val cellMinLng = (gridOriginLng + lngStep * col).coerceAtLeast(minLng)
                val cellMaxLng = (gridOriginLng + lngStep * (col + 1)).coerceAtMost(maxLng)
                if (cellMinLng >= cellMaxLng) continue

                cellQueries += cellSql
                args += listOf(cellMinLat.toString(), cellMaxLat.toString(), cellMinLng.toString(), cellMaxLng.toString())
                if (sinceDate != null) args += sinceDate
                args += perCellLimit.toString()
            }
        }
        if (cellQueries.isEmpty()) return emptyList()

        val sql = cellQueries.joinToString(" UNION ALL ")
        return db.rawQuery(sql, args.toTypedArray()).use(::readAll)
    }

    /**
     * Every transaction recorded against this exact address, newest first — a house that's
     * changed hands more than once shows up as more than one row here. Matches on
     * (postcode, address) rather than id, since the whole point is finding *other* sales of
     * the same property, not just the one that was tapped. Relies on the
     * idx_sold_properties_address index built in [open] — without it this would be a full
     * scan of the 31M-row table on every tap.
     */
    fun historyForAddress(address: String, postcode: String): List<SoldProperty> {
        val sql = """
            SELECT $COLUMNS
            FROM sold_properties
            WHERE postcode = ? AND address = ?
            ORDER BY date_of_transfer DESC
        """.trimIndent()
        return db.rawQuery(sql, arrayOf(postcode, address)).use(::readAll)
    }

    /**
     * Every postcode sector's precomputed 2-year rollup — the data behind the recent-sales
     * heatmap (which weights by sale count, not price; see PropertyRepository). Read once and
     * held in memory: the whole sector_stats table is ~8k rows / a few hundred KB no matter how
     * large sold_properties itself gets, so there's nothing a bounds query would save.
     *
     * This used to be a live `GROUP BY substr(postcode, ...)` over whatever was in the viewport,
     * run on every camera-idle event. Its cost scaled with the *area on screen* rather than with
     * the handful of sectors it returned, so zoomed out it degenerated into a scan of a large
     * fraction of the table — measured at ~1.6s for Greater London, 4.3s for the south east and
     * 18.1s for most of England, on a desktop SSD with a warm cache. Because this file is opened
     * read-only and non-WAL (see [open]), Android gives it a connection pool of exactly one, so
     * each of those scans also blocked every other query behind it. See build_seed_data.py's
     * build_sector_stats() for why precomputing loses nothing.
     *
     * Lazy so an install that never switches the heatmap on never pays for the read at all, and
     * so it doesn't land on the already-slow first launch.
     */
    private val sectorStats: List<PostcodeSectorPrice> by lazy { loadSectorStats() }

    /** The sectors whose centroid falls inside the given bounds. A plain in-memory filter over
     *  [sectorStats] — deliberately not a query. */
    fun sectorsWithinBounds(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<PostcodeSectorPrice> = sectorStats.filter {
        it.latitude >= minLat && it.latitude <= maxLat &&
            it.longitude >= minLng && it.longitude <= maxLng
    }

    private fun loadSectorStats(): List<PostcodeSectorPrice> {
        val sql = "SELECT sector, average_price_gbp, sale_count, latitude, longitude FROM sector_stats"
        val results = mutableListOf<PostcodeSectorPrice>()
        db.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                results += PostcodeSectorPrice(
                    sector = cursor.getString(0),
                    averagePriceGbp = cursor.getDouble(1),
                    saleCount = cursor.getInt(2),
                    latitude = cursor.getDouble(3),
                    longitude = cursor.getDouble(4)
                )
            }
        }
        return results
    }

    /**
     * The most recent sale date anywhere in the bundled dataset, e.g. for a "last 12 months"
     * filter. Computed at most once per [PriceDatabase]: the file is opened read-only and never
     * written to, so this can't change while the app is running, but there's no index on
     * date_of_transfer — the query behind it is a full scan of every row (~0.7s over the current
     * 9.7M). It used to run on *every* refresh whenever the recency filter or the heatmap was
     * on, which meant a full scan queued ahead of the bounds query the user was actually waiting
     * for, on each and every pan and zoom.
     *
     * Lazy rather than computed in [open] so an install that never turns either of those
     * settings on never pays for it at all, and so it doesn't land on the already-slow first
     * launch (which is busy copying the asset).
     */
    private val newestTransferDate: String? by lazy { queryMostRecentDate() }

    fun mostRecentDate(): String? = newestTransferDate

    private fun queryMostRecentDate(): String? {
        db.rawQuery("SELECT MAX(date_of_transfer) FROM sold_properties", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun readAll(cursor: Cursor): List<SoldProperty> {
        val results = mutableListOf<SoldProperty>()
        while (cursor.moveToNext()) {
            results += SoldProperty(
                id = cursor.getLong(0),
                address = cursor.getString(1),
                postcode = cursor.getString(2),
                priceGbp = cursor.getLong(3),
                dateOfTransfer = cursor.getString(4),
                propertyType = cursor.getString(5),
                newBuild = cursor.getInt(6) != 0,
                tenure = cursor.getString(7),
                latitude = cursor.getDouble(8),
                longitude = cursor.getDouble(9)
            )
        }
        return results
    }

    companion object {
        private const val ASSET_NAME = "seed_prices.db"
        private const val VERSION_ASSET_NAME = "seed_prices.version"
        private const val COLUMNS =
            "id, address, postcode, price_gbp, date_of_transfer, property_type, new_build, tenure, latitude, longitude"
        // Up to (GRID_CELLS_PER_AXIS+1)^2 small indexed queries per refresh, not 1 — see
        // findInBounds. Fine enough that the whole viewport gets visible coverage and dense
        // clusters stay stable while panning, coarse enough to stay cheap.
        private const val GRID_CELLS_PER_AXIS = 6
        // kotlin.io.copyTo's own default is 8KB — at the bundled asset's size (currently ~1.7GB
        // decompressed), that's 200,000+ read/inflate/write round trips on first launch alone. A
        // bigger buffer means far fewer of them, cutting per-call overhead substantially. The
        // asset is still Deflate-compressed in the APK (kept that way deliberately to avoid
        // roughly doubling the app's download size), so this doesn't remove the CPU-bound
        // decompression itself — only the extra overhead layered on top of it.
        private const val COPY_BUFFER_SIZE_BYTES = 1 shl 20

        fun open(context: Context): PriceDatabase {
            val dbFile = context.getDatabasePath(ASSET_NAME)
            val versionFile = context.getDatabasePath(VERSION_ASSET_NAME)
            // Leftovers from any earlier version of this app that opened the file read-write
            // (or from a copy interrupted mid-write) — see the sidecarFiles.any(...) check below.
            val sidecarFiles = listOf("-journal", "-wal", "-shm").map { File(dbFile.path + it) }
            val bundledVersion = context.assets.open(VERSION_ASSET_NAME).use { it.reader().readText() }
            val onDeviceVersion = versionFile.takeIf { it.exists() }?.readText()

            // Re-copy whenever the bundled asset doesn't match what's already on disk, not just
            // when nothing's on disk yet — otherwise reseeding with a different --years/
            // --outcode selection (or any future data refresh) would silently do nothing for
            // any install that already has an old copy sitting in app storage. Also re-copy if a
            // -journal/-wal/-shm sidecar is sitting next to it: that means some earlier install
            // (e.g. one built before this file was opened read-only) left a hot journal an
            // OPEN_READONLY connection can't roll back, which fails open() outright with
            // SQLITE_READONLY_ROLLBACK instead of ever returning a usable database. The app never
            // writes to this file once it's copied, so there's no transaction worth recovering —
            // deleting the sidecar and re-copying fresh is simpler than trying to repair it.
            if (!dbFile.exists() || onDeviceVersion != bundledVersion || sidecarFiles.any { it.exists() }) {
                sidecarFiles.forEach { it.delete() }
                dbFile.parentFile?.mkdirs()
                context.assets.open(ASSET_NAME).use { input ->
                    FileOutputStream(dbFile).use { output -> input.copyTo(output, bufferSize = COPY_BUFFER_SIZE_BYTES) }
                }
                versionFile.writeText(bundledVersion)
            }

            // Read-only: both indexes (latitude/longitude and postcode/address) now ship
            // pre-built by build_seed_data.py, so the app never runs a transaction against this
            // file. It used to open read-write purely to CREATE INDEX the address one on-device
            // — over up to 31M rows, that either thrashed low-memory devices badly enough to
            // get killed outright, or, if that kill landed mid-transaction, left the file with a
            // stale journal that threw SQLiteDatabaseLockedException on the next open. Read-only
            // sidesteps that whole failure class: no transaction, no journal.
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            return PriceDatabase(db)
        }
    }
}
