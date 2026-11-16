package com.yap.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 一条增量事件解码之后的样子（`GET /v1/events`，契约 §3.4）。
 *
 * 未知类型保留为 [Unknown]：服务端将来加 `typing.started` 之类的事件时，客户端应该忽略它
 * 而不是丢掉整批（否则一条新事件就能让「他的新消息」永远同步不到）。
 */
sealed class ServerEventChange {
    data class MessageCreated(val message: ServerMessage) : ServerEventChange()
    data class MoodChanged(val mood: ServerMood) : ServerEventChange()
    data class PresenceChanged(val presence: ServerPresence) : ServerEventChange()
    data class Unknown(val type: String) : ServerEventChange()
}

/**
 * 事件 payload 解码（纯函数，可单测）。
 *
 * 服务端的 payload 形状：`message.created → {message:{…}}`、`mood.changed → {mood:{…}}`、
 * `presence.changed → {presence:{…}}`。字段缺失/坏 JSON 时返回 [ServerEventChange.Unknown]，
 * 不让一条坏事件阻断后续事件。
 */
object ServerEventDecoder {

    fun decode(event: ServerEvent, json: Json = ServerApi.DEFAULT_JSON): ServerEventChange {
        val payload = event.payload
        return runCatching {
            when (event.type) {
                TYPE_MESSAGE_CREATED -> payload.objectOf("message")
                    ?.let { ServerEventChange.MessageCreated(json.decodeFromJsonElement<ServerMessage>(it)) }

                TYPE_MOOD_CHANGED -> payload.objectOf("mood")
                    ?.let { ServerEventChange.MoodChanged(json.decodeFromJsonElement<ServerMood>(it)) }

                TYPE_PRESENCE_CHANGED -> payload.objectOf("presence")
                    ?.let { ServerEventChange.PresenceChanged(json.decodeFromJsonElement<ServerPresence>(it)) }

                else -> null
            }
        }.getOrNull() ?: ServerEventChange.Unknown(event.type)
    }

    private const val TYPE_MESSAGE_CREATED = "message.created"
    private const val TYPE_MOOD_CHANGED = "mood.changed"
    private const val TYPE_PRESENCE_CHANGED = "presence.changed"

    private fun JsonObject.objectOf(name: String): JsonObject? = this[name] as? JsonObject
}

/** 事件落库之后给通知层的最小信息；null 表示这条事件不需要打扰用户。 */
data class ServerEventApplied(
    val notifySessionId: String? = null,
    val notifyPersonaId: String? = null,
    val notifyText: String? = null
)

/** 事件落库口（Room 实现；测试用假实现断言「新消息入缓存」）。 */
interface ServerEventSink {
    suspend fun apply(change: ServerEventChange): ServerEventApplied
}

/** 本地通知口；生产实现会自己判断「App 在前台且没开着这个会话」。 */
interface ServerEventNotifier {
    suspend fun notifyMessage(sessionId: String, personaId: String, text: String)
}

/** 一次长轮询的结果。 */
data class ServerPollOutcome(
    val events: Int = 0,
    val cursor: Long = 0,
    val notified: Int = 0,
    val error: Throwable? = null
) {
    val success: Boolean get() = error == null
}

/**
 * 前台事件长轮询（M4）：
 *
 * ```
 * events(since=cursor) → 逐条落 Room → 新的 assistant 消息触发本地通知 → 游标前进
 * ```
 *
 * 三条容易写错、所以单测钉住的规则：
 * 1. **空轮询不前移游标**：服务端在超时返回时原样带回旧 cursor，客户端不能「因为请求成功」
 *    就把指针向前拨；
 * 2. **一条坏事件不影响整批**：解码失败/落库失败只跳过这一条（游标仍按整批前进，否则
 *    下一次还会拉到同一条坏事件，永远卡住）；
 * 3. **失败不前进**：网络错/5xx 时保持旧游标，下一次重试还能拿到这段时间的事件。
 *
 * 这里只做一次轮询；循环（前台启动/退后台停止/失败退避）由 `ServerModeRuntime` 负责，
 * 这样测试不需要控制协程调度。
 */
class ServerEventPoller(
    private val remoteProvider: ServerRemoteProvider,
    private val localStore: ServerLocalStore,
    private val sink: ServerEventSink,
    private val notifier: ServerEventNotifier,
    private val decoder: (ServerEvent) -> ServerEventChange = { ServerEventDecoder.decode(it) }
) {

    suspend fun pollOnce(): ServerPollOutcome {
        val remote = remoteProvider.remote() ?: return ServerPollOutcome(cursor = localStore.cursor())
        val since = localStore.cursor()
        return try {
            val batch = remote.events(since)
            var applied = 0
            var notified = 0
            batch.events.forEach { event ->
                val change = runCatching { decoder(event) }.getOrDefault(ServerEventChange.Unknown(event.type))
                val effect = runCatching { sink.apply(change) }.getOrNull() ?: return@forEach
                applied++
                val sessionId = effect.notifySessionId
                val personaId = effect.notifyPersonaId
                val text = effect.notifyText
                if (!sessionId.isNullOrBlank() && !personaId.isNullOrBlank() && !text.isNullOrBlank()) {
                    runCatching { notifier.notifyMessage(sessionId, personaId, text) }
                    notified++
                }
            }
            if (batch.events.isNotEmpty()) {
                val next = maxOf(since, batch.cursor, batch.events.maxOfOrNull { it.cursor } ?: since)
                localStore.setCursor(next)
                ServerPollOutcome(events = applied, cursor = next, notified = notified)
            } else {
                // 没事件时服务端原样返回旧 cursor；不写库（DataStore 写入本身就不该为空轮询发生）。
                ServerPollOutcome(events = 0, cursor = since, notified = 0)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            ServerPollOutcome(events = 0, cursor = since, notified = 0, error = error)
        }
    }
}
