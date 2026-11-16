package com.yap.app.spec

import com.yap.app.domain.RankInput
import com.yap.app.domain.SearchFilter
import com.yap.app.domain.SearchRanking
import com.yap.app.domain.SearchSnippet
import com.yap.app.domain.Segment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「两端必须一致」的聊天记录检索契约测试（Android 侧）。
 *
 * 用例住在仓库根 `spec/search/cases/` 下：
 *
 * - `snippet.json`：摘要/高亮规则的输入输出，纯函数，逐条断言；
 * - `ranking.json`：命中与排序规则，用例里连 `filter` 都写好了（`personaId` / `since`）。
 *
 * iOS 侧 `SearchSpecTests.swift` 读的是**同一批文件**。谁改了实现没同步另一端，
 * 两边必有一边立刻变红 —— 这正是契约文件存在的意义。
 *
 * 为什么路径要从 `user.dir` 往上找：Gradle 跑单测时工作目录通常是 `app/`，IDE 里可能
 * 是仓库根。硬编码绝对路径在别人的机器上一定挂，所以逐级向上找「同时有
 * `settings.gradle.kts` 与 `spec/search/cases`」的目录（和 `AgentCoreSpecTest` 一致）。
 */
class SearchSpecTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------------ 路径与用例

    private fun findRepoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile &&
                File(dir, "spec/search/cases").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        error(
            "找不到仓库根目录：从 $start 向上都没有同时包含 settings.gradle.kts 与 spec/search/cases 的目录。" +
                "（Android 单测的 working dir 通常是 app/ 或仓库根。）"
        )
    }

    private fun cases(name: String): JsonArray {
        val file = File(findRepoRoot(), "spec/search/cases/$name.json")
        assertTrue("用例文件不存在：${file.absolutePath}", file.isFile)
        val root = json.parseToJsonElement(file.readText(Charsets.UTF_8))
        val array = root as? JsonArray
            ?: error("$name.json 的顶层应该是数组（不是对象），实际是 ${root::class.simpleName}")
        assertTrue("$name.json 一条用例都没有", array.isNotEmpty())
        return array
    }

    /** 逐条跑，失败信息带上用例名，方便直接定位是哪一个契约对不上。 */
    private fun forEachCase(name: String, block: (entry: JsonObject) -> Unit) {
        cases(name).forEach { element ->
            val entry = element as JsonObject
            val caseName = entry.str("name")
            try {
                block(entry)
            } catch (e: AssertionError) {
                throw AssertionError("[$name.json / $caseName] ${e.message}", e)
            }
        }
    }

    // ------------------------------------------------------------------ 1. 摘要与高亮

    @Test
    fun `snippet 契约用例全部通过`() {
        forEachCase("snippet") { entry ->
            val input = entry.obj("input")
            val expect = entry.obj("expect")
            val content = input.str("content")
            val query = input.str("query")
            val window = input.intOrNull("window") ?: SearchSnippet.WINDOW

            val actual = SearchSnippet.build(content, query, window)

            assertEquals("text", expect.str("text"), actual.text)
            assertEquals("highlights（规格是 [start, end) 半开区间，Kotlin 用闭区间）", expect.ranges("highlights"), actual.highlights)
        }
    }

    @Test
    fun `摘要切成的片段拼回去等于原文`() {
        forEachCase("snippet") { entry ->
            val input = entry.obj("input")
            val snippet = SearchSnippet.build(input.str("content"), input.str("query"), input.intOrNull("window") ?: SearchSnippet.WINDOW)
            val segments = SearchSnippet.segments(snippet)
            assertEquals("片段拼起来必须还原 text", snippet.text, segments.joinToString("") { it.text })
            assertFalse("空摘要不该产生片段", snippet.text.isEmpty() && segments.isNotEmpty())
            assertEquals(
                "高亮的片段必须正好覆盖 highlights",
                snippet.highlights.map { snippet.text.substring(it.first, it.last + 1) },
                segments.filter { it.highlighted }.map { it.text }
            )
        }
    }

    // ------------------------------------------------------------------ 2. 命中与排序

    @Test
    fun `ranking 契约用例全部通过`() {
        forEachCase("ranking") { entry ->
            val input = entry.obj("input")
            val expect = entry.obj("expect")
            val messages = (input["messages"] as JsonArray).map { element ->
                val message = element as JsonObject
                RankInput(
                    id = message.str("id"),
                    content = message.str("content"),
                    createdAt = message.long("createdAt"),
                    personaId = message.stringOrNull("personaId")
                )
            }
            val filter = input.objOrNull("filter")?.let { raw ->
                SearchFilter(
                    personaId = raw.stringOrNull("personaId"),
                    since = raw.longOrNull("since")
                )
            }
            val expected = (expect["order"] as JsonArray).map { (it as JsonPrimitive).asString() }

            val actual = SearchRanking.rank(messages, input.str("query"), filter).map { it.id }

            assertEquals("排序结果", expected, actual)
        }
    }

    // ------------------------------------------------------------------ 3. 实现层单测

    @Test
    fun `多段高亮切成相邻片段`() {
        val snippet = SearchSnippet.build("他说哈尔滨冷，我说哈尔滨也行。", "哈尔滨")
        assertEquals(listOf(2..4, 9..11), snippet.highlights)
        assertEquals(
            listOf(
                Segment("他说", false),
                Segment("哈尔滨", true),
                Segment("冷，我说", false),
                Segment("哈尔滨", true),
                Segment("也行。", false)
            ),
            SearchSnippet.segments(snippet)
        )
    }

    @Test
    fun `高亮偏移按 UTF-16 code unit 计`() {
        // 每个 emoji 占 2 个 code unit：3 个 emoji = 6，所以「哈尔滨」从下标 6 开始。
        val content = "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00哈尔滨\uD83D\uDE00\uD83D\uDE00"
        val snippet = SearchSnippet.build(content, "哈尔滨")
        assertEquals(content, snippet.text)
        assertEquals(listOf(6..8), snippet.highlights)
        assertEquals("哈尔滨", snippet.text.substring(6, 9))
    }

    @Test
    fun `超长正文的摘要长度有上界且偏移正确`() {
        val content = "a".repeat(100_000) + "哈尔滨" + "b".repeat(1_000)
        val snippet = SearchSnippet.build(content, "哈尔滨")
        assertTrue(
            "摘要最多 window + 两个省略号，实际 ${snippet.text.length}",
            snippet.text.length <= SearchSnippet.WINDOW + 2
        )
        assertEquals(1, snippet.highlights.size)
        val range = snippet.highlights.single()
        assertEquals("哈尔滨", snippet.text.substring(range.first, range.last + 1))
        assertTrue("左侧还有上下文却被当成开头", snippet.text.startsWith("…"))
        assertTrue("右侧还有上下文却被当成结尾", snippet.text.endsWith("…"))
    }

    @Test
    fun `超长正文没有命中时只给开头一段`() {
        val content = "x".repeat(10_000)
        val snippet = SearchSnippet.build(content, "哈尔滨")
        assertEquals("x".repeat(SearchSnippet.WINDOW) + "…", snippet.text)
        assertTrue(snippet.highlights.isEmpty())
    }

    @Test
    fun `重叠出现只高亮不重叠的那一处`() {
        val snippet = SearchSnippet.build("aaa", "aa")
        assertEquals("aaa", snippet.text)
        assertEquals(listOf(0..1), snippet.highlights)
    }

    @Test
    fun `边界输入不抛异常`() {
        assertEquals("", SearchSnippet.build("", "哈尔滨").text)
        assertEquals("", SearchSnippet.build("正文", "").text)
        assertEquals("", SearchSnippet.build("正文", "   ").text)
        assertEquals("", SearchSnippet.build("正文", "正文", window = 0).text)
        assertTrue(SearchSnippet.segments(SearchSnippet.build("", "x")).isEmpty())
        assertEquals(0, SearchRanking.matchedTerms("", "正文"))
        assertEquals(emptyList<RankInput>(), SearchRanking.rank(emptyList(), "harbin"))
    }

    @Test
    fun `中文词元命中不会因为虚词而漏`() {
        // 「项目压力」的两字词元里含「压力」；正文写的是「压得我睡不着」，字面不包含整句 query。
        assertTrue(SearchRanking.isHit("项目压力", "最近项目压得我睡不着"))
        assertTrue(SearchRanking.isHit("harbin", "Harbin in January"))
        assertFalse(SearchRanking.isHit("harbin", "totally unrelated"))
    }

    // ------------------------------------------------------------------ JSON 辅助

    private fun JsonObject.obj(key: String): JsonObject =
        this[key] as? JsonObject ?: error("缺少对象字段 $key")

    private fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.asString()
            ?: error("缺少字符串字段 $key")

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.asString()

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: error("缺少整数字段 $key")

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: error("缺少长整数字段 $key")

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    private fun JsonPrimitive.asString(): String = content

    /**
     * 用例里的高亮是 `[start, end]` 两个整数（规格写的是 `[start, end)`）。
     * 转成 Kotlin 的闭区间 `start..(end - 1)` —— 这个 -1 是整份契约里最容易写错的一格。
     */
    private fun JsonObject.ranges(key: String): List<IntRange> {
        val array = this[key] as? JsonArray ?: error("缺少数组字段 $key")
        return array.map { element ->
            val pair = element as JsonArray
            assertEquals("highlight 必须是 [start, end] 两个整数", 2, pair.size)
            val start = (pair[0] as JsonPrimitive).int
            val end = (pair[1] as JsonPrimitive).int
            start..(end - 1)
        }
    }
}
