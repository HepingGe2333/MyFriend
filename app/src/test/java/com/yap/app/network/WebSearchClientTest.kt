package com.yap.app.network

import com.yap.app.domain.NetworkErrorKind
import com.yap.app.domain.WebToolsProviders
import com.yap.app.domain.WebUrlPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多家搜索 provider 的纯 JVM 单测（不发真网）。
 *
 * 用 OkHttp `Interceptor` 冒充服务端：既能逐项检查「方法 / 路径 / 头 / body」，
 * 又能返回任意状态码、坏 JSON、超大响应——比 MockWebServer 少一个依赖，
 * 也不会受本机端口/防火墙影响。
 */
class WebSearchClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val server = FakeServer()
    private val client = WebSearchClient(
        client = OkHttpClient.Builder().addInterceptor(server).build(),
        json = json
    )

    // ---------------------------------------------------------------- MessageAPI（老名字 DEEPSEEK）

    @Test
    fun `MESSAGEAPI 搜索保持原有请求形状与解析`() = runBlocking {
        server.respond { FakeResponse(body = """{"results":[{"title":"Kotlin","url":"https://kotlinlang.org","snippet":"现代语言","summary":"官方文档","site_name":"kotlinlang.org","date":"2024-11-20","authority":8,"rerank_score":0.9}]}""") }

        val hits = client.search(
            provider = WebToolsProviders.MESSAGEAPI,
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "sk-deepseek",
            query = "kotlin",
            count = 3,
            oriQuestion = "kotlin 是什么",
            needContent = true
        )

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.deepseek.com/v1/tools/search", request.url)
        assertEquals("Bearer sk-deepseek", request.header("Authorization"))
        val body = json.parseToJsonElement(request.body).jsonObject
        assertEquals("kotlin", body["query"]!!.jsonPrimitive.content)
        assertEquals("3", body["count"]!!.jsonPrimitive.content)
        assertEquals("kotlin 是什么", body["ori_question"]!!.jsonPrimitive.content)
        assertEquals("true", body["need_content"]!!.jsonPrimitive.content)
        assertEquals(1, hits.size)
        assertEquals("Kotlin", hits[0].title)
        assertEquals("https://kotlinlang.org", hits[0].url)
        assertEquals("现代语言", hits[0].snippet)
        assertEquals("官方文档", hits[0].summary)
        assertEquals(8, hits[0].authority)
    }

    @Test
    fun `MESSAGEAPI 抓取仍走服务端 fetch`() = runBlocking {
        server.respond { FakeResponse(body = """{"url":"https://example.com/a","title":"示例","content":"正文"}""") }

        val page = client.fetch(
            provider = WebToolsProviders.MESSAGEAPI,
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-deepseek",
            url = "https://example.com/a"
        )

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.deepseek.com/v1/tools/fetch", request.url)
        assertEquals("示例", page.title)
        assertEquals("正文", page.markdown)
    }

    // ---------------------------------------------------------------- TAVILY / BRAVE / SERPER / GENERIC

    @Test
    fun `TAVILY 请求方法路径头与解析严格按规范`() = runBlocking {
        server.respond { FakeResponse(body = """{"results":[{"title":"T","url":"https://t.example/a","content":"tavily snippet"}]}""") }

        val hits = client.search(
            provider = WebToolsProviders.TAVILY,
            baseUrl = "",
            apiKey = "tvly-key",
            query = "hello world",
            count = 4
        )

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.tavily.com/search", request.url)
        assertEquals("Bearer tvly-key", request.header("Authorization"))
        val body = json.parseToJsonElement(request.body).jsonObject
        assertEquals("tvly-key", body["api_key"]!!.jsonPrimitive.content)
        assertEquals("hello world", body["query"]!!.jsonPrimitive.content)
        assertEquals("4", body["max_results"]!!.jsonPrimitive.content)
        assertEquals("false", body["include_answer"]!!.jsonPrimitive.content)
        assertEquals("tavily snippet", hits.single().snippet)
    }

    @Test
    fun `BRAVE 是 GET 查询参数与 X-Subscription-Token`() = runBlocking {
        server.respond { FakeResponse(body = """{"web":{"results":[{"title":"B","url":"https://b.example/a","description":"brave snippet"}]}}""") }

        val hits = client.search(
            provider = WebToolsProviders.BRAVE,
            baseUrl = "",
            apiKey = "brave-key",
            query = "kotlin coroutines",
            count = 2
        )

        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertTrue("默认地址 + /web/search：${request.url}", request.url.startsWith("https://api.search.brave.com/res/v1/web/search?"))
        assertTrue("query 要 urlencode：${request.url}", request.url.contains("q=kotlin%20coroutines"))
        assertTrue("count 要带上：${request.url}", request.url.contains("count=2"))
        assertEquals("brave-key", request.header("X-Subscription-Token"))
        assertEquals("brave snippet", hits.single().snippet)
    }

    @Test
    fun `SERPER 是 POST X-API-KEY 与 organic`() = runBlocking {
        server.respond { FakeResponse(body = """{"organic":[{"title":"S","link":"https://s.example/a","snippet":"serper snippet"}]}""") }

        val hits = client.search(
            provider = WebToolsProviders.SERPER,
            baseUrl = "",
            apiKey = "serper-key",
            query = "q",
            count = 5
        )

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://google.serper.dev/search", request.url)
        assertEquals("serper-key", request.header("X-API-KEY"))
        val body = json.parseToJsonElement(request.body).jsonObject
        assertEquals("q", body["q"]!!.jsonPrimitive.content)
        assertEquals("5", body["num"]!!.jsonPrimitive.content)
        assertEquals("https://s.example/a", hits.single().url)
    }

    @Test
    fun `GENERIC 原样使用自定义 base 且无 Key 也可用`() = runBlocking {
        server.respond { FakeResponse(body = """{"results":[{"title":"G","url":"https://g.example/a","snippet":"generic snippet"}]}""") }

        val hits = client.search(
            provider = WebToolsProviders.GENERIC,
            baseUrl = "https://search.example.com/api",
            apiKey = "",
            query = "q",
            count = 6
        )

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://search.example.com/api", request.url)
        assertEquals("没有 Key 时不该带 Authorization", null, request.header("Authorization"))
        val body = json.parseToJsonElement(request.body).jsonObject
        assertEquals("q", body["query"]!!.jsonPrimitive.content)
        assertEquals("6", body["count"]!!.jsonPrimitive.content)
        assertEquals("generic snippet", hits.single().snippet)
    }

    @Test
    fun `GENERIC 有 Key 时带 Bearer`() = runBlocking {
        server.respond { FakeResponse(body = """{"results":[]}""") }

        client.search(
            provider = WebToolsProviders.GENERIC,
            baseUrl = "https://search.example.com",
            apiKey = "g-key",
            query = "q"
        )

        assertEquals("Bearer g-key", server.requests.single().header("Authorization"))
    }

    // ---------------------------------------------------------------- 错误与限额

    @Test
    fun `缺 Key 时明确报错而不是空结果 GENERIC 除外`() = runBlocking {
        listOf(
            WebToolsProviders.MESSAGEAPI,
            WebToolsProviders.TAVILY,
            WebToolsProviders.BRAVE,
            WebToolsProviders.SERPER
        ).forEach { provider ->
            val error = runCatching {
                client.search(provider, "https://api.example.com", "", "q")
            }.exceptionOrNull()
            assertTrue("$provider 缺 Key 必须是 ApiException：$error", error is ApiException)
            assertEquals(
                "$provider 缺 Key 要映射到 WebKeyMissing",
                NetworkErrorKind.WebKeyMissing,
                (error as ApiException).kind
            )
        }
        assertTrue("缺 Key 不该发出请求", server.requests.isEmpty())
    }

    @Test
    fun `401 与 429 映射到 WebStatus`() = runBlocking {
        listOf(401, 429).forEach { status ->
            server.reset()
            server.respond { FakeResponse(code = status, body = """{"error":{"message":"nope"}}""") }
            val error = runCatching {
                client.search(WebToolsProviders.TAVILY, "", "key", "q")
            }.exceptionOrNull()
            assertEquals(
                "HTTP $status 要带状态码",
                NetworkErrorKind.WebStatus(status),
                (error as ApiException).kind
            )
        }
    }

    @Test
    fun `响应超过 2MiB 被拒绝`() = runBlocking {
        server.respond { FakeResponse(body = "x".repeat(WebSearchClient.MAX_RESPONSE_BYTES + 1)) }
        val error = runCatching {
            client.search(WebToolsProviders.TAVILY, "", "key", "q")
        }.exceptionOrNull()
        assertTrue(error is ApiException)
        assertTrue(
            "超大响应要映射成 ResponseTooLong：${(error as ApiException).kind}",
            error.kind is NetworkErrorKind.ResponseTooLong
        )
    }

    @Test
    fun `坏 JSON 报 WebUnparsable 缺字段被容错丢弃`() = runBlocking {
        server.respond { FakeResponse(body = """{"results":[}""") }
        val bad = runCatching { client.search(WebToolsProviders.TAVILY, "", "key", "q") }.exceptionOrNull()
        assertEquals(NetworkErrorKind.WebUnparsable, (bad as ApiException).kind)

        server.reset()
        server.respond { FakeResponse(body = """{"results":[{},{ "title":"没有链接" }]}""") }
        assertTrue("缺 url 的结果要丢掉", client.search(WebToolsProviders.TAVILY, "", "key", "q").isEmpty())

        server.reset()
        server.respond { FakeResponse(body = """{}""") }
        assertTrue("没有 results 字段当空结果", client.search(WebToolsProviders.TAVILY, "", "key", "q").isEmpty())

        server.reset()
        server.respond { FakeResponse(body = """{"results":"oops"}""") }
        assertTrue("results 不是数组当空结果", client.search(WebToolsProviders.TAVILY, "", "key", "q").isEmpty())
    }

    @Test
    fun `归一化去重丢非http并截断snippet`() {
        val hits = client.normalizeHits(
            listOf(
                WebSearchHit(title = " A ", url = "https://a.example/1", snippet = "x".repeat(600)),
                WebSearchHit(title = "dup", url = "https://a.example/1", snippet = "dup"),
                WebSearchHit(title = "ftp", url = "ftp://a.example/2"),
                WebSearchHit(title = "B", url = "http://b.example/2", snippet = "  b   b  "),
                WebSearchHit(title = "C", url = "javascript:alert(1)")
            ),
            count = 6
        )
        assertEquals(2, hits.size)
        assertEquals("https://a.example/1", hits[0].url)
        assertEquals("A", hits[0].title)
        assertEquals(500, hits[0].snippet.length)
        assertEquals("http://b.example/2", hits[1].url)
        assertEquals("b b", hits[1].snippet)
    }

    @Test
    fun `最多返回 count 条 且 count 上限 12`() {
        val many = (1..20).map { WebSearchHit(url = "https://x.example/$it") }
        assertEquals(3, client.normalizeHits(many, 3).size)
        assertEquals(12, client.normalizeHits(many, 50).size)
        assertEquals(6, client.normalizeHits(many, 0).size)
    }

    @Test
    fun `GENERIC 没有 base URL 时报 WebBaseMissing`() = runBlocking {
        val error = runCatching {
            client.search(WebToolsProviders.GENERIC, "", "", "q")
        }.exceptionOrNull()
        assertEquals(NetworkErrorKind.WebBaseMissing, (error as ApiException).kind)
    }

    // ---------------------------------------------------------------- web_fetch（客户端抓取）

    @Test
    fun `非 MESSAGEAPI 的 web_fetch 由客户端 GET 并抽正文`() = runBlocking {
        server.respond {
            FakeResponse(
                body = """
                    <html><head><title>示例 &amp; 标题</title>
                    <style>body{color:red}</style></head>
                    <body><script>alert('x')</script>
                    <p>第一段   正文</p><p>第二段</p></body></html>
                """.trimIndent()
            )
        }

        val page = client.fetch(
            provider = WebToolsProviders.TAVILY,
            baseUrl = "",
            apiKey = "",
            url = "https://example.com/page"
        )

        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://example.com/page", request.url)
        assertEquals("Yap/1.0", request.header("User-Agent"))
        assertEquals("示例 & 标题", page.title)
        assertFalse("script 内容要被去掉", page.markdown.contains("alert"))
        assertFalse("style 内容要被去掉", page.markdown.contains("color:red"))
        assertFalse("标签要被去掉", page.markdown.contains("<p>"))
        assertEquals("第一段 正文 第二段", page.markdown)
    }

    @Test
    fun `web_fetch 正文超过 8000 字符被截断`() = runBlocking {
        server.respond { FakeResponse(body = "<html><body>" + "字".repeat(20_000) + "</body></html>") }
        val page = client.fetch(
            provider = WebToolsProviders.SERPER,
            baseUrl = "",
            apiKey = "",
            url = "https://example.com/long"
        )
        assertEquals(WebSearchClient.MAX_FETCH_CHARS, page.markdown.length)
    }

    @Test
    fun `web_fetch 发请求前必须过 WebUrlPolicy`() = runBlocking {
        listOf(
            "http://127.0.0.1/admin",
            "http://10.0.0.1/x",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "https://user:pass@example.com/x"
        ).forEach { url ->
            val error = runCatching {
                client.fetch(WebToolsProviders.TAVILY, "", "", url)
            }.exceptionOrNull()
            assertTrue("$url 应被本地策略拒绝：$error", error is ApiException)
            assertTrue(
                "$url 的拒绝原因应为 WebUrlRejected：${(error as ApiException).kind}",
                error.kind is NetworkErrorKind.WebUrlRejected
            )
        }
        assertTrue("被策略拒绝时不许发请求", server.requests.isEmpty())
    }

    @Test
    fun `web_fetch 遇到 429 也映射成 WebStatus`() = runBlocking {
        server.respond { FakeResponse(code = 429, body = "{}") }
        val error = runCatching {
            client.fetch(WebToolsProviders.BRAVE, "", "", "https://example.com/x")
        }.exceptionOrNull()
        assertEquals(NetworkErrorKind.WebStatus(429), (error as ApiException).kind)
    }

    @Test
    fun `web_fetch 超大响应也被拒绝`() = runBlocking {
        server.respond { FakeResponse(body = "x".repeat(WebSearchClient.MAX_RESPONSE_BYTES + 1)) }
        val error = runCatching {
            client.fetch(WebToolsProviders.TAVILY, "", "", "https://example.com/x")
        }.exceptionOrNull()
        assertTrue((error as ApiException).kind is NetworkErrorKind.ResponseTooLong)
    }

    // ---------------------------------------------------------------- 端点归一化

    @Test
    fun `MessageAPI 端点归一化保持原行为`() {
        val expected = "https://api.deepseek.com/v1/tools/search"
        assertEquals(expected, client.toolsEndpoint("https://api.deepseek.com", "search"))
        assertEquals(expected, client.toolsEndpoint("https://api.deepseek.com/", "search"))
        assertEquals(expected, client.toolsEndpoint("https://api.deepseek.com/v1", "search"))
        assertEquals("https://api.deepseek.com/v1/tools/fetch", client.toolsEndpoint("https://api.deepseek.com/v1", "fetch"))
    }

    /**
     * 0.12 改名：`DEEPSEEK` → `MESSAGEAPI`。
     *
     * 旧库/旧备份里存的仍是 `DEEPSEEK`（大小写、空白都不标准），读进来必须还是一个**可用**的
     * MESSAGEAPI：走同一套 `/v1/tools/search`、`/v1/tools/fetch`，而不是掉到「客户端抓取」那条分支。
     */
    @Test
    fun `旧值 DEEPSEEK 读进来仍是可用的 MESSAGEAPI`() = runBlocking {
        listOf("DEEPSEEK", "deepseek", " DeepSeek ").forEach { legacy ->
            assertEquals("$legacy 要归一化成 MESSAGEAPI", "MESSAGEAPI", WebToolsProviders.normalize(legacy))
            assertTrue("$legacy 是认识的旧值", WebToolsProviders.isValid(legacy))
        }
        assertTrue("新值当然也合法", WebToolsProviders.isValid("MESSAGEAPI"))

        server.respond {
            FakeResponse(body = """{"results":[{"title":"K","url":"https://k.example/a","snippet":"s"}]}""")
        }
        val hits = client.search(
            provider = "DEEPSEEK",
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "sk-legacy",
            query = "q"
        )
        assertEquals("旧值也要走同一套 tools 搜索端点", "https://api.deepseek.com/v1/tools/search", server.requests.single().url)
        assertEquals(1, hits.size)

        server.reset()
        server.respond { FakeResponse(body = """{"url":"https://example.com/a","title":"t","content":"c"}""") }
        client.fetch(
            provider = " deepseek ",
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-legacy",
            url = "https://example.com/a"
        )
        assertEquals(
            "旧值也要走服务端 fetch（而不是客户端 GET 抓正文）",
            "https://api.deepseek.com/v1/tools/fetch",
            server.requests.single().url
        )
    }

    @Test
    fun `各 provider 的默认地址与自定义地址`() {
        assertEquals("https://api.tavily.com/search", client.providerEndpoint("TAVILY", "", "search", true))
        assertEquals(
            "https://api.search.brave.com/res/v1/web/search",
            client.providerEndpoint("BRAVE", "", "web/search", true)
        )
        assertEquals("https://google.serper.dev/search", client.providerEndpoint("SERPER", "", "search", true))
        assertEquals(
            "https://self.example.com/",
            client.providerEndpoint("GENERIC", "https://self.example.com", "", true)
        )
        assertEquals("TAVILY", WebToolsProviders.normalize("tavily"))
        assertEquals("MESSAGEAPI", WebToolsProviders.normalize("not-a-provider"))
    }

    @Test
    fun `html 标题与实体解码`() {
        assertEquals("A & B", client.extractHtmlTitle("<html><title>A &amp; B</title></html>"))
        assertEquals("第一 第二", client.htmlToText("<div>第一</div>\n\n<div>第二</div>"))
        assertEquals("x<y", client.htmlToText("x&lt;y"))
    }

    // ---------------------------------------------------------------- 假服务端

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private data class FakeResponse(
        val code: Int = 200,
        val body: String = "",
        val contentType: String = "application/json"
    )

    private class FakeServer : Interceptor {
        val requests = mutableListOf<RecordedRequest>()
        private var responder: (RecordedRequest) -> FakeResponse = { FakeResponse() }

        fun respond(responder: (RecordedRequest) -> FakeResponse) {
            this.responder = responder
        }

        fun reset() {
            requests.clear()
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request: Request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val record = RecordedRequest(
                method = request.method,
                url = request.url.toString(),
                headers = request.headers.toMultimap().mapKeys { it.key.lowercase() }
                    .mapValues { it.value.firstOrNull().orEmpty() },
                body = buffer.readUtf8()
            )
            requests += record
            val fake = responder(record)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(fake.code)
                .message("mock")
                .body(fake.body.toResponseBody(fake.contentType.toMediaType()))
                .build()
        }
    }
}
