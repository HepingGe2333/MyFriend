package com.yap.app.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.15 上下文/记忆 v2 的接线守卫（源码级）。
 *
 * 纯规则在 `domain/` 已经逐条单测；这里只盯「真的接上了没有」——规则写得再对，
 * 装配路径没用它，用户看到的还是 0.14 的行为。
 */
class MemoryV2WiringContractTest {

    private fun source(path: String): String {
        val candidates = listOf(File(path), File("app/$path"), File("../app/$path"))
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun `聊天上下文用 ContextAssembly 装配 而不是旧 trimHistory`() {
        val chat = source("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
        assertTrue(chat.contains("ContextAssembly.assemble("))
        assertTrue(chat.contains("PromptBuilder.personaAndTools("))
        assertTrue(chat.contains("PromptBuilder.currentStateBody("))
        assertTrue("三段备忘要从 sessionMemoryRepository 快照来", chat.contains("sessionMemoryRepository.snapshot("))
        assertFalse("旧的逐条裁剪不能再留着做第二套预算", chat.contains("private fun trimHistory("))
    }

    @Test
    fun `memorize 工具接进执行器 并把回忆留给下一轮`() {
        val executor = source("src/main/java/com/yap/app/data/repo/ToolExecutor.kt")
        assertTrue(executor.contains("\"memorize\" -> memorize("))
        assertTrue(executor.contains("memorizeRepository.stashForNextRound("))
        val chat = source("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
        assertTrue("下一轮要注入一次", chat.contains("memorizeRepository.takePending(session.id)"))
    }

    @Test
    fun `工具轮数从共享契约来 模型可指定`() {
        val chat = source("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
        assertTrue(chat.contains("var maxToolRounds = ToolRounds.DEFAULT"))
        assertTrue(chat.contains("ToolRounds.merge("))
        assertTrue(chat.contains("round >= maxToolRounds"))
        assertFalse("不能再用写死的 3 轮", chat.contains("const val MAX_TOOL_ROUNDS = 3"))
    }

    @Test
    fun `心情每条消息都判 且手动覆盖不会被后台覆盖`() {
        val coordinator = source("src/main/java/com/yap/app/data/repo/MoodJudgementCoordinator.kt")
        assertTrue(coordinator.contains("MoodJudgement.isStale("))
        val judgement = source("src/main/java/com/yap/app/domain/MoodJudgement.kt")
        assertFalse("取消每维变化≤2 的夹取", judgement.contains("MoodDimensions.limitChange(previous, normalized)"))
        val chat = source("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
        assertTrue("发完用户消息就触发判定", chat.contains("moodJudgement.ensureAsync(session.personaId, now)"))
    }

    @Test
    fun `节奏走共享契约`() {
        val rhythm = source("src/main/java/com/yap/app/domain/ExperienceRhythm.kt")
        assertTrue(rhythm.contains("ExperienceContract.typingDelayMillis("))
        assertTrue(rhythm.contains("ExperienceContract.burstGapMillis("))
        assertFalse("不能再有固定的 0.8–2.5 秒公式", rhythm.contains("GAP_SPAN_MS = 1_700L"))
    }

    @Test
    fun `人格语言接进聊天提示词与工具能力段`() {
        val chat = source("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
        assertTrue(chat.contains("PromptLanguage.resolve(persona.languageTag)"))
        assertTrue(chat.contains("language = personaLanguage"))
        assertTrue(chat.contains("ToolCatalog.capabilityPrompt("))
    }
}
