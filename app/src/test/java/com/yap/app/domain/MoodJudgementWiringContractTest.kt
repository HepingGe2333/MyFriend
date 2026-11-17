package com.yap.app.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自动情绪判定的接线契约（`spec/mood/README.md` §2）。
 *
 * 纯函数（触发/幅度/衰减）在 [MoodJudgementTest] 里逐条测；这里只证明「真的接上了」：
 * 用户消息发出后会触发判定、判定结果会写库、下一轮提示词会读到多维心情、
 * 失败/没配置会走时间衰减，以及老库（三列为 NULL）走 label/intensity 折算。
 *
 * 为什么要这一层：自动判定是一条「静默」的后台链路，接错线不会有任何报错——
 * 只会表现成「他的情绪一直是平静」。用源码契约钉住入口与出口，比等到用户反馈便宜得多。
 */
class MoodJudgementWiringContractTest {

    private fun appRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到 app 模块根目录，当前工作目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    private val chatRepository by lazy { source("src/main/java/com/yap/app/data/repo/ChatRepository.kt") }
    private val coordinator by lazy { source("src/main/java/com/yap/app/data/repo/MoodJudgementCoordinator.kt") }
    private val moodRepository by lazy { source("src/main/java/com/yap/app/data/repo/MoodRepository.kt") }
    private val settings by lazy { source("src/main/java/com/yap/app/data/prefs/SettingsRepository.kt") }
    private val daos by lazy { source("src/main/java/com/yap/app/data/local/Daos.kt") }

    @Test
    fun `用户消息发出后会按节奏触发判定 且不阻塞发送`() {
        assertTrue(
            "ChatRepository 必须在 sendUserMessage 里触发判定",
            chatRepository.contains("moodJudgement.ensureAsync(session.personaId, now)")
        )
        val ensure = coordinator.indexOf("fun ensureAsync")
        assertTrue("判定要起后台协程，不能 await", coordinator.substring(ensure, ensure + 500).contains("scope.launch"))
        assertTrue(
            "要单飞：连发几条消息不能打一串 API",
            coordinator.contains("private val inFlight = AtomicBoolean(false)")
        )
        assertTrue(
            "游标要在调用模型之前推进（失败不重试）",
            coordinator.indexOf("setMoodJudgeCursor") < coordinator.indexOf("judge(persona, sessionId")
        )
    }

    @Test
    fun `判定结果写库 下一轮提示词读到多维心情`() {
        assertTrue("判定结果要整组写进三维度", coordinator.contains("moodRepository.setReading("))
        assertTrue(
            "聊天装配提示词时要把多维心情传进去",
            chatRepository.contains("moodDetail = moodReading")
        )
        assertTrue(
            "提示词从仓库读的是多维心情，不是只有标签",
            chatRepository.contains("moodRepository.reading(persona.id, now)")
        )
    }

    @Test
    fun `失败与无配置走时间衰减 不报错`() {
        val judge = coordinator.substring(coordinator.indexOf("suspend fun judge("))
        assertTrue("没有可用配置时要衰减而不是报错", judge.contains("?: return runCatching { moodRepository.decayForFailure("))
        assertTrue("解析失败/没有明显变化时也要衰减", judge.contains("MoodJudgement.parse(raw, previous, now)")
            && judge.contains("decayForFailure"))
        assertTrue(
            "衰减的实现就是「每小时向 0/2/3 靠一步」",
            source("src/main/java/com/yap/app/domain/MoodJudgement.kt")
                .contains("MoodDimensions.decay(previous, hours)")
        )
    }

    @Test
    fun `老库的三列是 NULL 时按 label 与 intensity 折算`() {
        assertTrue(
            "读库要把 NULL 维度折算出来",
            moodRepository.contains("valence = moodValence ?: legacy.valence") &&
                moodRepository.contains("arousal = moodArousal ?: legacy.arousal") &&
                moodRepository.contains("dominance = moodDominance ?: legacy.dominance")
        )
        assertTrue(
            "写库只走 setMoodVector（标签/强度/三维度一次写完，避免只写一半）",
            daos.contains("suspend fun setMoodVector(")
        )
    }

    @Test
    fun `判定游标存在设置里 换人格不串号`() {
        listOf("mood_judge_persona_id", "mood_judge_user_message_count", "mood_judge_last_run_at")
            .forEach { key -> assertTrue("设置里缺 $key", settings.contains(key)) }
        assertTrue("换人格时游标归零", coordinator.contains("val samePersona = settings.moodJudgePersonaId == personaId"))
        assertTrue(
            "判定频率是 4 条 / 24 小时（规范 §2）",
            source("src/main/java/com/yap/app/domain/MoodJudgement.kt")
                .contains("USER_MESSAGES_PER_JUDGE: Int = 4")
        )
    }
}
