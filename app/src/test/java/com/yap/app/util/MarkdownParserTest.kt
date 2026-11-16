package com.yap.app.util

import com.yap.app.ui.components.MarkdownBlock
import com.yap.app.ui.components.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun parsesHeadingsListsQuotesAndCode() {
        val blocks = MarkdownParser.parse(
            """
            # 标题

            一段普通文字，包含 **粗体**。

            - 第一项
            - 第二项

            > 引用一句话

            ```kotlin
            val answer = 42
            ```
            """.trimIndent()
        )
        assertTrue(blocks.any { it is MarkdownBlock.Heading && it.level == 1 })
        assertTrue(blocks.any { it is MarkdownBlock.Paragraph && it.text.contains("一段普通文字") })
        assertTrue(blocks.any { it is MarkdownBlock.Bullets && it.items.size == 2 })
        assertTrue(blocks.any { it is MarkdownBlock.Quote })
        val code = blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val answer = 42", code.code)
    }

    @Test
    fun keepsUnclosedStreamingCodeFenceReadable() {
        val blocks = MarkdownParser.parse("说明\n\n```python\nprint('hello')")
        val code = blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("python", code.language)
        assertEquals("print('hello')", code.code)
    }

    @Test
    fun parsesDisplayMath() {
        val blocks = MarkdownParser.parse("公式：\n\n$$\nE = mc^2\n$$")
        val math = blocks.filterIsInstance<MarkdownBlock.MathBlock>().single()
        assertTrue(math.latex.contains("E = mc^2"))
    }

    @Test
    fun parsesOrderedList() {
        val blocks = MarkdownParser.parse("1. 第一\n2. 第二")
        val ordered = blocks.filterIsInstance<MarkdownBlock.Ordered>().single()
        assertEquals(listOf("第一", "第二"), ordered.items)
    }
}
