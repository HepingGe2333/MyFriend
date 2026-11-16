package com.yap.agentd.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每个 provider 的「请求形状 + 解析」契约测试。
 *
 * 用 JDK 自带的 `com.sun.net.httpserver.HttpServer` 假装 provider（**完全不联网**），
 * 逐条钉住 `spec/web-search/README.md` 与 `docs/design/server-authoritative.md` §3.5 里的线上形状：
 * - `MESSAGEAPI`：`POST {base}/v1/tools/search`，`{query,count,need_content:false}`，Bearer；
 * - `TAVILY`：`POST {base}/search`，body 里也带 `api_key`；
 * - `SEARXNG`（新增）：`GET {base}/search?q=&format=json&categories=general`，无 key；
 * - `BING`（新增）：`GET {base}/v7.0/search?q=&count=&mkt=zh-CN`，`Ocp-Apim-Subscription-Key`；
 * - `BRAVE` / `SERPER` / `GENERIC`：0.12 之前就有的三种，形状不变；
 * - 站点过滤源 `ZHIHU` / `TOUTIAO` / `QUORA`：只是把 `site:` 拼进 query，再走 SEARXNG/BING。
 *
 * 响应样例是按真实接口字段写的（SearXNG 的 `results[].content`、Bing 的 `webPages.value[].snippet`…），
 * 但都是本地假服务端返回的：这个测试在任何没有网络的机器上都必须通过。
 */
class SearchProviderContractTest {

    private val service = WebSearchService()

    // ------------------------------------------------------------------ MESSAGEAPI

    @Test
    fun `messageapi sends query count and need_content and parses results`() {
        val body = """
            {"results":[
              {"title":"杭州天气预报","url":"https://weather.example/hz","snippet":"多云 18~24℃","summary":"总览"},
              {"title":"","url":"https://news.example/hz","description":"第二条用的是 description 字段"}
            ]}
        """.trimIndent()
        FakeHttpServer { FakeHttpServer.FakeResponse(200, body) }.use { server ->
            val hits = service.search(
                SearchRequest(
                    query = "杭州 天气",
                    count = 2,
                    provider = "MESSAGEAPI",
                    baseUrl = server.baseUrl,
                    apiKey = "sk-test-secret"
                )
            )

            val request = server.onlyRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/tools/search", request.path)
            assertEquals("Bearer sk-test-secret", request.header("Authorization"))
            assertTrue(request.body, request.body.contains("\"query\":\"杭州 天气\""))
            assertTrue(request.body, request.body.contains("\"count\":2"))
            assertTrue(request.body, request.body.contains("\"need_content\":false"))

            assertEquals(2, hits.size)
            assertEquals("杭州天气预报", hits[0].title)
            assertEquals("https://weather.example/hz", hits[0].url)
            assertEquals("多云 18~24℃", hits[0].snippet)
            // 标题缺失时保留空串（与客户端一致），但 url/snippet 仍然要解析出来。
            assertEquals("", hits[1].title)
            assertEquals("第二条用的是 description 字段", hits[1].snippet)
        }
    }

    @Test
    fun `messageapi endpoint normalises v1 suffix and tools path`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"results":[]}""") }.use { server ->
            // 已经带 /v1/tools/search 的 base 也要拼回同一个端点，不能变成 .../v1/v1/tools/search。
            service.search(
                SearchRequest(
                    query = "x",
                    provider = "MESSAGEAPI",
                    baseUrl = "${server.baseUrl}/v1/tools/search",
                    apiKey = "k"
                )
            )
            assertEquals("/v1/tools/search", server.onlyRequest().path)
        }
    }

    // ------------------------------------------------------------------ TAVILY

    @Test
    fun `tavily sends api key in body and parses content`() {
        val body = """
            {"query":"kotlin","response_time":0.8,"results":[
              {"title":"Kotlin 官方文档","url":"https://kotlinlang.org/docs","content":"Kotlin 是一门 JVM 上的静态语言。","score":0.98}
            ]}
        """.trimIndent()
        FakeHttpServer { FakeHttpServer.FakeResponse(200, body) }.use { server ->
            val hits = service.search(
                SearchRequest(
                    query = "kotlin",
                    count = 3,
                    provider = "TAVILY",
                    baseUrl = server.baseUrl,
                    apiKey = "tvly-secret"
                )
            )

            val request = server.onlyRequest()
            assertEquals("POST", request.method)
            assertEquals("/search", request.path)
            assertEquals("Bearer tvly-secret", request.header("Authorization"))
            assertTrue(request.body, request.body.contains("\"api_key\":\"tvly-secret\""))
            assertTrue(request.body, request.body.contains("\"max_results\":3"))
            assertTrue(request.body, request.body.contains("\"include_answer\":false"))

            assertEquals(1, hits.size)
            assertEquals("https://kotlinlang.org/docs", hits[0].url)
            assertEquals("Kotlin 是一门 JVM 上的静态语言。", hits[0].snippet)
        }
    }

    // ------------------------------------------------------------------ SEARXNG（新增）

    @Test
    fun `searxng sends encoded query with json format and parses real shaped results`() {
        // 真实 SearXNG 的响应样例（results[].content 就是摘要；还带 engine/score/parsed_url 等字段）。
        val body = """
            {
              "query": "杭州 天气",
              "number_of_results": 0,
              "results": [
                {
                  "url": "https://www.weather.com.cn/weather/101210101.shtml",
                  "title": "杭州天气预报",
                  "content": "杭州今天多云，18~24℃，东风 2 级。",
                  "engine": "baidu",
                  "template": "default.html",
                  "parsed_url": ["https", "www.weather.com.cn", "/weather/101210101.shtml", "", "", ""],
                  "engines": ["baidu", "bing"],
                  "positions": [1, 2],
                  "score": 3.0,
                  "category": "general",
                  "publishedDate": null
                },
                {
                  "url": "https://www.hznews.example/weather",
                  "title": "杭州未来一周天气",
                  "content": "未来一周杭州以多云为主，周末有小雨。",
                  "engine": "bing",
                  "score": 1.5,
                  "category": "general"
                }
              ],
              "answers": [],
              "corrections": [],
              "infoboxes": [],
              "suggestions": [],
              "unresponsive_engines": []
            }
        """.trimIndent()
        FakeHttpServer { FakeHttpServer.FakeResponse(200, body) }.use { server ->
            val hits = service.search(
                SearchRequest(
                    query = "杭州 天气",
                    count = 6,
                    provider = "SEARXNG",
                    baseUrl = server.baseUrl
                )
            )

            val request = server.onlyRequest()
            assertEquals("GET", request.method)
            assertEquals("/search", request.path)
            assertEquals("杭州 天气", request.query["q"])
            assertEquals("json", request.query["format"])
            assertEquals("general", request.query["categories"])
            // SearXNG 不需要 key：带上 Authorization 反而会被某些实例/反代当成错误配置。
            assertNull(request.header("Authorization"))

            assertEquals(2, hits.size)
            assertEquals("杭州天气预报", hits[0].title)
            assertEquals("杭州今天多云，18~24℃，东风 2 级。", hits[0].snippet)
            assertEquals("杭州未来一周天气", hits[1].title)
        }
    }

    @Test
    fun `searxng base may already end with search`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"results":[]}""") }.use { server ->
            service.search(
                SearchRequest(query = "x", provider = "SEARXNG", baseUrl = "${server.baseUrl}/search")
            )
            assertEquals("/search", server.onlyRequest().path)
        }
    }

    // ------------------------------------------------------------------ BING（新增）

    @Test
    fun `bing sends subscription key and market and parses web pages`() {
        // 真实 Bing Web Search v7 的响应样例（webPages.value[] 用 name / url / snippet）。
        val body = """
            {
              "_type": "SearchResponse",
              "queryContext": { "originalQuery": "杭州 天气" },
              "webPages": {
                "webSearchUrl": "https://www.bing.com/search?q=%E6%9D%AD%E5%B7%9E",
                "totalEstimatedMatches": 1230000,
                "value": [
                  {
                    "id": "https://api.bing.microsoft.com/api/v7/#WebPages.0",
                    "name": "杭州天气预报",
                    "url": "https://www.weather.com.cn/weather/101210101.shtml",
                    "displayUrl": "https://www.weather.com.cn/weather/101210101.shtml",
                    "snippet": "杭州今天多云转晴，气温 18~24℃。",
                    "dateLastCrawled": "2026-11-15T00:00:00.0000000Z",
                    "language": "zh-Hans",
                    "isFamilyFriendly": true
                  },
                  {
                    "id": "https://api.bing.microsoft.com/api/v7/#WebPages.1",
                    "name": "杭州一周天气",
                    "url": "https://hz.example/forecast",
                    "snippet": "本周末有小雨，注意带伞。"
                  }
                ]
              },
              "rankingResponse": { "mainline": { "items": [] } }
            }
        """.trimIndent()
        FakeHttpServer { FakeHttpServer.FakeResponse(200, body) }.use { server ->
            val hits = service.search(
                SearchRequest(
                    query = "杭州 天气",
                    count = 5,
                    provider = "BING",
                    baseUrl = server.baseUrl,
                    apiKey = "bing-secret"
                )
            )

            val request = server.onlyRequest()
            assertEquals("GET", request.method)
            assertEquals("/v7.0/search", request.path)
            assertEquals("杭州 天气", request.query["q"])
            assertEquals("5", request.query["count"])
            assertEquals("zh-CN", request.query["mkt"])
            assertEquals("bing-secret", request.header("Ocp-Apim-Subscription-Key"))
            // 不要顺手把 key 塞进 Authorization（Bing 不认，还会让日志多一处泄漏面）。
            assertNull(request.header("Authorization"))

            assertEquals(2, hits.size)
            assertEquals("杭州天气预报", hits[0].title)
            assertEquals("https://www.weather.com.cn/weather/101210101.shtml", hits[0].url)
            assertEquals("杭州今天多云转晴，气温 18~24℃。", hits[0].snippet)
        }
    }

    @Test
    fun `bing base may already end with the search path`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"webPages":{"value":[]}}""") }.use { server ->
            service.search(
                SearchRequest(
                    query = "x",
                    provider = "BING",
                    baseUrl = "${server.baseUrl}/v7.0/search",
                    apiKey = "k"
                )
            )
            assertEquals("/v7.0/search", server.onlyRequest().path)
        }
    }

    // ------------------------------------------------------------------ 站点过滤源

    @Test
    fun `zhihu scopes the query through searxng`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"results":[{"title":"t","url":"https://www.zhihu.com/question/1","content":"c"}]}""") }
            .use { server ->
                val hits = service.search(
                    SearchRequest(query = "怎么学 Kotlin", provider = "ZHIHU", baseUrl = server.baseUrl)
                )
                val request = server.onlyRequest()
                assertEquals("/search", request.path)
                assertEquals("怎么学 Kotlin site:zhihu.com", request.query["q"])
                assertEquals("https://www.zhihu.com/question/1", hits.single().url)
            }
    }

    @Test
    fun `toutiao scopes the query through searxng`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"results":[]}""") }.use { server ->
            service.search(SearchRequest(query = "今日新闻", provider = "TOUTIAO", baseUrl = server.baseUrl))
            assertEquals("今日新闻 site:toutiao.com", server.onlyRequest().query["q"])
        }
    }

    @Test
    fun `quora can be scoped through bing`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"webPages":{"value":[]}}""") }.use { server ->
            service.search(
                SearchRequest(
                    query = "kotlin coroutines",
                    provider = "QUORA",
                    engine = "BING",
                    baseUrl = server.baseUrl,
                    apiKey = "bing-key"
                )
            )
            val request = server.onlyRequest()
            assertEquals("/v7.0/search", request.path)
            assertEquals("kotlin coroutines site:quora.com", request.query["q"])
            assertEquals("bing-key", request.header("Ocp-Apim-Subscription-Key"))
        }
    }

    @Test
    fun `query that already contains the site filter is not scoped twice`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"results":[]}""") }.use { server ->
            service.search(SearchRequest(query = "Kotlin site:zhihu.com", provider = "ZHIHU", baseUrl = server.baseUrl))
            assertEquals("Kotlin site:zhihu.com", server.onlyRequest().query["q"])
        }
    }

    // ------------------------------------------------------------------ BRAVE / SERPER / GENERIC

    @Test
    fun `brave sends subscription token and parses web results`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"web":{"results":[{"title":"Brave","url":"https://brave.example/1","description":"desc"}]}}""") }
            .use { server ->
                val hits = service.search(
                    SearchRequest(query = "news", count = 2, provider = "BRAVE", baseUrl = server.baseUrl, apiKey = "brave-key")
                )
                val request = server.onlyRequest()
                assertEquals("GET", request.method)
                assertEquals("/web/search", request.path)
                assertEquals("news", request.query["q"])
                assertEquals("2", request.query["count"])
                assertEquals("brave-key", request.header("X-Subscription-Token"))
                assertEquals("desc", hits.single().snippet)
            }
    }

    @Test
    fun `serper sends x api key and parses organic`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"organic":[{"title":"S","link":"https://s.example/1","snippet":"snip"}]}""") }
            .use { server ->
                val hits = service.search(
                    SearchRequest(query = "q", provider = "SERPER", baseUrl = server.baseUrl, apiKey = "serper-key")
                )
                val request = server.onlyRequest()
                assertEquals("POST", request.method)
                assertEquals("/search", request.path)
                assertEquals("serper-key", request.header("X-API-KEY"))
                assertTrue(request.body, request.body.contains("\"q\":\"q\""))
                assertTrue(request.body, request.body.contains("\"num\":6"))
                assertEquals("https://s.example/1", hits.single().url)
            }
    }

    @Test
    fun `generic uses the base url as is and allows no key`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"results":[{"title":"G","url":"https://g.example/1","snippet":"gs"}]}""") }
            .use { server ->
                val hits = service.search(
                    SearchRequest(query = "q", provider = "GENERIC", baseUrl = "${server.baseUrl}/custom/endpoint")
                )
                val request = server.onlyRequest()
                assertEquals("POST", request.method)
                assertEquals("/custom/endpoint", request.path)
                assertNull(request.header("Authorization"))
                assertEquals("G", hits.single().title)
            }
    }

    // ------------------------------------------------------------------ 错误与安全

    @Test
    fun `http status codes map to typed errors`() {
        val expected = mapOf(
            401 to SearchErrorKind.UNAUTHORIZED,
            403 to SearchErrorKind.FORBIDDEN,
            429 to SearchErrorKind.RATE_LIMITED,
            500 to SearchErrorKind.SERVER_ERROR,
            503 to SearchErrorKind.SERVER_ERROR,
            404 to SearchErrorKind.HTTP_STATUS
        )
        expected.forEach { (status, kind) ->
            FakeHttpServer { FakeHttpServer.FakeResponse(status, """{"error":"nope"}""") }.use { server ->
                val failure = runCatching {
                    service.search(SearchRequest(query = "q", provider = "BING", baseUrl = server.baseUrl, apiKey = "bing-key"))
                }.exceptionOrNull()
                val error = (failure as? SearchException)?.error
                    ?: error("status $status 应该抛 SearchException，实际：$failure")
                assertEquals("status $status", kind, error.kind)
                assertEquals("BING", error.provider)
                assertEquals(status, error.status)
            }
        }
    }

    @Test
    fun `error logs contain provider and status but never the key`() {
        val logs = ArrayList<String>()
        val client = SearchHttpClient(log = { logs += it })
        val service = WebSearchService(client)
        FakeHttpServer { request ->
            // 故意让 provider 把 key 回显在错误体里（真实世界里也可能发生），日志必须把它抹掉。
            FakeHttpServer.FakeResponse(401, "bad key: ${request.header("Authorization").orEmpty()}")
        }.use { server ->
            runCatching {
                service.search(
                    SearchRequest(query = "q", provider = "TAVILY", baseUrl = server.baseUrl, apiKey = "tvly-very-secret")
                )
            }
        }
        assertTrue("应该写一条错误日志", logs.isNotEmpty())
        val joined = logs.joinToString("\n")
        assertTrue(joined, joined.contains("provider=TAVILY"))
        assertTrue(joined, joined.contains("status=401"))
        assertTrue("日志里不能出现 key：$joined", !joined.contains("tvly-very-secret"))
        assertTrue("日志里不能出现 Bearer 拼出来的 key：$joined", !joined.contains("Bearer tvly-very-secret"))
    }

    @Test
    fun `missing key is reported instead of silent empty results`() {
        val failure = runCatching {
            service.search(SearchRequest(query = "q", provider = "BING", baseUrl = "https://api.bing.microsoft.com", apiKey = ""))
        }.exceptionOrNull()
        assertEquals(SearchErrorKind.MISSING_KEY, (failure as? SearchException)?.error?.kind)
    }

    @Test
    fun `searxng without base is reported as missing base`() {
        val failure = runCatching {
            service.search(SearchRequest(query = "q", provider = "SEARXNG"))
        }.exceptionOrNull()
        assertEquals(SearchErrorKind.MISSING_BASE, (failure as? SearchException)?.error?.kind)
    }

    @Test
    fun `empty query is rejected before any request`() {
        val failure = runCatching { service.search(SearchRequest(query = "   ", provider = "SEARXNG", baseUrl = "http://127.0.0.1:1")) }
            .exceptionOrNull()
        assertEquals(SearchErrorKind.EMPTY_QUERY, (failure as? SearchException)?.error?.kind)
    }

    @Test
    fun `non http base is rejected as invalid base`() {
        val failure = runCatching {
            service.search(SearchRequest(query = "q", provider = "SEARXNG", baseUrl = "ftp://search.example"))
        }.exceptionOrNull()
        assertEquals(SearchErrorKind.INVALID_BASE, (failure as? SearchException)?.error?.kind)
    }

    @Test
    fun `invalid json maps to unparsable`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, "<html>not json</html>", contentType = "text/html") }.use { server ->
            val failure = runCatching {
                service.search(SearchRequest(query = "q", provider = "SEARXNG", baseUrl = server.baseUrl))
            }.exceptionOrNull()
            assertEquals(SearchErrorKind.UNPARSABLE, (failure as? SearchException)?.error?.kind)
        }
    }

    @Test
    fun `response larger than two mebibytes is rejected`() {
        val oversized = ByteArray(SearchLimits.MAX_RESPONSE_BYTES + 1024) { 'a'.code.toByte() }
        FakeHttpServer { FakeHttpServer.FakeResponse(200, bytes = oversized) }.use { server ->
            val failure = runCatching {
                service.search(SearchRequest(query = "q", provider = "SEARXNG", baseUrl = server.baseUrl))
            }.exceptionOrNull()
            assertEquals(SearchErrorKind.RESPONSE_TOO_LARGE, (failure as? SearchException)?.error?.kind)
        }
    }

    @Test
    fun `count is clamped to one through twelve`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"webPages":{"value":[]}}""") }.use { server ->
            service.search(
                SearchRequest(query = "q", count = 99, provider = "BING", baseUrl = server.baseUrl, apiKey = "k")
            )
            assertEquals("12", server.requests.last().query["count"])
            service.search(
                SearchRequest(query = "q", count = 0, provider = "BING", baseUrl = server.baseUrl, apiKey = "k")
            )
            assertEquals("1", server.requests.last().query["count"])
        }
    }
}
