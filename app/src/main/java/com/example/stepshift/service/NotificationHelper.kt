package com.example.stepshift.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.stepshift.MainActivity
import com.example.stepshift.model.GeoPoint
import com.example.stepshift.model.SimulationSnapshot
import com.example.stepshift.model.SimulationStatus

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "StepShift 运动仿真常驻通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "实时显示步数、运动距离、配速与仿真进度"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(snapshot: SimulationSnapshot): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isRunning = snapshot.status == SimulationStatus.RUNNING
        val title = when (snapshot.status) {
            SimulationStatus.RUNNING -> "StepShift 正在运动仿真中..."
            SimulationStatus.PAUSED -> "StepShift 仿真已暂停"
            SimulationStatus.COMPLETED -> "StepShift 仿真已完成 ✅"
            SimulationStatus.IDLE -> "StepShift 待命中"
        }

        val distanceKm = "%.2f km".format(snapshot.totalDistanceMeters / 1000.0)
        val speedStr = "%.1f km/h".format(snapshot.speedKmH)
        val contentText = "里程: $distanceKm | 步数: ${snapshot.currentSteps} 步 | 配速: $speedStr"
        val subText = "耗时: ${snapshot.formatElapsedTime()} | 剩余: ${snapshot.formatRemainingTime()}"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(subText)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(isRunning || snapshot.status == SimulationStatus.PAUSED)
            .setOnlyAlertOnce(true)
            .setProgress(100, (snapshot.progressPercent * 100).toInt(), false)

        // Action 1: Pause (running) or Resume (paused) — hidden in terminal states
        if (isRunning) {
            val pauseIntent = Intent(context, MockForegroundService::class.java).apply {
                action = MockForegroundService.ACTION_PAUSE
            }
            val pausePending = PendingIntent.getService(
                context,
                1,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "暂停", pausePending)
        } else if (snapshot.status == SimulationStatus.PAUSED) {
            val resumeIntent = Intent(context, MockForegroundService::class.java).apply {
                action = MockForegroundService.ACTION_RESUME
            }
            val resumePending = PendingIntent.getService(
                context,
                2,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "继续", resumePending)
        }

        // Action 2: Stop (only meaningful while a run is active)
        if (isRunning || snapshot.status == SimulationStatus.PAUSED) {
            val stopIntent = Intent(context, MockForegroundService::class.java).apply {
                action = MockForegroundService.ACTION_STOP
            }
            val stopPending = PendingIntent.getService(
                context,
                3,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "结束", stopPending)
        }

        return builder.build()
    }

    private var lastNotifyAtMs = 0L
    private var lastNotifyStatus: SimulationStatus? = null

    /**
     * Throttled update: status transitions post immediately; plain telemetry ticks
     * post at most once every 2 seconds to stay clear of NotificationManager /
     * SystemUI rate limiting on some ROMs.
     */
    fun updateNotification(snapshot: SimulationSnapshot) {
        val now = System.currentTimeMillis()
        val statusChanged = snapshot.status != lastNotifyStatus
        if (!statusChanged && now - lastNotifyAtMs < 2000L) return
        lastNotifyAtMs = now
        lastNotifyStatus = snapshot.status
        val notification = buildNotification(snapshot)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Notification shown while the standalone fixed-point injection is active
     * (virtual position locked, no route simulation running).
     */
    fun buildFixedPointNotification(point: GeoPoint): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, MockForegroundService::class.java).apply {
            action = MockForegroundService.ACTION_FIXED_STOP
        }
        val stopPending = PendingIntent.getService(
            context,
            4,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("StepShift 定点位置注入中...")
            .setContentText("虚拟位置已锁定: %.5f, %.5f".format(point.latitude, point.longitude))
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "结束", stopPending)
            .build()
    }

    private var lastFixedNotifyAtMs = 0L

    /** Throttled 1Hz fixed-point tick update (at most one post every 5 seconds). */
    fun notifyFixedPoint(point: GeoPoint) {
        val now = System.currentTimeMillis()
        if (now - lastFixedNotifyAtMs < 5000L) return
        lastFixedNotifyAtMs = now
        notificationManager.notify(NOTIFICATION_ID, buildFixedPointNotification(point))
    }

    companion object {
        const val CHANNEL_ID = "stepshift_motion_channel"
        const val NOTIFICATION_ID = 1001
    }
}
