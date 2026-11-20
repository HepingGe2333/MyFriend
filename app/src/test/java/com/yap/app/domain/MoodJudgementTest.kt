package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动情绪判定的纯领域测试（`spec/mood/README.md` §2）。
 *
 * 四件事必须逐条钉住：**什么时候判**（0.15：每条用户消息后）、**判出来的值怎么收**
 * （只做物理范围夹取，不再限制单次幅度）、**什么时候不写**（没有明显变化 / 手动改过）、
 * **失败怎么办**（按时间衰减）。
 * 这些规则一旦松掉，表现不是崩溃，而是「他每天都是同一种情绪」或者「一句话就翻脸」。
 */
class MoodJudgementTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private val baseline = MoodReading(
        label = "平静",
        intensity = 2,
        valence = 0,
        arousal = 2,
        dominance = 3
    )

    // ------------------------------------------------------------- 触发条件

    @Test
    fun `每条用户消息后都重新判定一次`() {
        // 0.15：第一条消息就判，不再等攒够 4 条。
        assertTrue(MoodJudgement.shouldJudge(userMessages = 1, cursor = 0, lastRunAt = 0L, now = now))
        // 每多一条都判一次。
        assertTrue(MoodJudgement.shouldJudge(userMessages = 4, cursor = 3, lastRunAt = now, now = now))
        assertTrue(MoodJudgement.shouldJudge(userMessages = 8, cursor = 7, lastRunAt = now, now = now))
    }

    @Test
    fun `没有新消息就不重复判`() {
        assertFalse(MoodJudgement.shouldJudge(userMessages = 7, cursor = 7, lastRunAt = now, now = now))
        assertFalse(MoodJudgement.shouldJudge(userMessages = 0, cursor = 0, lastRunAt = 0L, now = now))
    }

    @Test
    fun `消息数倒退时按零算 不抛异常`() {
        assertFalse(MoodJudgement.shouldJudge(userMessages = 2, cursor = 10, lastRunAt = now, now = now))
    }

    @Test
    fun `后台判定不能覆盖模型刚手动改过的心情`() {
        // 判定开始时读到的 updatedAt = 100；落库前发现库里的更新了（set_mood 手动改过）→ 作废。
        assertTrue(MoodJudgement.isStale(judgedFromUpdatedAt = 100L, currentUpdatedAt = 200L))
        assertFalse("没有更新时正常写入", MoodJudgement.isStale(100L, 100L))
        assertFalse("时钟倒退/脏数据按不更新处理", MoodJudgement.isStale(100L, 0L))
    }

    // ------------------------------------------------------------- 解析

    @Test
    fun `合法 JSON 解析成多维心情`() {
        val parsed = MoodJudgement.parse(
            """{"label":"有点累","valence":-1,"arousal":1,"dominance":2,"intensity":3,"reason":"今天加班"}""",
            baseline,
            now
        )
        assertNotNull(parsed)
        assertEquals("有点累", parsed!!.label)
        assertEquals(-1, parsed.valence)
        assertEquals(1, parsed.arousal)
        assertEquals(2, parsed.dominance)
        assertEquals(3, parsed.intensity)
        assertEquals("今天加班", parsed.reason)
        assertEquals(now, parsed.updatedAt)
    }

    @Test
    fun `模型套代码块或包一层 mood 也能解析`() {
        val fenced = MoodJudgement.parse(
            """
            ```json
            {"mood":{"label":"开心","valence":1,"arousal":3,"dominance":4,"intensity":3}}
            ```
            """.trimIndent(),
            baseline,
            now
        )
        assertNotNull(fenced)
        assertEquals("开心", fenced!!.label)
        assertEquals(1, fenced.valence)
    }

    @Test
    fun `只做物理范围夹取 不再限制单次变化幅度`() {
        val parsed = MoodJudgement.parse(
            """{"label":"心烦","valence":-9,"arousal":9,"dominance":-4,"intensity":12,"reason":"烦"}""",
            baseline,
            now
        )
        assertNotNull(parsed)
        // 只夹到规范范围：不再把 valence 从 0 压到 -2、arousal 从 2 压到 4。
        assertEquals(-2, parsed!!.valence)
        assertEquals("arousal 直接到 5，不再被上一轮 +2 卡住", 5, parsed.arousal)
        assertEquals(0, parsed.dominance)
        assertEquals(5, parsed.intensity)
    }

    @Test
    fun `只给标签时按旧数据映射推出维度 不沿用上一轮的矛盾组合`() {
        val parsed = MoodJudgement.parse("""{"label":"开心"}""", baseline, now)
        assertNotNull(parsed)
        assertEquals("开心", parsed!!.label)
        assertEquals("正面的标签不该带着上一轮的 valence 0", 1, parsed.valence)
        // 没给强度就沿用上一轮（2），arousal 由它推。
        assertEquals(2, parsed.arousal)
        assertEquals(MoodDimensions.BASELINE_DOMINANCE, parsed.dominance)
    }

    @Test
    fun `数字写成字符串也认`() {
        val parsed = MoodJudgement.parse(
            """{"label":"放松","valence":"1","arousal":"2","dominance":"3","intensity":"2"}""",
            baseline,
            now
        )
        assertNotNull(parsed)
        assertEquals(1, parsed!!.valence)
        assertEquals(2, parsed.arousal)
    }

    @Test
    fun `没有明显变化就返回 null 不写库`() {
        assertNull(
            MoodJudgement.parse(
                """{"label":"平静","valence":0,"arousal":2,"dominance":3,"intensity":2}""",
                baseline,
                now
            )
        )
    }

    @Test
    fun `坏 JSON 或只有 reason 时不覆盖心情`() {
        assertNull(MoodJudgement.parse("", baseline, now))
        assertNull(MoodJudgement.parse("不是 JSON", baseline, now))
        assertNull(MoodJudgement.parse("""{"reason":"说不清"}""", baseline, now))
        assertNull(MoodJudgement.parse("""{"label":""}""", baseline, now))
    }

    @Test
    fun `缺字段时沿用上一轮的值`() {
        val previous = MoodReading(label = "有点累", intensity = 3, valence = -1, arousal = 1, dominance = 2)
        val parsed = MoodJudgement.parse("""{"label":"有点累","reason":"还是累"}""", previous, now)
        // 标签/维度都没变，只有 reason 变了：不算明显变化。
        assertNull(parsed)
        val changed = MoodJudgement.parse("""{"label":"有点累","intensity":4}""", previous, now)
        assertNotNull(changed)
        assertEquals(4, changed!!.intensity)
        assertEquals("没给的维度沿用上一轮", 1, changed.arousal)
    }

    // ------------------------------------------------------------- 失败衰减

    @Test
    fun `失败时按小时向 0 2 3 衰减`() {
        val previous = MoodReading(
            label = "心烦",
            intensity = 4,
            valence = -2,
            arousal = 5,
            dominance = 0,
            updatedAt = now - 2 * hour
        )
        val decayed = MoodJudgement.decayAfterFailure(previous, now)
        assertEquals(-2 + 2, decayed.valence)
        assertEquals(5 - 2, decayed.arousal)
        assertEquals(0 + 2, decayed.dominance)
        // 衰减不是一次新的情绪：时间戳不动，下一轮仍从原始时间算。
        assertEquals(previous.updatedAt, decayed.updatedAt)
        assertEquals("心烦", decayed.label)
    }

    @Test
    fun `刚变过的心情不会因为一次失败就被衰减`() {
        val previous = MoodReading(label = "雀跃", intensity = 4, valence = 2, arousal = 4, dominance = 4, updatedAt = now)
        assertEquals(previous, MoodJudgement.decayAfterFailure(previous, now))
        assertEquals(previous, MoodJudgement.decayAfterFailure(previous, now - hour))
    }

    @Test
    fun `衰减到底时强度不会掉到零`() {
        val previous = MoodReading(label = "低落", intensity = 1, valence = -2, arousal = 0, dominance = 0, updatedAt = now - 5 * hour)
        val decayed = MoodJudgement.decayAfterFailure(previous, now)
        assertTrue(decayed.intensity in 1..5)
        assertEquals(0, decayed.valence)
        assertEquals(MoodDimensions.BASELINE_AROUSAL, decayed.arousal)
        assertEquals(MoodDimensions.BASELINE_DOMINANCE, decayed.dominance)
    }

    // ------------------------------------------------------------- 提示词

    @Test
    fun `判定提示词带上一轮情绪与最近八条对话`() {
        val previous = MoodReading(label = "有点累", intensity = 3, valence = -1, arousal = 1, dominance = 2, reason = "加班")
        val recent = (1..12).map { "对方：第 $it 句" }
        val prompt = MoodJudgement.userPrompt(previous, recent)
        assertTrue(prompt.contains("上一轮的情绪：有点累"))
        assertTrue(prompt.contains("愉快度 -1"))
        assertTrue(prompt.contains("上一轮的原因：加班"))
        // 只带最后 8 条：第 4 句之后才在窗口里。
        assertFalse(prompt.contains("第 3 句"))
        assertTrue(prompt.contains("第 12 句"))
        assertTrue(prompt.contains("只输出 JSON"))
    }

    @Test
    fun `对话摘要区分对方与你 并裁长度`() {
        val line = MoodJudgement.recentLine(fromUser = true, content = "a".repeat(200))
        assertTrue(line.startsWith("对方："))
        assertEquals(MoodJudgement.RECENT_MESSAGE_CHARS, line.removePrefix("对方：").length)
        assertTrue(MoodJudgement.recentLine(fromUser = false, content = "嗯").startsWith("你："))
        // 换行会被压成空格，避免一条消息在提示词里占好几行。
        assertEquals("你：a b", MoodJudgement.recentLine(fromUser = false, content = "a\nb"))
    }

    @Test
    fun `判定 system 提示词要求 JSON 且限定范围与幅度`() {
        val prompt = MoodJudgement.systemPrompt()
        assertTrue(prompt.contains("只输出一个 JSON 对象"))
        assertTrue(prompt.contains("\"valence\""))
        assertTrue(prompt.contains("\"arousal\""))
        assertTrue(prompt.contains("\"dominance\""))
        assertTrue(prompt.contains("\"intensity\""))
        assertTrue(prompt.contains("\"reason\""))
        assertTrue("-2 到 2", prompt.contains("-2 到 2"))
        assertTrue("0 到 5", prompt.contains("0 到 5"))
        // 0.15：先直觉、后数值；不再要求「单次变化不要超过 2」。
        assertFalse(prompt.contains("单次变化幅度不要超过 2"))
        assertTrue("要先写直觉再填数值", prompt.contains("先写直觉，再填数值"))
        assertTrue("reason 是直觉的一句话", prompt.contains("一句直觉"))
    }
}
