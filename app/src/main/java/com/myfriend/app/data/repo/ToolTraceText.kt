package com.myfriend.app.data.repo

import android.content.Context
import androidx.annotation.StringRes
import com.myfriend.app.R
import com.myfriend.app.domain.MoodState
import com.myfriend.app.domain.RecallDepth
import com.myfriend.app.domain.RecallHit
import com.myfriend.app.domain.RecallKind
import com.myfriend.app.domain.ToolCatalog
import com.myfriend.app.util.MoodText

/**
 * 工具痕迹的**显示**文案：聊天气泡下面那一行小字、展开后的正文。
 *
 * 为什么单独一个文件：同一次工具调用会产生两份文字，它们的语言规则完全不同——
 *
 * 1. **回灌给模型的文本**（`ToolResult.contentForModel`、`ToolProtocol.renderResultsForModel`
 *    里的「【工具结果】/ 摘要：/ 失败：」）是提示词脚手架，按 `docs/I18N.md` 保持中文，
 *    留在 `ToolProtocol` / `ToolExecutor` 里，**不要**搬到这里；
 * 2. **给用户看的标题 / 副标题 / 展开正文**才是这个文件的职责，一律走 `R.string.tool_trace_*`，
 *    按当前界面语言取。
 *
 * 痕迹会落库（`ToolTraceItem.title/subtitle/detail`），所以文案在**写入时**按当时的语言取；
 * 老库里的中文原样显示，不做迁移（与 `@mf-err:` 令牌的取舍一致）。
 *
 * 这个文件不在 `ChineseLiteralGuardTest` 的白名单里：以后谁往这里塞一句裸中文，守卫会红。
 */
enum class ToolTraceTitle(@StringRes val res: Int) {
    THINK(R.string.tool_trace_think),
    WEB_SEARCH(R.string.tool_trace_web_search),
    WEB_FETCH(R.string.tool_trace_web_fetch),
    REMEMBER(R.string.tool_trace_remember),
    REMEMBER_PENDING(R.string.tool_trace_remember_pending),
    REMEMBER_CONFIRMED(R.string.tool_trace_remember_confirmed),
    REMEMBER_REINFORCED(R.string.tool_trace_remember_reinforced),
    REMEMBER_SKIPPED(R.string.tool_trace_remember_skipped),
    REMEMBER_CONSIDERING(R.string.tool_trace_remember_considering),
    UPDATE_PERSONA(R.string.tool_trace_update_persona),
    PERSONA_UNCHANGED(R.string.tool_trace_persona_unchanged),
    SET_QUIET_HOURS(R.string.tool_trace_set_quiet_hours),
    SET_MOOD(R.string.tool_trace_set_mood),
    RECALL(R.string.tool_trace_recall),
    DEFER_REPLY(R.string.tool_trace_defer_reply),
    UNKNOWN(R.string.tool_trace_unknown);

    companion object {
        /** 工具名 → 默认标题。认不出的工具名用「动作」，与 iOS 的 `ToolProtocol.describe` 一致。 */
        fun forTool(name: String): ToolTraceTitle = when (name.trim()) {
            ToolCatalog.THINK -> THINK
            ToolCatalog.WEB_SEARCH -> WEB_SEARCH
            ToolCatalog.WEB_FETCH -> WEB_FETCH
            ToolCatalog.REMEMBER -> REMEMBER
            ToolCatalog.UPDATE_PERSONA -> UPDATE_PERSONA
            ToolCatalog.SET_QUIET_HOURS -> SET_QUIET_HOURS
            ToolCatalog.SET_MOOD -> SET_MOOD
            ToolCatalog.RECALL -> RECALL
            ToolCatalog.DEFER_REPLY -> DEFER_REPLY
            else -> UNKNOWN
        }
    }
}

object ToolTraceText {

    // ------------------------------------------------------------ 标题

    fun title(context: Context, tool: String): String =
        context.getString(ToolTraceTitle.forTool(tool).res)

    fun title(context: Context, title: ToolTraceTitle): String = context.getString(title.res)

    // ------------------------------------------------------------ 副标题

    /** recall 的副标题：翻到东西说条数，没翻到说「没想起什么」。 */
    fun recallSubtitle(context: Context, hits: Int?): String = if (hits == null) {
        context.getString(R.string.tool_trace_recall_empty)
    } else {
        context.resources.getQuantityString(R.plurals.tool_trace_recall_hits, hits, hits)
    }

    /** 作息副标题：「08:00 睡 · 07:00 起」。 */
    fun quietHoursSubtitle(context: Context, sleepMinutes: Int, wakeMinutes: Int): String =
        context.getString(
            R.string.tool_trace_quiet_hours,
            formatClock(sleepMinutes),
            formatClock(wakeMinutes)
        )

    /**
     * 心情副标题/正文：「雀跃（4/5）· 因为刚聊到想去的地方」。
     *
     * 心情词本身是落库数据（见 `MoodLexicon`），显示时按 [MoodText] 映射；
     * 模型自造的怪词原样显示。
     */
    fun mood(context: Context, state: MoodState): String {
        // 心情词表里的英文是小写（心情胶囊里跟在图标后面），放在痕迹这一行的开头时首字母大写。
        val label = MoodText.label(context, state.label).replaceFirstChar { it.uppercase() }
        val intensity = state.intensity.coerceIn(1, 5)
        return if (state.reason.isBlank()) {
            context.getString(R.string.tool_trace_mood, label, intensity)
        } else {
            context.getString(R.string.tool_trace_mood_reason, label, intensity, state.reason)
        }
    }

    // ------------------------------------------------------------ 展开正文

    fun thinkDetail(context: Context, question: String): String =
        question.ifBlank { context.getString(R.string.tool_trace_think_detail_placeholder) }

    fun searchNoResult(context: Context): String =
        context.getString(R.string.tool_trace_search_no_result)

    fun rememberSkippedDetail(context: Context, reason: String): String =
        context.getString(R.string.tool_trace_remember_skipped_detail, reason)

    fun rememberEchoDetail(context: Context): String =
        context.getString(R.string.tool_trace_remember_echo_detail)

    fun rememberReinforcedDetail(context: Context): String =
        context.getString(R.string.tool_trace_remember_reinforced_detail)

    fun recallEmptyDetail(context: Context, depth: RecallDepth): String =
        context.getString(R.string.tool_trace_recall_empty_detail, depthLabel(context, depth))

    /** 翻到的每一条：`（记忆 · 3 周前 · 关于对方）正文`。来源与日期都是显示版。 */
    fun recallHitsDetail(context: Context, hits: List<RecallHit>, now: Long): String =
        hits.joinToString("\n") { hit ->
            context.getString(
                R.string.tool_trace_recall_hit_line,
                sourceLabel(context, hit, now),
                hit.text.take(80)
            )
        }

    fun personaUnchangedDetail(context: Context): String =
        context.getString(R.string.tool_trace_persona_unchanged_detail)

    fun personaReason(context: Context, reason: String): String =
        context.getString(R.string.tool_trace_persona_reason, reason)

    fun personaApplied(context: Context): String =
        context.getString(R.string.tool_trace_persona_applied)

    /** 作息正文：原因（可选）+「这段时间不会主动打扰」+ 免打扰说明（可选）。 */
    fun quietHoursDetail(context: Context, reason: String, dnd: Boolean?): String = buildString {
        if (reason.isNotBlank()) append(reason).append('\n')
        append(context.getString(R.string.tool_trace_quiet_hours_rest))
        when (dnd) {
            true -> append('\n').append(context.getString(R.string.tool_trace_quiet_hours_dnd_on))
            false -> append('\n').append(context.getString(R.string.tool_trace_quiet_hours_dnd_off))
            null -> Unit
        }
    }

    fun deferDetail(context: Context, minutes: Int, reason: String): String = if (reason.isBlank()) {
        context.resources.getQuantityString(R.plurals.tool_trace_defer_detail, minutes, minutes)
    } else {
        context.resources.getQuantityString(
            R.plurals.tool_trace_defer_detail_reason,
            minutes,
            minutes,
            reason
        )
    }

    // ------------------------------------------------------------ 回忆的显示版标签

    /** 命中来源：「记忆 / 聊天 / 他自己看到的」。 */
    fun kindLabel(context: Context, kind: RecallKind): String = context.getString(
        when (kind) {
            RecallKind.MEMORY -> R.string.tool_trace_source_memory
            RecallKind.CHAT -> R.string.tool_trace_source_chat
            RecallKind.BROWSING -> R.string.tool_trace_source_browsing
        }
    )

    fun depthLabel(context: Context, depth: RecallDepth): String = context.getString(
        when (depth) {
            RecallDepth.QUICK -> R.string.tool_trace_recall_depth_quick
            RecallDepth.NORMAL -> R.string.tool_trace_recall_depth_normal
            RecallDepth.DEEP -> R.string.tool_trace_recall_depth_deep
        }
    )

    /**
     * 显示版相对日期（提示词里的 `Recall.dateLabel` 保持中文）。
     *
     * 与 `Recall.dateLabel` 的分档逐条对齐，只是每一档换成资源：
     * 刚刚 / N 分钟前 / 今天 / 昨天 / 前天 / N 天前 / N 周前 / N 个月前 / N 年前。
     */
    fun dateLabel(context: Context, at: Long, now: Long): String {
        if (at <= 0L) return context.getString(R.string.presence_ago_long_ago)
        val delta = (now - at).coerceAtLeast(0L)
        val minutes = delta / 60_000L
        if (minutes < 1L) return context.getString(R.string.presence_ago_just_now)
        if (minutes < 60L) {
            return context.resources.getQuantityString(R.plurals.presence_ago_minutes, minutes.toInt(), minutes.toInt())
        }
        val days = delta / DAY_MILLIS
        return when {
            days <= 0L -> context.getString(R.string.common_today)
            days == 1L -> context.getString(R.string.presence_ago_yesterday)
            days == 2L -> context.getString(R.string.tool_trace_when_day_before)
            days < 7L -> context.resources.getQuantityString(R.plurals.presence_ago_days, days.toInt(), days.toInt())
            days < 30L -> context.resources.getQuantityString(R.plurals.presence_ago_weeks, (days / 7).toInt(), (days / 7).toInt())
            days < 365L -> context.resources.getQuantityString(R.plurals.presence_ago_months, (days / 30).toInt(), (days / 30).toInt())
            else -> context.resources.getQuantityString(R.plurals.tool_trace_when_years, (days / 365).toInt(), (days / 365).toInt())
        }
    }

    /** 显示版来源行：「记忆 · 3 周前 · 关于对方」；来源与备注是数据，原样保留。 */
    fun sourceLabel(context: Context, hit: RecallHit, now: Long): String {
        val parts = mutableListOf(kindLabel(context, hit.kind), dateLabel(context, hit.at, now))
        hit.source.trim().takeIf { it.isNotBlank() }?.let { parts += it.take(30) }
        hit.note.trim().takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    /** `08:00`；只做格式化，不读时钟。 */
    fun formatClock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}
