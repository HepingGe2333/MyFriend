package com.yap.agentd.experience

import com.yap.agentd.TestSupport
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.SessionMemory
import com.yap.app.domain.MemoryContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/**
 * `memorize(query)` 的检索（0.15 上下文/记忆 v2）：
 * 在**会话历史原文**里按「关键词 + 主题段 + 时间衰减」找旧事，输出
 * `<回忆：时间段 —— 摘要；你原话：「…」>`。
 *
 * 与 `recall` 的分工也在这里钉住：`recall` 翻长期记忆库，`memorize` 翻聊天记录本身。
 */
class SessionRecallTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-18 19:40 本地（周三晚上）。 */
    private val evening = Instant.parse("2026-11-18T11:40:00Z").toEpochMilli()

    /** 三个月前的同一时刻（用来验证时间衰减）。 */
    private val longAgo = Instant.parse("2026-08-18T11:40:00Z").toEpochMilli()

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-session-recall")
        store = FileStore.open(root, { evening })
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    private fun clockAt(millis: Long): java.time.Clock =
        java.time.Clock.fixed(Instant.ofEpochMilli(millis), zone)

    @Test
    fun `recall finds a keyword and renders the memory shape`() {
        store.appendMessage(sessionId, MessageRole.USER, "我下周要去哈尔滨出差，有点怕冷")
        store.appendMessage(sessionId, MessageRole.ASSISTANT, "那你多带件厚的。")
        store.appendMessage(sessionId, MessageRole.USER, "今天吃什么好呢")
        val recall = SessionRecall(store, clockAt(evening))
        val result = recall.recall(sessionId, "哈尔滨", now = evening)

        assertFalse("要能找到", result.hits.isEmpty())
        assertTrue(result.text, result.text.contains("<回忆："))
        assertTrue(result.text, result.text.contains("哈尔滨"))
        assertTrue("要带时间段", result.text.contains("2026-11-18"))
    }

    @Test
    fun `recall uses topic summaries and quotes the original line`() {
        store.appendMessage(sessionId, MessageRole.USER, "方案又被甲方打回来了，今晚还得改")
        store.saveSessionMemory(
            SessionMemory(
                sessionId = sessionId,
                personaId = FileStore.SEED_PERSONA_1_ID,
                topics = listOf(
                    MemoryContract.TopicSegment(
                        timeLabel = "2026-11-18 晚",
                        summary = "加班与方案改版"
                    )
                )
            )
        )
        val recall = SessionRecall(store, clockAt(evening))
        val result = recall.recall(sessionId, "方案", now = evening)

        assertTrue(result.hits.any { it.kind == SessionRecall.Hit.Kind.TOPIC })
        assertTrue(result.text, result.text.contains("加班与方案改版"))
        assertTrue("主题命中要附一句当时的原话", result.hits.any { !it.quote.isNullOrBlank() })
    }

    @Test
    fun `recent mentions rank above the same keyword from months ago`() {
        // 两份数据目录：一份的消息在三个月前，一份在今天。
        val oldRoot = TestSupport.tempDir("agentd-session-recall-old")
        try {
            val oldStore = FileStore.open(oldRoot, { longAgo })
            oldStore.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.USER, "我怕冷，冬天手总是冰的")
            val oldRecall = SessionRecall(oldStore, clockAt(longAgo))
            val oldResult = oldRecall.recall(FileStore.SEED_SESSION_ID, "怕冷", now = evening)
            val oldScore = oldResult.hits.first().score

            store.appendMessage(sessionId, MessageRole.USER, "我还是怕冷，今天又冻着了")
            val newRecall = SessionRecall(store, clockAt(evening))
            val newResult = newRecall.recall(sessionId, "怕冷", now = evening)
            val newScore = newResult.hits.first().score

            assertTrue("越近的事越容易想起来：new=$newScore old=$oldScore", newScore > oldScore)
        } finally {
            TestSupport.deleteRecursively(oldRoot)
        }
    }

    @Test
    fun `nothing found returns a honest message and no hits`() {
        store.appendMessage(sessionId, MessageRole.USER, "今天天气不错")
        val recall = SessionRecall(store, clockAt(evening))
        val result = recall.recall(sessionId, "量子力学", now = evening)

        assertTrue(result.hits.isEmpty())
        assertEquals(SessionRecall.NOTHING, result.text)
    }

    @Test
    fun `blocked messages are never recalled`() {
        store.appendMessage(
            sessionId,
            MessageRole.USER,
            "被拉黑时说的秘密：我其实怕黑",
            blocked = true
        )
        store.appendMessage(sessionId, MessageRole.USER, "今天想早点睡")
        val recall = SessionRecall(store, clockAt(evening))
        val result = recall.recall(sessionId, "怕黑", now = evening)

        assertTrue("拉黑期间的消息永久不进模型上下文（检索也一样）", result.hits.isEmpty())
    }

    @Test
    fun `an empty query returns nothing`() {
        store.appendMessage(sessionId, MessageRole.USER, "在吗")
        val recall = SessionRecall(store, clockAt(evening))
        assertEquals(SessionRecall.NOTHING, recall.recall(sessionId, "   ", now = evening).text)
    }
}
