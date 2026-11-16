package com.myfriend.app.util

import android.content.Context
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.remote.ServerEventNotifier
import com.myfriend.app.data.repo.ChatRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端模式的新消息通知（复用现有通知渠道/分组/锁屏打码）。
 *
 * 阻挡条件与本地模式保持一致：
 * - App 不在前台 → 不发（没有推送时，前台是唯一能实时知道的时机；后台由下次同步补）；
 * - 正开着这个会话 → 不发（用户在看着，通知只会多余地响一声）。
 *
 * 文案不在这里拼：标题用 [PersonaEntity.name]，正文由 [ChatNotifier] 压成一行并截断，
 * 锁屏只显示「他发来一条消息」。
 */
@Singleton
class ServerModeNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatNotifier: ChatNotifier,
    private val chatRepository: ChatRepository,
    private val personaDao: PersonaDao,
    private val foregroundTracker: AppForegroundTracker
) : ServerEventNotifier {

    override suspend fun notifyMessage(sessionId: String, personaId: String, text: String) {
        if (!foregroundTracker.isForeground) return
        if (chatRepository.isSessionOpen(sessionId)) return
        val persona = runCatching { personaDao.getById(personaId) }.getOrNull() ?: return
        chatNotifier.notifyMessage(context, persona, sessionId, text)
    }
}
