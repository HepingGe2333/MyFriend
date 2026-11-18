package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 日记板的纯领域规则（`spec/diary/README.md`）。
 *
 * 这里只测不碰数据库的部分：内容长度、条数上限、提醒时间换算、错过 24 小时不补、
 * 「已经提醒过就不再算到点」，以及回灌给模型的中文正文。落库与「只触发一次」的
 * 原子性在 DAO/SQL 里，由 androidTest 的迁移/DAO 用例与心跳接线保证。
 */
class DiaryTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 18, 19, 40, 0, 0, zone).toInstant().toEpochMilli()

    private fun entry(
        content: String = "周五问她面试怎么样",
        createdAt: Long = now - 3 * 60 * 60_000L,
        remindAt: Long? = null,
        remindedAt: Long? = null
    ) = Diary.Entry(id = "n1", content = content, createdAt = createdAt, remindAt = remindAt, remindedAt = remindedAt)

    // ------------------------------------------------------------ 内容与条数

    @Test
    fun `内容去掉首尾空白后原样保存`() {
        assertEquals("买牛奶", Diary.cleanContent("  买牛奶 \n"))
    }

    @Test
    fun `空内容返回 null 因为一条空笔记没有意义`() {
        assertNull(Diary.cleanContent(null))
        assertNull(Diary.cleanContent(""))
        assertNull(Diary.cleanContent("   \n\t "))
    }

    @Test
    fun `内容超过 2000 字会被截断`() {
        val long = "字".repeat(Diary.CONTENT_MAX + 500)
        val clean = Diary.cleanContent(long)
        assertEquals(Diary.CONTENT_MAX, clean!!.length)
        assertTrue(long.startsWith(clean))
    }

    @Test
    fun `刚好 2000 字不截断`() {
        val exact = "字".repeat(Diary.CONTENT_MAX)
        assertEquals(Diary.CONTENT_MAX, Diary.cleanContent(exact)!!.length)
    }

    @Test
    fun `list 默认 20 条 最多 50 最少 1`() {
        assertEquals(20, Diary.limitOf(null))
        assertEquals(3, Diary.limitOf(3))
        assertEquals(50, Diary.limitOf(51))
        assertEquals(50, Diary.limitOf(5000))
        assertEquals(1, Diary.limitOf(0))
        assertEquals(1, Diary.limitOf(-5))
    }

    // ------------------------------------------------------------ 提醒换算

    @Test
    fun `显式 remindAt 优先于 remindInMinutes`() {
        val explicit = now + 90 * 60_000L
        assertEquals(explicit, Diary.resolveRemindAt(explicit, 30, now))
    }

    @Test
    fun `remindInMinutes 按 now 换算`() {
        assertEquals(now + 30 * 60_000L, Diary.resolveRemindAt(null, 30, now))
    }

    @Test
    fun `都不给就是不提醒`() {
        assertNull(Diary.resolveRemindAt(null, null, now))
    }

    @Test
    fun `非法提醒值当作不提醒 不写出荒唐的定时`() {
        assertNull("0 不是有效时间戳", Diary.resolveRemindAt(0L, null, now))
        assertNull("负数不是有效时间戳", Diary.resolveRemindAt(-1L, null, now))
        assertNull("0 分钟不算提醒", Diary.resolveRemindAt(null, 0, now))
        assertNull("负数分钟不算提醒", Diary.resolveRemindAt(null, -30, now))
        assertNull("超过 30 天不算提醒", Diary.resolveRemindAt(null, Diary.REMIND_IN_MINUTES_MAX + 1, now))
    }

    @Test
    fun `提醒分钟数的边界 1 与 30 天都有效`() {
        assertEquals(now + 60_000L, Diary.resolveRemindAt(null, 1, now))
        assertEquals(
            now + Diary.REMIND_IN_MINUTES_MAX * 60_000L,
            Diary.resolveRemindAt(null, Diary.REMIND_IN_MINUTES_MAX, now)
        )
    }

    // ------------------------------------------------------------ 到点 / 错过

    @Test
    fun `到点判定 有 remindAt 未提醒过且已到点`() {
        assertTrue(Diary.isDue(entry(remindAt = now - 1_000L), now))
        assertTrue(Diary.isDue(entry(remindAt = now), now))
        assertFalse("还没到点", Diary.isDue(entry(remindAt = now + 60_000L), now))
        assertFalse("没有提醒时间", Diary.isDue(entry(remindAt = null), now))
        assertFalse("已经提醒过", Diary.isDue(entry(remindAt = now - 1_000L, remindedAt = now), now))
    }

    @Test
    fun `错过超过 24 小时不补发`() {
        assertFalse(Diary.isMissed(now - Diary.MISSED_GRACE_MS, now))
        assertTrue(Diary.isMissed(now - Diary.MISSED_GRACE_MS - 1, now))
        assertFalse("没有提醒时间就谈不上错过", Diary.isMissed(null, now))
    }

    // ------------------------------------------------------------ 给模型看

    @Test
    fun `空本子也要给一句人话 不能是空串`() {
        val text = Diary.renderForModel(emptyList(), now, zone)
        assertTrue(text.isNotBlank())
        assertTrue(text.contains("还没有"))
    }

    @Test
    fun `翻日记的正文按顺序列出 时间 内容 与提醒状态`() {
        val notes = listOf(
            entry(content = "买牛奶", createdAt = now - 2 * 24 * 60 * 60_000L),
            entry(
                content = "周五问她面试怎么样",
                createdAt = now - 60 * 60_000L,
                remindAt = now + 24 * 60 * 60_000L
            )
        )
        val text = Diary.renderForModel(notes, now, zone)
        assertTrue(text.contains("买牛奶"))
        assertTrue(text.contains("周五问她面试怎么样"))
        assertTrue("较早的那条要写成「2 天前」这类相对时间", text.contains("2 天前"))
        assertTrue("有提醒的要写清提醒时间", text.contains("提醒："))
        assertTrue("先给头部再给条目", text.indexOf("买牛奶") > text.indexOf("备忘"))
    }

    @Test
    fun `翻日记的正文里带 id 否则模型没法删掉某一条`() {
        val text = Diary.renderForModel(listOf(entry(content = "买牛奶")), now, zone)
        assertTrue("list 的结果必须能指向某一条（remove 只认 id）：$text", text.contains("#n1"))
    }

    @Test
    fun `已经提醒过的条目写提醒过 不再当成待办`() {
        val text = Diary.renderForModel(listOf(entry(remindAt = now - 60_000L, remindedAt = now - 30_000L)), now, zone)
        assertTrue(text.contains("提醒过"))
    }

    @Test
    fun `add 的回灌写清记下了与到点提醒`() {
        val withRemind = Diary.renderOne(entry(remindAt = now + 30 * 60_000L), now, zone)
        assertTrue(withRemind.contains("记下了"))
        assertTrue(withRemind.contains("提醒你"))
        val plain = Diary.renderOne(entry(), now, zone)
        assertTrue(plain.contains("记下了"))
        assertFalse(plain.contains("提醒你"))
    }

    @Test
    fun `提醒变成念头时用第一人称 并带上原文`() {
        val note = Diary.reminderNote("周五问她面试怎么样")
        assertTrue(note.contains("周五问她面试怎么样"))
        assertTrue("是自语，不是系统通知", note.startsWith("日记里写着"))
    }

    @Test
    fun `念头的 hint 说明备忘是什么时候写的`() {
        val hint = Diary.reminderHint(now - 3 * 60 * 60_000L, now)
        assertTrue(hint.contains("3 小时前"))
        assertTrue(hint.contains("开口"))
    }

    @Test
    fun `长的多行内容进正文时折成一行`() {
        val text = Diary.renderForModel(listOf(entry(content = "第一行\n第二行")), now, zone)
        assertFalse("列表里不能因为换行把一条拆成两条", text.contains("\n- （刚刚）第一行\n"))
        assertTrue(text.contains("第一行 第二行"))
    }
}
