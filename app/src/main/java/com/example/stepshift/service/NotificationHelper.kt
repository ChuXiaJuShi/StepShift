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
        val title = if (isRunning) "StepShift 正在运动仿真中..." else "StepShift 仿真已暂停"

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
            .setOngoing(isRunning)
            .setOnlyAlertOnce(true)
            .setProgress(100, (snapshot.progressPercent * 100).toInt(), false)

        // Action 1: Pause or Resume
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
        } else {
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

        // Action 2: Stop
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

        return builder.build()
    }

    fun updateNotification(snapshot: SimulationSnapshot) {
        val notification = buildNotification(snapshot)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "stepshift_motion_channel"
        const val NOTIFICATION_ID = 1001
    }
}
