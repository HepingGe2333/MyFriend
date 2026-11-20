package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MentalActivity
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.ProactiveFailure
import com.yap.agentd.store.SessionMemory
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import com.yap.app.domain.MemoryContract
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
 * 0.15 全量备份 v6+：**服务端专有状态都进 `extensions`，客户端键集不变**。
 *
 * 覆盖四块（0.14 已经覆盖的心理活动/会话记忆这里也一起再走一遍，防止 0.15 的可加键把旧字段挤掉）：
 * 1. `mentalActivities[]`、`sessionMemory[]`（含 0.15 的主题总结与检查游标）、
 *    `proactiveFailures[]` 都写在 `extensions` 里；
 * 2. 人格的 `blockedUntil/blockReason`、消息的 `blocked` 仍然是客户端可加键；
 * 3. 顶层键集只增 `extensions`（客户端 `ignoreUnknownKeys`），**不许**出现服务端专有的顶层段；
 * 4. 客户端 v6 备份（没有 `extensions`）导入不报错；merge 不清空已有服务端状态。
 */
class BackupV6ExtensionsTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-backup-015")
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
            """{"ok":true,"personaId":"$personaId","mentalActivities":${result.mentalActivities},""" +
                """"sessionMemories":${result.sessionMemories},"proactiveFailures":${result.proactiveFailures}}"""
        }

    private fun seed015State() {
        clock.advance(1_000)
        store.blockPersona(personaId, until = clock.now + 3 * 3_600_000L, reason = "想安静一天")
        store.appendMessage(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = "拉黑期间说的那句",
            clientMessageId = "blocked-015",
            blocked = true
        )
        store.saveMentalActivity(
            MentalActivity(
                id = "mental-015",
                personaId = personaId,
                sessionId = sessionId,
                at = clock.now,
                thought = "刚才那句话让我有点想笑",
                moodLabel = "轻快",
                moodValence = 1,
                moodArousal = 3,
                moodDominance = 4,
                moodIntensity = 3
            )
        )
        store.saveSessionMemory(
            SessionMemory(
                sessionId = sessionId,
                personaId = personaId,
                shortSummary = "下周要去哈尔滨。",
                shortUpdatedAt = clock.now,
                shortUserMessages = 5,
                longSummary = "你们认识三年了。",
                longUpdatedAt = clock.now,
                longUserMessages = 40,
                longCheckedUserMessages = 40,
                topics = listOf(
                    MemoryContract.TopicSegment("2026-11-18 晚", "加班与方案改版"),
                    MemoryContract.TopicSegment("2026-11-19 早上", "哈尔滨的行程")
                ),
                topicUpdatedAt = clock.now,
                topicUserMessages = 30,
                topicCheckedUserMessages = 30
            )
        )
        store.recordProactiveFailure(
            ProactiveFailure(
                id = "failure-015",
                personaId = personaId,
                sessionId = sessionId,
                at = clock.now,
                stage = ProactiveFailure.Stage.GENERATION,
                reason = "generation_failed",
                detail = "掷中了但没生成出正文",
                text = ""
            )
        )
    }

    @Test
    fun `015 state is exported under extensions without changing the client key set`() {
        seed015State()
        val backup = Json.parse(export()) as JsonValue.Obj

        val topLevel = backup.fields.keys
        assertTrue("客户端 v6 的键都还在", topLevel.containsAll(
            listOf(
                "format", "formatVersion", "appVersion", "exportedAt", "apiKeysOmitted",
                "personas", "memories", "sessions", "messages", "media", "stickers",
                "diaryNotes", "presets", "proactiveRules", "agentIntents",
                "memoryConsolidations", "apiConfigs", "settings", "extensions"
            )
        ))
        assertFalse("服务端专有段不能出现在顶层", topLevel.contains("mentalActivities"))
        assertFalse(topLevel.contains("sessionMemory"))
        assertFalse(topLevel.contains("proactiveFailures"))
        assertEquals(FileStore.BACKUP_FORMAT_VERSION, backup.int("formatVersion"))

        val extensions = backup.obj("extensions")
        assertEquals(1, extensions.objects("mentalActivities").size)
        assertEquals(1, extensions.objects("sessionMemory").size)
        assertEquals(1, extensions.objects("proactiveFailures").size)

        val memory = extensions.objects("sessionMemory").single()
        assertEquals(2, memory.objects("topics").size)
        assertEquals("2026-11-18 晚", memory.objects("topics").first().str("timeLabel"))
        assertEquals("加班与方案改版", memory.objects("topics").first().str("summary"))
        assertEquals(40, memory.int("longUserMessages"))
        assertEquals(40, memory.int("longCheckedUserMessages"))
        assertEquals(30, memory.int("topicUserMessages"))

        val failure = extensions.objects("proactiveFailures").single()
        assertEquals("generation", failure.str("stage"))
        assertEquals("generation_failed", failure.str("reason"))

        val persona = backup.objects("personas").first { it.str("id") == personaId }
        assertNotNull(persona.optLong("blockedUntil"))
        assertEquals("想安静一天", persona.str("blockReason"))
        val blocked = backup.objects("messages").first { it.str("content") == "拉黑期间说的那句" }
        assertTrue(blocked.bool("blocked"))
    }

    @Test
    fun `round trip restores topics failures and block fields`() {
        seed015State()
        val restoredRoot = TestSupport.tempDir("agentd-backup-015-restored")
        try {
            val restored = FileStore.open(restoredRoot, clock.reader)
            val result = importInto(restored, export())
            assertTrue(result, result.contains("\"proactiveFailures\":1"))
            assertTrue(result, result.contains("\"sessionMemories\":1"))

            val memory = restored.sessionMemory(sessionId)!!
            assertEquals(2, memory.topics.size)
            assertEquals("哈尔滨的行程", memory.topics.last().summary)
            assertEquals(40L, memory.longUserMessages)
            assertEquals(30L, memory.topicCheckedUserMessages)

            val failure = restored.proactiveFailures().single()
            assertEquals("generation_failed", failure.reason)
            assertEquals("掷中了但没生成出正文", failure.detail)

            assertTrue(restored.isBlocked(personaId, clock.now))
            assertEquals("想安静一天", restored.persona(personaId)!!.blockReason)
            assertTrue(
                restored.blockMessages(sessionId, 0).single { it.content == "拉黑期间说的那句" }.blocked
            )
        } finally {
            TestSupport.deleteRecursively(restoredRoot)
        }
    }

    @Test
    fun `a client v6 backup without extensions still imports and merge keeps server state`() {
        seed015State()
        // 模拟「客户端导出的 v6」：没有 extensions、没有 blocked 字段（客户端不认识它们）。
        val stripped = Json.parse(export()) as JsonValue.Obj
        stripped.fields.remove("extensions")
        (stripped.fields["personas"] as JsonValue.Arr).items.forEach { item ->
            val obj = item as JsonValue.Obj
            obj.fields.remove("blockedUntil")
            obj.fields.remove("blockReason")
        }
        (stripped.fields["messages"] as JsonValue.Arr).items.forEach { item ->
            (item as JsonValue.Obj).fields.remove("blocked")
        }
        val clientBackup = Json.stringify(stripped)

        val mergedRoot = TestSupport.tempDir("agentd-backup-015-merge")
        try {
            val merged = FileStore.open(mergedRoot, clock.reader)
            // 先把服务端自己的全量备份导进去（有 extensions），再 merge 一份客户端 v6。
            importInto(merged, export(), mode = FileStore.MODE_REPLACE)
            assertEquals(1, merged.proactiveFailures().size)
            val result = importInto(merged, clientBackup, mode = FileStore.MODE_MERGE)
            assertTrue(result, result.contains("ok"))
            // merge 的语义：备份里没有的段保留。客户端的 v6 没有 extensions，
            // 所以心理活动 / 会话记忆 / 失败记录都还在（不会因为换客户端备份就被清空）。
            assertEquals("失败记录不丢", 1, merged.proactiveFailures().size)
            assertEquals("心理活动不丢", 1, merged.mentalActivities(personaId).size)
            assertEquals(
                "会话记忆（含 0.15 主题）不丢",
                2,
                merged.sessionMemory(sessionId)!!.topics.size
            )
        } finally {
            TestSupport.deleteRecursively(mergedRoot)
        }

        // replace：备份就是新状态，服务端专有段被清空（与 0.14 的语义一致）。
        val replacedRoot = TestSupport.tempDir("agentd-backup-015-replace")
        try {
            val replaced = FileStore.open(replacedRoot, clock.reader)
            importInto(replaced, clientBackup, mode = FileStore.MODE_REPLACE)
            assertNull("客户端备份里没有拉黑字段 → replace 后没有了", replaced.persona(personaId)!!.blockedUntil)
            assertEquals(0, replaced.mentalActivities(personaId).size)
            assertNull(replaced.sessionMemory(sessionId))
            assertTrue(replaced.proactiveFailures().isEmpty())
        } finally {
            TestSupport.deleteRecursively(replacedRoot)
        }
    }
}
