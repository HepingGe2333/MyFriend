package com.yap.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「服务端模式只同步」的**接线契约**（0.13）。
 *
 * 纯策略在 [LocalAutonomyTest] 里；这里钉「真的接上了」——每个自主逻辑入口都必须有一道
 * 服务端模式闸门。这类问题最典型的形态是单测全绿、功能没接：五个入口里漏了一个，
 * 真机上就会变成双份主动消息或双份情绪判定，而且很难从日志看出来。
 *
 * 扫描的是源码文本（与 `ActivityToolWiringContractTest` 同一套做法）：断言的是「有闸」，
 * 不锁死实现细节，重命名变量时按提示改断言即可。
 */
class ServerModeAutonomyContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 $relative（工作目录=${File("").absolutePath}）")
    }

    private fun repo(name: String): String = source("src/main/java/com/yap/app/data/repo/$name")

    private fun remote(name: String): String = source("src/main/java/com/yap/app/data/remote/$name")

    @Test
    fun `心跳在服务端模式下一拍都不判`() {
        val text = repo("HeartbeatController.kt")
        assertTrue("心跳要有 serverMode 依赖", text.contains("private val serverMode"))
        assertTrue("心跳要先判服务端模式", text.contains("if (serverMode.isEnabled()) return stop(Reason.SERVER_MODE"))
    }

    @Test
    fun `AgentLoop 在服务端模式下连锁都不抢`() {
        val text = repo("AgentLoop.kt")
        assertTrue(text.contains("private val serverMode"))
        val tick = text.substring(text.indexOf("suspend fun tick("), text.indexOf("private suspend fun tickLocked"))
        assertTrue("tick 一开始就要挡服务端模式", tick.contains("serverMode.isEnabled()"))
        assertTrue(tick.contains("服务端模式"))
    }

    @Test
    fun `常驻循环控制器跟随服务端模式启停`() {
        val text = source("src/main/java/com/yap/app/service/AgentLoopController.kt")
        assertTrue(text.contains("private val serverMode"))
        assertTrue("要观察服务端状态变化", text.contains("serverMode.state"))
        assertTrue(text.contains("LocalAutonomy.proactiveGate"))
    }

    @Test
    fun `两个后台 Worker 都走同一个本地自主判定`() {
        val tickWorker = repo("AgentTickWorker.kt")
        assertTrue(tickWorker.contains("LocalAutonomy.shouldRunLocalTick(serverMode.isEnabled())"))
        val proactive = repo("ProactiveWorker.kt")
        assertTrue(proactive.contains("LocalAutonomy.shouldRunLocalTick(serverMode.isEnabled())"))
    }

    @Test
    fun `日记提醒在服务端模式下不变成念头`() {
        val text = repo("DiaryRepository.kt")
        val remind = text.substring(text.indexOf("suspend fun remindDue"), text.indexOf("private fun DiaryNoteEntity.toEntry"))
        assertTrue("remindDue 要判服务端模式", remind.contains("serverMode.isEnabled()"))
    }

    @Test
    fun `自动情绪判定与进化评估在服务端模式下不触发`() {
        val mood = repo("MoodJudgementCoordinator.kt")
        assertTrue(mood.contains("serverMode.isEnabled()"))
        val evolution = repo("PersonaEvolutionRepository.kt")
        assertTrue(evolution.contains("if (serverWrites.isEnabled()) return"))
        // 聊天主路径里本地触发被服务端模式挡住，且不再由客户端调服务端评估端点。
        val chat = repo("ChatRepository.kt")
        assertTrue(chat.contains("if (!isServerMode()) {"))
        assertFalse("服务端模式不再在回复后触发服务端情绪评估", chat.contains("serverMood.onReplyDone"))
        assertFalse("服务端模式不再在回复后触发服务端进化评估", chat.contains("serverEvolution.onReplyDone"))
    }

    @Test
    fun `记忆自动整理在服务端模式下不跑 但手动那一次保留`() {
        val text = repo("MemoryConsolidationWorker.kt")
        assertTrue(text.contains("serverMode.isEnabled() && !tags.contains(TAG_MANUAL)"))
        assertTrue("手动任务要带标记", text.contains(".addTag(TAG_MANUAL)"))
    }

    @Test
    fun `表情与日记的新增删除在服务端模式下走服务端 CRUD`() {
        val stickers = repo("StickerRepository.kt")
        assertTrue("新建表情要 POST /v1/stickers", stickers.contains("serverWrites.createSticker"))
        assertTrue("删除表情要 DELETE /v1/stickers/{id}", stickers.contains("serverWrites.deleteSticker"))
        // 服务端 inline base64 有 ~192 KiB 上限：大表情必须先传 /v1/media 再建行。
        assertTrue("大表情要走媒体上传", stickers.contains("serverWrites.uploadStickerMedia"))
        assertTrue("要有 inline base64 上限常量", stickers.contains("INLINE_BASE64_LIMIT_BYTES"))
        val diary = repo("DiaryRepository.kt")
        assertTrue("新增/更新日记要走服务端", diary.contains("serverWrites.saveDiary"))
        assertTrue("删除日记要走服务端", diary.contains("serverWrites.deleteDiary"))
    }

    @Test
    fun `表情与日记的拉取缓存挂在服务端同步循环上`() {
        val runtime = remote("ServerModeRuntime.kt")
        assertTrue(runtime.contains("catalogSync.syncAll"))
        val sync = repo("ServerCatalogSync.kt")
        assertTrue("表情字节要经 /v1/media 落盘", sync.contains("media.localUris"))
        assertTrue("日记只同步行", sync.contains("api.diary("))
    }

    @Test
    fun `备份在服务端模式下走 v1 backup`() {
        val backup = repo("BackupRepository.kt")
        assertTrue(backup.contains("serverWrites.exportBackup()"))
        assertTrue(backup.contains("serverWrites.importBackup("))
        val gateway = remote("ServerWriteGateway.kt")
        assertTrue(gateway.contains("exportBackup"))
        assertTrue(gateway.contains("importBackup"))
    }
}
