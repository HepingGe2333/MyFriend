package com.myfriend.agentd.proactive

import com.myfriend.app.core.json.Json
import com.myfriend.app.core.json.JsonParseException
import com.myfriend.app.core.json.JsonValue
import java.time.Instant
import java.util.Locale

/**
 * 「占用时间」在服务端的读法（`spec/persona-guidance/README.md` §4，客户端同名字段
 * `personas.activityJson` / `PersonaActivity`）。
 *
 * 为什么要单独一份：`:agentd` 不能依赖 `:app`（Android），而 `:agentcore` 里目前没有这个模型。
 * 结构刻意与客户端**逐字段同构**（`kind` / `activity` / `until` / `availability`），
 * 这样客户端上报什么、服务端就按什么判定，不需要中间映射层：
 *
 * | 字段 | 服务端怎么用 |
 * | --- | --- |
 * | `kind` | 只校验/归一化，不参与判定（界面也不展示原值） |
 * | `activity` | 为空视为「没在忙」 |
 * | `until` | 过期判断的唯一依据（毫秒；ISO-8601 也能解析） |
 * | `availability` | `BUSY` → 不主动开口；`LIMITED` → 概率 ×[LIMITED_PROACTIVE_FACTOR] |
 *
 * 两条与客户端保持一致的语义：
 * - **过期即 FREE**：`until <= now` 或 `activity` 为空 → `availabilityAt(now)` 返回 `FREE`，
 *   不需要任何定时任务清理（`README.md` §4「到 until 自动失效（读取时判断）」）；
 * - **坏 JSON 一律当没有活动**：一个坏字段不该把主动消息永久掐死（宁可不打扰，也不能装死）。
 */
data class ActivityState(
    /** `WORK|COMMUTE|MEAL|SOCIAL|CHORE|STUDY|REST|OTHER`（归一化后一定是其中之一）。 */
    val kind: String = KIND_OTHER,
    /** 用他自己的口吻写的一句话，例如「在事务所赶第三版方案」。 */
    val activity: String = "",
    /** 结束时刻（epoch millis）；`<= now` 视为已经结束。 */
    val until: Long = 0L,
    /** 忙不忙；过期以后不像 `FREE` 那样改写这里，判定走 [availabilityAt]。 */
    val availability: ActivityAvailability = ActivityAvailability.BUSY
) {

    /** 有没有一条**还没结束**的活动。 */
    fun isActive(now: Long): Boolean = activity.isNotBlank() && until > now

    /** 此刻真正的可用程度：过期（或没设活动）就是 [ActivityAvailability.FREE]。 */
    fun availabilityAt(now: Long): ActivityAvailability =
        if (isActive(now)) availability else ActivityAvailability.FREE

    /** 落盘/下发的 JSON 形状（与客户端 `PersonaActivityCodec.encode` 同构）。 */
    fun toJson(): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "kind" to JsonValue.Str(kind),
            "activity" to JsonValue.Str(activity),
            "until" to JsonValue.Num(until.toString()),
            "availability" to JsonValue.Str(availability.name)
        )
    )

    companion object {
        /**
         * `LIMITED` 时主动消息的降权系数（`README.md` §4「LIMITED：可以回，但明显更少」）。
         *
         * 与客户端 `PersonaActivity.LIMITED_PROACTIVE_FACTOR` 是同一个数：两边必须一致，
         * 否则「服务端权威模式」和「客户端本地心跳」的主动频率会不一样。
         */
        const val LIMITED_PROACTIVE_FACTOR: Double = 0.35

        /** `minutes` 的下限：太短的忙不值得打断节奏（规范 §4）。 */
        const val MIN_MINUTES: Int = 15

        /** `minutes` 的上限：8 小时（规范 §4）。 */
        const val MAX_MINUTES: Int = 480

        /** 路由既没给 `minutes` 也没给 `until` 时的默认时长（一句话的活动默认一小时）。 */
        const val DEFAULT_MINUTES: Int = 60

        /** `activity` 的字符上限（与客户端 `PersonaActivityCodec` 一致）。 */
        const val MAX_ACTIVITY_CHARS: Int = 160

        const val KIND_OTHER = "OTHER"

        /** `set_activity` 的 kind 枚举（`spec/agent-core/cases/tool-args.json`）。 */
        val KINDS: List<String> = listOf(
            "WORK", "COMMUTE", "MEAL", "SOCIAL", "CHORE", "STUDY", "REST", KIND_OTHER
        )

        /** 大小写无关；认不出的 kind 归一成 [KIND_OTHER]（与客户端 `ActivityKind.parse` 一致）。 */
        fun parseKind(raw: String?): String {
            val cleaned = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
            return KINDS.firstOrNull { it == cleaned } ?: KIND_OTHER
        }

        /** `minutes` 夹到 [MIN_MINUTES]–[MAX_MINUTES]（路由层用；客户端已经夹过一遍也照样夹）。 */
        fun clampMinutes(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

        /** `minutes` → 活动对象（`until = now + minutes * 60_000`）。 */
        fun fromMinutes(
            kind: String?,
            activity: String,
            minutes: Int,
            availability: ActivityAvailability,
            now: Long
        ): ActivityState = ActivityState(
            kind = parseKind(kind),
            activity = activity.trim().take(MAX_ACTIVITY_CHARS),
            until = now + clampMinutes(minutes) * 60_000L,
            availability = availability
        )

        /**
         * 宽容解析 `personas.activityJson`。
         *
         * 返回 `null` 的情况：空串、坏 JSON、不是对象、`activity` 为空。
         * **过期的活动仍然返回对象**（由 [availabilityAt] 判断），这样 `GET /v1/state`
         * 还能把「他到 21:30 之前都在忙」这种刚过期的信息显示出来，客户端决定怎么展示。
         */
        fun decode(raw: String?): ActivityState? {
            if (raw.isNullOrBlank()) return null
            val root = try {
                Json.parse(raw) as? JsonValue.Obj
            } catch (_: JsonParseException) {
                null
            } ?: return null
            val activity = root.str("activity").take(MAX_ACTIVITY_CHARS)
            if (activity.isEmpty()) return null
            return ActivityState(
                kind = parseKind(root.str("kind")),
                activity = activity,
                until = root.long("until") ?: 0L,
                availability = ActivityAvailability.parse(root.str("availability"))
            )
        }

        /** [ActivityState] → 落盘用的 JSON 文本。 */
        fun encode(activity: ActivityState): String = Json.stringify(activity.toJson())

        private fun JsonValue.Obj.str(key: String): String =
            ((fields[key] as? JsonValue.Str)?.value ?: "").trim()

        /** `until` 支持数字（毫秒）与 ISO-8601 字符串（客户端两种都可能写）。 */
        private fun JsonValue.Obj.long(key: String): Long? = when (val element = fields[key]) {
            is JsonValue.Num -> element.asLong
            is JsonValue.Str -> {
                val text = element.value.trim()
                text.toLongOrNull() ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            }

            else -> null
        }
    }
}

/** 忙不忙：与客户端 `ActivityAvailability` 同一个枚举、同一套宽容解析。 */
enum class ActivityAvailability {
    /** 在忙：主动消息不该发（除非用户先说话）。 */
    BUSY,

    /** 有点忙：可以回，但明显更少。 */
    LIMITED,

    /** 不忙（或活动已经过期）。 */
    FREE;

    companion object {
        /** 大小写无关；认不出的值按 [BUSY]（保守：宁可说在忙，也不谎称有空）。 */
        fun parse(raw: String?): ActivityAvailability {
            val cleaned = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { it.name == cleaned } ?: BUSY
        }
    }
}
