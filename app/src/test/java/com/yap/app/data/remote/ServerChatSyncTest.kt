package com.yap.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ServerChatSync] 的映射单测：快照 → 首页、快照/分页 → 聊天页。
 *
 * 重点钉住三件在 P1b 会直接变成 UI 行为的事：
 * 1. 会话排序与消息预览（列表看起来对不对）；
 * 2. `hasMoreOlder` / `hasMoreNewer`（「加载更早」按钮什么时候出现）；
 * 3. `hasMoreMessages`（首次同步被服务端截断时，不能假装已经是全部历史）。
 */
class ServerChatSyncTest {

    // ------------------------------------------------------------------ 首页

    @Test
    fun `home 按最后消息时间倒序并带预览`() {
        val snapshot = StateSnapshot(
            serverTime = 1000,
            sessions = listOf(
                session(id = "s-old", lastBlockIndex = 0, lastMessageAt = 10, messageCount = 1),
                session(id = "s-new", lastBlockIndex = 2, lastMessageAt = 30, messageCount = 5, unread = 3),
                session(id = "s-empty", lastBlockIndex = -1, lastMessageAt = null, messageCount = 0)
            ),
            messages = listOf(
                message(id = "m1", sessionId = "s-old", blockIndex = 0, content = "旧的", createdAt = 10),
                message(id = "m2", sessionId = "s-new", blockIndex = 2, content = "最新的", createdAt = 30),
                message(id = "m3", sessionId = "s-new", blockIndex = 1, content = "早一点", createdAt = 20)
            ),
            mood = ServerMood(personaId = "p1", label = "平静", intensity = 2, updatedAt = 9),
            presence = ServerPresence(personaId = "p1", kind = "IDLE", detail = "发呆", updatedAt = 8),
            serverCursor = 77,
            hasMoreMessages = true
        )

        val home = ServerChatSync.home(snapshot)

        assertEquals(
            "有消息的会话按 lastMessageAt 倒序，没消息的排最后",
            listOf("s-new", "s-old", "s-empty"),
            home.sessions.map { it.id }
        )
        assertEquals(3, home.sessions[0].unread)
        assertEquals("最新的", home.sessions[0].preview)
        assertEquals("旧的", home.sessions[1].preview)
        assertNull("没有消息就没有预览，而不是空字符串", home.sessions[2].preview)
        assertEquals(77, home.serverCursor)
        assertEquals(1000, home.serverTime)
        assertTrue("截断标记要传下去，列表才知道要不要提示「还有更早」", home.hasMoreMessages)
        assertEquals("平静", home.mood?.label)
        assertEquals("IDLE", home.presence?.kind)
    }

    @Test
    fun `home 的预览是单行且截断到上限`() {
        val long = "第一行\n第二行" + "字".repeat(ServerChatSync.PREVIEW_MAX_CHARS)
        val snapshot = StateSnapshot(
            sessions = listOf(session(id = "s1", lastBlockIndex = 0, lastMessageAt = 10, messageCount = 1)),
            messages = listOf(message(id = "m1", sessionId = "s1", blockIndex = 0, content = long, createdAt = 10))
        )

        val preview = ServerChatSync.home(snapshot).sessions.single().preview.orEmpty()

        assertFalse("预览不能带换行", preview.contains('\n'))
        assertEquals(ServerChatSync.PREVIEW_MAX_CHARS + 1, preview.length)
        assertTrue("截断要有省略号", preview.endsWith("…"))
    }

    @Test
    fun `home 预览取块编号与时间最大的那条而不是数组最后一条`() {
        val snapshot = StateSnapshot(
            sessions = listOf(session(id = "s1", lastBlockIndex = 2, lastMessageAt = 30, messageCount = 2)),
            messages = listOf(
                // 服务端快照的消息是按 createdAt 全局排的，但契约没保证；映射器要自己取最新一条。
                message(id = "m-new", sessionId = "s1", blockIndex = 2, content = "最新", createdAt = 30),
                message(id = "m-old", sessionId = "s1", blockIndex = 1, content = "更早", createdAt = 5)
            )
        )

        assertEquals("最新", ServerChatSync.home(snapshot).sessions.single().preview)
    }

    // ------------------------------------------------------------------ 聊天页（快照）

    @Test
    fun `chat 从快照映射当前块并标出还能往前翻`() {
        val snapshot = StateSnapshot(
            sessions = listOf(
                session(id = "s1", lastBlockIndex = 3, unread = 2, lastMessageAt = 30, messageCount = 9)
            ),
            messages = listOf(
                message(id = "m3", sessionId = "s1", blockIndex = 3, content = "第三块", createdAt = 30),
                message(id = "m1", sessionId = "s1", blockIndex = 3, content = "你好", createdAt = 10, role = "user"),
                message(id = "m2", sessionId = "s1", blockIndex = 3, content = "在的", createdAt = 20,
                    role = "ASSISTANT", attachments = listOf("media-1"), readAt = 25)
            ),
            hasMoreMessages = true
        )

        val chat = ServerChatSync.chat(snapshot, "s1")!!

        assertEquals("s1", chat.sessionId)
        assertEquals("p1", chat.personaId)
        assertEquals(3, chat.blockIndex)
        assertEquals("块内按时间升序", listOf("m1", "m2", "m3"), chat.messages.map { it.id })
        assertEquals(ServerMessageRole.USER, chat.messages[0].role)
        assertEquals(ServerMessageRole.ASSISTANT, chat.messages[1].role)
        assertEquals(listOf("media-1"), chat.messages[1].attachments)
        assertEquals(25L, chat.messages[1].readAt)
        assertTrue("块编号 3 > 0，说明还有更早的块", chat.hasMoreOlder)
        assertFalse("快照给的就是最新一块", chat.hasMoreNewer)
        assertTrue(chat.hasMoreMessages)
        assertEquals(2, chat.unread)
        assertEquals(30L, chat.lastMessageAt)
    }

    @Test
    fun `chat 在只有第一块且没截断时两个方向都没有更多`() {
        val snapshot = StateSnapshot(
            sessions = listOf(session(id = "s1", lastBlockIndex = 0, messageCount = 1)),
            messages = listOf(message(id = "m1", sessionId = "s1", blockIndex = 0, content = "只有一条", createdAt = 1)),
            hasMoreMessages = false
        )

        val chat = ServerChatSync.chat(snapshot, "s1")!!

        assertEquals(0, chat.blockIndex)
        assertFalse(chat.hasMoreOlder)
        assertFalse(chat.hasMoreNewer)
        assertFalse(chat.hasMoreMessages)
    }

    @Test
    fun `chat 在服务端截断过消息时保守标成还有更早`() {
        val snapshot = StateSnapshot(
            sessions = listOf(session(id = "s1", lastBlockIndex = 0, messageCount = 500)),
            messages = listOf(message(id = "m1", sessionId = "s1", blockIndex = 0, content = "被截断后的头一条", createdAt = 1)),
            hasMoreMessages = true
        )

        val chat = ServerChatSync.chat(snapshot, "s1")!!

        assertTrue("hasMoreMessages=true 时不能假装历史已经全了", chat.hasMoreOlder)
        assertTrue(chat.hasMoreMessages)
    }

    @Test
    fun `chat 对不存在的会话返回 null`() {
        val snapshot = StateSnapshot(
            sessions = listOf(session(id = "s1", lastBlockIndex = 0, messageCount = 1)),
            messages = listOf(message(id = "m1", sessionId = "s1", blockIndex = 0, content = "x", createdAt = 1))
        )

        assertNull("会话被删了就返回 null，而不是造一个空聊天页", ServerChatSync.chat(snapshot, "missing"))
    }

    @Test
    fun `chat 对还没有消息的会话给出空块与 null 块号`() {
        val snapshot = StateSnapshot(
            sessions = listOf(session(id = "s1", lastBlockIndex = -1, messageCount = 0))
        )

        val chat = ServerChatSync.chat(snapshot, "s1")!!

        assertNull(chat.blockIndex)
        assertTrue(chat.messages.isEmpty())
        assertFalse(chat.hasMoreOlder)
        assertFalse(chat.hasMoreNewer)
    }

    // ------------------------------------------------------------------ 聊天页（分页）

    @Test
    fun `chat 从分页结果直接采用服务端的两个方向标记`() {
        val page = ServerMessagePage(
            sessionId = "s1",
            blockIndex = 2,
            hasMoreOlder = true,
            hasMoreNewer = true,
            messages = listOf(
                message(id = "m2", sessionId = "s1", blockIndex = 2, content = "二", createdAt = 2),
                message(id = "m1", sessionId = "s1", blockIndex = 2, content = "一", createdAt = 1)
            )
        )
        val session = session(id = "s1", lastBlockIndex = 3, title = "今天", unread = 4, lastMessageAt = 9, messageCount = 6)

        val chat = ServerChatSync.chat(page, session)

        assertEquals(listOf("m1", "m2"), chat.messages.map { it.id })
        assertEquals(2, chat.blockIndex)
        assertTrue(chat.hasMoreOlder)
        assertTrue(chat.hasMoreNewer)
        assertEquals("今天", chat.title)
        assertEquals(4, chat.unread)
        assertEquals(9L, chat.lastMessageAt)
    }

    @Test
    fun `chat 翻过头时保留 sessionId 和空消息`() {
        val page = ServerMessagePage(
            sessionId = "s1",
            blockIndex = null,
            hasMoreOlder = false,
            hasMoreNewer = true,
            messages = emptyList()
        )

        val chat = ServerChatSync.chat(page)

        assertEquals("s1", chat.sessionId)
        assertNull(chat.blockIndex)
        assertTrue(chat.messages.isEmpty())
        assertFalse(chat.hasMoreOlder)
        assertTrue(chat.hasMoreNewer)
        assertEquals("", chat.title)
    }

    @Test
    fun `角色归一化 未知角色不假装是用户`() {
        assertEquals(ServerMessageRole.USER, ServerMessageRole.from("USER"))
        assertEquals(ServerMessageRole.USER, ServerMessageRole.from(" user "))
        assertEquals(ServerMessageRole.ASSISTANT, ServerMessageRole.from("ASSISTANT"))
        assertEquals(ServerMessageRole.SYSTEM, ServerMessageRole.from("SYSTEM"))
        assertEquals(ServerMessageRole.UNKNOWN, ServerMessageRole.from("TOOL"))
        assertEquals(ServerMessageRole.UNKNOWN, ServerMessageRole.from(""))
    }

    // ------------------------------------------------------------------ 构图

    private fun session(
        id: String,
        lastBlockIndex: Int,
        unread: Int = 0,
        lastMessageAt: Long? = null,
        messageCount: Long = 0,
        title: String = "会话 $id",
        personaId: String = "p1",
        updatedAt: Long = lastMessageAt ?: 0
    ): ServerSession = ServerSession(
        id = id,
        personaId = personaId,
        title = title,
        lastMessageAt = lastMessageAt,
        unread = unread,
        lastBlockIndex = lastBlockIndex,
        messageCount = messageCount,
        updatedAt = updatedAt,
        createdAt = 0
    )

    private fun message(
        id: String,
        sessionId: String,
        blockIndex: Int,
        content: String,
        createdAt: Long,
        role: String = "USER",
        attachments: List<String> = emptyList(),
        readAt: Long? = null,
        source: String = "CHAT"
    ): ServerMessage = ServerMessage(
        id = id,
        sessionId = sessionId,
        blockIndex = blockIndex,
        role = role,
        content = content,
        createdAt = createdAt,
        readAt = readAt,
        attachments = attachments,
        source = source
    )
}
