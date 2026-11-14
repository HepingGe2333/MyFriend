package com.myfriend.app.ui.screens.memory

import com.myfriend.app.data.repo.MemoryConsolidationWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 记忆健康卡片与整理结果的中文文案。
 *
 * 这些句子每一条都会被用户直接看到：数字拼错的顺序、「昨天」和「今天」的边界、
 * 一件都没动时该说什么，都在这里盯住。
 */
class MemoryHealthTextTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    // ------------------------------------------------------------- 上次整理

    @Test
    fun `上次整理写昨天加时刻加四项计数`() {
        val now = at(2026, 5, 3, 12, 0)
        val line = MemoryHealthText.lastRunLine(
            at = at(2026, 5, 2, 4, 10),
            kept = 12,
            merged = 2,
            archived = 3,
            forgotten = 1,
            now = now
        )
        assertEquals("上次整理：昨天 04:10 · 留下 12 条、并掉 2 条、移出 3 条、忘掉 1 条", line)
    }

    @Test
    fun `同一天的整理写今天`() {
        val now = at(2026, 5, 3, 12, 0)
        val line = MemoryHealthText.lastRunLine(at(2026, 5, 3, 4, 10), 1, 0, 0, 0, now)
        assertTrue(line.startsWith("上次整理：今天 04:10"))
    }

    @Test
    fun `隔了很久的整理写具体日期`() {
        val now = at(2026, 5, 3, 12, 0)
        val line = MemoryHealthText.lastRunLine(at(2026, 5, 1, 4, 10), 2, 0, 0, 0, now)
        assertTrue(line.startsWith("上次整理：5月1日 04:10"))
    }

    @Test
    fun `四项全为 0 时只留时间`() {
        val now = at(2026, 5, 3, 12, 0)
        val line = MemoryHealthText.lastRunLine(at(2026, 5, 2, 4, 10), 0, 0, 0, 0, now)
        assertEquals("上次整理：昨天 04:10", line)
        assertFalse(line.contains("留下"))
    }

    @Test
    fun `只为非零的项写句子`() {
        val now = at(2026, 5, 3, 12, 0)
        val line = MemoryHealthText.lastRunLine(at(2026, 5, 2, 4, 10), 0, 0, 3, 1, now)
        assertEquals("上次整理：昨天 04:10 · 移出 3 条、忘掉 1 条", line)
    }

    // ------------------------------------------------------------- 手动整理结果

    @Test
    fun `整理完成按留下并掉移出忘掉的顺序说`() {
        val message = MemoryHealthText.manualResultMessage(
            status = MemoryConsolidationWorker.STATUS_DONE,
            kept = 12,
            merged = 2,
            archived = 3,
            forgotten = 1
        )
        assertEquals("整理完了：留下 12 条，并掉 2 条，移出 3 条，忘掉 1 条", message)
    }

    @Test
    fun `整理完成但没动任何东西`() {
        val message = MemoryHealthText.manualResultMessage(
            status = MemoryConsolidationWorker.STATUS_DONE,
            kept = 0,
            merged = 0,
            archived = 0,
            forgotten = 0
        )
        assertTrue(message.startsWith("整理完了："))
        assertTrue(message.contains("没怎么动"))
    }

    @Test
    fun `空跑、关闭、失败都有各自的一句话`() {
        val empty = MemoryHealthText.manualResultMessage(MemoryConsolidationWorker.STATUS_EMPTY, 0, 0, 0, 0)
        val disabled = MemoryHealthText.manualResultMessage(MemoryConsolidationWorker.STATUS_DISABLED, 0, 0, 0, 0)
        val failed = MemoryHealthText.manualResultMessage(MemoryConsolidationWorker.STATUS_FAILED, 0, 0, 0, 0)
        assertTrue(empty.contains("没有需要整理"))
        assertTrue(disabled.contains("关着"))
        assertTrue(failed.contains("明天"))
    }

    @Test
    fun `任务自己挂了也要说得出话`() {
        val message = MemoryHealthText.manualResultMessage(status = null, kept = 0, merged = 0, archived = 0, forgotten = 0)
        assertTrue(message.isNotBlank())
        assertTrue(message.startsWith("整理结束了"))
    }
}
