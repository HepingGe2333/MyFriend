package com.myfriend.agentd.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `web_fetch`：URL 策略、直连抓取与 `MESSAGEAPI` 的 tools/fetch。
 *
 * 两条必须分开测的东西：
 * 1. **策略先于请求**：非 http(s)、本机/内网、带 userinfo 的 URL 一个字节都不许发出去；
 * 2. **正文抽取**：去 script/style/标签、解实体、压空白、≤8000 字，10 秒 / 2 MiB 上限。
 *
 * 直连抓取要打本地假服务端，而真实策略一定会拒绝 `127.0.0.1` —— 所以抓取路径的测试
 * 用 `urlPolicy = { null }` 显式放过策略；策略本身在下面单独测（默认构造函数，不传第二个参数）。
 */
class WebFetchTest {

    private val service = WebSearchService()

    private fun serviceWithoutPolicy() = WebSearchService(urlPolicy = { null })

    // ------------------------------------------------------------------ 策略

    @Test
    fun `fetch rejects local and private hosts before any request`() {
        var called = false
        FakeHttpServer {
            called = true
            FakeHttpServer.FakeResponse(200, "<html>should not be fetched</html>")
        }.use { server ->
            val targets = listOf(
                "${server.baseUrl}/page",
                "http://localhost:${server.baseUrl.substringAfterLast(':')}/page",
                "http://10.0.0.1/page",
                "http://192.168.1.1/page",
                "http://169.254.169.254/latest/meta-data"
            )
            targets.forEach { target ->
                val failure = runCatching { service.webFetch(target) }.exceptionOrNull()
                val error = (failure as? SearchException)?.error
                    ?: error("$target 应该被策略拒绝，实际：$failure")
                assertEquals("$target", SearchErrorKind.URL_REJECTED, error.kind)
            }
            assertTrue("策略拒绝的 URL 不应该真的发出请求", !called)
            assertTrue("策略拒绝的 URL 不应该真的发出请求", server.requests.isEmpty())
        }
    }

    @Test
    fun `fetch rejects non http schemes and userinfo`() {
        mapOf(
            "file:///etc/passwd" to SearchErrorKind.URL_REJECTED,
            "javascript:alert(1)" to SearchErrorKind.URL_REJECTED,
            "https://user:pass@example.com/x" to SearchErrorKind.URL_REJECTED,
            "ftp://example.com/x" to SearchErrorKind.URL_REJECTED
        ).forEach { (target, kind) ->
            val failure = runCatching { service.webFetch(target) }.exceptionOrNull()
            assertEquals(target, kind, (failure as? SearchException)?.error?.kind)
        }
    }

    @Test
    fun `fetch rejects empty url`() {
        val failure = runCatching { service.webFetch("   ") }.exceptionOrNull()
        assertEquals(SearchErrorKind.EMPTY_URL, (failure as? SearchException)?.error?.kind)
    }

    // ------------------------------------------------------------------ 直连抓取

    @Test
    fun `fetch strips scripts styles tags and decodes entities`() {
        val html = """
            <!doctype html>
            <html><head>
              <title>杭州 &amp; 西湖</title>
              <style>body { color: red; }</style>
            </head>
            <body>
              <!-- 广告位 -->
              <script>var tracking = "should not appear";</script>
              <h1>断桥残雪</h1>
              <p>西湖十景之一&nbsp;&mdash;&nbsp;冬天最美。</p>
              <p>门票：&#20813;&#36153;</p>
            </body></html>
        """.trimIndent()
        FakeHttpServer { FakeHttpServer.FakeResponse(200, html, contentType = "text/html; charset=utf-8") }.use { server ->
            val page = serviceWithoutPolicy().webFetch("${server.baseUrl}/west-lake")
            val request = server.onlyRequest()
            assertEquals("GET", request.method)
            assertEquals(SearchLimits.USER_AGENT, request.header("User-Agent"))
            assertEquals("杭州 & 西湖", page.title)
            assertEquals("${server.baseUrl}/west-lake", page.url)
            assertTrue(page.text, page.text.contains("断桥残雪"))
            assertTrue(page.text, page.text.contains("西湖十景之一 — 冬天最美。"))
            assertTrue(page.text, page.text.contains("门票：免费"))
            assertTrue(page.text, !page.text.contains("tracking"))
            assertTrue(page.text, !page.text.contains("color: red"))
            assertTrue(page.text, !page.text.contains("广告位"))
            assertTrue(page.text, !page.text.contains("<"))
        }
    }

    @Test
    fun `fetch caps text at eight thousand characters`() {
        val paragraph = "他".repeat(20_000)
        val html = "<html><head><title>长文</title></head><body><p>$paragraph</p></body></html>"
        FakeHttpServer { FakeHttpServer.FakeResponse(200, html) }.use { server ->
            val page = serviceWithoutPolicy().webFetch("${server.baseUrl}/long")
            assertEquals(SearchLimits.MAX_FETCH_CHARS, page.text.length)
        }
    }

    @Test
    fun `fetch title falls back to the url when the page has none`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, "<html><body>只有正文</body></html>") }.use { server ->
            val url = "${server.baseUrl}/no-title"
            val page = serviceWithoutPolicy().webFetch(url)
            assertEquals(url, page.title)
            assertEquals("只有正文", page.text)
        }
    }

    @Test
    fun `fetch response larger than two mebibytes is rejected`() {
        val oversized = ByteArray(SearchLimits.MAX_RESPONSE_BYTES + 1024) { 'a'.code.toByte() }
        FakeHttpServer { FakeHttpServer.FakeResponse(200, bytes = oversized, contentType = "text/html") }.use { server ->
            val failure = runCatching { serviceWithoutPolicy().webFetch("${server.baseUrl}/big") }.exceptionOrNull()
            assertEquals(SearchErrorKind.RESPONSE_TOO_LARGE, (failure as? SearchException)?.error?.kind)
        }
    }

    @Test
    fun `fetch maps http errors to typed errors`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(404, "not found", contentType = "text/plain") }.use { server ->
            val failure = runCatching { serviceWithoutPolicy().webFetch("${server.baseUrl}/missing") }.exceptionOrNull()
            val error = (failure as? SearchException)?.error ?: error("应该抛 SearchException")
            assertEquals(SearchErrorKind.HTTP_STATUS, error.kind)
            assertEquals(404, error.status)
        }
    }

    // ------------------------------------------------------------------ MESSAGEAPI

    @Test
    fun `messageapi fetch posts the url and parses content`() {
        FakeHttpServer {
            FakeHttpServer.FakeResponse(
                200,
                """{"url":"https://example.com/a","title":"示例页","content":"正文内容"}"""
            )
        }.use { server ->
            val page = WebSearchService(urlPolicy = { null }).webFetch(
                "https://example.com/a",
                ProviderConfig(baseUrl = server.baseUrl, apiKey = "sk-fetch", provider = "MESSAGEAPI")
            )
            val request = server.onlyRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/tools/fetch", request.path)
            assertEquals("Bearer sk-fetch", request.header("Authorization"))
            assertEquals("""{"url":"https://example.com/a"}""", request.body)
            assertEquals("https://example.com/a", page.url)
            assertEquals("示例页", page.title)
            assertEquals("正文内容", page.text)
        }
    }

    @Test
    fun `messageapi fetch accepts markdown field and data wrapper`() {
        FakeHttpServer {
            FakeHttpServer.FakeResponse(200, """{"data":{"url":"https://example.com/b","markdown":"# 标题\n\n正文"}}""")
        }.use { server ->
            val page = serviceWithoutPolicy().webFetch(
                "https://example.com/b",
                ProviderConfig(baseUrl = server.baseUrl, apiKey = "k", provider = "MESSAGEAPI")
            )
            assertEquals("https://example.com/b", page.title)
            assertTrue(page.text.startsWith("# 标题"))
        }
    }

    @Test
    fun `messageapi fetch requires a key and a base before any request`() {
        val noKey = runCatching {
            serviceWithoutPolicy().webFetch(
                "https://example.com/a",
                ProviderConfig(baseUrl = "https://api.example.com", apiKey = "", provider = "MESSAGEAPI")
            )
        }.exceptionOrNull()
        assertEquals(SearchErrorKind.MISSING_KEY, (noKey as? SearchException)?.error?.kind)

        val noBase = runCatching {
            serviceWithoutPolicy().webFetch(
                "https://example.com/a",
                ProviderConfig(baseUrl = "", apiKey = "k", provider = "MESSAGEAPI")
            )
        }.exceptionOrNull()
        assertEquals(SearchErrorKind.MISSING_BASE, (noBase as? SearchException)?.error?.kind)
    }

    @Test
    fun `messageapi fetch caps text and falls back to the target url`() {
        val longContent = "字".repeat(20_000)
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"content":"$longContent"}""") }.use { server ->
            val page = serviceWithoutPolicy().webFetch(
                "https://example.com/c",
                ProviderConfig(baseUrl = server.baseUrl, apiKey = "k", provider = "MESSAGEAPI")
            )
            assertEquals("https://example.com/c", page.title)
            assertEquals(SearchLimits.MAX_FETCH_CHARS, page.text.length)
        }
    }
}
