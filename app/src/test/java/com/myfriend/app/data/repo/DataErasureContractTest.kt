package com.myfriend.app.data.repo

import com.myfriend.app.domain.DataEraser
import com.myfriend.app.domain.ErasableTable
import com.myfriend.app.domain.ErasureFiles
import com.myfriend.app.domain.ErasurePath
import com.myfriend.app.domain.ErasurePlan
import com.myfriend.app.domain.ErasureStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「删干净」的契约测试（审计 P1-3 / 安全 P1-3）。
 *
 * 本项目没有 Room/Robolectric 单测环境，所以**不是**起一个真库再断言表为空；
 * 而是把「删除」抽象成 [ErasureStore]，用内存假实现记录「哪些表被清到了什么程度」，
 * 再用一个假的 [ErasureFiles] 断言媒体目录的清理。这样：
 *
 * 1. 四条路径的实际调用序列是真的（`DataEraser` 是生产代码）；
 * 2. 「操作后这张表为空」能逐表断言，包括按人格清的路径；
 * 3. 文件清理可以注入，不需要真的写 `filesDir`。
 *
 * 生产实现 `RoomErasureStore` 只负责把枚举映射到 DAO（见那个文件与源码契约测试）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataErasureContractTest {

    private companion object {
        /** 假 store 里「整表」用的 key（真库里就是 personaId = null 的 deleteAll）。 */
        const val GLOBAL = "<all>"
    }

    private class FakeStore : ErasureStore {
        /** table -> (personaId 或 GLOBAL) -> 行数。 */
        val rows = mutableMapOf<ErasableTable, MutableMap<String, Int>>()
        var attachments: List<String> = emptyList()
        var avatar: String? = null
        var failOn: ErasableTable? = null
        var transactions = 0

        fun seed(table: ErasableTable, personaId: String? = null, count: Int = 1) {
            rows.getOrPut(table) { mutableMapOf() }[personaId ?: GLOBAL] = count
        }

        fun countOf(table: ErasableTable, personaId: String? = null): Int =
            rows[table]?.get(personaId ?: GLOBAL) ?: 0

        fun total(table: ErasableTable): Int = rows[table]?.values?.sum() ?: 0

        fun isEmpty(table: ErasableTable, personaId: String? = null): Boolean =
            countOf(table, personaId) == 0

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            transactions++
            return block()
        }

        override suspend fun delete(table: ErasableTable, personaId: String?) {
            failOn?.let { if (it == table) error("boom: $table") }
            val perPersona = rows[table] ?: return
            if (personaId == null) perPersona.clear() else perPersona.remove(personaId)
        }

        override suspend fun attachmentsOfPersona(personaId: String): List<String> = attachments

        override suspend fun avatarUriOf(personaId: String): String? = avatar
    }

    private class FakeFiles : ErasureFiles {
        var chatImagesCleared = 0
        var allMediaCleared = 0
        val deletedChatImages = mutableListOf<String>()
        val deletedAvatars = mutableListOf<String?>()
        var keptAvatars: Set<String>? = null

        override fun clearChatImages(): Int {
            chatImagesCleared++
            return 3
        }

        override fun clearAllMedia(): Int {
            allMediaCleared++
            return 5
        }

        override fun deleteChatImages(uris: List<String>): Int {
            deletedChatImages += uris
            return uris.size
        }

        override fun deleteAvatar(uri: String?): Int {
            deletedAvatars += uri
            return if (uri.isNullOrBlank()) 0 else 1
        }

        override fun clearUnreferencedAvatars(keep: Set<String>): Int {
            keptAvatars = keep
            return 0
        }
    }

    private fun newEraser(): Triple<DataEraser, FakeStore, FakeFiles> {
        val store = FakeStore()
        val files = FakeFiles()
        return Triple(DataEraser(store, files), store, files)
    }

    private fun seedAllTables(store: FakeStore) {
        ErasableTable.entries.forEach { store.seed(it) }
    }

    // ------------------------------------------------------------------ 清空聊天与记忆

    @Test
    fun `清空聊天与记忆 清掉所有含用户原文的派生表`() = runTest {
        val (eraser, store, files) = newEraser()
        seedAllTables(store)

        val report = eraser.clearChatsAndMemories()

        assertEquals(ErasurePath.CLEAR_CHATS, report.path)
        listOf(
            ErasableTable.MESSAGES,
            ErasableTable.SESSIONS,
            ErasableTable.MEMORY_ECHOES,
            ErasableTable.MEMORY_CONSOLIDATIONS,
            ErasableTable.MEMORIES,
            ErasableTable.MEMORY_TERMS,
            ErasableTable.MEMORY_VERSIONS,
            ErasableTable.MEMORY_VECTORS
        ).forEach { table ->
            assertTrue("$table 应当被清空", store.isEmpty(table))
        }
        assertEquals("表删除必须在一个事务里", 1, store.transactions)
        assertEquals("聊天图片文件要删", 1, files.chatImagesCleared)
        assertEquals("聊天背景与头像不该被删", 0, files.allMediaCleared)
        assertTrue("按 uri 删头像不该发生在清空聊天路径", files.deletedAvatars.isEmpty())
    }

    @Test
    fun `清空聊天与记忆 不碰人格与配置`() = runTest {
        val (eraser, store, _) = newEraser()
        seedAllTables(store)

        eraser.clearChatsAndMemories()

        listOf(
            ErasableTable.AGENT_TICKS,
            ErasableTable.AGENT_INTENTS,
            ErasableTable.PERSONA_PENDING_PATCHES,
            ErasableTable.PERSONA_REVISIONS,
            ErasableTable.PERSONAS,
            ErasableTable.API_CONFIGS,
            ErasableTable.PROACTIVE_RULES,
            ErasableTable.PERSONA_PRESETS
        ).forEach { table ->
            assertFalse("$table 属于人格/设置数据，不该被「清空聊天」删掉", store.isEmpty(table))
        }
    }

    // ------------------------------------------------------------------ 删除全部数据

    @Test
    fun `删除全部数据 整库与全部媒体都清空`() = runTest {
        val (eraser, store, files) = newEraser()
        seedAllTables(store)
        store.attachments = listOf("file:///files/media/chat-1.jpg")
        store.avatar = "file:///files/avatars/avatar-1.jpg"

        val report = eraser.wipeAllData()

        assertEquals(ErasurePath.WIPE_ALL, report.path)
        ErasableTable.entries.forEach { table ->
            assertTrue("$table 应当被清空", store.isEmpty(table))
        }
        assertEquals("整棵媒体目录（含头像/背景）要删", 1, files.allMediaCleared)
        assertEquals(1, store.transactions)
    }

    // ------------------------------------------------------------------ 删人格

    @Test
    fun `删人格 只清这个人的行 并删他的图片与头像`() = runTest {
        val (eraser, store, files) = newEraser()
        val persona = "p-A"
        val other = "p-B"
        ErasableTable.entries.forEach { table ->
            runCatching { store.seed(table, persona) }
            runCatching { store.seed(table, other) }
        }
        store.attachments = listOf(
            "file:///files/media/chat-a1.jpg",
            "file:///files/media/chat-a2.jpg"
        )
        store.avatar = "file:///files/avatars/avatar-a.jpg"

        val report = eraser.deletePersona(persona)

        assertEquals(ErasurePath.DELETE_PERSONA, report.path)
        assertEquals(persona, report.personaId)
        ErasurePlan.tables(ErasurePath.DELETE_PERSONA).forEach { table ->
            assertTrue("$table 里这个人的行应当没了", store.isEmpty(table, persona))
        }
        assertTrue("另一个人的消息不该被牵连", store.countOf(ErasableTable.MESSAGES, other) > 0)
        assertTrue("另一个人的人格不该被牵连", store.countOf(ErasableTable.PERSONAS, other) > 0)
        assertEquals(
            "他的聊天图片要按 uri 删",
            listOf("file:///files/media/chat-a1.jpg", "file:///files/media/chat-a2.jpg"),
            files.deletedChatImages
        )
        assertEquals(listOf<String?>("file:///files/avatars/avatar-a.jpg"), files.deletedAvatars)
        assertEquals("不该整目录删", 0, files.allMediaCleared)
    }

    @Test
    fun `删人格 不动 API 配置与预设库`() = runTest {
        val (eraser, store, _) = newEraser()
        ErasableTable.entries.forEach { table -> store.seed(table) }

        eraser.deletePersona("p-A")

        assertFalse("API 配置从不随人删", store.isEmpty(ErasableTable.API_CONFIGS))
        assertFalse("预设库不是某个人的派生数据", store.isEmpty(ErasableTable.PERSONA_PRESETS))
    }

    // ------------------------------------------------------------------ 备份替换

    @Test
    fun `备份替换 清掉除 API 配置外的全部数据`() = runTest {
        val (eraser, store, files) = newEraser()
        seedAllTables(store)

        val report = eraser.replaceForBackup()

        assertEquals(ErasurePath.BACKUP_REPLACE, report.path)
        ErasurePlan.tables(ErasurePath.BACKUP_REPLACE).forEach { table ->
            assertTrue("$table 应当被清空", store.isEmpty(table))
        }
        assertFalse("API 配置从不删：Key 只在本机", store.isEmpty(ErasableTable.API_CONFIGS))
        assertEquals("旧聊天图片要删（备份不携带附件）", 1, files.chatImagesCleared)
        assertEquals("头像目录不能整删：备份里的 avatarUri 可能就指向本机仍有效的文件", 0, files.allMediaCleared)
        assertTrue("按引用回收孤儿头像（keep 集合被传进来）", files.keptAvatars != null)
    }

    @Test
    fun `独立调用的备份替换自己开且只开一个事务`() = runTest {
        val (eraser, store, files) = newEraser()
        seedAllTables(store)

        eraser.replaceForBackup()

        assertEquals(1, store.transactions)
        assertFalse(store.isEmpty(ErasableTable.API_CONFIGS))
        assertEquals(1, files.chatImagesCleared)
    }

    @Test
    fun `备份替换按引用保留头像 其余当孤儿回收`() = runTest {
        val (eraser, store, files) = newEraser()
        seedAllTables(store)
        val keep = setOf("file:///files/avatars/avatar-keep.jpg", "file:///files/avatars/user.jpg")

        val report = store.inTransaction { eraser.replaceForBackupInsideTransaction(keep) }
        assertNull("事务里只收集，不删文件（审计 P1-4）", files.keptAvatars)
        assertEquals("事务里不该碰聊天图片", 0, files.chatImagesCleared)

        eraser.commitFiles(report)
        assertEquals(keep, files.keptAvatars)
        assertEquals(1, files.chatImagesCleared)
    }

    @Test
    fun `已有事务里的两个入口不再嵌套开事务`() = runTest {
        val (eraser, store, _) = newEraser()
        seedAllTables(store)

        store.inTransaction { eraser.replaceForBackupInsideTransaction() }
        assertEquals("备份导入已经自己开好事务了，清空不该再套一层", 1, store.transactions)

        store.inTransaction { eraser.deletePersonaInsideTransaction("p-A") }
        assertEquals("删人格的事务由 PersonaRepository 提供", 2, store.transactions)
    }

    // ------------------------------------------------------------------ 事务外删文件（审计 P1-4）

    @Test
    fun `删人格在事务内只收集待删文件 提交后才删`() = runTest {
        val (eraser, store, files) = newEraser()
        seedAllTables(store)
        store.attachments = listOf("file:///files/media/chat-a1.jpg")
        store.avatar = "file:///files/avatars/avatar-a.jpg"

        val report = store.inTransaction { eraser.deletePersonaInsideTransaction("p-A") }

        assertTrue("事务里这个人的行已经删了", store.isEmpty(ErasableTable.MESSAGES, "p-A"))
        assertTrue("表删除发生在事务内", store.transactions == 1)
        assertTrue("事务内不能删图片", files.deletedChatImages.isEmpty())
        assertTrue("事务内不能删头像", files.deletedAvatars.isEmpty())
        assertNotNull("待删文件被收集进报告", report.pendingFiles)

        eraser.commitFiles(report)
        assertEquals(listOf("file:///files/media/chat-a1.jpg"), files.deletedChatImages)
        assertEquals(listOf<String?>("file:///files/avatars/avatar-a.jpg"), files.deletedAvatars)
    }

    @Test
    fun `删人格事务失败时 一个文件都不删`() = runTest {
        val (eraser, store, files) = newEraser()
        seedAllTables(store)
        store.attachments = listOf("file:///files/media/chat-a1.jpg")
        store.avatar = "file:///files/avatars/avatar-a.jpg"
        store.failOn = ErasableTable.MEMORIES

        val error = runCatching { eraser.deletePersona("p-A") }.exceptionOrNull()

        assertTrue("事务失败必须抛出来", error != null)
        // 假 store 没有真回滚（它只记录调用），所以这里只断言**文件**：
        // 这条测试要钉住的正是「事务失败时文件删除根本没发生」。
        assertTrue("失败路径绝不能已经删掉聊天图片", files.deletedChatImages.isEmpty())
        assertTrue("失败路径绝不能已经删掉头像", files.deletedAvatars.isEmpty())
    }

    @Test
    fun `备份替换事务失败时 一个文件都不删`() = runTest {
        val (eraser, store, files) = newEraser()
        seedAllTables(store)
        store.failOn = ErasableTable.PERSONAS

        val error = runCatching { eraser.replaceForBackup(setOf("file:///files/avatars/avatar-keep.jpg")) }
            .exceptionOrNull()

        assertTrue("事务失败必须抛出来", error != null)
        assertEquals("旧聊天图片不能被删", 0, files.chatImagesCleared)
        assertNull("头像目录不能被回收", files.keptAvatars)
    }

    @Test
    fun `删除全部数据事务失败时 不删媒体目录`() = runTest {
        val (eraser, store, files) = newEraser()
        seedAllTables(store)
        store.failOn = ErasableTable.AGENT_TICKS

        val error = runCatching { eraser.wipeAllData() }.exceptionOrNull()

        assertTrue(error != null)
        assertEquals("失败路径不能删整棵媒体目录", 0, files.allMediaCleared)
    }

    @Test
    fun `两个调用点在事务提交之后才删文件`() {
        val personaRepo = source("src/main/java/com/myfriend/app/data/repo/PersonaRepository.kt")
        val backupRepo = source("src/main/java/com/myfriend/app/data/repo/BackupRepository.kt")
        listOf("PersonaRepository" to personaRepo, "BackupRepository" to backupRepo).forEach { (name, text) ->
            assertTrue("$name 必须在提交后调 commitFiles", text.contains("dataEraser.commitFiles("))
        }
        val importBody = backupBody(backupRepo)
        val transactionIndex = importBody.indexOf("database.withTransaction")
        val filesIndex = importBody.indexOf("dataEraser.commitFiles(")
        assertTrue("备份替换：withTransaction 必须在 commitFiles 之前", transactionIndex in 0 until filesIndex)
        val personaDeleteBody = personaDeleteBody(personaRepo)
        val personaTransaction = personaDeleteBody.indexOf("database.withTransaction")
        val personaFiles = personaDeleteBody.indexOf("dataEraser.commitFiles(")
        assertTrue("删人格：withTransaction 必须在 commitFiles 之前", personaTransaction in 0 until personaFiles)
        val eraserSource = source("agentcore/src/main/kotlin/com/myfriend/app/domain/DataErasure.kt")
        listOf(
            "suspend fun replaceForBackupInsideTransaction(",
            "suspend fun deletePersonaInsideTransaction(",
            "private suspend fun _replaceForBackup("
        ).forEach { marker ->
            val body = section(eraserSource, marker)
            assertFalse("$marker 在事务里，不许碰文件（只收集）", body.contains("files."))
        }
    }

    // ------------------------------------------------------------------ 清单本身

    @Test
    fun `每条路径的清单覆盖审计要求的表`() {
        // 与报告里的「表 × 路径」矩阵一一对应；少一张就会在这里红。
        val expected = mapOf(
            ErasurePath.CLEAR_CHATS to setOf(
                "messages", "chat_sessions", "memories", "memory_terms",
                "memory_echoes", "memory_versions", "memory_vectors", "memory_consolidations"
            ),
            ErasurePath.WIPE_ALL to setOf(
                "messages", "chat_sessions", "memories", "memory_terms", "memory_echoes",
                "memory_versions", "memory_vectors", "memory_consolidations", "agent_ticks",
                "agent_intents", "api_usage", "persona_pending_patches", "persona_revisions",
                "proactive_rules", "persona_presets", "personas", "api_configs"
            ),
            ErasurePath.DELETE_PERSONA to setOf(
                "messages", "chat_sessions", "memories", "memory_terms", "memory_echoes",
                "memory_versions", "memory_vectors", "memory_consolidations", "agent_ticks",
                "agent_intents", "persona_pending_patches", "persona_revisions",
                "proactive_rules", "personas"
            ),
            ErasurePath.BACKUP_REPLACE to setOf(
                "messages", "chat_sessions", "memories", "memory_terms", "memory_echoes",
                "memory_versions", "memory_vectors", "memory_consolidations", "agent_ticks",
                "agent_intents", "persona_pending_patches", "persona_revisions",
                "proactive_rules", "persona_presets", "personas"
            )
        )
        expected.forEach { (path, tables) ->
            assertEquals(
                "$path 的清理清单对不上",
                tables,
                ErasurePlan.tables(path).map { it.tableName }.toSet()
            )
        }
    }

    @Test
    fun `清单顺序是先子表后父表`() {
        ErasurePlan.tables(ErasurePath.WIPE_ALL).let { tables ->
            assertTrue(
                "memory_terms 只挂在 memoryId 上，必须在 memories 之后回收",
                tables.indexOf(ErasableTable.MEMORY_TERMS) > tables.indexOf(ErasableTable.MEMORIES)
            )
            assertTrue(
                "memory_versions 只挂在 memoryId 上，必须在 memories 之后回收",
                tables.indexOf(ErasableTable.MEMORY_VERSIONS) > tables.indexOf(ErasableTable.MEMORIES)
            )
            assertTrue(
                "memory_vectors 只挂在 memoryId 上，必须在 memories 之后回收",
                tables.indexOf(ErasableTable.MEMORY_VECTORS) > tables.indexOf(ErasableTable.MEMORIES)
            )
            assertEquals("messages 应当先删", 0, tables.indexOf(ErasableTable.MESSAGES))
            assertTrue(
                "personas 是父表，要排在它的派生表之后",
                tables.indexOf(ErasableTable.PERSONAS) > tables.indexOf(ErasableTable.PERSONA_REVISIONS)
            )
            assertEquals("API 配置最后删", ErasableTable.API_CONFIGS, tables.last())
        }
    }

    @Test
    fun `清单里的每个表名都真实存在于 Entities`() {
        val entities = source("src/main/java/com/myfriend/app/data/local/Entities.kt")
        val declared = Regex("""tableName\s*=\s*"([^"]+)"""")
            .findAll(entities)
            .map { it.groupValues[1] }
            .toSet()
        ErasableTable.entries.forEach { table ->
            assertTrue(
                "ErasableTable.${table.name} 的表名「${table.tableName}」在 Entities.kt 里找不到" +
                    "（表名拼错就永远删不到）",
                table.tableName in declared
            )
        }
    }

    /**
     * 反向守卫（审计 P2-4）：`Entities.kt` 里出现的**每一张表**都必须在 [ErasableTable] 里。
     *
     * 0.10.2 的守卫只做「枚举 → Entities」一个方向：新加一张含用户原文的表、忘了登记枚举时，
     * 测试仍然绿（这正是 `api_usage` 漏掉的路径）。反向枚举后，新表一进库就必须在清单里出现，
     * 或者显式写进 allow-list 并说明理由。
     */
    @Test
    fun `库里出现的每一张表都能被删干净`() {
        // 实体集中在两个文件：Entities.kt（业务表）与 MessageBlockDao.kt（v11 的区块元数据）。
        val entities = Regex("""tableName\s*=\s*"([^"]+)"""")
            .findAll(
                source("src/main/java/com/myfriend/app/data/local/Entities.kt") +
                    source("src/main/java/com/myfriend/app/data/local/MessageBlockDao.kt")
            )
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("一张表都没读到，路径判断有问题", entities.size >= 17)
        val known = ErasableTable.entries.map { it.tableName }.toSet()
        // 派生表可以不走枚举：但它必须在 RoomErasureStore 里跟着父表一起删。
        val derived = mapOf(
            "message_blocks" to "messages 的派生元数据（没有独立原文），在 RoomErasureStore 的 MESSAGES 分支连带删除"
        )
        val missing = entities.filter { it !in known && it !in derived }
        assertEquals(
            "以下真实表既不在 ErasableTable 里、也不在派生表允许列表里（新的用户内容表必须能被删干净）：$missing",
            emptyList<String>(),
            missing
        )
        assertTrue("api_usage 必须在清单里（审计 P2-4）", "api_usage" in known)
        derived.keys.forEach { table ->
            val store = source("src/main/java/com/myfriend/app/data/repo/RoomErasureStore.kt")
            assertTrue(
                "派生表 $table 必须跟着父表在 RoomErasureStore 里删掉",
                store.contains("blockWriter.deleteAll()") && store.contains("blockWriter.deletePersona(personaId)")
            )
        }
    }

    @Test
    fun `删除中途失败会整体失败 不返回成功报告`() = runTest {
        val (eraser, store, _) = newEraser()
        seedAllTables(store)
        store.failOn = ErasableTable.MEMORIES

        val error = runCatching { eraser.wipeAllData() }.exceptionOrNull()
        assertTrue("删除失败必须抛出来，不能假装成功", error != null)
        assertFalse("失败的那张表不该被当成已清空", store.isEmpty(ErasableTable.MEMORIES))
    }

    // ------------------------------------------------------------------ 路径接线（源码契约）

    @Test
    fun `四条删除路径都走 DataEraser`() {
        assertTrue(
            "清空聊天与记忆要走 DataEraser",
            source("src/main/java/com/myfriend/app/data/repo/ChatRepository.kt")
                .contains("dataEraser.clearChatsAndMemories()")
        )
        assertTrue(
            "删除全部数据要走 DataEraser",
            source("src/main/java/com/myfriend/app/data/repo/ChatRepository.kt")
                .contains("dataEraser.wipeAllData()")
        )
        assertTrue(
            "删人格要走 DataEraser",
            source("src/main/java/com/myfriend/app/data/repo/PersonaRepository.kt")
                .contains("dataEraser.deletePersonaInsideTransaction(personaId)")
        )
        assertTrue(
            "备份替换要走 DataEraser",
            source("src/main/java/com/myfriend/app/data/repo/BackupRepository.kt")
                .contains("dataEraser.replaceForBackupInsideTransaction(keepAvatarUris)")
        )
    }

    @Test
    fun `RoomErasureStore 覆盖清单里的每一张表`() {
        val store = source("src/main/java/com/myfriend/app/data/repo/RoomErasureStore.kt")
        ErasableTable.entries.forEach { table ->
            assertTrue(
                "RoomErasureStore 没有处理 ${table.name}",
                store.contains("ErasableTable.${table.name} ->")
            )
        }
        assertTrue(
            "memory_versions 的删除入口应当在 MemoryReconsolidator（写的人也是删的人）",
            store.contains("memoryReconsolidator.purgeVersions(")
        )
        assertTrue(
            "v11 区块元数据跟着 messages 在一个分支里删（整库/按人两种口径都要有）",
            store.contains("blockWriter.deleteAll()") && store.contains("blockWriter.deletePersona(personaId)")
        )
        assertTrue(
            "删完会话之后回收孤儿块（异常导入/中断事务留下的行）",
            store.contains("blockDao.deleteOrphans()")
        )
        assertTrue(
            "MemoryReconsolidator 必须提供删除清理入口",
            source("src/main/java/com/myfriend/app/data/repo/MemoryReconsolidator.kt")
                .contains("suspend fun purgeVersions(")
        )
    }

    private fun source(relative: String): String {
        val candidates = listOf(File("."), File(".."))
        val appRoot = candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
        // `agentcore/...` 这类路径相对仓库根；其它相对 app 模块。
        val file = if (relative.startsWith("agentcore/")) {
            File(repoRoot(appRoot), relative)
        } else {
            File(appRoot, relative)
        }
        if (file.isFile) return file.readText()
        // `:agentcore` 改成 KMP 之后源码搬进了 commonMain / jvmMain。
        val relocated = listOf("commonMain", "jvmMain")
            .map { sourceSet -> File(repoRoot(appRoot), relative.replace("src/main/kotlin", "src/$sourceSet/kotlin")) }
            .firstOrNull { it.isFile }
        return (relocated ?: file).readText()
    }

    /** 从 app 模块目录往上找仓库根（`File(".").parentFile == null`，不能直接用 parent）。 */
    private fun repoRoot(appRoot: File): File {
        var dir: File? = appRoot.absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录：${appRoot.absolutePath}")
    }

    private fun backupBody(text: String): String = section(text, "suspend fun importBundle(")

    private fun personaDeleteBody(text: String): String = section(text, "suspend fun delete(personaId: String)")

    private fun insideTransactionBody(text: String): String = section(text, "suspend fun replaceForBackupInsideTransaction(")

    /** 从 [marker] 起截到本类下一个成员声明之前。 */
    private fun section(text: String, marker: String): String {
        val start = text.indexOf(marker)
        require(start >= 0) { "找不到 $marker" }
        val rest = text.substring(start + marker.length)
        val ends = listOf(
            "\n    private suspend fun ",
            "\n    private fun ",
            "\n    suspend fun ",
            "\n    override fun ",
            "\n    fun "
        )
        val end = ends.map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }
}
