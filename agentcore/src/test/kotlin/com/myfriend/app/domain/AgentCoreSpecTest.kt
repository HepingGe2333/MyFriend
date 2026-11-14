package com.myfriend.app.domain

import com.myfriend.app.core.json.Json
import com.myfriend.app.core.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * `:agentcore` 自己的契约测试：`InProcessAgentCore` 必须与 `spec/agent-core/cases/` 逐字一致。
 *
 * 为什么在这里再测一遍（Android 的 `app/src/test/.../spec/AgentCoreSpecTest.kt` 已经测过同一批用例）：
 * - 0.11.0 起规则住在 `:agentcore`，这个模块必须能**独立**证明自己是对的（改完 agentcore 不用先跑 Android）；
 * - App 那份测试用 kotlinx-serialization 读用例；`:agentcore` 不能引它，所以这里用模块自带的
 *   `com.myfriend.app.core.json.Json`——顺便也验证了远程编解码用的解析器能读真实用例文件；
 * - 断言的口径从「直接调 domain object」变成「经由 `AgentCore` 接口」：验的是**接线后的行为**
 *   （客户端真正会拿到什么），而不只是底层函数。
 *
 * 路径怎么找：Gradle 跑单测时 `user.dir` 是 `agentcore/`（模块目录），换 IDE 可能是仓库根。
 * 所以从 `user.dir` 一路往上找「同时有 settings.gradle.kts 和 spec/agent-core/cases」的目录。
 */
class AgentCoreSpecTest {

    // ------------------------------------------------------------------ 路径

    private fun findRepoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile &&
                File(dir, "spec/agent-core/cases").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        error(
            "找不到仓库根目录：从 $start 向上都没有同时包含 settings.gradle.kts 与 " +
                "spec/agent-core/cases 的目录。"
        )
    }

    private fun casesFile(name: String): JsonValue.Obj {
        val file = File(findRepoRoot(), "spec/agent-core/cases/$name.json")
        assertTrue("用例文件不存在：${file.absolutePath}", file.isFile)
        return Json.parse(file.readText(Charsets.UTF_8)) as JsonValue.Obj
    }

    private fun forEachCase(
        file: String,
        block: (caseName: String, input: JsonValue.Obj, expect: JsonValue.Obj) -> Unit
    ) {
        val root = casesFile(file)
        val cases = root.fields["cases"] as? JsonValue.Arr
        assertNotNull("$file 缺少 cases 数组", cases)
        assertTrue("$file 的 cases 不能为空", cases!!.items.isNotEmpty())
        for (element in cases.items) {
            val entry = element as JsonValue.Obj
            val name = entry.str("name")
            val input = entry.obj("input")
            val expect = entry.obj("expect")
            try {
                block(name, input, expect)
            } catch (e: AssertionError) {
                throw AssertionError("[$file / $name] ${e.message}", e)
            }
        }
    }

    // ------------------------------------------------------------------ 1. 睡眠

    @Test
    fun `sleep decision cases through AgentCore`() {
        forEachCase("sleep-decision") { _, input, expect ->
            val mood = input.objOrNull("mood")
            val decision = InProcessAgentCore.sleepDecision(
                SleepInput(
                    sleepEnabled = input.bool("sleepEnabled"),
                    dndOnSleep = input.bool("dndOnSleep"),
                    sleepMinutes = input.intOrNull("sleepMinutes"),
                    wakeMinutes = input.intOrNull("wakeMinutes"),
                    nowMinutes = input.int("nowMinutes"),
                    userMessagesInWindow = input.int("userMessagesInWindow"),
                    wakeThreshold = input.int("wakeThreshold")
                )
            )
            assertEquals("wake（= shouldReply）", expect.bool("shouldReply"), decision.wake)
            assertEquals("asleep", expect.bool("asleep"), decision.asleep)
            assertEquals("dnd", expect.bool("dnd"), decision.dnd)
            assertEquals("moodRelaxed（= woken）", expect.bool("woken"), decision.moodRelaxed)
            assertEquals("marksRead", expect.bool("marksRead"), decision.marksRead)

            // 被叫醒时的心情恢复（MoodRecovery 也在 :agentcore，两端一致）。
            val moodAfter = expect.objOrNull("moodAfter")
            if (moodAfter != null && mood != null) {
                val recovered = MoodRecovery.afterWake(
                    MoodState(label = mood.str("label"), intensity = mood.int("intensity")),
                    now = 0L
                )
                assertEquals("moodAfter.label", moodAfter.str("label"), recovered.label)
                assertEquals("moodAfter.intensity", moodAfter.int("intensity"), recovered.intensity)
            }
        }
    }

    // ------------------------------------------------------------------ 2. defer_reply

    @Test
    fun `defer plan cases through AgentCore`() {
        forEachCase("defer-plan") { _, input, expect ->
            val plan = InProcessAgentCore.deferPlan(
                minutes = input.intOrNull("minutes"),
                reason = input.stringOrNull("reason"),
                now = input.long("now")
            )
            assertEquals("minutes", expect.int("minutes"), plan.minutes)
            assertEquals("reason", expect.str("reason"), plan.reason)
            assertEquals("earliestAt", expect.long("earliestAt"), plan.earliestAt)
            assertEquals("latestAt", expect.long("latestAt"), plan.latestAt)
        }
    }

    // ------------------------------------------------------------------ 3. 主动上限

    /** 固定「本地时间中午」：避免 MoodBehavior 的深夜折扣让结果随跑测试的时刻变化。 */
    private val localNoon: Long = LocalDate.of(2026, 1, 15)
        .atTime(12, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    @Test
    fun `proactive cap cases through AgentCore`() {
        forEachCase("proactive-cap") { _, input, expect ->
            val level = input.int("level")
            assertEquals("normalizedLevel", expect.int("normalizedLevel"), ProactiveLevel.normalize(level))
            val baseCap = ProactiveLevel.dailyCap(level)
            assertEquals("baseDailyCap", expect.int("baseDailyCap"), baseCap)
            assertEquals(
                "heartbeatWeight",
                expect.double("heartbeatWeight"),
                ProactiveLevel.heartbeatWeight(level),
                TOLERANCE
            )
            assertEquals("description", expect.str("description"), ProactiveLevel.description(level))

            val mood = input.objOrNull("mood")
            val actual = InProcessAgentCore.proactiveCap(
                ProactiveCapInput(
                    level = level,
                    moodLabel = mood?.str("label"),
                    moodIntensity = mood?.int("intensity")
                )
            )
            assertEquals("dailyCap（含心情修正）", expect.int("dailyCap"), actual)

            // 只有强度的简化入口：没有标签就不该凭空挪格。
            val intensityOnly = InProcessAgentCore.proactiveCap(level, mood?.intOrNull("intensity"))
            assertEquals("proactiveCap(level, moodIntensity) 只影响基础值", baseCap, intensityOnly)
        }
    }

    @Test
    fun `mood cap matches direct MoodBehavior math`() {
        // 与 AgentCoreSpecTest（Android 侧）的算法逐项对齐：经由接口得到的结果必须与
        // 「MoodBehavior.from + effectiveDailyCap」这条 0.10.x 的老路径完全一致。
        for (level in 1..5) {
            for (label in listOf("", "平静", "雀跃", "难过", "有点累")) {
                for (intensity in 1..5) {
                    val expected = if (label.isBlank()) {
                        ProactiveLevel.dailyCap(level)
                    } else {
                        MoodBehavior.effectiveDailyCap(
                            ProactiveLevel.dailyCap(level),
                            MoodBehavior.from(MoodState(label = label, intensity = intensity), now = localNoon)
                        )
                    }
                    assertEquals(
                        "level=$level label=$label intensity=$intensity",
                        expected,
                        InProcessAgentCore.proactiveCap(ProactiveCapInput(level, label, intensity))
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 4. 工具参数

    @Test
    fun `tool args cases through AgentCore`() {
        forEachCase("tool-args") { _, input, expect ->
            val tool = input.str("tool")
            val args = input.obj("args")
            val actual = InProcessAgentCore.clampToolArgs(tool, plainArgs(args))
            assertEquals(
                "夹取后的参数（tool=$tool）",
                canonical(plainArgs(expect.obj("args"))),
                canonical(actual)
            )
        }
    }

    @Test
    fun `unknown tool is returned unchanged`() {
        // 拒绝是服务端 HTTP 层的责任（400 unknown_tool），客户端实现不该替它定义工具表。
        val args = mapOf("anything" to 42, "text" to "保留")
        assertEquals(args, InProcessAgentCore.clampToolArgs("rm_rf", args))
    }

    @Test
    fun `string numbers are rescued but booleans are not`() {
        assertEquals(5, InProcessAgentCore.clampToolArgs("remember", mapOf("importance" to "9"))["importance"])
        assertEquals(3, InProcessAgentCore.clampToolArgs("remember", mapOf("importance" to true))["importance"])
        assertEquals(3, InProcessAgentCore.clampToolArgs("remember", mapOf("importance" to null))["importance"])
    }

    // ------------------------------------------------------------------ 5. 编解码

    @Test
    fun `codec round trips sleep`() {
        val input = SleepInput(
            sleepEnabled = true,
            dndOnSleep = false,
            sleepMinutes = 1410,
            wakeMinutes = 480,
            nowMinutes = 120,
            userMessagesInWindow = 6,
            wakeThreshold = 6
        )
        assertEquals(input, AgentCoreCodec.decodeSleepInput(AgentCoreCodec.encodeSleepInput(input)))
        // 空值也要保形（null 不能变成 0）。
        val nullWindow = input.copy(sleepMinutes = null, wakeMinutes = null)
        assertEquals(nullWindow, AgentCoreCodec.decodeSleepInput(AgentCoreCodec.encodeSleepInput(nullWindow)))

        val decision = InProcessAgentCore.sleepDecision(input)
        assertEquals(decision, AgentCoreCodec.decodeSleepDecision(AgentCoreCodec.encodeSleepDecision(decision)))
    }

    @Test
    fun `codec round trips defer plan`() {
        val plan = InProcessAgentCore.deferPlan(minutes = 9999, reason = "  现在不想说话  ", now = 1700000000000L)
        assertEquals(plan, AgentCoreCodec.decodeDeferPlan(AgentCoreCodec.encodeDeferPlan(plan)))
        assertEquals("现在不想说话", plan.reason)
        assertEquals(720, plan.minutes)

        val request = AgentCoreCodec.encodeDeferPlanRequest(null, null, 0L)
        // 缺省请求要能落回同一套规则。
        val decodedRequest = Json.parse(request) as JsonValue.Obj
        assertTrue(decodedRequest.fields["minutes"] is JsonValue.Null)
        assertTrue(decodedRequest.fields["reason"] is JsonValue.Null)
    }

    @Test
    fun `codec round trips proactive cap`() {
        val input = ProactiveCapInput(level = 3, moodLabel = "雀跃", moodIntensity = 4)
        val request = Json.parse(AgentCoreCodec.encodeProactiveCapRequest(input)) as JsonValue.Obj
        assertEquals(3, (request.fields["level"] as JsonValue.Num).asInt)
        assertEquals("雀跃", (request.fields["moodLabel"] as JsonValue.Str).value)
        assertEquals(4, (request.fields["moodIntensity"] as JsonValue.Num).asInt)

        val cap = InProcessAgentCore.proactiveCap(input)
        assertEquals(cap, AgentCoreCodec.decodeProactiveCap(AgentCoreCodec.encodeProactiveCap(cap)))
    }

    @Test
    fun `codec round trips tool args preserving unknown keys and types`() {
        val args = mapOf<String, Any?>(
            "content" to "她妹妹叫「小雨」\n第二行",
            "drawer" to "USER",
            "importance" to 9,
            "pinned" to true,
            "nothing" to null,
            "nested" to mapOf("a" to 1),
            "tags" to listOf("家人", "妹妹")
        )
        val request = AgentCoreCodec.encodeToolArgsRequest("remember", args)
        val requestObject = Json.parse(request) as JsonValue.Obj
        assertEquals("remember", (requestObject.fields["tool"] as JsonValue.Str).value)

        val decoded = AgentCoreCodec.decodeToolArgs(
            // 服务端回的是 {"args": {...}}；这里直接用请求里的 args 拼响应，验证解码路径。
            Json.stringify(
                JsonValue.Obj(linkedMapOf("args" to (requestObject.fields["args"] as JsonValue)))
            )
        )
        assertEquals("她妹妹叫「小雨」\n第二行", decoded["content"])
        assertEquals("USER", decoded["drawer"])
        assertEquals(9, decoded["importance"])
        assertEquals(true, decoded["pinned"])
        assertEquals(null, decoded["nothing"])
        assertEquals(mapOf("a" to 1), decoded["nested"])
        assertEquals(listOf("家人", "妹妹"), decoded["tags"])
    }

    @Test
    fun `codec rejects malformed responses instead of guessing`() {
        assertFailsWithProtocolException { AgentCoreCodec.decodeSleepDecision("not json") }
        assertFailsWithProtocolException { AgentCoreCodec.decodeSleepDecision("""{"moodRelaxed":true}""") }
        assertFailsWithProtocolException { AgentCoreCodec.decodeDeferPlan("""{"minutes":45}""") }
        assertFailsWithProtocolException { AgentCoreCodec.decodeToolArgs("""{"args":[]}""") }
    }

    @Test
    fun `codec reads the unified error body`() {
        assertEquals(
            "字段 now 必须是整数（invalid_argument）",
            AgentCoreCodec.decodeErrorMessage(
                """{"error":{"message":"字段 now 必须是整数","code":"invalid_argument"}}"""
            )
        )
        assertEquals(null, AgentCoreCodec.decodeErrorMessage("not json"))
        assertEquals(null, AgentCoreCodec.decodeErrorMessage(null))
    }

    // ------------------------------------------------------------------ 5. 心跳

    /**
     * 审计 P2-6（0.11.1）：0.11.0 的这个文件只覆盖 4/6 个 cases 文件（55/94 条），
     * README 却写「94 条由本模块测试」。heartbeat / memory-decay 的行为回归只能靠 `:app`
     * 的测试才发现——只跑 `:agentcore:test` 是绿的谎言。这里补齐另外两类。
     */
    @Test
    fun `heartbeat cases`() {
        forEachCase("heartbeat") { _, input, expect ->
            val mood = input.objOrNull("mood")?.let {
                MoodState(label = it.str("label"), intensity = it.int("intensity"))
            } ?: MoodState()
            val probability = Heartbeat.probability(
                minutesSinceReset = input.double("minutesSinceReset"),
                mood = mood,
                now = 0L,
                hourOfDay = input.int("hourOfDay")
            )
            assertEquals("probability", expect.double("probability"), probability, TOLERANCE)

            input.objOrNull("perBeat")?.let { perBeat ->
                assertEquals(
                    "perBeatProbability",
                    expect.double("perBeatProbability"),
                    Heartbeat.perBeatProbability(
                        perMinute = perBeat.double("perMinute"),
                        intervalMinutes = perBeat.double("intervalMinutes")
                    ),
                    TOLERANCE
                )
            }

            input.objOrNull("fire")?.let { fire ->
                assertEquals(
                    "shouldFire",
                    expect.bool("shouldFire"),
                    Heartbeat.shouldFire(
                        probability = fire.double("probability"),
                        roll = fire.double("roll")
                    )
                )
            }

            input.objOrNull("minutesBetween")?.let { between ->
                assertEquals(
                    "minutesBetween",
                    expect.double("minutesBetween"),
                    Heartbeat.minutesBetween(
                        lastResetAt = between.long("lastResetAt"),
                        now = between.long("now")
                    ),
                    TOLERANCE
                )
            }
        }
    }

    // ------------------------------------------------------------------ 6. 记忆衰减

    @Test
    fun `memory decay cases`() {
        forEachCase("memory-decay") { _, input, expect ->
            val memory = MemoryLifecycleState(
                id = "m1",
                tier = MemoryTier.valueOf(input.str("tier")),
                pinned = input.bool("pinned"),
                importance = input.int("importance"),
                useCount = input.int("useCount"),
                lastUsedAt = input.long("lastUsedAt"),
                createdAt = input.long("createdAt"),
                strength = input.double("strength"),
                lastConsolidatedAt = input.long("lastConsolidatedAt"),
                drawer = MemoryDrawer.valueOf(input.str("drawer"))
            )
            val now = input.long("now")
            val retention = input.intOrNull("retention")
            val factor = retention?.let { MemoryLifecycle.retentionFactor(it) }

            expect.numOrNull("retentionFactor")?.let { expected ->
                assertNotNull("用例写了 retentionFactor 却没给 retention", factor)
                assertEquals("retentionFactor", expected, factor!!, TOLERANCE)
            }
            expect.numOrNull("effectiveStrength")?.let { expected ->
                assertEquals(
                    "effectiveStrength",
                    expected,
                    MemoryLifecycle.effectiveStrength(memory, now),
                    TOLERANCE
                )
            }
            expect.numOrNull("decayStep")?.let { expected ->
                assertEquals(
                    "decayStep",
                    expected,
                    MemoryLifecycle.decayStep(memory, now, factor ?: 1.0),
                    TOLERANCE
                )
            }
            expect.objOrNull("action")?.let { element ->
                val action = MemoryLifecycle.nextStatus(memory, input.double("statusStrength"), now)
                assertLifecycleAction(memory.id, action, element)
            }
        }
    }

    private fun assertLifecycleAction(id: String, action: LifecycleAction, expect: JsonValue.Obj) {
        when (expect.str("type")) {
            "keep" -> assertEquals("action 类型", LifecycleAction.Keep(id), action)
            "promote" -> {
                val promote = action as? LifecycleAction.Promote
                    ?: error("expect promote，实际 $action")
                assertEquals("promote.strength", expect.double("strength"), promote.strength, TOLERANCE)
            }
            "demote" -> {
                val demote = action as? LifecycleAction.Demote
                    ?: error("expect demote，实际 $action")
                assertEquals("demote.to", MemoryTier.valueOf(expect.str("to")), demote.to)
                assertEquals("demote.strength", expect.double("strength"), demote.strength, TOLERANCE)
            }
            "forget" -> {
                val forget = action as? LifecycleAction.Forget
                    ?: error("expect forget，实际 $action")
                assertEquals("forget.reason", expect.str("reason"), forget.reason)
            }
            else -> error("未知 action 类型：${expect.str("type")}")
        }
    }

    // ------------------------------------------------------------------ 7. 协议常量

    @Test
    fun `protocol constants pin the wire contract`() {
        // 这四条字符串是 0.10.x 起就公开的 HTTP 契约；改它们等于改对外接口，
        // 所以这里**硬编码**断言，而不是从常量反向生成。
        assertEquals("/v1/core/sleep-decision", AgentCoreProtocol.SLEEP_DECISION_PATH)
        assertEquals("/v1/core/defer-plan", AgentCoreProtocol.DEFER_PLAN_PATH)
        assertEquals("/v1/core/proactive-cap", AgentCoreProtocol.PROACTIVE_CAP_PATH)
        assertEquals("/v1/core/tool-args", AgentCoreProtocol.TOOL_ARGS_PATH)
        assertEquals("/health", AgentCoreProtocol.HEALTH_PATH)
        assertEquals(4, AgentCoreProtocol.CORE_PATHS.size)
    }

    // ------------------------------------------------------------------ 辅助

    private fun plainArgs(obj: JsonValue.Obj): Map<String, Any?> =
        LinkedHashMap<String, Any?>().apply {
            obj.fields.forEach { (key, value) -> put(key, AgentCoreCodec.toPlainValue(value)) }
        }

    private fun canonical(map: Map<String, Any?>): String =
        map.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${it.key}=${valueText(it.value)}" }

    private fun valueText(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }
            .joinToString(",", "{", "}") { "${it.key}=${valueText(it.value)}" }

        is List<*> -> value.joinToString(",", "[", "]") { valueText(it) }
        else -> value.toString()
    }

    private fun assertFailsWithProtocolException(block: () -> Unit) {
        try {
            block()
        } catch (expected: AgentCoreProtocolException) {
            return
        }
        throw AssertionError("预期抛 AgentCoreProtocolException，但没有抛")
    }

    private fun JsonValue.Obj.obj(key: String): JsonValue.Obj =
        fields[key] as? JsonValue.Obj ?: error("缺少对象字段 $key")

    private fun JsonValue.Obj.objOrNull(key: String): JsonValue.Obj? = fields[key] as? JsonValue.Obj

    private fun JsonValue.Obj.str(key: String): String =
        (fields[key] as? JsonValue.Str)?.value ?: error("缺少字符串字段 $key")

    private fun JsonValue.Obj.stringOrNull(key: String): String? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Str)?.value ?: error("字段 $key 不是字符串")
    }

    private fun JsonValue.Obj.bool(key: String): Boolean =
        (fields[key] as? JsonValue.Bool)?.value ?: error("缺少布尔字段 $key")

    private fun JsonValue.Obj.int(key: String): Int =
        intOrNull(key) ?: error("缺少整数字段 $key")

    private fun JsonValue.Obj.intOrNull(key: String): Int? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Num)?.asInt ?: error("字段 $key 不是整数")
    }

    private fun JsonValue.Obj.long(key: String): Long {
        val value = fields[key] ?: error("缺少长整数字段 $key")
        return (value as? JsonValue.Num)?.asLong ?: error("字段 $key 不是整数")
    }

    private fun JsonValue.Obj.double(key: String): Double {
        val raw = fields[key] as? JsonValue.Num ?: error("缺少数字字段 $key")
        return raw.asDouble
    }

    private fun JsonValue.Obj.numOrNull(key: String): Double? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Num)?.asDouble ?: error("字段 $key 不是数字")
    }

    private companion object {
        /** 浮点断言容差：exp() 在 JVM 与 Darwin libm 上可能差最后一个二进制位。 */
        const val TOLERANCE = 1e-9
    }
}
