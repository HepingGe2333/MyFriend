package com.myfriend.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            val left = placeholders(zh.getValue(key))
            val right = placeholders(en.getValue(key))
            if (left == right) null else "$key zh=$left en=$right"
        }
        assertEquals("占位符对不上的条目：$mismatched", emptyList<String>(), mismatched)
    }

    @Test
    fun `plurals 的占位符两边一致且英语带 other`() {
        val zh = TestResources.plurals("values")
        val en = TestResources.plurals("values-en")
        val problems = buildList {
            (zh.keys intersect en.keys).forEach { key ->
                val left = zh.getValue(key).values.flatMap { placeholders(it) }.toSet()
                val right = en.getValue(key).values.flatMap { placeholders(it) }.toSet()
                if (left != right) add("$key 占位符不一致 zh=$left en=$right")
                if (zh.getValue(key).keys != setOf("other")) {
                    add("$key 中文 plurals 只该有 other，实际 ${zh.getValue(key).keys}")
                }
                if ("other" !in en.getValue(key).keys) {
                    add("$key 英语 plurals 缺少 other")
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
     * 一条文案里的全部格式符（Android `String.format` 语法）。
     *
     * 覆盖位置参数、flags、宽度、精度与所有转换字符（`%s %d %f %x %c %%` 等）。
     * `%%` 也保留：它决定「这段文案里有没有一个字面百分号」，两边不一致同样是问题。
     */
    /** 0.10.1 的窄口径占位符（只认 `%1$s` / `%d`）；保留给老测试，全量口径见 [formatSpecifiers]。 */
    private fun placeholders(text: String): Set<String> = formatSpecifiers(text)

    private fun formatSpecifiers(text: String): Set<String> =
        Regex("""%(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])""")
            .findAll(text)
            .map { it.value }
            .toSet()

    private companion object {
        val CONTAINS_CJK = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]")
    }
}
