package com.yap.app.domain

import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拉黑协议（`spec/experience/README.md` §9）：七字段 JSON、三种返回形状、过期判断。
 *
 * 这些字符串会直接回灌给模型、也会落进 `personas.blockProposalJson`，所以测试逐字比对，
 * 不只断言「能解析」。
 */
class BlockProtocolTest {

    private val sample = BlockProtocol.Proposal(
        proposalId = "p1",
        personaId = "persona-1",
        hours = 6,
        reason = "我现在不想说话",
        userMessageId = "m1",
        proposedAt = 1_700_000_000_000L,
        expiresAt = 1_700_000_600_000L
    )

    private fun keysOf(raw: String): List<String> {
        val obj = Json.parse(raw) as JsonValue.Obj
        return obj.fields.keys.toList()
    }

    // ------------------------------------------------------------------ 编码

    @Test
    fun `encode writes exactly the seven spec fields in order`() {
        val encoded = BlockProtocol.encode(sample)
        assertEquals(BlockProtocol.PROPOSAL_FIELDS, keysOf(encoded))
        assertEquals(
            """{"proposalId":"p1","personaId":"persona-1","hours":6,"reason":"我现在不想说话","userMessageId":"m1","proposedAt":1700000000000,"expiresAt":1700000600000}""",
            encoded
        )
    }

    @Test
    fun `encode keeps empty fields because they are part of the contract`() {
        val encoded = BlockProtocol.encode(
            BlockProtocol.Proposal(proposalId = "p1", hours = 1, proposedAt = 1, expiresAt = 2)
        )
        assertEquals(BlockProtocol.PROPOSAL_FIELDS, keysOf(encoded))
        assertTrue(encoded.contains("\"personaId\":\"\""))
        assertTrue(encoded.contains("\"reason\":\"\""))
        assertTrue(encoded.contains("\"userMessageId\":\"\""))
    }

    @Test
    fun `encode escapes quotes newlines and backslashes`() {
        val tricky = sample.copy(reason = "他说\"好\"\n然后\\走了")
        val encoded = BlockProtocol.encode(tricky)
        assertTrue(encoded.contains("\\\""))
        assertTrue(encoded.contains("\\n"))
        assertTrue(encoded.contains("\\\\"))
        assertEquals(tricky, BlockProtocol.decode(encoded))
    }

    // ------------------------------------------------------------------ 解码

    @Test
    fun `encode decode round trips`() {
        assertEquals(sample, BlockProtocol.decode(BlockProtocol.encode(sample)))
    }

    @Test
    fun `decode tolerates missing optional fields`() {
        val decoded = BlockProtocol.decode("""{"proposalId":"p9"}""")
        assertEquals(
            BlockProtocol.Proposal(
                proposalId = "p9",
                personaId = "",
                hours = BlockProtocol.DEFAULT_HOURS,
                reason = "",
                userMessageId = "",
                proposedAt = 0L,
                expiresAt = 0L
            ),
            decoded
        )
    }

    @Test
    fun `decode ignores extra keys but keeps the seven fields`() {
        val decoded = BlockProtocol.decode(
            """{"proposalId":"p9","hours":2,"sessionId":"should-be-ignored","extra":{"a":1}}"""
        )
        assertEquals("p9", decoded?.proposalId)
        assertEquals(2, decoded?.hours)
    }

    @Test
    fun `decode clamps hours and truncates reason`() {
        val longReason = "长".repeat(200)
        val decoded = BlockProtocol.decode(
            """{"proposalId":"p1","hours":99,"reason":"$longReason","proposedAt":1,"expiresAt":2}"""
        )
        assertEquals(BlockProtocol.MAX_HOURS, decoded?.hours)
        assertEquals(BlockProtocol.MAX_REASON_CHARS, decoded?.reason?.length)
        assertEquals(1L, decoded?.proposedAt)
        assertEquals(2L, decoded?.expiresAt)
        // 下界同样夹取；缺 hours 时用默认值。
        assertEquals(
            BlockProtocol.MIN_HOURS,
            BlockProtocol.decode("""{"proposalId":"p1","hours":-7}""")?.hours
        )
    }

    @Test
    fun `decode rejects malformed or non object input`() {
        val bad = listOf(
            null,
            "",
            "   ",
            "{",
            "[]",
            "\"proposalId\"",
            "123",
            "{}",
            """{"proposalId":""}""",
            """{"proposalId":"   "}"""
        )
        for (raw in bad) {
            assertNull("input=$raw", BlockProtocol.decode(raw))
        }
    }

    // ------------------------------------------------------------------ 返回形状

    @Test
    fun `pending json matches the spec shape exactly`() {
        assertEquals(
            """{"status":"pending_confirmation","expiresAt":1700000600000}""",
            BlockProtocol.pendingJson(1_700_000_600_000L)
        )
        assertEquals(
            BlockProtocol.PENDING_FIELDS,
            keysOf(BlockProtocol.pendingJson(1L))
        )
    }

    @Test
    fun `blocked json matches the spec shape exactly`() {
        assertEquals(
            """{"status":"blocked","blockedUntil":1700021600000}""",
            BlockProtocol.blockedJson(1_700_021_600_000L)
        )
        assertEquals(
            BlockProtocol.BLOCKED_FIELDS,
            keysOf(BlockProtocol.blockedJson(1L))
        )
    }

    @Test
    fun `rejected json matches the spec shape exactly`() {
        assertEquals(
            """{"status":"rejected","code":"need_more_user_messages"}""",
            BlockProtocol.rejectedJson("need_more_user_messages")
        )
        assertEquals(
            BlockProtocol.REJECTED_FIELDS,
            keysOf(BlockProtocol.rejectedJson("x"))
        )
        // code 去空白；空 code 也写出空串（形状固定）。
        assertEquals(
            """{"status":"rejected","code":""}""",
            BlockProtocol.rejectedJson("  ")
        )
    }

    @Test
    fun `status constants are the three spec values`() {
        assertEquals("pending_confirmation", BlockProtocol.STATUS_PENDING_CONFIRMATION)
        assertEquals("blocked", BlockProtocol.STATUS_BLOCKED)
        assertEquals("rejected", BlockProtocol.STATUS_REJECTED)
        assertEquals(3, BlockProtocol.STATUSES.size)
        assertTrue(BlockProtocol.isKnownStatus(BlockProtocol.STATUS_BLOCKED))
        assertFalse(BlockProtocol.isKnownStatus("pending"))
        assertFalse(BlockProtocol.isKnownStatus(null))
    }

    @Test
    fun `statusOf reads the first field of both return shapes`() {
        assertEquals(
            BlockProtocol.STATUS_PENDING_CONFIRMATION,
            BlockProtocol.statusOf(BlockProtocol.pendingJson(1L))
        )
        assertEquals(
            BlockProtocol.STATUS_BLOCKED,
            BlockProtocol.statusOf(BlockProtocol.blockedJson(2L))
        )
        assertEquals(
            BlockProtocol.STATUS_REJECTED,
            BlockProtocol.statusOf(BlockProtocol.rejectedJson("x"))
        )
        assertNull(BlockProtocol.statusOf("not json"))
        assertNull(BlockProtocol.statusOf("""{"status":""}"""))
        assertNull(BlockProtocol.statusOf(null))
    }

    // ------------------------------------------------------------------ 规则

    @Test
    fun `expiry treats clock rollback and the boundary as expired`() {
        val proposal = BlockProtocol.Proposal(
            proposalId = "p1",
            hours = 1,
            proposedAt = 1_000L,
            expiresAt = 601_000L
        )
        assertTrue(BlockProtocol.isExpired(proposal, 999L))
        assertFalse(BlockProtocol.isExpired(proposal, 1_000L))
        assertFalse(BlockProtocol.isExpired(proposal, 600_999L))
        assertTrue(BlockProtocol.isExpired(proposal, 601_000L))
        assertTrue(BlockProtocol.isExpired(proposal, 700_000L))
        // 没有时间戳的坏数据按过期处理，不冒险生效。
        assertTrue(BlockProtocol.isExpired(BlockProtocol.Proposal(proposalId = "p1"), 1L))
    }

    @Test
    fun `sanitize trims and clamps every field`() {
        val dirty = BlockProtocol.Proposal(
            proposalId = "  p1  ",
            personaId = " persona ",
            hours = 99,
            reason = "  " + "长".repeat(200) + "  ",
            userMessageId = " m1 ",
            proposedAt = 7L,
            expiresAt = 8L
        )
        val clean = BlockProtocol.sanitize(dirty)
        assertEquals("p1", clean.proposalId)
        assertEquals("persona", clean.personaId)
        assertEquals(BlockProtocol.MAX_HOURS, clean.hours)
        assertEquals(BlockProtocol.MAX_REASON_CHARS, clean.reason.length)
        assertEquals("m1", clean.userMessageId)
        assertEquals(7L, clean.proposedAt)
        assertEquals(8L, clean.expiresAt)
    }

    @Test
    fun `hours and ttl constants follow the spec`() {
        assertEquals(1, BlockProtocol.MIN_HOURS)
        assertEquals(24, BlockProtocol.MAX_HOURS)
        assertEquals(12, BlockProtocol.DEFAULT_HOURS)
        assertEquals(120, BlockProtocol.MAX_REASON_CHARS)
        assertEquals(10 * 60 * 1000L, BlockProtocol.PROPOSAL_TTL_MS)
        assertEquals(601_000L, BlockProtocol.expiresAtFor(1_000L))
        assertEquals(1, BlockProtocol.clampHours(-100))
        assertEquals(24, BlockProtocol.clampHours(100))
        assertEquals(6, BlockProtocol.clampHours(6))
    }
}
