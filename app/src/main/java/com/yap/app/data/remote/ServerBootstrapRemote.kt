package com.yap.app.data.remote

import kotlinx.serialization.json.JsonObject

/**
 * bootstrap 的**服务端读写面**（抽接口：纯 JVM 单测注入假实现，断言顺序/批量/幂等）。
 *
 * 所有方法都是「一次调用一个网络请求」：编排（重试、分批、进度）在 [ServerBootstrapManager]，
 * 传输细节在 [ServerApi]。这样测试不需要 socket，也不需要 `android.util.Log`。
 */
interface ServerBootstrapRemote {

    /** `GET /v1/state?since=0`：计数与游标来源（每个会话只给最新一块，不用于全量消息）。 */
    suspend fun state(): StateSnapshot

    /** `GET /v1/personas`：完整人格 JSON 列表。 */
    suspend fun personas(): List<JsonObject>

    /** `GET /v1/sessions`：会话列表。 */
    suspend fun sessions(): List<ServerSession>

    /** `GET /v1/settings`；不支持时返回空对象（视作「服务端还没有设置」）。 */
    suspend fun settings(): JsonObject = JsonObject(emptyMap())

    /** `GET /v1/presets`；端点缺失（404/405）时返回 null —— 不阻断其它数据的搬运。 */
    suspend fun presets(): List<JsonObject>? = null

    /** `GET /v1/personas/{id}/memories` 全部分页；端点缺失时返回 null。 */
    suspend fun memories(personaId: String): List<JsonObject>? = null

    /** `POST /v1/personas`，返回**内层** `persona` 对象。 */
    suspend fun createPersona(payload: JsonObject): JsonObject

    /** 更新已有服务端人格（payload 里带服务端 id），返回内层 `persona` 对象。 */
    suspend fun updatePersona(payload: JsonObject): JsonObject = createPersona(payload)

    /** `POST /v1/sessions`（一人格一段；已存在时服务端返回既有会话）。 */
    suspend fun createSession(personaId: String, title: String?): ServerSession

    /** `POST /v1/sessions/{id}/messages/import`；返回服务端计数（0 也无所谓）。 */
    suspend fun importMessages(sessionId: String, messages: List<ServerMessageImport>): Int

    /** 最新一块消息。 */
    suspend fun latestPage(sessionId: String): ServerMessagePage

    /** `beforeBlock` 之前的一块消息。 */
    suspend fun pageBefore(sessionId: String, beforeBlock: Int): ServerMessagePage

    /** `POST /v1/presets`，返回内层 `preset` 对象。 */
    suspend fun savePreset(payload: JsonObject): JsonObject

    /** `POST /v1/personas/{id}/memories`，返回内层 `memory` 对象。 */
    suspend fun saveMemory(personaId: String, payload: JsonObject): JsonObject

    /** `POST /v1/settings`（白名单合并）。 */
    suspend fun pushSettings(values: JsonObject) = Unit
}

/**
 * [ServerApi] → [ServerBootstrapRemote] 的生产适配器。
 *
 * 这里只做三件事：解包 `{persona:…}` / `{preset:…}` / `{memory:…}` 外层、把可缺省的
 * 列表端点（presets/memories）的 404/405 当成「服务端这个版本没有」而不是失败、
 * 以及把记忆分页按 `since`（updatedAt）翻完。
 */
class ServerApiBootstrapRemote(
    private val provider: ServerApiProvider
) : ServerBootstrapRemote {

    /**
     * 每次调用现取客户端：设置页换地址后 [com.yap.app.data.remote.ServerModeRepository]
     * 会换一个 [ServerApi]，长活的 bootstrap 不能攥着旧地址的客户端。
     */
    private fun requireApi(): ServerApi = provider.api() ?: throw ServerApiException(
        "server mode is not available",
        code = "server_unavailable"
    )

    override suspend fun state(): StateSnapshot = requireApi().state(since = 0L)

    override suspend fun personas(): List<JsonObject> = requireApi().personas()

    override suspend fun sessions(): List<ServerSession> = requireApi().sessions()

    /**
     * `GET /v1/settings` 的真实形状是 `{"settings":{…}}`（`agentd` 的 `getSettings`），
     * 这里解包成白名单键的平铺对象；老服务端若直接回平铺对象也兼容。
     */
    override suspend fun settings(): JsonObject {
        val response = runCatching { requireApi().settings() }.getOrDefault(JsonObject(emptyMap()))
        return response.child("settings") ?: response
    }

    override suspend fun presets(): List<JsonObject>? =
        optionalEndpoint { requireApi().presets() }

    override suspend fun memories(personaId: String): List<JsonObject>? =
        optionalEndpoint { fetchAllMemories(personaId) }

    override suspend fun createPersona(payload: JsonObject): JsonObject =
        unwrap(requireApi().savePersona(payload), "persona")

    override suspend fun updatePersona(payload: JsonObject): JsonObject =
        unwrap(requireApi().savePersona(payload), "persona")

    override suspend fun createSession(personaId: String, title: String?): ServerSession =
        requireApi().createSession(personaId, title).session

    override suspend fun importMessages(
        sessionId: String,
        messages: List<ServerMessageImport>
    ): Int = requireApi().importMessages(sessionId, messages).let { it.imported + it.skipped }

    override suspend fun latestPage(sessionId: String): ServerMessagePage =
        requireApi().messages(sessionId)

    override suspend fun pageBefore(sessionId: String, beforeBlock: Int): ServerMessagePage =
        requireApi().messages(sessionId, beforeBlock = beforeBlock)

    override suspend fun savePreset(payload: JsonObject): JsonObject =
        unwrap(requireApi().savePreset(payload), "preset")

    override suspend fun saveMemory(personaId: String, payload: JsonObject): JsonObject =
        unwrap(requireApi().saveMemory(personaId, payload), "memory")

    override suspend fun pushSettings(values: JsonObject) {
        runCatching { requireApi().postSettings(values) }
    }

    /**
     * 翻完一个记忆分页。
     *
     * `since` 是「updatedAt > since」的严格游标：服务端按 updatedAt 倒序取一页，
     * 所以下一页从这一页最小的 updatedAt 继续。返回数量不足一页就结束，最多翻
     * [MAX_MEMORY_PAGES] 页（防御服务端游标不前进的 bug）。
     */
    private suspend fun fetchAllMemories(personaId: String): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var since = 0L
        var pages = 0
        while (pages < MAX_MEMORY_PAGES) {
            pages++
            val page = requireApi().memories(personaId, since = since)
            all += page
            if (page.size < ServerApi.MEMORY_PAGE_LIMIT) break
            val oldest = page.minOfOrNull { it.long("updatedAt") ?: 0L } ?: break
            if (oldest <= since) break
            since = oldest
        }
        return all
    }

    private suspend fun <T> optionalEndpoint(block: suspend () -> T): T? = try {
        block()
    } catch (missing: ServerApiException) {
        if (missing.status == 404 || missing.status == 405 || missing.code == "not_found") null else throw missing
    }

    private fun unwrap(response: JsonObject, key: String): JsonObject =
        response.child(key) ?: response

    private companion object {
        /** 防御性上限：50 万条记忆以内都能翻完（500/页）。 */
        const val MAX_MEMORY_PAGES = 1_000
    }
}
