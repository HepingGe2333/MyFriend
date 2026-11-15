package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区块分页状态机（规范 `spec/messages-blocks/README.md` §3）的单测。
 *
 * 覆盖的边界：
 * - 首屏**只有一块**（最新块），空会话也算定位过；
 * - 向上 / 向下各加载一块（向下是 0.12 新增能力）；
 * - 块内有删除之后的**短块**（加载进来之后状态不变形，边界仍按块表重算）；
 * - **边界块**：最早一块 / 最新一块；
 * - 编号**空洞**：上一块不一定是 `当前 - 1`；
 * - 并发触发**去重**：加载中再触发不会再发一次查询；
 * - 滚动锚定：数据是「已加载块集合」决定的，向前插入不会改变既有块的编号。
 */
class MessageBlockPagingTest {

    // ------------------------------------------------------------------ 首屏

    @Test
    fun `首屏只加载最新一块`() {
        val state = MessageBlockPaging.onInitial(lastBlockIndex = 7, firstBlockIndex = 0)

        assertEquals("首屏就是一页 = 一块", listOf(7), state.blocks)
        assertTrue("上面还有更早的块", state.hasMoreOlder)
        assertFalse("首屏就在最新块", state.hasMoreNewer)
        assertTrue(state.initialized)
        assertEquals(7, state.newestBlock)
        assertEquals(7, state.oldestBlock)
    }

    @Test
    fun `只有一个块的会话 首屏就已经到头`() {
        val state = MessageBlockPaging.onInitial(lastBlockIndex = 0, firstBlockIndex = 0)

        assertEquals(listOf(0), state.blocks)
        assertFalse(state.hasMoreOlder)
        assertFalse(state.hasMoreNewer)
    }

    @Test
    fun `空会话没有块 也算定位完成`() {
        val state = MessageBlockPaging.onInitial(lastBlockIndex = null, firstBlockIndex = null)

        assertTrue(state.blocks.isEmpty())
        assertTrue(state.initialized)
        assertFalse(state.hasMoreOlder)
        assertFalse(state.hasMoreNewer)
        assertNull(state.oldestBlock)
        assertNull(state.newestBlock)
    }

    @Test
    fun `首屏边界按块表算 不是按 latest - 1`() {
        // 块编号有空洞：最早一块是 3、最新是 9。首屏在 9，上面「还有更早的」。
        val state = MessageBlockPaging.onInitial(lastBlockIndex = 9, firstBlockIndex = 3)

        assertEquals("首屏加载的是最新块 9（不是 first）", 9, state.oldestBlock)
        assertTrue("first(3) < last(9)，说明确实还有更早的块", state.hasMoreOlder)
    }

    // ------------------------------------------------------------------ 向上

    @Test
    fun `滚到顶部加载上一块`() {
        val start = MessageBlockPaging.onInitial(7, 0)

        val (loading, started) = MessageBlockPaging.beginLoadOlder(start)
        assertTrue(started)
        assertTrue("加载中要挡住重复触发", loading.loadingOlder)

        val loaded = MessageBlockPaging.onOlderLoaded(loading, block = 6)
        assertEquals(listOf(6, 7), loaded.blocks)
        assertFalse(loaded.loadingOlder)
        assertTrue(loaded.hasMoreOlder)
    }

    @Test
    fun `上一块不一定是当前减一 编号空洞要按实际块走`() {
        val start = MessageBlockPaging.onInitial(9, 3)

        val (loading, _) = MessageBlockPaging.beginLoadOlder(start)
        val loaded = MessageBlockPaging.onOlderLoaded(loading, block = 5)

        assertEquals(listOf(5, 9), loaded.blocks)
        assertTrue("中间还有块（编号 3），继续可加载", loaded.hasMoreOlder)
    }

    @Test
    fun `加载到最早一块时 hasMoreOlder 归零`() {
        val start = MessageBlockPaging.State(blocks = listOf(1), initialized = true, hasMoreOlder = true)

        val (loading, _) = MessageBlockPaging.beginLoadOlder(start)
        val done = MessageBlockPaging.onOlderLoaded(loading, block = 0)
        assertEquals(listOf(0, 1), done.blocks)

        // 再问一次：真的没有更早的块了（Repository 查出来是 null）。
        val (again, started) = MessageBlockPaging.beginLoadOlder(done)
        assertTrue(started)
        val finished = MessageBlockPaging.onOlderLoaded(again, block = null)
        assertFalse("最早一块：不能再往上加载", finished.hasMoreOlder)
    }

    // ------------------------------------------------------------------ 向下（0.12 新增）

    @Test
    fun `滚到底部加载下一块`() {
        val start = MessageBlockPaging.State(
            blocks = listOf(3),
            hasMoreNewer = true,
            initialized = true
        )

        val (loading, started) = MessageBlockPaging.beginLoadNewer(start)
        assertTrue(started)
        assertTrue(loading.loadingNewer)

        val loaded = MessageBlockPaging.onNewerLoaded(loading, block = 4)
        assertEquals(listOf(3, 4), loaded.blocks)
        assertFalse(loaded.loadingNewer)
        assertTrue(loaded.hasMoreNewer)
    }

    @Test
    fun `加载到最新块之后 hasMoreNewer 归零`() {
        val start = MessageBlockPaging.State(
            blocks = listOf(3, 4),
            hasMoreNewer = true,
            initialized = true
        )

        val (loading, _) = MessageBlockPaging.beginLoadNewer(start)
        val done = MessageBlockPaging.onNewerLoaded(loading, block = null)

        assertEquals(listOf(3, 4), done.blocks)
        assertFalse("已经到最新块", done.hasMoreNewer)
        assertFalse("到最新块之后不再触发", MessageBlockPaging.beginLoadNewer(done).second)
    }

    // ------------------------------------------------------------------ 短块 / 删除

    @Test
    fun `块内有删除后的短块 不影响分页状态`() {
        // 3 号块原来 200 条，删到只剩 2 条；加载它只是「块里行少了」，块的语义不变。
        val start = MessageBlockPaging.State(blocks = listOf(4), hasMoreOlder = true, initialized = true)

        val (loading, _) = MessageBlockPaging.beginLoadOlder(start)
        val loaded = MessageBlockPaging.onOlderLoaded(loading, block = 3)

        assertEquals(listOf(3, 4), loaded.blocks)
        assertTrue(loaded.hasMoreOlder)
    }

    @Test
    fun `当前块被删空时 边界重算仍然可行`() {
        // 8 号块被删空：已加载集合里它还留着，但两侧边界按块表重算之后仍然正确。
        val state = MessageBlockPaging.State(blocks = listOf(8), initialized = true)

        val updated = MessageBlockPaging.onBoundaries(state, hasMoreOlder = true, hasMoreNewer = false)

        assertEquals(listOf(8), updated.blocks)
        assertTrue(updated.hasMoreOlder)
        assertFalse(updated.hasMoreNewer)
    }

    // ------------------------------------------------------------------ 边界重算（后台写入）

    @Test
    fun `后台写入让下面出现新块时 hasMoreNewer 会重新亮起`() {
        val jumped = MessageBlockPaging.onJump(targetBlock = 2, hasMoreOlder = true, hasMoreNewer = false)
        assertFalse(jumped.hasMoreNewer)

        val afterNewBlock = MessageBlockPaging.onBoundaries(jumped, hasMoreOlder = true, hasMoreNewer = true)

        assertTrue("新块出现在下面之后，界面上「更新消息」入口要能重新出现", afterNewBlock.hasMoreNewer)
        assertTrue("重算不该动 hasMoreOlder", afterNewBlock.hasMoreOlder)
        assertEquals("重算不该动已加载的块", jumped.blocks, afterNewBlock.blocks)
    }

    // ------------------------------------------------------------------ 并发去重

    @Test
    fun `加载中再次触发不会重复查询`() {
        val start = MessageBlockPaging.State(blocks = listOf(5), hasMoreOlder = true, hasMoreNewer = true, initialized = true)

        val (first, startedFirst) = MessageBlockPaging.beginLoadOlder(start)
        assertTrue(startedFirst)
        val (_, startedAgain) = MessageBlockPaging.beginLoadOlder(first)
        assertFalse("向上加载中：第二次直接忽略", startedAgain)

        // 两个方向各自去重，互不影响。
        val (newer, startedNewer) = MessageBlockPaging.beginLoadNewer(first)
        assertTrue("向下加载不受向上加载影响", startedNewer)
        assertFalse("向下加载中：第二次直接忽略", MessageBlockPaging.beginLoadNewer(newer).second)
    }

    @Test
    fun `没有更多时 begin 直接返回 false`() {
        val state = MessageBlockPaging.State(blocks = listOf(0), hasMoreOlder = false, hasMoreNewer = false, initialized = true)

        assertFalse(MessageBlockPaging.beginLoadOlder(state).second)
        assertFalse(MessageBlockPaging.beginLoadNewer(state).second)
    }

    @Test
    fun `还没定位过（未初始化）时不允许加载`() {
        val state = MessageBlockPaging.State()

        assertFalse(MessageBlockPaging.beginLoadOlder(state).second)
        assertFalse(MessageBlockPaging.beginLoadNewer(state).second)
    }

    @Test
    fun `查询失败只收掉转圈 不把失败当成到底`() {
        val state = MessageBlockPaging.State(blocks = listOf(5), hasMoreOlder = true, hasMoreNewer = true, initialized = true)

        val (loadingOlder, _) = MessageBlockPaging.beginLoadOlder(state)
        val failed = MessageBlockPaging.onLoadFailed(loadingOlder, older = true)
        assertFalse(failed.loadingOlder)
        assertTrue("失败之后还能再试", failed.canLoadOlder)

        val (loadingNewer, _) = MessageBlockPaging.beginLoadNewer(state)
        val failedNewer = MessageBlockPaging.onLoadFailed(loadingNewer, older = false)
        assertFalse(failedNewer.loadingNewer)
        assertTrue(failedNewer.canLoadNewer)
    }

    // ------------------------------------------------------------------ 定位 / 回到最新 / 滚动锚定

    @Test
    fun `从检索定位只加载目标那一块`() {
        val state = MessageBlockPaging.onJump(targetBlock = 2, hasMoreOlder = true, hasMoreNewer = true)

        assertEquals(listOf(2), state.blocks)
        assertTrue(state.hasMoreOlder)
        assertTrue("下面还有更新的块：可以往下加载，不必「跳回最新」", state.hasMoreNewer)
    }

    @Test
    fun `回到最新只保留最新一块`() {
        val jumped = MessageBlockPaging.onJump(targetBlock = 2, hasMoreOlder = true, hasMoreNewer = true)

        val latest = MessageBlockPaging.onLatest(jumped, lastBlockIndex = 9, firstBlockIndex = 0)

        assertEquals(listOf(9), latest.blocks)
        assertTrue(latest.hasMoreOlder)
        assertFalse(latest.hasMoreNewer)
    }

    @Test
    fun `判断是否已经停在最新块`() {
        val latest = MessageBlockPaging.onInitial(9, 0)
        val older = MessageBlockPaging.State(blocks = listOf(2), initialized = true)

        assertTrue(MessageBlockPaging.isAtNewestBlock(latest, 9))
        assertFalse("停在旧块里：发新消息之前要先回到最新", MessageBlockPaging.isAtNewestBlock(older, 9))
        assertTrue("空会话没有「不在最新」这回事", MessageBlockPaging.isAtNewestBlock(older, null))
    }

    @Test
    fun `向前加载是追加块集合 而不是挪动既有块编号（滚动锚定）`() {
        val before = MessageBlockPaging.State(blocks = listOf(4), initialized = true, hasMoreOlder = true)

        var state = before
        repeat(3) { index ->
            val (loading, started) = MessageBlockPaging.beginLoadOlder(state)
            assertTrue(started)
            state = MessageBlockPaging.onOlderLoaded(loading, block = 3 - index)
        }

        assertEquals("既有块 4 的编号没有变——列表里那条消息的 key 也没变，视口可以按 key 恢复", 4, state.newestBlock)
        assertEquals(listOf(1, 2, 3, 4), state.blocks)
        assertEquals("块编号升序（列表拼接顺序）", state.blocks.sorted(), state.blocks)
    }

    @Test
    fun `contains 用来短路已经加载过的块`() {
        val state = MessageBlockPaging.onInitial(5, 0)

        assertTrue(MessageBlockPaging.contains(state, 5))
        assertFalse(MessageBlockPaging.contains(state, 4))
    }
}
