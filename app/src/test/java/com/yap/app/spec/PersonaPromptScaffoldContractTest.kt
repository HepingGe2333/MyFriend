package com.yap.app.spec

import com.yap.app.domain.ActivityAvailability
import com.yap.app.domain.ActivityKind
import com.yap.app.domain.PersonaActivity
import com.yap.app.domain.PersonaCard
import com.yap.app.domain.PromptBuilder
import com.yap.app.domain.SpeechStyle
import com.yap.app.domain.WorldPreset
import com.yap.app.domain.WorldRelationship
import com.yap.app.domain.WorldScheduleEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * 人设引导脚手架的契约测试（Android 侧）。
 *
 * 规范文件是仓库根 `spec/persona-guidance/scaffold.json`：它规定了两端系统提示词里
 * **逐字一致**的中文（§1 / §6.3）。Android 的副本在 `PromptBuilder.Scaffold`；
 * iOS 侧的副本（`ios/` 下的 `PromptBuilder.swift`）由另一个任务按同一份 JSON 同步，
 * 所以这里**不读 iOS 源码**——「两端一致」等价于「各自的常量都等于 spec」，spec 是唯一的裁判。
 *
 * 为什么值得一个专门的测试：提示词是产品行为，改一个「，」或一个空格，模型的表现就可能变；
 * 而这种字符串漂移靠人眼 review 几乎发现不了。这里做三件事：
 *
 * 1. 双向比对：spec 的每个键在 Kotlin 里都有常量，Kotlin 里也没有 spec 之外的键，
 *    每个值逐字相等（差异会打印到具体第几个字符、两边的原文片段）；
 * 2. 运行时输出：至少一份「全量」和一份「极简」输入，逐行断言脚手架真的被用上了
 *    （占位符换成了这次输入里的真实值）；
 * 3. 占位符集合：spec 里出现的 `{name}` 之类的键必须都是 [PromptBuilder] 会填的，
 *    否则提示词里会留下一个生写进去的 `{foo}`。
 *
 * 文件路径怎么找：与 `AgentCoreSpecTest` 同一套办法——从 `user.dir` 往上找同时有
 * `settings.gradle.kts` 与 `spec/persona-guidance/scaffold.json` 的目录；找不到/读不到就
 * 直接失败并说清楚原因，不硬编码绝对路径，也不静默跳过（跳过等于没有契约）。
 */
class PersonaPromptScaffoldContractTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-11-18 是周三；19:40 对齐规范 §6.3 的实测场景。 */
    private val now = ZonedDateTime.of(2026, 11, 18, 19, 40, 0, 0, zone).toInstant().toEpochMilli()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val scaffold get() = PromptBuilder.Scaffold

    // ------------------------------------------------------------------ 路径

    private fun findRepoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile &&
                File(dir, "spec/persona-guidance/scaffold.json").isFile
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        error(
            "找不到仓库根目录：从 $start 向上都没有同时包含 settings.gradle.kts 与 " +
                "spec/persona-guidance/scaffold.json 的目录。" +
                "（Android 单测的 working dir 通常是 app/ 或仓库根；如果你的环境不同，请把 working dir 指到仓库内。）"
        )
    }

    private fun scaffoldRoot(): JsonObject {
        val file = File(findRepoRoot(), "spec/persona-guidance/scaffold.json")
        assertTrue("规范文件不存在：${file.absolutePath}", file.isFile)
        return json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
    }

    // -------------------------------------------------- 1. spec 与常量逐字比对

    @Test
    fun `脚手架每一行在 Kotlin 里都有常量 且逐字一致`() {
        assertScaffoldMatchesConstants()
    }

    /**
     * 把 spec 的 `sections` + `lines` 摊成「键 → 值（String 或 List<String>）」，
     * 与 [PromptBuilder.Scaffold.VALUES_BY_KEY] 双向比对。
     */
    private fun assertScaffoldMatchesConstants() {
        val root = scaffoldRoot()
        val expected = expectedValues(root)
        val actual = scaffold.VALUES_BY_KEY

        // 两个方向分开报：少了哪个键、多了哪个键，比一句 assertEquals 更好定位。
        val missing = expected.keys - actual.keys
        val extra = actual.keys - expected.keys
        assertTrue(
            "PromptBuilder.Scaffold 缺少这些 spec 键的常量：$missing" +
                "（spec 有的键必须有同名常量，命名规则：sections → SECTION_<KEY>，lines → <KEY>）",
            missing.isEmpty()
        )
        assertTrue(
            "PromptBuilder.Scaffold 有 spec 里没有的键：$extra" +
                "（常量表必须和 spec/persona-guidance/scaffold.json 的键集合完全相等）",
            extra.isEmpty()
        )
        expected.forEach { (key, want) -> assertSameValue(key, want, actual.getValue(key)) }
    }

    /** spec 里的字符串列表（`voice_rules` / `boundary_rules`）与单行常量都保持原始字符。 */
    private fun expectedValues(root: JsonObject): Map<String, Any> {
        val sections = root.obj("sections")
        val lines = root.obj("lines")
        val out = linkedMapOf<String, Any>()
        sections.forEach { (key, value) -> out[key] = text(value, "sections.$key") }
        lines.forEach { (key, value) ->
            out[key] = when (value) {
                is JsonArray -> value.mapIndexed { index, element -> text(element, "lines.$key[$index]") }
                else -> text(value, "lines.$key")
            }
        }
        assertTrue("scaffold.json 的 sections/lines 不能为空", out.isNotEmpty())
        return out
    }

    /** 逐字比较；不一致时打印第一个差异字符与两边片段，避免「一大段字符串不相等」这种废话。 */
    private fun assertSameValue(key: String, expected: Any, actual: Any) {
        if (expected is List<*> && actual is List<*>) {
            assertEquals("$key 的条数不一致：spec=${expected.size} 常量=${actual.size}", expected.size, actual.size)
            expected.forEachIndexed { index, want ->
                assertSameText("$key[$index]", want as String, actual[index] as String)
            }
            return
        }
        if (expected is String && actual is String) {
            assertSameText(key, expected, actual)
            return
        }
        fail("$key 的类型不一致：spec=${expected::class.java.simpleName} 常量=${actual::class.java.simpleName}")
    }

    private fun assertSameText(label: String, expected: String, actual: String) {
        if (expected == actual) return
        val index = expected.zip(actual).indexOfFirst { (a, b) -> a != b }
            .let { if (it >= 0) it else minOf(expected.length, actual.length) }
        val from = (index - 12).coerceAtLeast(0)
        val specSlice = expected.substring(from, (index + 12).coerceAtMost(expected.length))
        val kotlinSlice = actual.substring(from, (index + 12).coerceAtMost(actual.length))
        val detail = if (index < expected.length && index < actual.length) {
            "spec 第 $index 个字符是 U+%04X，常量是 U+%04X".format(
                Locale.ROOT, expected[index].code, actual[index].code
            )
        } else {
            "长度不同：spec=${expected.length} 个字符，常量=${actual.length} 个字符，从第 $index 个字符起分叉"
        }
        fail(
            """
            [$label] 与 spec/persona-guidance/scaffold.json 不一致（第 $index 个字符起）。
            spec  : 「$specSlice」
            kotlin: 「$kotlinSlice」
            $detail
            （两端必须逐字一致；只能改 PromptBuilder.Scaffold 或改 spec，不能只改一边。）
            """.trimIndent()
        )
    }

    // ---------------------------------------------- 2. 运行时输出（全量输入）

    private fun fullInputs(): PromptBuilder.Inputs = PromptBuilder.Inputs(
        name = NAME,
        age = AGE,
        role = ROLE,
        relation = "朋友",
        address = "阿深",
        card = PersonaCard(bond = BOND),
        world = WorldPreset(
            worldName = "临港新城·云筑建筑事务所",
            era = ERA,
            description = DESCRIPTION,
            schedule = listOf(WorldScheduleEntry("工作日", "09:00", "18:30", "在事务所画图、审图")),
            relationships = listOf(WorldRelationship("母亲", "母亲", "每周三晚打电话", intimacy = 4)),
            obligations = listOf("按节点交图"),
            notes = NOTES
        ),
        speech = SpeechStyle(
            tone = TONE,
            phrases = listOf("在忙，晚点说", "别想太多", "嗯，知道了")
        ),
        activity = PersonaActivity(
            kind = ActivityKind.WORK,
            activity = "在事务所赶第三版方案",
            until = now + 90 * 60_000L,
            availability = ActivityAvailability.BUSY
        ),
        now = now,
        zone = zone,
        moodBlock = "\n\n## 你现在的心情\n有点累（强度 3/5，明显）。"
    )

    @Test
    fun `完整输入的系统提示词用上了脚手架每一行`() {
        val text = PromptBuilder.system(fullInputs())

        val expectedLines = listOf(
            scaffold.SECTION_IDENTITY,
            scaffold.IDENTITY_BODY_WITH_META
                .replace("{name}", NAME)
                .replace("{meta}", "$AGE，$ROLE"),
            scaffold.SECTION_WORLD,
            scaffold.WORLD_NAME.replace("{worldName}", WORLD_NAME),
            scaffold.WORLD_INTRO.replace("{era}", ERA).replace("{description}", DESCRIPTION),
            scaffold.WORLD_NOTES.replace("{notes}", NOTES),
            scaffold.WORLD_HINT,
            scaffold.SECTION_STATE,
            // 2026-11-18 是周三；now 就是 19:40。
            // `spec/context-time/README.md` 要求首行同时带日期与时区偏移；脚手架模板不变，
            // 只是两个占位符的填充内容加宽（见 PromptBuilder.clockLine 的注释）。
            scaffold.STATE_LINE.replace("{weekday}", "2026-11-18 周三")
                .replace("{time}", "19:40（UTC+08:00）"),
            scaffold.STATE_BUSY
                .replace("{activity}", ACTIVITY_WORD)
                .replace("{until}", "21:10"),
            scaffold.STATE_MOOD.replace("{mood}", "有点累").replace("{intensity}", "3"),
            scaffold.SECTION_VOICE,
            scaffold.VOICE_TONE.replace("{tone}", TONE),
            scaffold.VOICE_PHRASES.replace(
                "{phrases}",
                "「在忙，晚点说」、「别想太多」、「嗯，知道了」"
            ),
            scaffold.VOICE_NO_NARRATION,
            scaffold.SECTION_RELATIONSHIP,
            scaffold.SECTION_BOUNDARIES
        ) + scaffold.VOICE_RULES + scaffold.BOUNDARY_RULES

        expectedLines.forEach { line ->
            assertTrue("提示词缺少脚手架行：\n「$line」\n\n实际提示词：\n$text", text.contains(line))
        }
        // 世界段的子弹行是数据格式（不是规范文案），但格式也在这里订住。
        assertTrue(text.contains("- 工作日 09:00-18:30 在事务所画图、审图"))
        assertTrue(text.contains("- 母亲（母亲）：每周三晚打电话，亲密度 4/5"))
        assertTrue(text.contains("- 必须：按节点交图"))
        assertTrue("提示词里不该留下未替换的占位符", !text.contains("{"))
        assertSectionsInSpecOrder(text)
        assertBoundariesLast(text)
    }

    // ---------------------------------------------- 3. 运行时输出（极简输入）

    private fun minimalInputs(): PromptBuilder.Inputs =
        PromptBuilder.Inputs(name = "阿澄", now = now, zone = zone)

    @Test
    fun `极简输入也用脚手架 空世界写 world_empty 而不是空段`() {
        val text = PromptBuilder.system(minimalInputs())

        val expectedLines = listOf(
            scaffold.SECTION_IDENTITY,
            scaffold.IDENTITY_BODY.replace("{name}", "阿澄"),
            scaffold.SECTION_WORLD,
            scaffold.WORLD_EMPTY,
            scaffold.SECTION_STATE,
            // `spec/context-time/README.md` 要求首行同时带日期与时区偏移；脚手架模板不变，
            // 只是两个占位符的填充内容加宽（见 PromptBuilder.clockLine 的注释）。
            scaffold.STATE_LINE.replace("{weekday}", "2026-11-18 周三")
                .replace("{time}", "19:40（UTC+08:00）"),
            scaffold.STATE_IDLE,
            scaffold.SECTION_VOICE,
            scaffold.VOICE_NO_NARRATION,
            scaffold.SECTION_RELATIONSHIP,
            scaffold.RELATIONSHIP_DEFAULT,
            scaffold.SECTION_BOUNDARIES
        ) + scaffold.VOICE_RULES + scaffold.BOUNDARY_RULES

        expectedLines.forEach { line ->
            assertTrue("提示词缺少脚手架行：\n「$line」\n\n实际提示词：\n$text", text.contains(line))
        }
        // 没有世界预设时只能有 world_empty 一行，不能留下一个只有标题的空段。
        val worldBody = text.substringAfter(scaffold.SECTION_WORLD + "\n").substringBefore("\n\n").trim()
        assertEquals("没有世界预设时 world 段应只有一行 world_empty", scaffold.WORLD_EMPTY, worldBody)
        // 身份段也只有脚手架那一行（没有 meta、没人设卡正文）。
        val identityBody = text.substringAfter(scaffold.SECTION_IDENTITY + "\n").substringBefore("\n\n").trim()
        assertEquals(
            "没有年龄/身份时身份段只写 identity_body",
            scaffold.IDENTITY_BODY.replace("{name}", "阿澄"),
            identityBody
        )
        assertTrue("提示词里不该留下未替换的占位符", !text.contains("{"))
        assertSectionsInSpecOrder(text)
        assertBoundariesLast(text)
    }

    // ----------------------------- 3.5 world_name：非空才输出、顺序固定

    @Test
    fun `world_name 行只在 worldName 非空时出现 且在世界段最前`() {
        // 全量输入：worldName 非空 → 必须用脚手架模板渲染。
        val full = PromptBuilder.system(fullInputs())
        val expectedLine = scaffold.WORLD_NAME.replace("{worldName}", WORLD_NAME)
        assertTrue("worldName 非空时必须输出 world_name 行：\n$full", full.contains(expectedLine))
        val worldBody = full.substringAfter(scaffold.SECTION_WORLD + "\n").substringBefore("\n\n")
        assertTrue(
            "world_name 必须是世界段第一行（顺序：world_name → intro → 日程 → … → hint）",
            worldBody.startsWith(expectedLine)
        )

        // worldName 为空 → 整行不出现（即使其它世界字段都在）。
        val noName = PromptBuilder.system(
            fullInputs().copy(world = WorldPreset(era = ERA, description = DESCRIPTION))
        )
        assertFalse("worldName 为空时不该出现「你所在的地方：」：\n$noName", noName.contains("你所在的地方："))

        // 只填 worldName：仍然算有世界（§2.1），至少渲染 world_name + world_hint，不退回 world_empty。
        val nameOnly = PromptBuilder.system(
            fullInputs().copy(world = WorldPreset(worldName = "某个城市"))
        )
        assertTrue(nameOnly, nameOnly.contains(scaffold.WORLD_NAME.replace("{worldName}", "某个城市")))
        assertTrue(nameOnly, nameOnly.contains(scaffold.WORLD_HINT))
        assertFalse(nameOnly, nameOnly.contains(scaffold.WORLD_EMPTY))
        assertSectionsInSpecOrder(nameOnly)
    }

    // ------------------------------------- 4. 「与 iOS 差异为 0」= 只由 spec 决定

    /**
     * iOS 侧由另一个任务按 `scaffold.json` 同步；这里能证明的是「Android 侧没有任何规范之外的
     * 中文固定文案」，也就是两端差异只能来自 spec 本身（而不是某一端私加了一句）。
     *
     * 具体做三件事：
     * - 常量表与 spec 的键/值完全相等（同第 1 条，但这里作为「零差异」的显式断言重复跑一次，
     *   避免以后有人把第 1 条改成宽松比较）；
     * - spec 里出现的占位符集合必须恰好是 PromptBuilder 会填的那一组，
     *   否则「按 spec 渲染」这件事根本做不到（会漏出一个 `{foo}`）；
     * - 段标题唯一，运行时顺序与 spec 的声明顺序一致。
     */
    @Test
    fun `与 iOS 的差异为 0 只由 scaffold json 决定`() {
        assertScaffoldMatchesConstants()

        val root = scaffoldRoot()
        val allText = expectedValues(root).values.joinToString("\n") { value ->
            if (value is List<*>) value.joinToString("\n") { it as String } else value as String
        }
        val declared = root.obj("sections").entries.map { (key, element) -> text(element, "sections.$key") }
        assertEquals("段标题不能重复，否则运行时无法用标题定位段落", declared.size, declared.distinct().size)

        // PromptBuilder 会替换的占位符：多一个（填不了 → 提示词里留 `{foo}`）或少一个都算漂移。
        val fillable = setOf(
            "name", "meta", "worldName", "era", "description", "notes",
            "weekday", "time", "activity", "until",
            "mood", "intensity", "tone", "phrases"
        )
        val usedPlaceholders = PLACEHOLDER.findAll(allText).map { it.groupValues[1] }.toSet()
        assertEquals(
            "scaffold.json 里的占位符必须恰好是 PromptBuilder 会填的那一组；" +
                "多了会漏出未替换的 {…}，少了说明 spec 改了没人跟",
            fillable,
            usedPlaceholders
        )
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 段的**文案**来自 scaffold.json，段的**顺序**自 0.15 起由
     * `spec/context-profile/README.md` 的 state_first 决定：
     *
     * 状态 → 引导 → 人设（身份/世界/话风/关系）→ 工具 → 反 AI →（记忆）→ 语言 → 边界。
     *
     * 这是本测试与 spec 唯一的分歧点：scaffold.json 里 `sections` 的书写顺序还是
     * 「身份 → 世界 → 状态 → 话风 → 关系」（0.14 的协议）。0.15 的 profile 只调顺序、
     * 不改任何一句文案，所以「逐字一致」这条仍然成立；顺序按新 profile 断言。
     */
    private fun assertSectionsInSpecOrder(text: String) {
        val order = listOf(
            scaffold.SECTION_STATE,
            PromptBuilder.SECTION_GUIDANCE,
            scaffold.SECTION_IDENTITY,
            scaffold.SECTION_WORLD,
            scaffold.SECTION_VOICE,
            scaffold.SECTION_RELATIONSHIP,
            scaffold.SECTION_BOUNDARIES
        )
        val positions = order.map { header ->
            val index = text.indexOf(header)
            assertTrue("缺少段落标题「$header」：\n$text", index >= 0)
            assertEquals("段落标题「$header」出现了不止一次", 1, Regex(Regex.escape(header)).findAll(text).count())
            index
        }
        assertEquals("段落顺序必须是 state_first（状态 → 引导 → 人设 → … → 边界）", positions.sorted(), positions)
    }

    private fun assertBoundariesLast(text: String) {
        assertTrue(
            "边界段必须是最后一段、并以脚手架最后一条边界规则收尾",
            text.trimEnd().endsWith("- " + scaffold.BOUNDARY_RULES.last())
        )
        assertFalse(
            "边界段之后不该再有别的段",
            text.substringAfter(scaffold.SECTION_BOUNDARIES).contains("\n# ")
        )
    }

    private fun JsonObject.obj(key: String): JsonObject =
        this[key] as? JsonObject ?: error("scaffold.json 缺少对象字段 $key")

    private fun text(element: JsonElement, label: String): String =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("scaffold.json 的 $label 不是字符串：$element")

    private companion object {
        const val NAME = "林深"
        const val AGE = "28 岁"
        const val ROLE = "建筑事务所项目负责人"
        const val WORLD_NAME = "临港新城·云筑建筑事务所"
        const val ERA = "当代，中国沿海新一线城市，2026 年"
        const val DESCRIPTION = "他带着六人小组负责一个高层住宅项目，加班是常态。"
        const val NOTES = "这个城市的雨季很长，事务所楼下只有一家便利店。"
        const val BOND = "你们认识两年了，他不太主动，但会记得你说过的事。"
        const val TONE = "简短直接，偏冷淡务实"
        /** 活动数据自己带「在」，填进「你在{activity}」时要先摘掉，否则会变成「你在在…」。 */
        const val ACTIVITY_WORD = "事务所赶第三版方案"

        /** 占位符的形状固定是 `{camelCase/pascalCase 键名}`（`worldName` 必须被认出来）。 */
        val PLACEHOLDER = Regex("\\{([a-zA-Z_]+)\\}")
    }
}
