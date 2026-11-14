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

    /** `%1$s` / `%2$d` 这类位置化参数；裸 `%s` 也算一种写法，一并比对。 */
    private fun placeholders(text: String): Set<String> =
        Regex("""%(\d+\$[sd]|[sd])""").findAll(text).map { it.value }.toSet()
}
