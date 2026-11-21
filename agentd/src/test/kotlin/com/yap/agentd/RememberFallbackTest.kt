package com.yap.agentd

import com.yap.agentd.memory.RememberFallback
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Memory
import com.yap.agentd.store.MessageRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path

/**
 * 0.15 remember 可靠性兜底：
 *
 * 1. 用户说「过敏/忌口/生日/纪念日/重要约定」而模型**没调 remember** → 回复落库后自动补一条
 *    USER 记忆（importance 4–5，内容取原句关键片段）；
 * 2. 模型调过 `remember` → 不重复；
 * 3. 同一件事说两遍 → 记忆库里只有一条；
 * 4. 不命中的普通闲聊 → 什么也不写。
 */
class RememberFallbackTest {

    private lateinit var root: Path
    private lateinit var store: FileStore

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-remember-fallback")
        store = FileStore.open(root)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    /** 模型正常回一句、**没有**调用任何工具。 */
    private fun replyOnly(text: String = "好，我记住了。"): ReplyGenerator = ReplyGenerator { _, emit ->
        emit(SseEvent.Delta(text))
        ReplyOutcome(Usage(1, text.length), textLength = text.length)
    }

    private fun send(
        content: String,
        id: String = "remember-1",
        generator: ReplyGenerator = replyOnly()
    ): RecordingSink {
        val sink = RecordingSink()
        ChatService(store, generator).send(
            sessionId,
            SendMessageRequest(clientMessageId = id, content = content),
            sink
        )
        return sink
    }

    private fun memories(): List<Memory> = store.memories(personaId, limit = Int.MAX_VALUE)

    // ------------------------------------------------------------------ 纯函数

    @Test
    fun `detect extracts the clause and assigns user tag with high importance`() {
        val draft = RememberFallback.detect("对了，我对花生过敏，千万别点带花生的。")
        assertNotNull(draft)
        assertTrue(draft!!.content, draft.content.contains("我对花生过敏"))
        assertFalse("口头前缀要去掉", draft.content.contains("对了"))
        assertFalse("不相关的分句不该拼进来", draft.content.contains("千万别点"))
        assertEquals("过敏是健康级事实", 5, draft.importance)
        assertTrue("必须标成 USER", draft.tags.contains(RememberFallback.USER_TAG))
        assertTrue(draft.tags.contains("过敏"))
    }

    @Test
    fun `detect covers birthday and promises but ignores ordinary chat`() {
        assertEquals(4, RememberFallback.detect("我生日是 3 月 2 日。")!!.importance)
        assertTrue(RememberFallback.detect("我们约定好了明年一起去看海。")!!.content.contains("约定好了"))
        assertNull(RememberFallback.detect("今天天气不错，出去走走。"))
        assertNull("否定句不是事实", RememberFallback.detect("我不过敏，什么都能吃。"))
    }

    @Test
    fun `detect merges two facts from one message into a single memory`() {
        val draft = RememberFallback.detect("我对芒果过敏；生日是 5 月 3 日。")!!
        assertTrue(draft.content, draft.content.contains("我对芒果过敏"))
        assertTrue(draft.content, draft.content.contains("生日是 5 月 3 日"))
        assertEquals(5, draft.importance)
    }

    // ------------------------------------------------------------------ ChatService 集成

    @Test
    fun `a missed remember is backfilled after the reply is persisted`() {
        val sink = send("我对花生过敏，千万别点带花生的。")

        val done = sink.firstOf<SseEvent.Done>()
        assertTrue("用户消息 + 回复都落库了", done.assistantMessageId.isNotBlank())
        val assistant = store.blockMessages(sessionId, 0).last { it.role == MessageRole.ASSISTANT }
        assertTrue("回复是模型原话", assistant.content.contains("我记住了"))

        val memory = memories().single()
        assertTrue(memory.content, memory.content.contains("花生过敏"))
        assertEquals("importance 5 → 0–100 表里的 100", 100, memory.importance)
        assertTrue(memory.tags.contains(RememberFallback.USER_TAG))
        assertEquals("内容就是原句片段，不改写、不加前缀", "我对花生过敏", memory.content)
    }

    @Test
    fun `when the model already called remember no duplicate is written`() {
        val generator = ReplyGenerator { _, emit ->
            emit(SseEvent.Delta("好，我记住了。"))
            ReplyOutcome(
                usage = Usage(1, 6),
                toolsUsed = listOf(com.yap.agentd.tools.ServerToolCatalog.REMEMBER),
                textLength = 6
            )
        }
        send("我对花生过敏。", generator = generator)
        assertEquals("模型已经记过，兜底不能再来一条", 0, memories().size)
    }

    @Test
    fun `saying the same thing twice keeps exactly one memory`() {
        send("我对花生过敏。", id = "remember-a")
        send("我对花生过敏。", id = "remember-b")
        assertEquals("同一事实不重复记", 1, memories().size)
    }

    @Test
    fun `ordinary chat writes no memory`() {
        send("今天天气不错，出去走走。")
        assertEquals(0, memories().size)
    }
}
