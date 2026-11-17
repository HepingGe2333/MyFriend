package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `stay_silent` 的接线契约（`spec/mood/README.md` §4）。
 *
 * 规范里有三件必须同时成立的事：**这一轮不产生 assistant 消息**、**只把用户消息标成已读**、
 * **连续使用受限制**。前两件发生在聊天生成流程内部（`ChatRepository`），JVM 单测没有 Room，
 * 没法真的跑一遍；但它们是「有没有实现」而不是「实现得好不好」，用源码契约钉住是够的
 * （与仓库里 `*ContractTest` 的一贯做法一致）。
 *
 * 第三条（连续上限）与工具注册表在 [StaySilentTest] 里逐条测；这里只确认接线没有接歪。
 */
class StaySilentContractTest {

    private fun appRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到 app 模块根目录，当前工作目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    private val chatRepository by lazy { source("src/main/java/com/yap/app/data/repo/ChatRepository.kt") }
    private val toolExecutor by lazy { source("src/main/java/com/yap/app/data/repo/ToolExecutor.kt") }
    private val settingsRepository by lazy { source("src/main/java/com/yap/app/data/prefs/SettingsRepository.kt") }
    private val models by lazy { source("src/main/java/com/yap/app/domain/Models.kt") }

    @Test
    fun `聊天生成流程在 stay_silent 时直接结束 不落 assistant 消息`() {
        val branch = chatRepository.indexOf("if (silenced) {")
        assertTrue("ChatRepository 里没有 stay_silent 的结束分支", branch >= 0)
        val body = chatRepository.substring(branch, minOf(chatRepository.length, branch + 700))
        assertTrue("这一轮必须把用户消息标已读", body.contains("markUserMessagesRead"))
        assertTrue("必须告诉界面是「已读不回」", body.contains("SilentReason.STAY_SILENT"))
        assertTrue("结束这一轮，不再让模型补话", body.contains("return@flow"))

        // 真正的写入助手消息发生在这里；stay_silent 分支必须在它之前就返回。
        val saveAssistant = chatRepository.indexOf("val saved = withContext(NonCancellable)")
        assertTrue("没找到助手消息的写入点", saveAssistant >= 0)
        assertTrue("stay_silent 必须在写入助手消息之前返回", branch < saveAssistant)
    }

    @Test
    fun `stay_silent 不写念头 与 defer_reply 的语义保持分开`() {
        val branch = chatRepository.substring(
            chatRepository.indexOf("if (silenced) {"),
            chatRepository.indexOf("if (silenced) {") + 700
        )
        assertFalse("stay_silent 不该留下到点会想起来的念头", branch.contains("intentRepository.plan"))
        assertFalse("stay_silent 不该写 REACH_OUT", branch.contains("REACH_OUT"))

        // defer_reply 仍然走原来的分支（不落消息、标已读、发 DEFERRED）。
        // 取「回合结束后」的 defer 分支（循环里还有一个同名判断，位置不同、语义不同）。
        val deferredStart = chatRepository.indexOf("// defer_reply：这一轮他明确选择不回")
        val deferred = chatRepository.substring(deferredStart, deferredStart + 500)
        assertTrue(deferred.contains("SilentReason.DEFERRED"))
        assertTrue(deferred.contains("markUserMessagesRead"))
        assertTrue("defer 的念头在工具执行里写，不能被顺手删掉", toolExecutor.contains("AgentIntentKind.REACH_OUT"))
    }

    @Test
    fun `已读不回不产生用户可见的工具痕迹`() {
        // 成功/被拒绝都不该在气泡下面留一张「已读不回」的动作卡片：这一轮本来就没有气泡。
        assertTrue(
            "ChatRepository 要在 traces 里跳过 stay_silent",
            chatRepository.contains("!call.name.trim().equals(ToolCatalog.STAY_SILENT")
        )
        val stay = toolExecutor.substring(toolExecutor.indexOf("private suspend fun staySilent"))
        val head = stay.substring(0, minOf(stay.length, 2400))
        assertTrue("工具执行自己也要标已读", head.contains("markUserMessagesRead"))
        assertTrue("连续计数要落库", head.contains("setStaySilentStreak"))
        assertTrue("超过上限要拒绝（ok=false），让模型这一轮回一句", head.contains("StaySilent.allowed"))
    }

    @Test
    fun `连续沉默的计数在回话后清零`() {
        assertTrue(
            "ChatRepository 在助手消息落库后要清掉连续计数",
            chatRepository.contains("resetStaySilentStreak()")
        )
        val resetIndex = chatRepository.indexOf("resetStaySilentStreak()")
        val savedGuard = chatRepository.indexOf("if (saved == null)")
        val notified = chatRepository.indexOf("chatNotifier.notifyMessage", resetIndex)
        assertTrue("清零发生在「这一轮成功落库」之后", resetIndex > savedGuard)
        assertTrue("清零发生在通知之前（这一轮确实回了）", notified > resetIndex)
        assertTrue("上限存在设置层，且只允许两轮", settingsRepository.contains("STAY_SILENT_MAX_STREAK = 2"))
        // 计数与人格绑定：换人格不会被上一个人的沉默拖累。
        assertTrue(settingsRepository.contains("staySilentPersonaId"))
    }

    @Test
    fun `界面把已读不回当作与晚点回不同的静默原因`() {
        assertTrue(
            "SilentReason 要有独立的 STAY_SILENT，不能复用 DEFERRED",
            models.contains("STAY_SILENT")
        )
        val enum = models.substring(models.indexOf("enum class SilentReason"))
        assertTrue(enum.contains("ASLEEP"))
        assertTrue(enum.contains("DND"))
        assertTrue(enum.contains("DEFERRED"))
        assertTrue(enum.contains("STAY_SILENT"))
    }
}
