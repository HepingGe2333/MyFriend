package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接识别的边界：这些用例就是渲染层「蓝色可点」的唯一依据，
 * 误判会把普通文字点成网页，漏判会让用户看不到他发的地址。
 */
class LinkDetectorTest {

    // ---------------------------------------------------------------- 正常形态

    @Test
    fun findsHttpsUrlWithOffsets() {
        val text = "看这个 https://example.com/a?b=1#c 吧"
        val spans = LinkDetector.findAll(text)
        assertEquals(1, spans.size)
        val span = spans.single()
        assertEquals(LinkKind.URL, span.kind)
        assertEquals(4, span.start)
        assertEquals(4 + "https://example.com/a?b=1#c".length, span.end)
        assertEquals("https://example.com/a?b=1#c", span.url)
        assertEquals(span.url, text.substring(span.start, span.end))
    }

    @Test
    fun findsHttpUrlWithPort() {
        val spans = LinkDetector.findAll("旧地址 http://example.com:8080/x 还能用")
        val span = spans.single()
        assertEquals(LinkKind.URL, span.kind)
        assertEquals("http://example.com:8080/x", span.url)
        assertEquals(span.url, "旧地址 http://example.com:8080/x 还能用".substring(span.start, span.end))
    }

    @Test
    fun findsBareDomainAndAddsHttps() {
        val text = "文档在 example.com/guide 里"
        val span = LinkDetector.findAll(text).single()
        assertEquals(LinkKind.BARE_DOMAIN, span.kind)
        assertEquals("https://example.com/guide", span.url)
        assertEquals(4, span.start)
        assertEquals("example.com/guide", text.substring(span.start, span.end))
    }

    @Test
    fun findsWwwDomain() {
        val span = LinkDetector.findAll("看 www.example.org 吧").single()
        assertEquals("https://www.example.org", span.url)
        assertEquals(LinkKind.BARE_DOMAIN, span.kind)
    }

    @Test
    fun findsBareDomainRightAfterChineseWithoutSpace() {
        val text = "看example.com"
        val span = LinkDetector.findAll(text).single()
        assertEquals("https://example.com", span.url)
        assertEquals(1, span.start)
        assertEquals(text.length, span.end)
    }

    @Test
    fun findsUrlRightAfterChineseWithoutSpace() {
        val span = LinkDetector.findAll("看https://example.com").single()
        assertEquals("https://example.com", span.url)
        assertEquals(1, span.start)
    }

    @Test
    fun findsUrlInsideBoldMarkers() {
        val text = "**https://example.com**"
        val span = LinkDetector.findAll(text).single()
        assertEquals(2, span.start)
        assertEquals(2 + "https://example.com".length, span.end)
    }

    @Test
    fun findsMarkdownLinkAndCoversLabelOnly() {
        val text = "请看[官网](https://example.com/home)吧"
        val span = LinkDetector.findAll(text).single()
        assertEquals(LinkKind.MARKDOWN, span.kind)
        assertEquals("https://example.com/home", span.url)
        assertEquals("官网", text.substring(span.start, span.end))
    }

    @Test
    fun markdownLinkAcceptsBareDomainTarget() {
        val span = LinkDetector.findAll("[官网](example.com)").single()
        assertEquals(LinkKind.MARKDOWN, span.kind)
        assertEquals("https://example.com", span.url)
    }

    @Test
    fun findsMultipleLinksInOrderWithoutOverlap() {
        val text = "A https://a.com/1 和 b.example.org/x 还有 [文](https://c.net)"
        val spans = LinkDetector.findAll(text)
        assertEquals(3, spans.size)
        assertEquals(
            listOf(LinkKind.URL, LinkKind.BARE_DOMAIN, LinkKind.MARKDOWN),
            spans.map { it.kind }
        )
        assertEquals(listOf("https://a.com/1", "https://b.example.org/x", "https://c.net"), spans.map { it.url })
        var previousEnd = 0
        spans.forEach { span ->
            assertTrue("区间不应重叠", span.start >= previousEnd)
            assertTrue(span.end > span.start)
            previousEnd = span.end
        }
    }

    @Test
    fun keepsQueryAndFragment() {
        val url = "https://example.com/a?b=1&c=2#d"
        assertEquals(url, LinkDetector.normalize(url))
        assertEquals(url, LinkDetector.findAll("看 $url 。").single().url)
    }

    // ---------------------------------------------------------------- 标点边界

    @Test
    fun stripsChinesePunctuationAfterUrl() {
        val text = "链接：https://example.com/x，还有https://example.com/y。"
        val spans = LinkDetector.findAll(text)
        assertEquals(listOf("https://example.com/x", "https://example.com/y"), spans.map { it.url })
        spans.forEach { span ->
            assertEquals(span.url, text.substring(span.start, span.end))
        }
    }

    @Test
    fun stripsAsciiSentencePunctuation() {
        val span = LinkDetector.findAll("见https://example.com/a.").single()
        assertEquals("https://example.com/a", span.url)
        assertEquals("https://example.com/a", "见https://example.com/a.".substring(span.start, span.end))
    }

    @Test
    fun keepsBalancedParenthesesInUrl() {
        val url = "https://en.wikipedia.org/wiki/Foo_(bar)"
        val span = LinkDetector.findAll("维基 $url 吧").single()
        assertEquals(url, span.url)
        assertEquals(url, "维基 $url 吧".substring(span.start, span.end))
    }

    @Test
    fun stripsUnbalancedClosingParenthesis() {
        val text = "(见 https://example.com/a)"
        val span = LinkDetector.findAll(text).single()
        assertEquals("https://example.com/a", span.url)
        assertEquals("https://example.com/a", text.substring(span.start, span.end))
    }

    @Test
    fun normalizeStripsTrailingPunctuation() {
        assertEquals("https://example.com/x", LinkDetector.normalize("https://example.com/x。"))
        assertEquals("https://example.com", LinkDetector.normalize("example.com，"))
        assertEquals("https://example.com", LinkDetector.normalize(" https://example.com "))
    }

    // ---------------------------------------------------------------- 协议与写法

    @Test
    fun normalizeAddsHttpsForBareDomain() {
        assertEquals("https://example.com", LinkDetector.normalize("example.com"))
        assertEquals("https://example.com:8080/x", LinkDetector.normalize("example.com:8080/x"))
    }

    @Test
    fun normalizeHandlesProtocolRelative() {
        assertEquals("https://example.com/a", LinkDetector.normalize("//example.com/a"))
    }

    @Test
    fun normalizeStripsAngleBrackets() {
        assertEquals("https://example.com", LinkDetector.normalize("<https://example.com>"))
    }

    @Test
    fun acceptsUppercaseScheme() {
        assertEquals("HTTPS://Example.com/Path", LinkDetector.normalize("HTTPS://Example.com/Path"))
    }

    @Test
    fun parsesDocumentedSampleUrl() {
        val text = "他说这个例子不错：https://example.com/x。你看看。"
        val span = LinkDetector.findAll(text).single()
        assertEquals("https://example.com/x", span.url)
        assertEquals(LinkKind.URL, span.kind)
    }

    // ---------------------------------------------------------------- 拒绝

    @Test
    fun rejectsDangerousSchemes() {
        listOf(
            "javascript:alert(1)",
            "javascript:void(0)",
            "file:///etc/passwd",
            "content://media/external/images/1",
            "intent://scan#Intent;scheme=zxing;end",
            "data:text/html;base64,PHNjcmlwdD4=",
            "mailto:someone@example.com"
        ).forEach { raw ->
            assertNull("不该放行：$raw", LinkDetector.normalize(raw))
        }
    }

    @Test
    fun doesNotLinkDangerousSchemeInText() {
        val spans = LinkDetector.findAll("点这里 javascript:alert(1) 和 file:///etc/passwd")
        assertTrue(spans.isEmpty())
    }

    @Test
    fun ignoresEmptyAndBlankInput() {
        assertTrue(LinkDetector.findAll("").isEmpty())
        assertTrue(LinkDetector.findAll("   ").isEmpty())
        assertNull(LinkDetector.normalize(""))
        assertNull(LinkDetector.normalize("    "))
        assertNull(LinkDetector.normalize("https://"))
        assertNull(LinkDetector.normalize("http://"))
    }

    @Test
    fun ignoringInvalidHosts() {
        assertNull(LinkDetector.normalize("https://abc"))
        assertNull(LinkDetector.normalize("https://.com"))
        assertNull(LinkDetector.normalize("https://a..b.com"))
        assertNull(LinkDetector.normalize("https://exa mple.com/a"))
        assertNull(LinkDetector.normalize("https://-example.com"))
    }

    @Test
    fun ignoresEmailAddress() {
        assertTrue(LinkDetector.findAll("联系 foo@example.com 谢谢").isEmpty())
    }

    @Test
    fun ignoresFileLikeNames() {
        assertTrue(LinkDetector.findAll("文件 说明.txt 和 main.kt、build.gradle").isEmpty())
    }

    @Test
    fun ignoresVersionNumbers() {
        assertTrue(LinkDetector.findAll("版本 v1.7.0 和 1.2.3 都过时了").isEmpty())
    }

    @Test
    fun rejectsOverlongUrl() {
        val longUrl = "https://example.com/" + "a".repeat(3000)
        assertNull(LinkDetector.normalize(longUrl))
        assertTrue(LinkDetector.findAll(longUrl).isEmpty())
        assertTrue(LinkDetector.findAll("说明 $longUrl 结束").isEmpty())
    }

    // ---------------------------------------------------------------- 坏输入不炸

    @Test
    fun doesNotThrowOnWeirdInput() {
        listOf(
            "[[[[]]]]",
            "()[]<>",
            "[]()",
            "[标题]()",
            "https://",
            "http://",
            "\n\n\n",
            "😀🌐",
            "a".repeat(10_000),
            "[".repeat(500),
            "https://example.com/" + "(".repeat(50),
            "%%%"
        ).forEach { text ->
            val spans = LinkDetector.findAll(text)
            spans.forEach { span ->
                assertTrue(span.start >= 0)
                assertTrue(span.end > span.start)
                assertTrue(span.end <= text.length)
                assertTrue(span.url.startsWith("http"))
            }
        }
    }

    @Test
    fun doesNotSplitWords() {
        // 单词里的点、路径里的点都不该被当成域名
        assertTrue(LinkDetector.findAll("文件是 report.final.docx").isEmpty())
        assertTrue(LinkDetector.findAll("自增字段 user_id 不能点").isEmpty())
    }

    @Test
    fun handlesManyLinksWithoutOverlap() {
        val text = (1..200).joinToString(" ") { "https://example.com/p/$it" }
        val spans = LinkDetector.findAll(text)
        assertEquals(200, spans.size)
        assertEquals("https://example.com/p/200", spans.last().url)
        var previousEnd = 0
        spans.forEach { span ->
            assertTrue(span.start >= previousEnd)
            previousEnd = span.end
        }
    }

    @Test
    fun markdownLinkWithUnsafeTargetFallsBackToPlainText() {
        val spans = LinkDetector.findAll("[点我](javascript:alert(1))")
        assertTrue(spans.isEmpty())
    }

    @Test
    fun `markdown 地址里的成对括号不会被截断`() {
        val text = "看这个 [维基](https://en.wikipedia.org/wiki/Foo_(bar)) 挺有意思"
        val spans = LinkDetector.findAll(text)
        assertEquals(1, spans.size)
        val span = spans.first()
        assertEquals(LinkKind.MARKDOWN, span.kind)
        assertEquals("维基", text.substring(span.start, span.end))
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", span.url)
    }

    @Test
    fun `markdown 地址里带空格就不认`() {
        // 地址段不该有空格；有空格说明这不是链接，宁可少识别也别把后半段正文吃进去。
        val text = "[标题](https://example.com/a b) 后面还有话"
        assertTrue(LinkDetector.findAll(text).none { it.kind == LinkKind.MARKDOWN })
    }

    @Test
    fun `markdown 地址跨行就不认`() {
        val text = "[标题](https://example.com/a\nb)"
        assertTrue(LinkDetector.findAll(text).none { it.kind == LinkKind.MARKDOWN })
    }
}
