package com.myfriend.agentd

import org.junit.Assert.assertEquals
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
            """{"ok":true,"service":"myfriend-agentd","version":"0.10.0"}""",
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

    @Test
    fun `empty body is 400`() {
        val response = post("/v1/core/proactive-cap", "")
        assertEquals(400, response.status)
        val error = (json(response).obj().fields["error"] as JsonValue.Obj)
        assertEquals("empty_body", (error.fields["code"] as JsonValue.Str).value)
    }
}
