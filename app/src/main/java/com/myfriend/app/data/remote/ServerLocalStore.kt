package com.myfriend.app.data.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.serverStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "myfriend_server_state")

/**
 * 一条「还没被服务端接受」的用户消息（离线待发队列，`docs/design/server-authoritative.md` §6）。
 *
 * 关键字段是 [clientMessageId]：服务端用它做幂等，网络恢复后重发同一条不会产生第二条消息。
 * [createdAt] 只用于「按用户发送顺序补发」，不参与服务端排序。
 */
@Serializable
data class PendingServerMessage(
    val clientMessageId: String,
    val sessionId: String,
    val content: String,
    val attachments: List<String> = emptyList(),
    val replyTo: String? = null,
    val createdAt: Long = 0L,
    /** 已经试过几次（仅用于日志/诊断，不决定是否放弃：用户的话不该被自动丢掉）。 */
    val attempts: Int = 0
)

/**
 * 服务端模式在客户端留下的**本机状态**：同步游标 + 离线待发队列。
 *
 * 为什么放 DataStore 而不是 Room：
 * - 这两样都只属于「本机连哪台服务端」，不是聊天数据，换服务端/清缓存应当能单独处置；
 * - 不引入 Room 迁移（聊天缓存沿用现有 v14 表结构，服务端消息只是写进同一批表）。
 *
 * 接口化的原因是纯 JVM 单测：同步游标的前进、离线队列的幂等与补发顺序都必须能直接测，
 * 不需要 Android DataStore。
 */
interface ServerLocalStore {
    suspend fun cursor(): Long
    suspend fun setCursor(cursor: Long)

    /**
     * `/v1/state` 的 `since` 游标（**epoch 毫秒**）。
     *
     * 与 [cursor] 分开是必要的：agentd 的 `/v1/state` 按 `updatedAt > since` / `createdAt > since`
     * 过滤，要的是时间戳；而 [cursor] 是 `/v1/events` 的单调事件游标（从 1 开始）。
     * 把事件游标当时间戳传进去，会每次都退化成全量同步。默认委托给 [cursor]/[setCursor]
     * 只是为了让只测编排规则的假实现少写两个方法；生产实现用独立的 DataStore 键。
     */
    suspend fun stateCursor(): Long = cursor()
    suspend fun setStateCursor(cursor: Long) = setCursor(cursor)

    /** 按发送顺序（[PendingServerMessage.createdAt] 升序）返回待发消息。 */
    suspend fun pending(): List<PendingServerMessage>

    /**
     * 入队；同一个 [PendingServerMessage.clientMessageId] 已存在时返回 false（不重复入队）。
     */
    suspend fun enqueuePending(message: PendingServerMessage): Boolean

    /** 服务端已接受/已删除 → 出队。 */
    suspend fun removePending(clientMessageId: String)

    /** 记一次失败尝试；消息保留（离线恢复后继续补发）。 */
    suspend fun markPendingAttempt(clientMessageId: String)

    /**
     * 服务端会话 id → 本地缓存会话 id。
     *
     * 为什么需要映射：本地 `chat_sessions.personaId` 是唯一索引（一人一段），切到服务端模式时
     * 用户已有的本机会话 id 与服务端分配的 id 通常不同。把服务端的会话/消息直接按服务端 id
     * 插进 Room 会撞唯一索引（REPLACE 还会删掉本地那一段）。所以缓存一律落在本地会话 id 上，
     * 这里记下「这个本地会话对应服务端哪一个」，写路径再用 [serverSessionId] 反查。
     *
     * 默认实现返回 null / 空操作，方便只测编排规则的假实现不被迫关心映射。
     */
    suspend fun localSessionId(serverSessionId: String): String? = null

    /** 本地缓存会话 id → 服务端会话 id。 */
    suspend fun serverSessionId(localSessionId: String): String? = null

    /** 记下/更新一条映射（服务端 id 或本地 id 任一为空时忽略）。 */
    suspend fun setSessionMapping(serverSessionId: String, localSessionId: String) = Unit

    /**
     * 换了一台服务端：清空游标/会话映射/待发队列。
     *
     * 旧游标是旧服务端的单调游标，带着它去问新服务端会漏掉新服务端 0..old 的事件；
     * 待发队列里的 clientMessageId 与 sessionId 也只对旧服务端有意义。
     * 默认空操作，方便假实现只测自己关心的规则。
     */
    suspend fun resetForNewServer() = Unit
}

/**
 * 生产实现：Preferences DataStore。
 *
 * 队列用一份 JSON 数组存：待发消息通常只有个位数，JSON 的读写成本远低于为它建一张表；
 * 真正会变长的聊天数据在 Room 里。写入走 `DataStore.edit`（原子、串行），不会出现两条并发
 * 发送互相覆盖队列的情况。
 */
@Singleton
class DataStoreServerLocalStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) : ServerLocalStore {

    override suspend fun cursor(): Long =
        context.serverStateDataStore.data.first()[Keys.cursor]?.takeIf { it >= 0L } ?: 0L

    override suspend fun stateCursor(): Long =
        context.serverStateDataStore.data.first()[Keys.stateCursor]?.takeIf { it >= 0L } ?: 0L

    override suspend fun setStateCursor(cursor: Long) {
        if (cursor < 0L) return
        context.serverStateDataStore.edit { prefs ->
            val previous = prefs[Keys.stateCursor] ?: 0L
            // 时间戳游标同样只进不退；服务端时钟略慢时不能把客户端指针拨回过去。
            if (cursor > previous) prefs[Keys.stateCursor] = cursor
        }
    }

    override suspend fun setCursor(cursor: Long) {
        if (cursor < 0L) return
        context.serverStateDataStore.edit { prefs ->
            val previous = prefs[Keys.cursor] ?: 0L
            // 游标只增不减：服务端重启/回滚时不能把客户端指针往回拨（会重复处理旧事件）。
            if (cursor > previous) prefs[Keys.cursor] = cursor
        }
    }

    override suspend fun pending(): List<PendingServerMessage> =
        decode(context.serverStateDataStore.data.first()[Keys.pending])

    override suspend fun enqueuePending(message: PendingServerMessage): Boolean {
        var added = false
        context.serverStateDataStore.edit { prefs ->
            val current = decode(prefs[Keys.pending])
            if (current.any { it.clientMessageId == message.clientMessageId }) return@edit
            prefs[Keys.pending] = encode(current + message)
            added = true
        }
        return added
    }

    override suspend fun removePending(clientMessageId: String) {
        context.serverStateDataStore.edit { prefs ->
            val current = decode(prefs[Keys.pending])
            val next = current.filterNot { it.clientMessageId == clientMessageId }
            if (next.size != current.size) prefs[Keys.pending] = encode(next)
        }
    }

    override suspend fun markPendingAttempt(clientMessageId: String) {
        context.serverStateDataStore.edit { prefs ->
            val current = decode(prefs[Keys.pending])
            val next = current.map {
                if (it.clientMessageId == clientMessageId) it.copy(attempts = it.attempts + 1) else it
            }
            if (next != current) prefs[Keys.pending] = encode(next)
        }
    }

    override suspend fun localSessionId(serverSessionId: String): String? {
        if (serverSessionId.isBlank()) return null
        val prefs = context.serverStateDataStore.data.first()
        return decodeMap(prefs[Keys.serverToLocal])[serverSessionId]
    }

    override suspend fun serverSessionId(localSessionId: String): String? {
        if (localSessionId.isBlank()) return null
        val prefs = context.serverStateDataStore.data.first()
        return decodeMap(prefs[Keys.localToServer])[localSessionId]
    }

    override suspend fun setSessionMapping(serverSessionId: String, localSessionId: String) {
        if (serverSessionId.isBlank() || localSessionId.isBlank()) return
        context.serverStateDataStore.edit { prefs ->
            val serverToLocal = decodeMap(prefs[Keys.serverToLocal]) + (serverSessionId to localSessionId)
            val localToServer = decodeMap(prefs[Keys.localToServer]) + (localSessionId to serverSessionId)
            prefs[Keys.serverToLocal] = encodeMap(serverToLocal)
            prefs[Keys.localToServer] = encodeMap(localToServer)
        }
    }

    override suspend fun resetForNewServer() {
        context.serverStateDataStore.edit { prefs ->
            prefs.remove(Keys.cursor)
            prefs.remove(Keys.stateCursor)
            prefs.remove(Keys.pending)
            prefs.remove(Keys.serverToLocal)
            prefs.remove(Keys.localToServer)
        }
    }

    private fun decode(raw: String?): List<PendingServerMessage> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<PendingServerMessage>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun encode(messages: List<PendingServerMessage>): String =
        runCatching { json.encodeToString(messages.sortedBy { it.createdAt }) }.getOrDefault("[]")

    private fun decodeMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
    }

    private fun encodeMap(value: Map<String, String>): String =
        runCatching { json.encodeToString(value) }.getOrDefault("{}")

    private object Keys {
        val cursor = longPreferencesKey("server_cursor")
        val stateCursor = longPreferencesKey("server_state_cursor")
        val pending = stringPreferencesKey("server_pending_messages")
        val serverToLocal = stringPreferencesKey("server_session_to_local")
        val localToServer = stringPreferencesKey("local_session_to_server")
    }
}
