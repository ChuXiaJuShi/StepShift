package com.example.stepshift.engine

import com.example.stepshift.model.GeoPoint
import kotlin.math.*

/**
 * Spherical trigonometry and human physical walking kinematics calculator.
 */
object KinematicsCalculator {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Compute Great-Circle distance between two coordinates in meters (Haversine formula).
     */
    fun computeDistanceMeters(p1: GeoPoint, p2: GeoPoint): Double {
        val lat1Rad = Math.toRadians(p1.latitude)
        val lat2Rad = Math.toRadians(p2.latitude)
        val deltaLat = Math.toRadians(p2.latitude - p1.latitude)
        val deltaLon = Math.toRadians(p2.longitude - p1.longitude)

        val a = sin(deltaLat / 2.0).pow(2.0) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Compute initial forward azimuth / bearing from p1 to p2 in degrees (0 to 360).
     */
    fun computeBearing(p1: GeoPoint, p2: GeoPoint): Float {
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val lon1 = Math.toRadians(p1.longitude)
        val lon2 = Math.toRadians(p2.longitude)

        val deltaLon = lon2 - lon1
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)

        val bearingRad = atan2(y, x)
        val bearingDeg = Math.toDegrees(bearingRad)

        return ((bearingDeg + 360.0) % 360.0).toFloat()
    }

    /**
     * Calculate destination coordinate given start point, distance (meters), and bearing (degrees).
     */
    fun computeDestinationPoint(start: GeoPoint, distanceMeters: Double, bearingDegrees: Float): GeoPoint {
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearingRad = Math.toRadians(bearingDegrees.toDouble())

        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)

        val lat2 = asin(
            sin(lat1) * cos(angularDistance) +
                    cos(lat1) * sin(angularDistance) * cos(bearingRad)
        )

        val lon2 = lon1 + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )

        return GeoPoint(
            latitude = Math.toDegrees(lat2),
            longitude = Math.toDegrees(lon2),
            altitude = start.altitude
        )
    }

    /**
     * Calculate realistic human step cadence (steps per minute) based on speed (km/h).
     * Typical physiology:
     * - Slow Walk (1~4 km/h): 90~110 spm
     * - Brisk Walk (4~6 km/h): 110~130 spm
     * - Jogging (6~10 km/h): 130~160 spm
     * - Running (>10 km/h): 160~190 spm
     */
    fun calculateCadenceSpm(speedKmH: Double): Int {
        return when {
            speedKmH <= 0.1 -> 0
            speedKmH <= 4.0 -> (90.0 + (speedKmH - 1.0) * 6.6).toInt().coerceIn(80, 115)
            speedKmH <= 6.5 -> (110.0 + (speedKmH - 4.0) * 8.0).toInt().coerceIn(110, 135)
            speedKmH <= 11.0 -> (130.0 + (speedKmH - 6.5) * 6.5).toInt().coerceIn(130, 165)
            else -> (160.0 + (speedKmH - 11.0) * 3.0).toInt().coerceIn(160, 195)
        }
    }

    /**
     * Calculate stride length in meters based on speed and cadence.
     * Stride (m) = Speed (m/s) / (Cadence / 60.0)
     */
    fun calculateStrideLength(speedKmH: Double, cadenceSpm: Int): Double {
        if (cadenceSpm <= 0 || speedKmH <= 0.1) return 0.0
        val speedMs = speedKmH / 3.6
        val stepsPerSecond = cadenceSpm / 60.0
        val stride = speedMs / stepsPerSecond
        return stride.coerceIn(0.45, 1.40)
    }

    /**
     * Compute total route distance for a list of coordinates.
     */
    fun computeTotalRouteDistance(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += computeDistanceMeters(points[i], points[i + 1])
        }
        return total
    }
}
