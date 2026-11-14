package com.myfriend.app.ui.screens.chat

import com.myfriend.app.domain.MessageJump
import com.myfriend.app.domain.MessagePaging
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「从搜索跳进有界窗口之后，发送/重新生成/新消息必须回到可见范围」的契约（审计 P1-1 / 0.11.1）。
 *
 * 缺陷：定位窗口是 `LIMIT rows` 的固定区间（例如 200 条会话跳到第 100 条 → 窗口 40..199，
 * `hasMoreNewer=false`）。此后 `send()` 只写库 + `startGeneration`，既不清窗口也不调
 * `showLatest()`，于是：
 * - 自己刚发的那条 `createdAt` 落在窗口外 → 列表里看不到（用户感知「发出去的话消失了」）；
 * - 助手回复同样在窗口外；
 * - `hasMoreNewer` 只在定位时算一次，界面连「更新的消息」入口都没有。
 *
 * 本项目没有 Robolectric/Room 单测环境，所以这里分两层：
 * 1. **纯状态机**（`MessagePaging` / `MessageJump` 是真的生产代码）模拟「定位 → 新消息到达」；
 * 2. **接线**（源码断言）：`send` / `regenerate` / `editUserMessage` 写库前必须退出定位窗口；
 *    `messageFlow` 每次窗口变化都要重算 `hasMoreNewer`。源码断言拦不住运行时分支，
 *    它只证明「调用点在、顺序对」——这是本项目现有测试体系的边界。
 */
class ChatJumpWindowContractTest {

    private val page = MessagePaging.PAGE_SIZE

    @Test
    fun `定位窗口里发送前会回到最新一屏`() {
        val vm = viewModel()
        listOf("fun send()", "fun regenerate()", "fun editUserMessage(").forEach { marker ->
            val body = bodyOf(vm, marker)
            val latestIndex = body.indexOf("leaveJumpWindowForNewMessage()")
            assertTrue("$marker 必须先退出定位窗口（否则刚写的消息落在窗口外）", latestIndex >= 0)
            // 发送/生成都发生在仓库调用之前：回到最新必须在写库之前生效。
            val writeIndex = listOf(
                "chatRepository.sendUserMessage(",
                "chatRepository.prepareRegeneration(",
                "chatRepository.editUserMessage("
            ).map { body.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
            assertTrue("$marker 里应当有写库调用", writeIndex >= 0)
            assertTrue("$marker：showLatest 必须在写库之前", latestIndex < writeIndex)
        }
    }

    @Test
    fun `退出定位窗口会清掉游标与窗口 并复位分页状态`() {
        val vm = viewModel()
        val showLatest = bodyOf(vm, "fun showLatest()")
        assertTrue("要清掉有界窗口", showLatest.contains("jumpWindow.value = null"))
        assertTrue("要清掉游标", showLatest.contains("sinceCursor.value = null"))
        assertTrue("要复位分页状态", showLatest.contains("MessagePaging.onLatest(it)"))
        val leave = bodyOf(vm, "private fun leaveJumpWindowForNewMessage()")
        assertTrue("只在真的处于定位窗口时才复位", leave.contains("if (jumpWindow.value != null) showLatest()"))
    }

    @Test
    fun `窗口数据变化时重算 hasMoreNewer`() {
        val vm = viewModel()
        val flow = vm.substringAfter("private val messageFlow", "")
        assertTrue(
            "有界窗口分支必须每次都用最新游标重算",
            flow.contains("MessagePaging.onWindowRows(it, latest, windowEnd)")
        )
        assertTrue("要取会话最新一条的游标", flow.contains("chatRepository.latestMessageCursor(sessionId)"))
        assertFalse(
            "不能只在首屏算 hasMoreOlder 而把窗口的 newer 交给定位计划",
            flow.contains("if (jumpWindow.value == null && sinceCursor.value == null)")
        )
    }

    @Test
    fun `仓库提供最新游标查询`() {
        val repo = source("src/main/java/com/myfriend/app/data/repo/ChatRepository.kt")
        assertTrue(repo.contains("suspend fun latestMessageCursor(sessionId: String)"))
        assertTrue("走 DAO 的 latestInSession（不多读行）", repo.contains("messageDao.latestInSession(sessionId)?.let"))
    }

    @Test
    fun `界面在 hasMoreNewer 为真时渲染回到最新入口`() {
        val screen = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatScreen.kt")
        assertTrue("要有入口", screen.contains("if (state.hasMoreNewer)"))
        assertTrue("点了回到最新一屏", screen.contains("LoadNewerRow(onClick = onShowLatest)"))
    }

    /**
     * 纯逻辑回归：定位到「窗口刚好覆盖到末尾」之后，后台来一条消息，状态必须变成「还有更新的」。
     * 这条不依赖任何 UI/DB，是这段修复真正的行为断言。
     */
    @Test
    fun `定位到末尾后来消息 状态机给出回到最新`() {
        val plan = MessageJump.plan(total = 200, beforeTarget = 100, pageSize = page)
        assertFalse(plan.hasMoreNewer)
        val window = MessagePaging.Window(
            start = MessagePaging.Cursor(40L, "m40"),
            rows = plan.windowRows,
            hasMoreOlder = plan.hasMoreOlder,
            hasMoreNewer = plan.hasMoreNewer
        )
        var state = MessagePaging.onJumped(MessagePaging.State(), window)
        assertFalse(state.hasMoreNewer)

        state = MessagePaging.onWindowRows(
            state,
            latest = MessagePaging.Cursor(9_999L, "m-new"),
            windowEnd = MessagePaging.Cursor(199L, "m199")
        )
        assertTrue(state.hasMoreNewer)

        // 用户点了「回到最新」：状态整体复位，之后的窗口按首屏规则重算。
        state = MessagePaging.onLatest(state)
        assertFalse(state.hasMoreNewer)
        assertTrue(state.hasMoreOlder)
    }

    private fun viewModel(): String =
        source("src/main/java/com/myfriend/app/ui/screens/chat/ChatViewModel.kt")

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到 app 模块目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(projectRoot(), relative).readText()

    private fun bodyOf(text: String, marker: String): String {
        val start = text.indexOf(marker)
        require(start >= 0) { "找不到 $marker" }
        val rest = text.substring(start + marker.length)
        val ends = listOf(
            "\n    private suspend fun ",
            "\n    private fun ",
            "\n    suspend fun ",
            "\n    override fun ",
            "\n    fun "
        )
        val end = ends.map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }
}
