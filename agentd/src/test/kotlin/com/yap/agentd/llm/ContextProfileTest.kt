package com.yap.agentd.llm

import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.Mood
import com.yap.app.domain.PromptContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 0.15 上下文 profile 的**三条纯规则**（`spec/context-profile/README.md`）：
 *
 * 1. 引导 g12/g17 的选择（`valence < 0` 或 `arousal < 2` → g12；其余与「没有心情」→ g17）；
 * 2. 反 AI 收敛成 core 五条（前四条逐字复用共享契约，第五条是不旁白）；
 * 3. 历史时间戳（`[HH:mm]` 同日 / `[MM-dd HH:mm]` 跨天，工具与系统消息不加）。
 *
 * 这些是「同一份 profile 在两端/服务端一致」的契约点，所以断言逐字比对文本，不做模糊匹配。
 */
class ContextProfileTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-15T11:40:00Z").toEpochMilli() // 19:40 本地

    private fun mood(valence: Int, arousal: Int) = Mood(
        personaId = "p1",
        label = "平静",
        intensity = 3,
        updatedAt = now,
        valence = valence,
        arousal = arousal,
        dominance = 3
    )

    // ------------------------------------------------------------------ 引导

    @Test
    fun `guidance picks g12 only for negative valence or low arousal`() {
        assertEquals("没有心情 → g17", PromptGuidance.G17, PromptGuidance.select(null))
        assertEquals("valence<0 → g12", PromptGuidance.G12, PromptGuidance.select(mood(-1, 4)))
        assertEquals("valence<0 → g12（低唤醒也是）", PromptGuidance.G12, PromptGuidance.select(mood(-2, 0)))
        assertEquals("arousal<2 → g12", PromptGuidance.G12, PromptGuidance.select(mood(0, 1)))
        assertEquals("arousal=2（平静）→ g17", PromptGuidance.G17, PromptGuidance.select(mood(0, 2)))
        assertEquals("valence 0 / arousal 3 → g17", PromptGuidance.G17, PromptGuidance.select(mood(0, 3)))
        assertEquals("valence 2 / arousal 5 → g17", PromptGuidance.G17, PromptGuidance.select(mood(2, 5)))
    }

    @Test
    fun `guidance text is fixed verbatim`() {
        assertEquals(
            """
            # 引导
            这一轮：有点防备，不轻易示弱
            - 用短句挡一下
            - 被问急了才多露半句
            - 不要掏心掏肺。
            """.trimIndent(),
            PromptGuidance.section(mood(-1, 2))
        )
        assertEquals(
            """
            # 引导
            这一轮：只聊此刻，不翻旧账
            - 回应现在这句话
            - 不延伸过去
            - 不要反问。
            """.trimIndent(),
            PromptGuidance.section(null)
        )
    }

    // ------------------------------------------------------------------ 反 AI

    @Test
    fun `anti ai core keeps exactly five rules`() {
        assertEquals(5, AntiAiCore.RULES.size)
        assertEquals(
            "前四条逐字复用共享契约（两端不会漂）",
            PromptContract.AntiAi.RULES.take(4),
            AntiAiCore.RULES.take(4)
        )
        assertTrue("第五条 = 不旁白", AntiAiCore.RULES.last() == AntiAiCore.NARRATION_RULE)
        assertTrue(AntiAiCore.RULES.last().contains("旁白"))

        val section = AntiAiCore.section()
        assertTrue(section.startsWith("# 别像 AI"))
        assertEquals("标题 + 五条，每条一行", 6, section.lines().size)

        // 被删掉的共享三条不再出现。
        val removed = PromptContract.AntiAi.RULES.drop(4)
        assertEquals(3, removed.size)
        removed.forEach { rule -> assertFalse("已删除的规则不能残留：$rule", section.contains(rule)) }
    }

    // ------------------------------------------------------------------ 时间戳

    @Test
    fun `history timestamps are same day HH mm and cross day MM dd HH mm`() {
        val sameDay = Instant.parse("2026-11-15T11:35:00Z").toEpochMilli() // 19:35 本地
        assertEquals("[19:35] 用户：", HistoryTimestamps.prefix(MessageRole.USER, sameDay, now, zone))
        assertEquals("[19:35] 他：", HistoryTimestamps.prefix(MessageRole.ASSISTANT, sameDay, now, zone))

        val yesterday = Instant.parse("2026-11-14T13:05:00Z").toEpochMilli() // 11-14 21:05 本地
        assertEquals("[11-14 21:05] 用户：", HistoryTimestamps.prefix(MessageRole.USER, yesterday, now, zone))
    }

    @Test
    fun `history timestamps use the given zone and never fake a missing time`() {
        val at = Instant.parse("2026-11-15T16:30:00Z").toEpochMilli() // 上海 11-16 00:30 / UTC 11-15 16:30
        val shanghaiNow = now // 2026-11-15 19:40 +08:00
        assertEquals(
            "服务端时区（+08）下已经是第二天 → 跨天",
            "[11-16 00:30] 用户：",
            HistoryTimestamps.prefix(MessageRole.USER, at, shanghaiNow, zone)
        )
        assertEquals(
            "显式 UTC 时区下还是同一天 → 只写时:分",
            "[16:30] 用户：",
            HistoryTimestamps.prefix(MessageRole.USER, at, shanghaiNow, ZoneId.of("UTC"))
        )

        assertNull("工具消息不加", HistoryTimestamps.prefix("TOOL", now, now, zone))
        assertNull("系统消息不加", HistoryTimestamps.prefix(MessageRole.SYSTEM, now, now, zone))
        assertNull("没有 createdAt 就不加", HistoryTimestamps.prefix(MessageRole.USER, 0L, now, zone))
        assertEquals(
            "原样透传时内容不变",
            "系统消息",
            HistoryTimestamps.render(MessageRole.SYSTEM, " 系统消息 ", now, now, zone)
        )
    }
}
