package com.yap.agentd

import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.store.DiaryNote
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.MessageSource
import com.yap.agentd.store.StoredIntent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.util.UUID

/**
 * 日记提醒与 `defer_reply` 念头：**到点由心跳落库，只发一次，错过太久不补发**。
 *
 * 这条链路的关键是「不依赖客户端」：`POST /v1/proactive/tick`（生产是 60 秒定时器）自己推进；
 * 客户端连不连、在不在前台，提醒照样变成一条主动消息。
 */
class DiaryReminderTest {

    private lateinit var root: Path
    private val clock = TestSupport.FakeClock(1_700_000_000_000L)
    private lateinit var store: FileStore
    private lateinit var services: AgentdServices

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-reminders")
        clock.now = 1_700_000_000_000L
        store = FileStore.open(root, clock.reader)
        services = AgentdServices(store = store)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun tick(now: Long = clock.now): ProactiveTickOutcome = services.proactiveTicker!!.tickWithDecisions(now)

    private fun note(content: String = "记得周四给妈妈打电话", remindAt: Long): DiaryNote =
        store.upsertDiaryNote(personaId, noteId = null, content = content, remindAt = remindAt)

    private fun messages(): List<com.yap.agentd.store.Message> = store.blockMessages(sessionId, 0)

    // ------------------------------------------------------------------ 日记提醒

    @Test
    fun `a due diary reminder becomes one proactive message`() {
        val note = note(remindAt = clock.now)
        val outcome = tick()

        assertEquals(1, outcome.fired.size)
        assertEquals(ProactiveTicker.REASON_DIARY_REMINDER, outcome.fired.single().reason)
        val message = messages().single()
        assertEquals(MessageRole.ASSISTANT, message.role)
        assertEquals(MessageSource.PROACTIVE, message.source)
        assertTrue("提醒正文要带笔记内容", message.content.contains("记得周四给妈妈打电话"))

        val updated = store.diaryNote(personaId, note.id)!!
        assertEquals("要写 remindedAt", clock.now, updated.remindedAt)
        // 不占用主动消息的每日配额（用户自己定的提醒不该被「今天说够了」挡掉）。
        assertEquals(0, store.proactiveState(personaId, "1970-01-01").proactiveCountToday)
    }

    @Test
    fun `the same reminder fires exactly once`() {
        note(remindAt = clock.now)
        assertEquals(1, tick().fired.size)
        clock.advance(60_000)
        assertTrue("第二拍不能重复提醒", tick().fired.isEmpty())
        assertEquals(1, messages().size)
    }

    @Test
    fun `a reminder in the future does not fire yet`() {
        note(remindAt = clock.now + 60 * 60_000)
        assertTrue(tick().fired.isEmpty())
        assertTrue(messages().isEmpty())

        clock.advance(61 * 60_000)
        assertEquals(1, tick().fired.size)
    }

    @Test
    fun `a reminder more than a day late is cleared without a message`() {
        note(remindAt = clock.now - DiaryNote.CATCH_UP_WINDOW_MS - 1)
        assertTrue(">24h 不补发", tick().fired.isEmpty())
        assertTrue(messages().isEmpty())
        val cleared = store.diaryNotes(personaId).single()
        assertNotNull("只清标记", cleared.remindedAt)
        // 再拍也不会补发。
        clock.advance(60_000)
        assertTrue(tick().fired.isEmpty())
    }

    @Test
    fun `a reminder survives a restart and still fires once`() {
        note(remindAt = clock.now)
        val reopened = FileStore.open(root, clock.reader)
        val reopenedServices = AgentdServices(store = reopened)
        assertEquals(1, reopenedServices.proactiveTicker!!.tickWithDecisions(clock.now).fired.size)
        val afterRestart = FileStore.open(root, clock.reader)
        assertTrue(
            "重启后 remindedAt 仍在，不该重复提醒",
            AgentdServices(store = afterRestart).proactiveTicker!!.tickWithDecisions(clock.now).fired.isEmpty()
        )
    }

    @Test
    fun `a reminder for a persona without a session is skipped without crashing`() {
        store.deleteSession(sessionId)
        note(remindAt = clock.now)
        assertTrue("没有会话就没有落库的地方", tick().fired.isEmpty())
        // 提醒没有被消费（remindedAt 仍是 null）：等会话建回来还能发。
        assertNull(store.diaryNotes(personaId).single().remindedAt)
    }

    // ------------------------------------------------------------------ defer_reply 念头

    private fun intent(earliestAt: Long, latestAt: Long = earliestAt + 30 * 60_000L, status: String = StoredIntent.STATUS_PENDING): StoredIntent =
        store.saveIntent(
            StoredIntent(
                id = UUID.randomUUID().toString(),
                personaId = personaId,
                sessionId = sessionId,
                kind = StoredIntent.KIND_REACH_OUT,
                earliestAt = earliestAt,
                latestAt = latestAt,
                note = "当时手头忙",
                hint = "",
                status = status,
                createdAt = earliestAt - 60 * 60_000L,
                sentAt = null
            )
        )

    @Test
    fun `a due deferred intent becomes one proactive message`() {
        val intent = intent(earliestAt = clock.now)
        val outcome = tick()

        assertEquals(1, outcome.fired.size)
        assertEquals(ProactiveTicker.REASON_DEFERRED, outcome.fired.single().reason)
        val message = messages().single()
        assertEquals(MessageSource.PROACTIVE, message.source)
        assertEquals(
            "测试路径没有 LLM，正文来自 ProactiveTextSource.DEFAULT",
            "忽然想起你，想问问你现在在做什么。",
            message.content
        )
        assertEquals(StoredIntent.STATUS_SENT, store.intent(intent.id)!!.status)
        assertNotNull(store.intent(intent.id)!!.sentAt)

        clock.advance(60_000)
        assertTrue("念头只发一次", tick().fired.isEmpty())
        assertEquals(1, messages().size)
    }

    @Test
    fun `a deferred intent in the future waits`() {
        intent(earliestAt = clock.now + 10 * 60_000)
        assertTrue(tick().fired.isEmpty())
        assertTrue(messages().isEmpty())
    }

    @Test
    fun `a stale deferred intent expires instead of firing`() {
        val stale = intent(
            earliestAt = clock.now - 3 * 24 * 60 * 60 * 1000L,
            latestAt = clock.now - 2 * 24 * 60 * 60 * 1000L
        )
        assertTrue(tick().fired.isEmpty())
        assertTrue(messages().isEmpty())
        assertEquals(StoredIntent.STATUS_EXPIRED, store.intent(stale.id)!!.status)
    }

    @Test
    fun `deferred intents persist across a restart`() {
        intent(earliestAt = clock.now)
        val reopened = FileStore.open(root, clock.reader)
        assertEquals(1, reopened.pendingIntents(personaId).size)
        val outcome = AgentdServices(store = reopened).proactiveTicker!!.tickWithDecisions(clock.now)
        assertEquals(1, outcome.fired.size)
        assertNull("发出去之后就不再 pending", reopened.pendingIntents(personaId).firstOrNull())
    }

    @Test
    fun `activity gating does not block user scheduled reminders`() {
        // 他正在忙：主动消息会被门禁挡掉，但用户自己定的提醒照发。
        val busy = ActivityState(
            kind = "WORK",
            activity = "在赶方案",
            until = clock.now + 60 * 60_000,
            availability = com.yap.agentd.proactive.ActivityAvailability.BUSY
        )
        store.updateActivity(personaId, ActivityState.encode(busy))
        note(remindAt = clock.now)
        assertEquals(1, tick().fired.size)
    }
}
