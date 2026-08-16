package com.example.stepshift.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.example.stepshift.engine.SimulationEngine
import com.example.stepshift.model.SimulationStatus
import com.example.stepshift.root.RootLocationMock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Background keep-alive service holding a Partial WakeLock and managing location mock stream.
 */
class MockForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var notificationHelper: NotificationHelper
    private var wakeLock: PowerManager.WakeLock? = null

    private val engine = SimulationEngine.instance
    private val rootMock = RootLocationMock.instance

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
                    // Simulation finished
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
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
                engine.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
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
    }
}
