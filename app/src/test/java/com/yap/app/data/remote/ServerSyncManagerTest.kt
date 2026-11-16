package com.yap.app.data.remote

import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.PersonaEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端状态同步（M4）的纯 JVM 单测。
 *
 * 覆盖四类最容易在真机上才暴露的问题：
 * 1. 首次 `since=0` 之后按会话补齐最新块；
 * 2. 游标前进（且只进不退）；
 * 3. 服务端 `updatedAt` 更新才覆盖本地，本地未发送草稿不受影响；
 * 4. 失败静默重试（指数退避、最多 3 次）。
 */
class ServerSyncManagerTest {

    private class FakeSource(
        private val states: MutableList<Result<StateSnapshot>>,
        private val pages: Map<String, ServerMessagePage> = emptyMap()
    ) : ServerStateSource {
        val sinceValues = mutableListOf<Long>()
        val pageRequests = mutableListOf<String>()

        override suspend fun state(since: Long): StateSnapshot {
            sinceValues += since
            val next = states.removeFirstOrNull() ?: error("没有更多 state 响应")
            return next.getOrThrow()
        }

        override suspend fun latestPage(sessionId: String): ServerMessagePage {
            pageRequests += sessionId
            return pages[sessionId] ?: ServerMessagePage(sessionId = sessionId)
        }
    }

    private class FakeSink(
        private val snapshotResult: ServerSyncApplyResult = ServerSyncApplyResult(personas = 1, sessions = 1, messages = 2),
        private val pageResult: ServerSyncApplyResult = ServerSyncApplyResult(messages = 3),
        private val failSnapshot: Boolean = false
    ) : ServerStateSink {
        var snapshots = 0
        var pages = 0
        override suspend fun applySnapshot(snapshot: StateSnapshot): ServerSyncApplyResult {
            snapshots++
            if (failSnapshot) error("db is down")
            return snapshotResult
        }

        override suspend fun applyPage(page: ServerMessagePage, personaId: String?): ServerSyncApplyResult {
            pages++
            return pageResult
        }
    }

    private class FakeLocalStore(
        initialCursor: Long = 0L,
        initialCursorForState: Long = initialCursor
    ) : ServerLocalStore {
        var currentCursor = initialCursor
        var currentStateCursor = initialCursorForState
        val pending = mutableListOf<PendingServerMessage>()
        override suspend fun cursor(): Long = currentCursor
        override suspend fun setCursor(cursor: Long) {
            if (cursor > currentCursor) currentCursor = cursor
        }

        override suspend fun stateCursor(): Long = currentStateCursor
        override suspend fun setStateCursor(cursor: Long) {
            if (cursor > currentStateCursor) currentStateCursor = cursor
        }

        override suspend fun pending(): List<PendingServerMessage> = pending.toList()
        override suspend fun enqueuePending(message: PendingServerMessage): Boolean {
            if (pending.any { it.clientMessageId == message.clientMessageId }) return false
            pending += message
            return true
        }

        override suspend fun removePending(clientMessageId: String) {
            pending.removeAll { it.clientMessageId == clientMessageId }
        }

        override suspend fun markPendingAttempt(clientMessageId: String) {
            pending.replaceAll {
                if (it.clientMessageId == clientMessageId) it.copy(attempts = it.attempts + 1) else it
            }
        }
    }

    private fun session(
        id: String = "s1",
        personaId: String = "p1",
        lastBlockIndex: Int = 2,
        updatedAt: Long = 100,
        unread: Int = 0
    ) = ServerSession(
        id = id,
        personaId = personaId,
        title = "和${personaId}的对话",
        lastBlockIndex = lastBlockIndex,
        unread = unread,
        updatedAt = updatedAt,
        createdAt = 10
    )

    // ------------------------------------------------------------------ 首次同步

    @Test
    fun `首次同步先落快照 再按会话补齐最新块 并前进游标`() = runBlocking {
        val local = FakeLocalStore()
        val source = FakeSource(
            mutableListOf(
                Result.success(
                    StateSnapshot(
                        sessions = listOf(session("s1", lastBlockIndex = 2), session("s2", lastBlockIndex = 0)),
                        serverCursor = 42
                    )
                )
            ),
            pages = mapOf("s1" to ServerMessagePage("s1", blockIndex = 2), "s2" to ServerMessagePage("s2", blockIndex = 0))
        )
        val sink = FakeSink()
        val delays = mutableListOf<Long>()
        val manager = ServerSyncManager(source, sink, local, delayFn = { delays += it })

        val outcome = manager.syncNow()

        assertTrue(outcome.success)
        assertEquals(1, outcome.attempts)
        assertEquals("首次 must 用 since=0", listOf(0L), source.sinceValues)
        assertEquals("每个有消息的会话补一块", listOf("s1", "s2"), source.pageRequests)
        assertEquals(2, sink.pages)
        assertEquals("事件游标来自 serverCursor", 42L, local.currentCursor)
        assertEquals("状态游标是快照里的时间戳（不是事件游标）", 100L, local.currentStateCursor)
        assertEquals(100L, outcome.cursor)
        assertEquals(42L, outcome.eventCursor)
        assertEquals("快照 + 两页的计数要累加", 1, outcome.applied.personas)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `第二次同步用上次游标 且不再补拉分页`() = runBlocking {
        val local = FakeLocalStore(initialCursor = 42)
        val source = FakeSource(
            mutableListOf(Result.success(StateSnapshot(sessions = listOf(session()), serverCursor = 55)))
        )
        val sink = FakeSink()
        val manager = ServerSyncManager(source, sink, local)

        manager.syncNow()

        assertEquals(listOf(42L), source.sinceValues)
        assertTrue("增量同步不重复拉块（老消息交给聊天页前翻）", source.pageRequests.isEmpty())
        assertEquals(55L, local.currentCursor)
        assertEquals("状态游标取快照时间戳与旧游标的较大者", 100L, local.currentStateCursor)
    }

    @Test
    fun `会话没有消息时不补分页`() = runBlocking {
        val local = FakeLocalStore()
        val source = FakeSource(
            mutableListOf(
                Result.success(StateSnapshot(sessions = listOf(session(lastBlockIndex = -1)), serverCursor = 7))
            )
        )
        val sink = FakeSink()
        ServerSyncManager(source, sink, local).syncNow()

        assertTrue(source.pageRequests.isEmpty())
        assertEquals(7L, local.currentCursor)
        assertEquals(100L, local.currentStateCursor)
    }

    // ------------------------------------------------------------------ 游标

    @Test
    fun `服务端游标更旧时客户端游标不后退`() = runBlocking {
        val local = FakeLocalStore(initialCursor = 100)
        val source = FakeSource(mutableListOf(Result.success(StateSnapshot(serverCursor = 50))))
        val manager = ServerSyncManager(source, FakeSink(), local)

        val outcome = manager.syncNow()

        assertEquals("事件游标只进不退：回退会重复处理事件", 100L, local.currentCursor)
        assertEquals("时间戳游标也不会被服务端慢时钟拨回去", 100L, local.currentStateCursor)
        assertEquals(100L, outcome.cursor)
        assertEquals(listOf(100L), source.sinceValues)
    }

    // ------------------------------------------------------------------ 重试

    @Test
    fun `失败按指数退避重试 最多三次`() = runBlocking {
        val local = FakeLocalStore()
        val source = FakeSource(
            mutableListOf(
                Result.failure(ServerApiException("down", code = "io_error")),
                Result.failure(ServerApiException("down again", code = "io_error")),
                Result.success(StateSnapshot(serverTime = 900, serverCursor = 9))
            )
        )
        val delays = mutableListOf<Long>()
        val manager = ServerSyncManager(source, FakeSink(), local, delayFn = { delays += it })

        val outcome = manager.syncNow()

        assertTrue(outcome.success)
        assertEquals(3, outcome.attempts)
        assertEquals("0.5s、1s 的指数退避", listOf(500L, 1_000L), delays)
        assertEquals(9L, local.currentCursor)
        assertEquals(900L, local.currentStateCursor)
    }

    @Test
    fun `三次都失败时静默返回 failure 游标不变`() = runBlocking {
        val local = FakeLocalStore(initialCursor = 20, initialCursorForState = 20)
        val source = FakeSource(
            mutableListOf(
                Result.failure(ServerApiException("1", code = "io_error")),
                Result.failure(ServerApiException("2", code = "io_error")),
                Result.failure(ServerApiException("3", code = "io_error"))
            )
        )
        val delays = mutableListOf<Long>()
        val manager = ServerSyncManager(source, FakeSink(), local, delayFn = { delays += it })

        val outcome = manager.syncNow()

        assertFalse(outcome.success)
        assertEquals(3, outcome.attempts)
        assertEquals(20L, local.currentCursor)
        assertEquals(20L, local.currentStateCursor)
        assertTrue(outcome.error is ServerApiException)
        assertEquals("第三次失败不再等（交给下一次前台同步）", 2, delays.size)
    }

    @Test
    fun `落库失败也算同步失败 不会前进游标`() = runBlocking {
        val local = FakeLocalStore()
        val source = FakeSource(
            mutableListOf(
                Result.success(StateSnapshot(serverCursor = 33)),
                Result.success(StateSnapshot(serverCursor = 33)),
                Result.success(StateSnapshot(serverCursor = 33))
            )
        )
        val manager = ServerSyncManager(source, FakeSink(failSnapshot = true), local)

        val outcome = manager.syncNow()

        assertFalse(outcome.success)
        assertEquals(0L, local.currentCursor)
        assertEquals(3, outcome.attempts)
    }

    // ------------------------------------------------------------------ 合并规则

    @Test
    fun `服务端 updatedAt 更新时覆盖本地展示字段 但不抹掉本地扩展字段`() {
        val local = PersonaEntity(
            id = "p1",
            name = "旧名字",
            description = "本地描述",
            avatarUri = "file:///avatar.jpg",
            avatarSeed = 7,
            prompt = "本地提示词",
            category = "日常聊天",
            version = 3,
            defaultApiConfigId = "cfg",
            modelOverride = "m",
            isDefault = true,
            createdAt = 1,
            updatedAt = 10,
            worldJson = "{\"worldName\":\"本地世界\"}"
        )
        val newer = ServerPersona(id = "p1", name = "新名字", updatedAt = 20)
        val merged = ServerMergePlan.persona(local, newer)

        assertEquals("新名字", merged.name)
        assertEquals("本地描述", merged.description)
        assertEquals("本地提示词", merged.prompt)
        assertEquals("{\"worldName\":\"本地世界\"}", merged.worldJson)
        assertEquals("file:///avatar.jpg", merged.avatarUri)
        assertEquals(20L, merged.updatedAt)
    }

    @Test
    fun `本地比服务端新时保留本地`() {
        val local = PersonaEntity(
            id = "p1",
            name = "本地",
            description = "",
            avatarUri = null,
            avatarSeed = 0,
            prompt = "",
            category = "日常聊天",
            version = 1,
            defaultApiConfigId = null,
            modelOverride = null,
            isDefault = false,
            createdAt = 1,
            updatedAt = 100
        )
        val merged = ServerMergePlan.persona(local, ServerPersona(id = "p1", name = "服务端", updatedAt = 99))
        assertSame("updatedAt 不更新就不重写", local, merged)
    }

    @Test
    fun `会话合并保留本地绑定与置顶 覆盖服务端拥有的列`() {
        val local = ChatSessionEntity(
            id = "s1",
            personaId = "p1",
            apiConfigId = "cfg",
            model = "deepseek",
            title = "旧标题",
            lastMessagePreview = "旧预览",
            lastMessageAt = 5,
            unreadCount = 9,
            pinned = true,
            createdAt = 1,
            updatedAt = 10,
            lastPersonaVersion = 3,
            lastMemoryExtractedCount = 6
        )
        val merged = ServerMergePlan.session(
            local = local,
            server = ServerSession(
                id = "s1",
                personaId = "p1",
                title = "新标题",
                lastMessageAt = 50,
                unread = 2,
                updatedAt = 20
            ),
            preview = "新预览"
        )
        assertEquals("新标题", merged.title)
        assertEquals("新预览", merged.lastMessagePreview)
        assertEquals(50L, merged.lastMessageAt)
        assertEquals(2, merged.unreadCount)
        assertEquals("本地 API 绑定不能被快照清掉", "cfg", merged.apiConfigId)
        assertEquals("deepseek", merged.model)
        assertTrue(merged.pinned)
        assertEquals(6, merged.lastMemoryExtractedCount)
    }

    @Test
    fun `消息合并按 id 幂等 未知角色不冒充用户`() {
        val server = ServerMessage(
            id = "m1",
            sessionId = "s1",
            blockIndex = 2,
            role = "ROBOT",
            content = "你好",
            createdAt = 10,
            attachments = listOf("media-1")
        )
        val first = ServerMergePlan.message(server, "p1", local = null)
        val second = ServerMergePlan.message(server, "p1", local = first)
        assertEquals("同一 id 重复同步得到同一行（REPLACE 幂等）", first, second)
        assertEquals(MessageRole.SYSTEM, first.role)
        assertEquals(2, first.blockIndex)
        assertTrue("附件 id 不能悄悄丢：${first.attachmentsJson}", first.attachmentsJson!!.contains("media-1"))
    }

    @Test
    fun `消息按服务端角色映射`() {
        assertEquals(
            MessageRole.USER,
            ServerMergePlan.message(ServerMessage(role = "user"), "p1", null).role
        )
        assertEquals(
            MessageRole.ASSISTANT,
            ServerMergePlan.message(ServerMessage(role = "ASSISTANT"), "p1", null).role
        )
        assertEquals(
            MessageRole.SYSTEM,
            ServerMergePlan.message(ServerMessage(role = "system"), "p1", null).role
        )
    }

    @Test
    fun `预览是单行且按同一上限截断`() {
        val long = "第一行\n第二行" + "字".repeat(ServerChatSync.PREVIEW_MAX_CHARS)
        val preview = ServerMergePlan.previewOf(long)
        assertFalse(preview.contains('\n'))
        assertEquals(ServerChatSync.PREVIEW_MAX_CHARS + 1, preview.length)
        assertTrue(preview.endsWith("…"))
    }
}
