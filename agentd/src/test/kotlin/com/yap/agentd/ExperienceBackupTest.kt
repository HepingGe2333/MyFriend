package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MentalActivity
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.SessionMemory
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.nio.file.Path

/**
 * 0.14 备份往返：`blockedUntil/blockReason`、心理活动、会话记忆都进导出/导入。
 *
 * 两条兼容性要求：
 * 1. 这三块是**可加**的（客户端 v6 会忽略未知键/未知段）；
 * 2. 老 v6 备份（没有这些段）照样能导入，导入后不会把已有状态清空（merge）。
 */
class ExperienceBackupTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-exp-backup")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun export(store: FileStore = this.store): String = buildString {
        store.writeBackup("test", clock.now, this)
    }

    private fun importInto(target: FileStore, backup: String, mode: String = FileStore.MODE_REPLACE): String =
        target.importBackupRequest(StringReader("""{"mode":"$mode","data":$backup}"""), mode).let { result ->
            """{"ok":true,"personas":${result.personas},"mentalActivities":${result.mentalActivities},"sessionMemories":${result.sessionMemories}}"""
        }

    private fun seedExperienceState() {
        clock.advance(1_000)
        store.blockPersona(personaId, until = clock.now + 2 * 3_600_000L, reason = "想清静两天")
        store.appendMessage(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = "被拉黑时发的那条",
            clientMessageId = "blocked-backup",
            blocked = true
        )
        clock.advance(1_000)
        store.saveMentalActivity(
            MentalActivity(
                id = "mental-backup-1",
                personaId = personaId,
                sessionId = sessionId,
                at = clock.now,
                thought = "他刚才那句话让我心里软了一下",
                moodLabel = "有点软",
                moodValence = 1,
                moodArousal = 3,
                moodDominance = 3,
                moodIntensity = 3
            )
        )
        store.saveSessionMemory(
            SessionMemory(
                sessionId = sessionId,
                personaId = personaId,
                shortSummary = "你提过下周去哈尔滨。",
                shortUserMessages = 5,
                longSummary = "你们认识三年了。",
                longUserMessages = 30
            )
        )
    }

    @Test
    fun `export carries all 0_14 fields in client-compatible sections`() {
        seedExperienceState()
        val backup = Json.parse(export()) as JsonValue.Obj

        val persona = backup.objects("personas").first { it.str("id") == personaId }
        assertEquals(clock.now - 1_000 + 2 * 3_600_000L, persona.long("blockedUntil"))
        assertEquals("想清静两天", persona.str("blockReason"))
        val blocked = backup.objects("messages").first { it.str("content") == "被拉黑时发的那条" }
        assertTrue(blocked.bool("blocked"))

        val extensions = backup.obj("extensions")
        assertEquals(1, extensions.objects("mentalActivities").size)
        assertEquals("他刚才那句话让我心里软了一下", extensions.objects("mentalActivities").single().str("thought"))
        assertEquals(1, extensions.objects("sessionMemory").size)
        assertEquals("你提过下周去哈尔滨。", extensions.objects("sessionMemory").single().str("shortSummary"))
        // 顶层仍然是合法的 v6 备份（客户端只认它认识的段）。
        assertEquals(FileStore.BACKUP_FORMAT_VERSION, backup.int("formatVersion"))
        assertEquals(FileStore.BACKUP_FORMAT_NAME, backup.str("format"))
    }

    @Test
    fun `round trip restores block mental activity and session memory`() {
        seedExperienceState()
        val backup = export()
        val restoredRoot = TestSupport.tempDir("agentd-exp-backup-restored")
        try {
            val restored = FileStore.open(restoredRoot, clock.reader)
            val result = importInto(restored, backup)
            assertTrue(result.contains("\"mentalActivities\":1"))
            assertTrue(result.contains("\"sessionMemories\":1"))

            val persona = restored.persona(personaId)!!
            assertNotNull("拉黑状态要带回来", persona.blockedUntil)
            assertEquals("想清静两天", persona.blockReason)
            assertTrue(restored.isBlocked(personaId, clock.now))

            val activity = restored.mentalActivities(personaId).single()
            assertEquals("他刚才那句话让我心里软了一下", activity.thought)
            assertEquals("有点软", activity.moodLabel)

            val memory = restored.sessionMemory(sessionId)!!
            assertEquals("你提过下周去哈尔滨。", memory.shortSummary)
            assertEquals("你们认识三年了。", memory.longSummary)
            assertEquals(30L, memory.longUserMessages)

            val blocked = restored.blockMessages(sessionId, 0)
                .single { it.content == "被拉黑时发的那条" }
            assertTrue("blocked 标记要活着回来", blocked.blocked)
            assertFalse(
                "模型上下文仍然排除它",
                restored.latestMessages(sessionId, 100, includeBlocked = false).any { it.id == blocked.id }
            )
        } finally {
            TestSupport.deleteRecursively(restoredRoot)
        }
    }

    @Test
    fun `merge keeps the imported 0_14 state and replace clears it when absent`() {
        seedExperienceState()
        val backup = export()
        val mergedRoot = TestSupport.tempDir("agentd-exp-backup-merge")
        try {
            val merged = FileStore.open(mergedRoot, clock.reader)
            importInto(merged, backup, mode = FileStore.MODE_MERGE)
            assertEquals(1, merged.mentalActivities(personaId).size)
            assertEquals("你提过下周去哈尔滨。", merged.sessionMemory(sessionId)!!.shortSummary)
        } finally {
            TestSupport.deleteRecursively(mergedRoot)
        }

        // 老客户端备份（没有这三段）导入 replace：新状态应当被清空，不残留。
        val plainBackup = buildString {
            store.writeBackup("test", clock.now, this)
        }
        val stripped = Json.parse(plainBackup) as JsonValue.Obj
        stripped.fields.remove("extensions")
        (stripped.fields["personas"] as JsonValue.Arr).items.forEach { item ->
            val obj = item as JsonValue.Obj
            obj.fields.remove("blockedUntil")
            obj.fields.remove("blockReason")
        }
        val oldBackup = Json.stringify(stripped)
        val clearedRoot = TestSupport.tempDir("agentd-exp-backup-clear")
        try {
            val cleared = FileStore.open(clearedRoot, clock.reader)
            importInto(cleared, oldBackup, mode = FileStore.MODE_REPLACE)
            assertNull("没有人格状态", cleared.persona(personaId)!!.blockedUntil)
            assertEquals(0, cleared.mentalActivities(personaId).size)
            assertNull(cleared.sessionMemory(sessionId))
        } finally {
            TestSupport.deleteRecursively(clearedRoot)
        }
    }
}
