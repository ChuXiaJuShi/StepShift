package com.example.stepshift.network

import com.example.stepshift.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchLocationResult(
    val displayName: String,
    val shortTitle: String,
    val point: GeoPoint,
    val type: String = ""
)

/**
 * Multi-provider Geocoding client:
 * 1. Photon by Komoot (Direct OSM search, high availability in China and globally)
 * 2. Nominatim OpenStreetMap (Fallback)
 * 3. Direct Coordinate Parser
 */
class GeocodingApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): Result<List<SearchLocationResult>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext Result.success(emptyList())

        // 1. Direct coordinate format (e.g. 39.9042, 116.4074)
        val coord = parseDirectCoordinates(trimmed)
        if (coord != null) {
            return@withContext Result.success(listOf(coord))
        }

        // 2. Query Photon Komoot API (OSM powered, 0% packet loss)
        val photonResults = searchViaPhoton(trimmed)
        if (photonResults.isNotEmpty()) {
            return@withContext Result.success(photonResults)
        }

        // 3. Fallback to Nominatim
        val nominatimResults = searchViaNominatim(trimmed)
        if (nominatimResults.isNotEmpty()) {
            return@withContext Result.success(nominatimResults)
        }

        Result.success(emptyList())
    }

    private fun searchViaPhoton(query: String): List<SearchLocationResult> {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://photon.komoot.io/api/?q=$encoded&limit=8"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "StepShift/1.0 (Android)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()

                val bodyStr = response.body?.string() ?: return emptyList()
                val json = JSONObject(bodyStr)
                val features = json.optJSONArray("features") ?: return emptyList()

                val results = mutableListOf<SearchLocationResult>()
                for (i in 0 until features.length()) {
                    val feat = features.getJSONObject(i)
                    val geom = feat.optJSONObject("geometry") ?: continue
                    val coords = geom.optJSONArray("coordinates") ?: continue
                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)

                    val props = feat.optJSONObject("properties") ?: JSONObject()
                    val name = props.optString("name", "")
                    val street = props.optString("street", "")
                    val city = props.optString("city", "")
                    val state = props.optString("state", "")
                    val country = props.optString("country", "")

                    val title = name.ifBlank { street.ifBlank { city.ifBlank { "地点 $i" } } }
                    val descParts = listOf(country, state, city, street, name).filter { it.isNotBlank() }.distinct()
                    val description = descParts.joinToString(", ")

                    results.add(
                        SearchLocationResult(
                            displayName = if (description.isNotBlank()) description else "经纬度: %.5f, %.5f".format(lat, lon),
                            shortTitle = title,
                            point = GeoPoint(lat, lon),
                            type = props.optString("osm_value", "place")
                        )
                    )
                }
                return results
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun searchViaNominatim(query: String): List<SearchLocationResult> {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&addressdetails=1&limit=8"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "StepShift-Android/1.0")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val bodyStr = response.body?.string() ?: return emptyList()
                val jsonArray = JSONArray(bodyStr)
                val results = mutableListOf<SearchLocationResult>()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val lat = item.optDouble("lat", 0.0)
                    val lon = item.optDouble("lon", 0.0)
                    val displayName = item.optString("display_name", "")
                    val name = item.optString("name", "")

                    val title = if (name.isNotBlank()) name else displayName.split(",").firstOrNull() ?: displayName

                    results.add(
                        SearchLocationResult(
                            displayName = displayName,
                            shortTitle = title,
                            point = GeoPoint(lat, lon),
                            type = item.optString("type", "")
                        )
                    )
                }
                return results
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun parseDirectCoordinates(query: String): SearchLocationResult? {
        val parts = query.replace("，", ",").split(",", " ").filter { it.isNotBlank() }
        if (parts.size == 2) {
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                return SearchLocationResult(
                    displayName = "经纬度坐标: %.6f, %.6f".format(lat, lon),
                    shortTitle = "坐标点 (%.4f, %.4f)".format(lat, lon),
                    point = GeoPoint(lat, lon),
                    type = "coordinate"
                )
            }
        }
        return null
    }
}
