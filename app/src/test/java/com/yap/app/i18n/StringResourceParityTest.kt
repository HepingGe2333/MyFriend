package com.yap.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 资源一致性守卫：`values/`（中文，默认）与 `values-en/`（英语）必须一一对应。
 *
 * 只做一件事，但做得比较硬：两边 key 集合完全相同、同一句的占位符完全相同、
 * 中文只有 other、英语必须有 other（可多 one）。少了或多了任何一条都会红，
 * 这样「加了中文忘了英文」不会漏到发布包里。
 */
class StringResourceParityTest {

    @Test
    fun `values 与 values-en 的 string key 完全一致`() {
        val zh = TestResources.strings("values")
        val en = TestResources.strings("values-en")

        assertFalse("values/ 里一条 string 都没读到，路径判断有问题", zh.isEmpty())
        assertEquals(
            "values/ 有、values-en/ 没有的 key：${(zh.keys - en.keys).sorted()}",
            emptySet<String>(),
            zh.keys - en.keys
        )
        assertEquals(
            "values-en/ 有、values/ 没有的 key：${(en.keys - zh.keys).sorted()}",
            emptySet<String>(),
            en.keys - zh.keys
        )
        assertEquals("两个语言的 string 条数不一致", zh.size, en.size)
    }

    @Test
    fun `plurals 与 integer 的 key 也完全一致`() {
        assertEquals(
            TestResources.plurals("values").keys,
            TestResources.plurals("values-en").keys
        )
        assertEquals(
            TestResources.keysOfType("integer", "values"),
            TestResources.keysOfType("integer", "values-en")
        )
    }

    @Test
    fun `同一个 key 的占位符两边一致`() {
        val zh = TestResources.strings("values")
        val en = TestResources.strings("values-en")
        val mismatched = (zh.keys intersect en.keys).mapNotNull { key ->
            val left = formatSlots(zh.getValue(key))
            val right = formatSlots(en.getValue(key))
            if (left == right) null else "$key zh=$left en=$right"
        }
        assertEquals("占位符对不上的条目：$mismatched", emptyList<String>(), mismatched)
    }

    /**
     * 没有位置参数时 `%s` / `%d` **互换**也会让 `String.format` 抛
     * `IllegalFormatConversionException`（安全审计 P2-10 的第二个缺口）。
     *
     * 以前的比较是「格式符集合」：`已用 %s，共 %d` 与 `%d used by %s` 的集合都是
     * `{%s, %d}`，于是绿；但按参数位置看，中文第 1 个是字符串、英文第 1 个是整数，
     * 运行时直接崩。现在比的是「参数位置 → 格式符」的映射。
     */
    @Test
    fun `无位置参数的格式符互换会被判红`() {
        assertNotEquals(
            "第 1 个参数在中文里是 %s、英文里是 %d，必须判红",
            formatSlots("已用 %s，共 %d"),
            formatSlots("%d used by %s")
        )
        assertEquals(
            "有位置参数时换了语序但参数位置不变，是合法的",
            formatSlots("已用 %1\$s，共 %2\$d"),
            formatSlots("%2\$d used by %1\$s")
        )
        assertEquals(
            "同一个位置重复引用同一个值也是合法的",
            formatSlots("%1\$d 条（共 %1\$d 条）"),
            formatSlots("%1\$d item(s) (of %1\$d)")
        )
    }

    @Test
    fun `plurals 的占位符两边一致且英语带 other`() {
        val zh = TestResources.plurals("values")
        val en = TestResources.plurals("values-en")
        val problems = buildList {
            (zh.keys intersect en.keys).forEach { key ->
                val zhItems = zh.getValue(key)
                val enItems = en.getValue(key)
                if (zhItems.keys != setOf("other")) {
                    add("$key 中文 plurals 只该有 other，实际 ${zhItems.keys}")
                }
                if ("other" !in enItems.keys) add("$key 英语 plurals 缺少 other")
                // 英文每个 quantity 里出现的格式符都必须与中文同位置同类型；
                // 少用某个占位符是合法的（`getQuantityString` 会忽略多余参数，例如 en 的
                // one = "once" 不再重复计数）。但**多出**或**位置/类型不同**会崩，必须判红。
                val expected = formatSlots(zhItems["other"].orEmpty())
                enItems.forEach { (quantity, text) ->
                    val actual = formatSlots(text)
                    val problemsForQuantity = actual.filter { (index, spec) -> expected[index] != spec }
                    if (problemsForQuantity.isNotEmpty()) {
                        add("$key[$quantity] 占位符与中文不一致 zh=$expected en=$actual")
                    }
                }
            }
        }
        assertEquals("plurals 有问题：$problems", emptyList<String>(), problems)
    }

    @Test
    fun `中文与英文都不该有空的文案`() {
        val problems = buildList {
            listOf("values", "values-en").forEach { qualifier ->
                TestResources.strings(qualifier).forEach { (key, text) ->
                    if (text.isBlank()) add("$qualifier/$key 是空的")
                }
                TestResources.plurals(qualifier).forEach { (key, items) ->
                    items.forEach { (quantity, text) ->
                        if (text.isBlank()) add("$qualifier/$key[$quantity] 是空的")
                    }
                }
            }
        }
        assertEquals("空文案：$problems", emptyList<String>(), problems)
    }

    @Test
    fun `语言目录都在 main res 下`() {
        assertTrue("找不到 values/ 目录", TestResources.valuesDir("values").isDirectory)
        assertTrue("找不到 values-en/ 目录", TestResources.valuesDir("values-en").isDirectory)
        assertTrue(
            "values-en/ 里没有 xml 文件",
            TestResources.xmlFiles("values-en").isNotEmpty()
        )
        // 目录本身在 res/ 下，说明测试读的是真的打包资源，不是测试自带的一份副本。
        assertTrue(TestResources.valuesDir("values").parentFile?.name == "res")
        assertTrue(File(TestResources.valuesDir("values").parentFile, "xml/locales_config.xml").isFile)
    }

    /**
     * `values-en` 里不允许出现中文（未翻译）。
     *
     * 0.10.1 的守卫只比 key 集合：把一条英文文案写成中文，测试照样绿——
     * 上一轮实际存在 `values-en/seed.xml` 三段中文分类。现在直接扫**值**：
     * XML 注释不算（注释是给开发者看的，可以写中文）。
     */
    @Test
    fun `values-en 的文案里不许出现中文`() {
        val problems = buildList {
            TestResources.strings("values-en").forEach { (key, text) ->
                if (CONTAINS_CJK.containsMatchIn(text)) add("values-en/$key = $text")
            }
            TestResources.plurals("values-en").forEach { (key, items) ->
                items.forEach { (quantity, text) ->
                    if (CONTAINS_CJK.containsMatchIn(text)) add("values-en/$key[$quantity] = $text")
                }
            }
        }
        assertEquals(
            "以下英文资源里还有中文（未翻译，或该加进 cjkWhitelist 并写清原因）：\n" +
                problems.joinToString("\n"),
            emptyList<String>(),
            problems
        )
    }

    /**
     * 两份资源的格式符集合必须一致（比 0.10.1 宽得多的正则）。
     *
     * 旧守卫只认 `%1$s` / `%d`，于是 `%.1f`、`%x`、`%%` 这类格式符两边不一致也发现不了——
     * 英文界面可能因此拿到一条「参数比实际多一个」的文案，轻则显示 `%2$d`，重则
     * `String.format` 抛 `IllegalFormatConversionException` 崩溃。
     */
    @Test
    fun `同一 key 的完整格式符集合两边一致`() {
        val zh = TestResources.strings("values")
        val en = TestResources.strings("values-en")
        val mismatched = (zh.keys intersect en.keys).mapNotNull { key ->
            val left = formatSpecifiers(zh.getValue(key))
            val right = formatSpecifiers(en.getValue(key))
            if (left == right) null else "$key zh=$left en=$right"
        }
        assertEquals("格式符对不上的条目：$mismatched", emptyList<String>(), mismatched)
    }

    /** 只写中文标点/全角字符也算「没翻译」——0.10.x 的正则漏了 U+3000–303F 与 U+FF00–FFEF。 */
    @Test
    fun `只写中文标点或全角字符也判红`() {
        listOf("Settings。", "Settings，", "Settings：", "Ｓｅｔｔｉｎｇｓ", "设置").forEach { text ->
            assertTrue("「$text」应当被中文守卫拦住", CONTAINS_CJK.containsMatchIn(text))
        }
        listOf("Settings.", "Set: up", "Older format (v%1\$d)", "…", "—", "·").forEach { text ->
            assertFalse("「$text」是正常英文/排版字符，不该判红", CONTAINS_CJK.containsMatchIn(text))
        }
    }

    @Test
    fun `plurals 的完整格式符集合两边一致`() {
        val zh = TestResources.plurals("values")
        val en = TestResources.plurals("values-en")
        val mismatched = (zh.keys intersect en.keys).mapNotNull { key ->
            val left = zh.getValue(key).values.flatMap { formatSpecifiers(it) }.toSet()
            val right = en.getValue(key).values.flatMap { formatSpecifiers(it) }.toSet()
            if (left == right) null else "$key zh=$left en=$right"
        }
        assertEquals("plurals 格式符对不上的条目：$mismatched", emptyList<String>(), mismatched)
    }

    /**
     * 「参数位置 → 格式符」的映射（安全审计 P2-10）。
     *
     * 没写 `%n$` 的格式符按出现顺序自动编号（`String.format` 的真实语义）；
     * `%%` 不消耗参数，忽略。这样 `%s %d` 与 `%d %s` 会得出不同的映射（判红），
     * 而 `%1$s %2$d` 与 `%2$d %1$s` 相同（合法换语序）。
     */
    private fun formatSlots(text: String): Map<Int, String> {
        val slots = LinkedHashMap<Int, String>()
        var nextImplicit = 1
        FORMAT_SPECIFIER.findAll(text).forEach { match ->
            if (match.groupValues[2] == "%") return@forEach
            val explicit = match.groupValues[1]
            val index = if (explicit.isNotEmpty()) {
                explicit.dropLast(1).toInt()
            } else {
                nextImplicit++
                nextImplicit - 1
            }
            slots[index] = match.value
        }
        return slots
    }

    private fun formatSpecifiers(text: String): Set<String> =
        FORMAT_SPECIFIER.findAll(text)
            .filter { it.groupValues[2] != "%" }
            .map { it.value }
            .toSet()

    private companion object {
        /**
         * `values-en` 里不允许出现的字符（安全审计 P2-10 的第一个缺口）。
         *
         * 旧正则只覆盖 CJK 统一表意文字（3400–4DBF / 4E00–9FFF / F900–FAFF）：
         * `values-en/strings.xml` 里写 `Settings。` 或全角字母都会绿。
         * 现在把 CJK 标点（U+3000–303F）、全角字符（U+FF00–FFEF）也算进来。
         *
         * 允许的排版字符不在这些区间：`…`(U+2026)、`—`(U+2014)、`·`(U+00B7)、`’`(U+2019)。
         */
        val CONTAINS_CJK = Regex("[\\u3000-\\u303f\\uff00-\\uffef\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]")

        /** Android `String.format` 语法：位置参数、flags、宽度、精度、转换字符。 */
        val FORMAT_SPECIFIER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])""")
    }
}
