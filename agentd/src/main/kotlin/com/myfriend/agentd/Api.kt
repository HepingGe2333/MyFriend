package com.myfriend.agentd

import com.myfriend.app.core.json.Json
import com.myfriend.app.core.json.JsonParseException
import com.myfriend.app.core.json.JsonValue
import com.myfriend.app.domain.AgentCore
import com.myfriend.app.domain.AgentCoreCodec
import com.myfriend.app.domain.AgentCoreProtocol
import com.myfriend.app.domain.InProcessAgentCore
import com.myfriend.app.domain.ProactiveCapInput
import com.myfriend.app.domain.SleepInput

/**
 * HTTP 合约层：路由 + 请求校验 + 调 domain + 拼 JSON。
 *
 * 这一层**不碰端口**：`handle()` 是一个纯函数（方法、路径、请求体 → 状态码、响应体），
 * 所以单元测试不用起服务器就能覆盖每一条路由、每一个错误分支。
 * 真正的 socket/HTTP 接线在 [AgentdServer] 里，只负责读写字节、请求体上限与鉴权。
 *
 * 错误统一是 `{"error":{"message":"…中文…","code":"…"}}` + 4xx/5xx；
 * 绝不静默返回 200（否则客户端会把「参数写错」当成「功能没实现」）。
 *
 * 0.11.1 的加固（安全审计 P2-1 / P2-2）：
 * - 请求体上限 [MAX_BODY_BYTES]（1 MiB）：超了返回 413 `content_too_large`，不解析、不回显；
 * - 错误文案上限 [MAX_ERROR_MESSAGE_CHARS]：任何错误体最多 512 个字符，客户端输入只出现在
 *   固定的模板里（不再把工具名/路径原样拼回去），未知输入不回显原文；
 * - 500 只回固定文案 + 一个随机 `errorId`，异常细节只写服务端 stderr（`errorId` 用来对照日志）。
 */
object AgentdApi {

    const val SERVICE = "myfriend-agentd"
    const val VERSION = "0.12.0"

    /** 请求体字节上限（HTTP 层与 handler 层共用这一份）。 */
    const val MAX_BODY_BYTES = 1024 * 1024

    /** 错误体里 `message` 的字符上限。 */
    const val MAX_ERROR_MESSAGE_CHARS = 512

    /** 回显客户端输入（路径等）时的字符上限；超出只留前缀，避免「发 1 份收 1 份」。 */
    const val MAX_ECHO_CHARS = 64

    data class Response(val status: Int, val body: String)

    /** 可预期的请求错误：由 [handle] 翻成统一错误体。 */
    private class ApiFailure(val status: Int, val code: String, message: String) : Exception(message)

    fun handle(method: String, rawPath: String, body: String?): Response {
        val path = rawPath.substringBefore('?')
        val verb = method.uppercase()
        // 直接在 handler 层也挡一次：测试与「有人绕过 AgentdServer 直接调 Api」时同样有上限。
        if (body != null && body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            return errorResponse(
                413,
                "content_too_large",
                "请求体超过 ${MAX_BODY_BYTES / 1024} KiB 上限"
            )
        }
        return try {
            when (path) {
                "/health" -> {
                    if (verb != "GET") throw ApiFailure(405, "method_not_allowed", "/health 只支持 GET")
                    Response(
                        200,
                        Json.stringify(
                            JsonValue.Obj(
                                linkedMapOf(
                                    "ok" to JsonValue.Bool(true),
                                    "service" to JsonValue.Str(SERVICE),
                                    "version" to JsonValue.Str(VERSION)
                                )
                            )
                        )
                    )
                }

                else -> routeCore(verb, path, body)
            }
        } catch (failure: ApiFailure) {
            errorResponse(failure.status, failure.code, failure.message ?: "请求不合法")
        } catch (invalid: JsonParseException) {
            errorResponse(400, "invalid_json", "请求体不是合法 JSON：${invalid.message.orEmpty()}")
        } catch (unexpected: Exception) {
            unexpectedFailureResponse(unexpected)
        }
    }

    private fun routeCore(verb: String, path: String, body: String?): Response {
        // 路由表与客户端共用同一份常量（AgentCoreProtocol），改名时不会一边改一边漏。
        if (path !in AgentCoreProtocol.CORE_PATHS) throw ApiFailure(404, "not_found", "没有这个接口")
        if (verb != "POST") throw ApiFailure(405, "method_not_allowed", "${echo(path)} 只支持 POST")
        return when (path) {
            AgentCoreProtocol.SLEEP_DECISION_PATH -> sleepDecision(body)
            AgentCoreProtocol.DEFER_PLAN_PATH -> deferPlan(body)
            AgentCoreProtocol.PROACTIVE_CAP_PATH -> proactiveCap(body)
            AgentCoreProtocol.TOOL_ARGS_PATH -> toolArgs(body)
            else -> throw ApiFailure(404, "not_found", "没有这个接口")
        }
    }

    // ------------------------------------------------------------------ 睡眠
    //
    // 请求校验留在这里（错误码/中文提示是 HTTP 契约的一部分），判定与 reason 文案在
    // `InProcessAgentCore`：服务端与 Android 的进程内实现必须给出同一句话。

    private fun sleepDecision(body: String?): Response {
        val request = requestObject(body)
        val sleepEnabled = request.boolField("sleepEnabled")
        val sleepMinutes = request.optionalIntField("sleepMinutes")
        val wakeMinutes = request.optionalIntField("wakeMinutes")
        val nowMinutes = request.intField("nowMinutes")
        val dndOnSleep = request.boolField("dndOnSleep")
        val recent = request.intField("userMessagesInWindow")
        val threshold = request.intField("wakeThreshold")

        val decision = InProcessAgentCore.sleepDecision(
            SleepInput(
                sleepEnabled = sleepEnabled,
                dndOnSleep = dndOnSleep,
                sleepMinutes = sleepMinutes,
                wakeMinutes = wakeMinutes,
                nowMinutes = nowMinutes,
                userMessagesInWindow = recent,
                wakeThreshold = threshold
            )
        )
        // 0.11.0 起响应多一个 `marksRead`（兼容字段）：远程模式下缺少它，免打扰时的已读抑制会丢失。
        return Response(200, AgentCoreCodec.encodeSleepDecision(decision))
    }

    // ------------------------------------------------------------------ defer_reply
    private fun deferPlan(body: String?): Response {
        val request = requestObject(body)
        val plan = InProcessAgentCore.deferPlan(
            minutes = request.optionalIntField("minutes"),
            reason = request.optionalStringField("reason"),
            now = request.longField("now")
        )
        return Response(200, AgentCoreCodec.encodeDeferPlan(plan))
    }

    // ------------------------------------------------------------------ 主动程度
    private fun proactiveCap(body: String?): Response {
        val request = requestObject(body)
        val input = ProactiveCapInput(
            level = request.intField("level"),
            // moodIntensity 单独存在时无法改变上限：域模型要靠 label 判断 valence / energy。
            // 只给强度、不给标签时按「没有心情」处理（README 里写清楚了）。
            moodLabel = request.optionalStringField("moodLabel"),
            moodIntensity = request.optionalIntField("moodIntensity")
        )
        val cap = InProcessAgentCore.proactiveCap(input)
        return Response(200, AgentCoreCodec.encodeProactiveCap(cap))
    }

    // ------------------------------------------------------------------ 工具参数
    private fun toolArgs(body: String?): Response {
        val request = requestObject(body)
        val tool = request.stringField("tool")
        val args = request.objField("args")
        // 除被夹取的那个数值键外，其余字段原样回显（保持 JSON 数值的原始写法）。
        val out = LinkedHashMap(args.fields)
        when (tool) {
            AgentCore.TOOL_REMEMBER -> out["importance"] = jsonNumber(
                clampedInt(tool, "importance", args.optionalIntField("importance"))
            )

            AgentCore.TOOL_WEB_SEARCH -> out["count"] = jsonNumber(
                clampedInt(tool, "count", args.optionalIntField("count"))
            )

            AgentCore.TOOL_DEFER_REPLY -> out["minutes"] = jsonNumber(
                clampedInt(tool, "minutes", args.optionalIntField("minutes"))
            )

            else -> throw ApiFailure(
                400,
                "unknown_tool",
                // 不回显工具名：它可能是几 MB 的客户端输入（审计 P2-1 的 2 MB 放大）。
                "不认识的工具（支持 remember / web_search / defer_reply）"
            )
        }
        return Response(200, Json.stringify(JsonValue.Obj(linkedMapOf("args" to JsonValue.Obj(out)))))
    }

    /**
     * 取夹取后的整数：缺省值也由 `:agentcore` 给（不在 HTTP 层再抄一遍 1–5 / 1–12 / 5–720）。
     */
    private fun clampedInt(tool: String, key: String, raw: Int?): Int {
        val args: Map<String, Any?> = if (raw == null) emptyMap() else mapOf(key to raw)
        return InProcessAgentCore.clampToolArgs(tool, args)[key] as Int
    }

    private fun jsonNumber(value: Int): JsonValue = JsonValue.Num(value.toString())

    // ------------------------------------------------------------------ 错误与取值

    /**
     * 500 分支的对外文案：固定模板 + 随机编号，异常细节只进 stderr 日志。
     *
     * `internal` 是为了让测试直接钉住「不泄漏异常文本」这条规则（不用先构造一个真能抛异常的请求）。
     */
    internal fun unexpectedFailureResponse(failure: Exception): Response {
        val errorId = newErrorId()
        logInternalError(errorId, failure)
        return errorResponse(500, "internal_error", "服务内部错误（错误编号 $errorId）")
    }

    private fun errorResponse(status: Int, code: String, message: String): Response = Response(
        status,
        Json.stringify(
            JsonValue.Obj(
                linkedMapOf(
                    "error" to JsonValue.Obj(
                        linkedMapOf(
                            "message" to JsonValue.Str(truncate(message, MAX_ERROR_MESSAGE_CHARS)),
                            "code" to JsonValue.Str(code)
                        )
                    )
                )
            )
        )
    )

    /** 回显客户端输入前的截断（路径等）；超出上限只留前缀。 */
    private fun echo(text: String): String = truncate(text, MAX_ECHO_CHARS)

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    /** 内部错误编号：只用来在 stderr 日志里对照，不含任何内部信息。 */
    private fun newErrorId(): String = java.util.UUID.randomUUID().toString().take(8)

    /** 异常细节只写服务端日志；响应里只有编号。 */
    private fun logInternalError(errorId: String, failure: Exception) {
        System.err.println("[agentd] 内部错误 $errorId：${failure::class.qualifiedName}")
        failure.printStackTrace(System.err)
    }

    private fun requestObject(body: String?): JsonValue.Obj {
        if (body.isNullOrBlank()) throw ApiFailure(400, "empty_body", "请求体不能为空")
        return Json.parse(body) as? JsonValue.Obj
            ?: throw ApiFailure(400, "invalid_body", "请求体必须是一个 JSON 对象")
    }

    private fun JsonValue.Obj.required(key: String, kind: String): JsonValue =
        fields[key] ?: throw ApiFailure(400, "missing_field", "缺少字段 $key（$kind）")

    private fun JsonValue.Obj.stringField(key: String): String {
        val value = required(key, "字符串")
        return (value as? JsonValue.Str)?.value
            ?: throw ApiFailure(400, "invalid_argument", "字段 $key 必须是字符串")
    }

    private fun JsonValue.Obj.optionalStringField(key: String): String? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Str)?.value
            ?: throw ApiFailure(400, "invalid_argument", "字段 $key 必须是字符串")
    }

    private fun JsonValue.Obj.intField(key: String): Int {
        val value = required(key, "整数")
        return (value as? JsonValue.Num)?.asInt
            ?: throw ApiFailure(400, "invalid_argument", "字段 $key 必须是整数")
    }

    private fun JsonValue.Obj.optionalIntField(key: String): Int? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Num)?.asInt
            ?: throw ApiFailure(400, "invalid_argument", "字段 $key 必须是整数")
    }

    private fun JsonValue.Obj.longField(key: String): Long {
        val value = required(key, "整数")
        return (value as? JsonValue.Num)?.asLong
            ?: throw ApiFailure(400, "invalid_argument", "字段 $key 必须是整数")
    }

    private fun JsonValue.Obj.boolField(key: String): Boolean {
        val value = required(key, "布尔")
        return (value as? JsonValue.Bool)?.value
            ?: throw ApiFailure(400, "invalid_argument", "字段 $key 必须是 true/false")
    }

    private fun JsonValue.Obj.objField(key: String): JsonValue.Obj {
        val value = required(key, "对象")
        return value as? JsonValue.Obj
            ?: throw ApiFailure(400, "invalid_argument", "字段 $key 必须是 JSON 对象")
    }
}
