package com.yap.app.domain

import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonParseException
import com.yap.app.core.json.JsonValue
import kotlin.math.floor

/**
 * [AgentCore] 的 HTTP 编解码：数据类 ⇄ `/v1/core/…` 的 JSON。
 *
 * 为什么放在 `:agentcore` 而不是 Android 侧：服务端也要用同一套字段名与默认值。
 * `:agentcore` 不能引 kotlinx-serialization（硬约束：零第三方依赖），所以底层用
 * `com.yap.app.core.json.Json`——一个 ~250 行的递归下降解析器，只支持标准 JSON，
 * 坏输入一律抛 [AgentCoreProtocolException]，由调用方决定「400」还是「回退进程内」。
 *
 * 三个原则：
 * - **不静默降级**：字段缺失 / 类型不对抛异常，不猜一个默认值继续跑（猜错比报错更难查）；
 * - **数值保形**：整数写成整数，不让 `5` 变成 `5.0`；
 * - **向后兼容**：多出来的字段忽略；`marksRead` 缺失时按「会留已读」处理（见 [decodeSleepDecision]）。
 */
object AgentCoreCodec {

    /** 远端错误体最长接受的字符数；超过直接当作「没有可读错误」，避免异常响应放大内存/日志。 */
    const val MAX_ERROR_BODY_CHARS = 1024 * 1024

    /** 取出来给人看/写日志的错误文案上限（0.11.1 P2-2：错误回显必须有界）。 */
    const val MAX_ERROR_MESSAGE_CHARS = 512

    // ------------------------------------------------------------------ 睡眠

    fun encodeSleepInput(input: SleepInput): String = Json.stringify(
        JsonValue.Obj(
            linkedMapOf(
                AgentCoreProtocol.FIELD_SLEEP_ENABLED to JsonValue.Bool(input.sleepEnabled),
                AgentCoreProtocol.FIELD_DND_ON_SLEEP to JsonValue.Bool(input.dndOnSleep),
                AgentCoreProtocol.FIELD_SLEEP_MINUTES to input.sleepMinutes.toJsonNumberOrNull(),
                AgentCoreProtocol.FIELD_WAKE_MINUTES to input.wakeMinutes.toJsonNumberOrNull(),
                AgentCoreProtocol.FIELD_NOW_MINUTES to JsonValue.Num(input.nowMinutes.toString()),
                AgentCoreProtocol.FIELD_USER_MESSAGES_IN_WINDOW to JsonValue.Num(input.userMessagesInWindow.toString()),
                AgentCoreProtocol.FIELD_WAKE_THRESHOLD to JsonValue.Num(input.wakeThreshold.toString())
            )
        )
    )

    fun decodeSleepInput(body: String): SleepInput {
        val obj = parseObject(body, "睡眠判定请求")
        return SleepInput(
            sleepEnabled = obj.bool(AgentCoreProtocol.FIELD_SLEEP_ENABLED),
            dndOnSleep = obj.bool(AgentCoreProtocol.FIELD_DND_ON_SLEEP),
            sleepMinutes = obj.optionalInt(AgentCoreProtocol.FIELD_SLEEP_MINUTES),
            wakeMinutes = obj.optionalInt(AgentCoreProtocol.FIELD_WAKE_MINUTES),
            nowMinutes = obj.int(AgentCoreProtocol.FIELD_NOW_MINUTES),
            userMessagesInWindow = obj.int(AgentCoreProtocol.FIELD_USER_MESSAGES_IN_WINDOW),
            wakeThreshold = obj.int(AgentCoreProtocol.FIELD_WAKE_THRESHOLD)
        )
    }

    fun encodeSleepDecision(decision: SleepDecision): String = Json.stringify(
        JsonValue.Obj(
            linkedMapOf(
                AgentCoreProtocol.FIELD_WAKE to JsonValue.Bool(decision.wake),
                AgentCoreProtocol.FIELD_MOOD_RELAXED to JsonValue.Bool(decision.moodRelaxed),
                AgentCoreProtocol.FIELD_REASON to JsonValue.Str(decision.reason),
                AgentCoreProtocol.FIELD_MARKS_READ to JsonValue.Bool(decision.marksRead)
            )
        )
    )

    /**
     * 读睡眠判定结果。
     *
     * `marksRead` 缺失时默认 **true**：0.10.x 的 agentd 还没有这个字段，那时「睡着了但没开免打扰」
     * 的语义就是留已读；比起把睡眠中的人显示成冷处理，多留一条已读更接近既有行为。
     */
    fun decodeSleepDecision(body: String): SleepDecision {
        val obj = parseObject(body, "睡眠判定响应")
        return SleepDecision(
            wake = obj.bool(AgentCoreProtocol.FIELD_WAKE),
            moodRelaxed = obj.optionalBool(AgentCoreProtocol.FIELD_MOOD_RELAXED) ?: false,
            reason = obj.optionalString(AgentCoreProtocol.FIELD_REASON).orEmpty(),
            marksRead = obj.optionalBool(AgentCoreProtocol.FIELD_MARKS_READ) ?: true
        )
    }

    // ------------------------------------------------------------------ defer_reply

    fun encodeDeferPlanRequest(minutes: Int?, reason: String?, now: Long): String = Json.stringify(
        JsonValue.Obj(
            linkedMapOf(
                AgentCoreProtocol.FIELD_MINUTES to minutes.toJsonNumberOrNull(),
                AgentCoreProtocol.FIELD_REASON to (reason?.let { JsonValue.Str(it) } ?: JsonValue.Null),
                AgentCoreProtocol.FIELD_NOW to JsonValue.Num(now.toString())
            )
        )
    )

    fun decodeDeferPlan(body: String): DeferPlan {
        val obj = parseObject(body, "defer_reply 计划响应")
        return DeferPlan(
            earliestAt = obj.long(AgentCoreProtocol.FIELD_EARLIEST_AT),
            latestAt = obj.long(AgentCoreProtocol.FIELD_LATEST_AT),
            minutes = obj.int(AgentCoreProtocol.FIELD_MINUTES),
            reason = obj.optionalString(AgentCoreProtocol.FIELD_REASON).orEmpty()
        )
    }

    fun encodeDeferPlan(plan: DeferPlan): String = Json.stringify(
        JsonValue.Obj(
            linkedMapOf(
                AgentCoreProtocol.FIELD_EARLIEST_AT to JsonValue.Num(plan.earliestAt.toString()),
                AgentCoreProtocol.FIELD_LATEST_AT to JsonValue.Num(plan.latestAt.toString()),
                AgentCoreProtocol.FIELD_MINUTES to JsonValue.Num(plan.minutes.toString()),
                AgentCoreProtocol.FIELD_REASON to JsonValue.Str(plan.reason)
            )
        )
    )

    // ------------------------------------------------------------------ 主动上限

    fun encodeProactiveCapRequest(input: ProactiveCapInput): String = Json.stringify(
        JsonValue.Obj(
            linkedMapOf(
                AgentCoreProtocol.FIELD_LEVEL to JsonValue.Num(input.level.toString()),
                AgentCoreProtocol.FIELD_MOOD_LABEL to (input.moodLabel?.let { JsonValue.Str(it) } ?: JsonValue.Null),
                AgentCoreProtocol.FIELD_MOOD_INTENSITY to input.moodIntensity.toJsonNumberOrNull()
            )
        )
    )

    fun decodeProactiveCap(body: String): Int {
        val obj = parseObject(body, "主动上限响应")
        return obj.int(AgentCoreProtocol.FIELD_DAILY_CAP)
    }

    fun encodeProactiveCap(dailyCap: Int): String = Json.stringify(
        JsonValue.Obj(
            linkedMapOf(AgentCoreProtocol.FIELD_DAILY_CAP to JsonValue.Num(dailyCap.toString()))
        )
    )

    // ------------------------------------------------------------------ 工具参数

    fun encodeToolArgsRequest(tool: String, args: Map<String, Any?>): String = Json.stringify(
        JsonValue.Obj(
            linkedMapOf(
                AgentCoreProtocol.FIELD_TOOL to JsonValue.Str(tool),
                AgentCoreProtocol.FIELD_ARGS to args.toJsonObject()
            )
        )
    )

    fun decodeToolArgs(body: String): Map<String, Any?> {
        val obj = parseObject(body, "工具参数响应")
        val args = obj.fields[AgentCoreProtocol.FIELD_ARGS]
            ?: throw AgentCoreProtocolException("工具参数响应缺少 args 字段")
        if (args is JsonValue.Null) return emptyMap()
        val argsObject = args as? JsonValue.Obj
            ?: throw AgentCoreProtocolException("工具参数响应的 args 必须是 JSON 对象")
        return LinkedHashMap<String, Any?>().apply {
            argsObject.fields.forEach { (key, value) -> put(key, toPlainValue(value)) }
        }
    }

    // ------------------------------------------------------------------ 错误体

    /**
     * 从统一错误体里取一句人话；不是错误体 / 坏 JSON / 超大响应返回 null（不抛异常）。
     *
     * 0.11.1 起对「读回来的东西」也设上限：错误体本身超过 [MAX_ERROR_BODY_CHARS] 直接不解析，
     * 取出的文案截断到 [MAX_ERROR_MESSAGE_CHARS]——服务端可控文本会进客户端日志（审计 P2-8），
     * 不能让一条远端错误把 logcat 刷爆。
     */
    fun decodeErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        if (body.length > MAX_ERROR_BODY_CHARS) return null
        val parsed = runCatching { Json.parse(body) }.getOrNull() as? JsonValue.Obj ?: return null
        val error = parsed.fields[AgentCoreProtocol.FIELD_ERROR] as? JsonValue.Obj ?: return null
        val message = (error.fields[AgentCoreProtocol.FIELD_ERROR_MESSAGE] as? JsonValue.Str)?.value
        val code = (error.fields[AgentCoreProtocol.FIELD_ERROR_CODE] as? JsonValue.Str)?.value
        val text = when {
            !message.isNullOrBlank() && !code.isNullOrBlank() -> "$message（$code）"
            !message.isNullOrBlank() -> message
            !code.isNullOrBlank() -> code
            else -> null
        } ?: return null
        return if (text.length > MAX_ERROR_MESSAGE_CHARS) {
            text.take(MAX_ERROR_MESSAGE_CHARS) + "…"
        } else {
            text
        }
    }

    // ------------------------------------------------------------------ 通用转换

    /** 任意「可 JSON 化」的值 → [JsonValue]（支持集合 / Map / 基本类型 / 已经是 JsonValue 的）。 */
    fun fromPlainValue(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Int -> JsonValue.Num(value.toString())
        is Long -> JsonValue.Num(value.toString())
        is Short -> JsonValue.Num(value.toString())
        is Byte -> JsonValue.Num(value.toString())
        is Double -> numberText(value)
        is Float -> numberText(value.toDouble())
        is Number -> JsonValue.Num(value.toString())
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(
            LinkedHashMap<String, JsonValue>().apply {
                value.forEach { (key, child) ->
                    if (key != null) put(key.toString(), fromPlainValue(child))
                }
            }
        )

        is Iterable<*> -> JsonValue.Arr(value.map { fromPlainValue(it) }.toMutableList())
        is Array<*> -> JsonValue.Arr(value.map { fromPlainValue(it) }.toMutableList())
        else -> JsonValue.Str(value.toString())
    }

    /** [JsonValue] → Kotlin 基本类型（Int / Long / Double / String / Boolean / List / Map / null）。 */
    fun toPlainValue(value: JsonValue): Any? = when (value) {
        is JsonValue.Obj -> LinkedHashMap<String, Any?>().apply {
            value.fields.forEach { (key, child) -> put(key, toPlainValue(child)) }
        }

        is JsonValue.Arr -> value.items.map { toPlainValue(it) }
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.asInt ?: value.asLong ?: value.asDouble
        JsonValue.Null -> null
    }

    // ------------------------------------------------------------------ 内部

    private fun parseObject(body: String, what: String): JsonValue.Obj {
        val parsed = try {
            Json.parse(body)
        } catch (invalid: JsonParseException) {
            throw AgentCoreProtocolException("$what 不是合法 JSON：${invalid.message}", invalid)
        }
        return parsed as? JsonValue.Obj
            ?: throw AgentCoreProtocolException("$what 必须是一个 JSON 对象")
    }

    private fun Int?.toJsonNumberOrNull(): JsonValue =
        this?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null

    private fun Map<String, Any?>.toJsonObject(): JsonValue.Obj {
        val out = LinkedHashMap<String, JsonValue>()
        forEach { (key, value) -> out[key] = fromPlainValue(value) }
        return JsonValue.Obj(out)
    }

    private fun JsonValue.Obj.field(key: String): JsonValue =
        fields[key] ?: throw AgentCoreProtocolException("响应缺少字段 $key")

    private fun JsonValue.Obj.bool(key: String): Boolean {
        val value = field(key)
        return (value as? JsonValue.Bool)?.value
            ?: throw AgentCoreProtocolException("字段 $key 必须是 true/false")
    }

    private fun JsonValue.Obj.optionalBool(key: String): Boolean? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Bool)?.value
            ?: throw AgentCoreProtocolException("字段 $key 必须是 true/false")
    }

    private fun JsonValue.Obj.int(key: String): Int {
        val value = field(key)
        return (value as? JsonValue.Num)?.asInt
            ?: throw AgentCoreProtocolException("字段 $key 必须是整数")
    }

    private fun JsonValue.Obj.optionalInt(key: String): Int? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Num)?.asInt
            ?: throw AgentCoreProtocolException("字段 $key 必须是整数")
    }

    private fun JsonValue.Obj.long(key: String): Long {
        val value = field(key)
        return (value as? JsonValue.Num)?.asLong
            ?: throw AgentCoreProtocolException("字段 $key 必须是整数")
    }

    private fun JsonValue.Obj.optionalString(key: String): String? {
        val value = fields[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Str)?.value
            ?: throw AgentCoreProtocolException("字段 $key 必须是字符串")
    }

    /** 整数值写成整数，避免 `5.0`；非有限值退化成 null（不往线路上发 NaN/Infinity）。 */
    private fun numberText(value: Double): JsonValue {
        if (!value.isFinite()) return JsonValue.Null
        return if (value == floor(value) && value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
            JsonValue.Num(value.toLong().toString())
        } else {
            JsonValue.Num(value.toString())
        }
    }
}
