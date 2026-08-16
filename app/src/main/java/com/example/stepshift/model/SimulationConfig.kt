package com.example.stepshift.model

/**
 * Configuration options for the physical simulation engine.
 */
data class SimulationConfig(
    val speedKmH: Double = 5.0, // Default walking speed: 5.0 km/h (1.0 ~ 25.0 km/h)
    val autoCadence: Boolean = true, // Auto compute cadence and stride from speed
    val customCadenceSpm: Int = 115, // Custom steps per minute (if autoCadence == false)
    val customStrideM: Double = 0.72, // Custom stride in meters (if autoCadence == false)
    val enableGpsDrift: Boolean = true, // Simulate micro GPS jitter & drift
    val driftIntensityMeters: Double = 0.85, // Noise standard deviation in meters
    val injectRootGps: Boolean = true, // Inject into Android Location Framework via Root / TestProvider
    val targetSteps: Long? = null, // Optional target step limit
    val targetDistanceM: Double? = null // Optional target distance limit
)
