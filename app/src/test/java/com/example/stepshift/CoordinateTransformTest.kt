package com.example.stepshift

import com.example.stepshift.engine.KinematicsCalculator
import com.example.stepshift.model.GeoPoint
import com.example.stepshift.utils.CoordinateTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the WGS-84 <-> GCJ-02 conversion used when drawing on AMap (GCJ-02)
 * tiles versus feeding OSRM / GPS injection (WGS-84).
 */
class CoordinateTransformTest {

    // Tiananmen, Beijing (WGS-84)
    private val tiananmen = GeoPoint(39.9087, 116.3975)

    @Test
    fun `gcj02 offset for central Beijing is within expected range`() {
        val gcj = CoordinateTransform.wgs84ToGcj02(tiananmen)
        val offsetMeters = KinematicsCalculator.computeDistanceMeters(tiananmen, gcj)
        // The GCJ-02 shift in central Beijing is known to be a few hundred meters
        assertTrue("offset was $offsetMeters m", offsetMeters in 300.0..800.0)
    }

    @Test
    fun `gcj02ToWgs84 approximately inverts wgs84ToGcj02`() {
        val gcj = CoordinateTransform.wgs84ToGcj02(tiananmen)
        val roundTrip = CoordinateTransform.gcj02ToWgs84(gcj)
        // Iterative approximation: round-trip error must stay well under 1 meter
        assertEquals(tiananmen.latitude, roundTrip.latitude, 1e-5)
        assertEquals(tiananmen.longitude, roundTrip.longitude, 1e-5)
    }

    @Test
    fun `coordinates outside China pass through unchanged`() {
        val london = GeoPoint(51.5074, -0.1278)
        val converted = CoordinateTransform.wgs84ToGcj02(london)
        assertEquals(london.latitude, converted.latitude, 0.0)
        assertEquals(london.longitude, converted.longitude, 0.0)
    }
}
