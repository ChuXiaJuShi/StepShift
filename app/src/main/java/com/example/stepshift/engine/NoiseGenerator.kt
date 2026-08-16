package com.example.stepshift.engine

import com.example.stepshift.model.GeoPoint
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates natural Gaussian GPS micro-drift, multi-path noise, and subtle altitude variation.
 * Real GPS receivers always exhibit 0.5 ~ 2.0 meters of ionospheric/atmospheric jitter.
 */
class NoiseGenerator(seed: Long = System.currentTimeMillis()) {

    private val random = Random(seed)
    private var altitudePhase = 0.0

    /**
     * Apply Gaussian jitter to a given point.
     * @param point Base ground truth coordinate
     * @param intensityMeters 1-sigma standard deviation in meters (typically ~0.8m)
     */
    fun applyGpsNoise(point: GeoPoint, intensityMeters: Double = 0.85): GeoPoint {
        if (intensityMeters <= 0.0) return point

        // Box-Muller Gaussian noise for X (East-West) and Y (North-South) in meters
        val noiseXMeters = random.nextGaussian() * intensityMeters
        val noiseYMeters = random.nextGaussian() * intensityMeters

        // Conversion factors:
        // 1 deg latitude ≈ 111,320 meters
        // 1 deg longitude ≈ 111,320 * cos(latitude) meters
        val metersPerDegreeLat = 111320.0
        val metersPerDegreeLon = 111320.0 * cos(Math.toRadians(point.latitude)).coerceAtLeast(0.01)

        val deltaLat = noiseYMeters / metersPerDegreeLat
        val deltaLon = noiseXMeters / metersPerDegreeLon

        // Altitude variation: slowly evolving wave + micro noise
        altitudePhase += 0.05
        val altitudeDrift = sin(altitudePhase) * 0.4 + (random.nextGaussian() * 0.2)
        val noisyAltitude = (point.altitude + altitudeDrift).coerceAtLeast(0.0)

        return GeoPoint(
            latitude = point.latitude + deltaLat,
            longitude = point.longitude + deltaLon,
            altitude = noisyAltitude
        )
    }
}
