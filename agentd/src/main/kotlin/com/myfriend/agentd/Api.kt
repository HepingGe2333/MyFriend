package com.myfriend.agentd

import com.myfriend.app.domain.DeferReply
import com.myfriend.app.domain.MoodBehavior
import com.myfriend.app.domain.MoodRecovery
import com.myfriend.app.domain.MoodState
import com.myfriend.app.domain.ProactiveLevel
import com.myfriend.app.domain.SleepPolicy
import com.myfriend.app.domain.ToolArgClamp

/**
 * HTTP 合约层：路由 + 请求校验 + 调 domain + 拼 JSON。
 *
 * 这一层**不碰端口**：`handle()` 是一个纯函数（方法、路径、请求体 → 状态码、响应体），
 * 所以单元测试不用起服务器就能覆盖每一条路由、每一个错误分支。
 * 真正的 `HttpServer` 接线在 [AgentdServer] 里，只负责读写字节。
 *
 * 错误统一是 `{"error":{"message":"…中文…","code":"…"}}` + 4xx/5xx；
 * 绝不静默返回 200（否则客户端会把「参数写错」当成「功能没实现」）。
 */
object AgentdApi {

    const val SERVICE = "myfriend-agentd"
    const val VERSION = "0.10.0"

    data class Response(val status: Int, val body: String)

    /** 可预期的请求错误：由 [handle] 翻成统一错误体。 */
    private class ApiFailure(val status: Int, val code: String, message: String) : Exception(message)

    fun handle(method: String, rawPath: String, body: String?): Response {
        val path = rawPath.substringBefore('?')
        val verb = method.uppercase()
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
            errorResponse(400, "invalid_json", "请求体不是合法 JSON：${invalid.message}")
        } catch (unexpected: Exception) {
            errorResponse(500, "internal_error", "服务内部错误：${unexpected.message ?: unexpected::class.simpleName}")
        }
    }

    private fun routeCore(verb: String, path: String, body: String?): Response {
        val known = setOf(
            "/v1/core/sleep-decision",
            "/v1/core/defer-plan",
            "/v1/core/proactive-cap",
            "/v1/core/tool-args"
        )
        if (path !in known) throw ApiFailure(404, "not_found", "没有这个接口：$path")
        if (verb != "POST") throw ApiFailure(405, "method_not_allowed", "$path 只支持 POST")
        return when (path) {
            "/v1/core/sleep-decision" -> sleepDecision(body)
            "/v1/core/defer-plan" -> deferPlan(body)
            "/v1/core/proactive-cap" -> proactiveCap(body)
            "/v1/core/tool-args" -> toolArgs(body)
            else -> throw ApiFailure(404, "not_found", "没有这个接口：$path")
        }
    }

    // ------------------------------------------------------------------ 睡眠

    private fun sleepDecision(body: String?): Response {
        val request = requestObject(body)
        val sleepEnabled = request.boolField("sleepEnabled")
        val sleepMinutes = request.optionalIntField("sleepMinutes")
        val wakeMinutes = request.optionalIntField("wakeMinutes")
        val nowMinutes = request.intField("nowMinutes")
        val dndOnSleep = request.boolField("dndOnSleep")
        val recent = request.intField("userMessagesInWindow")
        val threshold = request.intField("wakeThreshold")

        val decision = SleepPolicy.evaluate(
            sleepEnabled = sleepEnabled,
            sleepMinutes = sleepMinutes,
            wakeMinutes = wakeMinutes,
            minuteOfDay = nowMinutes,
            dndOnSleep = dndOnSleep,
            recentUserMessages = recent,
            wakeThreshold = threshold
        )
        val wakeClock = SleepPolicy.wakeClockText(sleepEnabled, sleepMinutes, wakeMinutes)
        val reason = when {
            !decision.asleep -> "醒着，会正常回复"
            decision.dnd -> "免打扰中，不会醒"
            decision.woken -> MoodRecovery.WAKE_REASON
            wakeClock != null -> "睡着了 · $wakeClock 醒"
            else -> "睡着了"
        }
        return Response(
            200,
            Json.stringify(
                JsonValue.Obj(
                    linkedMapOf(
                        "wake" to JsonValue.Bool(decision.shouldReply),
                        "moodRelaxed" to JsonValue.Bool(decision.woken),
                        "reason" to JsonValue.Str(reason)
                    )
                )
            )
        )
    }

    // ------------------------------------------------------------------ defer_reply

    private fun deferPlan(body: String?): Response {
        val request = requestObject(body)
        val now = request.longField("now")
        val plan = DeferReply.plan(
            minutes = request.optionalIntField("minutes"),
            reason = request.optionalStringField("reason"),
            now = now
        )
        return Response(
            200,
            Json.stringify(
                JsonValue.Obj(
                    linkedMapOf(
                        "earliestAt" to JsonValue.Num(plan.earliestAt.toString()),
                        "latestAt" to JsonValue.Num(plan.latestAt.toString()),
                        "minutes" to JsonValue.Num(plan.minutes.toString()),
                        "reason" to JsonValue.Str(plan.reason)
                    )
                )
            )
        )
    }

    // ------------------------------------------------------------------ 主动程度

    private fun proactiveCap(body: String?): Response {
        val request = requestObject(body)
        val level = request.intField("level")
        val baseCap = ProactiveLevel.dailyCap(level)
        // moodIntensity 单独存在时无法改变上限：域模型要靠 label 判断 valence / energy。
        // 这里只接受 moodLabel + moodIntensity 一起给（README 里写清楚了）。
        val label = request.optionalStringField("moodLabel")
        val intensity = request.optionalIntField("moodIntensity")
        val cap = if (!label.isNullOrBlank()) {
            val tuning = MoodBehavior.from(
                MoodState(label = label, intensity = intensity ?: 2),
                now = 0L
            )
            MoodBehavior.effectiveDailyCap(baseCap, tuning)
        } else {
            baseCap
        }
        return Response(
            200,
            Json.stringify(JsonValue.Obj(linkedMapOf("dailyCap" to JsonValue.Num(cap.toString()))))
        )
    }

    // ------------------------------------------------------------------ 工具参数

    private fun toolArgs(body: String?): Response {
        val request = requestObject(body)
        val tool = request.stringField("tool")
        val args = request.objField("args")
        val out = LinkedHashMap(args.fields)
        when (tool) {
            "remember" -> out["importance"] = JsonValue.Num(
                ToolArgClamp.clampImportance(
                    args.optionalIntField("importance") ?: ToolArgClamp.IMPORTANCE_DEFAULT
                ).toString()
            )

            "web_search" -> out["count"] = JsonValue.Num(
                ToolArgClamp.clampCount(
                    args.optionalIntField("count") ?: ToolArgClamp.COUNT_DEFAULT
                ).toString()
            )

            "defer_reply" -> out["minutes"] = JsonValue.Num(
                ToolArgClamp.clampDeferMinutes(
                    args.optionalIntField("minutes") ?: ToolArgClamp.MINUTES_DEFAULT
                ).toString()
            )

            else -> throw ApiFailure(
                400,
                "unknown_tool",
                "不认识的工具：$tool（支持 remember / web_search / defer_reply）"
            )
        }
        return Response(200, Json.stringify(JsonValue.Obj(linkedMapOf("args" to JsonValue.Obj(out)))))
    }

    // ------------------------------------------------------------------ 错误与取值

    private fun errorResponse(status: Int, code: String, message: String): Response = Response(
        status,
        Json.stringify(
            JsonValue.Obj(
                linkedMapOf(
                    "error" to JsonValue.Obj(
                        linkedMapOf(
                            "message" to JsonValue.Str(message),
                            "code" to JsonValue.Str(code)
                        )
                    )
                )
            )
        )
    )

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
