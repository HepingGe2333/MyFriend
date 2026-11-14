package com.myfriend.app.ui.screens.memory

import com.myfriend.app.data.repo.MemoryConsolidationWorker
import com.myfriend.app.i18n.TestResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * 记忆健康卡片与整理结果的文案拼装。
 *
 * 句子本身已经搬进 res 下的 strings.xml：这里用 [TestResources] 直接读真实资源，
 * 所以断言的同时也在钉「资源里的原话有没有被改动」。
 * 中文一套（`values/`）、英语一套（`values-en/`）都跑，数字拼错的顺序、
 * 「昨天」和「今天」的边界、一件都没动时该说什么，都在这里盯住。
 */
class MemoryHealthTextTest {

    private val zh = TestResources.lookup("values")
    private val en = TestResources.lookup("values-en")

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
            now = now,
            strings = zh
        )
        assertEquals("上次整理：昨天 04:10 · 留下 12 条、并掉 2 条、移出 3 条、忘掉 1 条", line)
    }

    @Test
    fun `同一天的整理写今天`() {
        val now = at(2026, 5, 3, 12, 0)
        val line = MemoryHealthText.lastRunLine(at(2026, 5, 3, 4, 10), 1, 0, 0, 0, now, zh)
        assertTrue(line.startsWith("上次整理：今天 04:10"))
    }

    @Test
    fun `隔了很久的整理写具体日期`() {
        val now = at(2026, 5, 3, 12, 0)
        val line = MemoryHealthText.lastRunLine(at(2026, 5, 1, 4, 10), 2, 0, 0, 0, now, zh)
        assertTrue(line.startsWith("上次整理：5月1日 04:10"))
    }

    @Test
    fun `四项全为 0 时只留时间`() {
        val now = at(2026, 5, 3, 12, 0)
        val line = MemoryHealthText.lastRunLine(at(2026, 5, 2, 4, 10), 0, 0, 0, 0, now, zh)
        assertEquals("上次整理：昨天 04:10", line)
        assertFalse(line.contains("留下"))
    }

    @Test
    fun `只为非零的项写句子`() {
        val now = at(2026, 5, 3, 12, 0)
        val line = MemoryHealthText.lastRunLine(at(2026, 5, 2, 4, 10), 0, 0, 3, 1, now, zh)
        assertEquals("上次整理：昨天 04:10 · 移出 3 条、忘掉 1 条", line)
    }

    @Test
    fun `英文资源下说的是英语的昨天与计数`() {
        // 真机上 App 语言是英语时 Locale.getDefault() 就是 en；单测里显式摆成 en-US，
        // 才能验到 "4:10 AM" 而不是跟着宿主机的「上午」。
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val now = at(2026, 5, 3, 12, 0)
            val line = MemoryHealthText.lastRunLine(at(2026, 5, 2, 4, 10), 12, 2, 3, 1, now, en)
            assertEquals("Last tidy: Yesterday, 4:10 AM · kept 12, merged 2, moved out 3, forgot 1", line)
        } finally {
            Locale.setDefault(previous)
        }
    }

    // ------------------------------------------------------------- 手动整理结果

    @Test
    fun `整理完成按留下并掉移出忘掉的顺序说`() {
        val message = MemoryHealthText.manualResultMessage(
            status = MemoryConsolidationWorker.STATUS_DONE,
            kept = 12,
            merged = 2,
            archived = 3,
            forgotten = 1,
            strings = zh
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
            forgotten = 0,
            strings = zh
        )
        assertTrue(message.startsWith("整理完了："))
        assertTrue(message.contains("没怎么动"))
    }

    @Test
    fun `空跑、关闭、失败都有各自的一句话`() {
        val empty = MemoryHealthText.manualResultMessage(MemoryConsolidationWorker.STATUS_EMPTY, 0, 0, 0, 0, zh)
        val disabled = MemoryHealthText.manualResultMessage(MemoryConsolidationWorker.STATUS_DISABLED, 0, 0, 0, 0, zh)
        val failed = MemoryHealthText.manualResultMessage(MemoryConsolidationWorker.STATUS_FAILED, 0, 0, 0, 0, zh)
        assertTrue(empty.contains("没有需要整理"))
        assertTrue(disabled.contains("关着"))
        assertTrue(failed.contains("明天"))
    }

    @Test
    fun `任务自己挂了也要说得出话`() {
        val message = MemoryHealthText.manualResultMessage(
            status = null,
            kept = 0,
            merged = 0,
            archived = 0,
            forgotten = 0,
            strings = zh
        )
        assertTrue(message.isNotBlank())
        assertTrue(message.startsWith("整理结束了"))
    }

    @Test
    fun `英文资源下整理结果也成句`() {
        val message = MemoryHealthText.manualResultMessage(
            status = MemoryConsolidationWorker.STATUS_DONE,
            kept = 12,
            merged = 2,
            archived = 3,
            forgotten = 1,
            strings = en
        )
        assertEquals("Done tidying: kept 12, merged 2, moved out 3, forgot 1", message)
    }
}
