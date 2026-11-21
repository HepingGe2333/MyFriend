package com.yap.app.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.15 三条安卓侧护栏的接线契约：
 *
 * 1. **remember 可靠性**：模型这一轮调用过 `remember` 时，高置信自动记忆不再重复写；
 *    自动记忆发生在回复落库之后，内容经仓储去重；
 * 2. **stay_silent 严格语义**：同一批调用里 `stay_silent` 先执行、这一轮立刻结束，
 *    即使模型同时输出正文也不会落库；
 * 3. **危机安全兜底**：用户消息命中危机且回复缺少安全锚点时，在末尾追加一句资源文案。
 *
 * 这些行为发生在 `ChatRepository.generateAssistant` 内部（JVM 单测没有 Room / 流式模型），
 * 与仓库里其它 `*ContractTest` 一样用源码契约钉住「有没有接对」，纯规则本身在
 * [AutoMemoryTest] / [SafetyGuardTest] / [StaySilentTest] 里逐条测。
 */
class AndroidGuardsContractTest {

    private fun appRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到 app 模块根目录，当前工作目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    private val chatRepository by lazy { source("src/main/java/com/yap/app/data/repo/ChatRepository.kt") }
    private val memoryRepository by lazy { source("src/main/java/com/yap/app/data/repo/MemoryRepository.kt") }
    private val memoryScreen by lazy { source("src/main/java/com/yap/app/ui/screens/memory/MemoryScreen.kt") }
    private val toolProtocol by lazy { source("src/main/java/com/yap/app/domain/ToolProtocol.kt") }
    private val zhStrings by lazy { source("src/main/res/values/strings.xml") }
    private val enStrings by lazy { source("src/main/res/values-en/strings.xml") }

    // ---------------------------------------------------------------- remember

    @Test
    fun `只在正文说记住了不算的提示还留着`() {
        assertTrue(
            "中文提示词必须保留「只在正文说记住了不算」",
            toolProtocol.contains("只在正文说「记住了」不算")
        )
        assertTrue(
            "英文提示词同样要说明只在回复里说没用",
            toolProtocol.contains("Saying “I'll remember that” in the reply does nothing.")
        )
    }

    @Test
    fun `模型调用过 remember 时不再自动补记忆`() {
        assertTrue("生成流程要记录这一轮有没有调用 remember", chatRepository.contains("rememberCalled"))
        assertTrue(
            "自动记忆的入口要把 rememberCalled 传进去",
            chatRepository.contains("autoRemember(persona.id, sessionId, latestUserText, rememberCalled)")
        )
        assertTrue(
            "规则层据 rememberCalled 决定不再兜底",
            chatRepository.contains("AutoMemory.detectThisTurn(userMessage, rememberCalled)")
        )
        val record = chatRepository.indexOf("ToolCatalog.REMEMBER")
        assertTrue("remember 的记录点在工具执行里", record >= 0)
        val branch = chatRepository.indexOf("autoRemember(persona.id")
        assertTrue("自动记忆只在正常收尾、回复落库之后", branch > record)
        val saved = chatRepository.indexOf("val saved = lastSaved ?: throw")
        assertTrue("自动记忆发生在这一轮消息落库之后", branch > saved)
    }

    @Test
    fun `自动记忆走仓储去重 并标记 auto`() {
        assertTrue("聊天流程要调用规则检测", chatRepository.contains("AutoMemory.detect"))
        assertTrue("写记忆走仓储", chatRepository.contains("memoryRepository.addAutoMemory"))
        assertTrue("仓储按规则去重", memoryRepository.contains("AutoMemory.isDuplicate"))
        assertTrue(
            "自动记忆写 USER 抽屉",
            memoryRepository.contains("drawer = MemoryDrawer.USER.name")
        )
        assertTrue(
            "importance 必须在 4–5",
            memoryRepository.contains("importance.coerceIn(4, 5)")
        )
        assertTrue("标签要带 auto", memoryRepository.contains("tags: List<String> = listOf(AutoMemory.TAG)"))
    }

    @Test
    fun `记忆抽屉把 auto 标出来`() {
        assertTrue("抽屉要读 tags", memoryScreen.contains("MemoryTags.decode(memory.tags)"))
        assertTrue("抽屉要判断 auto", memoryScreen.contains("AutoMemory.isAuto"))
        assertTrue("抽屉要显示自动标记", memoryScreen.contains("R.string.memory_source_auto"))
        assertTrue("values 里要有自动标记文案", zhStrings.contains("name=\"memory_source_auto\""))
        assertTrue("values-en 里也要有", enStrings.contains("name=\"memory_source_auto\""))
    }

    // ---------------------------------------------------------------- stay_silent

    @Test
    fun `stay_silent 先执行 并且这一轮立刻结束`() {
        val plan = chatRepository.indexOf("StaySilent.planBatch(calls)")
        assertTrue("同一批调用要先排好顺序", plan >= 0)
        val silentGuard = chatRepository.indexOf("if (silenced) {", plan)
        assertTrue("沉默之后要挡住同批的其它工具", silentGuard > plan)
        val guardBody = chatRepository.substring(silentGuard, minOf(chatRepository.length, silentGuard + 800))
        assertTrue("同批剩余调用不再执行", guardBody.contains("continue"))
        assertTrue(
            "已经发过消息时拒绝沉默",
            chatRepository.contains("StaySilent.canHonor(sentMessages.isNotEmpty())")
        )
        assertTrue(
            "轮数用尽时 stay_silent 仍然优先：不能因为预算到了就把同一响应里的正文落库",
            chatRepository.contains("round >= maxToolRounds && !silenceInCalls")
        )
        // 循环里那段「立刻结束」：finalBody 清空 + break，不再向模型要下一轮。
        val breakAnchor = chatRepository.indexOf("// stay_silent：立刻结束，不再向模型要下一轮。")
        assertTrue("循环里要有「立刻结束」的分支", breakAnchor > plan)
        val breakBody = chatRepository.substring(breakAnchor, minOf(chatRepository.length, breakAnchor + 300))
        assertTrue(
            "必须清掉本轮正文并结束循环（工具+正文也只沉默、不重复）",
            breakBody.contains("finalBody = \"\"") && breakBody.contains("break")
        )
    }

    @Test
    fun `连续两轮的沉默限制还保留`() {
        assertTrue(
            "连续上限仍由 StaySilent.allowed 控制",
            chatRepository.contains("StaySilent") && chatRepository.contains("ToolCatalog.STAY_SILENT")
        )
        assertTrue(
            "工具执行层继续读 stay_silent streak",
            source("src/main/java/com/yap/app/data/repo/ToolExecutor.kt")
                .contains("StaySilent.allowed(previous)")
        )
    }

    // ---------------------------------------------------------------- safety

    @Test
    fun `危机兜底只在缺失锚点时追加一句资源文案`() {
        assertTrue("生成流程要调用安全兜底", chatRepository.contains("SafetyGuard.noteIfNeeded"))
        assertTrue(
            "提示句从资源取，按界面语言显示",
            chatRepository.contains("R.string.safety_crisis_note")
        )
        assertTrue("values 里要有这句提示", zhStrings.contains("name=\"safety_crisis_note\""))
        assertTrue("values-en 里也要有", enStrings.contains("name=\"safety_crisis_note\""))
        val safety = chatRepository.indexOf("SafetyGuard.noteIfNeeded")
        val split = chatRepository.indexOf("ExperienceRhythm.splitPlainReply")
        assertTrue("追加发生在拆条落库之前（否则提示会丢）", safety in 0 until split)
        assertTrue(
            "正文为空、已经用 send_message 发过时补到最后一条",
            chatRepository.contains("content = previous.content.trimEnd() + \"\\n\" + safetyNote")
        )
    }
}
