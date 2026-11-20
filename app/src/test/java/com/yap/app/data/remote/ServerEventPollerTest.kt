package com.yap.app.data.remote

import com.yap.app.domain.PresenceKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 前台事件长轮询（M4）的纯 JVM 单测：
 *
 * 1. 新 assistant 消息 → 落库回调 + 注入的通知器被调用（不碰 Android 通知系统）；
 * 2. mood / presence 事件被解码并交给 sink；
 * 3. 空轮询不前移游标；失败不前移；一条坏事件不阻断整批。
 */
class ServerEventPollerTest {

    private class FakeLocalStore(var currentCursor: Long = 10) : ServerLocalStore {
        override suspend fun cursor(): Long = currentCursor
        override suspend fun setCursor(cursor: Long) {
            if (cursor > currentCursor) currentCursor = cursor
        }

        override suspend fun pending(): List<PendingServerMessage> = emptyList()
        override suspend fun enqueuePending(message: PendingServerMessage): Boolean = false
        override suspend fun removePending(clientMessageId: String) = Unit
        override suspend fun markPendingAttempt(clientMessageId: String) = Unit
    }

    private class FakeRemote(var result: () -> ServerEvents) : ServerRemote {
        val sinceValues = mutableListOf<Long>()
        override suspend fun sendMessage(
            sessionId: String,
            clientMessageId: String,
            content: String,
            attachments: List<String>,
            replyTo: String?,
            onEvent: (ServerChatEvent) -> Unit
        ) = Unit

        override suspend fun messages(sessionId: String, beforeBlock: Int?, afterBlock: Int?): ServerMessagePage =
            ServerMessagePage(sessionId = sessionId)

        override suspend fun events(since: Long, timeoutSeconds: Long?): ServerEvents {
            sinceValues += since
            return result()
        }

        override suspend fun createSession(personaId: String, title: String?): ServerSessionCreated =
            ServerSessionCreated(session = ServerSession(id = "s", personaId = personaId))

        override suspend fun markRead(sessionId: String, upTo: Long?): ServerReadReceipt =
            ServerReadReceipt(sessionId = sessionId, unread = 0)
    }

    private class FakeSink : ServerEventSink {
        val changes = mutableListOf<ServerEventChange>()
        override suspend fun apply(change: ServerEventChange): ServerEventApplied {
            changes += change
            return when (change) {
                is ServerEventChange.MessageCreated -> ServerEventApplied(
                    notifySessionId = change.message.sessionId,
                    notifyPersonaId = "p1",
                    notifyText = change.message.content
                )

                else -> ServerEventApplied()
            }
        }
    }

    private class FakeNotifier : ServerEventNotifier {
        val calls = mutableListOf<Triple<String, String, String>>()
        override suspend fun notifyMessage(sessionId: String, personaId: String, text: String) {
            calls += Triple(sessionId, personaId, text)
        }
    }

    private class FakeTyping : ServerTypingSink {
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        override fun typingStarted(sessionId: String) {
            started += sessionId
        }

        override fun typingStopped(sessionId: String) {
            stopped += sessionId
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun event(
        cursor: Long,
        type: String,
        payload: String = "{}",
        sessionId: String? = "s1",
        personaId: String? = "p1"
    ) = ServerEvent(
        cursor = cursor,
        type = type,
        at = cursor,
        sessionId = sessionId,
        personaId = personaId,
        payload = json.parseToJsonElement(payload).jsonObject
    )

    private fun messageEvent(cursor: Long, role: String = "ASSISTANT", content: String = "在的") =
        event(
            cursor = cursor,
            type = "message.created",
            payload = """
                {"message":{"id":"m$cursor","sessionId":"s1","blockIndex":2,"role":"$role",
                           "content":"$content","createdAt":$cursor,"source":"PROACTIVE"}}
            """.trimIndent()
        )

    private fun provider(remote: FakeRemote?) = object : ServerRemoteProvider {
        override fun remote(): ServerRemote? = remote
    }

    private fun poller(
        remote: FakeRemote?,
        store: ServerLocalStore = FakeLocalStore(),
        sink: FakeSink = FakeSink(),
        notifier: FakeNotifier = FakeNotifier(),
        typing: ServerTypingSink = ServerTypingSink.None
    ) = Triple(ServerEventPoller(provider(remote), store, sink, notifier, typing), sink, notifier)

    // ------------------------------------------------------------------ 正常事件

    @Test
    fun `新主动消息落库并触发通知 游标前进`() = runBlocking {
        val remote = FakeRemote { ServerEvents(cursor = 12, events = listOf(messageEvent(11), messageEvent(12))) }
        val store = FakeLocalStore(currentCursor = 10)
        val (poller, sink, notifier) = poller(remote, store)

        val outcome = poller.pollOnce()

        assertTrue(outcome.success)
        assertEquals(2, outcome.events)
        assertEquals(2, outcome.notified)
        assertEquals(12L, outcome.cursor)
        assertEquals(12L, store.currentCursor)
        assertEquals(listOf(10L), remote.sinceValues)
        assertTrue(sink.changes.all { it is ServerEventChange.MessageCreated })
        assertEquals(
            listOf("s1" to "在的", "s1" to "在的"),
            notifier.calls.map { it.first to it.third }
        )
    }

    @Test
    fun `mood 与 presence 事件解码并交给 sink`() = runBlocking {
        val remote = FakeRemote {
            ServerEvents(
                cursor = 21,
                events = listOf(
                    event(
                        20,
                        "mood.changed",
                        """{"mood":{"personaId":"p1","label":"平静","intensity":3,"updatedAt":20}}"""
                    ),
                    event(
                        21,
                        "presence.changed",
                        """{"presence":{"personaId":"p1","kind":"BROWSING","detail":"在翻东西","updatedAt":21}}"""
                    )
                )
            )
        }
        val (poller, sink, notifier) = poller(remote)

        val outcome = poller.pollOnce()

        assertEquals(2, outcome.events)
        assertEquals(0, outcome.notified)
        assertTrue(sink.changes[0] is ServerEventChange.MoodChanged)
        assertEquals("平静", (sink.changes[0] as ServerEventChange.MoodChanged).mood.label)
        assertTrue(sink.changes[1] is ServerEventChange.PresenceChanged)
        assertEquals("BROWSING", (sink.changes[1] as ServerEventChange.PresenceChanged).presence.kind)
        assertTrue(notifier.calls.isEmpty())
    }

    @Test
    fun `未知事件类型不阻断整批`() = runBlocking {
        val remote = FakeRemote {
            ServerEvents(
                cursor = 3,
                events = listOf(
                    event(2, "heartbeat", """{"who":"p1"}"""),
                    messageEvent(3)
                )
            )
        }
        val (poller, sink, notifier) = poller(remote)

        val outcome = poller.pollOnce()

        assertEquals(2, outcome.events)
        assertTrue(sink.changes[0] is ServerEventChange.Unknown)
        assertEquals("heartbeat", (sink.changes[0] as ServerEventChange.Unknown).type)
        assertEquals(1, notifier.calls.size)
    }

    @Test
    fun `坏 payload 解码成 Unknown 不会抛异常`() {
        val change = ServerEventDecoder.decode(event(1, "message.created", """{"message":42}"""))
        assertTrue(change is ServerEventChange.Unknown)
        assertEquals("message.created", (change as ServerEventChange.Unknown).type)
    }

    // ------------------------------------------------------------------ 游标 / 失败

    @Test
    fun `空轮询不前移游标 也不写通知`() = runBlocking {
        val remote = FakeRemote { ServerEvents(cursor = 10, events = emptyList()) }
        val store = FakeLocalStore(currentCursor = 10)
        val (poller, sink, notifier) = poller(remote, store)

        val outcome = poller.pollOnce()

        assertTrue(outcome.success)
        assertEquals(0, outcome.events)
        assertEquals(10L, store.currentCursor)
        assertTrue(sink.changes.isEmpty())
        assertTrue(notifier.calls.isEmpty())
    }

    @Test
    fun `请求失败时保持旧游标`() = runBlocking {
        val remote = FakeRemote { throw IOException("offline") }
        val store = FakeLocalStore(currentCursor = 77)
        val (poller, _, notifier) = poller(remote, store)

        val outcome = poller.pollOnce()

        assertFalse(outcome.success)
        assertNotNull(outcome.error)
        assertEquals(77L, store.currentCursor)
        assertEquals(0, outcome.notified)
        assertTrue(notifier.calls.isEmpty())
    }

    @Test
    fun `服务端返回的游标比事件还大时取最大`() = runBlocking {
        val remote = FakeRemote { ServerEvents(cursor = 100, events = listOf(messageEvent(5))) }
        val store = FakeLocalStore(currentCursor = 1)
        val (poller, _, _) = poller(remote, store)

        val outcome = poller.pollOnce()

        assertEquals(100L, outcome.cursor)
        assertEquals(100L, store.currentCursor)
    }

    // ------------------------------------------------------------------ typing（§8）

    @Test
    fun `typing 事件只更新输入指示 不落库不通知 游标照常前进`() = runBlocking {
        val remote = FakeRemote {
            ServerEvents(
                cursor = 12,
                events = listOf(
                    event(11, "typing.started"),
                    messageEvent(12)
                )
            )
        }
        val store = FakeLocalStore(currentCursor = 10)
        val typing = FakeTyping()
        val (poller, sink, notifier) = poller(remote, store, typing = typing)

        val outcome = poller.pollOnce()

        assertTrue(outcome.success)
        assertEquals(2, outcome.events)
        assertEquals(listOf("s1"), typing.started)
        assertTrue(typing.stopped.isEmpty())
        // typing 不进 Room：sink 只收到那一条真正的新消息。
        assertEquals(1, sink.changes.size)
        assertTrue(sink.changes.single() is ServerEventChange.MessageCreated)
        assertEquals(1, notifier.calls.size)
        assertEquals(12L, store.currentCursor)
    }

    @Test
    fun `typing 事件先做 server 到 local 的会话映射`() = runBlocking {
        val remote = FakeRemote {
            ServerEvents(cursor = 2, events = listOf(event(1, "typing.started"), event(2, "typing.stopped")))
        }
        val store = object : ServerLocalStore by FakeLocalStore() {
            override suspend fun localSessionId(serverSessionId: String): String? = "local-$serverSessionId"
        }
        val typing = FakeTyping()
        val (poller, _, _) = poller(remote, store, typing = typing)

        poller.pollOnce()

        assertEquals(listOf("local-s1"), typing.started)
        assertEquals(listOf("local-s1"), typing.stopped)
    }

    @Test
    fun `typing 事件缺 sessionId 时解码成 Unknown`() {
        val change = ServerEventDecoder.decode(event(1, "typing.started", sessionId = null, personaId = null))
        assertTrue(change is ServerEventChange.Unknown)
        assertEquals("typing.started", (change as ServerEventChange.Unknown).type)
    }

    @Test
    fun `typing 事件顶层缺字段时从 payload 兜底`() {
        val change = ServerEventDecoder.decode(
            event(
                1,
                "typing.stopped",
                """{"sessionId":"s1","personaId":"p1"}""",
                sessionId = null,
                personaId = null
            )
        )
        assertEquals(ServerEventChange.TypingStopped("s1", "p1"), change)
    }

    // ------------------------------------------------------------------ presence 映射

    @Test
    fun `presence 快照映射到本地展示模型`() {
        val presence = ServerPresenceSnapshot(
            personaId = "p1",
            kind = "browsing",
            detail = "在翻东西",
            updatedAt = 123
        ).toPresence()
        assertEquals(PresenceKind.BROWSING, presence.kind)
        assertEquals("在翻东西", presence.detail)
        assertEquals(123L, presence.sinceAt)

        val unknown = ServerPresenceSnapshot(personaId = "p1", kind = "WARPING").toPresence()
        assertEquals("认不出的状态退回发呆，不崩", PresenceKind.IDLE, unknown.kind)
    }
}
