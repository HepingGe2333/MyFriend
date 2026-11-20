package com.yap.app.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份 v7（0.15）的**接线契约**：两张体验表真的接进导出/导入了吗？
 *
 * 纯逻辑（编解码、映射、哈希校验）在 `BackupV7Test` / `MediaIntegrityTest` 里；
 * 这里只在源码层面钉住最容易「测试全绿、发布包里却没带上」的几处接线：
 * - 导出真的按人格取心理活动、按会话取备忘录；
 * - 导入真的把它们写回 `mental_activities` / `session_memory`；
 * - **旧备份（v1–v6）替换导入不会清掉本机已有的 0.15 数据**（先快照、清空后补回）；
 * - 服务端 `extensions` 的两段在被服务端备份适配时提到顶层，`proactiveFailures` 被忽略。
 *
 * 为什么用源码断言而不是 Room 集成测试：`app:testDebugUnitTest` 是纯 JVM（Room 不可用），
 * 而「接线掉了」正是这类改动最常见的回归形态。仓库里其它 wiring contract 也走同一套办法。
 */
class BackupV7WiringContractTest {

    private val repo get() = source("src/main/java/com/yap/app/data/repo/BackupRepository.kt")
    private val codec get() = source("src/main/java/com/yap/app/domain/BackupCodec.kt")
    private val adapter get() = source("src/main/java/com/yap/app/data/remote/ServerBackupAdapter.kt")
    private val mediaStore get() = source("src/main/java/com/yap/app/data/remote/ServerMediaStore.kt")
    private val api get() = source("src/main/java/com/yap/app/data/remote/ServerApi.kt")

    @Test
    fun `备份 v7 的版本与两张新表都在编解码里`() {
        assertTrue("格式版本要抬到 7", codec.contains("BACKUP_FORMAT_VERSION: Int = 7"))
        listOf(
            "val mentalActivities: List<BackupMentalActivity> = emptyList()",
            "val sessionMemory: List<BackupSessionMemory> = emptyList()",
            "val languageTag: String? = null",
            "val blockedUntil: Long? = null",
            "val blockReason: String? = null",
            "val blockProposalJson: String? = null",
            "val blocked: Boolean = false",
            "val sha256: String = \"\""
        ).forEach { marker -> assertTrue("BackupCodec 缺少 $marker", codec.contains(marker)) }
        // 服务端 extensions 的读取回退与 proactiveFailures 的忽略都要在解码里写明。
        assertTrue("要读服务端 extensions", codec.contains("root[\"extensions\"] as? JsonObject"))
        assertTrue("顶层优先、缺失回退", codec.contains("mergeClientExtras(mergeClientExtras(root, clientExtras), serverExtensions)"))
        assertFalse("proactiveFailures 没有本地表，不该被解码", codec.contains("decodeOptionalList(\n            clientOnlyRoot,\n            \"proactiveFailures\""))
    }

    @Test
    fun `导出真的收集心理活动与会话备忘录`() {
        val export = bodyOf("suspend fun export(")
        assertTrue("导出要按人格取心理活动", export.contains("mentalActivityDao.recent("))
        assertTrue("导出要按人格取会话备忘录", export.contains("sessionMemoryDao.byPersona("))
        assertTrue("只导这次真的在备份里的会话", export.contains("exportedSessionIds"))
        assertTrue("要写进 bundle", export.contains("mentalActivities = mentalActivities.map"))
        assertTrue("要写进 bundle", export.contains("sessionMemory = sessionMemoryRows.map"))
    }

    @Test
    fun `导入把两张新表写回对应本地表 并在替换模式保住旧数据`() {
        val import = bodyOf("suspend fun importBundle(")
        assertTrue("心理活动要落表", import.contains("mentalActivityDao.insert("))
        assertTrue("会话备忘录要落表", import.contains("sessionMemoryDao.upsert("))
        assertTrue("要按备份里的 sessionId 映射到本机会话", import.contains("sessionIdMap[backup.sessionId]"))
        assertTrue("要过滤不存在的人格", import.contains("backup.personaId !in personaIds"))
        assertTrue("导入后按保留策略裁剪", import.contains("mentalActivityDao.trim("))
        // 旧备份替换：清库前先快照、事务里清空后补回。
        assertTrue("替换前要快照本机心理活动", import.contains("preservedMentalActivities"))
        assertTrue("替换前要快照本机会话备忘录", import.contains("preservedSessionMemory"))
        assertTrue("补回的行不能指向不存在的会话", import.contains("activity.copy(sessionId = null)"))
        assertTrue("备份里没有的会话备忘录要保留", import.contains("it.sessionId !in importedMemorySessions"))
        // 顺序：快照必须在 withTransaction 之前（清空发生在事务里）。
        val snapshotIndex = import.indexOf("preservedMentalActivities = mutableListOf")
        val transactionIndex = import.indexOf("database.withTransaction {")
        assertTrue("快照必须在清空之前", snapshotIndex in 0 until transactionIndex)
    }

    @Test
    fun `服务端备份适配把 0_15 两段提到顶层 并忽略诊断段`() {
        assertTrue("要识别 extensions", adapter.contains("root[\"extensions\"] as? JsonObject"))
        assertTrue("两段要提到顶层", adapter.contains("SERVER_CLIENT_SECTIONS"))
        assertTrue(adapter.contains("\"mentalActivities\""))
        assertTrue(adapter.contains("\"sessionMemory\""))
        assertFalse("proactiveFailures 不能被提到顶层", adapter.contains("\"proactiveFailures\""))
    }

    @Test
    fun `媒体下载会校验 sha256 且拒绝未知类型`() {
        assertTrue("下载要带回服务端哈希", api.contains("downloadMediaWithMeta("))
        assertTrue("ETag 要归一成 sha256", api.contains("MediaHashes.normalize(response.header(\"ETag\"))"))
        assertTrue("落盘前要算哈希", mediaStore.contains("MediaHashes.sha256Hex(bytes)"))
        assertTrue("不一致要记 hashMismatches", mediaStore.contains("hashMismatch = true"))
        assertTrue("要记到附件上", mediaStore.contains("sha256 = computedSha"))
        assertTrue("未知类型拒绝而不是猜", mediaStore.contains("ServerMediaKind.classify("))
        assertTrue("本机记录哈希要复检", mediaStore.contains("matchesLocalFile("))
        assertTrue("坏副本要删掉重下", mediaStore.contains("deleteLocalCopy("))
    }

    // ------------------------------------------------------------------ 源码小工具

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("找不到 $relative（工作目录=${File("").absolutePath}）")
    }

    /** 取一个函数从签名到下一个顶层 `}` 的正文（缩进 4 空格结尾），够做「接线」断言。 */
    private fun bodyOf(signature: String): String {
        val text = repo
        val start = text.indexOf(signature)
        assertTrue("BackupRepository 里找不到 $signature", start >= 0)
        val end = text.indexOf("\n    }\n", start)
        return if (end < 0) text.substring(start) else text.substring(start, end)
    }
}
