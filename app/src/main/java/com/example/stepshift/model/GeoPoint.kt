package com.example.stepshift.model

import android.location.Location
import org.osmdroid.util.GeoPoint as OsmGeoPoint

/**
 * Standard GeoPoint representation for StepShift simulation.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0
) {
    /**
     * Convert to osmdroid GeoPoint for map rendering
     */
    fun toOsmGeoPoint(): OsmGeoPoint {
        return OsmGeoPoint(latitude, longitude, altitude)
    }

    /**
     * Format as string with 6 decimal places (approx. 10cm accuracy)
     */
    fun formatCoordinates(): String {
        return "%.6f, %.6f".format(latitude, longitude)
    }

    companion object {
        fun fromOsmGeoPoint(osm: OsmGeoPoint): GeoPoint {
            return GeoPoint(osm.latitude, osm.longitude, osm.altitude)
        }

        fun fromAndroidLocation(loc: Location): GeoPoint {
            return GeoPoint(loc.latitude, loc.longitude, loc.altitude)
        }
    }
}
