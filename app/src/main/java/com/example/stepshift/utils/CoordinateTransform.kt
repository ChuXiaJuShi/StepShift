package com.example.stepshift.utils

import com.example.stepshift.model.GeoPoint
import kotlin.math.*

/**
 * High-precision coordinate converter between WGS-84 (Standard GPS) and GCJ-02 (Mars / AutoNavi).
 */
object CoordinateTransform {
    private const val X_PI = Math.PI * 3000.0 / 180.0
    private const val OFFSET_A = 6378245.0
    private const val OFFSET_EE = 0.00669342162296594323

    /**
     * Convert WGS-84 (Standard GPS) to GCJ-02 (AutoNavi / Amap Map Display)
     */
    fun wgs84ToGcj02(point: GeoPoint): GeoPoint {
        if (outOfChina(point.latitude, point.longitude)) {
            return point
        }
        var dLat = transformLat(point.longitude - 105.0, point.latitude - 35.0)
        var dLon = transformLon(point.longitude - 105.0, point.latitude - 35.0)
        val radLat = point.latitude / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - OFFSET_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((OFFSET_A * (1 - OFFSET_EE)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (OFFSET_A / sqrtMagic * cos(radLat) * Math.PI)
        val mgLat = point.latitude + dLat
        val mgLon = point.longitude + dLon
        return GeoPoint(mgLat, mgLon, point.altitude)
    }

    /**
     * Convert GCJ-02 (AutoNavi Screen Click) to WGS-84 (Standard GPS for Injection)
     */
    fun gcj02ToWgs84(point: GeoPoint): GeoPoint {
        if (outOfChina(point.latitude, point.longitude)) {
            return point
        }
        val gcj = wgs84ToGcj02(point)
        val dLat = gcj.latitude - point.latitude
        val dLon = gcj.longitude - point.longitude
        return GeoPoint(point.latitude - dLat, point.longitude - dLon, point.altitude)
    }

    private fun outOfChina(lat: Double, lon: Double): Boolean {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
