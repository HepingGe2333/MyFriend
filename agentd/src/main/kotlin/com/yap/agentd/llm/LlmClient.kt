package com.yap.agentd.llm

import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonParseException
import com.yap.app.core.json.JsonValue
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * OpenAI 兼容的 `/v1/chat/completions` 客户端（JDK 17 `java.net.http.HttpClient`，零第三方依赖）。
 *
 * 只做三件事：
 * 1. **拼请求**：`model` / `messages` / `stream=true` / `stream_options.include_usage` /
 *    `temperature=0.75` / `max_tokens=16384`（默认值来自 [LlmDefaults]，可被 [LlmConfig] 覆盖）；
 * 2. **解析 SSE**：按行读 `data:`，把 `choices[0].delta.content`（也认 `text` / `message.content`）
 *    交给 `onDelta`，`[DONE]` 结束；usage 出现在最后一条（`choices` 为空）分片里时照样收下；
 * 3. **分类失败**：见 [LlmErrorKind]。任何文案都不含 key；上游响应体只截 300 字且只进 [LlmException.detail]。
 *
 * 刻意**不做**的两件事（都写在 README 的「没做到/风险」里）：
 * - 不在客户端断开时取消（契约 §3.3 要求服务端继续生成并落库）；
 * - 不做工具回路（模型只输出文字；`web_search` 等工具的循环是下一步）。
 */
class LlmClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val requestTimeout: Duration = Duration.ofMinutes(5)
) {

    /**
     * 流式生成：每收到一段文字就调一次 [onDelta]，返回本次用量。
     *
     * 一次性把响应读成字符串是不允许的：聊天必须"边生成边下发"（契约 §3.3 的 `delta` 事件）。
     *
     * @throws LlmException 配置/网络/上游/空回复
     */
    fun stream(config: LlmConfig, messages: List<LlmMessage>, onDelta: (String) -> Unit): LlmUsage {
        if (messages.isEmpty()) throw LlmException(LlmErrorKind.INVALID_CONFIG, "没有可发送的消息")
        val request = buildRequest(config, messages, stream = true)
        val response = send(config, request)
        val body = response.body()
        try {
            val contentType = response.headers().firstValue("content-type").orElse("").lowercase()
            if (response.statusCode() !in 200..299) {
                throw failureForStatus(response.statusCode(), readLimited(body, MAX_ERROR_BODY_CHARS))
            }
            if (!contentType.contains("text/event-stream")) {
                // 有些网关忽略 stream=true，直接回一整份 completion：按非流式解析，仍然逐段下发。
                val completion = parseCompletion(readLimited(body, MAX_COMPLETION_BODY_BYTES))
                if (completion.text.isBlank()) {
                    throw LlmException(LlmErrorKind.EMPTY_REPLY, "模型没有生成任何内容（空回复）。")
                }
                onDelta(completion.text)
                return completion.usage
            }
            return consumeSse(body) { delta -> onDelta(delta) }
        } finally {
            runCatching { body.close() }
        }
    }

    /**
     * 非流式生成（内部仍是同一个流式实现，只是把 delta 收集起来）。
     *
     * 进化评估、情绪判定、主动消息都用它：它们只关心"最后那句话"，不需要逐字下发，
     * 但走同一条代码路径意味着参数、错误分类、usage 统计不会出现第二套。
     */
    fun complete(config: LlmConfig, messages: List<LlmMessage>): LlmCompletion {
        val builder = StringBuilder()
        val usage = stream(config, messages) { delta -> builder.append(delta) }
        return LlmCompletion(builder.toString(), usage)
    }

    // ------------------------------------------------------------------ 请求

    private fun buildRequest(config: LlmConfig, messages: List<LlmMessage>, stream: Boolean): HttpRequest {
        val uri = try {
            URI.create(config.endpoint())
        } catch (invalid: IllegalArgumentException) {
            throw LlmException(
                LlmErrorKind.INVALID_CONFIG,
                "LLM 地址不合法（需要 http(s)://…）：${config.baseUrl}"
            )
        }
        if (uri.scheme != "http" && uri.scheme != "https") {
            throw LlmException(LlmErrorKind.INVALID_CONFIG, "LLM 地址必须是 http:// 或 https://")
        }
        val payload = buildPayload(config, messages, stream)
        val builder = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
        if (!config.apiKey.isNullOrBlank()) {
            builder.header("Authorization", "Bearer ${config.apiKey}")
        }
        return builder.build()
    }

    private fun send(config: LlmConfig, request: HttpRequest): HttpResponse<InputStream> {
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LlmException(LlmErrorKind.NETWORK, "请求被中断", cause = interrupted)
        } catch (timeout: java.net.http.HttpTimeoutException) {
            throw LlmException(LlmErrorKind.TIMEOUT, "模型接口超时", cause = timeout)
        } catch (io: java.io.IOException) {
            // 连接失败/DNS/TLS/对端断开：给用户看的是"连不上"，细节只进日志。
            throw LlmException(
                LlmErrorKind.NETWORK,
                io.message?.take(160) ?: "网络错误",
                detail = "${io::class.simpleName}: ${io.message}",
                cause = io
            )
        } catch (invalid: IllegalArgumentException) {
            throw LlmException(LlmErrorKind.INVALID_CONFIG, "LLM 地址不合法：${invalid.message}")
        }
        // 每次请求记一行（配置已脱敏；key 永远不会出现在这里）。
        System.err.println("[agentd] LLM 请求 → ${config.redacted()}（HTTP ${response.statusCode()}）")
        return response
    }

    // ------------------------------------------------------------------ SSE 解析

    /**
     * 按行消费 SSE。返回 usage；一个文字分片都没收到时抛 [LlmErrorKind.EMPTY_REPLY]。
     *
     * 行长度上限 [MAX_LINE_CHARS]：上游异常时可能吐出一整行几十 MB 的 base64/错误页，
     * 不能让它把内存吃掉。
     */
    private fun consumeSse(body: InputStream, onDelta: (String) -> Unit): LlmUsage {
        var usage = LlmUsage.ZERO
        var emitted = false
        var sawDone = false
        val promptSeed = StringBuilder()
        try {
            BufferedReader(InputStreamReader(body, StandardCharsets.UTF_8), 8 * 1024).use { reader ->
                while (true) {
                    val line = readLimitedLine(reader) ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") {
                        sawDone = true
                        break
                    }
                    val chunk = parseStreamChunk(payload)
                    if (!chunk.text.isNullOrEmpty()) {
                        emitted = true
                        promptSeed.append(chunk.text)
                        onDelta(chunk.text)
                    }
                    if (chunk.usage != LlmUsage.ZERO) usage = chunk.usage
                }
            }
        } catch (io: java.io.IOException) {
            throw LlmException(
                LlmErrorKind.NETWORK,
                io.message?.take(160) ?: "流读取中断",
                detail = "${io::class.simpleName}: ${io.message}",
                cause = io
            )
        }
        if (!emitted) {
            val reason = if (sawDone) "服务端只发了 [DONE]，没有内容" else "流里没有任何文字分片"
            throw LlmException(LlmErrorKind.EMPTY_REPLY, "模型没有生成任何内容（$reason）。")
        }
        val completion = LlmUsage.estimate(promptSeed.toString())
        return if (usage.promptTokens > 0 || usage.completionTokens > 0) {
            usage
        } else {
            LlmUsage(promptTokens = 0, completionTokens = completion)
        }
    }

    private data class StreamChunk(val text: String?, val usage: LlmUsage)

    private fun parseStreamChunk(payload: String): StreamChunk {
        val root = try {
            Json.parse(payload) as? JsonValue.Obj
        } catch (_: JsonParseException) {
            null
        } ?: throw LlmException(
            LlmErrorKind.INVALID_RESPONSE,
            "流式分片不是合法 JSON",
            detail = payload.take(300)
        )
        root.fields["error"]?.let { error ->
            throw LlmException(
                LlmErrorKind.UPSTREAM,
                "模型接口在流里返回错误",
                detail = errorText(error).take(300)
            )
        }
        val usage = parseUsage(root)
        val choice = (root.fields["choices"] as? JsonValue.Arr)?.items?.firstOrNull() as? JsonValue.Obj
            ?: return StreamChunk(text = null, usage = usage)
        val delta = choice.fields["delta"] as? JsonValue.Obj
        val content = string(delta?.fields?.get("content"))
            ?: string(choice.fields["text"])
            ?: string((choice.fields["message"] as? JsonValue.Obj)?.fields?.get("content"))
        return StreamChunk(text = content, usage = usage)
    }

    private fun parseCompletion(raw: String): LlmCompletion {
        val root = try {
            Json.parse(raw) as? JsonValue.Obj
        } catch (_: JsonParseException) {
            null
        } ?: throw LlmException(
            LlmErrorKind.INVALID_RESPONSE,
            "模型返回的不是 JSON",
            detail = raw.take(300)
        )
        root.fields["error"]?.let { error ->
            throw LlmException(LlmErrorKind.UPSTREAM, "模型接口返回错误", detail = errorText(error).take(300))
        }
        val choice = (root.fields["choices"] as? JsonValue.Arr)?.items?.firstOrNull() as? JsonValue.Obj
            ?: throw LlmException(LlmErrorKind.INVALID_RESPONSE, "响应里没有 choices", detail = raw.take(300))
        val text = string((choice.fields["message"] as? JsonValue.Obj)?.fields?.get("content"))
            ?: string(choice.fields["text"])
            ?: ""
        val usage = parseUsage(root)
        return LlmCompletion(text, if (usage != LlmUsage.ZERO) usage else LlmUsage(completionTokens = LlmUsage.estimate(text)))
    }

    private fun parseUsage(root: JsonValue.Obj): LlmUsage {
        val usage = root.fields["usage"] as? JsonValue.Obj ?: return LlmUsage.ZERO
        val prompt = (usage.fields["prompt_tokens"] as? JsonValue.Num)?.asInt
            ?: (usage.fields["input_tokens"] as? JsonValue.Num)?.asInt
            ?: 0
        val completion = (usage.fields["completion_tokens"] as? JsonValue.Num)?.asInt
            ?: (usage.fields["output_tokens"] as? JsonValue.Num)?.asInt
            ?: 0
        return LlmUsage(prompt.coerceAtLeast(0), completion.coerceAtLeast(0))
    }

    // ------------------------------------------------------------------ 错误

    private fun failureForStatus(status: Int, body: String): LlmException {
        val detail = extractErrorMessage(body).take(300)
        val message = if (detail.isBlank()) "" else "：$detail"
        return when (status) {
            401, 403 -> LlmException(LlmErrorKind.UNAUTHORIZED, "模型接口拒绝了请求（HTTP $status）", detail = detail)
            429 -> LlmException(LlmErrorKind.RATE_LIMITED, "模型接口限流（HTTP 429）", detail = detail)
            else -> LlmException(LlmErrorKind.UPSTREAM, "模型接口返回 HTTP $status$message", detail = detail)
        }
    }

    private fun extractErrorMessage(body: String): String {
        val root = runCatching { Json.parse(body) as? JsonValue.Obj }.getOrNull() ?: return body.take(300)
        root.fields["error"]?.let { error -> return errorText(error).take(300) }
        return string(root.fields["message"])?.take(300).orEmpty()
    }

    /** `{"error":{"message":"…"}}` / `{"error":"…"}` 都认。 */
    private fun errorText(value: JsonValue): String = when (value) {
        is JsonValue.Obj -> string(value.fields["message"]) ?: Json.stringify(value)
        is JsonValue.Str -> value.value
        else -> Json.stringify(value)
    }

    // ------------------------------------------------------------------ 小工具

    private fun string(value: JsonValue?): String? = (value as? JsonValue.Str)?.value

    /** 读一行，超过 [MAX_LINE_CHARS] 直接失败（不让异常上游吃内存）。 */
    private fun readLimitedLine(reader: BufferedReader): String? {
        val builder = StringBuilder()
        while (true) {
            val code = reader.read()
            if (code < 0) return if (builder.isEmpty()) null else builder.toString()
            val ch = code.toChar()
            if (ch == '\n') return builder.toString().removeSuffix("\r")
            if (builder.length >= MAX_LINE_CHARS) {
                throw LlmException(
                    LlmErrorKind.INVALID_RESPONSE,
                    "模型返回的一行超过 ${MAX_LINE_CHARS / 1024} KiB，已中止读取"
                )
            }
            builder.append(ch)
        }
    }

    private fun readLimited(input: InputStream, limit: Int): String {
        val bytes = input.readNBytes(limit + 1)
        if (bytes.size > limit) return String(bytes, 0, limit, StandardCharsets.UTF_8)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun buildPayload(config: LlmConfig, messages: List<LlmMessage>, stream: Boolean): String {
        val messageArray = messages.mapTo(mutableListOf<JsonValue>()) { message ->
            JsonValue.Obj(
                linkedMapOf(
                    "role" to JsonValue.Str(message.role),
                    "content" to JsonValue.Str(message.content)
                )
            )
        }
        val fields = linkedMapOf<String, JsonValue>(
            "model" to JsonValue.Str(config.model),
            "messages" to JsonValue.Arr(messageArray),
            "stream" to JsonValue.Bool(stream),
            // 规范默认值：temperature 0.75、max_tokens 16384（`spec/api-defaults/README.md`）。
            "temperature" to JsonValue.Num(config.temperature.toString()),
            "max_tokens" to JsonValue.Num(config.maxTokens.toString())
        )
        if (stream) {
            // 让服务端在最后一个分片里带上 usage；不支持的网关会忽略这个字段。
            fields["stream_options"] = JsonValue.Obj(
                linkedMapOf("include_usage" to JsonValue.Bool(true))
            )
        }
        return Json.stringify(JsonValue.Obj(fields))
    }

    companion object {
        /** 单行 SSE 上限：正常分片只有几个字，1 MiB 足够；超过说明上游吐了异常内容。 */
        const val MAX_LINE_CHARS: Int = 1024 * 1024

        /** 错误响应体只读这么多（不含完整错误页）。 */
        const val MAX_ERROR_BODY_CHARS: Int = 8 * 1024

        /** 非流式响应体上限（32 MiB）：完整 completion 不该更大。 */
        const val MAX_COMPLETION_BODY_BYTES: Int = 32 * 1024 * 1024

        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            // 明确用 HTTP/1.1：SSE 与反向代理的兼容性最好，也避开 h2c upgrade 的坑。
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}
