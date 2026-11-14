package com.myfriend.app.ui.screens.memory

import com.myfriend.app.data.repo.MemoryConsolidationWorker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「记忆的健康」卡片与整理结果的中文文案。
 *
 * 只拼句子，不碰 Android、不碰数据库，也不读时钟（`now` 由调用方传）：
 * 「昨天 04:10」这种话最容易写错日期边界，放在这里好用单测盯住。
 */
object MemoryHealthText {

    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val dayFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.getDefault())

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
        now: Long = System.currentTimeMillis()
    ): String {
        val counts = countsText(kept, merged, archived, forgotten, separator = "、")
        val head = "上次整理：${dayAndClock(at, now)}"
        return if (counts.isEmpty()) head else "$head · $counts"
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
        forgotten: Int
    ): String = when (status) {
        MemoryConsolidationWorker.STATUS_DONE -> {
            val counts = countsText(kept, merged, archived, forgotten, separator = "，")
            if (counts.isEmpty()) "整理完了：这次没怎么动，让它自己慢慢变淡" else "整理完了：$counts"
        }

        MemoryConsolidationWorker.STATUS_EMPTY -> "整理完了：这次没有需要整理的东西"
        MemoryConsolidationWorker.STATUS_DISABLED -> "还没整理：记忆整理现在是关着的"
        MemoryConsolidationWorker.STATUS_FAILED -> "这次没能整理，明天它自己会再试一次"
        else -> "整理结束了，回来看看记忆有什么变化"
    }

    /** 四项计数拼成中文；全为 0 时返回空串（由调用方决定补哪句话）。 */
    private fun countsText(
        kept: Int,
        merged: Int,
        archived: Int,
        forgotten: Int,
        separator: String
    ): String {
        val parts = buildList {
            if (kept > 0) add("留下 $kept 条")
            if (merged > 0) add("并掉 $merged 条")
            if (archived > 0) add("移出 $archived 条")
            if (forgotten > 0) add("忘掉 $forgotten 条")
        }
        return parts.joinToString(separator)
    }

    /** 今天 / 昨天 / 几月几日 再加精确到分钟的时刻。 */
    private fun dayAndClock(at: Long, now: Long): String {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = date.format(clockFormatter)
        return when {
            date.toLocalDate() == today -> "今天 $clock"
            date.toLocalDate() == today.minusDays(1) -> "昨天 $clock"
            date.year == today.year -> "${date.format(dayFormatter)} $clock"
            else -> "${date.year}/${date.monthValue}/${date.dayOfMonth} $clock"
        }
    }
}
