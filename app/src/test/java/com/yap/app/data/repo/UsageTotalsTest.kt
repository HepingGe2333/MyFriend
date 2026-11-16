package com.yap.app.data.repo

import com.yap.app.data.local.ApiUsageEntity
import com.yap.app.domain.UsageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用量统计（规格 §5.2 / §九）：按用途与按人两种口径都要能出数，且没有任何拦截。
 *
 * `ApiUsageRepository` 只是数据库读写；真正的聚合逻辑在 [UsageTotals.from]，
 * 所以这里直接喂行数据，验证总量、用途分桶与会话排行的口径。
 */
class UsageTotalsTest {

    private fun row(kind: String, prompt: Int, completion: Int, sessionId: String? = null) =
        ApiUsageEntity(
            id = "$kind-$prompt-$completion-${sessionId.orEmpty()}",
            at = 1L,
            kind = kind,
            sessionId = sessionId,
            promptTokens = prompt,
            completionTokens = completion
        )

    @Test
    fun `按用途分桶 聊天排在最前面`() {
        val totals = UsageTotals.from(
            listOf(
                row(UsageKind.CONSOLIDATION, 100, 50),
                row(UsageKind.CHAT, 300, 200),
                row(UsageKind.CHAT, 100, 100)
            )
        )
        assertEquals(3, totals.calls)
        assertEquals(500, totals.promptTokens)
        assertEquals(350, totals.completionTokens)
        assertEquals(850, totals.totalTokens)
        assertEquals(UsageKind.CHAT, totals.byKind.first().kind)
        assertEquals(2, totals.byKind.first().calls)
        assertEquals(700, totals.byKind.first().totalTokens)
        assertTrue(totals.byKind.any { it.kind == UsageKind.CONSOLIDATION })
    }

    @Test
    fun `按会话排行原样透出 最多由上层取前10`() {
        val sessions = listOf(
            SessionUsage("s1", "林深", calls = 3, promptTokens = 300, completionTokens = 200),
            SessionUsage("s2", "阿岚", calls = 1, promptTokens = 100, completionTokens = 50)
        )
        val totals = UsageTotals.from(listOf(row(UsageKind.CHAT, 300, 200, "s1")), sessions)
        assertEquals(2, totals.bySession.size)
        assertEquals("林深", totals.bySession.first().label)
        assertEquals(500, totals.bySession.first().totalTokens)
    }

    @Test
    fun `空数据不崩 也不产生假的成本字段`() {
        assertTrue(UsageTotals.from(emptyList()).isEmpty)
        // 1.9 的用量页只认 token：UsageTotals 上已经没有「钱」这个属性了。
        val totals = UsageTotals.from(emptyList())
        assertEquals(0, totals.totalTokens)
    }
}
