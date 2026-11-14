package com.myfriend.app.network

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeekWebTools 的纯 JVM 单元测试：只验证端点归一化与响应容错解析，不发起任何网络请求。
 */
class DeepSeekWebToolsTest {

    private val tools = DeepSeekWebTools(
        client = OkHttpClient(),
        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    )

    @Test
    fun toolsEndpointNormalizesAllBaseUrlVariants() {
        val expected = "https://api.deepseek.com/v1/tools/search"
        assertEquals(expected, tools.toolsEndpoint("https://api.deepseek.com", "search"))
        assertEquals(expected, tools.toolsEndpoint("https://api.deepseek.com/", "search"))
        assertEquals(expected, tools.toolsEndpoint("https://api.deepseek.com/v1", "search"))
        assertEquals(expected, tools.toolsEndpoint("https://api.deepseek.com/v1/", "search"))
    }

    @Test
    fun toolsEndpointBuildsFetchFromV1Base() {
        assertEquals(
            "https://api.deepseek.com/v1/tools/fetch",
            tools.toolsEndpoint("https://api.deepseek.com/v1", "fetch")
        )
    }

    @Test
    fun parseSearchReadsOfficialFields() {
        val body = """
            {
              "results": [
                {
                  "title": "Kotlin 官方文档",
                  "url": "https://kotlinlang.org/docs/home.html",
                  "snippet": "Kotlin 是一门现代静态类型语言。",
                  "summary": "官方文档首页，涵盖入门、协程与多平台。",
                  "site_name": "kotlinlang.org",
                  "date": "2024-11-20",
                  "authority": 8,
                  "rerank_score": 0.9231
                }
              ]
            }
        """.trimIndent()

        val hits = tools.parseSearch(body)

        assertEquals(1, hits.size)
        val hit = hits.first()
        assertEquals("Kotlin 官方文档", hit.title)
        assertEquals("https://kotlinlang.org/docs/home.html", hit.url)
        assertEquals("Kotlin 是一门现代静态类型语言。", hit.snippet)
        assertEquals("官方文档首页，涵盖入门、协程与多平台。", hit.summary)
        assertEquals("kotlinlang.org", hit.siteName)
        assertEquals("2024-11-20", hit.date)
        assertEquals(8, hit.authority)
        assertEquals(0.9231, hit.rerankScore, 0.0001)
    }

    @Test
    fun parseSearchToleratesEmptyAndMissingFields() {
        assertTrue(tools.parseSearch("""{"results":[]}""").isEmpty())
        assertTrue(tools.parseSearch("""{}""").isEmpty())
        assertTrue(tools.parseSearch("""{"results":"oops"}""").isEmpty())

        val hits = tools.parseSearch("""{"results":[{}]}""")
        assertEquals(1, hits.size)
        val hit = hits.first()
        assertEquals("", hit.title)
        assertEquals("", hit.url)
        assertEquals("", hit.snippet)
        assertEquals("", hit.summary)
        assertNull(hit.siteName)
        assertNull(hit.date)
        assertEquals(-1, hit.authority)
        assertEquals(0.0, hit.rerankScore, 0.0001)
    }

    @Test
    fun parseFetchReadsTitleAndContent() {
        val body = """
            {
              "url": "https://example.com/a",
              "title": "示例页面",
              "content": "# 小标题\n\n这是正文。",
              "extra": {"ignored": true}
            }
        """.trimIndent()

        val page = tools.parseFetch(body)

        assertEquals("https://example.com/a", page.url)
        assertEquals("示例页面", page.title)
        assertEquals("# 小标题\n\n这是正文。", page.markdown)
    }

    @Test
    fun parseFetchFallsBackToUrlAsTitle() {
        val page = tools.parseFetch("""{"url":"https://example.com/no-title","content":"正文"}""")

        assertEquals("https://example.com/no-title", page.title)
        assertEquals("正文", page.markdown)
    }

    @Test
    fun parseFetchTruncatesOverlongMarkdown() {
        val longContent = "测".repeat(13_000)
        val body = """{"url":"https://example.com/long","title":"超长页面","content":"$longContent"}"""

        val page = tools.parseFetch(body)

        assertEquals("超长页面", page.title)
        assertTrue(page.markdown.length < longContent.length)
        assertTrue(page.markdown.endsWith("\n\n…（内容过长已截断）"))
        assertEquals(12_000, page.markdown.length - "\n\n…（内容过长已截断）".length)
        assertTrue(page.markdown.startsWith("测".repeat(100)))
    }
}
