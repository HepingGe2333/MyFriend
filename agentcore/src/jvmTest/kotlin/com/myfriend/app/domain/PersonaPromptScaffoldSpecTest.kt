package com.myfriend.app.domain

import com.myfriend.app.core.json.Json
import com.myfriend.app.core.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **逐字一致**契约测试：`agentcore` 里的脚手架常量必须与 `spec/persona-guidance/scaffold.json`
 * 一个字都不差。
 *
 * 为什么不放在 `commonTest`：`commonTest` 会被 iOS target（`iosSimulatorArm64Test`）一起编译，
 * 而 Kotlin/Native 的标准库里没有文件 API；用 `expect/actual` 又要改 `jvmMain`/`iosMain`。
 * 所以把「读仓库文件」这件事放在 JVM 测试源集（与既有的 `AgentCoreSpecTest` 同一个做法），
 * `:agentcore:test`（= `jvmTest`）一定会跑它。行为断言仍在 `commonTest` 里（两端同跑）。
 *
 * 链起来的关系：
 *
 * ```
 * spec/persona-guidance/scaffold.json  ←(本文件逐字比对)→  Section.all / Line.all / Line.arrays
 *                                                              ↓(PersonaPromptScaffoldTest 断言)
 *                                                        build() 的输出
 * ```
 *
 * 找不到仓库根目录/JSON 文件时 **assume 跳过**（不把一个「环境问题」变成红），
 * 但 `:agentcore:test` 从模块目录往上找一定找得到。
 */
class PersonaPromptScaffoldSpecTest {

    // ------------------------------------------------------------------ 定位与读取

    private fun scaffoldFile(): File? {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val candidate = File(dir, "spec/persona-guidance/scaffold.json")
            if (File(dir, "settings.gradle.kts").isFile && candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun scaffold(): JsonValue.Obj {
        val file = scaffoldFile()
        assumeTrue(
            "找不到 spec/persona-guidance/scaffold.json（从 ${System.getProperty("user.dir")} 向上找）；跳过逐字比对",
            file != null
        )
        return Json.parse(file!!.readText(Charsets.UTF_8)) as JsonValue.Obj
    }

    private fun JsonValue.Obj.strField(key: String): String =
        (fields[key] as? JsonValue.Str)?.value ?: error("scaffold.json 的字段 $key 不是字符串")

    private fun JsonValue.Obj.objField(key: String): JsonValue.Obj =
        fields[key] as? JsonValue.Obj ?: error("scaffold.json 的字段 $key 不是对象")

    private fun scalarLines(json: JsonValue.Obj): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for ((key, value) in json.fields) {
            if (value is JsonValue.Str) result[key] = value.value
        }
        return result
    }

    private fun arrayLines(json: JsonValue.Obj): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        for ((key, value) in json.fields) {
            if (value is JsonValue.Arr) {
                result[key] = value.items.map { item ->
                    (item as? JsonValue.Str)?.value ?: error("scaffold.json 的 $key 里有非字符串元素")
                }
            }
        }
        return result
    }

    // ------------------------------------------------------------------ 逐字比对

    @Test
    fun `section titles are verbatim`() {
        val json = scaffold()
        val sections = json.objField("sections")
        val expected = sections.fields.mapValues { (key, value) ->
            (value as? JsonValue.Str)?.value ?: error("scaffold.json 的 sections.$key 不是字符串")
        }
        assertEquals(
            "Section.all 必须与 scaffold.json 的 sections 逐字一致（键名与顺序也算）",
            expected,
            PersonaPromptScaffold.Section.all
        )
        assertEquals(
            "段标题的键集合不能多也不能少",
            expected.keys.toList(),
            PersonaPromptScaffold.Section.all.keys.toList()
        )
    }

    @Test
    fun `scalar line templates are verbatim`() {
        val json = scaffold().objField("lines")
        assertEquals(
            "Line.all 必须与 scaffold.json 的 lines（字符串项）逐字一致",
            scalarLines(json),
            PersonaPromptScaffold.Line.all
        )
    }

    @Test
    fun `array line templates are verbatim`() {
        val json = scaffold().objField("lines")
        assertEquals(
            "Line.arrays 必须与 scaffold.json 的 lines（数组项）逐字一致（顺序也算）",
            arrayLines(json),
            PersonaPromptScaffold.Line.arrays
        )
        // 两条数组行都要在 JSON 里出现，避免「测试写死了但 spec 改了」的假绿。
        assertTrue("spec 里必须有 voice_rules", json.fields.containsKey("voice_rules"))
        assertTrue("spec 里必须有 boundary_rules", json.fields.containsKey("boundary_rules"))
    }

    @Test
    fun `scaffold json version is the one this class implements`() {
        val json = scaffold()
        assertEquals("脚手架版本变了就要同步改代码与测试", 1L, (json.fields["version"] as? JsonValue.Num)?.asLong)
    }

    // ------------------------------------------------------------------ 输出覆盖每一条模板

    /** 把模板里的占位符按规范语义填上（与 commonTest 的取值同一套）。 */
    private fun filled(template: String): String {
        val values = mapOf(
            "name" to "林深",
            "meta" to "26 岁，建筑师",
            "worldName" to "临港新城·云筑建筑事务所",
            "era" to "当代，中国沿海新一线城市，2026 年",
            "description" to "在事务所画图、审图",
            "notes" to "周三是母亲打电话的日子，别问设计上的事。",
            "weekday" to "周日",
            "time" to "19:40",
            "activity" to "事务所赶第三版方案",
            "until" to "21:30",
            "mood" to "有点累",
            "intensity" to "3",
            "tone" to "简短直接，偏冷淡务实",
            "phrases" to "「在忙，晚点说」、「别想太多」"
        )
        var text = template
        for ((key, value) in values) text = text.replace("{$key}", value)
        return text
    }

    private fun fullInput(availability: PromptAvailability = PromptAvailability.BUSY) = PromptScaffoldInput(
        name = "林深",
        meta = "26 岁，建筑师",
        world = PromptWorld(
            worldName = "临港新城·云筑建筑事务所",
            era = "当代，中国沿海新一线城市，2026 年",
            description = "在事务所画图、审图",
            notes = "周三是母亲打电话的日子，别问设计上的事。"
        ),
        state = PromptState(
            weekday = "周日",
            time = "19:40",
            activity = "在事务所赶第三版方案",
            until = "21:30",
            availability = availability,
            mood = "有点累",
            moodIntensity = 3
        ),
        voice = PromptVoice(
            tone = "简短直接，偏冷淡务实",
            phrases = listOf("在忙，晚点说", "别想太多")
        )
    )

    private fun minimalInput() = PromptScaffoldInput(
        name = "林深",
        relationship = "你们从初中就认识。"
    )

    @Test
    fun `built prompt contains every section title and every line from the spec file`() {
        val json = scaffold()
        val sections = json.objField("sections").fields.values.map { (it as JsonValue.Str).value }
        val scalar = scalarLines(json.objField("lines"))
        val arrays = arrayLines(json.objField("lines"))

        // 三份输入把「同一条槽位的不同分支」都走一遍。
        val busy = PersonaPromptScaffold.build(fullInput(PromptAvailability.BUSY))
        val limited = PersonaPromptScaffold.build(fullInput(PromptAvailability.LIMITED))
        val minimal = PersonaPromptScaffold.build(minimalInput())
        val outputs = listOf(busy, limited, minimal)

        for (title in sections) {
            assertTrue("每份输出都要有段标题「$title」", outputs.all { it.contains(title) })
        }

        // 每一份输出各自「该有」的行：按槽位分组，避免把替代分支也算成必须出现。
        val groups = mapOf(
            busy to listOf(
                "identity_body_with_meta", "world_name", "world_intro", "world_notes", "world_hint",
                "state_line", "state_busy", "state_mood", "voice_tone", "voice_phrases",
                "voice_no_narration", "relationship_default"
            ),
            limited to listOf("state_limited"),
            minimal to listOf("identity_body", "world_empty", "state_idle")
        )
        val covered = LinkedHashSet<String>()
        for ((prompt, keys) in groups) {
            for (key in keys) {
                val template = scalar[key] ?: error("scaffold.json 里没有 $key")
                val expected = filled(template)
                assertTrue("提示词里少了 $key 渲染后的文本：\n$expected\n----- 实际 -----\n$prompt", prompt.contains(expected))
                covered += key
            }
        }
        assertEquals(
            "每条标量行模板都要被至少一份输出的断言覆盖（新增行模板必须补测试）",
            scalar.keys.sorted(),
            covered.sorted()
        )

        // 数组行：每一项都要以列表项出现在输出里，顺序不变。
        for ((key, items) in arrays) {
            val prompt = busy
            val positions = items.map { item ->
                val expected = "- $item"
                val index = prompt.indexOf(expected)
                assertTrue("提示词里少了 $key 的列表项：$expected\n$prompt", index >= 0)
                index
            }
            assertEquals("$key 的顺序必须与 spec 一致", positions.sorted(), positions)
            covered += key
        }
        assertEquals(
            "每条数组行模板也要被覆盖",
            (scalar.keys + arrays.keys).sorted(),
            covered.sorted()
        )

        // 反向：占位符必须全部被替换（没有一个 {key} 漏出来）。
        for (prompt in outputs) {
            assertFalse("还有没被替换的占位符：\n$prompt", prompt.contains("{"))
        }
    }
}
