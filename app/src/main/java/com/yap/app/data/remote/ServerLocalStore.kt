package com.yap.app.data.remote

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

private val Context.serverStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "yap_server_state")

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
     * 人格 id 映射：`serverPersonaId ↔ localPersonaId`。
     *
     * 为什么初始 bootstrap 需要它：本地模式的人格 id 是客户端 UUID，`POST /v1/personas`
     * 不带 id 时服务端会分配自己的 id（服务端不接受客户端指定未知 id）。之后本地的写路径
     * （改人格、记忆、建会话、活动上报）必须用服务端 id，否则会 404；而增量同步拿回的
     * 服务端人格也不能按服务端 id 再插一行（本地会多出一个「同名的他」）。
     */
    suspend fun localPersonaId(serverPersonaId: String): String? = null

    /** 本地人格 id → 服务端人格 id。 */
    suspend fun serverPersonaId(localPersonaId: String): String? = null

    suspend fun setPersonaMapping(serverPersonaId: String, localPersonaId: String) = Unit

    /**
     * 记忆 id 映射：bootstrap 推送本地记忆时服务端会分配新 id。
     *
     * 本地记忆行保留自己的 id（`memory_terms` / `memory_vectors` / 回声都按它索引，
     * 换主键等于把这些派生表全部重写），改之后的「编辑/删除记忆」用映射查服务端 id。
     */
    suspend fun localMemoryId(serverMemoryId: String): String? = null

    suspend fun serverMemoryId(localMemoryId: String): String? = null

    suspend fun setMemoryMapping(serverMemoryId: String, localMemoryId: String) = Unit

    /**
     * 本机是否已经对**这台服务端**做过一次性 bootstrap（`server_bootstrap_done:<baseUrl>`）。
     *
     * 按地址分键而不是一个全局布尔：换服务端后新地址要重新判断两端数据量；
     * 换回旧地址时也不该再全量搬一遍（那次已经搬过，之后靠增量同步）。
     */
    suspend fun bootstrapDone(baseUrl: String): Boolean = false

    /** bootstrap 成功（或判定无需搬运）之后写标记；失败不写，下次前台重试。 */
    suspend fun markBootstrapDone(baseUrl: String) = Unit

    /**
     * 这台服务端的「切换同意」状态（0.13：`server_sync_consent:<baseUrl>` = FULL | NEW_ONLY）。
     *
     * 按地址分键：换服务端必须重新询问（用户同意的是「把历史交给这台机器」）；
     * 换回同一台地址时沿用上次的选择，不再重复弹窗。
     */
    suspend fun syncConsent(baseUrl: String): ServerSyncConsent? = null

    /** 记录同意（用户点了「同意并同步」/「只同步之后的新内容」）。 */
    suspend fun setSyncConsent(baseUrl: String, consent: ServerSyncConsent) = Unit

    /**
     * 清掉某台服务端的 bootstrap 标记（「删除全部数据」/主动断开时用）。
     *
     * 普通换地址**不**调用它：换回旧地址时上次已经搬过，应该继续靠增量同步；
     * 只有本机数据被清空、需要重新做一次全量对齐时才清。
     */
    suspend fun clearBootstrapDone(baseUrl: String) = Unit

    /**
     * 换了一台服务端：清空游标/会话映射/待发队列。
     *
     * 旧游标是旧服务端的单调游标，带着它去问新服务端会漏掉新服务端 0..old 的事件；
     * 待发队列里的 clientMessageId 与 sessionId 也只对旧服务端有意义；会话 id 只对旧服务端
     * 有意义（**人格/记忆 id 映射刻意保留**：它们把「本机 UUID」绑到「某个服务端 id」，
     * 换回旧地址时没有它，增量同步会在本机多插一份同名人格；新服务端上刚开始用不到，
     * bootstrap 推送时会先核对远端列表再决定是否沿用）。
     *
     * **刻意不清 bootstrap 标记**：它是「这台地址搬过没有」的历史，按地址分键本身就不冲突。
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
        // 映射在每次增量同步都会走到：值没变就不要写盘（DataStore 的写入不是零成本）。
        val current = context.serverStateDataStore.data.first()
        if (decodeMap(current[Keys.serverToLocal])[serverSessionId] == localSessionId) return
        context.serverStateDataStore.edit { prefs ->
            val serverToLocal = decodeMap(prefs[Keys.serverToLocal]) + (serverSessionId to localSessionId)
            val localToServer = decodeMap(prefs[Keys.localToServer]) + (localSessionId to serverSessionId)
            prefs[Keys.serverToLocal] = encodeMap(serverToLocal)
            prefs[Keys.localToServer] = encodeMap(localToServer)
        }
    }

    override suspend fun localPersonaId(serverPersonaId: String): String? {
        if (serverPersonaId.isBlank()) return null
        return decodeMap(context.serverStateDataStore.data.first()[Keys.serverToLocalPersona])[serverPersonaId]
    }

    override suspend fun serverPersonaId(localPersonaId: String): String? {
        if (localPersonaId.isBlank()) return null
        return decodeMap(context.serverStateDataStore.data.first()[Keys.localToServerPersona])[localPersonaId]
    }

    override suspend fun setPersonaMapping(serverPersonaId: String, localPersonaId: String) {
        if (serverPersonaId.isBlank() || localPersonaId.isBlank()) return
        val current = context.serverStateDataStore.data.first()
        if (decodeMap(current[Keys.serverToLocalPersona])[serverPersonaId] == localPersonaId) return
        context.serverStateDataStore.edit { prefs ->
            prefs[Keys.serverToLocalPersona] = encodeMap(
                decodeMap(prefs[Keys.serverToLocalPersona]) + (serverPersonaId to localPersonaId)
            )
            prefs[Keys.localToServerPersona] = encodeMap(
                decodeMap(prefs[Keys.localToServerPersona]) + (localPersonaId to serverPersonaId)
            )
        }
    }

    override suspend fun localMemoryId(serverMemoryId: String): String? {
        if (serverMemoryId.isBlank()) return null
        return decodeMap(context.serverStateDataStore.data.first()[Keys.serverToLocalMemory])[serverMemoryId]
    }

    override suspend fun serverMemoryId(localMemoryId: String): String? {
        if (localMemoryId.isBlank()) return null
        return decodeMap(context.serverStateDataStore.data.first()[Keys.localToServerMemory])[localMemoryId]
    }

    override suspend fun setMemoryMapping(serverMemoryId: String, localMemoryId: String) {
        if (serverMemoryId.isBlank() || localMemoryId.isBlank()) return
        val current = context.serverStateDataStore.data.first()
        if (decodeMap(current[Keys.serverToLocalMemory])[serverMemoryId] == localMemoryId) return
        context.serverStateDataStore.edit { prefs ->
            prefs[Keys.serverToLocalMemory] = encodeMap(
                decodeMap(prefs[Keys.serverToLocalMemory]) + (serverMemoryId to localMemoryId)
            )
            prefs[Keys.localToServerMemory] = encodeMap(
                decodeMap(prefs[Keys.localToServerMemory]) + (localMemoryId to serverMemoryId)
            )
        }
    }

    override suspend fun bootstrapDone(baseUrl: String): Boolean {
        val key = bootstrapKey(baseUrl) ?: return false
        return context.serverStateDataStore.data.first()[key] == BOOTSTRAP_DONE_VALUE
    }

    override suspend fun markBootstrapDone(baseUrl: String) {
        val key = bootstrapKey(baseUrl) ?: return
        context.serverStateDataStore.edit { prefs -> prefs[key] = BOOTSTRAP_DONE_VALUE }
    }

    override suspend fun clearBootstrapDone(baseUrl: String) {
        val key = bootstrapKey(baseUrl) ?: return
        context.serverStateDataStore.edit { prefs -> prefs.remove(key) }
    }

    override suspend fun syncConsent(baseUrl: String): ServerSyncConsent? {
        val key = consentKey(baseUrl) ?: return null
        val raw = context.serverStateDataStore.data.first()[key] ?: return null
        return ServerSyncConsent.parse(raw)
    }

    override suspend fun setSyncConsent(baseUrl: String, consent: ServerSyncConsent) {
        val key = consentKey(baseUrl) ?: return
        context.serverStateDataStore.edit { prefs -> prefs[key] = consent.storedValue }
    }

    override suspend fun resetForNewServer() {
        context.serverStateDataStore.edit { prefs ->
            prefs.remove(Keys.cursor)
            prefs.remove(Keys.stateCursor)
            prefs.remove(Keys.pending)
            prefs.remove(Keys.serverToLocal)
            prefs.remove(Keys.localToServer)
            // 人格/记忆映射保留：换回旧地址时它们仍然正确；新服务端上 bootstrap 会先核对
            // 远端列表，不会盲目沿用（见 ServerBootstrapManager 的 `in remotePersonaIds` 判断）。
        }
    }

    /**
     * 标记键：`server_bootstrap_done:<归一化 baseUrl>`。
     *
     * 归一只做 `trimEnd('/')`：与 [ServerModeRepository] 交给运行时的 `config.baseUrl` 同形，
     * 不做协议归一化（`http`/`https` 是两台不同的服务端，不能共用标记）。
     */
    private fun bootstrapKey(baseUrl: String): Preferences.Key<String>? {
        val clean = baseUrl.trim().trimEnd('/')
        if (clean.isEmpty()) return null
        return stringPreferencesKey("$BOOTSTRAP_KEY_PREFIX$clean")
    }

    /** 同意键：`server_sync_consent:<归一化 baseUrl>`（规格逐字要求的前缀）。 */
    private fun consentKey(baseUrl: String): Preferences.Key<String>? {
        val clean = baseUrl.trim().trimEnd('/')
        if (clean.isEmpty()) return null
        return stringPreferencesKey("$CONSENT_KEY_PREFIX$clean")
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
        val serverToLocalPersona = stringPreferencesKey("server_persona_to_local")
        val localToServerPersona = stringPreferencesKey("local_persona_to_server")
        val serverToLocalMemory = stringPreferencesKey("server_memory_to_local")
        val localToServerMemory = stringPreferencesKey("local_memory_to_server")
    }

    private companion object {
        /** bootstrap 标记的键前缀（后接归一化 baseUrl）。 */
        const val BOOTSTRAP_KEY_PREFIX = "server_bootstrap_done:"

        /** 切换同意的键前缀（后接归一化 baseUrl）：`server_sync_consent:<url>`。 */
        const val CONSENT_KEY_PREFIX = "server_sync_consent:"

        /** 标记值；DataStore 只存字符串，值本身不重要，但只要键存在就代表完成。 */
        const val BOOTSTRAP_DONE_VALUE = "1"
    }
}
