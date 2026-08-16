package com.example.stepshift.engine

import com.example.stepshift.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-precision 1Hz physical simulation engine for location and step calculation.
 */
class SimulationEngine(
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val noiseGenerator = NoiseGenerator()

    private var interpolator: TrajectoryInterpolator? = null
    private var currentRoutePoints: List<GeoPoint> = emptyList()

    private val _config = MutableStateFlow(SimulationConfig())
    val config: StateFlow<SimulationConfig> = _config.asStateFlow()

    private val _snapshot = MutableStateFlow(SimulationSnapshot())
    val snapshot: StateFlow<SimulationSnapshot> = _snapshot.asStateFlow()

    private var tickerJob: Job? = null

    // Accumulators
    private var accumulatedFractionalSteps = 0.0
    private var cumulativeSteps: Long = 0
    private var elapsedSeconds: Long = 0

    // Callback for location mock injector
    var onLocationUpdateListener: ((point: GeoPoint, rawPoint: GeoPoint, speedMs: Double, bearing: Float, stepsDelta: Int) -> Unit)? = null

    /**
     * Set route points for the simulation.
     */
    fun setRoute(points: List<GeoPoint>) {
        currentRoutePoints = points
        if (points.isNotEmpty()) {
            val newInterpolator = TrajectoryInterpolator(points)
            interpolator = newInterpolator
            val initial = newInterpolator.getInitialPosition()
            
            _snapshot.value = SimulationSnapshot(
                status = SimulationStatus.IDLE,
                currentPoint = initial.point,
                rawPoint = initial.point,
                currentBearing = initial.bearing,
                speedKmH = _config.value.speedKmH,
                speedMs = _config.value.speedKmH / 3.6,
                totalDistanceMeters = 0.0,
                remainingDistanceMeters = newInterpolator.totalRouteDistanceMeters,
                totalRouteDistanceMeters = newInterpolator.totalRouteDistanceMeters,
                currentSteps = 0,
                elapsedTimeSeconds = 0,
                remainingTimeSeconds = calculateRemainingTime(newInterpolator.totalRouteDistanceMeters, _config.value.speedKmH),
                progressPercent = 0f
            )
        } else {
            interpolator = null
            _snapshot.value = SimulationSnapshot()
        }
    }

    /**
     * Update simulation configuration (speed, cadence, drift).
     */
    fun updateConfig(newConfig: SimulationConfig) {
        _config.value = newConfig
        val current = _snapshot.value
        val speedMs = newConfig.speedKmH / 3.6
        val cadence = if (newConfig.autoCadence) {
            KinematicsCalculator.calculateCadenceSpm(newConfig.speedKmH)
        } else {
            newConfig.customCadenceSpm
        }
        val stride = KinematicsCalculator.calculateStrideLength(newConfig.speedKmH, cadence)

        _snapshot.value = current.copy(
            speedKmH = newConfig.speedKmH,
            speedMs = speedMs,
            cadenceSpm = cadence,
            strideLengthMeters = stride,
            remainingTimeSeconds = calculateRemainingTime(current.remainingDistanceMeters, newConfig.speedKmH)
        )
    }

    /**
     * Start or restart the simulation.
     */
    fun start() {
        val currentInterp = interpolator ?: return
        if (_snapshot.value.status == SimulationStatus.RUNNING) return

        if (_snapshot.value.status == SimulationStatus.COMPLETED || _snapshot.value.status == SimulationStatus.IDLE) {
            currentInterp.reset()
            accumulatedFractionalSteps = 0.0
            cumulativeSteps = 0
            elapsedSeconds = 0
        }

        _snapshot.value = _snapshot.value.copy(status = SimulationStatus.RUNNING)
        startTicker()
    }

    /**
     * Pause the active simulation.
     */
    fun pause() {
        if (_snapshot.value.status != SimulationStatus.RUNNING) return
        tickerJob?.cancel()
        tickerJob = null
        _snapshot.value = _snapshot.value.copy(status = SimulationStatus.PAUSED)
    }

    /**
     * Resume simulation from paused state.
     */
    fun resume() {
        if (_snapshot.value.status != SimulationStatus.PAUSED) return
        _snapshot.value = _snapshot.value.copy(status = SimulationStatus.RUNNING)
        startTicker()
    }

    /**
     * Stop simulation completely and reset counters.
     */
    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        interpolator?.reset()
        accumulatedFractionalSteps = 0.0
        cumulativeSteps = 0
        elapsedSeconds = 0

        val initial = interpolator?.getInitialPosition()
        _snapshot.value = SimulationSnapshot(
            status = SimulationStatus.IDLE,
            currentPoint = initial?.point,
            rawPoint = initial?.point,
            currentBearing = initial?.bearing ?: 0f,
            speedKmH = _config.value.speedKmH,
            speedMs = _config.value.speedKmH / 3.6,
            totalDistanceMeters = 0.0,
            remainingDistanceMeters = interpolator?.totalRouteDistanceMeters ?: 0.0,
            totalRouteDistanceMeters = interpolator?.totalRouteDistanceMeters ?: 0.0,
            currentSteps = 0,
            elapsedTimeSeconds = 0,
            remainingTimeSeconds = calculateRemainingTime(interpolator?.totalRouteDistanceMeters ?: 0.0, _config.value.speedKmH),
            progressPercent = 0f
        )
    }

    /**
     * Reset simulation to starting line.
     */
    fun reset() {
        stop()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = coroutineScope.launch {
            while (isActive && _snapshot.value.status == SimulationStatus.RUNNING) {
                delay(1000L) // 1Hz simulation tick
                performTick()
            }
        }
    }

    private fun performTick() {
        val interp = interpolator ?: return
        val cfg = _config.value
        val speedMs = cfg.speedKmH / 3.6
        val distanceThisSecond = speedMs * 1.0 // distance in 1 sec

        // 1. Advance trajectory
        val pos = interp.advance(distanceThisSecond)

        // 2. Physical step calculation
        val cadenceSpm = if (cfg.autoCadence) {
            KinematicsCalculator.calculateCadenceSpm(cfg.speedKmH)
        } else {
            cfg.customCadenceSpm
        }
        val stepsPerSec = cadenceSpm / 60.0
        accumulatedFractionalSteps += stepsPerSec
        val deltaSteps = accumulatedFractionalSteps.toInt()
        accumulatedFractionalSteps -= deltaSteps
        cumulativeSteps += deltaSteps

        elapsedSeconds++

        // 3. Apply Gaussian GPS drift if enabled
        val noisyPoint = if (cfg.enableGpsDrift) {
            noiseGenerator.applyGpsNoise(pos.point, cfg.driftIntensityMeters)
        } else {
            pos.point
        }

        val strideLength = KinematicsCalculator.calculateStrideLength(cfg.speedKmH, cadenceSpm)
        val progress = if (interp.totalRouteDistanceMeters > 0.0) {
            (pos.traveledDistanceMeters / interp.totalRouteDistanceMeters).toFloat().coerceIn(0f, 1f)
        } else 0f

        val isTargetReached = (cfg.targetSteps != null && cumulativeSteps >= cfg.targetSteps) ||
                (cfg.targetDistanceM != null && pos.traveledDistanceMeters >= cfg.targetDistanceM) ||
                pos.isFinished

        val newStatus = if (isTargetReached) SimulationStatus.COMPLETED else SimulationStatus.RUNNING

        // 4. Update snapshot
        _snapshot.value = SimulationSnapshot(
            status = newStatus,
            currentPoint = noisyPoint,
            rawPoint = pos.point,
            currentBearing = pos.bearing,
            speedKmH = cfg.speedKmH,
            speedMs = speedMs,
            cadenceSpm = cadenceSpm,
            strideLengthMeters = strideLength,
            totalDistanceMeters = pos.traveledDistanceMeters,
            remainingDistanceMeters = pos.remainingDistanceMeters,
            totalRouteDistanceMeters = interp.totalRouteDistanceMeters,
            currentSteps = cumulativeSteps,
            deltaStepsThisSecond = deltaSteps,
            elapsedTimeSeconds = elapsedSeconds,
            remainingTimeSeconds = calculateRemainingTime(pos.remainingDistanceMeters, cfg.speedKmH),
            progressPercent = progress
        )

        // 5. Notify root location / framework mock injector
        onLocationUpdateListener?.invoke(noisyPoint, pos.point, speedMs, pos.bearing, deltaSteps)

        // If completed, stop ticker
        if (isTargetReached) {
            tickerJob?.cancel()
            tickerJob = null
        }
    }

    private fun calculateRemainingTime(remainingMeters: Double, speedKmH: Double): Long {
        if (speedKmH <= 0.1) return 0L
        val speedMs = speedKmH / 3.6
        return (remainingMeters / speedMs).toLong()
    }

    companion object {
        // Shared instance accessible across UI and Service
        val instance by lazy { SimulationEngine() }
    }
}
