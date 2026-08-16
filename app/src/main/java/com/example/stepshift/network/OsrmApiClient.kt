package com.example.stepshift.network

import com.example.stepshift.engine.KinematicsCalculator
import com.example.stepshift.model.GeoPoint
import com.example.stepshift.model.RouteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for Walking Routing API with automatic multi-mirror failover:
 * 1. routing.openstreetmap.de (High accessibility mirror)
 * 2. router.project-osrm.org
 * 3. Offline smooth great-circle fallback
 */
class OsrmApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val mirrorEndpoints = listOf(
        "https://routing.openstreetmap.de/routed-foot/route/v1/driving",
        "https://router.project-osrm.org/route/v1/walking"
    )

    suspend fun fetchWalkingRoute(start: GeoPoint, end: GeoPoint): Result<RouteResult> = withContext(Dispatchers.IO) {
        for (baseUrl in mirrorEndpoints) {
            val url = "$baseUrl/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson&steps=true"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "StepShift/1.0 (Android)")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string() ?: ""
                        val json = JSONObject(bodyString)
                        val code = json.optString("code", "")

                        if (code == "Ok") {
                            val routesArray = json.optJSONArray("routes")
                            if (routesArray != null && routesArray.length() > 0) {
                                val firstRoute = routesArray.getJSONObject(0)
                                val totalDistance = firstRoute.optDouble("distance", 0.0)
                                val totalDuration = firstRoute.optDouble("duration", 0.0)

                                val geometry = firstRoute.getJSONObject("geometry")
                                val coordinatesArray = geometry.getJSONArray("coordinates")

                                val points = mutableListOf<GeoPoint>()
                                for (i in 0 until coordinatesArray.length()) {
                                    val coord = coordinatesArray.getJSONArray(i)
                                    val lon = coord.getDouble(0)
                                    val lat = coord.getDouble(1)
                                    points.add(GeoPoint(latitude = lat, longitude = lon))
                                }

                                if (points.isEmpty()) {
                                    points.add(start)
                                    points.add(end)
                                }

                                val result = RouteResult(
                                    points = points,
                                    totalDistanceMeters = totalDistance,
                                    estimatedDurationSeconds = totalDuration,
                                    startAddress = "起点: ${start.formatCoordinates()}",
                                    endAddress = "终点: ${end.formatCoordinates()}",
                                    isFallbackDirect = false
                                )
                                return@withContext Result.success(result)
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {
                // Try next mirror
            }
        }

        // All mirrors failed or network offline -> use smooth great-circle fallback
        val fallback = generateFallbackRoute(start, end)
        Result.success(fallback)
    }

    fun generateFallbackRoute(start: GeoPoint, end: GeoPoint): RouteResult {
        val totalDistance = KinematicsCalculator.computeDistanceMeters(start, end)
        val bearing = KinematicsCalculator.computeBearing(start, end)

        val stepDistance = 15.0
        val stepsCount = (totalDistance / stepDistance).toInt().coerceIn(10, 200)

        val points = mutableListOf<GeoPoint>()
        points.add(start)

        for (i in 1 until stepsCount) {
            val dist = (totalDistance / stepsCount) * i
            val interpolated = KinematicsCalculator.computeDestinationPoint(start, dist, bearing)
            points.add(interpolated)
        }
        points.add(end)

        val speedWalkingMs = 5.0 / 3.6
        val estimatedDuration = totalDistance / speedWalkingMs

        return RouteResult(
            points = points,
            totalDistanceMeters = totalDistance,
            estimatedDurationSeconds = estimatedDuration,
            startAddress = "起点: ${start.formatCoordinates()}",
            endAddress = "终点: ${end.formatCoordinates()}",
            isFallbackDirect = true
        )
    }
}
