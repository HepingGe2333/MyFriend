package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** `set_activity` 的 kind 枚举（`spec/agent-core/cases/tool-args.json` 的 activity 契约）。 */
enum class ActivityKind {
    WORK, COMMUTE, MEAL, SOCIAL, CHORE, STUDY, REST, OTHER;

    companion object {
        fun parse(raw: String?): ActivityKind {
            val cleaned = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { it.name == cleaned } ?: OTHER
        }
    }
}

/** 忙不忙：BUSY 时不主动开口，LIMITED 时降低主动概率，FREE 时不影响。 */
enum class ActivityAvailability {
    BUSY, LIMITED, FREE;

    companion object {
        fun parse(raw: String?): ActivityAvailability {
            val cleaned = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { it.name == cleaned } ?: BUSY
        }
    }
}

/**
 * 「他此刻在做什么」（`spec/persona-guidance/README.md` §4）。
 *
 * 与作息（`set_quiet_hours`）的区别写在这里：**安静时段是「不被打扰」，活动是「我在做事」**，
 * 两者可以同时存在。活动只活到 [until] 为止，读取时判断过期，不需要定时任务清理。
 *
 * 纯 JVM：时间由参数传进来，单测不依赖真实时钟。
 */
data class PersonaActivity(
    val kind: ActivityKind = ActivityKind.OTHER,
    /** 用他自己的口吻写的一句话，例如「在事务所赶第三版方案」。 */
    val activity: String = "",
    /** 结束时刻（epoch millis）；<= now 视为已经结束。 */
    val until: Long = 0L,
    val availability: ActivityAvailability = ActivityAvailability.BUSY
) {
    fun isActive(now: Long): Boolean = activity.isNotBlank() && until > now

    /** 过期就回到 FREE：一个已经结束的忙碌不该继续影响主动消息。 */
    fun availabilityAt(now: Long): ActivityAvailability =
        if (isActive(now)) availability else ActivityAvailability.FREE

    /**
     * 提示词里的「你此刻的状态」一部分。
     *
     * 过期（或根本没设）时返回「没在忙什么」——不要留一个空句子让模型自己猜。
     */
    fun renderForPrompt(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (!isActive(now)) return "没在忙什么。"
        val tail = when (availability) {
            ActivityAvailability.BUSY -> "，这段时间不太有空，回复会更短更晚，要说清楚几点以后再说"
            ActivityAvailability.LIMITED -> "，这段时间在忙，回得慢"
            ActivityAvailability.FREE -> "，手头是这件事，但随时能聊"
        }
        val doing = activity.trim().take(120)
        val sentence = if (doing.startsWith("在") || doing.startsWith("去")) "你$doing" else "你在$doing"
        return "$sentence，预计 ${formatUntil(zone)} 结束$tail。"
    }

    fun formatUntil(zone: ZoneId = ZoneId.systemDefault()): String = runCatching {
        Instant.ofEpochMilli(until).atZone(zone).format(HH_MM)
    }.getOrDefault("")

    companion object {
        /** `LIMITED` 时主动消息的降权系数（BUSY 直接跳过，见 HeartbeatController）。 */
        const val LIMITED_PROACTIVE_FACTOR = 0.35

        private val HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    }
}

/**
 * `personas.activityJson` 的编解码。
 *
 * 宽容解析：坏 JSON → `null`；`until` 支持数字（毫秒）与 ISO-8601 字符串；
 * 过期的活动读出来仍然返回对象（由 [PersonaActivity.isActive] 判断），
 * 这样界面可以显示「他到 21:30 之前都在忙」这种刚过期的信息，而不是瞬间消失。
 */
object PersonaActivityCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun decode(raw: String?): PersonaActivity? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val activity = root.str("activity").take(160)
        if (activity.isBlank()) return null
        return PersonaActivity(
            kind = ActivityKind.parse(root.str("kind")),
            activity = activity,
            until = root.long("until") ?: 0L,
            availability = ActivityAvailability.parse(root.str("availability"))
        )
    }

    fun decodeOrNull(raw: String?): PersonaActivity? = decode(raw)

    fun encode(activity: PersonaActivity): String = runCatching {
        JsonObject(
            mapOf(
                "kind" to JsonPrimitive(activity.kind.name),
                "activity" to JsonPrimitive(activity.activity.trim().take(160)),
                "until" to JsonPrimitive(activity.until),
                "availability" to JsonPrimitive(activity.availability.name)
            )
        ).toString()
    }.getOrDefault("{}")

    /** 模型给的活动参数 → 落库形状；minutes 已经过 `ToolArgClamp` 夹取。 */
    fun fromTool(
        kind: String?,
        activity: String,
        minutes: Int,
        availability: String?,
        now: Long
    ): PersonaActivity = PersonaActivity(
        kind = ActivityKind.parse(kind),
        activity = activity.trim().take(160),
        until = now + minutes.coerceAtLeast(0) * 60_000L,
        availability = ActivityAvailability.parse(availability)
    )

    private fun JsonObject.str(key: String): String =
        ((this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: "").trim()

    private fun JsonObject.long(key: String): Long? {
        val element = this[key] as? JsonPrimitive ?: return null
        element.longOrNull?.let { return it }
        val text = element.contentOrNull?.trim() ?: return null
        text.toLongOrNull()?.let { return it }
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
    }
}
