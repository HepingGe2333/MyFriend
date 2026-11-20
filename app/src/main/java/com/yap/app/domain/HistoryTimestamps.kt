package com.yap.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 历史消息的时间戳（`spec/context-profile/README.md`：历史格式 = **timestamped**）。
 *
 * Bench v2 的坐标下降里 `history=timestamped` 只输给 `memory=full`（88.5 vs 87.5 `raw`），
 * 因为它让模型知道「这句话是几点说的」：深夜的消息与下午的消息，该用的语气不一样。
 *
 * 格式固定（两端一致）：
 *
 * ```
 * [19:40] 用户：今天还是加班吗
 * [19:41] 他：还得一会儿
 * ```
 *
 * 跨天的消息补上日期，避免「昨晚 23:50」和「今天 00:10」看起来是同一段对话：
 *
 * ```
 * [11-18 23:50] 用户：我先睡了
 * ```
 *
 * 几条硬约束：
 * - 只给**用户/人格的聊天原文**加时间戳；工具结果、系统消息、system 段都不加（它们不是「他说过的话」）；
 * - 时间取消息自己的 `createdAt`，时区取**人格/设备时区**（与状态段的时钟同一个 `ZoneId`），
 *   这样「历史里的 19:40」和「现在是 19:40」永远是同一把尺子；
 * - 纯函数：时间与时区都由调用方传进来，测试可以钉死跨天与时区切换。
 */
object HistoryTimestamps {

    /** 用户消息的说话人标签。 */
    const val USER_LABEL = "用户"

    /** 人格自己消息的说话人标签（与「他」的第一人称提示词一致）。 */
    const val PERSONA_LABEL = "他"

    /** 标签与冒号之间的固定写法：全角冒号，后面直接接正文。 */
    const val SEPARATOR = "："

    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** 跨天用 `MM-dd HH:mm`：月份在前、没有年份——历史窗口最多几天，年份只会浪费 token。 */
    private val MM_DD_HH_MM = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

    /** 说话人标签：用户消息是「用户」，其余（人格自己说的）是「他」。 */
    fun label(outgoing: Boolean): String = if (outgoing) USER_LABEL else PERSONA_LABEL

    /**
     * 一条消息的前缀：`[HH:mm] 用户：`，跨天时是 `[MM-dd HH:mm] 他：`。
     *
     * @param createdAt 消息自己的时间（毫秒）
     * @param now 这一轮的时间（毫秒）；用来判断「是不是跨天」，不参与时刻的计算
     * @param zone 人格/设备时区
     * @param outgoing 是不是用户发的（true → 「用户」，false → 「他」）
     */
    fun prefix(createdAt: Long, now: Long, zone: ZoneId, outgoing: Boolean): String {
        if (createdAt <= 0L) return ""
        val at = runCatching { Instant.ofEpochMilli(createdAt).atZone(zone) }.getOrNull() ?: return ""
        // now <= 0 时判不出「今天」，一律按跨天写（宁可多一个日期，也不要假装是同一天）。
        val today = runCatching { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }.getOrNull()
        val clock = if (today != null && today == at.toLocalDate()) {
            at.format(HH_MM)
        } else {
            at.format(MM_DD_HH_MM)
        }
        return "[$clock] " + label(outgoing) + SEPARATOR
    }

    /**
     * 给一条历史消息正文加时间戳：`[19:40] 用户：今天还是加班吗`。
     *
     * 时间拿不到（`createdAt <= 0`，例如老数据/正在入库的消息）时**原样返回**：
     * 宁可少一个时间戳，也不要写出「[1970-01-01] 用户：」这种假时间。
     *
     * 与服务端 `agentd` 的 `HistoryTimestamps.render` 同一套语义：前缀直接接正文（中间没有空格）。
     */
    fun decorate(
        content: String,
        createdAt: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        outgoing: Boolean
    ): String {
        val text = content.trim()
        val head = prefix(createdAt, now, zone, outgoing)
        if (head.isEmpty() || text.isEmpty()) return content
        return head + text
    }

    /** 给整个历史列表按同一条「今天」基准加时间戳（保持顺序不变）。 */
    fun <T> decorateAll(
        history: List<T>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        createdAtOf: (T) -> Long,
        outgoingOf: (T) -> Boolean,
        contentOf: (T) -> String,
        copy: (T, String) -> T
    ): List<T> = history.map { item ->
        copy(
            item,
            decorate(
                content = contentOf(item),
                createdAt = createdAtOf(item),
                now = now,
                zone = zone,
                outgoing = outgoingOf(item)
            )
        )
    }

    /** 只给测试用：把毫秒换算成这一天的 `LocalDate`（跨天判定的口径）。 */
    internal fun dayOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
