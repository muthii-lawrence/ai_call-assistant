package com.aicall.assistant.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aicall.assistant.ui.InCallActivity

/**
 * Keeps the process alive for the duration of the call. Being the default
 * dialer + foreground service is necessary but on Samsung/Vivo may not be
 * sufficient -- see doc section 2.3 and the Phase 0 spike. Users must also
 * grant battery-optimization exemption and, on Vivo, autostart permission.
 */
class CallForegroundService : Service() {

    companion object {
        const val ACTION_START_INCOMING = "com.aicall.assistant.START_INCOMING"
        private const val INCOMING_CHANNEL_ID = "incoming_call"
        private const val ACTIVE_CHANNEL_ID = "active_call"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val incoming = intent?.action == ACTION_START_INCOMING
            val channelId = if (incoming) INCOMING_CHANNEL_ID else ACTIVE_CHANNEL_ID
            val channel = NotificationChannel(
                channelId,
                if (incoming) "Incoming calls" else "Active call",
                if (intent?.action == ACTION_START_INCOMING) {
                    NotificationManager.IMPORTANCE_HIGH
                } else {
                    NotificationManager.IMPORTANCE_LOW
                }
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val incoming = intent?.action == ACTION_START_INCOMING
        val channelId = if (incoming) INCOMING_CHANNEL_ID else ACTIVE_CHANNEL_ID
        val controlsIntent = Intent(this, InCallActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val controlsPendingIntent = PendingIntent.getActivity(
            this,
            1,
            controlsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(if (incoming) "Incoming call" else "Call in progress")
            .setContentText(if (incoming) "Tap to answer or screen the call" else "Open call controls")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentIntent(controlsPendingIntent)
            .setFullScreenIntent(controlsPendingIntent, incoming)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(if (incoming) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .apply {
                if (incoming) {
                    addAction(NotificationCompat.Action.Builder(0, "Answer", CallNotificationManager.actionPendingIntent(this@CallForegroundService, CallNotificationManager.ACTION_ANSWER, 2)).build())
                    addAction(NotificationCompat.Action.Builder(0, "Decline", CallNotificationManager.actionPendingIntent(this@CallForegroundService, CallNotificationManager.ACTION_DECLINE, 3)).build())
                } else {
                    addAction(NotificationCompat.Action.Builder(0, "Take over", CallNotificationManager.actionPendingIntent(this@CallForegroundService, CallNotificationManager.ACTION_TAKE_OVER, 4)).build())
                }
            }
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            stopSelf(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AudioRouter.stop(this)
        super.onDestroy()
    }
}
