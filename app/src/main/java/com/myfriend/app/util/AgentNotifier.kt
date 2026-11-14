package com.myfriend.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.myfriend.app.MainActivity
import com.myfriend.app.R

/** 常驻通知：告诉用户「他还在后台活着」，点开回到 App。 */
object AgentNotifier {

    const val CHANNEL_ID = "agent_alive"
    const val NOTIFICATION_ID = 0x1F2E3D

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.agent_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.agent_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 常驻通知本体。尽量安静：不要声音、不要弹出，只是「他还在」。 */
    fun build(context: Context, title: String, text: String): Notification {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0x1F2E3D,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 这条是常驻的，锁屏上常年挂着，传进来的文字未必干净（可能是他正在做的事的简述）。
            // 锁屏只留「他还在」，细节等解锁后再看，免得手机放桌上就替用户说了话。
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, pending))
            .build()
    }

    /** 锁屏上只露一句状态，不露任何具体内容。 */
    private fun publicVersion(context: Context, pending: PendingIntent): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.agent_notify_public_text))
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
}
