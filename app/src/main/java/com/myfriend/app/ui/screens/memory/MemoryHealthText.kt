package com.myfriend.app.ui.screens.memory

import com.myfriend.app.R
import com.myfriend.app.data.repo.MemoryConsolidationWorker
import com.myfriend.app.util.StringLookup
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「记忆的健康」卡片与整理结果的文案拼装。
 *
 * 句子本身全部在资源里（`memory_health_*`），这里只负责：
 * 哪天该说「今天/昨天/具体日期」、四项计数按什么顺序拼、哪些项为 0 就不说话。
 * 不碰 Android、不碰数据库，也不读时钟（`now` 由调用方传），所以能直接跑 JVM 单测；
 * 取文案通过 [StringLookup]，生产侧从资源拿（语言随系统），单测侧用假实现。
 */
object MemoryHealthText {

    /**
     * 一行整理留痕：「上次整理：昨天 04:10 · 留下 12 条、并掉 2 条、移出 3 条、忘掉 1 条」。
     * 四项全为 0 时只说时间，不硬凑「留下 0 条」。
     */
    fun lastRunLine(
        at: Long,
        kept: Int,
        merged: Int,
        archived: Int,
        forgotten: Int,
        now: Long = System.currentTimeMillis(),
        strings: StringLookup
    ): String {
        val counts = countsText(kept, merged, archived, forgotten, strings)
        val day = dayAndClock(at, now, strings)
        return if (counts.isEmpty()) {
            strings.get(R.string.memory_health_last_run, day)
        } else {
            strings.get(R.string.memory_health_last_run_counts, day, counts)
        }
    }

    /**
     * 手动整理结束后的 snackbar：「整理完了：留下 12 条，并掉 2 条，移出 3 条，忘掉 1 条」。
     *
     * @param status WorkManager 回传的 [MemoryConsolidationWorker.KEY_STATUS]；为空表示任务本身没跑成
     */
    fun manualResultMessage(
        status: String?,
        kept: Int,
        merged: Int,
        archived: Int,
        forgotten: Int,
        strings: StringLookup
    ): String = when (status) {
        MemoryConsolidationWorker.STATUS_DONE -> {
            val counts = countsText(
                kept, merged, archived, forgotten, strings,
                separator = strings.get(R.string.memory_health_counts_separator_clause)
            )
            if (counts.isEmpty()) {
                strings.get(R.string.memory_health_manual_nothing)
            } else {
                strings.get(R.string.memory_health_manual_done, counts)
            }
        }
        MemoryConsolidationWorker.STATUS_EMPTY -> strings.get(R.string.memory_health_manual_empty)
        MemoryConsolidationWorker.STATUS_DISABLED -> strings.get(R.string.memory_health_manual_disabled)
        MemoryConsolidationWorker.STATUS_FAILED -> strings.get(R.string.memory_health_manual_failed)
        else -> strings.get(R.string.memory_health_manual_unknown)
    }

    /** 四项计数拼成一句话；全为 0 时返回空串（由调用方决定补哪句话）。 */
    private fun countsText(
        kept: Int,
        merged: Int,
        archived: Int,
        forgotten: Int,
        strings: StringLookup,
        separator: String = strings.get(R.string.memory_health_counts_separator)
    ): String {
        val parts = buildList {
            if (kept > 0) add(strings.get(R.string.memory_health_kept, kept))
            if (merged > 0) add(strings.get(R.string.memory_health_merged, merged))
            if (archived > 0) add(strings.get(R.string.memory_health_archived, archived))
            if (forgotten > 0) add(strings.get(R.string.memory_health_forgotten, forgotten))
        }
        return parts.joinToString(separator)
    }

    /** 今天 / 昨天 / 几月几日 再加精确到分钟的时刻。 */
    private fun dayAndClock(at: Long, now: Long, strings: StringLookup): String {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = date.format(
            DateTimeFormatter.ofPattern(strings.get(R.string.common_clock_pattern), Locale.getDefault())
        )
        return when {
            date.toLocalDate() == today -> strings.get(R.string.memory_health_today, clock)
            date.toLocalDate() == today.minusDays(1) -> strings.get(R.string.memory_health_yesterday, clock)
            date.year == today.year -> strings.get(
                R.string.memory_health_day_clock,
                date.format(
                    DateTimeFormatter.ofPattern(strings.get(R.string.common_month_day_pattern), Locale.getDefault())
                ),
                clock
            )
            else -> strings.get(
                R.string.memory_health_day_clock,
                date.format(
                    DateTimeFormatter.ofPattern(strings.get(R.string.common_year_month_day_pattern), Locale.getDefault())
                ),
                clock
            )
        }
    }
}
