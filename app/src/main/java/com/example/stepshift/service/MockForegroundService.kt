package com.example.stepshift.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.example.stepshift.engine.SimulationEngine
import com.example.stepshift.model.GeoPoint
import com.example.stepshift.model.SimulationStatus
import com.example.stepshift.root.RootLocationMock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Background keep-alive service holding a Partial WakeLock and managing location mock stream.
 * Supports two mutually exclusive driving modes:
 *  - Route simulation (engine 1Hz ticks, ACTION_START)
 *  - Fixed-point injection (standalone virtual position, ACTION_FIXED_START)
 */
class MockForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var notificationHelper: NotificationHelper
    private var wakeLock: PowerManager.WakeLock? = null

    private val engine = SimulationEngine.instance
    private val rootMock = RootLocationMock.instance

    private var fixedJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        acquireWakeLock()
        setupMockInjection()
        observeEngineState()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StepShift::KeepAliveWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun setupMockInjection() {
        rootMock.setupTestProviders(this)

        engine.onLocationUpdateListener = { point, _, speedMs, bearing, _ ->
            if (engine.config.value.injectRootGps) {
                rootMock.pushLocation(this, point, speedMs, bearing)
            }
        }
    }

    private fun observeEngineState() {
        serviceScope.launch {
            engine.snapshot.collectLatest { snapshot ->
                notificationHelper.updateNotification(snapshot)
                com.example.stepshift.health.HealthDataManager.instance.dispatchMotionUpdate(
                    this@MockForegroundService,
                    snapshot
                )
                if (snapshot.status == SimulationStatus.COMPLETED) {
                    // Simulation finished: drop the wake lock so the CPU can sleep, and
                    // detach from foreground (notification stays as a final summary card).
                    // A started-but-non-foreground service may be killed freely — nothing
                    // of value is lost once the run is complete.
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                // The route engine takes priority — cancel any fixed-point injection
                stopFixedTicker()
                // Re-acquire in case this is a restart after COMPLETED released the lock
                acquireWakeLock()
                rootMock.setupTestProviders(this)
                val initialNotification = notificationHelper.buildNotification(engine.snapshot.value)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NotificationHelper.NOTIFICATION_ID,
                        initialNotification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } else {
                    startForeground(NotificationHelper.NOTIFICATION_ID, initialNotification)
                }
                engine.start()
            }
            ACTION_PAUSE -> {
                engine.pause()
            }
            ACTION_RESUME -> {
                engine.resume()
            }
            ACTION_STOP -> {
                stopFixedTicker()
                engine.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_FIXED_START -> {
                val lat = intent?.getDoubleExtra(EXTRA_FIXED_LAT, Double.NaN) ?: Double.NaN
                val lon = intent?.getDoubleExtra(EXTRA_FIXED_LON, Double.NaN) ?: Double.NaN
                val alt = intent?.getDoubleExtra(EXTRA_FIXED_ALT, 0.0) ?: 0.0
                if (lat.isNaN() || lon.isNaN()) return START_STICKY

                // Never let the fixed point fight the route engine
                val engineStatus = engine.snapshot.value.status
                if (engineStatus == SimulationStatus.RUNNING || engineStatus == SimulationStatus.PAUSED) {
                    return START_STICKY
                }

                acquireWakeLock()
                rootMock.setupTestProviders(this)
                val notification = notificationHelper.buildFixedPointNotification(GeoPoint(lat, lon, alt))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NotificationHelper.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } else {
                    startForeground(NotificationHelper.NOTIFICATION_ID, notification)
                }
                startFixedTicker(GeoPoint(lat, lon, alt))
            }
            ACTION_FIXED_STOP -> {
                stopFixedTicker()
                rootMock.removeTestProviders(this)
                val engineStatus = engine.snapshot.value.status
                if (engineStatus != SimulationStatus.RUNNING && engineStatus != SimulationStatus.PAUSED) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    /**
     * 1Hz fixed-point injection loop. Self-cancels if the route engine ever takes
     * over the location stream (defensive guard — the ACTION_START path already
     * stops the ticker explicitly).
     */
    private fun startFixedTicker(point: GeoPoint) {
        stopFixedTicker()
        fixedJob = serviceScope.launch {
            while (isActive) {
                val engineStatus = engine.snapshot.value.status
                if (engineStatus == SimulationStatus.RUNNING || engineStatus == SimulationStatus.PAUSED) {
                    break
                }
                rootMock.pushLocation(this@MockForegroundService, point, 0.0, 0f)
                notificationHelper.notifyFixedPoint(point)
                delay(1000L)
            }
        }
    }

    private fun stopFixedTicker() {
        fixedJob?.cancel()
        fixedJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        engine.onLocationUpdateListener = null
        rootMock.removeTestProviders(this)
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.stepshift.ACTION_START"
        const val ACTION_PAUSE = "com.example.stepshift.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.stepshift.ACTION_RESUME"
        const val ACTION_STOP = "com.example.stepshift.ACTION_STOP"
        const val ACTION_FIXED_START = "com.example.stepshift.ACTION_FIXED_START"
        const val ACTION_FIXED_STOP = "com.example.stepshift.ACTION_FIXED_STOP"

        private const val EXTRA_FIXED_LAT = "extra_fixed_lat"
        private const val EXTRA_FIXED_LON = "extra_fixed_lon"
        private const val EXTRA_FIXED_ALT = "extra_fixed_alt"

        fun startService(context: Context) {
            val intent = Intent(context, MockForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MockForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** Start (or restart with a new point) the standalone fixed-point injection. */
        fun startFixedService(context: Context, point: GeoPoint) {
            val intent = Intent(context, MockForegroundService::class.java).apply {
                action = ACTION_FIXED_START
                putExtra(EXTRA_FIXED_LAT, point.latitude)
                putExtra(EXTRA_FIXED_LON, point.longitude)
                putExtra(EXTRA_FIXED_ALT, point.altitude)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the fixed-point injection (route simulation, if any, keeps running). */
        fun stopFixedService(context: Context) {
            val intent = Intent(context, MockForegroundService::class.java).apply {
                action = ACTION_FIXED_STOP
            }
            context.startService(intent)
        }
    }
}
