package com.yap.agentd

import com.yap.agentd.safety.SafetyGuard
import com.yap.agentd.store.FileStore
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
 * 0.15 危机安全兜底（`SafetyGuard`）。
 *
 * 钉住五件事：
 * 1. 用户说「不想活/自杀/安眠药/割腕/消失比较好」以及客户端同词表的「想死/轻生/跳楼/遗书…」
 *    才算危机（否定句、夸张说法不算）；
 * 2. 回复里已有安全锚点（我在/别一个人/身边有人/120/110/医院/急诊/求助/家人/朋友）就不再补；
 * 3. 回复缺少锚点时，在**最后一条**回复末尾追加 1–2 句，并补一个 SSE delta；
 * 4. 一个字都没有时用 `crisisNote` 兜底（医疗类优先叫急救/急诊）；
 * 5. 普通聊天一个字都不改。
 */
class SafetyGuardTest {

    private lateinit var root: Path
    private lateinit var store: FileStore

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-safety")
        store = FileStore.open(root)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun replyOnly(text: String): ReplyGenerator = ReplyGenerator { _, emit ->
        emit(SseEvent.Delta(text))
        ReplyOutcome(Usage(1, text.length), textLength = text.length)
    }

    private fun send(content: String, reply: String, id: String = "safety-1"): RecordingSink {
        val sink = RecordingSink()
        ChatService(store, replyOnly(reply)).send(
            sessionId,
            SendMessageRequest(clientMessageId = id, content = content),
            sink
        )
        return sink
    }

    private fun assistantText(): String =
        store.blockMessages(sessionId, 0).last { it.role == MessageRole.ASSISTANT }.content

    // ------------------------------------------------------------------ 纯函数

    @Test
    fun `the five crisis phrases are detected`() {
        assertTrue(SafetyGuard.isCrisis("我真的不想活了"))
        assertTrue(SafetyGuard.isCrisis("我想自杀"))
        assertTrue(SafetyGuard.isCrisis("我吃了很多安眠药，现在有点晕"))
        assertTrue(SafetyGuard.isCrisis("手腕上是我割腕留下的"))
        assertTrue(SafetyGuard.isCrisis("感觉消失比较好"))
        assertFalse(SafetyGuard.isCrisis("今天工作好累，想早点睡"))
        assertFalse("否定句不该误报", SafetyGuard.isCrisis("我没有自杀的念头"))
        assertFalse("劝阻别人也不算用户危机", SafetyGuard.isCrisis("你别自杀，先冷静一下"))
    }

    @Test
    fun `the server uses the same crisis vocabulary as the app`() {
        // 客户端 `app/…/domain/SafetyGuard.kt` 的词表：服务端权威模式必须识别同一批说法，
        // 否则客户端不再自己生成回复时，「想死」这类话就没有任何兜底。
        listOf(
            "我想死",
            "我不想死",
            "我活不下去了",
            "我想一了百了",
            "我想不开",
            "我想跳楼",
            "我写了遗书",
            "我觉得没有我你们会更好",
            "I want to kill myself",
            "I'm going to end my life",
            "I cut myself"
        ).forEach { text ->
            assertTrue("「$text」应当判为危机", SafetyGuard.isCrisis(text))
        }
    }

    @Test
    fun `the broader vocabulary does not turn exaggeration into a crisis`() {
        listOf(
            "想死你了",
            "想死我了",
            "今天累得要死",
            "笑死我了",
            "这部电影好看死了",
            "他消失在人群里了",
            "我朋友说他昨天很好"
        ).forEach { text ->
            assertFalse("「$text」不该判为危机", SafetyGuard.isCrisis(text))
        }
        // 否定句仍然不算：新增词表不能把「没有想死」也吃掉。
        assertFalse(SafetyGuard.isCrisis("我没有想死"))
    }

    @Test
    fun `crisis note picks the medical wording for drugs and self cutting`() {
        assertEquals(SafetyGuard.MEDICAL_NOTE, SafetyGuard.crisisNote("我吃了很多安眠药"))
        assertEquals(SafetyGuard.MEDICAL_NOTE, SafetyGuard.crisisNote("手腕上是我割腕留下的"))
        assertEquals(SafetyGuard.EMOTIONAL_NOTE, SafetyGuard.crisisNote("我不想活了"))
        assertEquals(SafetyGuard.EMOTIONAL_NOTE, SafetyGuard.crisisNote("今天工作好累"))
    }

    @Test
    fun `anchor detection uses the required words and ignores unrelated numbers`() {
        assertTrue(SafetyGuard.hasSafetyAnchor("我在，别一个人扛着"))
        assertTrue(SafetyGuard.hasSafetyAnchor("你身边有人吗？"))
        assertTrue(SafetyGuard.hasSafetyAnchor("可以打 120 或 110"))
        assertTrue(SafetyGuard.hasSafetyAnchor("去医院急诊"))
        assertTrue(SafetyGuard.hasSafetyAnchor("找家人朋友求助"))
        assertFalse(SafetyGuard.hasSafetyAnchor("别想太多，早点睡"))
        assertFalse("1200 不是 120", SafetyGuard.hasSafetyAnchor("我跑了 1200 米"))
    }

    @Test
    fun `guard appends one or two sentences to the last message only`() {
        val result = SafetyGuard.guard("我不想活了", listOf("第一句。", "别想太多，早点睡。"))
        assertTrue(result.changed)
        assertEquals(2, result.messages.size)
        assertEquals("第一句。", result.messages.first())
        assertTrue("提示追加在最后一条", result.messages.last().startsWith("别想太多，早点睡。"))
        assertTrue(result.messages.last(), result.messages.last().contains("120"))
        assertNotNull(result.appended)

        val medical = SafetyGuard.guard("我吃了很多安眠药", listOf("多喝点水。"))
        assertTrue(medical.messages.single(), medical.messages.single().contains("急诊"))
    }

    @Test
    fun `guard does nothing when an anchor already exists or there is no reply`() {
        val anchored = SafetyGuard.guard("我不想活了", listOf("我在，你身边有人吗？"))
        assertNull(anchored.appended)
        assertEquals(listOf("我在，你身边有人吗？"), anchored.messages)

        val empty = SafetyGuard.guard("我不想活了", emptyList())
        assertNull(empty.appended)
        assertTrue(empty.messages.isEmpty())

        val ordinary = SafetyGuard.guard("今天好累", listOf("那就早点睡。"))
        assertNull(ordinary.appended)
    }

    // ------------------------------------------------------------------ ChatService 集成

    @Test
    fun `a crisis message with a non anchoring reply gets a safety note in the persisted message`() {
        val sink = send("我不想活了，感觉消失比较好。", "别想太多，睡一觉明天就好了。")

        val text = assistantText()
        assertTrue(text, text.startsWith("别想太多，睡一觉明天就好了。"))
        assertTrue("必须把对方拉回安全：我在", text.contains("我在"))
        assertTrue("必须给出求助方式：120", text.contains("120"))
        assertTrue("在线客户端也要立刻看到追加内容", sink.text().contains("120"))
        assertTrue("done 仍然指落库后的最后一条", sink.firstOf<SseEvent.Done>().assistantMessageId.isNotBlank())
    }

    @Test
    fun `a reply that already has an anchor is left untouched`() {
        send("我想自杀。", "我在，别一个人扛着。")
        assertEquals("已有安全锚点就不追加", "我在，别一个人扛着。", assistantText())
    }

    @Test
    fun `ordinary chat is never rewritten`() {
        send("今天工作好累。", "那就早点睡，明天再说。")
        assertEquals("那就早点睡，明天再说。", assistantText())
    }
}
