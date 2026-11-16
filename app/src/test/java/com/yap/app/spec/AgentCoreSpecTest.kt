package com.yap.app.spec

import com.yap.app.domain.DeferReply
import com.yap.app.domain.Heartbeat
import com.yap.app.domain.MemoryDrawer
import com.yap.app.domain.MemoryLifecycle
import com.yap.app.domain.MemoryLifecycleState
import com.yap.app.domain.MemoryTier
import com.yap.app.domain.MoodBehavior
import com.yap.app.domain.MoodRecovery
import com.yap.app.domain.MoodState
import com.yap.app.domain.ProactiveLevel
import com.yap.app.domain.SleepPolicy
import com.yap.app.domain.ToolArgClamp
import com.yap.app.domain.LifecycleAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「两端必须一致」的契约测试（Android 侧）。
 *
 * 用例住在仓库根 `spec/agent-core/cases/` 目录下的 JSON 文件里，iOS 侧的对应测试
 * 也读**同一批文件**。
 * 谁改了实现而没同步另一端，两边的测试里必有一边立刻变红 —— 这是 0.10.0 用最低成本做到的「共用」。
 *
 * 为什么用例只收纯函数：这里断言的是「给定输入 → 确定输出」，任何依赖数据库、网络、
 * 设备时钟的代码都不能进来。需要时间的地方，用例里显式给 `now`。
 *
 * 文件路径怎么找：Gradle 跑 unit test 时 `user.dir` 通常是 `app/`，但换 IDE / 换任务也可能
 * 是仓库根。所以从 `user.dir` 一路往上找「同时有 settings.gradle.kts 和 spec/agent-core/cases」
 * 的目录，找不到就直接失败并提示 —— 不要硬编码绝对路径。
 */
class AgentCoreSpecTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
            "找不到仓库根目录：从 $start 向上都没有同时包含 " +
                "settings.gradle.kts 与 spec/agent-core/cases 的目录。" +
                "（Android 单测的 working dir 通常是 app/ 或仓库根；如果你的环境不同，请把 working dir 指到仓库内。）"
        )
    }

    private fun casesFile(name: String): JsonObject {
        val file = File(findRepoRoot(), "spec/agent-core/cases/$name.json")
        assertTrue("用例文件不存在：${file.absolutePath}", file.isFile)
        return json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
    }

    /** 遍历一个契约文件里的 cases；失败信息带上 name，方便直接定位是哪一条不一致。 */
    private fun forEachCase(file: String, block: (caseName: String, input: JsonObject, expect: JsonObject) -> Unit) {
        val root = casesFile(file)
        val cases = root["cases"] as? JsonArray
        assertNotNull("$file 缺少 cases 数组", cases)
        assertTrue("$file 的 cases 不能为空", cases!!.isNotEmpty())
        for (element in cases) {
            val entry = element as JsonObject
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
    fun `sleep decision cases`() {
        forEachCase("sleep-decision") { _, input, expect ->
            val decision = SleepPolicy.evaluate(
                sleepEnabled = input.bool("sleepEnabled"),
                sleepMinutes = input.intOrNull("sleepMinutes"),
                wakeMinutes = input.intOrNull("wakeMinutes"),
                minuteOfDay = input.int("nowMinutes"),
                dndOnSleep = input.bool("dndOnSleep"),
                recentUserMessages = input.int("userMessagesInWindow"),
                wakeThreshold = input.int("wakeThreshold")
            )
            assertEquals("asleep", expect.bool("asleep"), decision.asleep)
            assertEquals("dnd", expect.bool("dnd"), decision.dnd)
            assertEquals("woken", expect.bool("woken"), decision.woken)
            assertEquals("shouldReply", expect.bool("shouldReply"), decision.shouldReply)
            assertEquals("marksRead", expect.bool("marksRead"), decision.marksRead)

            // 被叫醒时的心情恢复（隔离在 MoodRecovery 里，同样要求两端一致）。
            val moodAfter = expect["moodAfter"] as? JsonObject
            if (moodAfter != null) {
                val mood = input.obj("mood")
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
    fun `defer plan cases`() {
        forEachCase("defer-plan") { _, input, expect ->
            val plan = DeferReply.plan(
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

    // ------------------------------------------------------------------ 3. 主动程度

    /** 固定「本地时间中午」：避免 MoodBehavior 的深夜折扣让结果随跑测试的时刻变化。 */
    private val localNoon: Long = LocalDate.of(2026, 1, 15)
        .atTime(12, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    @Test
    fun `proactive cap cases`() {
        forEachCase("proactive-cap") { _, input, expect ->
            val level = input.int("level")
            assertEquals("normalizedLevel", expect.int("normalizedLevel"), ProactiveLevel.normalize(level))
            val baseCap = ProactiveLevel.dailyCap(level)
            assertEquals("baseDailyCap", expect.int("baseDailyCap"), baseCap)
            assertEquals("heartbeatWeight", expect.double("heartbeatWeight"), ProactiveLevel.heartbeatWeight(level), 1e-9)
            assertEquals("description", expect.str("description"), ProactiveLevel.description(level))

            val mood = input.objOrNull("mood")
            val dailyCap = if (mood == null) {
                baseCap
            } else {
                val tuning = MoodBehavior.from(
                    MoodState(label = mood.str("label"), intensity = mood.int("intensity")),
                    now = localNoon
                )
                MoodBehavior.effectiveDailyCap(baseCap, tuning)
            }
            assertEquals("dailyCap（含心情修正）", expect.int("dailyCap"), dailyCap)
        }
    }

    // ------------------------------------------------------------------ 4. 心跳

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

            val perBeat = input.objOrNull("perBeat")
            if (perBeat != null) {
                val actual = Heartbeat.perBeatProbability(
                    perMinute = perBeat.double("perMinute"),
                    intervalMinutes = perBeat.double("intervalMinutes")
                )
                assertEquals("perBeatProbability", expect.double("perBeatProbability"), actual, TOLERANCE)
            }

            val fire = input.objOrNull("fire")
            if (fire != null) {
                val actual = Heartbeat.shouldFire(
                    probability = fire.double("probability"),
                    roll = fire.double("roll")
                )
                assertEquals("shouldFire", expect.bool("shouldFire"), actual)
            }

            val between = input.objOrNull("minutesBetween")
            if (between != null) {
                val actual = Heartbeat.minutesBetween(
                    lastResetAt = between.long("lastResetAt"),
                    now = between.long("now")
                )
                assertEquals("minutesBetween", expect.double("minutesBetween"), actual, TOLERANCE)
            }
        }
    }

    // ------------------------------------------------------------------ 5. 记忆

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

            expect["retentionFactor"]?.let {
                assertNotNull("用例写了 retentionFactor 却没给 retention", factor)
                assertEquals("retentionFactor", (it as JsonPrimitive).double, factor!!, TOLERANCE)
            }
            expect["effectiveStrength"]?.let {
                assertEquals(
                    "effectiveStrength",
                    (it as JsonPrimitive).double,
                    MemoryLifecycle.effectiveStrength(memory, now),
                    TOLERANCE
                )
            }
            expect["decayStep"]?.let {
                assertEquals(
                    "decayStep",
                    (it as JsonPrimitive).double,
                    MemoryLifecycle.decayStep(memory, now, factor ?: 1.0),
                    TOLERANCE
                )
            }
            expect["action"]?.let { element ->
                val action = MemoryLifecycle.nextStatus(memory, input.double("statusStrength"), now)
                assertActionMatches(memory.id, action, element as JsonObject)
            }
        }
    }

    private fun assertActionMatches(id: String, action: LifecycleAction, expect: JsonObject) {
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

    // ------------------------------------------------------------------ 6. 工具参数

    @Test
    fun `tool args cases`() {
        forEachCase("tool-args") { _, input, expect ->
            val tool = input.str("tool")
            val args = input.obj("args")
            val actual = clampArgs(tool, args)
            val expected = expect.obj("args")
            assertEquals("夹取后的参数（tool=$tool）", sortedText(expected), sortedText(actual))
        }
    }

    /**
     * 服务/执行器实际的夹取方式：只夹已知的数值键，其余键原样保留，缺省值补在结果里。
     * 这份映射与 `agentd` 的 `/v1/core/tool-args` 必须是同一份规则。
     */
    private fun clampArgs(tool: String, args: JsonObject): JsonObject {
        val out = args.toMutableMap()
        when (tool) {
            "remember" -> out["importance"] = JsonPrimitive(
                ToolArgClamp.clampImportance(args.intOrNull("importance") ?: ToolArgClamp.IMPORTANCE_DEFAULT)
            )
            "web_search" -> out["count"] = JsonPrimitive(
                ToolArgClamp.clampCount(args.intOrNull("count") ?: ToolArgClamp.COUNT_DEFAULT)
            )
            "defer_reply" -> out["minutes"] = JsonPrimitive(
                ToolArgClamp.clampDeferMinutes(args.intOrNull("minutes") ?: ToolArgClamp.MINUTES_DEFAULT)
            )
            else -> error("用例里出现未知 tool：$tool")
        }
        return JsonObject(out)
    }

    /** 按键名排序后序列化，避免对象键顺序影响比较。 */
    private fun sortedText(obj: JsonObject): String =
        obj.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${it.key}=${it.value}" }

    // ------------------------------------------------------------------ JSON 辅助

    private fun JsonObject.obj(key: String): JsonObject =
        this[key] as? JsonObject ?: error("缺少对象字段 $key")

    private fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            ?: error("缺少字符串字段 $key")

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: error("缺少布尔字段 $key")

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: error("缺少整数字段 $key")

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: error("缺少长整数字段 $key")

    private fun JsonObject.double(key: String): Double {
        val raw = this[key] as? JsonPrimitive ?: error("缺少数字字段 $key")
        return raw.doubleOrNull() ?: raw.content.toDoubleOrNull() ?: error("字段 $key 不是数字")
    }

    private fun JsonPrimitive.doubleOrNull(): Double? = content.toDoubleOrNull()

    private val JsonPrimitive.double: Double get() = content.toDouble()

    private companion object {
        /** 浮点断言容差：exp() 在 JVM 与 Darwin libm 上可能差最后一个二进制位。 */
        const val TOLERANCE = 1e-9
    }
}
