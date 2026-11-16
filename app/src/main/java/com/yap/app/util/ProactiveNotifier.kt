package com.yap.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yap.app.MainActivity
import com.yap.app.R
import com.yap.app.data.local.PersonaEntity

/** 主动唤醒通知：一个渠道，按人格聚合，点击直接进入对应会话。 */
object ProactiveNotifier {

    const val CHANNEL_ID = "proactive_wake"
    const val EXTRA_SESSION_ID = "extra_session_id"
    const val EXTRA_PERSONA_ID = "extra_persona_id"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.proactive_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.proactive_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun notify(context: Context, persona: PersonaEntity, sessionId: String, text: String) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_PERSONA_ID, persona.id)
        }
        val pending = PendingIntent.getActivity(
            context,
            NotificationIds.chatId(sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = text.replace(Regex("\\s+"), " ").trim().take(160)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(persona.name)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            // 和聊天回复共用同一套分组与锁屏策略：主动找你和回你话，在系统看来是同一件事。
            .setGroup(NotificationIds.chatGroup(persona.id))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // 锁屏上只留「他发来一条消息」，正文留到解锁后；点开仍然直达会话。
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, persona.name))
            .build()
        runCatching {
            // id 按会话分配（不是按人格）：同一个人的两个会话不会互相顶掉。
            NotificationManagerCompat.from(context).notify(NotificationIds.chatId(sessionId), notification)
        }
    }

    /** 锁屏上看到的公开版：只有名字和一句「他发来一条消息」，不露正文。 */
    private fun publicVersion(context: Context, personaName: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(personaName)
            .setContentText(context.getString(R.string.chat_notify_one_message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
}
