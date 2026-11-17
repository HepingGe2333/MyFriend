package com.yap.app.data.repo

import android.content.Context
import androidx.annotation.StringRes
import com.yap.app.R
import com.yap.app.domain.ActivityAvailability
import com.yap.app.domain.MoodState
import com.yap.app.domain.PersonaActivity
import com.yap.app.domain.RecallDepth
import com.yap.app.domain.RecallHit
import com.yap.app.domain.RecallKind
import com.yap.app.domain.ToolCatalog
import com.yap.app.util.MoodText

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
    SET_ACTIVITY(R.string.tool_trace_set_activity),
    SET_MOOD(R.string.tool_trace_set_mood),
    RECALL(R.string.tool_trace_recall),
    DEFER_REPLY(R.string.tool_trace_defer_reply),
    STAY_SILENT(R.string.tool_trace_stay_silent),
    DIARY(R.string.tool_trace_diary),
    DIARY_ADD(R.string.tool_trace_diary_add),
    DIARY_LIST(R.string.tool_trace_diary_list),
    DIARY_REMOVE(R.string.tool_trace_diary_remove),
    STICKER(R.string.tool_trace_sticker),
    STICKER_SEND(R.string.tool_trace_sticker_send),
    STICKER_MAKE(R.string.tool_trace_sticker_make),
    STICKER_SAVE(R.string.tool_trace_sticker_save),
    LISTEN(R.string.tool_trace_listen),
    WATCH(R.string.tool_trace_watch),
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
            ToolCatalog.SET_ACTIVITY -> SET_ACTIVITY
            ToolCatalog.SET_MOOD -> SET_MOOD
            ToolCatalog.RECALL -> RECALL
            ToolCatalog.DEFER_REPLY -> DEFER_REPLY
            ToolCatalog.STAY_SILENT -> STAY_SILENT
            ToolCatalog.DIARY -> DIARY
            ToolCatalog.STICKER -> STICKER
            ToolCatalog.LISTEN -> LISTEN
            ToolCatalog.WATCH -> WATCH
            else -> UNKNOWN
        }
    }
}

object ToolTraceText {

    // ------------------------------------------------------------ 表情包（spec/stickers）

    /** 表情动作对应的标题：`send` / `make` / `save` 各自一句，认不出动作就用「表情」这一个词。 */
    fun stickerTitle(context: Context, action: String?): String = context.getString(
        when (action?.trim()?.lowercase()) {
            "send" -> R.string.tool_trace_sticker_send
            "make" -> R.string.tool_trace_sticker_make
            "save" -> R.string.tool_trace_sticker_save
            else -> R.string.tool_trace_sticker
        }
    )

    /**
     * 表情副标题：「320×240 · 12 帧 · 46 KB」/「320×240 · 静态 · 8 KB」。
     *
     * 两条文案的参数个数不同（静态那条不该出现帧数），所以这里分开调用——
     * 用一条 `%4$d` 跳号的字符串会被 lint 判 `StringFormatCount`，运行时也容易传错位。
     */
    fun stickerSubtitle(context: Context, width: Int, height: Int, frames: Int, bytes: Int): String =
        if (frames > 1) {
            context.getString(R.string.tool_trace_sticker_animated, width, height, frames, bytes / 1024)
        } else {
            context.getString(R.string.tool_trace_sticker_static, width, height, bytes / 1024)
        }

    /** 展开正文：一句话说明这张表情现在在哪（发出去了 / 收进收藏了 / 已经做好了）。 */
    fun stickerDetail(context: Context, action: String?): String = context.getString(
        when (action?.trim()?.lowercase()) {
            "send" -> R.string.tool_trace_sticker_sent_detail
            "save" -> R.string.tool_trace_sticker_saved_detail
            "make" -> R.string.tool_trace_sticker_made_detail
            else -> R.string.tool_trace_sticker_detail
        }
    )

    /** 失败原因：枚举 → 资源（界面按当前语言显示，老数据原样保留）。 */
    fun stickerFailure(context: Context, reason: com.yap.app.domain.StickerToolFailure): String =
        context.getString(
            when (reason) {
                com.yap.app.domain.StickerToolFailure.MISSING_ACTION -> R.string.tool_trace_sticker_failed_missing_action
                com.yap.app.domain.StickerToolFailure.UNKNOWN_ACTION -> R.string.tool_trace_sticker_failed_unknown_action
                com.yap.app.domain.StickerToolFailure.STICKER_NOT_FOUND -> R.string.tool_trace_sticker_failed_not_found
                com.yap.app.domain.StickerToolFailure.MESSAGE_NOT_FOUND -> R.string.tool_trace_sticker_failed_message_not_found
                com.yap.app.domain.StickerToolFailure.NO_STICKER_IN_MESSAGE -> R.string.tool_trace_sticker_failed_no_sticker
                com.yap.app.domain.StickerToolFailure.EMPTY_MAKE -> R.string.tool_trace_sticker_failed_empty_make
                com.yap.app.domain.StickerToolFailure.BAD_URL -> R.string.tool_trace_sticker_failed_bad_url
                com.yap.app.domain.StickerToolFailure.DOWNLOAD_FAILED -> R.string.tool_trace_sticker_failed_download
                com.yap.app.domain.StickerToolFailure.IMAGE_UNREADABLE -> R.string.tool_trace_sticker_failed_unreadable
                com.yap.app.domain.StickerToolFailure.CANNOT_COMPRESS -> R.string.tool_trace_sticker_failed_too_big
                com.yap.app.domain.StickerToolFailure.SAVE_FAILED -> R.string.tool_trace_sticker_failed_save
            }
        )

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

    // ------------------------------------------------------------ 日记（spec/diary）

    /** `add` 的副标题：「周五问面试 · 到 11-21 周五 09:00 提醒」（不提醒就只有内容）。 */
    fun diaryAddSubtitle(context: Context, content: String, remindText: String?): String {
        val text = content.replace('\n', ' ').take(40)
        return if (remindText.isNullOrBlank()) {
            text
        } else {
            context.getString(R.string.tool_trace_diary_remind, text, remindText)
        }
    }

    /** `list` 的副标题：「3 条备忘」。 */
    fun diaryListSubtitle(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.tool_trace_diary_count, count, count)

    /** `list` 的展开正文：一条一行；空本子给一句人话，而不是一片空白。 */
    fun diaryListDetail(context: Context, lines: List<String>): String =
        if (lines.isEmpty()) {
            context.getString(R.string.tool_trace_diary_empty_detail)
        } else {
            lines.joinToString("\n")
        }

    /** `remove` 的展开正文：真删掉了。 */
    fun diaryRemovedDetail(context: Context): String =
        context.getString(R.string.tool_trace_diary_removed_detail)

    /** `remove` 找不到那条：不是错误（可能已经撕掉了），但要让用户看明白。 */
    fun diaryNotFoundDetail(context: Context): String =
        context.getString(R.string.tool_trace_diary_not_found_detail)

    /**
     * 活动副标题：「在事务所赶图 · 到 21:30」。
     *
     * 显示的是他的一句话 + 结束时间，不显示 kind 原值（规范 §4）。
     */
    fun activitySubtitle(context: Context, activity: PersonaActivity): String =
        context.getString(
            R.string.tool_trace_activity_subtitle,
            activity.activity.take(40),
            activity.formatUntil()
        )

    /** 活动展开正文：多久、忙到什么程度；安静时段是另一件事，这里只解释活动。 */
    fun activityDetail(context: Context, activity: PersonaActivity, minutes: Int): String {
        val base = context.getString(R.string.tool_trace_activity_detail, minutes, activity.formatUntil())
        val availability = when (activity.availability) {
            ActivityAvailability.BUSY -> context.getString(R.string.tool_trace_activity_busy)
            ActivityAvailability.LIMITED -> context.getString(R.string.tool_trace_activity_limited)
            ActivityAvailability.FREE -> context.getString(R.string.tool_trace_activity_free)
        }
        return base + "\n" + availability
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
