package com.yap.agentd.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯函数部分：归一化、provider 名字归一化、条数夹取、`site:` 拼装、URL 编码、错误文案。
 *
 * 这里不需要起服务器：`spec/web-search/README.md` §3 的「丢非 http(s)、按 URL 去重、snippet ≤500、
 * 最多 count 条」是纯数据变换，应该在毫秒级被钉死，而不是靠某个 provider 的集成测试顺带覆盖。
 */
class SearchNormalizeTest {

    // ------------------------------------------------------------------ normalizeHits

    @Test
    fun `normalize drops non http urls and keeps first occurrence order`() {
        val hits = listOf(
            WebSearchHit("a", "https://a.example/1", "a"),
            WebSearchHit("ftp", "ftp://a.example/2", "x"),
            WebSearchHit("js", "javascript:alert(1)", "x"),
            WebSearchHit("file", "file:///etc/passwd", "x"),
            WebSearchHit("relative", "/relative", "x"),
            WebSearchHit("b", "http://b.example/2", "b")
        )
        val out = WebSearchService.normalizeHits(hits, 10)
        assertEquals(listOf("https://a.example/1", "http://b.example/2"), out.map { it.url })
    }

    @Test
    fun `normalize deduplicates by url keeping the first hit`() {
        val hits = listOf(
            WebSearchHit("第一次", "https://a.example/1", "s1"),
            WebSearchHit("第二次", "https://a.example/1", "s2"),
            WebSearchHit("B", "https://b.example/1", "s4")
        )
        val out = WebSearchService.normalizeHits(hits, 10)
        assertEquals(2, out.size)
        assertEquals("第一次", out[0].title)
        assertEquals("s1", out[0].snippet)
    }

    @Test
    fun `dedupe is byte exact like the client`() {
        // 客户端 `WebSearchClient.normalizeHits` 用的是 `seen.add(url)`（大小写敏感）。
        // 服务端保持一致：同一个 URL 的大小写变体仍算两条，免得两端对同一次搜索给出不同条数。
        val out = WebSearchService.normalizeHits(
            listOf(
                WebSearchHit("小写", "https://a.example/1", "s"),
                WebSearchHit("大写", "HTTPS://A.EXAMPLE/1", "s")
            ),
            10
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `normalize truncates snippet to five hundred characters and strips markup`() {
        val long = "字".repeat(800)
        val out = WebSearchService.normalizeHits(
            listOf(WebSearchHit("t", "https://a.example/1", "<b>$long</b>")),
            1
        )
        assertEquals(SearchLimits.MAX_SNIPPET_CHARS, out.single().snippet.length)
        assertTrue(out.single().snippet.startsWith("字"))
    }

    @Test
    fun `normalize caps results at count`() {
        val hits = (1..12).map { WebSearchHit("t$it", "https://a.example/$it", "s") }
        assertEquals(3, WebSearchService.normalizeHits(hits, 3).size)
        assertEquals(12, WebSearchService.normalizeHits(hits, 999).size)
        assertEquals(1, WebSearchService.normalizeHits(hits, 0).size)
    }

    @Test
    fun `normalize trims title and url`() {
        val out = WebSearchService.normalizeHits(
            listOf(WebSearchHit("  title  ", "  https://a.example/1  ", "  snippet  ")),
            1
        )
        assertEquals("title", out.single().title)
        assertEquals("https://a.example/1", out.single().url)
        assertEquals("snippet", out.single().snippet)
    }

    @Test
    fun `normalize collapses whitespace in snippets`() {
        val out = WebSearchService.normalizeHits(
            listOf(WebSearchHit("t", "https://a.example/1", "第一行\n\n\t第二行  第三行")),
            1
        )
        assertEquals("第一行 第二行 第三行", out.single().snippet)
    }

    // ------------------------------------------------------------------ provider 名字

    @Test
    fun `provider names normalize case and legacy deepseek`() {
        assertEquals(Providers.MESSAGEAPI, Providers.normalize("deepseek"))
        assertEquals(Providers.MESSAGEAPI, Providers.normalize("  DEEPSEEK "))
        assertEquals(Providers.SEARXNG, Providers.normalize("searxng"))
        assertEquals(Providers.BING, Providers.normalize("Bing"))
        assertEquals(Providers.ZHIHU, Providers.normalize("zhihu"))
        // 脏值/空值退回默认 provider，而不是让整个工具不可用。
        assertEquals(Providers.MESSAGEAPI, Providers.normalize(""))
        assertEquals(Providers.MESSAGEAPI, Providers.normalize(null))
        assertEquals(Providers.MESSAGEAPI, Providers.normalize("SOMETHING_ELSE"))
    }

    @Test
    fun `site providers map to the right domains and only they are site scoped`() {
        assertEquals("zhihu.com", Providers.siteFor("ZHIHU"))
        assertEquals("toutiao.com", Providers.siteFor("TOUTIAO"))
        assertEquals("quora.com", Providers.siteFor("QUORA"))
        assertNull(Providers.siteFor("SEARXNG"))
        assertTrue(Providers.isSiteScoped("ZHIHU"))
        assertTrue(!Providers.isSiteScoped("BING"))
    }

    @Test
    fun `only self hosted providers may omit the key`() {
        assertTrue(Providers.requiresApiKey("MESSAGEAPI"))
        assertTrue(Providers.requiresApiKey("TAVILY"))
        assertTrue(Providers.requiresApiKey("BRAVE"))
        assertTrue(Providers.requiresApiKey("SERPER"))
        assertTrue(Providers.requiresApiKey("BING"))
        assertTrue(!Providers.requiresApiKey("GENERIC"))
        assertTrue(!Providers.requiresApiKey("SEARXNG"))
    }

    @Test
    fun `default base urls match the client contract`() {
        assertEquals("https://api.tavily.com", Providers.defaultBaseUrl("TAVILY"))
        assertEquals("https://api.search.brave.com/res/v1", Providers.defaultBaseUrl("BRAVE"))
        assertEquals("https://google.serper.dev", Providers.defaultBaseUrl("SERPER"))
        assertEquals("https://api.bing.microsoft.com", Providers.defaultBaseUrl("BING"))
        assertNull(Providers.defaultBaseUrl("SEARXNG"))
        assertNull(Providers.defaultBaseUrl("GENERIC"))
        assertNull(Providers.defaultBaseUrl("MESSAGEAPI"))
    }

    @Test
    fun `site engine normalizes to searxng or bing only`() {
        assertEquals(Providers.SEARXNG, Providers.normalizeEngine(null))
        assertEquals(Providers.SEARXNG, Providers.normalizeEngine(""))
        assertEquals(Providers.SEARXNG, Providers.normalizeEngine("GOOGLE"))
        assertEquals(Providers.BING, Providers.normalizeEngine("bing"))
        assertEquals(Providers.SEARXNG, Providers.normalizeEngine("searxng"))
    }

    @Test
    fun `count normalizes like tool args`() {
        assertEquals(6, Providers.normalizeCount(null))
        assertEquals(1, Providers.normalizeCount(0))
        assertEquals(1, Providers.normalizeCount(-5))
        assertEquals(12, Providers.normalizeCount(99))
        assertEquals(7, Providers.normalizeCount(7))
    }

    @Test
    fun `site scoped provider appends site once`() {
        val scoped = SiteScopedProvider(Providers.implementation("SEARXNG"), "zhihu.com")
        assertEquals("Kotlin site:zhihu.com", scoped.scopedQuery("Kotlin"))
        assertEquals("Kotlin site:zhihu.com", scoped.scopedQuery("  Kotlin  "))
        assertEquals("Kotlin site:zhihu.com", scoped.scopedQuery("Kotlin site:zhihu.com"))
        assertEquals("site:zhihu.com", scoped.scopedQuery("   "))
        // 只有独立的 site: 词元才算「已经限定过」；`mysite:zhihu.com` 不是。
        assertEquals("mysite:zhihu.com site:zhihu.com", scoped.scopedQuery("mysite:zhihu.com"))
    }

    // ------------------------------------------------------------------ 端点与编码

    @Test
    fun `query components use percent twenty for spaces`() {
        assertEquals("a%20b", SearchHttpClient.encodeComponent("a b"))
        assertEquals("%E6%9D%AD%E5%B7%9E", SearchHttpClient.encodeComponent("杭州"))
        val url = SearchHttpClient.appendQuery("http://h/search", listOf("q" to "杭 州", "format" to "json"))
        assertEquals("http://h/search?q=%E6%9D%AD%20%E5%B7%9E&format=json", url)
        val withExisting = SearchHttpClient.appendQuery("http://h/search?x=1", listOf("q" to "a"))
        assertEquals("http://h/search?x=1&q=a", withExisting)
    }

    @Test
    fun `message api endpoint normalisation handles trailing slashes and v1`() {
        assertEquals(
            "https://api.deepseek.com/v1/tools/search",
            Endpoints.messageApi("https://api.deepseek.com/", "search")
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/fetch",
            Endpoints.messageApi("https://api.deepseek.com/v1", "fetch")
        )
        assertEquals(
            "https://api.example.com/v1/tools/search",
            Endpoints.messageApi("https://api.example.com/v1/tools/fetch", "search")
        )
    }

    @Test
    fun `base urls must be http or https with a usable host`() {
        val failures = listOf("", "ftp://x.example", "http://", "https://user:pass@x.example", "http://x example")
        failures.forEach { base ->
            val failure = runCatching { Endpoints.requireHttpBase(base, "SEARXNG") }.exceptionOrNull()
            assertTrue("base=<$base> 应该被拒", failure is SearchException)
        }
        assertEquals("https://x.example", Endpoints.requireHttpBase("https://x.example/", "SEARXNG"))
    }
}
