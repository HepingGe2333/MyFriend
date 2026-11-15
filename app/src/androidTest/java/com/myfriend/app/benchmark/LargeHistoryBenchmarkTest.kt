package com.myfriend.app.benchmark

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myfriend.app.data.local.MessageSearchRow
import com.myfriend.app.data.local.MessageSearchSql
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.domain.BlockSearch
import com.myfriend.app.domain.ChineseTerms
import com.myfriend.app.domain.RankInput
import com.myfriend.app.domain.SearchFilter
import com.myfriend.app.domain.SearchRanking
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.Random

/**
 * **10 万条消息**的大历史基准：v10 → v11 迁移耗时 + 逐块检索第 1 / 10 / 100 页耗时。
 *
 * ## 它回答什么
 *
 * 1. 一个聊了几年、历史攒到 10 万条的用户，从 v10 升到 v11 时**要等多久**（含内存峰值）；
 * 2. v11 的「逐块扫描 + 键集游标」在 10 万条上翻到第 100 页时**还是不是人能用**的响应速度；
 * 3. 由此判断：「加倒排索引（FTS / 词元表）」是必要的，还是把复杂度花在了错的地方。
 *
 * ## 它怎么造数（重要，否则数字没有解释力）
 *
 * - 表结构**逐字**取自 `app/schemas/.../10.json`（见 [V10Schema]），所以 `MIGRATION_10_11`
 *   跑在一张「和用户手机上那一版一模一样」的 `messages` 表上；
 * - 10 万条消息按 1000 条/事务灌进去，`createdAt` 严格递增（1 秒一条），内容中英混排，
 *   关键词按**固定比例**埋进去：「晚安」占 20%（2 万条命中）、「项目」「压力」合计约 25%、
 *   「体检报告」约 0.29%（280 条左右）。比例是构造出来的，所以「命中数」这一列可以当作
 *   「同一份语料下不同查询的代价」来读。
 * - 单会话、单人格：检索的 `blocksQuery` 会一次枚举 500 块，这正是最坏形状（会话越长块越多）。
 *
 * ## 它怎么量
 *
 * - **迁移**：量两次。一次在数据库文件的副本上直接跑 `MIGRATION_10_11.migrate(db)`（纯 SQL + 回填），
 *   一次让 Room 打开原库（迁移 + Room 的 `onValidateSchema`），后者才是用户升级时真实经历的那一次；
 * - **检索**：走**生产代码**：`BlockSearch.page` + `MessageSearchSql` + 真实 Room DAO，
 *   唯一复制的是 `ChatRepository.blockScanSource` 那 15 行匿名实现（它是 private 的，
 *   而把 `ChatRepository` 的 25 个依赖在 instrumented test 里拼出来没有意义）。
 * - 第 N 页的游标不靠「翻 99 页」得到，而是**先做一次全量命中扫描**，按 `BlockSearch.compare`
 *   排序后取第 `30N` 条——排序键唯一确定，所以这等价于真的翻过去，但少扫 99 遍。
 *
 * ## 跑法
 *
 * 默认**跳过**（见 [benchEnabled]）：它是分钟级的测量，不该拖慢别人跑 UI / 迁移测试。
 * 命令写在 `docs/performance.md`。
 */
@RunWith(AndroidJUnit4::class)
class LargeHistoryBenchmarkTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val recorder = BenchRecorder("large-history")
    private val openedHelpers = mutableListOf<SupportSQLiteOpenHelper>()
    private var room: MyFriendDatabase? = null

    @Before
    fun setUp() {
        assumeTrue("benchmark 默认跳过：需要 -Pandroid.testInstrumentationRunnerArguments.bench=true", benchEnabled())
    }

    @After
    fun tearDown() {
        runCatching { room?.close() }
        room = null
        openedHelpers.forEach { runCatching { it.close() } }
        openedHelpers.clear()
        runCatching { context.deleteDatabase(DB_NAME) }
        runCatching { context.deleteDatabase(COPY_DB_NAME) }
    }

    @Test
    fun 十万条历史的迁移与逐块检索基准() {
        val messageCount = intArgument("messages", DEFAULT_MESSAGES).coerceIn(1_000, 200_000)
        recorder.putEnvironment(context)
        recorder.put("fixture.messageCount", messageCount)
        recorder.put("fixture.blockSize", BLOCK_SIZE)
        recorder.put("fixture.sessionCount", 1)

        // ---- 1. 造 v10 形态的库 + 灌 10 万条 --------------------------------
        val legacy = createV10Database()
        insertPersonaAndSession(legacy, messageCount)
        val insert = insertMessages(legacy, messageCount)
        recorder.put("fixture.insertMs", round2(insert.elapsedMs))
        recorder.put("fixture.insertBatches", insert.batches)
        recorder.put("fixture.insertRowsPerSecond", round2(messageCount / (insert.elapsedMs / 1000.0)))
        recorder.put("fixture.contentAvgUtf8Bytes", insert.averageContentBytes)
        recorder.put("fixture.dbBytesAfterInsert", insert.dbBytes)
        recorder.put("fixture.walBytesAfterInsert", insert.walBytes)
        recorder.put("fixture.avgContentChars", insert.averageChars)
        legacy.close()
        openedHelpers.forEach { runCatching { it.close() } }
        openedHelpers.clear()

        // ---- 2. 纯迁移 SQL 的耗时（在副本上跑，原库留给 Room） ----------------
        val sqlOnlyMs = measureMigrationOnCopy()
        recorder.put("migration.sqlOnlyMs", round2(sqlOnlyMs))
        forceGc()
        val beforeMigration = readMemory(context)
        recorder.put("migration.beforeGcJavaUsedMb", beforeMigration.javaUsedMb)
        recorder.put("migration.beforeTotalPssMb", beforeMigration.totalPssMb)

        // ---- 3. 走 Room 打开（真实升级路径：迁移 + 表校验） ------------------
        val roomOpenCpuStart = processCpuMs()
        val roomOpenStart = System.nanoTime()
        val opened = openRoom()
        // 注意：Room 的 `build()` 只是建对象，真正的「打开 + 跑迁移 + onValidateSchema」
        // 发生在第一次拿 writableDatabase 的时候。计时必须把这一步包进来，
        // 否则量到的是 7ms 的对象构造，不是用户升级时等待的那几秒。
        val schemaVersion = opened.openHelper.writableDatabase.version
        val roomOpenMs = elapsedMsSince(roomOpenStart)
        recorder.put("migration.roomOpenCpuMs", processCpuMs() - roomOpenCpuStart)
        recorder.put("migration.roomOpenMs", round2(roomOpenMs))
        recorder.put("schema.versionAfterOpen", schemaVersion)
        recorder.put("schema.migrationsRegistered", migrations().joinToString(",") { "${it.startVersion}->${it.endVersion}" })
        forceGc()
        val afterMigration = readMemory(context)
        recorder.put("migration.afterGcJavaUsedMb", afterMigration.javaUsedMb)
        recorder.put("migration.afterTotalPssMb", afterMigration.totalPssMb)
        recorder.put("migration.deltaJavaUsedMb", round2(afterMigration.javaUsedMb - beforeMigration.javaUsedMb))
        recorder.put("migration.deltaTotalPssMb", round2(afterMigration.totalPssMb - beforeMigration.totalPssMb))

        // ---- 4. 迁移结果对不对：条数、块数、块分布 -------------------------
        val stats = verifyMigratedShape(opened, messageCount)
        recorder.put("after.messages", stats.messageCount)
        recorder.put("after.blocks", stats.blockCount)
        recorder.put("after.maxBlockIndex", stats.maxBlockIndex)
        recorder.put("after.blockMessageCountMin", stats.blockSizeMin)
        recorder.put("after.blockMessageCountMax", stats.blockSizeMax)
        recorder.put("after.blockMessageCountAvg", round2(stats.blockSizeAvg))
        recorder.put("after.blockHistogram", stats.histogram)
        recorder.put("after.blockSamples", stats.samples)
        forceGc()
        val afterVerify = readMemory(context)
        recorder.put("after.gcJavaUsedMb", afterVerify.javaUsedMb)
        recorder.put("after.totalPssMb", afterVerify.totalPssMb)
        recorder.put("after.dbBytes", databaseBytes(DB_NAME).first)
        recorder.put("after.walBytes", databaseBytes(DB_NAME).second)
        recorder.writeJsonFile(context)

        // ---- 5. 检索基准 -----------------------------------------------------
        try {
            runBlocking { measureSearches(opened) }
        } finally {
            // ---- 6. 落盘 -----------------------------------------------------
            // 放在 finally 里：断言失败也要留下已经量到的数字，否则一次跑 2 分钟全白费。
            recorder.writeJsonFile(context)
        }
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    private suspend fun measureSearches(database: MyFriendDatabase) {
        val limit = intArgument("pageSize", DEFAULT_PAGE_SIZE).coerceIn(1, 100)
        recorder.put("search.pageSize", limit)
        recorder.put("search.repetitions", REPETITIONS)

        // 块枚举本身的耗时：501 条 SQL 里那 1 条枚举，单独量一下才能说清「时间花在哪」。
        val enumerationMs = statsOf(REPETITIONS) {
            val query = MessageSearchSql.blocksQuery(null, null, null)
            database.messageBlockDao().searchBlocks(SimpleSQLiteQuery(query.sql, query.args.toTypedArray()))
        }
        recorder.putStats("search.blockEnumeration", enumerationMs)

        // 一块的读取耗时（不含排序）：逐块扫描的成本下限。
        val singleBlockMs = statsOf(REPETITIONS) {
            val query = MessageSearchSql.blockScanQuery(HOT_SINGLE_TERM, SESSION_ID, 250, null, null)
            database.messageDao().searchBlockMessages(SimpleSQLiteQuery(query.sql, query.args.toTypedArray()))
        }
        recorder.putStats("search.singleBlockScan", singleBlockMs)

        // 热门词（单词元，2 万条命中）：第 1 / 10 / 100 页。
        measureQuery(database, HOT_SINGLE_TERM, pages = listOf(1, 10, 100), limit = limit)
        recorder.writeJsonFile(context)
        // 热门词（双词元，SQL 里 3 个 LIKE）：只量第 1 页。
        measureQuery(database, HOT_MULTI_TERM, pages = listOf(1), limit = limit)
        recorder.writeJsonFile(context)
        // 冷门词（约 280 条命中）：命中少不代表扫得少——它照样要走完 500 块。
        measureQuery(database, RARE_TERM, pages = listOf(1), limit = limit)
        // 零命中：只剩「枚举 500 块 + 读 500 块 + 500 条 SQL」的成本，
        // 用来把总耗时拆成「扫描」与「给命中行打分」两笔。
        measureQuery(database, NO_HIT_TERM, pages = listOf(1), limit = limit)
    }

    private suspend fun measureQuery(
        database: MyFriendDatabase,
        query: String,
        pages: List<Int>,
        limit: Int
    ) {
        val key = queryKey(query)
        val filter = SearchFilter()
        val source = RoomBlockScanSource(database, query, filter)

        // 一次全量扫描：既是 JIT/SQLite 预热，也给出总命中数与每一页的游标。
        val collectCpuStart = processCpuMs()
        val collectStart = System.nanoTime()
        val allHits = collectHits(source, query, filter)
        val collectMs = elapsedMsSince(collectStart)
        val collectCpuMs = processCpuMs() - collectCpuStart
        val sorted = allHits.sortedWith { left, right -> BlockSearch.compare(left.cursor(), right.cursor()) }
        recorder.put("search.$key.collectScanMs", round2(collectMs))
        recorder.put("search.$key.collectScanCpuMs", collectCpuMs)
        recorder.put("search.$key.totalMatches", sorted.size)

        pages.forEach { page ->
            if (sorted.size < page * limit) {
                // 语料比例不对才会走到这里：报告里的「第 100 页」必须真的有第 3000 条命中。
                recorder.put("search.$key.page$page", "skipped:只命中${sorted.size}条，不够翻到第${page}页")
                return@forEach
            }
            val cursor = if (page <= 1) null else sorted[(page - 1) * limit - 1].cursor()
            // 每个页码各自预热一次：SQLite 的页缓存与 JIT 在本页的形状上再走一遍。
            BlockSearch.page(source, query, filter, limit, cursor)

            var pageOutput: BlockSearch.Page<MessageSearchRow>? = null
            val dual = dualStatsOf(REPETITIONS) {
                pageOutput = BlockSearch.page(source, query, filter, limit, cursor)
            }
            val pageContent = requireNotNull(pageOutput)
            recorder.putStats("search.$key.page$page", dual.wall)
            dual.cpu?.let { recorder.putStats("search.$key.page$page.cpu", it) }
            recorder.put("search.$key.page$page.hits", pageContent.hits.size)
            recorder.put("search.$key.page$page.hasMore", pageContent.hasMore)
            recorder.put("search.$key.page$page.totalMatches", pageContent.totalMatches)
            recorder.put("search.$key.page$page.scannedBlocks", pageContent.scannedBlocks)
            recorder.put("search.$key.page$page.nextCursorPresent", pageContent.nextCursor != null)

            // 正确性：这一页的内容必须等于「全量排序后的第 N 段」。
            val expected = sorted.drop((page - 1) * limit).take(limit)
                .map { it.cursor().messageId }
            assertEquals(
                "第 $page 页的内容与全量排序不一致（游标语义被改坏了？）",
                expected,
                pageContent.hits.map { it.cursor().messageId }
            )
            if (page > 1) assertTrue("第 $page 页应该还有下一页", pageContent.hasMore)
            recorder.writeJsonFile(context)
        }
    }

    /** 与 `BlockSearch.page` 的命中判定逐行一致，只是把命中留下来（用来算页码游标与总命中数）。 */
    private suspend fun collectHits(
        source: BlockSearch.BlockScanSource<MessageSearchRow>,
        query: String,
        filter: SearchFilter
    ): List<BlockSearch.Hit<MessageSearchRow>> {
        val text = query.trim()
        val hits = ArrayList<BlockSearch.Hit<MessageSearchRow>>(4096)
        source.blocks(filter).forEach { block ->
            source.messages(block).forEach { (input, payload) ->
                if (!filter.accepts(input)) return@forEach
                val terms = SearchRanking.matchedTerms(text, input.content)
                val contains = input.content.indexOf(text, ignoreCase = true) >= 0
                if (terms <= 0 && !contains) return@forEach
                hits += BlockSearch.Hit(
                    input = input,
                    payload = payload,
                    matchedTermCount = terms,
                    relevance = ChineseTerms.relevance(text, input.content)
                )
            }
        }
        return hits
    }

    private suspend fun statsOf(repetitions: Int, block: suspend () -> Unit): Stats {
        val samples = ArrayList<Double>(repetitions)
        block()
        repeat(repetitions) {
            val start = System.nanoTime()
            block()
            samples += elapsedMsSince(start)
        }
        return Stats.of(samples)
    }

    /** 同时记「墙钟」与「进程 CPU」的一对样本；CPU 拿不到时只记墙钟。 */
    private class DualStats(val wall: Stats, val cpu: Stats?)

    private suspend fun dualStatsOf(repetitions: Int, block: suspend () -> Unit): DualStats {
        val wallSamples = ArrayList<Double>(repetitions)
        val cpuSamples = ArrayList<Double>(repetitions)
        block()
        repeat(repetitions) {
            val cpuStart = processCpuMs()
            val start = System.nanoTime()
            block()
            wallSamples += elapsedMsSince(start)
            val cpu = processCpuMs() - cpuStart
            if (cpu >= 0) cpuSamples += cpu.toDouble()
        }
        return DualStats(
            wall = Stats.of(wallSamples),
            cpu = if (cpuSamples.size == repetitions) Stats.of(cpuSamples) else null
        )
    }

    /**
     * 检索数据源：与 `ChatRepository.blockScanSource` 完全同形（同样的两条 SQL、同样的映射）。
     *
     * 复制的理由写在类注释里：生产那份是 private，而把 `ChatRepository` 的整套依赖在
     * instrumented test 里拼出来只会让测试更难维护，测到的仍然是同样的 SQL 与同样的引擎。
     */
    private class RoomBlockScanSource(
        private val database: MyFriendDatabase,
        private val text: String,
        private val filter: SearchFilter
    ) : BlockSearch.BlockScanSource<MessageSearchRow> {

        override suspend fun blocks(filter: SearchFilter): List<BlockSearch.BlockKey> {
            val query = MessageSearchSql.blocksQuery(filter.personaId, filter.since, filter.until)
            if (query.sql.isEmpty()) return emptyList()
            return database.messageBlockDao()
                .searchBlocks(SimpleSQLiteQuery(query.sql, query.args.toTypedArray()))
                .map { BlockSearch.BlockKey(it.sessionId, it.blockIndex) }
        }

        override suspend fun messages(block: BlockSearch.BlockKey): List<Pair<RankInput, MessageSearchRow>> {
            val query = MessageSearchSql.blockScanQuery(
                text = text,
                sessionId = block.sessionId,
                blockIndex = block.blockIndex,
                since = filter.since,
                until = filter.until
            )
            if (query.sql.isEmpty()) return emptyList()
            return database.messageDao()
                .searchBlockMessages(SimpleSQLiteQuery(query.sql, query.args.toTypedArray()))
                .map { row ->
                    RankInput(
                        id = row.messageId,
                        content = row.content,
                        createdAt = row.createdAt,
                        personaId = row.personaId
                    ) to row
                }
        }
    }

    // ------------------------------------------------------------------
    // 造库 / 迁移 / 校验
    // ------------------------------------------------------------------

    private fun createV10Database(): SupportSQLiteDatabase {
        context.deleteDatabase(DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        V10Schema.statements.forEach { db.execSQL(it) }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        openedHelpers += helper
        return helper.writableDatabase
    }

    private fun insertPersonaAndSession(db: SupportSQLiteDatabase, messageCount: Int) {
        val now = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT INTO `personas` (
                `id`, `name`, `description`, `avatarUri`, `avatarSeed`, `prompt`, `category`, `version`,
                `defaultApiConfigId`, `modelOverride`, `isDefault`, `createdAt`, `updatedAt`
            ) VALUES ('$PERSONA_ID', '小满', '十万条历史基准', NULL, 7, '你是小满', 'COMPANION', 1,
                NULL, NULL, 1, $now, $now)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `chat_sessions` (
                `id`, `personaId`, `apiConfigId`, `model`, `title`, `lastMessagePreview`, `lastMessageAt`,
                `unreadCount`, `pinned`, `createdAt`, `updatedAt`, `lastPersonaVersion`,
                `lastMemoryExtractedCount`, `historySummary`, `historySummarizedUpTo`, `historySummaryAt`,
                `memoryRetentionOverride`, `extractionEveryOverride`
            ) VALUES ('$SESSION_ID', '$PERSONA_ID', NULL, 'bench-model', '十万条历史', '晚安', $now,
                0, 0, $now, $now, 1, 0, NULL, NULL, NULL, NULL, NULL)
            """.trimIndent()
        )
    }

    private data class InsertResult(
        val elapsedMs: Double,
        val batches: Int,
        val averageChars: Int,
        val averageContentBytes: Int,
        val dbBytes: Long,
        val walBytes: Long
    )

    private fun insertMessages(db: SupportSQLiteDatabase, count: Int): InsertResult {
        val statement = db.compileStatement(
            """
            INSERT INTO `messages` (
                `id`, `sessionId`, `personaId`, `role`, `content`, `createdAt`, `updatedAt`,
                `status`, `errorMessage`, `model`, `edited`, `attachmentsJson`, `toolTraceJson`, `readAt`
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETE', NULL, ?, 0, NULL, NULL, NULL)
            """.trimIndent()
        )
        var totalChars = 0L
        var totalBytes = 0L
        var batches = 0
        val cpuStart = processCpuMs()
        val started = System.nanoTime()
        var index = 0
        while (index < count) {
            db.beginTransaction()
            try {
                val end = minOf(index + INSERT_BATCH, count)
                while (index < end) {
                    val content = HistoryCorpus.contentAt(index)
                    totalChars += content.length
                    totalBytes += content.toByteArray(Charsets.UTF_8).size
                    statement.clearBindings()
                    statement.bindString(1, messageId(index))
                    statement.bindString(2, SESSION_ID)
                    statement.bindString(3, PERSONA_ID)
                    statement.bindString(4, if (index % 2 == 0) "USER" else "ASSISTANT")
                    statement.bindString(5, content)
                    statement.bindLong(6, BASE_TIME + index * 1000L)
                    statement.bindLong(7, BASE_TIME + index * 1000L)
                    statement.bindString(8, "bench-model")
                    statement.executeInsert()
                    index++
                }
                db.setTransactionSuccessful()
                batches++
            } finally {
                db.endTransaction()
            }
        }
        val elapsedMs = elapsedMsSince(started)
        recorder.put("fixture.insertCpuMs", processCpuMs() - cpuStart)
        val (dbBytes, walBytes) = databaseBytes(DB_NAME)
        return InsertResult(
            elapsedMs = elapsedMs,
            batches = batches,
            averageChars = (totalChars / count).toInt(),
            averageContentBytes = (totalBytes / count).toInt(),
            dbBytes = dbBytes,
            walBytes = walBytes
        )
    }

    /** 在库文件副本上直接跑一次 `MIGRATION_10_11`：这段是「迁移 SQL 本体」的耗时，不含 Room 校验。 */
    private fun measureMigrationOnCopy(): Double {
        val source = context.getDatabasePath(DB_NAME)
        val copy = context.getDatabasePath(COPY_DB_NAME)
        context.deleteDatabase(COPY_DB_NAME)
        source.copyTo(copy, overwrite = true)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(COPY_DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        openedHelpers += helper
        val db = helper.writableDatabase
        val cpuStart = processCpuMs()
        val started = System.nanoTime()
        MyFriendDatabase.MIGRATION_10_11.migrate(db)
        val elapsed = elapsedMsSince(started)
        recorder.put("migration.sqlOnlyCpuMs", processCpuMs() - cpuStart)
        val blocks = queryLong(db, "SELECT COUNT(*) FROM message_blocks")
        recorder.put("migration.sqlOnlyBlocks", blocks)
        helper.close()
        openedHelpers.remove(helper)
        context.deleteDatabase(COPY_DB_NAME)
        return elapsed
    }

    private fun openRoom(): MyFriendDatabase {
        val built = Room.databaseBuilder(context, MyFriendDatabase::class.java, DB_NAME)
            .addMigrations(*migrations())
            .allowMainThreadQueries()
            .build()
        room = built
        return built
    }

    /**
     * 注册给 Room 的迁移清单：**反射**取 `MyFriendDatabase` 上所有 `Migration` 常量。
     *
     * 为什么不用手写清单：另一个并行改动正在把 schema 升到 v12，手写清单会在那之后漏掉
     * `11 → 12`，Room 打开 v10 旧库时直接抛 `IllegalStateException: A migration from 11 to 12
     * was required but not found`，而这跟基准测的东西无关。反射让「无论当前是第几版，
     * 都把这条链走完」成立，报告里会打印实际注册到的链。
     */
    private fun migrations(): Array<Migration> {
        val found = LinkedHashMap<Int, Migration>()
        listOf(MyFriendDatabase::class.java, MyFriendDatabase.Companion::class.java).forEach { clazz ->
            clazz.declaredFields.forEach { field ->
                if (!Migration::class.java.isAssignableFrom(field.type)) return@forEach
                runCatching {
                    field.isAccessible = true
                    (field.get(null) as? Migration)?.let { found[it.startVersion] = it }
                }
            }
        }
        if (found.isEmpty()) {
            // 反射失败的兜底（R8/字段搬移）：至少保证 v10 → 当前这一档在。
            return arrayOf(
                MyFriendDatabase.MIGRATION_8_9,
                MyFriendDatabase.MIGRATION_9_10,
                MyFriendDatabase.MIGRATION_10_11
            )
        }
        return found.toSortedMap().values.toTypedArray()
    }

    private data class MigratedShape(
        val messageCount: Long,
        val blockCount: Long,
        val maxBlockIndex: Long,
        val blockSizeMin: Long,
        val blockSizeMax: Long,
        val blockSizeAvg: Double,
        val histogram: String,
        val samples: String
    )

    private fun verifyMigratedShape(db: MyFriendDatabase, expectedMessages: Int): MigratedShape {
        val messages = queryLong(db, "SELECT COUNT(*) FROM `messages`")
        assertEquals("迁移把消息弄丢了", expectedMessages.toLong(), messages)
        val blocks = queryLong(db, "SELECT COUNT(*) FROM `message_blocks`")
        val maxBlockIndex = queryLong(db, "SELECT MAX(`blockIndex`) FROM `message_blocks`")
        val min = queryLong(db, "SELECT MIN(`messageCount`) FROM `message_blocks`")
        val max = queryLong(db, "SELECT MAX(`messageCount`) FROM `message_blocks`")
        val avg = queryDouble(db, "SELECT AVG(`messageCount`) FROM `message_blocks`")

        val histogram = StringBuilder()
        db.query("SELECT `messageCount`, COUNT(*) FROM `message_blocks` GROUP BY `messageCount` ORDER BY `messageCount`", emptyArray())
            .use { cursor ->
                while (cursor.moveToNext()) {
                    if (histogram.isNotEmpty()) histogram.append(' ')
                    histogram.append("${cursor.getLong(0)}条×${cursor.getLong(1)}块")
                }
            }

        val samples = StringBuilder()
        db.query("SELECT `blockIndex`, `messageCount`, `firstMessageId`, `lastMessageId` FROM `message_blocks` ORDER BY `blockIndex` LIMIT 3", emptyArray())
            .use { cursor ->
                while (cursor.moveToNext()) {
                    if (samples.isNotEmpty()) samples.append("; ")
                    samples.append("#${cursor.getLong(0)}:${cursor.getLong(1)}条(${cursor.getString(2)}..${cursor.getString(3)})")
                }
            }
        db.query("SELECT `blockIndex`, `messageCount`, `firstMessageId`, `lastMessageId` FROM `message_blocks` ORDER BY `blockIndex` DESC LIMIT 2", emptyArray())
            .use { cursor ->
                while (cursor.moveToNext()) {
                    samples.append("; #${cursor.getLong(0)}:${cursor.getLong(1)}条(${cursor.getString(2)}..${cursor.getString(3)})")
                }
            }

        // 每个 blockIndex 的条数必须与「行号 / BLOCK_SIZE」这条规则一致（抽样首尾）。
        db.query("SELECT `id`, `blockIndex` FROM `messages` ORDER BY `createdAt` ASC, `id` ASC LIMIT 1", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) {
                assertEquals("第一条消息的 blockIndex 应该是 0", 0L, cursor.getLong(1))
            }
        }
        db.query("SELECT `blockIndex` FROM `messages` ORDER BY `createdAt` DESC, `id` DESC LIMIT 1", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) {
                assertEquals(
                    "最后一条消息的 blockIndex 应该是 ${(expectedMessages - 1) / BLOCK_SIZE}",
                    ((expectedMessages - 1) / BLOCK_SIZE).toLong(),
                    cursor.getLong(0)
                )
            }
        }

        return MigratedShape(
            messageCount = messages,
            blockCount = blocks,
            maxBlockIndex = maxBlockIndex,
            blockSizeMin = min,
            blockSizeMax = max,
            blockSizeAvg = avg,
            histogram = histogram.toString(),
            samples = samples.toString()
        )
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun databaseBytes(name: String): Pair<Long, Long> {
        val db = context.getDatabasePath(name)
        val wal = File(db.path + "-wal")
        return db.length() to (if (wal.exists()) wal.length() else 0L)
    }

    private fun queryLong(db: MyFriendDatabase, sql: String): Long =
        db.query(sql, emptyArray()).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }

    private fun queryDouble(db: MyFriendDatabase, sql: String): Double =
        db.query(sql, emptyArray()).use { cursor -> if (cursor.moveToFirst()) cursor.getDouble(0) else -1.0 }

    private fun queryLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }

    private fun benchEnabled(): Boolean =
        InstrumentationRegistry.getArguments().getString("bench") == "true"

    private fun intArgument(key: String, fallback: Int): Int =
        InstrumentationRegistry.getArguments().getString(key)?.toIntOrNull() ?: fallback

    private fun messageId(index: Int): String = String.format(Locale.US, "m%06d", index)

    private fun queryKey(query: String): String = when (query) {
        HOT_SINGLE_TERM -> "hot-single"
        HOT_MULTI_TERM -> "hot-multi"
        RARE_TERM -> "rare"
        NO_HIT_TERM -> "no-hit"
        else -> query.replace(' ', '-')
    }

    private companion object {
        const val DB_NAME = "bench-large.db"
        const val COPY_DB_NAME = "bench-large-copy.db"
        const val PERSONA_ID = "persona-bench"
        const val SESSION_ID = "session-bench"
        const val BLOCK_SIZE = 200
        const val DEFAULT_MESSAGES = 100_000
        const val DEFAULT_PAGE_SIZE = 30
        const val REPETITIONS = 5
        const val INSERT_BATCH = 1_000

        /** 语料的固定时间基准：`createdAt = BASE_TIME + index * 1000`（1 秒一条，跨约 27.8 小时）。 */
        const val BASE_TIME = 1_700_000_000_000L

        /** 单词元热门词：「晚安」→ SQL 只有 1 个 LIKE。 */
        const val HOT_SINGLE_TERM = "晚安"

        /** 双词元热门词：「项目」「压力」→ SQL 有 3 个 LIKE（原文 + 2 个词元）。 */
        const val HOT_MULTI_TERM = "项目 压力"

        /** 冷门词：命中约 280 条，但仍然要扫完 500 块。 */
        const val RARE_TERM = "体检报告"

        /** 零命中查询：只为量「扫完 500 块但一条都不打分」的底噪。 */
        const val NO_HIT_TERM = "量子力学导论"
    }
}

/**
 * 10 万条消息的语料生成器（确定性：同一个 index 永远生成同一句话）。
 *
 * 关键词比例是**故意**固定的，因为报告里要用「同一份语料」比较不同查询的代价：
 * - 「晚安」每 5 条必现（20% → 2 万条命中）；
 * - 「项目」每 8 条、「压力」每 7 条（合计约 25%）；
 * - 「体检报告」每 350 条（约 0.29% → 280 条左右）。
 *
 * 其余部分是中英混排的日常对话 + 一点点技术词，让正文长度与真实聊天接近
 * （平均约 80–150 字，`docs/performance.md` 里报告实测值）。
 */
internal object HistoryCorpus {

    private val chinesePool = listOf(
        "今天上班有点累，回家的路上买了杯冰美式。",
        "你还记得我们上次说的那家小馆子吗？我想再去一次。",
        "刚刚把窗台的花浇了，叶子有点蔫，可能是这几天太干。",
        "凌晨三点醒了，翻来覆去睡不着，就想和你说说话。",
        "我妈今天又打电话来问东问西，我敷衍了两句就挂了。",
        "楼下那只橘猫又来蹭饭了，我给它留了半盒罐头。",
        "周末想去看海，天气预报说会下雨，犹豫要不要改期。",
        "今天读到一段话，说人到了一定年纪就开始练习告别。",
        "冰箱里只剩两个鸡蛋了，明天得去趟超市。",
        "刚才那阵风很凉，像小时候夏天傍晚的味道。",
        "我在想要不要换个城市生活一段时间，换个节奏。",
        "你今天有没有好好吃饭？别又拿咖啡当午饭。",
        "地铁上一直在听那张老专辑，突然有点鼻酸。",
        "把书桌收拾了一遍，翻出一张很久以前的电影票。",
        "刚洗完澡，头发还在滴水，屋里很安静。",
        "明天要早起开会，可我现在一点都不困。",
        "我妹说她想学画画，问我小时候是不是也喜欢。",
        "今天的夕阳特别好看，可惜你没在。"
    )

    private val englishPool = listOf(
        "I reread the old thread tonight — your line about the harbor still gets me.",
        "Still stuck on that flaky test; I think the retry budget was the culprit.",
        "Coffee count today: three. Not proud of it.",
        "Took the long way home just to finish the podcast.",
        "The rain stopped right before I left the office, which felt like a small mercy.",
        "Some nights the city is loud in a way that feels like company."
    )

    fun contentAt(index: Int): String {
        val random = Random(index * 7919L + 13L)
        val builder = StringBuilder(160)
        builder.append(chinesePool[random.nextInt(chinesePool.size)])
        builder.append(' ')

        if (index % 5 == 0) {
            builder.append("他停了一下，还是说了句：晚安，明天见。").append(' ')
        }
        if (index % 8 == 0) {
            builder.append("项目又延期了，周末大概要在改需求里过。").append(' ')
        }
        if (index % 7 == 0) {
            builder.append("最近压力有点大，睡得不太好。").append(' ')
        }
        if (index % 350 == 0) {
            builder.append("体检报告出来了，医生说一切正常，让我少熬夜。").append(' ')
        }
        if (index % 3 == 0) {
            builder.append(englishPool[random.nextInt(englishPool.size)]).append(' ')
        }
        if (index % 11 == 0) {
            builder.append("顺手记一下：`gradle :app:connectedDebugAndroidTest` 又超时了。")
        }
        return builder.toString().trim()
    }
}
