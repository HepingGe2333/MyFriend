package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拉黑协议（`spec/experience/README.md` §9）：
 * 七个固定字段、10 分钟过期、两轮确认（`userMessageId` 不同）、工具返回 JSON 原样。
 */
class BlockingTest {

    private val now = 1_700_000_000_000L
    private val persona = "persona-1"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun pending(
        hours: Int = 6,
        reason: String? = "烦",
        at: Long = now,
        userMessageId: String = "user-1"
    ): BlockProposal = (
        BlockPolicy.plan(
            existing = null,
            confirm = false,
            hours = hours,
            reason = reason,
            now = at,
            personaId = persona,
            userMessageId = userMessageId
        ) as BlockPlan.Pending
        ).proposal

    @Test
    fun `第一次调用只产生 pending_confirmation 不生效`() {
        val plan = BlockPolicy.plan(
            existing = null,
            confirm = false,
            hours = 3,
            reason = "越界",
            now = now,
            personaId = persona,
            userMessageId = "user-1"
        )
        assertTrue(plan is BlockPlan.Pending)
        val proposal = (plan as BlockPlan.Pending).proposal
        assertEquals(3, proposal.hours)
        assertEquals(persona, proposal.personaId)
        assertEquals("user-1", proposal.userMessageId)
        assertEquals(now, proposal.proposedAt)
        assertEquals(now + BlockPolicy.PROPOSAL_TTL_MS, proposal.expiresAt)
        assertEquals(proposal, BlockPolicy.decode(BlockPolicy.encode(proposal)))
        assertFalse(BlockPolicy.isBlocked(null, now))
    }

    @Test
    fun `blockProposalJson 恰好是规范里的七个字段`() {
        val encoded = BlockPolicy.encode(pending())
        val root = json.parseToJsonElement(encoded).jsonObject
        assertEquals(BlockPolicy.PROPOSAL_FIELDS.toSet(), root.keys)
        // 空串也必须写出来：服务端按同一份 7 键 DTO 读，缺 reason/userMessageId 会当成坏数据。
        val empty = BlockPolicy.encode(pending(reason = "", userMessageId = ""))
        assertEquals(BlockPolicy.PROPOSAL_FIELDS.toSet(), json.parseToJsonElement(empty).jsonObject.keys)
    }

    @Test
    fun `提议里的 proposalId 稳定且重复提议不续期`() {
        val first = pending()
        // 未过期时再提一次（不带 confirm）：返回同一条，时间/expiresAt 都不变（§9 不续期）。
        val again = (
            BlockPolicy.plan(
                existing = first,
                confirm = false,
                hours = 20,
                reason = "换个理由",
                now = now + 5 * 60_000L,
                personaId = persona,
                userMessageId = "user-2"
            ) as BlockPlan.Pending
            )
        assertFalse(again.renewed)
        assertEquals(first.proposalId, again.proposal.proposalId)
        assertEquals(first.expiresAt, again.proposal.expiresAt)
        assertEquals(first.hours, again.proposal.hours)
        // 过期后再提才是新提议。
        val third = (
            BlockPolicy.plan(
                existing = first,
                confirm = false,
                hours = 20,
                reason = null,
                now = now + BlockPolicy.PROPOSAL_TTL_MS,
                personaId = persona,
                userMessageId = "user-2"
            ) as BlockPlan.Pending
            )
        assertTrue(third.renewed)
        assertNotEquals(first.proposalId, third.proposal.proposalId)
        assertEquals(20, third.proposal.hours)
    }

    @Test
    fun `必须在之后的一轮有不同的 userMessageId 才能确认`() {
        val proposal = pending(userMessageId = "user-1")
        // 同一轮再 confirm：userMessageId 相同，拒绝。
        val sameRound = BlockPolicy.plan(
            existing = proposal,
            confirm = true,
            hours = 6,
            reason = null,
            now = now + 1_000,
            personaId = persona,
            userMessageId = "user-1"
        )
        assertEquals(BlockPolicy.REJECT_NEED_MORE_MESSAGES, (sameRound as BlockPlan.Reject).code)
        // 本轮没有用户消息（主动回合）：同样不能确认。
        val noUserMessage = BlockPolicy.plan(
            existing = proposal,
            confirm = true,
            hours = 6,
            reason = null,
            now = now + 1_000,
            personaId = persona,
            userMessageId = ""
        )
        assertEquals(BlockPolicy.REJECT_NEED_MORE_MESSAGES, (noUserMessage as BlockPlan.Reject).code)
        // 隔了一条新用户消息：生效，时长以**提议里**的 hours 为准（confirm 传 1 也不改）。
        val applied = BlockPolicy.plan(
            existing = proposal,
            confirm = true,
            hours = 1,
            reason = "新理由",
            now = now + 2_000,
            personaId = persona,
            userMessageId = "user-2"
        )
        assertTrue(applied is BlockPlan.Apply)
        applied as BlockPlan.Apply
        assertEquals(now + 2_000L + 6 * 3_600_000L, applied.until)
        assertEquals(6, applied.hours)
        assertEquals("烦", applied.reason)
        assertTrue(BlockPolicy.isBlocked(applied.until - 1, now))
        assertFalse(BlockPolicy.isBlocked(applied.until, applied.until))
    }

    @Test
    fun `提议十分钟后过期 边界按 expiresAt`() {
        val proposal = pending(hours = 2)
        val lastChance = BlockPolicy.plan(
            existing = proposal,
            confirm = true,
            hours = 2,
            reason = null,
            now = proposal.expiresAt - 1,
            personaId = persona,
            userMessageId = "user-2"
        )
        assertTrue(lastChance is BlockPlan.Apply)
        val expired = BlockPolicy.plan(
            existing = proposal,
            confirm = true,
            hours = 2,
            reason = null,
            now = proposal.expiresAt,
            personaId = persona,
            userMessageId = "user-2"
        )
        assertEquals(BlockPolicy.REJECT_EXPIRED, (expired as BlockPlan.Reject).code)
        assertTrue(BlockPolicy.isExpired(proposal, proposal.expiresAt))
        // 时钟倒退也不生效（宁可让模型下一轮重提）。
        assertTrue(BlockPolicy.isExpired(proposal, proposal.proposedAt - 1))
    }

    @Test
    fun `没有提议 或 提议属于别的人格 时不能确认`() {
        val none = BlockPolicy.plan(
            existing = null,
            confirm = true,
            hours = 6,
            reason = null,
            now = now,
            personaId = persona,
            userMessageId = "user-2"
        )
        assertEquals(BlockPolicy.REJECT_NO_PROPOSAL, (none as BlockPlan.Reject).code)
        val otherPersona = BlockPolicy.plan(
            existing = pending(),
            confirm = true,
            hours = 6,
            reason = null,
            now = now + 1_000,
            personaId = "persona-2",
            userMessageId = "user-2"
        )
        assertEquals(BlockPolicy.REJECT_NO_PROPOSAL, (otherPersona as BlockPlan.Reject).code)
    }

    @Test
    fun `时长夹在 1 到 24 小时`() {
        assertEquals(1, pending(hours = 0).hours)
        assertEquals(24, pending(hours = 99).hours)
    }

    @Test
    fun `工具回灌的三行 JSON 与规范逐字一致`() {
        val proposal = pending()
        assertEquals(
            """{"status":"pending_confirmation","expiresAt":${proposal.expiresAt}}""",
            BlockPolicy.pendingJson(proposal)
        )
        assertEquals(
            """{"status":"blocked","blockedUntil":1700021600000}""",
            BlockPolicy.blockedJson(1_700_021_600_000L)
        )
        assertEquals(
            """{"status":"rejected","code":"need_more_user_messages"}""",
            BlockPolicy.rejectedJson(BlockPolicy.REJECT_NEED_MORE_MESSAGES)
        )
        val pendingRoot = json.parseToJsonElement(BlockPolicy.pendingJson(proposal)).jsonObject
        assertEquals(setOf("status", "expiresAt"), pendingRoot.keys)
        val blockedRoot = json.parseToJsonElement(BlockPolicy.blockedJson(1L)).jsonObject
        assertEquals(setOf("status", "blockedUntil"), blockedRoot.keys)
    }

    @Test
    fun `解码兼容 0_14_0 的旧提议形状`() {
        val legacy = """{"at":$now,"hours":5,"afterUserCount":3,"reason":"旧数据"}"""
        val decoded = BlockPolicy.decode(legacy) ?: error("旧提议应该能读出来")
        assertEquals(now, decoded.proposedAt)
        assertEquals(now + BlockPolicy.PROPOSAL_TTL_MS, decoded.expiresAt)
        assertEquals(5, decoded.hours)
        assertEquals("旧数据", decoded.reason)
        assertEquals("", decoded.userMessageId)
        assertTrue(decoded.proposalId.isNotBlank())
        // 旧数据没有 personaId：plan 会补上当前人格，然后照常等下一轮确认。
        val plan = BlockPolicy.plan(
            existing = decoded,
            confirm = false,
            hours = 5,
            reason = null,
            now = now + 1_000,
            personaId = persona,
            userMessageId = "user-9"
        ) as BlockPlan.Pending
        assertEquals(persona, plan.proposal.personaId)
        assertEquals(decoded.proposalId, plan.proposal.proposalId)
    }

    @Test
    fun `坏 JSON 与空值解码成 null`() {
        assertNull(BlockPolicy.decode(null))
        assertNull(BlockPolicy.decode(""))
        assertNull(BlockPolicy.decode("not-json"))
        assertNull(BlockPolicy.decode("""{"hours":6}"""))
    }

    @Test
    fun `界面只在用户消息旁显示拉黑标记`() {
        assertTrue(BlockPolicy.showsBlockedIndicator(roleIsUser = true, blocked = true))
        assertFalse(BlockPolicy.showsBlockedIndicator(roleIsUser = true, blocked = false))
        assertFalse(BlockPolicy.showsBlockedIndicator(roleIsUser = false, blocked = true))
    }
}
