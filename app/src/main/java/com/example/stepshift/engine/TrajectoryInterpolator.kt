package com.example.stepshift.engine

import com.example.stepshift.model.GeoPoint

/**
 * Result returned by the trajectory interpolator for each tick step.
 */
data class InterpolatedPosition(
    val point: GeoPoint,
    val bearing: Float,
    val traveledDistanceMeters: Double,
    val remainingDistanceMeters: Double,
    val isFinished: Boolean
)

/**
 * Smooth multi-node polyline trajectory interpolator.
 */
class TrajectoryInterpolator(private val pathPoints: List<GeoPoint>) {

    // Segment lengths and cumulative distance array
    private val segmentLengths = DoubleArray((pathPoints.size - 1).coerceAtLeast(0))
    private val cumulativeDistances = DoubleArray(pathPoints.size)
    val totalRouteDistanceMeters: Double

    private var currentTraveledMeters: Double = 0.0
    private var lastBearing: Float = 0f

    init {
        var runningTotal = 0.0
        cumulativeDistances[0] = 0.0
        for (i in 0 until pathPoints.size - 1) {
            val length = KinematicsCalculator.computeDistanceMeters(pathPoints[i], pathPoints[i + 1])
            segmentLengths[i] = length
            runningTotal += length
            cumulativeDistances[i + 1] = runningTotal
        }
        totalRouteDistanceMeters = runningTotal

        if (pathPoints.size >= 2) {
            lastBearing = KinematicsCalculator.computeBearing(pathPoints[0], pathPoints[1])
        }
    }

    /**
     * Advance along the track by the specified distance in meters.
     */
    fun advance(distanceMeters: Double): InterpolatedPosition {
        if (pathPoints.isEmpty()) {
            return InterpolatedPosition(
                point = GeoPoint(0.0, 0.0),
                bearing = 0f,
                traveledDistanceMeters = 0.0,
                remainingDistanceMeters = 0.0,
                isFinished = true
            )
        }

        if (pathPoints.size == 1 || totalRouteDistanceMeters <= 0.0) {
            return InterpolatedPosition(
                point = pathPoints[0],
                bearing = 0f,
                traveledDistanceMeters = 0.0,
                remainingDistanceMeters = 0.0,
                isFinished = true
            )
        }

        currentTraveledMeters += distanceMeters

        if (currentTraveledMeters >= totalRouteDistanceMeters) {
            currentTraveledMeters = totalRouteDistanceMeters
            val lastPoint = pathPoints.last()
            return InterpolatedPosition(
                point = lastPoint,
                bearing = lastBearing,
                traveledDistanceMeters = totalRouteDistanceMeters,
                remainingDistanceMeters = 0.0,
                isFinished = true
            )
        }

        // Find which segment contains currentTraveledMeters
        var segmentIndex = 0
        while (segmentIndex < segmentLengths.size - 1 &&
            cumulativeDistances[segmentIndex + 1] <= currentTraveledMeters
        ) {
            segmentIndex++
        }

        val segmentStartDistance = cumulativeDistances[segmentIndex]
        val segmentLength = segmentLengths[segmentIndex]
        val distanceAlongSegment = currentTraveledMeters - segmentStartDistance

        val p1 = pathPoints[segmentIndex]
        val p2 = pathPoints[segmentIndex + 1]

        val bearing = KinematicsCalculator.computeBearing(p1, p2)
        lastBearing = bearing

        val currentPoint = if (segmentLength > 0.001) {
            KinematicsCalculator.computeDestinationPoint(p1, distanceAlongSegment, bearing)
        } else {
            p1
        }

        return InterpolatedPosition(
            point = currentPoint,
            bearing = bearing,
            traveledDistanceMeters = currentTraveledMeters,
            remainingDistanceMeters = (totalRouteDistanceMeters - currentTraveledMeters).coerceAtLeast(0.0),
            isFinished = false
        )
    }

    /**
     * Reset pointer to the start of the route.
     */
    fun reset() {
        currentTraveledMeters = 0.0
        if (pathPoints.size >= 2) {
            lastBearing = KinematicsCalculator.computeBearing(pathPoints[0], pathPoints[1])
        }
    }

    /**
     * Get initial position at start.
     */
    fun getInitialPosition(): InterpolatedPosition {
        val startPoint = pathPoints.firstOrNull() ?: GeoPoint(0.0, 0.0)
        val bearing = if (pathPoints.size >= 2) {
            KinematicsCalculator.computeBearing(pathPoints[0], pathPoints[1])
        } else 0f

        return InterpolatedPosition(
            point = startPoint,
            bearing = bearing,
            traveledDistanceMeters = 0.0,
            remainingDistanceMeters = totalRouteDistanceMeters,
            isFinished = false
        )
    }
}
