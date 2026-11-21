package com.yap.agentd.llm

import com.yap.agentd.ChatService
import com.yap.agentd.RecordingSink
import com.yap.agentd.SendMessageRequest
import com.yap.agentd.SseEvent
import com.yap.agentd.TestSupport
import com.yap.agentd.obj
import com.yap.agentd.str
import com.yap.agentd.store.EventType
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path

/**
 * 0.15 `stay_silent` 严格语义：
 *
 * 模型可能**先说了半句再调用** `stay_silent`（同一轮里文字 + tool_call 同时出现）。
 * 这一轮必须仍然按「什么都没说」处理：不落任何 assistant 正文、只标已读。
 *
 * 为什么不能「以已经说出口的话为准」：库里一旦有正文，`done.silent=true` 就和它对不上，
 * 客户端同步/幂等重放会把这段文字重新捡起来；「只看到已读」这条产品语义必须由**落库层**保证。
 */
class StaySilentStrictTest {

    private lateinit var root: Path
    private lateinit var store: FileStore

    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-stay-silent-strict")
        store = FileStore.open(root)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    @Test
    fun `text streamed before stay_silent is never persisted`() {
        FakeLlmServer { exchange ->
            exchange.sse(
                listOf(
                    FakeLlmServer.chunk("我本来想说点什么。"),
                    FakeLlmServer.toolCall(0, id = "c1", name = "stay_silent", arguments = """{"reason":"现在不想说"}"""),
                    FakeLlmServer.DONE
                )
            )
        }.use { server ->
            store.pushLlmConfig(server.baseUrl)
            val service = ChatService(store, LlmReplyGenerator(store, llmAccess(store)))

            // 先造一条未读的 assistant 消息：stay_silent 之后应该被标成已读。
            store.appendMessage(sessionId, MessageRole.ASSISTANT, "之前的一条")
            assertEquals(1, store.session(sessionId)!!.unread)

            val sink = RecordingSink()
            service.send(
                sessionId,
                SendMessageRequest(clientMessageId = "strict-silent", content = "在吗"),
                sink
            )

            val messages = store.blockMessages(sessionId, 0)
            assertEquals(
                "这一轮只留下用户消息，流出的半句也不能落库",
                listOf(MessageRole.ASSISTANT, MessageRole.USER),
                messages.map { it.role }
            )
            assertTrue(
                "没有任何 assistant 正文",
                messages.none { it.role == MessageRole.ASSISTANT && it.content.contains("我本来想") }
            )

            val done = sink.firstOf<SseEvent.Done>()
            assertTrue("done 必须标 silent", done.silent)
            assertEquals("没有 assistant 消息 id", "", done.assistantMessageId)
            assertEquals("会话标已读", 0, store.session(sessionId)!!.unread)
            assertTrue(
                "之前那条 assistant 也要标已读",
                messages.first().readAt != null
            )
            // 事件流里不能出现这一轮的 assistant message.created。
            val created = store.eventsSince(0)
                .filter { it.type == EventType.MESSAGE_CREATED }
                .filter { it.payload.obj("message").str("role") == MessageRole.ASSISTANT }
            assertEquals("只有之前那条，没有本轮正文", 1, created.size)
        }
    }
}
