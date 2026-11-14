package com.myfriend.app.core

import com.myfriend.app.core.json.Json
import com.myfriend.app.core.json.JsonValue
import com.myfriend.app.domain.AgentCoreCodec
import com.myfriend.app.domain.AgentCoreProtocol
import com.myfriend.app.domain.InProcessAgentCore
import com.myfriend.app.domain.ProactiveCapInput
import com.myfriend.app.domain.SleepInput
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * `RemoteAgentCoreClient` / `AgentCoreClients` 的测试。
 *
 * 假服务是 `ServerSocket` + 手写 HTTP/1.1（随机端口），**不引 MockWebServer**：
 * 项目已经有 OkHttp，但 `com.squareup.okhttp3:mockwebserver` 是新依赖；`com.sun.net.httpserver`
 * 在 Android 单测的编译类路径（`android.jar`）里又不存在。这个测试只需要「收请求 → 回 JSON」，
 * 几十行 `ServerSocket` 就够，也不给 App 增加任何依赖。
 *
 * 覆盖 0.11.0 的验收点：
 * - 四个端点的请求体/响应体与 `agentd` 契约一致，解码结果与进程内逐字段相等；
 * - 远程失败（5xx / 坏 JSON / 连不上 / 超时）**一律回退进程内**，结果与进程内一致；
 * - 开关关闭时**一个请求都不发**（服务器的命中计数为 0）；
 * - 开关打开时每次决策恰好一个请求，且路径来自 `AgentCoreProtocol`。
 *
 * 所有测试都注入假 logger：真实实现会写 `android.util.Log`，JVM 单测里那个方法是 stub。
 */
class AgentCoreClientTest {

    // ------------------------------------------------------------------ 假服务

    private lateinit var server: FakeHttpServer
    private val hits = AtomicInteger(0)
    private val paths = Collections.synchronizedList(mutableListOf<String>())
    private val lastBody = AtomicReference<String?>(null)

    /** 可替换的响应逻辑：路径 + 请求体 → 状态码 + 响应体。默认按真实契约计算。 */
    @Volatile
    private var responder: (path: String, body: String) -> Pair<Int, String> = ::realContract

    private val logs = Collections.synchronizedList(mutableListOf<String>())

    private val logger = AgentCoreLogger { message, _ -> logs += message }

    @Before
    fun resetRemoteStats() {
        AgentCoreRemoteStats.reset()
    }

    @Before
    fun startServer() {
        server = FakeHttpServer { path, body ->
            // 先计数再算响应：超时用例里响应会慢慢算，但请求确实已经到了。
            hits.incrementAndGet()
            paths += path
            lastBody.set(body)
            responder(path, body)
        }
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.port}"

    private fun client(
        remoteEnabled: Boolean = true,
        timeoutMillis: Long = 2_000L
    ): AgentCoreClient = AgentCoreClients.create(
        remoteEnabled = remoteEnabled,
        baseUrl = baseUrl(),
        httpClient = OkHttpClient(),
        fallback = InProcessAgentCore,
        logger = logger
    )

    /**
     * 真实契约的「假 agentd」：与 `agentd` 一样调 `InProcessAgentCore` 并按同一套编解码回包。
     * 这样能验证客户端发出的请求体真的能被服务端解析、且客户端解出来的结果与进程内一致。
     */
    private fun realContract(path: String, body: String): Pair<Int, String> = when (path) {
        AgentCoreProtocol.SLEEP_DECISION_PATH -> 200 to AgentCoreCodec.encodeSleepDecision(
            InProcessAgentCore.sleepDecision(AgentCoreCodec.decodeSleepInput(body))
        )

        AgentCoreProtocol.DEFER_PLAN_PATH -> {
            val request = decodeDeferPlanRequestForTest(body)
            val plan = InProcessAgentCore.deferPlan(request.minutes, request.reason, request.now)
            200 to AgentCoreCodec.encodeDeferPlan(plan)
        }

        AgentCoreProtocol.PROACTIVE_CAP_PATH -> 200 to AgentCoreCodec.encodeProactiveCap(
            InProcessAgentCore.proactiveCap(decodeProactiveCapRequestForTest(body))
        )

        AgentCoreProtocol.TOOL_ARGS_PATH -> {
            val request = decodeToolArgsRequestForTest(body)
            val args = InProcessAgentCore.clampToolArgs(request.first, request.second)
            200 to encodeToolArgsResponseForTest(args)
        }

        else -> 404 to """{"error":{"message":"没有这个接口：$path","code":"not_found"}}"""
    }

    // ------------------------------------------------------------------ 1. 契约解码

    @Test
    fun `remote decodes the documented response shapes`() {
        // 硬编码响应体（不经过 agentcore 的编码器）：证明客户端认识 0.10.x 就公开的字段名。
        responder = { path, _ ->
            when (path) {
                AgentCoreProtocol.SLEEP_DECISION_PATH -> 200 to
                    """{"wake":false,"moodRelaxed":false,"reason":"睡着了 · 08:00 醒","marksRead":true}"""

                AgentCoreProtocol.DEFER_PLAN_PATH -> 200 to
                    """{"earliestAt":1700002700000,"latestAt":1700004500000,"minutes":45,"reason":""}"""

                AgentCoreProtocol.PROACTIVE_CAP_PATH -> 200 to """{"dailyCap":4}"""
                AgentCoreProtocol.TOOL_ARGS_PATH -> 200 to
                    """{"args":{"content":"她妹妹叫小雨","drawer":"USER","importance":5}}"""

                else -> 404 to """{"error":{"code":"not_found","message":"没有这个接口"}}"""
            }
        }
        val core = client()

        val sleep = core.sleepDecision(
            SleepInput(
                sleepEnabled = true,
                dndOnSleep = false,
                nowMinutes = 120,
                userMessagesInWindow = 5,
                wakeThreshold = 6
            )
        )
        assertEquals(false, sleep.wake)
        assertEquals(false, sleep.moodRelaxed)
        assertEquals(true, sleep.marksRead)
        assertEquals("睡着了 · 08:00 醒", sleep.reason)
        assertTrue(sleep.asleep)

        val plan = core.deferPlan(null, null, 0L)
        assertEquals(45, plan.minutes)
        assertEquals(1_700_002_700_000L, plan.earliestAt)
        assertEquals(1_700_004_500_000L, plan.latestAt)

        assertEquals(4, core.proactiveCap(ProactiveCapInput(3, "雀跃", 4)))

        val args = core.clampToolArgs("remember", mapOf("content" to "她妹妹叫小雨", "importance" to 9))
        assertEquals(5, args["importance"])
        assertEquals("她妹妹叫小雨", args["content"])
        assertEquals("USER", args["drawer"])
    }

    @Test
    fun `remote results equal in-process results for the same inputs`() {
        val core = client()

        val sleepInputs = listOf(
            SleepInput(true, false, 1410, 480, 120, 0, 6),
            SleepInput(true, true, 1410, 480, 120, 99, 1),
            SleepInput(true, false, null, null, 120, 6, 6),
            SleepInput(false, true, null, null, 600, 0, 6)
        )
        for (input in sleepInputs) {
            assertEquals("sleep $input", InProcessAgentCore.sleepDecision(input), core.sleepDecision(input))
        }

        val deferInputs = listOf(
            Triple<Int?, String?, Long>(null, null, 0L),
            Triple(5, "缓一缓", 1000L),
            Triple(9999, "  现在不想说话  ", 1700000000000L),
            Triple(0, null, 42L)
        )
        for ((minutes, reason, now) in deferInputs) {
            assertEquals(
                "defer $minutes/$reason/$now",
                InProcessAgentCore.deferPlan(minutes, reason, now),
                core.deferPlan(minutes, reason, now)
            )
        }

        val capInputs = listOf(
            ProactiveCapInput(1),
            ProactiveCapInput(3, "雀跃", 4),
            ProactiveCapInput(5, "难过", 5),
            ProactiveCapInput(99, "有点累", 3)
        )
        for (input in capInputs) {
            assertEquals("cap $input", InProcessAgentCore.proactiveCap(input), core.proactiveCap(input))
        }

        val clampInputs = listOf(
            "remember" to mapOf<String, Any?>("content" to "她说怕冷", "drawer" to "USER"),
            "remember" to mapOf<String, Any?>("importance" to 0),
            "web_search" to mapOf<String, Any?>("query" to "天气", "count" to 50),
            "defer_reply" to mapOf<String, Any?>("minutes" to 3000, "reason" to "先不回")
        )
        for ((tool, args) in clampInputs) {
            assertEquals("clamp $tool $args", InProcessAgentCore.clampToolArgs(tool, args), core.clampToolArgs(tool, args))
        }

        // 四次输入组 × 四个端点；确认真的走了远程（否则这条测试等于白测）。
        assertEquals(16, hits.get())
        assertEquals(
            AgentCoreProtocol.CORE_PATHS.toSet(),
            paths.toSet()
        )
    }

    // ------------------------------------------------------------------ 2. 失败回退

    @Test
    fun `server error falls back to in-process and logs`() {
        responder = { _, _ -> 500 to """{"error":{"message":"服务内部错误：boom","code":"internal_error"}}""" }
        val core = client()
        val input = SleepInput(true, false, 1410, 480, 120, 0, 6)

        assertEquals(InProcessAgentCore.sleepDecision(input), core.sleepDecision(input))
        assertEquals(1, hits.get())
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("回退进程内"))
        assertTrue(logs.single().contains("服务内部错误"))
    }

    @Test
    fun `malformed json falls back to in-process`() {
        responder = { _, _ -> 200 to "not json at all" }
        val core = client()
        val plan = core.deferPlan(90, "现在不想说话", 1700000000000L)
        assertEquals(InProcessAgentCore.deferPlan(90, "现在不想说话", 1700000000000L), plan)
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `missing field in response falls back to in-process`() {
        responder = { path, _ ->
            if (path == AgentCoreProtocol.PROACTIVE_CAP_PATH) {
                200 to """{"unexpected":true}"""
            } else {
                404 to ""
            }
        }
        val core = client()
        val input = ProactiveCapInput(3, "雀跃", 4)
        assertEquals(InProcessAgentCore.proactiveCap(input), core.proactiveCap(input))
        assertTrue(logs.single().contains("dailyCap"))
    }

    @Test
    fun `unreachable server falls back to in-process`() {
        // 127.0.0.1:1 基本不会有服务在听：连接被拒绝应立即回退，而不是等超时。
        val core = RemoteAgentCoreClient(
            baseUrl = "http://127.0.0.1:1",
            fallback = InProcessAgentCore,
            logger = logger,
            timeoutMillis = 500L
        )
        val args = core.clampToolArgs("web_search", mapOf("count" to 99))
        assertEquals(InProcessAgentCore.clampToolArgs("web_search", mapOf("count" to 99))["count"], args["count"])
        assertEquals(12, args["count"])
        assertTrue(logs.single().contains("回退进程内"))
    }

    @Test
    fun `timeout falls back to in-process without hanging`() {
        responder = { _, _ ->
            Thread.sleep(600)
            200 to """{"dailyCap":5}"""
        }
        val core = RemoteAgentCoreClient(
            baseUrl = baseUrl(),
            fallback = InProcessAgentCore,
            logger = logger,
            timeoutMillis = 120L
        )
        val started = System.currentTimeMillis()
        val actual = core.proactiveCap(ProactiveCapInput(3, "雀跃", 4))
        val elapsed = System.currentTimeMillis() - started
        assertEquals(InProcessAgentCore.proactiveCap(ProactiveCapInput(3, "雀跃", 4)), actual)
        assertEquals(4, actual)
        // 决策层不能拖住聊天：120ms 超时 + 回退，必须远小于服务端的 600ms。
        assertTrue("耗时 $elapsed ms，回退不够快", elapsed < 600)
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `every failure keeps the exact in-process result`() {
        // 参数夹取是最容易被「回退写错」的一处：数值类型必须仍是 Int，而不是 Double/String。
        responder = { _, _ -> 503 to "" }
        val core = client()
        val result = core.clampToolArgs("remember", mapOf("importance" to 9))
        assertEquals(5, result["importance"])
        assertTrue(result["importance"] is Int)
    }

    // ------------------------------------------------------------------ 3. 开关

    @Test
    fun `switch off sends zero requests`() {
        val core = client(remoteEnabled = false)
        assertTrue(core is InProcessAgentCoreClient)

        core.sleepDecision(SleepInput(true, false, 1410, 480, 120, 0, 6))
        core.deferPlan(null, null, 0L)
        core.proactiveCap(ProactiveCapInput(3, "雀跃", 4))
        core.clampToolArgs("remember", mapOf("importance" to 9))

        assertEquals("开关关闭时不应有任何请求", 0, hits.get())
        assertTrue(paths.isEmpty())
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `switch on sends exactly one request per decision`() {
        val core = client()
        assertFalse(core is InProcessAgentCoreClient)

        core.sleepDecision(SleepInput(true, false, 1410, 480, 120, 0, 6))
        assertEquals(1, hits.get())
        assertEquals(AgentCoreProtocol.SLEEP_DECISION_PATH, paths.single())

        core.deferPlan(90, "现在不想说话", 1700000000000L)
        core.proactiveCap(ProactiveCapInput(3, "雀跃", 4))
        core.clampToolArgs("web_search", mapOf("count" to 50))
        assertEquals(4, hits.get())
        assertEquals(
            listOf(
                AgentCoreProtocol.SLEEP_DECISION_PATH,
                AgentCoreProtocol.DEFER_PLAN_PATH,
                AgentCoreProtocol.PROACTIVE_CAP_PATH,
                AgentCoreProtocol.TOOL_ARGS_PATH
            ),
            paths.toList()
        )
        assertTrue(logs.isEmpty())
    }

    // ------------------------------------------------------------------ 3.5 网络加固（审计 P2-7）

    /**
     * 审计 P2-7：决策客户端**不跟随重定向**。
     *
     * OkHttp 默认对 307/308 保留方法与请求体并跟到跨域地址——一台被劫持的局域网主机
     * 可以让 `tool-args` 里的文本（例如 remember 的 content）转给自己的收集端。
     * 这里让假服务回 302 + Location，断言客户端只发了 1 个请求（没有打到 /collect），
     * 并且按「非 2xx」回退进程内。
     */
    @Test
    fun `redirects are not followed`() {
        server.extraHeaders = { listOf("Location: /collect") }
        responder = { _, _ -> 302 to "" }
        val core = client()

        val decision = core.sleepDecision(SleepInput(true, false, 1410, 480, 120, 0, 6))

        assertEquals("重定向不该被跟随：只能有一个请求", 1, hits.get())
        assertEquals(listOf(AgentCoreProtocol.SLEEP_DECISION_PATH), paths.toList())
        assertEquals(
            "回退结果必须与进程内一致",
            InProcessAgentCore.sleepDecision(SleepInput(true, false, 1410, 480, 120, 0, 6)),
            decision
        )
        assertEquals(1L, AgentCoreRemoteStats.fallbackCount())
        assertEquals(1L, AgentCoreRemoteStats.fallbackCount("sleep-decision"))
    }

    /** 审计 P2-7 / 安全 P2-8：响应体超过上限按协议失败回退，不能整份读进内存。 */
    @Test
    fun `oversized response falls back instead of being read`() {
        val huge = """{"moodRelaxed":true,"asleep":false,"dnd":false,"wake":true,"marksRead":true,"pad":"""" +
            "x".repeat(RemoteAgentCoreClient.MAX_RESPONSE_BYTES + 1024) + """"}"""
        responder = { _, _ -> 200 to huge }
        val core = client()

        val decision = core.sleepDecision(SleepInput(true, false, 1410, 480, 120, 0, 6))

        assertTrue(
            "响应过大必须回退（日志里应当说明原因）：" + logs,
            logs.any { it.contains("响应体过大") }
        )
        assertEquals(
            InProcessAgentCore.sleepDecision(SleepInput(true, false, 1410, 480, 120, 0, 6)),
            decision
        )
        assertEquals(1L, AgentCoreRemoteStats.fallbackCount())
        assertEquals(0L, AgentCoreRemoteStats.successCount())
    }

    /** 审计 P2-7：回退不再只有一行 logcat——计数器按 action 分开记，成功/失败都能看见。 */
    @Test
    fun `fallbacks and successes are observable`() {
        responder = ::realContract
        val core = client()
        core.deferPlan(null, null, 0L)
        core.proactiveCap(ProactiveCapInput(3, "雀跃", 4))
        assertEquals(2L, AgentCoreRemoteStats.successCount())
        assertEquals(0L, AgentCoreRemoteStats.fallbackCount())

        responder = { _, _ -> 503 to "" }
        core.deferPlan(null, null, 0L)
        assertEquals(1L, AgentCoreRemoteStats.fallbackCount())
        assertEquals(1L, AgentCoreRemoteStats.fallbackCount("defer-plan"))
        assertEquals(0L, AgentCoreRemoteStats.fallbackCount("sleep-decision"))
        // 日志里带累计回退次数，便于「永远连不上」与「偶尔抖一下」区分。
        assertTrue(logs.any { it.contains("累计回退") })
    }

    // ------------------------------------------------------------------ 4. 进程内实现与直接调用一致

    @Test
    fun `in-process client is byte-for-byte the domain implementation`() {
        val input = SleepInput(true, false, 1410, 480, 120, 6, 6)
        assertEquals(InProcessAgentCore.sleepDecision(input), InProcessAgentCoreClient.sleepDecision(input))
        assertEquals(
            InProcessAgentCore.deferPlan(90, "先缓缓", 1000L),
            InProcessAgentCoreClient.deferPlan(90, "先缓缓", 1000L)
        )
        assertEquals(
            InProcessAgentCore.proactiveCap(ProactiveCapInput(3, "雀跃", 4)),
            InProcessAgentCoreClient.proactiveCap(ProactiveCapInput(3, "雀跃", 4))
        )
        assertEquals(
            InProcessAgentCore.clampToolArgs("web_search", mapOf("count" to 50)),
            InProcessAgentCoreClient.clampToolArgs("web_search", mapOf("count" to 50))
        )
        assertEquals(0, hits.get())
    }

    @Test
    fun `remote sends the documented request fields`() {
        responder = ::realContract
        val core = client()
        core.sleepDecision(SleepInput(true, true, 1410, 480, 120, 6, 6))
        val body = lastBody.get().orEmpty()
        for (field in listOf(
            "sleepEnabled",
            "dndOnSleep",
            "sleepMinutes",
            "wakeMinutes",
            "nowMinutes",
            "userMessagesInWindow",
            "wakeThreshold"
        )) {
            assertTrue("请求体缺少字段 $field：$body", body.contains("\"$field\""))
        }
    }
}

// ---------------------------------------------------------------------------
// 下面几个辅助函数只服务于「假 agentd」：把请求体拆回参数。生产代码不需要它们
// （服务端 agentd 有自己的字段校验），所以没有放进 AgentCoreCodec 的公开 API。
// ---------------------------------------------------------------------------

private data class DeferRequest(val minutes: Int?, val reason: String?, val now: Long)

private fun parseObject(body: String): JsonValue.Obj =
    Json.parse(body) as JsonValue.Obj

private fun decodeDeferPlanRequestForTest(body: String): DeferRequest {
    val obj = parseObject(body)
    val reasonValue = obj.fields["reason"]
    return DeferRequest(
        minutes = (obj.fields["minutes"] as? JsonValue.Num)?.asInt,
        reason = (reasonValue as? JsonValue.Str)?.value,
        now = (obj.fields["now"] as? JsonValue.Num)?.asLong ?: 0L
    )
}

private fun decodeProactiveCapRequestForTest(body: String): ProactiveCapInput {
    val obj = parseObject(body)
    return ProactiveCapInput(
        level = (obj.fields["level"] as? JsonValue.Num)?.asInt ?: 0,
        moodLabel = (obj.fields["moodLabel"] as? JsonValue.Str)?.value,
        moodIntensity = (obj.fields["moodIntensity"] as? JsonValue.Num)?.asInt
    )
}

private fun decodeToolArgsRequestForTest(body: String): Pair<String, Map<String, Any?>> {
    val obj = parseObject(body)
    val tool = (obj.fields["tool"] as JsonValue.Str).value
    val args = obj.fields["args"] as JsonValue.Obj
    val plain = LinkedHashMap<String, Any?>()
    args.fields.forEach { (key, value) -> plain[key] = AgentCoreCodec.toPlainValue(value) }
    return tool to plain
}

private fun encodeToolArgsResponseForTest(args: Map<String, Any?>): String {
    val fields = LinkedHashMap<String, JsonValue>()
    args.forEach { (key, value) -> fields[key] = AgentCoreCodec.fromPlainValue(value) }
    return Json.stringify(
        JsonValue.Obj(linkedMapOf("args" to JsonValue.Obj(fields)))
    )
}

/**
 * 极简假 HTTP 服务（`ServerSocket` + 手写 HTTP/1.1）。
 *
 * 为什么不用 `com.sun.net.httpserver` / MockWebServer：
 * - Android 单测的编译类路径来自 `android.jar`，里面没有 `jdk.httpserver` 模块，写不出 `HttpServer`；
 * - `mockwebserver` 在项目里不是已有依赖，0.11.0 不想为了让测试跑起来多加一个库。
 *
 * 每条连接一个后台线程、`Connection: close`、只支持这一组测试需要的 POST + Content-Length。
 * 它只服务于本文件的断言，不追求通用。
 */
private class FakeHttpServer(
    private val onRequest: (path: String, body: String) -> Pair<Int, String>
) {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = serverSocket.localPort

    /** 追加到响应头的行（例如 `Location:`，用来验证客户端不跟随重定向）。 */
    @Volatile
    var extraHeaders: (Int) -> List<String> = { emptyList() }

    @Volatile
    private var running = true

    init {
        thread(isDaemon = true, name = "fake-agentd-accept") {
            while (running) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "fake-agentd-conn") {
                    runCatching { handle(socket) }
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket.close() }
    }

    private fun handle(socket: Socket) {
        socket.use { connection ->
        val input = connection.getInputStream().buffered()
        val requestLine = readAsciiLine(input) ?: return
        var contentLength = 0
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("content-length:")) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        val bodyText = String(body, 0, read, Charsets.UTF_8)
        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
        val (status, responseBody) = onRequest(path, bodyText)
        val bytes = responseBody.toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Error"
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            extraHeaders(status).forEach { line -> append(line).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        connection.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                return if (out.size() == 0) null else out.toString("US-ASCII").trimEnd('\r')
            }
            if (byte == '\n'.code) return out.toString("US-ASCII").trimEnd('\r')
            out.write(byte)
        }
    }
}
