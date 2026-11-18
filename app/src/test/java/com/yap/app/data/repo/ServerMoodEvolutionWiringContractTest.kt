package com.yap.app.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.13 服务端模式的「情绪 / 进化」接线守卫。
 *
 * 行为本身由各自端点测试钉住；这里只看**聊天主路径与仓储分支**有没有真的接上——
 * 这类遗漏在编译期看不出来，只能靠读源码（与 `MoodJudgementWiringContractTest` 同一套做法）。
 */
class ServerMoodEvolutionWiringContractTest {

    @Test
    fun `服务端回复完成后客户端不再代服务端做情绪与进化`() {
        // 0.13 起「服务端独立运行」：情绪/进化由服务端自己在一轮回复后判定，
        // 客户端只同步结果。同一条回复被两端各判一次就是双份判定，因此这里反过来断言：
        // 服务端发送完成后**不能**再调客户端的评估触发。
        val source = source("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
        val block = source
            .substringAfter("suspend fun sendServerMessage(")
            .substringBefore("private suspend fun patchServerAttachments(")
        assertTrue("要写清为什么不再触发", block.contains("服务端"))
        assertFalse("不再触发服务端进化评估", block.contains("serverEvolution.onReplyDone("))
        assertFalse("不再触发服务端情绪判定", block.contains("serverMood.onReplyDone("))
    }

    @Test
    fun `服务端情绪判定调用 evaluate 端点并写本地缓存`() {
        val source = source("src/main/java/com/yap/app/data/repo/ServerMoodEvaluator.kt")
        assertTrue("只在服务端模式生效", source.contains("mode.isEnabled()"))
        assertTrue("调用 /mood/evaluate", source.contains("api.evaluateMood("))
        assertTrue("复用 4 条消息节流", source.contains("MoodJudgement.shouldJudge("))
        assertTrue("游标与本地判定共用", source.contains("setMoodJudgeCursor("))
        assertTrue("写本地多维缓存", source.contains("moodRepository.setReading("))
        assertTrue("服务端没配 LLM 时静默跳过", source.contains("status == 503"))
    }

    @Test
    fun `服务端进化走服务端端点而不是本机模型`() {
        val repository = source("src/main/java/com/yap/app/data/repo/PersonaEvolutionRepository.kt")
        assertTrue(repository.contains("serverWrites.isEnabled()"))
        assertTrue(repository.contains("evaluateOnServer("))
        assertTrue("评估调服务端端点", repository.contains("serverWrites.evaluateEvolution("))
        assertTrue("待审列表来自服务端", repository.contains("serverWrites.pendingEvolution("))

        val acceptance = source("src/main/java/com/yap/app/data/repo/PersonaEvolutionAcceptance.kt")
        assertTrue("接受调服务端 accept", acceptance.contains("serverWrites.acceptEvolution("))
        assertTrue("拒绝调服务端 reject", acceptance.contains("serverWrites.rejectEvolution("))
        assertTrue("服务端失败时保留待审（getOrThrow 抛出前不删）", acceptance.contains(".getOrThrow()"))
    }

    /** 读 `app/` 下的源码（与 `ServerPersonaEvolutionTest` 同一套根目录定位）。 */
    private fun source(relative: String): String {
        val candidates = listOf(File("."), File(".."))
        val root = candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
        return File(root, relative).readText()
    }
}
