package com.yap.app.ui.screens.chat

import com.yap.app.domain.MessageBlockPaging
import com.yap.app.domain.MessageBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「从检索跳进很久以前的那段话」的窗口契约（0.12：区块版）。
 *
 * 0.11 的版本守的是「有界条数窗口 + 只能回到最新」。区块分页之后语义变了：
 *
 * 1. 定位 = 换成**目标所在的那一块**（块编号落库，一次查询就够，不再算 COUNT/下标）；
 * 2. 下面还有更新的块时，界面给的是**加载入口**（`onLoadNewer`），不是「只能跳回最新」；
 * 3. 后台写入让下面出现新块时，`hasMoreNewer` 要能重新亮起（不能只在定位那一刻算一次）。
 *
 * 这里既跑纯状态机（`MessageBlockPaging` 是真生产代码），也读源码断言接线。
 */
class ChatJumpWindowContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File("."), File(".."))
        val root = candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
        return File(root, relative).readText()
    }

    private val vm get() = source("src/main/java/com/yap/app/ui/screens/chat/ChatViewModel.kt")
    private val screen get() = source("src/main/java/com/yap/app/ui/screens/chat/ChatScreen.kt")

    /** 从 marker 开始截一段（到下一个成员声明之前）。 */
    private fun body(text: String, marker: String): String {
        val index = text.indexOf(marker)
        assertTrue("找不到 $marker", index >= 0)
        val rest = text.substring(index + marker.length)
        val ends = listOf(
            "\n    private suspend fun ",
            "\n    private fun ",
            "\n    suspend fun ",
            "\n    fun ",
            "\n    override fun "
        )
        val end = ends.map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    // ------------------------------------------------------------------ 纯状态机

    @Test
    fun `定位只加载目标块 并记下两侧还可以加载`() {
        val state = MessageBlockPaging.onJump(targetBlock = 4, hasMoreOlder = true, hasMoreNewer = true)

        assertEquals(listOf(4), state.blocks)
        assertTrue(state.hasMoreOlder)
        assertTrue("下面还有更新的块：界面给加载入口，而不是「只能回到最新」", state.hasMoreNewer)
        assertEquals("块内最多一条 BLOCK_SIZE", 200, MessageBlocks.BLOCK_SIZE)
    }

    @Test
    fun `定位之后来了新块 hasMoreNewer 会重新亮起`() {
        // 定位那一刻：目标正好在最新块，下面没东西。
        val jumped = MessageBlockPaging.onJump(targetBlock = 9, hasMoreOlder = true, hasMoreNewer = false)
        assertFalse(jumped.hasMoreNewer)

        // 之后后台写入 / 自己发消息，块表多了一块 10。
        val afterNewBlock = MessageBlockPaging.onBoundaries(jumped, hasMoreOlder = true, hasMoreNewer = true)
        assertTrue("新块出现之后必须能继续往下加载", afterNewBlock.hasMoreNewer)
        assertEquals("重算边界不该动已加载的块", listOf(9), afterNewBlock.blocks)
        assertTrue("重算边界不该动 hasMoreOlder", afterNewBlock.hasMoreOlder)
    }

    @Test
    fun `一直往下加载能走到最新块`() {
        // 会话最新块是 6，从 2 号块开始往下走。
        val lastBlock = 6
        var state = MessageBlockPaging.onJump(targetBlock = 2, hasMoreOlder = true, hasMoreNewer = true)

        while (state.hasMoreNewer) {
            val (loading, started) = MessageBlockPaging.beginLoadNewer(state)
            assertTrue(started)
            val newest = loading.newestBlock ?: error("已加载集合里必须有块")
            val following = if (newest + 1 <= lastBlock) newest + 1 else null
            state = MessageBlockPaging.onNewerLoaded(loading, following)
        }

        assertFalse("走到最新块之后不能再往下", state.hasMoreNewer)
        assertEquals(listOf(2, 3, 4, 5, 6), state.blocks)
        assertTrue("向上仍然可以继续", state.hasMoreOlder)
    }

    @Test
    fun `回到最新只保留最新一块`() {
        val jumped = MessageBlockPaging.onJump(targetBlock = 2, hasMoreOlder = true, hasMoreNewer = true)

        val latest = MessageBlockPaging.onLatest(jumped, lastBlockIndex = 9, firstBlockIndex = 0)

        assertEquals(listOf(9), latest.blocks)
        assertFalse(latest.hasMoreNewer)
        assertTrue(latest.hasMoreOlder)
    }

    // ------------------------------------------------------------------ 源码接线

    @Test
    fun `定位走块 不走条数窗口`() {
        val jump = body(vm, "private fun startJumpToTarget()")
        assertTrue("定位按消息的块编号", jump.contains("chatRepository.blockContainingMessage(sessionId, target)"))
        assertTrue("换窗口用 onJump", jump.contains("MessageBlockPaging.onJump("))
        assertFalse("不再按条数算窗口", jump.contains("MessageJump.plan("))
        assertFalse("不再用 COUNT 算窗口起点", jump.contains("countBefore("))
        assertFalse("旧的条数窗口类型已退役", jump.contains("MessagePaging.Window"))
    }

    @Test
    fun `数据变化时重算两侧边界而不是只算一次`() {
        assertTrue("每次块数据回来都重算", vm.contains("refreshBlockBoundaries(rows)"))
        assertTrue(
            "最新块编号变化时重算（会话从空变有、最新块被删空）",
            vm.contains("chatRepository.observeLastBlockIndex(sessionId)")
        )
        assertTrue(
            "边界查询用块表的相邻块，而不是 latest - 1",
            vm.contains("chatRepository.blocksAround(sessionId, oldest).older") &&
                vm.contains("chatRepository.blocksAround(sessionId, newest).newer")
        )
    }

    @Test
    fun `界面在下面还有块时渲染加载入口 并且能自动加载`() {
        assertTrue("要有入口", screen.contains("if (state.hasMoreNewer || state.loadingNewer)"))
        assertTrue(
            "入口是加载下一块（点了会「跟着往下」并一路走到最新块）",
            screen.contains("LoadNewerRow(") &&
                screen.contains("onLoadNewer()") &&
                screen.contains("followNewer = true")
        )
        assertTrue(
            "滚到底自动加载",
            screen.contains("if (state.hasMoreNewer && !state.loadingNewer && isAtBottom) onLoadNewer()")
        )
        assertTrue("回到底部按钮在还有未加载块时直接回到最新", screen.contains("if (state.hasMoreNewer)"))
        assertTrue("点了回到最新", screen.contains("onShowLatest"))
    }

    @Test
    fun `发新消息之前会回到最新块`() {
        val save = body(vm, "private suspend fun leaveJumpWindowForNewMessage()")
        assertTrue("判断是否已经在最新块", save.contains("MessageBlockPaging.isAtNewestBlock(window.value, last)"))
        assertTrue("不在最新块就先回到最新", save.contains("MessageBlockPaging.onLatest("))
        assertTrue("发送路径调用它", vm.contains("leaveJumpWindowForNewMessage()"))
    }
}
