package com.yap.agentd

import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.LlmReplyGenerator
import com.yap.agentd.llm.llmAccess
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.safety.SafetyGuard
import com.yap.agentd.store.EventType
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.15.1「危机优先于沉默」：用户的话命中危机词表时，`stay_silent` / `defer_reply` 不再是答案。
 *
 * 钉住七件事：
 * 1. 危机 + `stay_silent`（无正文）→ 安全提示作为**唯一** assistant 消息落库，`message.created`
 *    + 非 silent 的 `done`（带真实 id）；
 * 2. 危机 + `stay_silent`（有正文）→ 用模型已经说出的正文，缺安全锚点时末尾追加；
 * 3. 危机 + `defer_reply` → 不写 defer 念头、不发工具卡片；正文/提示照常落库；
 * 4. 危机轮里模型想沉默也不会「只已读」：生成器拦掉工具并立刻收尾（不再问下一轮）；
 * 5. 正常（非危机）`stay_silent` / `defer_reply` 行为完全不变；
 * 6. 多条 `send_message` 已发满 4 条时，安全提示并进**最后一条**、不造第 5 个气泡；
 * 7. 即使换掉生成器（直接返回 `suppressed`），`ChatService` 仍然兜底（安全底线不依赖生成器实现）。
 */
class CrisisPriorityTest {

    private lateinit var root: Path
    private lateinit var store: FileStore

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-crisis-priority")
        store = FileStore.open(root)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun chatService(server: FakeLlmServer): ChatService {
        store.pushLlmConfig(server.baseUrl)
        return ChatService(store, LlmReplyGenerator(store, llmAccess(store)))
    }

    private fun send(
        service: ChatService,
        content: String,
        clientMessageId: String = "crisis-1"
    ): RecordingSink {
        val sink = RecordingSink()
        service.send(
            sessionId,
            SendMessageRequest(clientMessageId = clientMessageId, content = content),
            sink
        )
        return sink
    }

    private fun assistantMessages(): List<Message> =
        store.blockMessages(sessionId, 0).filter { it.role == MessageRole.ASSISTANT }

    private fun staySilentCall(id: String = "s1"): String =
        FakeLlmServer.toolCall(0, id = id, name = "stay_silent", arguments = """{"reason":"不想说"}""")

    private fun deferCall(id: String = "d1"): String =
        FakeLlmServer.toolCall(0, id = id, name = "defer_reply", arguments = """{"minutes":30,"reason":"等会儿"}""")

    // ------------------------------------------------------------------ 危机 + stay_silent

    @Test
    fun `crisis overrides stay_silent and persists the safety note as the only message`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(staySilentCall(), FakeLlmServer.DONE))
        }.use { server ->
            val sink = send(chatService(server), "我真的不想活了")

            val assistants = assistantMessages()
            assertEquals("危机轮必须有一条可见消息", 1, assistants.size)
            assertEquals(
                "一个字的正文都没有 → 安全提示就是唯一正文",
                SafetyGuard.EMOTIONAL_NOTE,
                assistants.single().content
            )
            assertNull("危机轮的消息仍是未读，不能只标已读", assistants.single().readAt)
            assertNull("沉默工具的卡片不能落库", assistants.single().toolTraceJson)

            // SSE：accepted → message.created → done（没有 delta、没有 tool）。
            assertEquals(
                listOf("accepted", "message.created", "done"),
                sink.types()
            )
            val done = sink.firstOf<SseEvent.Done>()
            assertFalse("done 不能标 silent", done.silent)
            assertEquals("done 必须带落库的消息 id", assistants.single().id, done.assistantMessageId)

            // 工具真的没有被执行：危机轮不向模型要下一轮（和客户端一样，多等一轮没有意义）。
            assertEquals("危机轮只问一次模型", 1, server.requests.size)

            // 事件表里也有这条 assistant 的 message.created（离线同步能拿到）。
            val created = store.eventsSince(0)
                .filter { it.type == EventType.MESSAGE_CREATED }
                .filter { it.payload.obj("message").str("role") == MessageRole.ASSISTANT }
            assertEquals(1, created.size)
            assertEquals(assistants.single().id, created.single().payload.obj("message").str("id"))
            assertTrue(store.pendingIntents(personaId).isEmpty())
        }
    }

    @Test
    fun `crisis with streamed body keeps the body and appends a missing safety anchor`() {
        FakeLlmServer { exchange ->
            // 同一个响应里先说了半句、又调了 `stay_silent`：正文照用，沉默被拦下。
            exchange.sse(
                listOf(
                    FakeLlmServer.chunk("别想太多，早点睡。"),
                    staySilentCall(),
                    FakeLlmServer.DONE
                )
            )
        }.use { server ->
            val sink = send(chatService(server), "我想自杀")

            val assistant = assistantMessages().single()
            assertTrue("有正文用正文", assistant.content.startsWith("别想太多，早点睡。"))
            assertTrue("缺安全锚点要补上：我在", assistant.content.contains("我在"))
            assertTrue("缺安全锚点要补上：120", assistant.content.contains("120"))
            assertTrue("在线客户端也要立刻看到追加内容", sink.text().contains("120"))

            val done = sink.firstOf<SseEvent.Done>()
            assertFalse(done.silent)
            assertEquals(assistant.id, done.assistantMessageId)
            assertTrue("危机轮必须有 message.created", sink.events.any { it is SseEvent.MessageCreated })
            assertEquals("沉默工具不能出现在事件流里", 0, sink.events.count { it is SseEvent.Tool })
            assertNull(assistant.toolTraceJson)
        }
    }

    @Test
    fun `medical crisis uses the emergency note instead of the emotional one`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(staySilentCall(), FakeLlmServer.DONE))
        }.use { server ->
            send(chatService(server), "我吃了很多安眠药，现在有点晕")

            val assistant = assistantMessages().single()
            assertEquals(SafetyGuard.MEDICAL_NOTE, assistant.content)
            assertTrue("医疗危机优先叫急救/急诊", assistant.content.contains("急诊"))
        }
    }

    // ------------------------------------------------------------------ 危机 + defer_reply

    @Test
    fun `crisis overrides defer reply without writing an intent`() {
        FakeLlmServer { exchange ->
            exchange.sse(
                listOf(
                    FakeLlmServer.chunk("你怎么了？"),
                    deferCall(),
                    FakeLlmServer.DONE
                )
            )
        }.use { server ->
            val sink = send(chatService(server), "感觉消失比较好")

            assertTrue("不能写 defer 念头", store.pendingIntents(personaId).isEmpty())
            val assistant = assistantMessages().single()
            assertTrue("有正文用正文", assistant.content.startsWith("你怎么了？"))
            assertTrue("缺安全锚点要补上", assistant.content.contains("120"))
            assertEquals("defer 的工具卡片不能出现", 0, sink.events.count { it is SseEvent.Tool })
            assertFalse(sink.firstOf<SseEvent.Done>().silent)
            assertEquals("危机轮只问一次模型", 1, server.requests.size)
        }
    }

    @Test
    fun `crisis with a defer reply and no body still lands one visible safety message`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(deferCall(), FakeLlmServer.DONE))
        }.use { server ->
            val sink = send(chatService(server), "我不想活了")

            assertEquals(1, assistantMessages().size)
            assertEquals(SafetyGuard.EMOTIONAL_NOTE, assistantMessages().single().content)
            assertTrue("defer 不能写念头", store.pendingIntents(personaId).isEmpty())
            assertEquals(0, sink.events.count { it is SseEvent.Tool })
            assertFalse(sink.firstOf<SseEvent.Done>().silent)
        }
    }

    // ------------------------------------------------------------------ 正常行为不变

    @Test
    fun `an ordinary stay silent still leaves no assistant message`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(staySilentCall(), FakeLlmServer.DONE))
        }.use { server ->
            val sink = send(chatService(server), "在吗")

            assertEquals("普通聊天保持『只已读』", 0, assistantMessages().size)
            val done = sink.firstOf<SseEvent.Done>()
            assertTrue(done.silent)
            assertEquals("", done.assistantMessageId)
            assertTrue("普通 stay_silent 仍然会显示工具卡片", sink.events.any { it is SseEvent.Tool })
            assertTrue(sink.events.none { it is SseEvent.MessageCreated })
            assertEquals("只问一次模型（stay_silent 立即结束）", 1, server.requests.size)
        }
    }

    @Test
    fun `an ordinary defer reply still writes the intent and keeps its tool card`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            if (calls.getAndIncrement() == 0) {
                exchange.sse(
                    listOf(
                        FakeLlmServer.chunk("等会儿再说。"),
                        deferCall(),
                        FakeLlmServer.DONE
                    )
                )
            } else {
                exchange.sse(listOf(FakeLlmServer.chunk("好。"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val sink = send(chatService(server), "在忙吗")

            assertEquals("普通聊天照常写念头", 1, store.pendingIntents(personaId).size)
            assertTrue("普通 defer 仍然显示工具卡片", sink.events.any { it is SseEvent.Tool })
            assertEquals("等会儿再说。好。", assistantMessages().single().content)
            assertFalse(sink.firstOf<SseEvent.Done>().silent)
        }
    }

    // ------------------------------------------------------------------ 危机 + 正常回复

    @Test
    fun `a crisis reply without an anchor gets the note appended once`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(FakeLlmServer.chunk("别想太多，早点睡。"), FakeLlmServer.DONE))
        }.use { server ->
            val sink = send(chatService(server), "活不下去了")

            val assistant = assistantMessages().single()
            assertTrue(assistant.content.startsWith("别想太多，早点睡。"))
            assertEquals(
                "安全提示只补一次",
                1,
                Regex(Regex.escape(SafetyGuard.EMOTIONAL_NOTE)).findAll(assistant.content).count()
            )
            assertTrue(sink.text().contains("120"))
            assertEquals(assistant.id, sink.firstOf<SseEvent.Done>().assistantMessageId)
        }
    }

    @Test
    fun `a crisis reply that already has an anchor is left untouched`() {
        FakeLlmServer { exchange ->
            exchange.sse(listOf(FakeLlmServer.chunk("我在，你身边有人吗？"), FakeLlmServer.DONE))
        }.use { server ->
            val sink = send(chatService(server), "我想自杀")

            assertEquals("已有锚点不重复追加", "我在，你身边有人吗？", assistantMessages().single().content)
            assertEquals("我在，你身边有人吗？", sink.text())
            assertFalse(sink.firstOf<SseEvent.Done>().silent)
        }
    }

    // ------------------------------------------------------------------ 连发已满 4 条

    @Test
    fun `the safety note is merged into the last of four send message bubbles`() {
        val calls = AtomicInteger()
        FakeLlmServer { exchange ->
            if (calls.getAndIncrement() == 0) {
                exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(0, id = "m1", name = "send_message", arguments = """{"text":"一"}"""),
                        FakeLlmServer.toolCall(1, id = "m2", name = "send_message", arguments = """{"text":"二"}"""),
                        FakeLlmServer.toolCall(2, id = "m3", name = "send_message", arguments = """{"text":"三"}"""),
                        FakeLlmServer.toolCall(3, id = "m4", name = "send_message", arguments = """{"text":"四"}"""),
                        FakeLlmServer.DONE
                    )
                )
            } else {
                exchange.sse(listOf(FakeLlmServer.chunk("好。"), FakeLlmServer.DONE))
            }
        }.use { server ->
            val sink = send(chatService(server), "我不想活了")

            val assistants = assistantMessages()
            assertEquals("不能造第 5 个气泡", 4, assistants.size)
            assertEquals(listOf("一", "二", "三"), assistants.take(3).map { it.content })
            assertTrue("提示并进最后一条", assistants.last().content.startsWith("四"))
            assertTrue(assistants.last().content.contains("120"))
            assertEquals(4, sink.events.count { it is SseEvent.MessageCreated })
            assertEquals(assistants.last().id, sink.firstOf<SseEvent.Done>().assistantMessageId)
        }
    }

    // ------------------------------------------------------------------ 生成器被换掉时的兜底

    @Test
    fun `a suppressed generator is overridden for crisis turns`() {
        val generator = ReplyGenerator { _, emit ->
            emit(SseEvent.Delta("我本来想说话。"))
            ReplyOutcome(
                usage = Usage(3, 4),
                suppressed = true,
                toolTraceJson = """[{"name":"stay_silent","title":"没说话","detail":"","ok":true}]""",
                toolsUsed = listOf("stay_silent")
            )
        }
        val service = ChatService(store, generator)
        val sink = RecordingSink()
        service.send(
            sessionId,
            SendMessageRequest(clientMessageId = "fake-suppressed", content = "我不想活了"),
            sink
        )

        val assistant = assistantMessages().single()
        assertTrue("已经流出的正文照用", assistant.content.startsWith("我本来想说话。"))
        assertTrue("缺锚点要补上", assistant.content.contains("120"))
        assertNull("沉默工具卡片不能落库", assistant.toolTraceJson)
        val done = sink.firstOf<SseEvent.Done>()
        assertFalse(done.silent)
        assertEquals(assistant.id, done.assistantMessageId)
        assertTrue(sink.events.any { it is SseEvent.MessageCreated })
        assertEquals(4, assistant.completionTokens)
    }

    @Test
    fun `a suppressed generator without any body lands the safety note`() {
        val generator = ReplyGenerator { _, _ -> ReplyOutcome(Usage(1, 1), suppressed = true) }
        val sink = RecordingSink()
        ChatService(store, generator).send(
            sessionId,
            SendMessageRequest(clientMessageId = "fake-suppressed-empty", content = "我想自杀"),
            sink
        )

        assertEquals(SafetyGuard.EMOTIONAL_NOTE, assistantMessages().single().content)
        assertFalse(sink.firstOf<SseEvent.Done>().silent)
        assertEquals(assistantMessages().single().id, sink.firstOf<SseEvent.Done>().assistantMessageId)
    }

    @Test
    fun `a crisis turn with only an attachment gets the safety note as its text`() {
        // 只发了一张贴纸/附件、没有正文：危机轮不能让「一张表情」成为唯一的回答。
        val generator = ReplyGenerator { _, _ ->
            ReplyOutcome(Usage(1, 0), attachments = listOf("/v1/media/m-1"))
        }
        val sink = RecordingSink()
        ChatService(store, generator).send(
            sessionId,
            SendMessageRequest(clientMessageId = "crisis-attachment-only", content = "我不想活了"),
            sink
        )

        val assistant = assistantMessages().single()
        assertEquals(SafetyGuard.EMOTIONAL_NOTE, assistant.content)
        assertEquals(listOf("/v1/media/m-1"), assistant.attachments)
        assertTrue(sink.events.any { it is SseEvent.MessageCreated })
        assertFalse(sink.firstOf<SseEvent.Done>().silent)
    }

    @Test
    fun `a crisis turn that produced no text at all still lands the safety note`() {
        // 工具轮用完了、模型一个字都没说（没有任何「想沉默」的工具）：普通聊天会以 silent 收尾，
        // 危机轮必须改成一条可见的安全提示。
        val generator = ReplyGenerator { _, _ -> ReplyOutcome(Usage(2, 0)) }
        val sink = RecordingSink()
        ChatService(store, generator).send(
            sessionId,
            SendMessageRequest(clientMessageId = "crisis-empty-body", content = "感觉消失比较好"),
            sink
        )

        assertEquals(SafetyGuard.EMOTIONAL_NOTE, assistantMessages().single().content)
        assertFalse(sink.firstOf<SseEvent.Done>().silent)
        assertEquals(assistantMessages().single().id, sink.firstOf<SseEvent.Done>().assistantMessageId)
        assertTrue(sink.events.any { it is SseEvent.MessageCreated })
    }
}
