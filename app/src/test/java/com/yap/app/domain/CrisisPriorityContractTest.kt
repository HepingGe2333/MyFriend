package com.yap.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 危机优先于沉默 / 延后（0.15）的接线契约。
 *
 * 产品决定：用户这一轮说了危机语句（`不想活 / 自杀 / 安眠药 / 割腕 / 消失比较好……`）时，
 * **必须**有一条可见的 assistant 消息——不能只「已读不回」（`stay_silent`），
 * 也不能只留一个「过会儿再说」的念头（`defer_reply`）。
 *
 * 规则本身（拦哪些工具、正文怎么选、非危机不变）在 [CrisisPriorityTest] 里逐条测。
 * 这里只钉住 `ChatRepository.generateAssistant` 里的接线，因为真实写入要 Room 与流式模型，
 * JVM 单测跑不起来——与仓库里其它 `*ContractTest` 同一做法。
 */
class CrisisPriorityContractTest {

    private fun appRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到 app 模块根目录，当前工作目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    private val chatRepository by lazy { source("src/main/java/com/yap/app/data/repo/ChatRepository.kt") }
    private val crisisPriority by lazy { source("src/main/java/com/yap/app/domain/CrisisPriority.kt") }
    private val safetyGuard by lazy { source("src/main/java/com/yap/app/domain/SafetyGuard.kt") }

    /** 截取一段源码，长度上界自动收敛到文件末尾。 */
    private fun slice(from: Int, length: Int): String =
        chatRepository.substring(from, minOf(chatRepository.length, from + length))

    private fun indexOf(needle: String, from: Int = 0): Int =
        chatRepository.indexOf(needle, from).also { require(it >= 0) { "源码里找不到：$needle" } }

    // ---------------------------------------------------------------- 判定在最前面

    @Test
    fun `危机判定只看用户原话且发生在向模型要回复之前`() {
        val judged = indexOf("val crisisTurn = CrisisPriority.isCrisisTurn(latestUserText)")
        assertTrue(
            "要看的是这一轮的用户原话",
            judged > indexOf("val latestUserText = recentHistory.firstOrNull")
        )
        assertTrue(
            "判定必须在开始生成之前（之后的所有分支都靠它）",
            judged < indexOf("emit(GenerationEvent.Started)")
        )
        assertTrue(
            "危机判定与 SafetyGuard 共用同一条规则，不在两处各写一份",
            crisisPriority.contains("SafetyGuard.isCrisis") && !crisisPriority.contains("Regex(")
        )
        assertTrue("SafetyGuard 自己仍然是唯一的规则来源", safetyGuard.contains("fun isCrisis("))
    }

    // ---------------------------------------------------------------- 拦下「不回」的工具

    @Test
    fun `危机轮在工具执行之前拦下 stay_silent 与 defer_reply`() {
        val blocks = indexOf("if (CrisisPriority.blocksTool(crisisTurn, call.name)) {")
        assertTrue(
            "必须用 crisisTurn 判断，非危机轮才不会被误拦",
            chatRepository.contains("blocksTool(crisisTurn, call.name)")
        )
        assertTrue(
            "拦截发生在任何工具执行之前（不写念头、不排窗口、不涨沉默计数、不留卡片）",
            blocks < indexOf("val execution = toolExecutor.execute(")
        )
        assertTrue(
            "拦截发生在把本轮标记成 defer 之前",
            blocks < indexOf("if (call.name.trim().equals(ToolCatalog.DEFER_REPLY, ignoreCase = true)) deferred = true")
        )
        val block = slice(blocks, 900)
        assertTrue("拦下后要记下「这一轮必须回」", block.contains("crisisForcedReply = true"))
        assertTrue("拦下的调用不再往下执行", block.contains("continue"))
        assertFalse("拦下的调用不该留下任何用户可见的痕迹", block.contains("traces +="))
        assertTrue(
            "给模型的说明来自纯规则",
            block.contains("CrisisPriority.blockedModelText()")
        )
    }

    @Test
    fun `已经写出来的消息仍然会发给对方 拦的只是不回`() {
        // 同一批里 `send_message` 依旧执行：用户该看到的话一句都不能少。
        val blocks = indexOf("if (CrisisPriority.blocksTool(crisisTurn, call.name)) {")
        val send = indexOf("if (call.name.trim().equals(ToolCatalog.SEND_MESSAGE, ignoreCase = true)) {")
        assertTrue("send_message 的处理在拦截之后（拦下沉默后仍会执行）", send > blocks)
        assertTrue(
            "拦下沉默不等于拦下说话",
            chatRepository.contains("contentForModel = CrisisPriority.blockedModelText()")
        )
    }

    // ---------------------------------------------------------------- 这一轮必须以消息结束

    @Test
    fun `被拦下后按模型正文或安全提示结束这一轮 而不是沉默`() {
        val forced = indexOf("if (crisisForcedReply) {")
        val deferredBreak = indexOf("if (deferred) {", forced)
        val block = chatRepository.substring(forced, deferredBreak)
        assertTrue("模型在同一响应里写的正文要照用", block.contains("finalBody = body.trim()"))
        assertTrue("结束循环，不再向模型要下一轮", block.contains("break"))
        assertFalse("不能走「已读不回」的静默收尾", block.contains("GenerationEvent.Silent"))
        assertFalse("不能提前 return 掉这一轮（否则消息不会落库）", block.contains("return@flow"))

        // 非危机的 defer / stay_silent 收尾分支原样保留（危机轮根本走不到它们：
        // 工具在循环里就被拦下，deferred / silenced 不会被置位）。
        val deferredEnd = indexOf("// defer_reply：这一轮他明确选择不回")
        val silentEnd = indexOf("// stay_silent：只把用户消息标成已读")
        assertTrue("非危机的 defer 分支仍在", chatRepository.contains("SilentReason.DEFERRED"))
        assertTrue("非危机的 stay_silent 分支仍在", chatRepository.contains("SilentReason.STAY_SILENT"))
        assertTrue(
            "非危机的两个收尾分支都排在循环内「危机强制回」之后，原样保留",
            forced < deferredEnd && forced < silentEnd
        )
    }

    @Test
    fun `危机轮没有正文时安全提示就是这一轮唯一的正文`() {
        val fallback = indexOf("if (crisisTurn && sentMessages.isEmpty()) {")
        val block = slice(fallback, 260)
        assertTrue("正文兜底走纯规则", block.contains("CrisisPriority.replyBody(crisisTurn, replyText, crisisNote)"))
        assertTrue(
            "兜底发生在拆条落库之前（否则提示会被丢掉）",
            fallback < indexOf("ExperienceRhythm.splitPlainReply")
        )
        val saved = indexOf("emit(GenerationEvent.MessageSent(saved))", fallback)
        assertTrue("落的是正常 assistant 消息（走 MessageSent）", saved > fallback)
        assertTrue(
            "这一轮真的回了，连续沉默计数照常清零",
            indexOf("resetStaySilentStreak()", saved) > saved
        )
        assertTrue(
            "已读在这一轮生成之前就标了（危机兜底不会绕过它）",
            indexOf("markUserMessagesRead(sessionId, now0)") < indexOf("emit(GenerationEvent.Started)")
        )
    }

    @Test
    fun `已经用 send_message 发过时不造第 5 个气泡_提示并入最后一条`() {
        // 正文为空、但已经发过消息：走既有「并进最后一条」的分支，不再落新的提示气泡。
        assertTrue(
            "兜底只在一条消息都没发时生效",
            chatRepository.contains("if (crisisTurn && sentMessages.isEmpty()) {")
        )
        assertTrue(
            "并发过消息时把提示补到最后一条",
            chatRepository.contains("content = previous.content.trimEnd() + \"\\n\" + safetyNote")
        )
        // 提示句只取一次，追加与兜底共用，避免同一轮写两遍。
        assertTrue(
            "提示句只从资源取一次",
            Regex("R\\.string\\.safety_crisis_note").findAll(chatRepository).count() == 1
        )
        assertTrue(
            "补句只判定一次（不重复追加）",
            Regex("SafetyGuard\\.noteIfNeeded").findAll(chatRepository).count() == 1
        )
        assertTrue("兜底用的就是这一句", chatRepository.contains("val crisisNote = appContext.getString"))
    }
}
