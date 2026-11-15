package com.myfriend.agentd

import com.myfriend.app.core.json.Json
import com.myfriend.app.core.json.JsonParseException
import com.myfriend.app.core.json.JsonValue
import com.myfriend.app.domain.AgentCoreProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * handler 层测试：不起端口，直接喂「方法 + 路径 + 请求体」，断言状态码与 JSON。
 *
 * 为什么先测这一层：HTTP 的坑（字段名、默认值、错误码）都在这里，
 * 起端口只多验证「字节读写」这一件事，放在 [AgentdServerTest] 里。
 */
class AgentdApiTest {

    private fun post(path: String, body: String): AgentdApi.Response =
        AgentdApi.handle("POST", path, body)

    private fun json(response: AgentdApi.Response): JsonValue =
        Json.parse(response.body)

    private fun JsonValue.obj(): JsonValue.Obj = this as JsonValue.Obj

    private fun JsonValue.Obj.str(key: String): String = (fields[key] as JsonValue.Str).value

    private fun JsonValue.Obj.bool(key: String): Boolean = (fields[key] as JsonValue.Bool).value

    private fun JsonValue.Obj.int(key: String): Int = (fields[key] as JsonValue.Num).asInt!!

    private fun JsonValue.Obj.long(key: String): Long = (fields[key] as JsonValue.Num).asLong!!

    // ------------------------------------------------------------------ /health

    @Test
    fun `health returns service and version`() {
        val response = AgentdApi.handle("GET", "/health", null)
        assertEquals(200, response.status)
        assertEquals(
            """{"ok":true,"service":"myfriend-agentd","version":"0.12.0"}""",
            response.body
        )
    }

    // ------------------------------------------------------------------ 睡眠

    @Test
    fun `sleep decision awake replies`() {
        val response = post(
            "/v1/core/sleep-decision",
            """{"sleepEnabled":true,"sleepMinutes":null,"wakeMinutes":null,"nowMinutes":720,
               "dndOnSleep":true,"userMessagesInWindow":0,"wakeThreshold":6}"""
        )
        assertEquals(200, response.status)
        val body = json(response).obj()
        assertTrue(body.bool("wake"))
        assertEquals(false, body.bool("moodRelaxed"))
        assertEquals("醒着，会正常回复", body.str("reason"))
        // 0.11.0 新增的兼容字段：醒着时一定留已读。
        assertTrue(body.bool("marksRead"))
    }

    @Test
    fun `sleep decision dnd never wakes`() {
        val response = post(
            "/v1/core/sleep-decision",
            """{"sleepEnabled":true,"sleepMinutes":1410,"wakeMinutes":480,"nowMinutes":120,
               "dndOnSleep":true,"userMessagesInWindow":99,"wakeThreshold":1}"""
        )
        val body = json(response).obj()
        assertEquals(false, body.bool("wake"))
        assertEquals(false, body.bool("moodRelaxed"))
        assertEquals("免打扰中，不会醒", body.str("reason"))
        // 免打扰是唯一例外：连已读都不留（规格 §6.2）。
        assertEquals(false, body.bool("marksRead"))
    }

    @Test
    fun `sleep decision below threshold stays silent with wake clock`() {
        val response = post(
            "/v1/core/sleep-decision",
            """{"sleepEnabled":true,"sleepMinutes":null,"wakeMinutes":null,"nowMinutes":120,
               "dndOnSleep":false,"userMessagesInWindow":5,"wakeThreshold":6}"""
        )
        val body = json(response).obj()
        assertEquals(false, body.bool("wake"))
        assertEquals("睡着了 · 08:00 醒", body.str("reason"))
        // 睡着了但没开免打扰：已读要留（界面只显示「已读」，不显示任何解释）。
        assertTrue(body.bool("marksRead"))
    }

    @Test
    fun `sleep decision at threshold wakes and relaxes mood`() {
        val response = post(
            "/v1/core/sleep-decision",
            """{"sleepEnabled":true,"sleepMinutes":null,"wakeMinutes":null,"nowMinutes":120,
               "dndOnSleep":false,"userMessagesInWindow":6,"wakeThreshold":6}"""
        )
        val body = json(response).obj()
        assertTrue(body.bool("wake"))
        assertTrue(body.bool("moodRelaxed"))
        assertEquals("被你叫醒了，缓了缓", body.str("reason"))
    }

    // ------------------------------------------------------------------ defer_reply

    @Test
    fun `defer plan defaults to 45 minutes`() {
        val response = post("/v1/core/defer-plan", """{"now":0}""")
        assertEquals(200, response.status)
        val body = json(response).obj()
        assertEquals(45, body.int("minutes"))
        assertEquals("", body.str("reason"))
        assertEquals(2_700_000L, body.long("earliestAt"))
        assertEquals(4_500_000L, body.long("latestAt"))
    }

    @Test
    fun `defer plan clamps and trims`() {
        val response = post(
            "/v1/core/defer-plan",
            """{"minutes":9999,"reason":"  现在不想说话  ","now":1000}"""
        )
        val body = json(response).obj()
        assertEquals(720, body.int("minutes"))
        assertEquals("现在不想说话", body.str("reason"))
        assertEquals(1000L + 720L * 60_000L, body.long("earliestAt"))
        assertEquals(1000L + 750L * 60_000L, body.long("latestAt"))
    }

    // ------------------------------------------------------------------ 主动程度

    @Test
    fun `proactive cap clamps level`() {
        assertEquals(1, json(post("/v1/core/proactive-cap", """{"level":0}""")).obj().int("dailyCap"))
        assertEquals(5, json(post("/v1/core/proactive-cap", """{"level":99}""")).obj().int("dailyCap"))
        assertEquals(3, json(post("/v1/core/proactive-cap", """{"level":3}""")).obj().int("dailyCap"))
    }

    @Test
    fun `proactive cap applies mood only when label is present`() {
        val happy = json(
            post("/v1/core/proactive-cap", """{"level":3,"moodLabel":"雀跃","moodIntensity":4}""")
        ).obj()
        assertEquals(4, happy.int("dailyCap"))

        // 只给强度、不给标签：不能凭空猜极性，返回基础上限。
        val intensityOnly = json(
            post("/v1/core/proactive-cap", """{"level":3,"moodIntensity":5}""")
        ).obj()
        assertEquals(3, intensityOnly.int("dailyCap"))
    }

    // ------------------------------------------------------------------ 工具参数

    @Test
    fun `tool args clamps importance and keeps other fields`() {
        val response = post(
            "/v1/core/tool-args",
            """{"tool":"remember","args":{"content":"她妹妹叫小雨","drawer":"USER","importance":9}}"""
        )
        assertEquals(200, response.status)
        val args = (json(response).obj().fields["args"] as JsonValue.Obj)
        assertEquals(5, (args.fields["importance"] as JsonValue.Num).asInt)
        assertEquals("她妹妹叫小雨", (args.fields["content"] as JsonValue.Str).value)
        assertEquals("USER", (args.fields["drawer"] as JsonValue.Str).value)
    }

    @Test
    fun `tool args fills defaults`() {
        val remember = json(
            post("/v1/core/tool-args", """{"tool":"remember","args":{"content":"她说怕冷"}}""")
        ).obj()
        val rememberArgs = remember.fields["args"] as JsonValue.Obj
        assertEquals(3, (rememberArgs.fields["importance"] as JsonValue.Num).asInt)

        val search = json(
            post("/v1/core/tool-args", """{"tool":"web_search","args":{"query":"天气"}}""")
        ).obj()
        val searchArgs = search.fields["args"] as JsonValue.Obj
        assertEquals(6, (searchArgs.fields["count"] as JsonValue.Num).asInt)

        val defer = json(
            post("/v1/core/tool-args", """{"tool":"defer_reply","args":{}}""")
        ).obj()
        val deferArgs = defer.fields["args"] as JsonValue.Obj
        assertEquals(45, (deferArgs.fields["minutes"] as JsonValue.Num).asInt)
    }

    // ------------------------------------------------------------------ 错误

    @Test
    fun `unknown route is 404 with Chinese message`() {
        val response = AgentdApi.handle("GET", "/v1/nope", null)
        assertEquals(404, response.status)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("not_found", (error.fields["code"] as JsonValue.Str).value)
        assertTrue((error.fields["message"] as JsonValue.Str).value.contains("没有这个接口"))
    }

    @Test
    fun `wrong method is 405`() {
        assertEquals(405, AgentdApi.handle("GET", "/v1/core/defer-plan", null).status)
        assertEquals(405, AgentdApi.handle("POST", "/health", "{}").status)
    }

    @Test
    fun `malformed json is 400`() {
        val response = post("/v1/core/defer-plan", """{"now":}""")
        assertEquals(400, response.status)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("invalid_json", (error.fields["code"] as JsonValue.Str).value)
    }

    @Test
    fun `missing field names the field`() {
        val response = post("/v1/core/defer-plan", """{"minutes":30}""")
        assertEquals(400, response.status)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("missing_field", (error.fields["code"] as JsonValue.Str).value)
        assertTrue((error.fields["message"] as JsonValue.Str).value.contains("now"))
    }

    @Test
    fun `wrong field type is 400`() {
        val response = post(
            "/v1/core/sleep-decision",
            """{"sleepEnabled":"yes","sleepMinutes":null,"wakeMinutes":null,"nowMinutes":0,
               "dndOnSleep":false,"userMessagesInWindow":0,"wakeThreshold":6}"""
        )
        assertEquals(400, response.status)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("invalid_argument", (error.fields["code"] as JsonValue.Str).value)
    }

    @Test
    fun `unknown tool is 400`() {
        val response = post("/v1/core/tool-args", """{"tool":"rm_rf","args":{}}""")
        assertEquals(400, response.status)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("unknown_tool", (error.fields["code"] as JsonValue.Str).value)
    }

    // ------------------------------------------------------------------ 协议常量

    /**
     * 路由表必须来自 `:agentcore` 的 [AgentCoreProtocol] 常量。
     *
     * 这条测试的意义：客户端（Android 的 `RemoteAgentCoreClient`）拼 URL 用的是同一批常量。
     * 谁要是只改了服务端字符串、没改 agentcore，这里会立刻红，而不是等到联调时 404。
     */
    @Test
    fun `core routes match agentcore protocol constants`() {
        assertEquals(
            listOf(
                "/v1/core/sleep-decision",
                "/v1/core/defer-plan",
                "/v1/core/proactive-cap",
                "/v1/core/tool-args"
            ),
            AgentCoreProtocol.CORE_PATHS
        )
        for (path in AgentCoreProtocol.CORE_PATHS) {
            // 存在性：不是 404（空体应该是 400 empty_body，说明路由真的接上了）。
            assertEquals(path, 400, AgentdApi.handle("POST", path, "").status)
        }
    }

    @Test
    fun `empty body is 400`() {
        val response = post("/v1/core/proactive-cap", "")
        assertEquals(400, response.status)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("empty_body", (error.fields["code"] as JsonValue.Str).value)
    }

    // ------------------------------------------------------------------ 0.11.1 加固

    /** 审计 P2-1：8 MiB body 不再「200 + 原样回显」，而是 413，且错误体很小。 */
    @Test
    fun `oversized body is 413 and never echoed`() {
        val body = """{"tool":"remember","args":{"content":"""" + "x".repeat(8 * 1024 * 1024) + """}}"""
        val response = post("/v1/core/tool-args", body)
        assertEquals(413, response.status)
        assertTrue("错误体被放大到 ${response.body.length} 字节", response.body.length < 600)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("content_too_large", (error.fields["code"] as JsonValue.Str).value)
    }

    /** 审计 P2-1：2 MB 未知工具名以前会回 2,000,115 字节；现在既不回显、也受 1 MiB 上限约束。 */
    @Test
    fun `unknown tool is not echoed back`() {
        val hugeName = "rm_rf-".repeat(40_000) // 240 KB：低于请求体上限，专门验证「不回显」
        val response = post("/v1/core/tool-args", """{"tool":"$hugeName","args":{}}""")
        assertEquals(400, response.status)
        assertTrue("错误体被放大到 ${response.body.length} 字节", response.body.length < 600)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("unknown_tool", (error.fields["code"] as JsonValue.Str).value)
        assertFalse(response.body.contains(hugeName.take(100)))

        // 超过 1 MiB 的工具名连解析都到不了：直接 413。
        val oversized = post("/v1/core/tool-args", """{"tool":"${"x".repeat(2 * 1024 * 1024)}","args":{}}""")
        assertEquals(413, oversized.status)
    }

    /** 审计 P2-1：1000 字符的路径以前会回 1,065 字节（原样回显）；现在 404 只回固定文案。 */
    @Test
    fun `unknown route does not echo a long path`() {
        val path = "/v1/" + "a".repeat(3_000)
        val response = AgentdApi.handle("GET", path, null)
        assertEquals(404, response.status)
        assertTrue("错误体被放大到 ${response.body.length} 字节", response.body.length < 600)
        assertFalse(response.body.contains("aaaa"))
    }

    /** 审计 P2-2：500 只回固定文案 + 错误编号，异常消息/类名/路径都不出现在响应里。 */
    @Test
    fun `internal error response is generic`() {
        val response = AgentdApi.unexpectedFailureResponse(
            IllegalStateException("boom at /data/user/0/com.myfriend.app/secret.db")
        )
        assertEquals(500, response.status)
        assertTrue(response.body.contains("internal_error"))
        assertTrue(response.body.contains("错误编号"))
        assertFalse(response.body.contains("boom"))
        assertFalse(response.body.contains("secret.db"))
        assertFalse(response.body.contains("IllegalStateException"))
        assertTrue(Regex("错误编号 [0-9a-f]{8}").containsMatchIn(response.body))
    }

    /** 请求体上限不能误伤正常大请求：256 KiB 的 remember 内容仍然 200。 */
    @Test
    fun `large but legal body still works`() {
        val content = "记".repeat(256 * 1024) // UTF-8 下 768 KiB，仍低于 1 MiB
        val response = post(
            "/v1/core/tool-args",
            """{"tool":"remember","args":{"content":"$content"}}"""
        )
        assertEquals(200, response.status)
        assertTrue(response.body.contains("\"importance\":3"))
    }

    // ------------------------------------------------------------------ JSON 解析边界

    /** 审计：100 层嵌套 → 400（解析深度上限 64），不是 500 / 栈溢出。 */
    @Test
    fun `deeply nested json is rejected as invalid json`() {
        val body = "[".repeat(100) + "]".repeat(100)
        val response = post("/v1/core/tool-args", body)
        assertEquals(400, response.status)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("invalid_json", (error.fields["code"] as JsonValue.Str).value)
        assertTrue((error.fields["message"] as JsonValue.Str).value.contains("嵌套过深"))
    }

    /** 1e308：语法合法但不能当整数用 → 400 invalid_argument，而不是溢出/静默取默认值。 */
    @Test
    fun `huge exponent number is rejected as non integer`() {
        val response = post("/v1/core/defer-plan", """{"minutes":1e308,"now":0}""")
        assertEquals(400, response.status)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("invalid_argument", (error.fields["code"] as JsonValue.Str).value)
    }

    /** `NaN` 不是 JSON 字面量 → 400 invalid_json（不允许宽松解析把非有限值放进决策层）。 */
    @Test
    fun `nan literal is rejected`() {
        val response = post("/v1/core/defer-plan", """{"minutes":NaN,"now":0}""")
        assertEquals(400, response.status)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("invalid_json", (error.fields["code"] as JsonValue.Str).value)
    }

    /** 字段长度上限：300 KiB 的单个字符串（低于 HTTP 请求体上限）在解析层被拒。 */
    @Test
    fun `oversized json string field is rejected`() {
        val body = """{"tool":"remember","args":{"content":"${"x".repeat(300 * 1024)}"}}"""
        val response = post("/v1/core/tool-args", body)
        assertEquals(400, response.status)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("invalid_json", (error.fields["code"] as JsonValue.Str).value)
        assertTrue((error.fields["message"] as JsonValue.Str).value.contains("字符串过长"))
    }

    /** 重复键直接拒，避免「前者后者谁生效」的解析差异。 */
    @Test
    fun `duplicate json keys are rejected`() {
        val response = post("/v1/core/defer-plan", """{"now":0,"now":1}""")
        assertEquals(400, response.status)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("invalid_json", (error.fields["code"] as JsonValue.Str).value)
        assertTrue((error.fields["message"] as JsonValue.Str).value.contains("重复键"))
    }
}
