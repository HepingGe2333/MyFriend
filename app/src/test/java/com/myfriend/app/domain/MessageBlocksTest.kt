package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区块规则（规范 `spec/messages-blocks/README.md` §1/§2/§5）的纯函数单测。
 *
 * 这些规则是「块元数据永远与消息一致」的唯一来源：落库那一半（`MessageBlockWriter`）
 * 只是读 DAO → 问这里 → 写 DAO。所以边界全在这里钉死：满了才开新块、编辑按锚点走、
 * 删除后首尾重算、删空删行且**不重排编号**、导入重建与迁移回填用同一个公式。
 */
class MessageBlocksTest {

    private fun ref(id: String, at: Long, session: String = "s1") =
        MessageBlocks.Ref(id = id, sessionId = session, createdAt = at)

    // ------------------------------------------------------------------ 追加

    @Test
    fun `BLOCK_SIZE 是规范里的 200`() {
        assertEquals(200, MessageBlocks.BLOCK_SIZE)
    }

    @Test
    fun `空会话的第一条消息开 0 号块`() {
        val placement = MessageBlocks.planAppend(last = null, ref = ref("m1", 100))

        assertEquals(0, placement.blockIndex)
        assertTrue("新块要标记 created", placement.created)
        with(placement.meta) {
            assertEquals("s1", sessionId)
            assertEquals(0, blockIndex)
            assertEquals("m1", firstMessageId)
            assertEquals("m1", lastMessageId)
            assertEquals(100L, firstCreatedAt)
            assertEquals(100L, lastCreatedAt)
            assertEquals(1, messageCount)
        }
    }

    @Test
    fun `未满的最后一块继续追加 只挪 last`() {
        val last = MessageBlocks.Meta("s1", 3, "m601", "m700", 601, 700, 100)

        val placement = MessageBlocks.planAppend(last, ref("m701", 701))

        assertEquals("没满就留在原块", 3, placement.blockIndex)
        assertFalse("不是新建块", placement.created)
        with(placement.meta) {
            assertEquals("first 不动", "m601", firstMessageId)
            assertEquals(601L, firstCreatedAt)
            assertEquals("last 前移", "m701", lastMessageId)
            assertEquals(701L, lastCreatedAt)
            assertEquals(101, messageCount)
        }
    }

    @Test
    fun `最后一块恰好 200 条时 追加会开新块`() {
        val full = MessageBlocks.Meta("s1", 3, "m601", "m800", 601, 800, MessageBlocks.BLOCK_SIZE)

        val placement = MessageBlocks.planAppend(full, ref("m801", 801))

        assertEquals(4, placement.blockIndex)
        assertTrue(placement.created)
        assertEquals(1, placement.meta.messageCount)
        assertEquals("m801", placement.meta.firstMessageId)
        assertEquals("m801", placement.meta.lastMessageId)
        assertEquals("新建块的 firstCreatedAt 也要写", 801L, placement.meta.firstCreatedAt)
    }

    @Test
    fun `块编号有空洞时 追加用的是编号最大的那一块而不是块数`() {
        // 1 号块被删空过：块编号是 0、2。追加必须进 2，不能按「有两块 → 进 1」。
        val last = MessageBlocks.Meta("s1", 2, "m401", "m420", 401, 420, 20)

        val placement = MessageBlocks.planAppend(last, ref("m421", 421))

        assertEquals(2, placement.blockIndex)
        assertEquals(21, placement.meta.messageCount)
    }

    // ------------------------------------------------------------------ 按锚点插入

    @Test
    fun `锚点插入落在锚点那一块 且排在最后时 last 前移`() {
        val anchor = MessageBlocks.Meta("s1", 5, "m1001", "m1100", 1001, 1100, 100)

        val placement = MessageBlocks.planInsertAtAnchor(anchor, ref("m1101", 1101))

        assertEquals(5, placement.blockIndex)
        assertEquals("first 不动", "m1001", placement.meta.firstMessageId)
        assertEquals("m1101", placement.meta.lastMessageId)
        assertEquals(1101L, placement.meta.lastCreatedAt)
        assertEquals(101, placement.meta.messageCount)
    }

    @Test
    fun `锚点插入但时间更早时 只加条数不动 last`() {
        val anchor = MessageBlocks.Meta("s1", 5, "m1001", "m1100", 1001, 1100, 100)

        val placement = MessageBlocks.planInsertAtAnchor(anchor, ref("m-hardin", 999))

        assertEquals(5, placement.blockIndex)
        assertEquals("m1100", placement.meta.lastMessageId)
        assertEquals(1100L, placement.meta.lastCreatedAt)
        assertEquals(101, placement.meta.messageCount)
    }

    @Test
    fun `锚点插入允许块超过 BLOCK_SIZE 一条 不重平衡`() {
        val full = MessageBlocks.Meta("s1", 0, "m1", "m200", 1, 200, MessageBlocks.BLOCK_SIZE)

        val placement = MessageBlocks.planInsertAtAnchor(full, ref("m201", 201))

        assertEquals(0, placement.blockIndex)
        assertEquals("规范允许块临时超过上限（不做重平衡）", MessageBlocks.BLOCK_SIZE + 1, placement.meta.messageCount)
    }

    // ------------------------------------------------------------------ 删除

    @Test
    fun `删掉中间一条 只减条数 首尾不动`() {
        val meta = MessageBlocks.Meta("s1", 2, "m401", "m600", 401, 600, 200)

        val updated = MessageBlocks.afterDelete(
            meta,
            remainingFirst = ref("m401", 401),
            remainingLast = ref("m600", 600),
            remainingCount = 199
        )

        assertEquals(199, updated!!.messageCount)
        assertEquals("m401", updated.firstMessageId)
        assertEquals("m600", updated.lastMessageId)
    }

    @Test
    fun `删掉首条或末条时首尾要按剩下的行重算`() {
        val meta = MessageBlocks.Meta("s1", 2, "m401", "m600", 401, 600, 10)

        val afterFirst = MessageBlocks.afterDelete(meta, ref("m402", 402), ref("m600", 600), 9)!!
        assertEquals("m402", afterFirst.firstMessageId)
        assertEquals(402L, afterFirst.firstCreatedAt)
        assertEquals("m600", afterFirst.lastMessageId)

        val afterLast = MessageBlocks.afterDelete(meta, ref("m401", 401), ref("m599", 599), 9)!!
        assertEquals("m401", afterLast.firstMessageId)
        assertEquals("m599", afterLast.lastMessageId)
        assertEquals(599L, afterLast.lastCreatedAt)
    }

    @Test
    fun `删空一块时返回 null 表示整行删掉 编号不重排`() {
        val meta = MessageBlocks.Meta("s1", 2, "m401", "m401", 401, 401, 1)

        assertNull(MessageBlocks.afterDelete(meta, remainingFirst = null, remainingLast = null, remainingCount = 0))
        assertNull(MessageBlocks.afterDelete(meta, ref("m401", 401), null, 0))
        assertNull(MessageBlocks.afterDelete(meta, null, ref("m401", 401), 0))
    }

    // ------------------------------------------------------------------ 重建（导入 / 迁移回填）

    @Test
    fun `行号除以 BLOCK_SIZE 就是块编号`() {
        assertEquals(0, MessageBlocks.blockIndexOf(0))
        assertEquals(0, MessageBlocks.blockIndexOf(199))
        assertEquals(1, MessageBlocks.blockIndexOf(200))
        assertEquals(1, MessageBlocks.blockIndexOf(399))
        assertEquals(2, MessageBlocks.blockIndexOf(400))
    }

    @Test
    fun `重建 450 条得到 200 200 50 三块`() {
        val refs = (0 until 450).map { ref("m$it", 1_000L + it) }

        val metas = MessageBlocks.rebuild("s1", refs)

        assertEquals(3, metas.size)
        assertEquals(listOf(0, 1, 2), metas.map { it.blockIndex })
        assertEquals(listOf(200, 200, 50), metas.map { it.messageCount })
        assertEquals("m0", metas[0].firstMessageId)
        assertEquals("m199", metas[0].lastMessageId)
        assertEquals("m200", metas[1].firstMessageId)
        assertEquals("m399", metas[1].lastMessageId)
        assertEquals("m400", metas[2].firstMessageId)
        assertEquals("m449", metas[2].lastMessageId)
        assertEquals(1_000L, metas[0].firstCreatedAt)
        assertEquals(1_449L, metas[2].lastCreatedAt)
    }

    @Test
    fun `重建与逐条追加得到的块边界完全一致`() {
        val refs = (0 until 430).map { ref("m$it", 1_000L + it) }

        // 逐条追加（生产写入路径）
        val appended = ArrayList<MessageBlocks.Meta>()
        refs.forEach { r ->
            val placement = MessageBlocks.planAppend(appended.lastOrNull(), r)
            if (placement.created) appended += placement.meta else appended[appended.lastIndex] = placement.meta
        }
        // 整段重建（导入 / 迁移）
        val rebuilt = MessageBlocks.rebuild("s1", refs)

        assertEquals("两条路径必须给出同一份块表", appended, rebuilt)
    }

    @Test
    fun `重建空会话得到空块表`() {
        assertTrue(MessageBlocks.rebuild("s1", emptyList()).isEmpty())
        assertTrue(MessageBlocks.assignments(emptyList()).isEmpty())
    }

    @Test
    fun `assignments 与 rebuild 的块编号一一对应`() {
        val refs = (0 until 401).map { ref("m$it", it.toLong()) }

        val assignments = MessageBlocks.assignments(refs)

        assertEquals(401, assignments.size)
        assertEquals(0, assignments["m199"])
        assertEquals(1, assignments["m200"])
        assertEquals(2, assignments["m400"])
        MessageBlocks.rebuild("s1", refs).forEach { meta ->
            assertEquals(
                "块 ${meta.blockIndex} 的首条消息编号必须与 assignments 一致",
                meta.blockIndex,
                assignments[meta.firstMessageId]
            )
        }
    }

    // ------------------------------------------------------------------ 稳定序

    @Test
    fun `同毫秒按 id 决先后`() {
        val a = ref("a", 100)
        val b = ref("b", 100)

        assertTrue(MessageBlocks.isAfter(b, a))
        assertFalse(MessageBlocks.isAfter(a, b))
        assertFalse("自己不比自己在之后", MessageBlocks.isAfter(a, a))
        assertTrue(MessageBlocks.isAfter(ref("a", 101), ref("b", 100)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空块元数据不允许存在`() {
        MessageBlocks.Meta("s1", 0, "m1", "m1", 1, 1, 0)
    }
}
