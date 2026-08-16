package com.example.stepshift.model

/**
 * State machine status of the simulation engine.
 */
enum class SimulationStatus {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED
}

/**
 * Real-time immutable snapshot of the simulation telemetry.
 */
data class SimulationSnapshot(
    val status: SimulationStatus = SimulationStatus.IDLE,
    val currentPoint: GeoPoint? = null, // Location with drift noise
    val rawPoint: GeoPoint? = null, // True route location before noise
    val currentBearing: Float = 0f, // Direction of motion in degrees (0 - 360)
    val speedKmH: Double = 0.0,
    val speedMs: Double = 0.0,
    val cadenceSpm: Int = 0, // Steps per minute
    val strideLengthMeters: Double = 0.0,
    val totalDistanceMeters: Double = 0.0, // Distance traveled so far
    val remainingDistanceMeters: Double = 0.0,
    val totalRouteDistanceMeters: Double = 0.0,
    val currentSteps: Long = 0,
    val deltaStepsThisSecond: Int = 0,
    val elapsedTimeSeconds: Long = 0,
    val remainingTimeSeconds: Long = 0,
    val progressPercent: Float = 0f // 0.0 to 1.0
) {
    fun formatElapsedTime(): String {
        val hours = elapsedTimeSeconds / 3600
        val minutes = (elapsedTimeSeconds % 3600) / 60
        val seconds = elapsedTimeSeconds % 60
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    fun formatRemainingTime(): String {
        val hours = remainingTimeSeconds / 3600
        val minutes = (remainingTimeSeconds % 3600) / 60
        val seconds = remainingTimeSeconds % 60
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    fun getBearingCardinal(): String {
        val normalized = (currentBearing % 360 + 360) % 360
        return when {
            normalized >= 337.5 || normalized < 22.5 -> "北 (N)"
            normalized < 67.5 -> "东北 (NE)"
            normalized < 112.5 -> "东 (E)"
            normalized < 157.5 -> "东南 (SE)"
            normalized < 202.5 -> "南 (S)"
            normalized < 247.5 -> "西南 (SW)"
            normalized < 292.5 -> "西 (W)"
            else -> "西北 (NW)"
        }
    }
}
