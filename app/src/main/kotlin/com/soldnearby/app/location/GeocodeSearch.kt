package com.soldnearby.app.location

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SearchResult(val label: String, val latitude: Double, val longitude: Double)

private const val USER_AGENT = "SoldNearby/0.1 (dev build)"

// A UK "outcode" (e.g. OX4, SW19, EC1A) versus a full postcode (outcode + incode, e.g. OX4 1AA).
private val OUTCODE_REGEX = Regex("^[A-Za-z]{1,2}[0-9][A-Za-z0-9]?$")
private val FULL_POSTCODE_REGEX = Regex("^[A-Za-z]{1,2}[0-9][A-Za-z0-9]?\\s*[0-9][A-Za-z]{2}$")

/**
 * Resolves free-text search input — a postcode, an outcode, or a place name — to coordinates.
 * Postcodes/outcodes go to postcodes.io (built for exactly this, and returns a clean area
 * centroid for a bare outcode like "OX4" rather than a random address fragment). Everything
 * else goes to Nominatim, the OpenStreetMap project's general place-name geocoder.
 */
object GeocodeSearch {

    suspend fun search(query: String): SearchResult? = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext null
        try {
            when {
                FULL_POSTCODE_REGEX.matches(trimmed) -> searchPostcode(trimmed)
                OUTCODE_REGEX.matches(trimmed) -> searchOutcode(trimmed)
                else -> searchPlaceName(trimmed)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun searchPostcode(postcode: String): SearchResult? {
        val encoded = URLEncoder.encode(postcode.replace(" ", ""), "UTF-8")
        val text = getText("https://api.postcodes.io/postcodes/$encoded") ?: return null
        val result = JSONObject(text).optJSONObject("result") ?: return null
        return SearchResult(
            label = result.optString("postcode", postcode),
            latitude = result.getDouble("latitude"),
            longitude = result.getDouble("longitude")
        )
    }

    private fun searchOutcode(outcode: String): SearchResult? {
        val encoded = URLEncoder.encode(outcode.uppercase(), "UTF-8")
        val text = getText("https://api.postcodes.io/outcodes/$encoded") ?: return null
        val result = JSONObject(text).optJSONObject("result") ?: return null
        return SearchResult(
            label = result.optString("outcode", outcode),
            latitude = result.getDouble("latitude"),
            longitude = result.getDouble("longitude")
        )
    }

    private fun searchPlaceName(place: String): SearchResult? {
        val encoded = URLEncoder.encode(place, "UTF-8")
        val text = getText(
            "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&countrycodes=gb&limit=1"
        ) ?: return null
        val results = JSONArray(text)
        if (results.length() == 0) return null
        val first = results.getJSONObject(0)
        return SearchResult(
            label = first.optString("display_name", place),
            latitude = first.getString("lat").toDouble(),
            longitude = first.getString("lon").toDouble()
        )
    }

    private fun getText(urlString: String): String? {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            if (connection.responseCode != 200) null else connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }
}
