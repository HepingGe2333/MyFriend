package com.myfriend.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 界面与文件生命周期的**接线**契约（0.11.0 P2 收尾：审计 P2-1 / P2-2 / P2-10 / P2-11 / P2-14）。
 *
 * 纯逻辑在 `AvatarDraftFilesTest` / `ReadReceiptTest` / `MessagePagingTest`；
 * 这里只保证「该调的地方真的调了」——比如 onCleared 里忘了删文件、气泡里又写回 O(n) 扫描。
 */
class P2LifecycleContractTest {

    private val chatVm get() = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatViewModel.kt")
    private val chatScreen get() = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatScreen.kt")
    private val editorVm get() = source("src/main/java/com/myfriend/app/ui/screens/persona/PersonaEditorViewModel.kt")
    private val editorScreen get() = source("src/main/java/com/myfriend/app/ui/screens/persona/PersonaEditorScreen.kt")
    private val proactiveScreen get() = source("src/main/java/com/myfriend/app/ui/screens/proactive/ProactiveScreen.kt")

    // ------------------------------------------------------------------ 审计 P2-1

    @Test
    fun `退出聊天页时删掉选了但没发出去的图`() {
        val onCleared = bodyOf(chatVm, "override fun onCleared()")
        assertTrue(
            "onCleared 必须回收草稿里的图片文件，否则每选一次退一次就永远多一个孤儿",
            onCleared.contains("MediaStorage.deleteAttachments(context, ephemeral.value.images)")
        )
        val switch = bodyOf(chatVm, "fun switchPersona(")
        assertTrue(
            "切人格时上一段对话的草稿图也要回收",
            switch.contains("MediaStorage.deleteAttachments(context, abandoned)")
        )
    }

    // ------------------------------------------------------------------ 审计 P2-2

    @Test
    fun `头像草稿文件在连选 取消与销毁时回收`() {
        assertTrue(
            "选新一张时删掉上一张草稿",
            bodyOf(editorVm, "fun setAvatarUri(").contains("AvatarDraftFiles.onPicked(")
        )
        assertTrue(
            "取消编辑时删掉本次新建的全部草稿",
            bodyOf(editorVm, "fun discardDraftFiles(").contains("AvatarDraftFiles.onDiscard(")
        )
        assertTrue(
            "保存成功后只留被采纳的那张",
            bodyOf(editorVm, "fun save(").contains("AvatarDraftFiles.onSaved(")
        )
        assertTrue("ViewModel 销毁兜底", bodyOf(editorVm, "override fun onCleared()").contains("discardDraftFiles()"))
        assertTrue(
            "返回时（未改动）也要清",
            between(editorScreen, "fun requestBack()", "BackHandler(enabled = true)")
                .contains("viewModel.discardDraftFiles()")
        )
        assertTrue("放弃确认时也要清", editorScreen.contains("viewModel.discardDraftFiles()\n                onBack()"))
    }

    // ------------------------------------------------------------------ 审计 P2-10

    @Test
    fun `已读判定不再对每条可见消息扫整个窗口`() {
        assertFalse(
            "气泡里不能再对每条用户消息 any{} 扫整个窗口",
            chatScreen.contains("state.messages.any { later ->")
        )
        assertTrue(
            "改用 ViewModel 一次算好的 lastAssistantAt",
            chatScreen.contains("lastAssistantAt = state.lastAssistantAt")
        )
        assertTrue(
            "ViewModel 只算一遍「最后一条可计入的助手消息」",
            chatVm.contains("ReadReceipt.latestAssistantAt(")
        )
    }

    // ------------------------------------------------------------------ 审计 P2-11

    @Test
    fun `主动页的时钟格式跟界面语言走`() {
        assertFalse(
            "不该再有硬编码的 HH:mm（英文界面会一直是 24 小时制）",
            proactiveScreen.contains("ofPattern(\"HH:mm\")")
        )
        assertTrue("用共用的 formatClock（读 common_clock_pattern）", proactiveScreen.contains("formatClock(context,"))
        val zh = source("src/main/res/values/strings.xml")
        val en = source("src/main/res/values-en/strings.xml")
        assertTrue("中文是 24 小时制", zh.contains("<string name=\"common_clock_pattern\">HH:mm</string>"))
        assertTrue("英文是 12 小时制（带 AM/PM）", en.contains("<string name=\"common_clock_pattern\">h:mm a</string>"))
    }

    // ------------------------------------------------------------------ 审计 P2-14

    @Test
    fun `分页滑窗时提示上面还有更早的消息`() {
        assertTrue(
            "首屏窗口每次变化都要重算 hasMoreOlder",
            chatVm.contains("MessagePaging.onFirstWindow(it, rows.size)")
        )
        assertTrue("顶部那一行就是这条提示", chatScreen.contains("R.string.chat_load_earlier"))
    }

    // ------------------------------------------------------------------ 工具

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(projectRoot(), relative).readText()

    private fun between(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "找不到 $startMarker" }
        val end = text.indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "找不到 $endMarker" }
        return text.substring(start, end)
    }

    private fun bodyOf(text: String, marker: String): String {
        val start = text.indexOf(marker)
        require(start >= 0) { "找不到 $marker" }
        val rest = text.substring(start + marker.length)
        val endTokens = listOf(
            "\n    private suspend fun ",
            "\n    private fun ",
            "\n    private val ",
            "\n    suspend fun ",
            "\n    override fun ",
            "\n    fun "
        )
        val end = endTokens.map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }
}
