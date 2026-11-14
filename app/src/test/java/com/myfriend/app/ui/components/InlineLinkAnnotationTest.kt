package com.myfriend.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天里点得开的链接靠两件事：文字上真的挂了 LinkAnnotation（样式 + 语义），
 * 以及 InlineText 抬手时能按位置反查出地址。这里守的是前半段：
 * 链接范围与地址都要对得上，否则点下去就不知道要打开哪个网址。
 */
class InlineLinkAnnotationTest {

    private fun linksOf(raw: String, withCallback: Boolean = true): AnnotatedString = buildInline(
        raw = raw,
        color = Color.Black,
        codeBackground = Color.Transparent,
        linkColor = Color.Blue,
        onOpenLink = if (withCallback) ({}) else null
    )

    @Test
    fun inlineUrlBecomesOneLinkWithItsOwnRange() {
        val raw = "看到一个你可能会喜欢的东西：https://example.com/reading ，里面讲的"
        val annotated = linksOf(raw)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val tag = (links[0].item as LinkAnnotation.Clickable).tag
        assertEquals("https://example.com/reading", tag)
        // 范围要正好是原文里那一段，不能把后面的标点、正文一起吃进去。
        assertEquals("https://example.com/reading", raw.substring(links[0].start, links[0].end))
    }

    @Test
    fun markdownLinkKeepsSpokenTextButOpensUrl() {
        val annotated = linksOf("还有这个 [一份更短的说明](https://example.com/brief) 也还行。")
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        assertEquals("https://example.com/brief", (links[0].item as LinkAnnotation.Clickable).tag)
        // 显示的是「一份更短的说明」，方括号和地址都不能留在正文里。
        assertEquals("一份更短的说明", annotated.text.substring(links[0].start, links[0].end))
    }

    @Test
    fun textWithoutLinksHasNoAnnotations() {
        val annotated = linksOf("今天有点累，早点睡。")
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
    }

    @Test
    fun linksStayPlainTextWhenNoCallback() {
        // 设置页、导出预览这类地方不给回调：链接照样上色，但不挂可点语义。
        val annotated = linksOf("看看 https://example.com/reading 吧", withCallback = false)
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
        assertTrue(annotated.text.contains("https://example.com/reading"))
    }
}
