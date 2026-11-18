package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `第一次调用只产生 pending_confirmation 不生效`() {
        val plan = BlockPolicy.plan(
            existing = null, confirm = false, hours = 3, reason = "越界",
            now = now, userMessageCount = 10
        )
        assertTrue(plan is BlockPlan.Pending)
        val proposal = (plan as BlockPlan.Pending).proposal
        assertEquals(3, proposal.hours)
        assertEquals(10, proposal.afterUserCount)
        assertFalse(BlockPolicy.isBlocked(null, now))
        assertEquals(proposal, BlockPolicy.decode(BlockPolicy.encode(proposal)))
    }

    @Test
    fun `必须在之后的一轮有新的用户消息才能确认`() {
        val pending = (BlockPolicy.plan(null, false, 6, "烦", now, 4) as BlockPlan.Pending).proposal
        // 同一轮再 confirm：用户消息数没变，拒绝。
        assertTrue(
            BlockPolicy.plan(pending, true, 6, null, now + 1_000, 4) is BlockPlan.Reject
        )
        // 隔了一条新用户消息：生效。
        val applied = BlockPolicy.plan(pending, true, 6, null, now + 2_000, 5)
        assertTrue(applied is BlockPlan.Apply)
        val until = (applied as BlockPlan.Apply).until
        assertEquals(now + 2_000L + 6 * 3_600_000L, until)
        assertTrue(BlockPolicy.isBlocked(until - 1, now))
        assertFalse(BlockPolicy.isBlocked(until, until))
    }

    @Test
    fun `提议十分钟后过期`() {
        val pending = (BlockPolicy.plan(null, false, 2, "", now, 1) as BlockPlan.Pending).proposal
        assertTrue(
            BlockPolicy.plan(pending, true, 2, null, now + BlockPolicy.PROPOSAL_TTL_MS - 1, 2) is BlockPlan.Apply
        )
        assertTrue(
            BlockPolicy.plan(
                pending, true, 2, null, now + BlockPolicy.PROPOSAL_TTL_MS, 2
            ) is BlockPlan.Reject
        )
    }

    @Test
    fun `时长夹在 1 到 24 小时`() {
        val a = (BlockPolicy.plan(null, false, 0, "", now, 0) as BlockPlan.Pending).proposal
        val b = (BlockPolicy.plan(null, false, 99, "", now, 0) as BlockPlan.Pending).proposal
        assertEquals(1, a.hours)
        assertEquals(24, b.hours)
    }
}
