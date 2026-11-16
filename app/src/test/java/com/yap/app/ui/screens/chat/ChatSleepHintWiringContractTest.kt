package com.yap.app.ui.screens.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 睡眠提示行的**接线**（0.12 续 7）。
 *
 * 纯逻辑在 `ChatSleepHint`（有真单测），但「判定对了、界面没显示」这种缺陷只有源码契约能拦住：
 * 本工程没有 Robolectric，聊天页与 `ChatViewModel` 都依赖 `Context`，无法在纯 JVM 里实例化。
 *
 * 这里钉三件事：
 * 1. `ChatRepository` 因睡眠静默时把原因带出来（`SilentReason.ASLEEP` / `DND`），
 *    免打扰不能被当成「发几条就能叫醒」；
 * 2. `ChatViewModel` 把原因落到状态里，并在开始出字 / 收到回复 / 停止 / 失败时清掉；
 * 3. `ChatScreen` 真的把它画在输入框上方（不是气泡）。
 */
class ChatSleepHintWiringContractTest {

    private fun source(relative: String) =
        File("src/main/java/com/yap/app/$relative").also {
            assertTrue("找不到源文件：${it.path}", it.isFile)
        }.readText()

    private val chatRepo = source("data/repo/ChatRepository.kt")
    private val viewModel = source("ui/screens/chat/ChatViewModel.kt")
    private val chatScreen = source("ui/screens/chat/ChatScreen.kt")

    @Test
    fun `睡眠静默会带上原因`() {
        assertTrue(
            "睡着了要带 SilentReason.ASLEEP（界面据此显示提示）",
            chatRepo.contains("GenerationEvent.Silent(") && chatRepo.contains("SilentReason.ASLEEP")
        )
        assertTrue(
            "免打扰要带 SilentReason.DND：叫多少条都不会醒，不能提示「发几条就能叫醒」",
            chatRepo.contains("SilentReason.DND")
        )
        assertTrue(
            "defer_reply 与睡眠无关，也该带原因（否则界面无法区分）",
            chatRepo.contains("SilentReason.DEFERRED")
        )
    }

    @Test
    fun `ViewModel 落状态并在新一轮清掉`() {
        assertTrue(
            "Silent 事件要把原因写进 ephemeral",
            viewModel.contains("silentReason = event.reason")
        )
        assertTrue(
            "开始出字 / 收到回复 / 停止 / 失败之后不该继续显示「他睡着了」",
            viewModel.windowedContains("silentReason = null", minimum = 3)
        )
        assertTrue(
            "提示文案由纯判定 ChatSleepHint 决定",
            viewModel.contains("ChatSleepHint.visible(")
        )
        assertTrue(
            "醒来的钟点来自人格作息，不能写死",
            viewModel.contains("SleepPolicy.wakeClockText(")
        )
    }

    @Test
    fun `聊天页把提示画在输入框上方`() {
        val composer = viewModel.let { chatScreen.substringAfter("private fun Composer(") }
        assertTrue(
            "Composer 里要有这一行提示",
            composer.contains("state.sleepHint")
        )
        assertTrue(
            "提示文案走资源（中英文各一份）",
            viewModel.contains("R.string.chat_sleep_hint")
        )
    }

    private fun String.windowedContains(needle: String, minimum: Int): Boolean =
        split(needle).size - 1 >= minimum
}
