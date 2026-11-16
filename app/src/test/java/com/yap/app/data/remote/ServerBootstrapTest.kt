package com.yap.app.data.remote

import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MemoryCategory
import com.yap.app.data.local.MemoryEntity
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageStatus
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPresetEntity
import com.yap.app.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.12.1 一次性 bootstrap 的纯 JVM 单测。
 *
 * 覆盖任务要求的四象限、幂等、批量、时间/已读保留、失败重试与标记：
 * 1. 服务端空 + 本地有 → 全量推送（人格/会话/记忆/设置/消息分批）；
 * 2. 本地空 + 服务端有 → 全量拉取（从最新块往前翻完所有块）；
 * 3. 两端都有 → 合并（本地独有推送、服务端独有拉取、同 id 服务端优先）；
 * 4. 两端都空 → NOOP，只写标记；
 * 5. 同地址重复执行 → 跳过，不重复导入；
 * 6. 失败重试 → 不写标记、重试成功后消息不重复（幂等键）。
 */
class ServerBootstrapTest {

    // ------------------------------------------------------------------ 假实现

    private class FakeRemote : ServerBootstrapRemote {
        val personas = mutableListOf<JsonObject>()
        val sessions = mutableListOf<ServerSession>()
        val presets = mutableListOf<JsonObject>()
        val settings = mutableMapOf<String, String>()
        val memories = mutableMapOf<String, MutableList<JsonObject>>()
        val blocks = mutableMapOf<String, MutableMap<Int, MutableList<ServerMessage>>>()

        var presetsSupported = true
        var memoriesSupported = true
        var failImportsTimes = 0
        var importCalls = mutableListOf<Pair<String, List<ServerMessageImport>>>()
        var createdPersonas = mutableListOf<JsonObject>()
        var createdSessions = mutableListOf<String>()
        var pushedSettings: JsonObject? = null
        var pageBeforeCalls = 0
        var updatePersonaCalls = 0
        var createdSessionCount = 0
        private val personaSeq = AtomicInteger(0)

        override suspend fun state(): StateSnapshot = StateSnapshot(
            serverTime = 1_000,
            personas = personas.map { ServerBootstrapMapping.personaSummary(it) },
            sessions = sessions.toList(),
            serverCursor = 7,
            messages = sessions.flatMap { latestPage(it.id).messages }
        )

        override suspend fun personas(): List<JsonObject> = personas.toList()

        override suspend fun sessions(): List<ServerSession> = sessions.toList()

        override suspend fun settings(): JsonObject = buildJsonObject {
            settings.forEach { (key, value) -> put(key, value) }
        }

        override suspend fun presets(): List<JsonObject>? =
            if (presetsSupported) presets.toList() else null

        override suspend fun memories(personaId: String): List<JsonObject>? =
            if (memoriesSupported) memories[personaId]?.toList().orEmpty() else null

        override suspend fun createPersona(payload: JsonObject): JsonObject {
            createdPersonas += payload
            val id = "srv-persona-${personaSeq.incrementAndGet()}"
            val stored = JsonObject(payload + mapOf("id" to JsonPrimitive(id), "updatedAt" to JsonPrimitive(2_000)))
            personas += stored
            return stored
        }

        override suspend fun updatePersona(payload: JsonObject): JsonObject {
            updatePersonaCalls++
            val id = payload.getValue("id").let { (it as JsonPrimitive).content }
            val index = personas.indexOfFirst { (it["id"] as? JsonPrimitive)?.content == id }
            val stored = JsonObject(payload + mapOf("updatedAt" to JsonPrimitive(9_000)))
            if (index >= 0) personas[index] = stored else personas += stored
            return stored
        }

        override suspend fun createSession(personaId: String, title: String?): ServerSession {
            sessions.firstOrNull { it.personaId == personaId }?.let { return it }
            val session = ServerSession(
                id = "srv-session-${++createdSessionCount}",
                personaId = personaId,
                title = title ?: "chat",
                updatedAt = 2_000,
                createdAt = 2_000
            )
            sessions += session
            createdSessions += session.id
            return session
        }

        override suspend fun importMessages(
            sessionId: String,
            messages: List<ServerMessageImport>
        ): Int {
            importCalls += sessionId to messages
            if (failImportsTimes > 0) {
                failImportsTimes--
                error("simulated import failure")
            }
            val block = blocks.getOrPut(sessionId) { mutableMapOf() }.getOrPut(0) { mutableListOf() }
            var imported = 0
            messages.forEach { message ->
                // 服务端幂等语义：同 id 不重复落库。
                if (block.none { it.id == message.id }) {
                    block += ServerMessage(
                        id = message.id,
                        sessionId = sessionId,
                        blockIndex = 0,
                        role = message.role,
                        content = message.content,
                        createdAt = message.createdAt,
                        readAt = message.readAt,
                        attachments = message.attachments
                    )
                    imported++
                }
            }
            sessions.replaceAll {
                if (it.id == sessionId) it.copy(messageCount = it.messageCount + imported, lastBlockIndex = 0) else it
            }
            return imported
        }

        override suspend fun latestPage(sessionId: String): ServerMessagePage {
            val blocks = blocks[sessionId].orEmpty()
            val newest = blocks.keys.maxOrNull() ?: return ServerMessagePage(sessionId = sessionId)
            return page(sessionId, newest)
        }

        override suspend fun pageBefore(sessionId: String, beforeBlock: Int): ServerMessagePage {
            pageBeforeCalls++
            val blocks = blocks[sessionId].orEmpty()
            val previous = blocks.keys.filter { it < beforeBlock }.maxOrNull()
                ?: return ServerMessagePage(sessionId = sessionId)
            return page(sessionId, previous)
        }

        override suspend fun savePreset(payload: JsonObject): JsonObject {
            val id = payload["id"]?.let { (it as? JsonPrimitive)?.content }
                ?: "srv-preset-${presets.size + 1}"
            val stored = JsonObject(payload + mapOf("id" to JsonPrimitive(id), "updatedAt" to JsonPrimitive(2_000)))
            val index = presets.indexOfFirst { (it["id"] as? JsonPrimitive)?.content == id }
            if (index >= 0) presets[index] = stored else presets += stored
            return stored
        }

        override suspend fun saveMemory(personaId: String, payload: JsonObject): JsonObject {
            val list = memories.getOrPut(personaId) { mutableListOf() }
            val id = payload["id"]?.let { (it as? JsonPrimitive)?.content }
                ?: "srv-memory-${list.size + 1}-${personaId}"
            val stored = JsonObject(payload + mapOf("id" to JsonPrimitive(id), "updatedAt" to JsonPrimitive(2_000)))
            val index = list.indexOfFirst { (it["id"] as? JsonPrimitive)?.content == id }
            if (index >= 0) list[index] = stored else list += stored
            return stored
        }

        override suspend fun pushSettings(values: JsonObject) {
            pushedSettings = values
            values.forEach { (key, value) ->
                (value as? JsonPrimitive)?.content?.let { settings[key] = it }
            }
        }

        fun seedBlock(sessionId: String, blockIndex: Int, messages: List<ServerMessage>) {
            blocks.getOrPut(sessionId) { mutableMapOf() }[blockIndex] = messages.toMutableList()
            sessions.replaceAll {
                if (it.id == sessionId) {
                    it.copy(
                        lastBlockIndex = maxOf(it.lastBlockIndex, blockIndex),
                        messageCount = it.messageCount + messages.size
                    )
                } else it
            }
        }

        private fun page(sessionId: String, blockIndex: Int): ServerMessagePage {
            val blocks = blocks[sessionId].orEmpty()
            return ServerMessagePage(
                sessionId = sessionId,
                blockIndex = blockIndex,
                hasMoreOlder = blocks.keys.any { it < blockIndex },
                messages = blocks[blockIndex].orEmpty()
            )
        }
    }

    private class FakeLocal(
        private val store: TestServerLocalStore
    ) : ServerBootstrapLocal {
        val personas = linkedMapOf<String, PersonaEntity>()
        val presets = linkedMapOf<String, PersonaPresetEntity>()
        val memories = mutableListOf<MemoryEntity>()
        val sessions = linkedMapOf<String, ChatSessionEntity>()
        val messages = mutableMapOf<String, MutableList<MessageEntity>>()
        var settings = AppSettings()
        var appliedSettings: JsonObject? = null
        var appliedAvatar: Pair<String?, String?>? = null
        var stateCursor = 0L
        var eventCursor = 0L

        override suspend fun counts(): ServerBootstrapCounts = ServerBootstrapCounts(
            localPersonas = personas.size,
            localSessions = sessions.size,
            localMessages = messages.values.sumOf { it.size }
        )

        override suspend fun personas(): List<PersonaEntity> = personas.values.toList()

        override suspend fun presets(): List<PersonaPresetEntity> = presets.values.toList()

        override suspend fun memories(personaId: String?): List<MemoryEntity> =
            if (personaId == null) memories.toList() else memories.filter { it.personaId == personaId }

        override suspend fun sessions(): List<ChatSessionEntity> = sessions.values.toList()

        override suspend fun messages(localSessionId: String): List<MessageEntity> =
            messages[localSessionId].orEmpty().toList()

        override suspend fun settings(): AppSettings = settings

        override suspend fun applySnapshot(snapshot: StateSnapshot) {
            snapshot.personas.forEach { summary ->
                if (summary.id.isBlank()) return@forEach
                val localId = store.localPersonaId(summary.id) ?: summary.id
                if (personas[localId] == null) {
                    personas[localId] = PersonaEntity(
                        id = localId,
                        name = summary.name,
                        description = "",
                        avatarUri = summary.avatarUrl,
                        avatarSeed = 0,
                        prompt = "",
                        category = ServerMergePlan.DEFAULT_SERVER_PERSONA_CATEGORY,
                        version = 1,
                        defaultApiConfigId = null,
                        modelOverride = null,
                        isDefault = false,
                        createdAt = summary.updatedAt,
                        updatedAt = summary.updatedAt
                    )
                }
                store.setPersonaMapping(summary.id, localId)
            }
            snapshot.sessions.forEach { server ->
                val localId = store.localSessionId(server.id)
                    ?: sessions.values.firstOrNull { it.personaId == (store.localPersonaId(server.personaId) ?: server.personaId) }?.id
                    ?: server.id
                if (sessions[localId] == null) {
                    sessions[localId] = ChatSessionEntity(
                        id = localId,
                        personaId = store.localPersonaId(server.personaId) ?: server.personaId,
                        apiConfigId = null,
                        model = "",
                        title = server.title,
                        lastMessagePreview = null,
                        lastMessageAt = server.lastMessageAt,
                        unreadCount = server.unread,
                        pinned = false,
                        createdAt = server.createdAt,
                        updatedAt = server.updatedAt,
                        lastPersonaVersion = 1,
                        lastMemoryExtractedCount = 0
                    )
                }
                store.setSessionMapping(server.id, localId)
            }
        }

        override suspend fun applyPage(page: ServerMessagePage, personaId: String?) {
            if (page.messages.isEmpty()) return
            val localSessionId = store.localSessionId(page.sessionId) ?: page.sessionId
            val list = messages.getOrPut(localSessionId) { mutableListOf() }
            page.messages.forEach { server ->
                val entity = ServerMergePlan.message(server, personaId.orEmpty(), list.firstOrNull { it.id == server.id })
                    .copy(sessionId = localSessionId)
                list.removeAll { it.id == entity.id }
                list += entity
            }
        }

        override suspend fun applyPersonas(personas: List<PersonaEntity>) {
            personas.forEach { persona ->
                val local = this.personas[persona.id]
                if (local == null || persona.updatedAt >= local.updatedAt) this.personas[persona.id] = persona
            }
        }

        override suspend fun applyPreset(preset: PersonaPresetEntity) {
            presets[preset.id] = preset
        }

        override suspend fun replacePresetId(localId: String, preset: PersonaPresetEntity) {
            presets.remove(localId)
            presets[preset.id] = preset
        }

        override suspend fun applyMemory(memory: MemoryEntity) {
            memories.removeAll { it.id == memory.id }
            memories += memory
        }

        override suspend fun applySettings(values: JsonObject) {
            appliedSettings = values
        }

        override suspend fun applyUserAvatar(serverRef: String?, localRef: String?) {
            appliedAvatar = serverRef to localRef
            settings = settings.copy(userAvatarUri = serverRef, userAvatarLocalUri = localRef)
        }

        override suspend fun setSyncCursors(stateCursor: Long, eventCursor: Long) {
            this.stateCursor = stateCursor
            this.eventCursor = eventCursor
        }

        fun addMessage(sessionId: String, message: MessageEntity) {
            messages.getOrPut(sessionId) { mutableListOf() } += message
        }
    }

    // ------------------------------------------------------------------ 构造数据

    private fun persona(id: String, name: String = "林深", updatedAt: Long = 100) = PersonaEntity(
        id = id,
        name = name,
        description = "desc",
        avatarUri = null,
        avatarSeed = 1,
        prompt = "prompt-$id",
        category = "日常聊天",
        version = 1,
        defaultApiConfigId = null,
        modelOverride = null,
        isDefault = false,
        createdAt = 10,
        updatedAt = updatedAt
    )

    private fun session(id: String, personaId: String, updatedAt: Long = 100) = ChatSessionEntity(
        id = id,
        personaId = personaId,
        apiConfigId = null,
        model = "",
        title = "和${personaId}的对话",
        lastMessagePreview = null,
        lastMessageAt = 50,
        unreadCount = 1,
        pinned = false,
        createdAt = 10,
        updatedAt = updatedAt,
        lastPersonaVersion = 1,
        lastMemoryExtractedCount = 0
    )

    private fun message(
        id: String,
        sessionId: String,
        personaId: String,
        role: MessageRole = MessageRole.USER,
        createdAt: Long = 100,
        readAt: Long? = null,
        attachmentsJson: String? = """[{"uri":"/v1/media/chat-1","mime":"image/jpeg"}]"""
    ) = MessageEntity(
        id = id,
        sessionId = sessionId,
        personaId = personaId,
        role = role,
        content = "content-$id",
        createdAt = createdAt,
        updatedAt = createdAt,
        status = MessageStatus.COMPLETE,
        errorMessage = null,
        model = null,
        edited = false,
        attachmentsJson = attachmentsJson,
        toolTraceJson = null,
        readAt = readAt,
        blockIndex = 0
    )

    private fun serverPersonaJson(id: String, name: String, updatedAt: Long) = buildJsonObject {
        put("id", id)
        put("name", name)
        put("description", "server desc")
        put("prompt", "server prompt")
        put("avatarUrl", "/v1/media/avatar-$id")
        put("createdAt", 10)
        put("updatedAt", updatedAt)
        put("traits", "[]")
        put("likes", "[]")
        put("behaviors", "[]")
    }

    private fun remoteMessage(
        id: String,
        sessionId: String,
        role: String = "USER",
        createdAt: Long = 100,
        readAt: Long? = null
    ) = ServerMessage(
        id = id,
        sessionId = sessionId,
        blockIndex = 0,
        role = role,
        content = "server-$id",
        createdAt = createdAt,
        readAt = readAt
    )

    // ------------------------------------------------------------------ 四象限

    @Test
    fun `服务端空本地有 全量推送 分批 映射 标记 且重复执行跳过`() = runBlocking {
        val store = TestServerLocalStore()
        val local = FakeLocal(store)
        val p1 = persona("local-p1", updatedAt = 100)
        val p2 = persona("local-p2", name = "Alan", updatedAt = 100)
        local.personas[p1.id] = p1
        local.personas[p2.id] = p2
        local.settings = AppSettings(activePersonaId = p1.id, themeMode = com.yap.app.data.prefs.ThemeMode.DARK)
        local.presets["preset-1"] = PersonaPresetEntity(
            id = "preset-1",
            name = "温柔",
            description = "",
            prompt = "p",
            category = "日常聊天",
            avatarUri = null,
            avatarSeed = 0,
            createdAt = 10,
            updatedAt = 20
        )
        local.memories += MemoryEntity(
            id = "mem-1",
            personaId = p1.id,
            content = "他喜欢咖啡",
            category = MemoryCategory.FACT,
            importance = 3,
            pinned = false,
            sourceSessionId = null,
            createdAt = 10,
            updatedAt = 20,
            lastUsedAt = 0,
            useCount = 1
        )
        local.sessions["local-s1"] = session("local-s1", p1.id)
        val messageCount = ServerApi.MAX_IMPORT_MESSAGES * 2 + 50
        repeat(messageCount) { index ->
            local.addMessage(
                "local-s1",
                message(
                    id = "m-$index",
                    sessionId = "local-s1",
                    personaId = p1.id,
                    createdAt = 1_000L + index,
                    readAt = if (index == 0) 1_500L else null
                )
            )
        }

        val remote = FakeRemote()
        val manager = ServerBootstrapManager(local, remote, store)

        val outcome = manager.runIfNeeded("http://server.test/")

        assertTrue(outcome.success)
        assertEquals(ServerBootstrapMode.PUSH, outcome.mode)
        assertEquals("本地 2 个人格都应推上去", 2, remote.personas.size)
        assertEquals("人格 id 映射必须落盘", p1.id, store.localPersonaId("srv-persona-1"))
        assertEquals("会话映射必须落盘", "local-s1", store.localSessionId("srv-session-1"))
        assertNotNull("记忆映射必须落盘", store.serverMemoryId("mem-1"))
        assertEquals("预设也要推", 1, remote.presets.size)
        assertNotNull("设置也要推", remote.pushedSettings)
        assertEquals(
            "activePersonaId 必须换成服务端 id（服务端有外键校验）",
            "srv-persona-1",
            (remote.pushedSettings?.get("activePersonaId") as? JsonPrimitive)?.content
        )

        val sizes = remote.importCalls.flatMap { it.second }.size
        assertEquals(messageCount, sizes)
        assertTrue("单批不得超过 200", remote.importCalls.all { it.second.size <= ServerApi.MAX_IMPORT_MESSAGES })
        assertEquals(3, remote.importCalls.size)
        // 时间/已读/附件/角色原样保留
        val first = remote.importCalls.first().second.first()
        assertEquals(MessageRole.USER.name, first.role)
        assertEquals(1_000L, first.createdAt)
        assertEquals(1_500L, first.readAt)
        assertEquals(listOf("/v1/media/chat-1"), first.attachments)
        assertTrue("用户消息带幂等键", first.clientMessageId?.startsWith("import-") == true)

        assertTrue(store.bootstrapDone("http://server.test"))
        assertEquals("一次 bootstrap 只写一次标记", 1, store.doneBootstraps.size)
        assertEquals(ServerBootstrapPhase.COMPLETED, manager.progress.value.phase)
        assertEquals(ServerBootstrapMode.PUSH, manager.progress.value.mode)
        assertEquals(messageCount, manager.progress.value.messages)

        // 重复执行：直接跳过，不再有任何写调用。
        val importsBefore = remote.importCalls.size
        val repeat = manager.runIfNeeded("http://server.test")
        assertTrue(repeat.success)
        assertTrue(repeat.skipped)
        assertEquals(importsBefore, remote.importCalls.size)
    }

    @Test
    fun `本地空服务端有 全量拉取 逐会话从最新块往前翻完 并还原设置和游标`() = runBlocking {
        val store = TestServerLocalStore()
        val local = FakeLocal(store)
        val remote = FakeRemote()
        remote.personas += serverPersonaJson("srv-p1", "林深", 100)
        remote.personas += serverPersonaJson("srv-p2", "Alan", 100)
        remote.sessions += ServerSession(
            id = "srv-s1",
            personaId = "srv-p1",
            title = "和srv-p1的对话",
            lastBlockIndex = 2,
            messageCount = 0,
            updatedAt = 100,
            createdAt = 10
        )
        remote.seedBlock("srv-s1", 0, listOf(remoteMessage("s1-0", "srv-s1", createdAt = 100)))
        remote.seedBlock("srv-s1", 1, listOf(remoteMessage("s1-1", "srv-s1", createdAt = 200)))
        remote.seedBlock(
            "srv-s1",
            2,
            listOf(
                remoteMessage("s1-2", "srv-s1", role = "ASSISTANT", createdAt = 300, readAt = 350),
                remoteMessage("s1-3", "srv-s1", createdAt = 400)
            )
        )
        remote.memories["srv-p1"] = mutableListOf(
            buildJsonObject {
                put("id", "srv-m1")
                put("content", "服务端的记忆")
                put("importance", 80)
                put("tags", JsonArray(listOf(JsonPrimitive("咖啡"))))
                put("createdAt", 10)
                put("updatedAt", 20)
            }
        )
        remote.presets += buildJsonObject {
            put("id", "srv-preset-1")
            put("name", "服务端预设")
            put("prompt", "p")
            put("createdAt", 10)
            put("updatedAt", 20)
        }
        remote.settings["themeMode"] = "DARK"
        remote.settings["userAvatarUri"] = "/v1/media/avatar-me"

        val manager = ServerBootstrapManager(local, remote, store)

        val outcome = manager.runIfNeeded("http://server.test")

        assertTrue(outcome.success)
        assertEquals(ServerBootstrapMode.PULL, outcome.mode)
        assertEquals("两个人格都拉回", 2, local.personas.size)
        assertEquals("完整人格字段要恢复", "server prompt", local.personas["srv-p1"]?.prompt)
        assertEquals("会话按服务端 id 落本地", 1, local.sessions.size)
        assertEquals("三块消息全部拉回", 4, local.messages["srv-s1"]?.size)
        assertEquals("从最新块往前翻", 2, remote.pageBeforeCalls)
        assertEquals("已读时间保留", 350L, local.messages["srv-s1"]?.first { it.id == "s1-2" }?.readAt)
        assertEquals("预设拉回", 1, local.presets.size)
        assertEquals("记忆拉回", 1, local.memories.size)
        assertEquals("记忆重要度换算 80→4", 4, local.memories.first().importance)
        assertNotNull("服务端设置写回本地", local.appliedSettings)
        assertEquals("游标推进到快照时刻", 1_000L, local.stateCursor)
        assertEquals("事件游标推进", 7L, local.eventCursor)
        assertTrue(store.bootstrapDone("http://server.test"))
    }

    @Test
    fun `两端都有 合并 本地独有推送 服务端独有拉取 同 id 服务端优先`() = runBlocking {
        val store = TestServerLocalStore()
        val local = FakeLocal(store)
        // 服务端：p1（服务端更新更晚）、p3（本地没有）
        // 本地：p1（旧）、p2（服务端没有）
        local.personas["p1"] = persona("p1", name = "本机旧名字", updatedAt = 100)
        local.personas["p2"] = persona("p2", name = "本机独有", updatedAt = 300)
        local.sessions["s-p1"] = session("s-p1", "p1")
        local.sessions["s-p2"] = session("s-p2", "p2")
        local.addMessage("s-p1", message("local-only", "s-p1", "p1", createdAt = 600))

        val remote = FakeRemote()
        remote.personas += serverPersonaJson("p1", "服务端新名字", 900)
        remote.personas += serverPersonaJson("p3", "服务端独有", 500)
        remote.sessions += ServerSession(
            id = "srv-s-p1",
            personaId = "p1",
            title = "服务端会话",
            lastBlockIndex = 0,
            messageCount = 0,
            updatedAt = 900,
            createdAt = 10
        )
        remote.seedBlock("srv-s-p1", 0, listOf(remoteMessage("server-only", "srv-s-p1", createdAt = 500)))
        remote.settings["themeMode"] = "DARK"
        // 服务端的 activePersonaId 指向服务端人格 id；本地 p2 的映射是 srv-persona-1。
        remote.settings["activePersonaId"] = "srv-persona-1"

        val manager = ServerBootstrapManager(local, remote, store)

        val outcome = manager.runIfNeeded("http://server.test")

        assertTrue(outcome.success)
        assertEquals(ServerBootstrapMode.MERGE, outcome.mode)
        assertEquals("同 id 服务端更新更晚 → 服务端实体写回本地", "服务端新名字", local.personas["p1"]?.name)
        assertEquals("服务端独有人格拉回", "服务端独有", local.personas["p3"]?.name)
        assertEquals("本地独有人格推送并映射", true, store.localPersonaId("srv-persona-1") == "p2")
        assertEquals("服务端消息拉回", 2, local.messages["s-p1"]?.size)
        assertEquals(
            "本地独有消息推送",
            listOf("local-only"),
            remote.importCalls.firstOrNull { it.first == "srv-s-p1" }?.second?.map { it.id }
        )
        assertEquals("服务端设置合并到本地", "DARK", local.appliedSettings?.get("themeMode")?.let { (it as JsonPrimitive).content })
        assertEquals(
            "activePersonaId 要换回本地 id",
            "p2",
            (local.appliedSettings?.get("activePersonaId") as? JsonPrimitive)?.content
        )
    }

    @Test
    fun `同 id 冲突 本地更新更晚 推送本地到服务端 不覆盖本地`() = runBlocking {
        val store = TestServerLocalStore()
        val local = FakeLocal(store)
        local.personas["p1"] = persona("p1", name = "本机更新", updatedAt = 900)
        local.sessions["s1"] = session("s1", "p1")
        local.settings = AppSettings(themeMode = com.yap.app.data.prefs.ThemeMode.DARK)

        val remote = FakeRemote()
        remote.personas += serverPersonaJson("p1", "服务端旧名字", 100)
        // 服务端也有会话，合并时按映射复用同一条。
        remote.sessions += ServerSession(
            id = "srv-s1",
            personaId = "p1",
            title = "服务端会话",
            lastBlockIndex = -1,
            messageCount = 0,
            updatedAt = 100,
            createdAt = 10
        )

        val manager = ServerBootstrapManager(local, remote, store)
        val outcome = manager.runIfNeeded("http://server.test")

        assertTrue(outcome.success)
        assertEquals("本地严格更新 → 推送本地", 1, remote.updatePersonaCalls)
        assertEquals("本地实体不被旧快照覆盖", "本机更新", local.personas["p1"]?.name)
        // 服务端没有设置（空对象）→ 把本地设置推上去。
        assertNotNull(remote.pushedSettings)
    }

    @Test
    fun `本地与服务端都空 只写标记 不发写请求`() = runBlocking {
        val store = TestServerLocalStore()
        val local = FakeLocal(store)
        val remote = FakeRemote()
        val manager = ServerBootstrapManager(local, remote, store)

        val outcome = manager.runIfNeeded("http://server.test")

        assertTrue(outcome.success)
        assertEquals(ServerBootstrapMode.NOOP, outcome.mode)
        assertTrue(remote.createdPersonas.isEmpty())
        assertTrue(remote.importCalls.isEmpty())
        assertTrue(store.bootstrapDone("http://server.test"))
    }

    // ------------------------------------------------------------------ 失败 / 重试 / 标记

    @Test
    fun `导入失败会重试且不重复导入 成功后才写标记`() = runBlocking {
        val store = TestServerLocalStore()
        val local = FakeLocal(store)
        local.personas["p1"] = persona("p1")
        local.sessions["s1"] = session("s1", "p1")
        local.addMessage("s1", message("m1", "s1", "p1", createdAt = 100))
        local.addMessage("s1", message("m2", "s1", "p1", createdAt = 200))

        val remote = FakeRemote()
        remote.failImportsTimes = 1
        val manager = ServerBootstrapManager(
            local,
            remote,
            store,
            retryDelaysMillis = listOf(0L, 0L),
            delayFn = { }
        )

        val outcome = manager.runIfNeeded("http://server.test")

        assertTrue(outcome.success)
        assertEquals("第一次失败后重试", 2, outcome.attempts)
        // 第一次调用抛错，第二次把两条都导入；服务端幂等，消息只有两条。
        val imported = remote.blocks.values.flatMap { it.values.flatten() }
        assertEquals(2, imported.size)
        assertTrue(store.bootstrapDone("http://server.test"))
    }

    @Test
    fun `持续失败不写标记 下次仍会重试`() = runBlocking {
        val store = TestServerLocalStore()
        val local = FakeLocal(store)
        local.personas["p1"] = persona("p1")
        local.sessions["s1"] = session("s1", "p1")
        local.addMessage("s1", message("m1", "s1", "p1"))

        val remote = object : ServerBootstrapRemote by FakeRemote() {
            override suspend fun state(): StateSnapshot = throw ServerApiException("down", code = "io_error")
        }
        val manager = ServerBootstrapManager(
            local,
            remote,
            store,
            retryDelaysMillis = listOf(0L, 0L),
            delayFn = { }
        )

        val first = manager.runIfNeeded("http://server.test")
        assertFalse(first.success)
        assertFalse("失败不能写标记", store.bootstrapDone("http://server.test"))
        assertEquals(ServerBootstrapPhase.FAILED, manager.progress.value.phase)
        assertEquals("io_error", manager.progress.value.error)

        val second = manager.runIfNeeded("http://server.test")
        assertFalse(second.success)
        assertFalse(store.bootstrapDone("http://server.test"))
    }

    @Test
    fun `本地文件附件不导入 只发服务端已存在的媒体引用 异常 id 换成稳定哈希`() {
        val localFile = message(
            id = "m-local-file",
            sessionId = "s1",
            personaId = "p1",
            attachmentsJson = """[{"uri":"file:///data/chat.jpg","mime":"image/jpeg"}]"""
        )
        assertEquals(emptyList<String>(), ServerBootstrapMapping.importAttachments(localFile.attachmentsJson))

        val mixed = message(
            id = "m-mixed",
            sessionId = "s1",
            personaId = "p1",
            attachmentsJson = """[{"uri":"file:///data/chat.jpg"},{"uri":"/v1/media/chat-2"}]"""
        )
        assertEquals(listOf("/v1/media/chat-2"), ServerBootstrapMapping.importAttachments(mixed.attachmentsJson))

        // 正常 UUID 原样保留；异常 id 变成 [A-Za-z0-9_-]{1,128} 内的确定性哈希，保证重试幂等。
        val uuid = "3f0c1a9e-2b8d-4c7a-9e1f-0a1b2c3d4e5f"
        assertEquals(uuid, ServerBootstrapMapping.importMessageId(uuid))
        val weird = "消息 id/带空格:1"
        val safe = ServerBootstrapMapping.importMessageId(weird)
        assertTrue(safe.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        assertEquals(safe, ServerBootstrapMapping.importMessageId(weird))
    }

    @Test
    fun `本地模式的我的头像 在首次推送时迁到 USER_AVATAR 并换成服务端引用`() = runBlocking {
        val store = TestServerLocalStore()
        val local = FakeLocal(store)
        local.personas["p1"] = persona("p1")
        local.sessions["s1"] = session("s1", "p1")
        local.settings = AppSettings(
            themeMode = com.yap.app.data.prefs.ThemeMode.DARK,
            userAvatarUri = "file:///data/avatars/me.jpg"
        )
        val remote = FakeRemote()
        val uploaded = mutableListOf<String>()
        val manager = ServerBootstrapManager(
            local = local,
            remote = remote,
            store = store,
            uploadUserAvatar = { uri ->
                uploaded += uri.orEmpty()
                "/v1/media/my-avatar"
            }
        )

        val outcome = manager.runIfNeeded("http://server.test")

        assertTrue(outcome.success)
        assertEquals(listOf("file:///data/avatars/me.jpg"), uploaded)
        assertEquals("/v1/media/my-avatar" to "file:///data/avatars/me.jpg", local.appliedAvatar)
        assertEquals(
            "/v1/media/my-avatar",
            (remote.pushedSettings?.get("userAvatarUri") as? JsonPrimitive)?.content
        )
        // 本机缓存副本保留，供离线显示。
        assertEquals("file:///data/avatars/me.jpg", local.settings.userAvatarLocalUri)
    }

    @Test
    fun `附件不存在导致整批 400 时 去掉附件重发 不阻断文字历史`() = runBlocking {
        val store = TestServerLocalStore()
        val local = FakeLocal(store)
        local.personas["p1"] = persona("p1")
        local.sessions["s1"] = session("s1", "p1")
        local.addMessage(
            "s1",
            message(
                id = "m1",
                sessionId = "s1",
                personaId = "p1",
                attachmentsJson = """[{"uri":"/v1/media/stale-image"}]"""
            )
        )

        val base = FakeRemote()
        val calls = mutableListOf<List<ServerMessageImport>>()
        val remote = object : ServerBootstrapRemote by base {
            override suspend fun importMessages(
                sessionId: String,
                messages: List<ServerMessageImport>
            ): Int {
                calls += messages
                if (messages.any { it.attachments.isNotEmpty() }) {
                    throw ServerApiException("unknown media", code = "unknown_media", status = 400)
                }
                return base.importMessages(sessionId, messages)
            }
        }
        val manager = ServerBootstrapManager(local, remote, store)

        val outcome = manager.runIfNeeded("http://server.test")

        assertTrue(outcome.success)
        assertEquals("先带附件失败 再不带附件重发", 2, calls.size)
        assertTrue(calls.first().single().attachments.isNotEmpty())
        assertTrue(calls.last().single().attachments.isEmpty())
        assertEquals("文字历史仍然导入", 1, base.blocks.values.flatMap { it.values.flatten() }.size)
        assertTrue(store.bootstrapDone("http://server.test"))
    }

    @Test
    fun `判定纯函数 四象限`() {
        assertEquals(
            ServerBootstrapMode.NOOP,
            ServerBootstrapDecision.decide(ServerBootstrapCounts())
        )
        assertEquals(
            ServerBootstrapMode.PUSH,
            ServerBootstrapDecision.decide(ServerBootstrapCounts(localPersonas = 1))
        )
        assertEquals(
            ServerBootstrapMode.PULL,
            ServerBootstrapDecision.decide(ServerBootstrapCounts(serverPersonas = 1))
        )
        assertEquals(
            ServerBootstrapMode.MERGE,
            ServerBootstrapDecision.decide(
                ServerBootstrapCounts(localPersonas = 1, serverPersonas = 1)
            )
        )
    }

    @Test
    fun `bootstrap 标记按 baseUrl 分键 换地址会重新执行`() = runBlocking {
        val store = TestServerLocalStore()
        val local = FakeLocal(store)
        local.personas["p1"] = persona("p1")
        local.sessions["s1"] = session("s1", "p1")
        val remote = FakeRemote()
        val manager = ServerBootstrapManager(local, remote, store)

        manager.runIfNeeded("http://a.test")
        assertTrue(store.bootstrapDone("http://a.test"))
        assertFalse(store.bootstrapDone("http://b.test"))

        val second = manager.runIfNeeded("http://b.test")
        assertTrue(second.success)
        assertFalse(second.skipped)
        assertTrue(store.bootstrapDone("http://b.test"))
    }
}
