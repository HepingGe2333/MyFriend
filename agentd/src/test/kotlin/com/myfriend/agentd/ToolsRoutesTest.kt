package com.myfriend.agentd

import com.myfriend.agentd.search.FakeHttpServer
import com.myfriend.agentd.search.SearchHttpClient
import com.myfriend.agentd.search.WebSearchService
import com.myfriend.agentd.store.EventType
import com.myfriend.agentd.store.FileStore
import com.myfriend.agentd.store.MessageRole
import com.myfriend.agentd.store.MessageSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * `/v1/tools/web-search`、`/v1/tools/web-fetch`、`/v1/proactive/tick` 的 handler 级测试。
 *
 * handler 是纯函数（`ToolsRoutes.handle(services, path, body)`），所以这里不起 `agentd` 自己的
 * socket；搜索/抓取打的是 JDK 自带 `com.sun.net.httpserver.HttpServer` 起的**本地假服务端**
 * （[FakeHttpServer]，完全不联网），主动判定用临时数据目录 + 固定随机源。
 *
 * 钉住的东西：
 * - provider / base / key 只从服务端环境读，客户端传的 `baseUrl`（非 SEARXNG）与 `apiKey` 一律忽略；
 * - 上游 401/429/5xx 映射成 401/429/502，配置类错误映射成 400，key 不出现在响应与日志里；
 * - 抓取先过 URL 策略（`127.0.0.1` 必须被拒），正文去标签；
 * - tick 掷中时落一条 `source = PROACTIVE` 的 assistant 消息，同一个 `now` 不重复发；
 * - 没有数据目录时 tick 回 503。
 */
class ToolsRoutesTest {

    private lateinit var root: Path

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-tools-test")
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    // ------------------------------------------------------------------ 工具

    private fun services(
        store: FileStore? = null,
        search: WebSearchService = WebSearchService(),
        env: Map<String, String> = emptyMap(),
        roll: () -> Double = { 0.0 }
    ): AgentdServices = AgentdServices(
        store = store,
        searchService = search,
        env = { name -> env[name] },
        proactiveRoll = roll
    )

    private fun post(path: String, body: String, services: AgentdServices): AgentdApi.Response =
        ToolsRoutes.handle(services, path, body)

    private fun json(response: AgentdApi.Response): com.myfriend.app.core.json.JsonValue.Obj {
        assertEquals("期望 200，实际 ${response.status}：${response.body}", 200, response.status)
        return TestSupport.parse(response.body)
    }

    private fun errorCode(response: AgentdApi.Response): String =
        TestSupport.parse(response.body).obj("error").str("code")

    private fun errorMessage(response: AgentdApi.Response): String =
        TestSupport.parse(response.body).obj("error").str("message")

    private val http = HttpClient.newHttpClient()

    /** 走真实 `ServerSocket`：验证 `AgentdServer` 真的把这三条路由分发给了 `ToolsRoutes`。 */
    private fun httpPost(port: Int, path: String, body: String, token: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun searchEnv(baseUrl: String, key: String = "sk-server-secret") = mapOf(
        ToolsRoutes.ENV_SEARCH_BASE to baseUrl,
        ToolsRoutes.ENV_SEARCH_KEY to key
    )

    // ------------------------------------------------------------------ web-search：配置解析

    @Test
    fun `search defaults to MessageApi and reads base and key from the server environment`() {
        val upstream = """
            {"results":[
              {"title":"杭州天气","url":"https://weather.example/hz","snippet":"<b>多云</b> 18~24℃"},
              {"title":"重复项","url":"https://weather.example/hz","snippet":"按 URL 去重后不该出现"},
              {"title":"非 http","url":"ftp://files.example/x","snippet":"不该出现"},
              {"title":"第二条","url":"https://news.example/hz","snippet":"第二条摘要"}
            ]}
        """.trimIndent()
        FakeHttpServer { FakeHttpServer.FakeResponse(200, upstream) }.use { server ->
            val response = post(
                ToolsRoutes.SEARCH_PATH,
                """{"query":" 杭州 天气 ","count":2}""",
                services(env = searchEnv(server.baseUrl))
            )

            val json = json(response)
            assertEquals(ToolsRoutes.DEFAULT_SEARCH_PROVIDER, json.str("provider"))
            val results = json.objects("results")
            assertEquals(2, results.size)
            assertEquals("杭州天气", results[0].str("title"))
            assertEquals("https://weather.example/hz", results[0].str("url"))
            assertEquals("snippet 的 HTML 标签要去掉", "多云 18~24℃", results[0].str("snippet"))
            // 去重 + 丢非 http(s) 之后，第二条是原始的第四条。
            assertEquals("第二条", results[1].str("title"))

            val request = server.onlyRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/tools/search", request.path)
            assertEquals("Bearer sk-server-secret", request.header("Authorization"))
            assertTrue(request.body, request.body.contains("\"query\":\"杭州 天气\""))
            assertTrue(request.body, request.body.contains("\"count\":2"))
            assertFalse("响应里绝不能出现 key", response.body.contains("sk-server-secret"))
        }
    }

    @Test
    fun `search uses the provider from the environment and lets Searxng override the base`() {
        val upstream = """{"results":[{"title":"SearXNG 结果","url":"https://s.example/1","content":"<em>摘要</em>"}]}"""
        FakeHttpServer { FakeHttpServer.FakeResponse(200, upstream) }.use { server ->
            val env = mapOf(
                ToolsRoutes.ENV_SEARCH_PROVIDER to "SEARXNG",
                // 环境里的 base 是故意打不通的地址：请求体给了 baseUrl 时必须以请求体为准。
                ToolsRoutes.ENV_SEARCH_BASE to "http://127.0.0.1:1/dead"
            )
            val response = post(
                ToolsRoutes.SEARCH_PATH,
                """{"query":"知乎 迁移","baseUrl":"${server.baseUrl}"}""",
                services(env = env)
            )

            val json = json(response)
            assertEquals("SEARXNG", json.str("provider"))
            assertEquals("摘要", json.objects("results").single().str("snippet"))

            val request = server.onlyRequest()
            assertEquals("GET", request.method)
            assertEquals("/search", request.path)
            assertEquals("知乎 迁移", request.query["q"])
            assertEquals("json", request.query["format"])
            assertEquals("general", request.query["categories"])
        }
    }

    @Test
    fun `search ignores a request baseUrl for every provider except Searxng`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"results":[]}""") }.use { server ->
            // 环境说 provider 是 SEARXNG、base 指向假服务端；请求体却指定 MESSAGEAPI + 另一个 base。
            // provider 以请求体为准（MESSAGEAPI），base 只认服务端配置 —— 请求必须打到假服务端。
            val env = mapOf(
                ToolsRoutes.ENV_SEARCH_PROVIDER to "SEARXNG",
                ToolsRoutes.ENV_SEARCH_BASE to server.baseUrl,
                ToolsRoutes.ENV_SEARCH_KEY to "sk-server-secret"
            )
            val response = post(
                ToolsRoutes.SEARCH_PATH,
                """{"query":"x","provider":"MESSAGEAPI","baseUrl":"http://127.0.0.1:9/evil"}""",
                services(env = env)
            )

            assertEquals("MESSAGEAPI", json(response).str("provider"))
            assertEquals("/v1/tools/search", server.onlyRequest().path)
        }
    }

    @Test
    fun `search never sends a client supplied key and uses the server one`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"results":[]}""") }.use { server ->
            val response = post(
                ToolsRoutes.SEARCH_PATH,
                """{"query":"x","apiKey":"sk-client-forged"}""",
                services(env = searchEnv(server.baseUrl, key = "sk-server-secret"))
            )

            assertEquals(200, response.status)
            val request = server.onlyRequest()
            assertEquals("Bearer sk-server-secret", request.header("Authorization"))
            assertFalse(request.body, request.body.contains("sk-client-forged"))
            assertFalse(response.body, response.body.contains("sk-client-forged"))
            assertFalse(response.body, response.body.contains("sk-server-secret"))
        }
    }

    // ------------------------------------------------------------------ web-search：错误映射

    @Test
    fun `search maps upstream statuses to 401 429 and 502`() {
        val cases = listOf(
            401 to 401,
            429 to 429,
            403 to 502,
            400 to 502,
            500 to 502
        )
        cases.forEach { (upstream, expected) ->
            FakeHttpServer { FakeHttpServer.FakeResponse(upstream, "upstream says no") }.use { server ->
                val response = post(
                    ToolsRoutes.SEARCH_PATH,
                    """{"query":"x"}""",
                    services(env = searchEnv(server.baseUrl))
                )
                assertEquals("上游 $upstream 应映射成 $expected", expected, response.status)
                val expectedCode = when (expected) {
                    401 -> "search_unauthorized"
                    429 -> "search_rate_limited"
                    else -> "search_upstream_error"
                }
                assertEquals(expectedCode, errorCode(response))
                assertFalse(response.body, response.body.contains("sk-server-secret"))
            }
        }
    }

    @Test
    fun `search maps missing key missing base invalid base and empty query to 400`() {
        val noKey = post(
            ToolsRoutes.SEARCH_PATH,
            """{"query":"x"}""",
            services(env = mapOf(ToolsRoutes.ENV_SEARCH_BASE to "https://api.example.com"))
        )
        assertEquals(400, noKey.status)
        assertEquals("search_bad_request", errorCode(noKey))
        assertTrue(errorMessage(noKey), errorMessage(noKey).contains("key"))

        val noBase = post(
            ToolsRoutes.SEARCH_PATH,
            """{"query":"x"}""",
            services(env = mapOf(ToolsRoutes.ENV_SEARCH_KEY to "k"))
        )
        assertEquals(400, noBase.status)
        assertEquals("search_bad_request", errorCode(noBase))

        val invalidBase = post(
            ToolsRoutes.SEARCH_PATH,
            """{"query":"x","provider":"SEARXNG","baseUrl":"ftp://searx.local"}""",
            services()
        )
        assertEquals(400, invalidBase.status)
        assertEquals("search_bad_request", errorCode(invalidBase))

        val emptyQuery = post(
            ToolsRoutes.SEARCH_PATH,
            """{"query":"   "}""",
            services(env = searchEnv("https://api.example.com"))
        )
        assertEquals(400, emptyQuery.status)
        assertEquals("invalid_argument", errorCode(emptyQuery))
    }

    @Test
    fun `search error logs and responses never contain the key`() {
        val logs = ArrayList<String>()
        val http = SearchHttpClient(log = { line -> logs += line })
        FakeHttpServer {
            // 上游把收到的 key 原样回显在错误体里：日志预览必须把它脱敏。
            FakeHttpServer.FakeResponse(401, "invalid api key sk-server-secret", contentType = "text/plain")
        }.use { server ->
            val response = post(
                ToolsRoutes.SEARCH_PATH,
                """{"query":"x"}""",
                services(search = WebSearchService(http = http), env = searchEnv(server.baseUrl))
            )

            assertEquals(401, response.status)
            assertFalse("响应里不能出现 key", response.body.contains("sk-server-secret"))
            val logText = logs.joinToString("\n")
            assertFalse("日志里不能出现 key：$logText", logText.contains("sk-server-secret"))
            assertTrue("日志预览应把 key 脱敏成 ***：$logText", logText.contains("***"))
        }
    }

    @Test
    fun `search rejects a body that is not a JSON object`() {
        val response = post(ToolsRoutes.SEARCH_PATH, "[]", services())
        assertEquals(400, response.status)
        assertEquals("invalid_body", errorCode(response))

        val invalidJson = post(ToolsRoutes.SEARCH_PATH, "{oops", services())
        assertEquals(400, invalidJson.status)
        assertEquals("invalid_json", errorCode(invalidJson))
    }

    // ------------------------------------------------------------------ web-fetch

    @Test
    fun `fetch returns the title and text without markup`() {
        val html = """
            <html><head><title>示例页</title><style>p{color:red}</style></head>
            <body><script>tracking()</script><p>正文 &amp; 段落</p></body></html>
        """.trimIndent()
        FakeHttpServer {
            FakeHttpServer.FakeResponse(200, html, contentType = "text/html; charset=utf-8")
        }.use { server ->
            // 真实策略拒绝 127.0.0.1；这里显式放行，好让抓取路径本身被测到（策略单独在下一个用例里测）。
            val response = post(
                ToolsRoutes.FETCH_PATH,
                """{"url":"${server.baseUrl}/page"}""",
                services(search = WebSearchService(urlPolicy = { null }))
            )

            val json = json(response)
            assertEquals("${server.baseUrl}/page", json.str("url"))
            assertEquals("示例页", json.str("title"))
            val text = json.str("text")
            assertTrue(text, text.contains("正文 & 段落"))
            assertFalse(text, text.contains("<"))
            assertFalse(text, text.contains("tracking()"))
            assertFalse(text, text.contains("color:red"))
        }
    }

    @Test
    fun `fetch rejects local addresses with URL_REJECTED before any request`() {
        val response = post(
            ToolsRoutes.FETCH_PATH,
            """{"url":"http://127.0.0.1:8080/private"}""",
            services()
        )
        assertEquals(400, response.status)
        assertEquals("search_bad_request", errorCode(response))
        assertTrue(errorMessage(response), errorMessage(response).contains("不抓取本机/内网地址"))
    }

    @Test
    fun `fetch rejects an empty url`() {
        val response = post(ToolsRoutes.FETCH_PATH, """{"url":"  "}""", services())
        assertEquals(400, response.status)
        assertEquals("invalid_argument", errorCode(response))
    }

    // ------------------------------------------------------------------ proactive/tick

    /** 存储层时钟可改写的伪时间：先造「很久以前」的互动，再把 now 拨到当前。 */
    private var storeNow = 1_600_000_000_000L

    @Test
    fun `tick fires one proactive assistant message and persists it`() {
        val store = FileStore.open(root, { storeNow })
        val sessionId = FileStore.SEED_SESSION_ID
        store.appendMessage(sessionId, MessageRole.USER, "在吗")
        val now = storeNow + 6 * 60 * 60 * 1000L
        storeNow = now

        val response = post(
            ToolsRoutes.TICK_PATH,
            """{"now":$now}""",
            services(store = store)
        )

        val json = json(response)
        assertEquals(now, json.long("now"))
        assertEquals(1, json.int("fired"))
        val decision = json.objects("decisions").single()
        assertEquals(FileStore.SEED_PERSONA_1_ID, decision.str("personaId"))
        assertEquals(sessionId, decision.str("sessionId"))
        assertEquals("fired", decision.str("reason"))

        val messageId = decision.str("messageId")
        val message = store.findMessage(sessionId, messageId)
            ?: error("主动消息应该已经落库：$messageId")
        assertEquals(MessageRole.ASSISTANT, message.role)
        assertEquals(MessageSource.PROACTIVE, message.source)
        assertEquals("忽然想起你，想问问你现在在做什么。", message.content)

        // 落库同时发一条 message.created 事件（客户端靠 /v1/events 增量同步拿到它）。
        val event = store.eventsSince(0).last()
        assertEquals(EventType.MESSAGE_CREATED, event.type)
        assertEquals(messageId, event.payload.obj("message").str("id"))
    }

    @Test
    fun `tick with the same now does not fire twice`() {
        val store = FileStore.open(root, { storeNow })
        val sessionId = FileStore.SEED_SESSION_ID
        store.appendMessage(sessionId, MessageRole.USER, "在吗")
        val now = storeNow + 6 * 60 * 60 * 1000L
        storeNow = now
        val services = services(store = store)

        assertEquals(1, json(post(ToolsRoutes.TICK_PATH, """{"now":$now}""", services)).int("fired"))
        val second = json(post(ToolsRoutes.TICK_PATH, """{"now":$now}""", services))
        assertEquals(0, second.int("fired"))
        assertTrue(second.objects("decisions").isEmpty())

        val proactive = store.latestMessages(sessionId, 20).filter { it.source == MessageSource.PROACTIVE }
        assertEquals("同一个 now 只能落一条主动消息", 1, proactive.size)
    }

    @Test
    fun `tick uses the server clock when now is omitted`() {
        val store = FileStore.open(root, { storeNow })
        store.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.USER, "在吗")
        // 数据目录的时钟已经是「六小时后」：不传 now 时应该用 store.serverTime() 判定。
        storeNow += 6 * 60 * 60 * 1000L

        val response = post(ToolsRoutes.TICK_PATH, "", services(store = store))

        val json = json(response)
        assertEquals(storeNow, json.long("now"))
        assertEquals(1, json.int("fired"))
    }

    @Test
    fun `tick returns 503 when the server has no data directory`() {
        val response = post(ToolsRoutes.TICK_PATH, "{}", services(store = null))
        assertEquals(503, response.status)
        assertEquals("store_disabled", errorCode(response))
    }

    @Test
    fun `tick rejects invalid json and non positive now`() {
        val store = FileStore.open(root, { storeNow })
        val invalidJson = post(ToolsRoutes.TICK_PATH, "{oops", services(store = store))
        assertEquals(400, invalidJson.status)
        assertEquals("invalid_json", errorCode(invalidJson))

        val nonPositive = post(ToolsRoutes.TICK_PATH, """{"now":0}""", services(store = store))
        assertEquals(400, nonPositive.status)
        assertEquals("invalid_argument", errorCode(nonPositive))
    }

    // ------------------------------------------------------------------ 真实 socket：路由分发

    @Test
    fun `real server dispatches the tools and tick routes behind the same token`() {
        FakeHttpServer { FakeHttpServer.FakeResponse(200, """{"results":[]}""") }.use { provider ->
            val server = AgentdServer.start(
                "127.0.0.1",
                0,
                "secret-token",
                RateLimiter(),
                services(store = FileStore.open(root, { storeNow }), env = searchEnv(provider.baseUrl))
            )
            try {
                // 新路由和 /v1/core/* 一样受 --token 约束。
                val unauthorized = httpPost(server.port, ToolsRoutes.SEARCH_PATH, """{"query":"x"}""")
                assertEquals(401, unauthorized.statusCode())

                val search = httpPost(
                    server.port,
                    ToolsRoutes.SEARCH_PATH,
                    """{"query":"x"}""",
                    token = "secret-token"
                )
                assertEquals(200, search.statusCode())
                assertEquals("MESSAGEAPI", TestSupport.parse(search.body()).str("provider"))
                // 请求真的打到了服务端配置的上游（证明 Server 把路径交给了 ToolsRoutes）。
                assertEquals("/v1/tools/search", provider.onlyRequest().path)

                val tick = httpPost(server.port, ToolsRoutes.TICK_PATH, "{}", token = "secret-token")
                assertEquals(200, tick.statusCode())
                assertEquals("刚播种、还没有互动起点时不该掷中", 0, TestSupport.parse(tick.body()).int("fired"))
            } finally {
                server.stop()
            }
        }
    }
}
