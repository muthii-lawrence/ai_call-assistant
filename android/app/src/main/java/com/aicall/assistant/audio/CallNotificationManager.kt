package com.aicall.assistant.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.aicall.assistant.R
import com.aicall.assistant.telecom.CallStateMachine
import com.aicall.assistant.ui.MainActivity

object CallNotificationManager {
    const val ACTION_ANSWER = "com.aicall.assistant.ANSWER"
    const val ACTION_DECLINE = "com.aicall.assistant.DECLINE"
    const val ACTION_TAKE_OVER = "com.aicall.assistant.TAKE_OVER"
    private const val CHANNEL_ID = "call_events"

    fun notifyCallEnded(context: Context, callId: Long, summary: String?, missed: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Call updates", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("call_id", callId)
        }
        val pending = PendingIntent.getActivity(context, callId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (missed) "Missed call" else "Call ended"
        manager.notify(callId.toInt(), NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title)
            .setContentText(summary ?: "Open call history")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build())
    }

    fun actionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class CallActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CallNotificationManager.ACTION_ANSWER -> CallStateMachine.onManualAnswer(context)
            CallNotificationManager.ACTION_DECLINE -> CallStateMachine.onManualReject(context)
            CallNotificationManager.ACTION_TAKE_OVER -> CallStateMachine.onTakeOver(context)
        }
    }
}
