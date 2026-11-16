package com.myfriend.app.data.repo

import com.myfriend.app.data.local.ApiUsageDao
import com.myfriend.app.data.local.ApiUsageEntity
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.remote.ServerChatSink
import com.myfriend.app.data.remote.ServerMessagePage
import com.myfriend.app.data.remote.ServerSyncApplyResult
import com.myfriend.app.data.remote.ServerUsage
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端发送之后的两件落库：当前块消息 + token 用量。
 *
 * 消息落库复用 [RoomServerStateSink]（块编号以服务端为准）；用量记一条
 * `kind = CHAT` 的本机流水，模型名写 `agentd`（真正的模型在服务端，客户端不知道也不该猜）。
 *
 * 成本字段写 0：服务端只下发 token 数，定价在服务端/账单侧；客户端显示的是「这个月在
 * 他身上的 token」，不是钱。
 */
@Singleton
class RoomServerChatSink @Inject constructor(
    private val stateSink: RoomServerStateSink,
    private val sessionDao: ChatSessionDao,
    private val usageDao: ApiUsageDao
) : ServerChatSink {

    override suspend fun applyPage(page: ServerMessagePage): ServerSyncApplyResult =
        stateSink.applyPage(page)

    override suspend fun recordUsage(sessionId: String, usage: ServerUsage, at: Long) {
        if (usage.promptTokens <= 0 && usage.completionTokens <= 0) return
        val personaId = runCatching { sessionDao.getById(sessionId)?.personaId }.getOrNull()
        runCatching {
            usageDao.insert(
                ApiUsageEntity(
                    id = UUID.randomUUID().toString(),
                    at = at,
                    kind = KIND_CHAT,
                    personaId = personaId,
                    sessionId = sessionId,
                    modelName = SERVER_MODEL_NAME,
                    promptTokens = usage.promptTokens.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    completionTokens = usage.completionTokens.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    costMicroUsd = 0L
                )
            )
        }
    }

    private companion object {
        /** 与 `ApiUsageEntity.kind` 的既有取值一致（聊天）。 */
        const val KIND_CHAT = "CHAT"

        /** 服务端那次生成用的模型不归客户端知道；记一个能看出「这是服务端聊天」的名字。 */
        const val SERVER_MODEL_NAME = "agentd"
    }
}
