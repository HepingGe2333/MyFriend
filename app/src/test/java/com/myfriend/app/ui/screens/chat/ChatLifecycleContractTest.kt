package com.myfriend.app.ui.screens.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「会话生命周期 / 发送路径 / 导出路径」的源码契约（审计 P1-1、P1-4、P1-5、P1-6）。
 *
 * 这些缺陷的共同点是「并发 + 生命周期」：本项目没有 Robolectric/仪器化单测环境，
 * 所以纯逻辑部分（openSession 的原子语义、世代号、通知策略、分批导出）各自有真单测；
 * 这里钉住的是**接线**：VM 必须调 compare-and-clear、必须 cancelAndJoin、
 * 必须不再 join 抽记忆、导出必须切 IO。接错线时纯逻辑测试仍然是绿的，
 * 只有这层源码断言能拦住。
 */
class ChatLifecycleContractTest {

    private val viewModel get() = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatViewModel.kt")
    private val chatRepo get() = source("src/main/java/com/myfriend/app/data/repo/ChatRepository.kt")
    private val chatScreen get() = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatScreen.kt")
    private val settingsViewModel get() =
        source("src/main/java/com/myfriend/app/ui/screens/settings/SettingsViewModel.kt")

    // ------------------------------------------------------------------ P1-1

    @Test
    fun `onCleared 用 compare-and-clear 清 openSession`() {
        val onCleared = bodyOf(viewModel, "override fun onCleared()")
        assertTrue(
            "旧 VM 销毁必须只在自己仍是当前会话时清空",
            onCleared.contains("chatRepository.clearOpenSession(sessionId)")
        )
        assertFalse(
            "无条件 setOpenSession(null) 会把新会话一起清掉（P1-1 的根因）",
            viewModel.contains("chatRepository.setOpenSession(null)")
        )
    }

    @Test
    fun `OpenSessionTracker 的写入与清空是原子的`() {
        val tracker = source("src/main/java/com/myfriend/app/domain/OpenSessionTracker.kt")
        assertTrue("clearIfOpen 必须做 compare-and-clear", tracker.contains("fun clearIfOpen(sessionId: String): Boolean"))
        assertTrue("读取/比较/写入要在同一把锁里", tracker.contains("synchronized(lock)"))
    }

    @Test
    fun `回复落库后的通知判定走统一策略`() {
        assertTrue(
            "ChatRepository 必须用 ChatNotificationPolicy 判定未读与通知",
            chatRepo.contains("ChatNotificationPolicy.shouldMarkUnread(") &&
                chatRepo.contains("ChatNotificationPolicy.shouldNotify(")
        )
        assertTrue(
            "规则型主动消息也要按同一套语义决定通知",
            source("src/main/java/com/myfriend/app/data/repo/ProactiveRepository.kt")
                .contains("ChatNotificationPolicy.shouldNotify(")
        )
    }

    // ------------------------------------------------------------------ P1-5

    @Test
    fun `发送 重新生成 编辑 都不再等抽记忆`() {
        assertFalse(
            "send() 里 memoryJob?.join() 会让发送键最长卡一次模型往返（P1-5）",
            viewModel.contains("memoryJob?.join()")
        )
        listOf("fun send()", "fun regenerate()", "fun editUserMessage(").forEach { marker ->
            val body = bodyOf(viewModel, marker)
            assertFalse("$marker 不该等待抽记忆", body.contains("memoryJob?.join()"))
            assertFalse("$marker 不该等待抽记忆", body.contains("memoryJob?.join("))
        }
    }

    @Test
    fun `抽记忆仍然是后台任务 失败不影响发送`() {
        assertTrue(
            "抽记忆应当由 Completed 事件在后台启动",
            viewModel.contains("memoryJob = viewModelScope.launch { extractMemory(silent = false) }")
        )
    }

    // ------------------------------------------------------------------ P1-6

    @Test
    fun `清空上下文与删除会话先取消并等生成收尾`() {
        listOf("fun clearContext()", "fun deleteSession()").forEach { marker ->
            val body = bodyOf(viewModel, marker)
            assertTrue("$marker 必须先 cancelAndJoin 在跑的生成", body.contains("streamJob?.cancelAndJoin()"))
        }
    }

    @Test
    fun `仓库在删除与清空时 bump 世代号并取消生成`() {
        listOf("suspend fun clearContext(", "suspend fun deleteSession(").forEach { marker ->
            val body = bodyOf(chatRepo, marker)
            assertTrue("$marker 要 bump 世代号", body.contains("sessionGeneration.bump(sessionId)"))
            assertTrue("$marker 要按 sessionId 取消在跑的生成", body.contains("cancelGeneration(sessionId)"))
        }
        assertTrue(
            "生成任务要按会话登记，才能被删除路径取消",
            chatRepo.contains("activeGenerations") && chatRepo.contains("fun cancelGeneration(sessionId: String)")
        )
    }

    @Test
    fun `收尾写入前校验会话仍存在且世代未变`() {
        assertTrue(
            "成功落库前要校验",
            chatRepo.contains("if (!writeStillValid(sessionId, generation)) return@withContext null")
        )
        listOf("private suspend fun saveInterrupted(", "private suspend fun saveFailed(").forEach { marker ->
            val body = bodyOf(chatRepo, marker)
            assertTrue("$marker 要先 writeStillValid", body.contains("if (!writeStillValid(sessionId, generation)) return"))
        }
    }

    // ------------------------------------------------------------------ P1-2

    @Test
    fun `历史压缩写回前核世代 并在清空时被取消`() {
        val compressor = source("src/main/java/com/myfriend/app/data/repo/HistoryCompressor.kt")
        assertTrue("压缩必须捕获开始时的世代", compressor.contains("val generation = sessionGeneration.current(sessionId)"))
        assertTrue(
            "写回 setHistorySummary 之前必须有 CompressionWriteGuard",
            compressor.indexOf("CompressionWriteGuard.decide(") in 0 until compressor.indexOf(
                "sessionDao.setHistorySummary("
            )
        )
        assertTrue("要有明确的取消点", compressor.contains("fun cancel(sessionId: String)"))
        assertTrue(
            "清空上下文时要取消在跑的压缩",
            bodyOf(chatRepo, "suspend fun clearContext(").contains("historyCompressor.cancel(sessionId)")
        )
    }

    // ------------------------------------------------------------------ P1-4

    @Test
    fun `导出在主线程之外执行`() {
        val exportSession = bodyOf(chatRepo, "suspend fun exportSession(")
        assertTrue("单会话导出要切 IO", exportSession.contains("withContext(Dispatchers.IO)"))
        val exportAll = bodyOf(chatRepo, "suspend fun exportAllChatsTo(")
        assertTrue("全量导出要切 IO 并分批", exportAll.contains("withContext(Dispatchers.IO)"))
        assertTrue("全量导出要走 ExportJsonStream", exportAll.contains("ExportJsonStream {"))
        assertTrue(
            "聊天页写导出文件必须切 IO（ActivityResult 回调在主线程）",
            chatScreen.indexOf("withContext(Dispatchers.IO)") >
                chatScreen.indexOf("val exportLauncher = rememberLauncherForActivityResult(") &&
                chatScreen.indexOf("val exportLauncher = rememberLauncherForActivityResult(") >= 0
        )
    }

    @Test
    fun `导出进行中有状态 失败有资源化提示`() {
        assertTrue("界面状态要有 isExporting", viewModel.contains("val isExporting: Boolean = false"))
        assertTrue(
            "导出期间给进行中文案",
            bodyOf(viewModel, "fun exportChat()").contains("R.string.chat_export_in_progress")
        )
        assertTrue(
            "失败走资源化错误",
            bodyOf(viewModel, "fun exportChat()").contains("R.string.chat_export_failed")
        )
    }

    @Test
    fun `设置页导出全部对话走流式写入`() {
        assertTrue(
            "SettingsViewModel 要调用仓库的流式导出",
            settingsViewModel.contains("chatRepository.exportAllChatsTo { chunk ->")
        )
        assertTrue(
            "每批都要写出去并 flush",
            settingsViewModel.contains("output.write(chunk.toByteArray(Charsets.UTF_8))")
        )
        assertFalse(
            "不要再把整份导出字符串拿回界面",
            settingsViewModel.contains("SettingsEvent.ExportReady(it)")
        )
    }

    // ------------------------------------------------------------------ P1-3

    @Test
    fun `旧的手写清空清单已经收进 DataEraser`() {
        assertFalse(
            "ChatRepository 里不该再手写 messageDao.deleteAll 的清空清单",
            bodyOf(chatRepo, "suspend fun clearAllChatData()").contains("messageDao.deleteAll()")
        )
        assertFalse(
            "SettingsViewModel 不再自己清媒体目录（与删表共用一份清单）",
            settingsViewModel.contains("MediaStorage.clear(context)")
        )
        assertTrue(
            "备份替换不再手写清空清单",
            !bodyOf(source("src/main/java/com/myfriend/app/data/repo/BackupRepository.kt"), "suspend fun importBundle(")
                .contains("messageDao.deleteAll()")
        )
    }

    /** 从 [marker] 起截到本类下一个成员声明之前（够用来断言「这个函数体里有什么」）。 */
    private fun bodyOf(text: String, marker: String): String {
        val start = text.indexOf(marker)
        require(start >= 0) { "找不到 $marker" }
        val rest = text.substring(start + marker.length)
        val endTokens = listOf(
            "\n    private suspend fun ",
            "\n    private fun ",
            "\n    suspend fun ",
            "\n    override fun ",
            "\n    fun "
        )
        val end = endTokens.map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    private fun source(relative: String): String {
        val candidates = listOf(File("."), File(".."))
        val root = candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
        return File(root, relative).readText()
    }
}
