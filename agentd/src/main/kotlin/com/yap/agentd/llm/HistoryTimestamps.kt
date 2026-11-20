package com.yap.agentd.llm

import com.yap.agentd.store.MessageRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 历史消息的**时间戳前缀**（0.15 上下文 profile 的 `history=timestamped`）。
 *
 * 评测里的写法：`[21:00] 用户：…` / `[21:01] 他：…`。为什么值得加：不给时间戳，模型
 * 分不清「刚才那句」和「昨晚那句」，跨天的话题会被当成同一段对话继续。
 *
 * 三条规则：
 *
 * 1. **只给聊天消息加**：`USER` / `ASSISTANT` 才加；工具结果、`SYSTEM`（导入数据）原样透传——
 *    它们不是「谁说的」，加了反而像多轮对话；
 * 2. **同一天只写时:分**：`[HH:mm]`；**跨天补日期**：`[MM-dd HH:mm]`（年份不写，聊天窗口内
 *    不会跨年）；
 * 3. **时区显式**：`createdAt` + 服务端时区（[ChatPromptBuilder] 注入的 `zone`）算本地时间；
 *    时间缺失（`createdAt <= 0`，导入的老数据）**整条不加**，不印一个 1970 年的假时间。
 *
 * 纯函数、不读时钟：`now` 由调用方传入，这样「跨天」的边界在测试里可以精确构造。
 */
object HistoryTimestamps {

    /** 用户消息的说话人标签（提示词里的称呼是「对方」，历史里沿用评测的「用户」）。 */
    const val USER_LABEL: String = "用户"

    /** 他自己（assistant）的标签。 */
    const val ASSISTANT_LABEL: String = "他"

    /** 同一天：`[HH:mm]`。 */
    val SAME_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** 跨天：`[MM-dd HH:mm]`。 */
    val CROSS_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

    /**
     * 一条历史的前缀；不该加时返回 `null`（调用方原样发内容）。
     *
     * @param now 本轮的时间（毫秒）；`<= 0` 时无法判断「今天」，一律用 [CROSS_DAY_FORMAT]
     */
    fun prefix(role: String, createdAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val label = when (role.uppercase(Locale.ROOT)) {
            MessageRole.USER -> USER_LABEL
            MessageRole.ASSISTANT -> ASSISTANT_LABEL
            else -> return null
        }
        if (createdAt <= 0L) return null
        val at = runCatching { Instant.ofEpochMilli(createdAt).atZone(zone) }.getOrNull() ?: return null
        val today = runCatching { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }.getOrNull()
        val format = if (today != null && today == at.toLocalDate()) SAME_DAY_FORMAT else CROSS_DAY_FORMAT
        return "[" + at.format(format) + "] " + label + "："
    }

    /** 前缀 + 原文；不需要前缀（工具/系统/时间缺失）时原样返回 [content]。 */
    fun render(
        role: String,
        content: String,
        createdAt: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val text = content.trim()
        val prefix = prefix(role, createdAt, now, zone) ?: return text
        return prefix + text
    }
}
