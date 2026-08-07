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
     * Average price and sale count per postcode sector within bounds — the data behind the
     * recent-sales heatmap (which weights by sale count, not price; see
     * PropertyRepository.salesDensityBySector). A full postcode is always "outcode, space, one
     * digit, two letters" (e.g. "SW1A 1AA"), so
     * dropping the last two characters gives the sector regardless of the outcode's own
     * variable length ("SW1A 1AA" -> "SW1A 1", "OX4 1AB" -> "OX4 1"). Aggregated in SQL rather
     * than pulled row-by-row into Kotlin: GROUP BY collapses what could be thousands of rows
     * per sector down to one, and the existing (latitude, longitude) index still narrows the
     * WHERE clause before that grouping happens. No LIMIT — a bounded viewport only ever
     * contains a small number of distinct sectors, unlike raw per-address rows.
     */
    fun averagePriceBySector(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        sinceDate: String? = null
    ): List<PostcodeSectorPrice> {
        val dateClause = if (sinceDate != null) "AND date_of_transfer >= ?" else ""
        val sql = """
            SELECT substr(postcode, 1, length(postcode) - 2) AS sector,
                   AVG(price_gbp), COUNT(*), AVG(latitude), AVG(longitude)
            FROM sold_properties
            WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?
            $dateClause
            GROUP BY sector
        """.trimIndent()

        val args = mutableListOf(minLat.toString(), maxLat.toString(), minLng.toString(), maxLng.toString())
        if (sinceDate != null) args += sinceDate

        val results = mutableListOf<PostcodeSectorPrice>()
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
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

    /** The most recent sale date anywhere in the bundled dataset, e.g. for a "last 12 months" filter. */
    fun mostRecentDate(): String? {
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
