package com.yap.app.util

import android.app.Notification
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 他回复消息时的系统通知：聊天页不在前台也要收到，像微信收消息那样。
 * 一个渠道、按人格聚合，点击直接进入对应会话。
 *
 * 两条硬要求（都关系到「他一直在那儿」这个感觉能不能立住）：
 * 1. 每条会话的通知 id 必须稳定且彼此不碰撞（见 [NotificationIds]），否则后来的消息会把前一条顶掉，
 *    用户只会觉得「他根本没理我」。
 * 2. 锁屏上不允许出现他说的话——手机放在桌上、旁边有人，也不该替用户把私聊内容念出来。
 *    锁屏只显示「他发来一条消息」（见 `R.string.chat_notify_one_message`），点开才进 App 看正文。
 */
@Singleton
class ChatNotifier @Inject constructor() {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.chat_reply_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.chat_reply_channel_description)
            setShowBadge(true)
            // 渠道级默认值只在首次创建时生效，所以每条通知自己还会再声明一次 visibility；
            // 这里加上是为了让新装的用户从第一条通知起就是打码的。
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyMessage(context: Context, persona: PersonaEntity, sessionId: String, text: String) {
        ensureChannel(context)
        val group = NotificationIds.chatGroup(persona.id)
        val notificationId = NotificationIds.chatId(sessionId)
        val pending = sessionIntent(context, sessionId, persona.id, notificationId)
        // 正文压成一行再截断，通知栏不展示换行。
        val body = text.replace(Regex("\\s+"), " ").trim().take(160)
            .ifBlank { context.getString(R.string.chat_notify_one_message) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(persona.name)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setGroup(group)
            // 同组只让摘要响一次，避免几个会话同时来信时连环响、连环弹。
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, pending))
            .build()
        runCatching {
            // 没有通知权限时这里会失败，静默吞掉，绝不能影响生成流程。
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            postGroupSummary(context, persona, pending)
        }
    }

    /** 进入会话时把该会话的这条通知撤掉；组里空了就把摘要也收走，别留下一条点不开的空摘要。 */
    fun cancel(context: Context, sessionId: String) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            val notificationId = NotificationIds.chatId(sessionId)
            // 先记住它属于哪一组：只凭 sessionId 是推不出 personaId 的（进程可能已经重启过）。
            val group = manager.activeNotifications
                .firstOrNull { it.id == notificationId }
                ?.notification
                ?.group
            manager.cancel(notificationId)
            if (group == null || !group.startsWith(GROUP_PREFIX)) return@runCatching
            val stillThere = manager.activeNotifications.any { it.notification.group == group }
            if (stillThere) return@runCatching
            manager.activeNotifications
                .firstOrNull { it.notification.group == group && it.notification.isGroupSummary() }
                ?.let { manager.cancel(it.tag, it.id) }
        }
    }

    /**
     * 把某个人的通知全部撤掉（会话通知 + 那条聚合摘要）。
     *
     * 审计 P2-14：`cancel` 以前只支持「单个会话」，而删人格 / 关掉常驻这些动作需要
     * 「这个人的所有通知都消失」。组名由 [NotificationIds.chatGroup] 决定，所以不用扫通知、
     * 也不怕进程重启过（组名是稳定推导的）。
     */
    fun cancelByPersona(context: Context, personaId: String) {
        val group = NotificationIds.chatGroup(personaId)
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.activeNotifications
                .filter { it.notification.group == group }
                .forEach { manager.cancel(it.tag, it.id) }
            manager.cancel(NotificationIds.chatSummaryId(personaId))
        }
    }

    /**
     * 撤掉**所有**聊天通知（会话 + 摘要）。
     *
     * 「删除全部数据」这类动作之后，任何一条残留通知都指向一个已经不存在的会话，
     * 点开只会进到一个空页面。
     */
    fun cancelAll(context: Context) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.activeNotifications
                .filter { it.notification.group?.startsWith(GROUP_PREFIX) == true }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }

    /**
     * 一个 persona 一条摘要：同一个人开了几个会话时，锁屏/通知栏只折叠成这一条，
     * 而不是把同样的话说三遍。
     */
    private fun postGroupSummary(context: Context, persona: PersonaEntity, pending: PendingIntent) {
        val manager = NotificationManagerCompat.from(context)
        val group = NotificationIds.chatGroup(persona.id)
        val count = runCatching {
            manager.activeNotifications.count {
                it.notification.group == group && !it.notification.isGroupSummary()
            }
        }.getOrDefault(1)
        // 说「几条」而不是编一个精确数字：一个会话里可能攒了好几条，通知层数不准，宁可不撒谎。
        val text = context.getString(
            if (count > 1) R.string.chat_notify_few_messages else R.string.chat_notify_one_message
        )
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(persona.name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, pending))
            .build()
        manager.notify(NotificationIds.chatSummaryId(persona.id), summary)
    }

    /**
     * 锁屏上展示的公开版通知：只有「他发来一条消息」，没有名字、没有正文。
     * 点开照样进 App，所以打码不会让用户多走一步。
     */
    private fun publicVersion(context: Context, pending: PendingIntent): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.chat_notify_one_message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun sessionIntent(
        context: Context,
        sessionId: String,
        personaId: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ProactiveNotifier.EXTRA_SESSION_ID, sessionId)
            putExtra(ProactiveNotifier.EXTRA_PERSONA_ID, personaId)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun Notification.isGroupSummary(): Boolean =
        flags and Notification.FLAG_GROUP_SUMMARY != 0

    companion object {
        const val CHANNEL_ID = "chat_reply"

        /** 通知分组名前缀，只有我们自己建的组才会去收拾摘要。 */
        private const val GROUP_PREFIX = "chat-"
    }
}
