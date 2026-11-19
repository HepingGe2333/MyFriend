package com.yap.app.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 服务端聊天的纯 JVM 单测（M3）：SSE 事件序列、落库时机、离线队列、幂等重发。
 *
 * 假实现只模拟「编排层需要的那部分契约」：传输细节（分片/取消/超时）在
 * `ServerApiTest` / `ServerSseTest` 里已经钉住。
 */
class ServerChatGatewayTest {

    private class FakeLocalStore : ServerLocalStore {
        var currentCursor = 0L
        val pending = mutableListOf<PendingServerMessage>()
        val serverToLocal = mutableMapOf<String, String>()
        val localToServer = mutableMapOf<String, String>()
        override suspend fun cursor(): Long = currentCursor
        override suspend fun setCursor(cursor: Long) { currentCursor = cursor }
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

        override suspend fun localSessionId(serverSessionId: String): String? = serverToLocal[serverSessionId]
        override suspend fun serverSessionId(localSessionId: String): String? = localToServer[localSessionId]
        override suspend fun setSessionMapping(serverSessionId: String, localSessionId: String) {
            serverToLocal[serverSessionId] = localSessionId
            localToServer[localSessionId] = serverSessionId
        }
    }

    private class FakeSink : ServerChatSink {
        var pages = 0
        var appliedPage: ServerMessagePage? = null
        val usages = mutableListOf<ServerUsage>()
        /** 0.14.1：SSE 里每条 message.created 都应当**收到即落库**（不等 done）。 */
        val enqueued = mutableListOf<ServerMessage>()

        override suspend fun applyPage(page: ServerMessagePage): ServerSyncApplyResult {
            pages++
            appliedPage = page
            return ServerSyncApplyResult(messages = page.messages.size)
        }

        override suspend fun recordUsage(sessionId: String, usage: ServerUsage, at: Long) {
            usages += usage
        }

        override fun enqueueMessage(message: ServerMessage) {
            enqueued += message
        }
    }

    private data class SendCall(
        val sessionId: String,
        val clientMessageId: String,
        val content: String,
        val attachments: List<String>,
        val replyTo: String?
    )

    private class FakeRemote(
        var page: ServerMessagePage = ServerMessagePage(
            sessionId = "s1",
            blockIndex = 1,
            messages = listOf(ServerMessage(id = "m1", sessionId = "s1", blockIndex = 1, role = "USER"))
        )
    ) : ServerRemote {
        val sends = mutableListOf<SendCall>()
        val pageRequests = mutableListOf<Pair<Int?, Int?>>()
        val pageSessionIds = mutableListOf<String>()
        var onSend: suspend (SendCall, (ServerChatEvent) -> Unit) -> Unit = { _, _ -> }

        override suspend fun sendMessage(
            sessionId: String,
            clientMessageId: String,
            content: String,
            attachments: List<String>,
            replyTo: String?,
            onEvent: (ServerChatEvent) -> Unit
        ) {
            val call = SendCall(sessionId, clientMessageId, content, attachments, replyTo)
            sends += call
            onSend(call, onEvent)
        }

        override suspend fun messages(
            sessionId: String,
            beforeBlock: Int?,
            afterBlock: Int?
        ): ServerMessagePage {
            pageSessionIds += sessionId
            pageRequests += beforeBlock to afterBlock
            return page
        }

        override suspend fun events(since: Long, timeoutSeconds: Long?): ServerEvents =
            ServerEvents(cursor = since)

        override suspend fun createSession(personaId: String, title: String?): ServerSessionCreated =
            ServerSessionCreated(created = true, session = ServerSession(id = "s-new", personaId = personaId))

        override suspend fun markRead(sessionId: String, upTo: Long?): ServerReadReceipt =
            ServerReadReceipt(sessionId = sessionId, unread = 0)
    }

    private fun provider(remote: FakeRemote?) = object : ServerRemoteProvider {
        override fun remote(): ServerRemote? = remote
    }

    private fun gateway(
        remote: FakeRemote?,
        store: FakeLocalStore = FakeLocalStore(),
        sink: FakeSink = FakeSink()
    ) = Triple(
        ServerChatGateway(
            remoteProvider = provider(remote),
            localStore = store,
            sink = sink,
            newClientMessageId = { "client-1" },
            now = { 1_000L }
        ),
        store,
        sink
    )

    // ------------------------------------------------------------------ SSE 编排

    @Test
    fun `message_created 收到即落库 多条按到达顺序`() = runBlocking {
        val remote = FakeRemote()
        remote.onSend = { _, emit ->
            emit(ServerChatEvent.Accepted("u1", 100))
            emit(
                ServerChatEvent.MessageCreated(
                    ServerMessage(id = "a1", sessionId = "s1", blockIndex = 1, role = "ASSISTANT", content = "第一条")
                )
            )
            emit(
                ServerChatEvent.MessageCreated(
                    ServerMessage(id = "a2", sessionId = "s1", blockIndex = 1, role = "ASSISTANT", content = "第二条")
                )
            )
            emit(ServerChatEvent.Done("a2", ServerUsage()))
        }
        val (gateway, _, sink) = gateway(remote)

        gateway.send("s1", "在吗")

        // 不等 done 之后拉整页：两条都按顺序交给落库口。
        assertEquals(listOf("a1", "a2"), sink.enqueued.map { it.id })
    }

    @Test
    fun `typing 事件转给界面 不参与落库`() = runBlocking {
        val remote = FakeRemote()
        remote.onSend = { _, emit ->
            emit(ServerChatEvent.Accepted("u1", 100))
            emit(ServerChatEvent.TypingStarted("s1", "p1"))
            emit(ServerChatEvent.TypingStopped("s1", "p1"))
            emit(ServerChatEvent.Done("a1", ServerUsage()))
        }
        val (gateway, _, sink) = gateway(remote)
        val events = mutableListOf<ServerSendEvent>()

        gateway.send("s1", "在吗", onEvent = { events += it })

        assertEquals(
            listOf(
                ServerSendEvent.TypingStarted("s1", "p1"),
                ServerSendEvent.TypingStopped("s1", "p1")
            ),
            events.filterIsInstance<ServerSendEvent.TypingStarted>() +
                events.filterIsInstance<ServerSendEvent.TypingStopped>()
        )
        assertTrue(sink.enqueued.isEmpty())
    }

    @Test
    fun `SSE 事件按序转给界面 accepted 后出队 done 记用量`() = runBlocking {
        val remote = FakeRemote()
        remote.onSend = { _, emit ->
            emit(ServerChatEvent.Accepted("u1", 100))
            emit(ServerChatEvent.Delta("你"))
            emit(ServerChatEvent.Delta("好"))
            emit(ServerChatEvent.Tool("web_search", "done", "查了 2 个网页"))
            emit(ServerChatEvent.Done("a1", ServerUsage(promptTokens = 12, completionTokens = 34)))
        }
        val (gateway, store, sink) = gateway(remote)
        val events = mutableListOf<ServerSendEvent>()

        val result = gateway.send("s1", "在吗", onEvent = { events += it })

        assertTrue(result.accepted)
        assertTrue(result.done)
        assertNull(result.error)
        assertEquals("client-1", result.clientMessageId)
        assertEquals(
            listOf(
                "accepted:u1",
                "delta:你",
                "delta:好",
                "tool:web_search:done",
                "done:a1"
            ),
            events.map {
                when (it) {
                    is ServerSendEvent.Accepted -> "accepted:${it.userMessageId}"
                    is ServerSendEvent.Delta -> "delta:${it.text}"
                    is ServerSendEvent.MessageCreated -> "message:${it.message.id}"
                    is ServerSendEvent.TypingStarted -> "typing-started:${it.sessionId}"
                    is ServerSendEvent.TypingStopped -> "typing-stopped:${it.sessionId}"
                    is ServerSendEvent.Tool -> "tool:${it.name}:${it.status}"
                    is ServerSendEvent.Done -> "done:${it.assistantMessageId}"
                    is ServerSendEvent.Error -> "error:${it.code}"
                }
            }
        )
        assertTrue("accepted 之后待发队列必须清空", store.pending.isEmpty())
        assertEquals("流结束后拉一次当前块落库", 1, sink.pages)
        assertEquals(ServerUsage(promptTokens = 12, completionTokens = 34), sink.usages.single())
        assertEquals(listOf(null to null), remote.pageRequests)
    }

    @Test
    fun `先入队再发 幂等键就是入队 id`() = runBlocking {
        val remote = FakeRemote()
        val store = FakeLocalStore()
        var seenPending = -1
        remote.onSend = { call, emit ->
            seenPending = store.pending.size
            assertEquals(call.clientMessageId, store.pending.single().clientMessageId)
            emit(ServerChatEvent.Accepted("u1", 1))
            emit(ServerChatEvent.Done("a1", ServerUsage()))
        }
        val g = ServerChatGateway(provider(remote), store, FakeSink(), newClientMessageId = { "client-1" })

        g.send("s1", "你好")

        assertEquals(1, seenPending)
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun `连接没通时保留待发队列并返回未接受`() = runBlocking {
        val remote = FakeRemote()
        remote.onSend = { _, _ -> throw IOException("no network") }
        val (gateway, store, sink) = gateway(remote)

        val result = gateway.send("s1", "在吗")

        assertFalse(result.accepted)
        assertFalse(result.done)
        assertNotNull(result.error)
        assertEquals("失败的消息必须留在队列里等恢复补发", 1, store.pending.size)
        assertEquals(1, store.pending.single().attempts)
        assertEquals("没 accepted 就不该拉分页", 0, sink.pages)
    }

    @Test
    fun `accepted 之后流断开 不退草稿也不重新排队`() = runBlocking {
        val remote = FakeRemote()
        remote.onSend = { _, emit ->
            emit(ServerChatEvent.Accepted("u1", 1))
            throw IOException("stream reset")
        }
        val (gateway, store, sink) = gateway(remote)

        val result = gateway.send("s1", "在吗")

        assertTrue("用户消息已在服务端，UI 不能退草稿", result.accepted)
        assertFalse(result.done)
        assertNotNull(result.error)
        assertTrue("不能重发：服务端已经落过这条", store.pending.isEmpty())
        assertEquals("仍要拉一次分页把用户消息补齐", 1, sink.pages)
    }

    @Test
    fun `error 事件映射成失败但用户消息已落服务端`() = runBlocking {
        val remote = FakeRemote()
        remote.onSend = { _, emit ->
            emit(ServerChatEvent.Accepted("u1", 1))
            emit(ServerChatEvent.Error("model_error", "模型暂时不可用"))
        }
        val (gateway, store) = gateway(remote)
        val events = mutableListOf<ServerSendEvent>()

        val result = gateway.send("s1", "在吗", onEvent = { events += it })

        assertTrue(result.accepted)
        assertFalse(result.done)
        assertTrue(events.any { it is ServerSendEvent.Error && it.code == "model_error" })
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun `没有服务端配置时直接失败且不入队`() = runBlocking {
        val g = ServerChatGateway(provider(null), FakeLocalStore(), FakeSink())
        val result = g.send("s1", "在吗")
        assertFalse(result.accepted)
        assertEquals("server_unavailable", (result.error as ServerApiException).code)
    }

    // ------------------------------------------------------------------ 离线队列补发

    @Test
    fun `补发按入队顺序 用原幂等键 成功即出队`() = runBlocking {
        val remote = FakeRemote()
        remote.onSend = { _, emit ->
            emit(ServerChatEvent.Accepted("u", 1))
            emit(ServerChatEvent.Done("a", ServerUsage()))
        }
        val store = FakeLocalStore()
        store.enqueuePending(PendingServerMessage("c1", "s1", "第一条", createdAt = 10))
        store.enqueuePending(PendingServerMessage("c2", "s1", "第二条", createdAt = 20))
        val g = ServerChatGateway(provider(remote), store, FakeSink())

        val sent = g.resendPending()

        assertEquals(2, sent)
        assertEquals("顺序不能乱（后发的先到会让上下文错位）", listOf("c1", "c2"), remote.sends.map { it.clientMessageId })
        assertEquals(listOf("第一条", "第二条"), remote.sends.map { it.content })
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun `补发第一条就失败时停下 不插队`() = runBlocking {
        val remote = FakeRemote()
        remote.onSend = { _, _ -> throw IOException("offline") }
        val store = FakeLocalStore()
        store.enqueuePending(PendingServerMessage("c1", "s1", "第一条", createdAt = 10))
        store.enqueuePending(PendingServerMessage("c2", "s1", "第二条", createdAt = 20))
        val g = ServerChatGateway(provider(remote), store, FakeSink())

        val sent = g.resendPending()

        assertEquals(0, sent)
        assertEquals(1, remote.sends.size)
        assertEquals(listOf("c1", "c2"), store.pending.map { it.clientMessageId })
        assertEquals(1, store.pending.first().attempts)
    }

    @Test
    fun `补发遇到 401 也保留队列（用户的话不该被自动丢掉）`() = runBlocking {
        val remote = FakeRemote()
        remote.onSend = { _, _ -> throw ServerApiException("unauthorized", code = "unauthorized", status = 401) }
        val store = FakeLocalStore()
        store.enqueuePending(PendingServerMessage("c1", "s1", "你好", createdAt = 1))
        val g = ServerChatGateway(provider(remote), store, FakeSink())

        g.resendPending()

        assertEquals(1, store.pending.size)
    }

    @Test
    fun `本地会话 id 与服务端不同时 发送和分页都用服务端 id`() = runBlocking {
        val remote = FakeRemote()
        var sentTo = ""
        remote.onSend = { call, emit ->
            sentTo = call.sessionId
            emit(ServerChatEvent.Accepted("u", 1))
            emit(ServerChatEvent.Done("a", ServerUsage()))
        }
        val store = FakeLocalStore()
        store.setSessionMapping(serverSessionId = "server-1", localSessionId = "local-1")
        val g = ServerChatGateway(provider(remote), store, FakeSink())

        g.send("local-1", "在吗")
        g.openSession("local-1").getOrThrow()

        assertEquals("server-1", sentTo)
        assertEquals("发送后拉分页 + 打开会话都用服务端 id", listOf("server-1", "server-1"), remote.pageSessionIds)
    }

    // ------------------------------------------------------------------ 分页 / 建会话

    @Test
    fun `打开会话拉最新一块 前翻后翻传对参数`() = runBlocking {
        val remote = FakeRemote()
        val sink = FakeSink()
        val g = ServerChatGateway(provider(remote), FakeLocalStore(), sink)

        g.openSession("s1").getOrThrow()
        g.loadPage("s1", beforeBlock = 3).getOrThrow()
        g.loadPage("s1", afterBlock = 5).getOrThrow()

        assertEquals(listOf(null to null, 3 to null, null to 5), remote.pageRequests)
        assertEquals(3, sink.pages)
    }

    @Test
    fun `建会话把服务端返回透传`() = runBlocking {
        val remote = FakeRemote()
        val g = ServerChatGateway(provider(remote), FakeLocalStore(), FakeSink())
        val created = g.createSession("p1", "标题").getOrThrow()
        assertTrue(created.created)
        assertEquals("s-new", created.session.id)
        assertEquals("p1", created.session.personaId)
    }
}
