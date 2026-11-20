package com.yap.agentd

import com.yap.agentd.delivery.ClientPresence
import com.yap.agentd.store.FileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path

/**
 * 0.14.1 的工具化人格写入（`spec/experience` 任务书第 4 项）：
 *
 * - `update_persona`：只允许 `traits/likes/behaviors/quirks` 与 `address`（称呼），
 *   走**待审补丁**（与 0.13 的人格进化同一条通路：用户点头之后才 `version+1` 写进人格）；
 * - `set_quiet_hours`：直接写人格的安静时段（`quietSleepMinutes/quietWakeMinutes`），
 *   跨夜/时区由 [com.yap.agentd.persona.QuietHours] 与 `SleepPolicy` 判断。
 */
class PersonaToolsTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000_000L)

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-persona-tools")
        clock.now = 1_000_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun executor(): com.yap.agentd.tools.ToolExecutor =
        com.yap.agentd.tools.ToolExecutor(store, clock = clock.asClock())

    private fun run(name: String, arguments: String): com.yap.agentd.tools.ToolExecutor.Outcome =
        executor().execute(
            name,
            arguments,
            com.yap.agentd.tools.ToolExecutor.Context(
                personaId = personaId,
                sessionId = sessionId,
                userMessageId = "user-message-1"
            )
        )

    // ------------------------------------------------------------------ update_persona

    @Test
    fun `update_persona files a pending patch instead of editing the persona directly`() {
        val beforeVersion = com.yap.agentd.persona.PersonaFields.version(store.persona(personaId)!!)
        val outcome = run(
            com.yap.agentd.tools.ServerToolCatalog.UPDATE_PERSONA,
            """{"addTraits":["嘴硬心软"],"addLikes":["雨天"],"address":"宝贝","reason":"聊着聊着觉得更亲近了"}"""
        )

        assertTrue(outcome.contentForModel, outcome.ok)
        assertTrue(outcome.contentForModel.contains("待审"))
        assertTrue(outcome.contentForModel.contains("patchId="))
        // 人格**没有**被直接改：等待用户点头。
        val current = com.yap.agentd.persona.PersonaFields.current(store.persona(personaId)!!)
        assertTrue("待审阶段不该写进 traits", current.traits.isEmpty())
        assertEquals(beforeVersion, com.yap.agentd.persona.PersonaFields.version(store.persona(personaId)!!))
        val patch = store.pendingPatches(personaId, clock.now).single()
        assertTrue(patch.patchJson.contains("嘴硬心软"))
        assertTrue(patch.patchJson.contains("\"address\":\"宝贝\""))
        assertEquals(sessionId, patch.sessionId)
    }

    @Test
    fun `accepting the patch writes the fields address and bumps the version`() {
        run(com.yap.agentd.tools.ServerToolCatalog.UPDATE_PERSONA, """{"addTraits":["嘴硬心软"],"address":"宝贝"}""")
        val patch = store.pendingPatches(personaId, clock.now).single()
        val beforeVersion = com.yap.agentd.persona.PersonaFields.version(store.persona(personaId)!!)

        val acceptance = com.yap.agentd.evolution.EvolutionService(
            store,
            com.yap.agentd.llm.llmAccess(store)
        ).accept(personaId, patch.id, clock.now)

        assertTrue(acceptance.changed)
        assertEquals("宝贝", acceptance.address)
        assertEquals(beforeVersion + 1, acceptance.version)
        val persona = store.persona(personaId)!!
        assertEquals(listOf("嘴硬心软"), com.yap.agentd.persona.PersonaFields.current(persona).traits)
        assertEquals("宝贝", com.yap.agentd.persona.PersonaFields.address(persona))
        assertTrue("补丁处理完要删掉", store.pendingPatches(personaId, clock.now).isEmpty())
    }

    @Test
    fun `update_persona refuses fields outside the allow list and no-op patches`() {
        // 只给不允许的字段（名字/描述/世界）：不产生补丁，明确失败。
        val nameOnly = run(
            com.yap.agentd.tools.ServerToolCatalog.UPDATE_PERSONA,
            """{"name":"新名字","description":"新的描述","address":""}"""
        )
        assertTrue("没有任何可改字段时不能假装成功：${nameOnly.contentForModel}", !nameOnly.contentForModel.contains("patchId="))
        assertTrue(store.pendingPatches(personaId, clock.now).isEmpty())

        // 加一条已经存在的 trait 也是 no-op。
        store.updatePersona(personaId) { existing, _ ->
            existing.copy(
                extraFields = existing.extraFields + (
                    "traits" to com.yap.app.core.json.JsonValue.Str("[\"慢热\"]")
                    )
            )
        }
        val duplicate = run(
            com.yap.agentd.tools.ServerToolCatalog.UPDATE_PERSONA,
            """{"addTraits":["慢热"]}"""
        )
        assertTrue(duplicate.contentForModel, !duplicate.contentForModel.contains("patchId="))
        assertTrue(store.pendingPatches(personaId, clock.now).isEmpty())
    }

    @Test
    fun `the same proposal is not duplicated`() {
        run(com.yap.agentd.tools.ServerToolCatalog.UPDATE_PERSONA, """{"addTraits":["嘴硬心软"]}""")
        clock.advance(1_000)
        run(com.yap.agentd.tools.ServerToolCatalog.UPDATE_PERSONA, """{"addTraits":["嘴硬心软"]}""")
        assertEquals(1, store.pendingPatches(personaId, clock.now).size)
    }

    // ------------------------------------------------------------------ set_quiet_hours

    @Test
    fun `set_quiet_hours writes the window and bumps the version`() {
        val beforeVersion = com.yap.agentd.persona.PersonaFields.version(store.persona(personaId)!!)
        val outcome = run(
            com.yap.agentd.tools.ServerToolCatalog.SET_QUIET_HOURS,
            """{"start":"23:30","end":"08:00"}"""
        )
        assertTrue(outcome.contentForModel, outcome.ok)
        assertTrue(outcome.summary.contains("23:30–08:00"))
        val persona = store.persona(personaId)!!
        val quiet = com.yap.agentd.persona.PersonaFields.quietHours(persona)
        assertEquals(23 * 60 + 30, quiet!!.startMinutes)
        assertEquals(8 * 60, quiet.endMinutes)
        assertEquals(beforeVersion + 1, com.yap.agentd.persona.PersonaFields.version(persona))
        // 提示词里能看到（数据行，不是规范文案）。
        val prompt = com.yap.agentd.llm.ChatPromptBuilder(clock.asClock().zone).systemPrompt(
            persona = persona,
            mood = store.mood(personaId),
            activity = null,
            now = clock.now
        )
        assertTrue(prompt.contains("23:30–08:00"))
    }

    @Test
    fun `cross midnight windows are correct and boundaries are half open`() {
        val quiet = com.yap.agentd.persona.QuietHours.of("23:30", "08:00")!!
        assertTrue("22:00 不在窗口里", !quiet.contains(22 * 60))
        assertTrue("23:30 是开始（含）", quiet.contains(23 * 60 + 30))
        assertTrue("00:00 在窗口里", quiet.contains(0))
        assertTrue("07:59 还在窗口里", quiet.contains(7 * 60 + 59))
        assertTrue("08:00 是结束（不含）", !quiet.contains(8 * 60))
        assertTrue("12:00 不在窗口里", !quiet.contains(12 * 60))

        // 不跨夜的窗口：13:00–15:00。
        val daytime = com.yap.agentd.persona.QuietHours.of("13:00", "15:00")!!
        assertTrue(daytime.contains(14 * 60))
        assertTrue(!daytime.contains(23 * 60))

        // 时区：安静时段是「本地当天第几分钟」，服务端用 Clock.zone 换算；
        // 同一时刻在东京是 01:00、在上海是 00:00，两者都在窗口内。
        val shanghai = com.yap.agentd.persona.QuietHours.of("23:30", "08:00")!!
        val tokyo = java.time.ZoneId.of("Asia/Tokyo")
        val shanghaiZone = java.time.ZoneId.of("Asia/Shanghai")
        val instant = java.time.Instant.parse("2026-11-19T16:00:00Z")
        val shanghaiMinute = java.time.ZonedDateTime.ofInstant(instant, shanghaiZone).hour * 60 +
            java.time.ZonedDateTime.ofInstant(instant, shanghaiZone).minute
        val tokyoMinute = java.time.ZonedDateTime.ofInstant(instant, tokyo).hour * 60 +
            java.time.ZonedDateTime.ofInstant(instant, tokyo).minute
        assertEquals(0, shanghaiMinute)
        assertEquals(60, tokyoMinute)
        assertTrue(shanghai.contains(shanghaiMinute))
        assertTrue(shanghai.contains(tokyoMinute))
    }

    @Test
    fun `quiet hour parsing accepts the shapes a model actually writes`() {
        assertEquals(23 * 60 + 30, com.yap.agentd.persona.QuietHours.parseMinute("23:30"))
        assertEquals(23 * 60 + 30, com.yap.agentd.persona.QuietHours.parseMinute("2330"))
        assertEquals(23 * 60 + 30, com.yap.agentd.persona.QuietHours.parseMinute("23点30"))
        assertEquals(8 * 60 + 5, com.yap.agentd.persona.QuietHours.parseMinute("8:05"))
        assertEquals(9 * 60 + 30, com.yap.agentd.persona.QuietHours.parseMinute("930"))
        assertNull(com.yap.agentd.persona.QuietHours.parseMinute("24:00"))
        assertNull(com.yap.agentd.persona.QuietHours.parseMinute("乱写"))
    }

    @Test
    fun `set_quiet_hours rejects equal or unparseable times instead of guessing`() {
        for (args in listOf(
            """{"start":"23:00","end":"23:00"}""",
            """{"start":"乱写","end":"08:00"}""",
            """{"start":"23:00"}"""
        )) {
            val outcome = run(com.yap.agentd.tools.ServerToolCatalog.SET_QUIET_HOURS, args)
            assertTrue("应明确失败：$args → ${outcome.contentForModel}", outcome.contentForModel.contains("工具调用没有成功"))
            assertNull("失败不能写人格", com.yap.agentd.persona.PersonaFields.quietHours(store.persona(personaId)!!))
        }
    }

    // ------------------------------------------------------------------ 安静时段真的影响主动判定

    @Test
    fun `quiet hours put the persona to sleep for the proactive engine`() {
        run(com.yap.agentd.tools.ServerToolCatalog.SET_QUIET_HOURS, """{"start":"00:00","end":"23:00"}""")
        // 服务端时钟固定在 2026-11-20 02:00 +08（= 120 分钟），落在 00:00–23:00 里。
        val fixed = object : java.time.Clock() {
            override fun getZone(): java.time.ZoneId = java.time.ZoneId.of("Asia/Shanghai")
            override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this
            override fun instant(): java.time.Instant = java.time.Instant.parse("2026-11-19T18:00:00Z")
            override fun millis(): Long = instant().toEpochMilli()
        }
        val services = AgentdServices(
            store = store,
            replyGenerator = ReplyGenerator { _, _ -> ReplyOutcome(Usage.ZERO) },
            llm = null,
            presence = ClientPresence(fixed)
        )
        val ticker = services.proactiveTicker!!
        val outcome = ticker.tickWithDecisions(fixed.millis())
        assertTrue(
            "安静时段内不该主动开口：${outcome.decisions}",
            outcome.decisions.all { it.reason.code == "asleep" || it.reason.code == "dnd" || it.reason.code == "daily_cap" }
        )
        assertTrue("不应落库任何主动消息", outcome.fired.isEmpty())
    }
}
