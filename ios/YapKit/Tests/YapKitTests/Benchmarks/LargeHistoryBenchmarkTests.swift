import Foundation
import XCTest
@testable import YapKit

// ---------------------------------------------------------------------------
// iOS 大库基准：10 万条消息的 v10 → v11 迁移 + 检索第 1/10/100 页耗时。
//
// 与 Android 侧同口径（数字要能并排放进 `docs/performance-ios.md`）：
// - 数据：单会话、`createdAt` 递增、中英混排、关键词按固定密度埋（每 10 条 1 条「约定」）；
// - 迁移：造一个**真的 v10 形状**的库（先建到 v11 再回退 messages.blockIndex / message_blocks，
//   与 `SchemaMigrationTests` 同一手法），然后量 `DatabaseStores.open` —— 迁移就跑在里面；
// - 检索：走**真实** `MessageStore.search`（逐区块 + 键集游标 + 无候选上限），每页 30 条，
//   第 1/10/100 页各重复 5 次取 median/min/max；同时用 `queryObserver` 数「扫了多少块、读了多少行」，
//   证明「成本再高也与页号无关」；
// - 内存：`task_info` 的 resident_size + phys_footprint，前后各取一次（还页 + 取最小值），
//   迁移期间用 5ms 采样线程记峰值。
//
// 为什么这些数字必须在这个仓库里、用真 SQLite 文件跑：
// `MessageStore.search` 的成本分布在 SQL（每块一次查询）与 Swift 精排（`SearchRanking`）两处，
// 任何 mock 都会把「逐块枚举」这件事测没；只有真库才能看出 500 次块查询 + 10 万行精排的真实代价。
// ---------------------------------------------------------------------------

final class LargeHistoryBenchmarkTests: XCTestCase {

    // MARK: - 基准参数（报告里要写清楚，改这里等于改口径）

    /// 默认 10 万条；`BENCH_MESSAGE_COUNT=40000` 之类的覆盖只用于冒烟（每 10 条 1 条命中，
    /// 翻到第 100 页至少需要 3 万条消息，否则第 100 页会不满 30 条）。
    private static let messageCount = benchmarkInt("BENCH_MESSAGE_COUNT", default: 100_000)
    /// 界面每页 30 条（`MessageStore.defaultSearchPageSize`）。
    private static let pageSize = MessageStore.defaultSearchPageSize
    private static let repeats = benchmarkInt("BENCH_REPEATS", default: 5)
    private static let secondaryRepeats = benchmarkInt("BENCH_SECONDARY_REPEATS", default: 3)

    /// 环境变量覆盖（只给调试/冒烟用；正式报告用默认值）。
    private static func benchmarkInt(_ name: String, default fallback: Int) -> Int {
        guard let raw = ProcessInfo.processInfo.environment[name], let value = Int(raw), value > 0 else {
            return fallback
        }
        return value
    }
    /// 主查询：中文关键词（每 10 条命中 1 条 → 1 万条命中、334 页，足以翻到第 100 页）。
    private static let primaryQuery = "约定"
    /// 次查询：中英混排多词元（同一批消息两个词元都命中，验证 matchCount=2 的精排路径）。
    private static let secondaryQuery = "约定 harbin"

    private static let personaId = "persona-bench"
    private static let sessionId = "session-bench"
    /// 固定基准时间（2026 年 11 月附近），保证两次运行的数字可比。
    private static let baseTimestamp: Int64 = 1_794_500_000_000

    private var directory: URL!
    private var databasePath: String!
    private var stores: DatabaseStores?

    override func setUpWithError() throws {
        try super.setUpWithError()
        // 默认 skip：常规 `swift test` 不该为基准多花一分钟。
        try BenchmarkGate.require()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-bench-history-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databasePath = directory.appendingPathComponent("bench-v10.sqlite").path
    }

    override func tearDownWithError() throws {
        if let stores { stores.database.close() }
        stores = nil
        if let directory { try? FileManager.default.removeItem(at: directory) }
        try super.tearDownWithError()
    }

    // MARK: - 主用例

    func testV10ToV11MigrationAndSearchPagingOn100kMessages() throws {
        // 1) 造 v10 库（100k 行单事务插入）
        let seed = try seedV10Database(messageCount: Self.messageCount)
        let seedRows: [(String, String)] = [
            ("数据集", "\(Self.messageCount) 条消息 / 1 会话 / 1 人格 / v10 形态"),
            ("建库插入", BenchmarkFormat.ms(seed.insertMilliseconds)),
            ("v10 库体积", BenchmarkFormat.bytes(seed.fileBytes))
        ]
        print(BenchmarkFormat.table(seedRows))

        // 2) 迁移：打开库 = 迁移（计时 + 5ms 内存采样）
        let baseline = ProcessMemory.settled()
        let sampler = PeakMemorySampler(baseline: baseline)
        sampler.start()
        let openStart = BenchmarkClock.nowNanos()
        let opened = try DatabaseStores.open(path: databasePath)
        let migrationMilliseconds = BenchmarkClock.milliseconds(since: openStart)
        let peak = sampler.stop().peak
        let after = ProcessMemory.settled(samples: 2, pauseMicroseconds: 15_000)
        let migrationMemory = ProcessMemory.Delta(baseline: baseline, peak: peak, after: after)
        stores = opened

        // 3) 迁移正确性（性能数字必须建立在「迁对了」之上）
        let version = try opened.database.userVersion()
        XCTAssertEqual(version, Schema.version, "迁移后 user_version 必须是 \(Schema.version)")
        let messagesAfter = try opened.messages.count()
        XCTAssertEqual(messagesAfter, Self.messageCount, "迁移不能丢消息")
        let blockCount = try opened.messageBlocks.count()
        let expectedBlockCount = Self.messageCount / MessageBlocks.blockSize
        XCTAssertEqual(blockCount, expectedBlockCount, "100k / 200 应当正好 \(expectedBlockCount) 块")
        XCTAssertEqual(try opened.messages.blockIndex(ofMessage: Self.messageId(0)), 0)
        XCTAssertEqual(
            try opened.messages.blockIndex(ofMessage: Self.messageId(Self.messageCount - 1)),
            expectedBlockCount - 1
        )
        let blocks = try opened.messageBlocks.blocks(sessionId: Self.sessionId)
        XCTAssertEqual(blocks.count, expectedBlockCount)
        let sampledBlockIndexes = [0, expectedBlockCount / 2, expectedBlockCount - 1]
        var sampledBlocks: [String: [String: Any]] = [:]
        for index in sampledBlockIndexes {
            let block = blocks[index]
            sampledBlocks["block \(index)"] = [
                "messageCount": block.messageCount,
                "firstMessageId": block.firstMessageId,
                "lastMessageId": block.lastMessageId,
                "firstCreatedAt": block.firstCreatedAt,
                "lastCreatedAt": block.lastCreatedAt
            ]
        }
        for index in sampledBlockIndexes {
            let block = blocks[index]
            XCTAssertEqual(block.messageCount, MessageBlocks.blockSize, "满块应当是 200 条")
            XCTAssertEqual(block.firstMessageId, Self.messageId(index * MessageBlocks.blockSize))
            XCTAssertEqual(block.lastMessageId, Self.messageId(index * MessageBlocks.blockSize + MessageBlocks.blockSize - 1))
        }
        XCTAssertTrue(try opened.messageBlocks.inconsistencies().isEmpty, "块表必须与消息表自洽")
        let sizeAfter = databaseFileBytes(path: databasePath)

        print(BenchmarkFormat.table([
            ("迁移耗时", BenchmarkFormat.ms(migrationMilliseconds)),
            ("消息数", "\(messagesAfter)"),
            ("块数", "\(blockCount)"),
            ("抽样块", sampledBlockIndexes.map { index in
                "block \(index)=\(blocks[index].messageCount) 条"
            }.joined(separator: "，")),
            ("迁移前内存(footprint)", BenchmarkFormat.mb(migrationMemory.baseline.footprintMegabytes)),
            ("迁移后内存(footprint)", BenchmarkFormat.mb(after.footprintMegabytes)),
            ("迁移峰值增量(footprint)", BenchmarkFormat.mb(ProcessMemory.Snapshot.megabytes(migrationMemory.peakFootprintDeltaBytes))),
            ("迁移峰值增量(resident)", BenchmarkFormat.mb(ProcessMemory.Snapshot.megabytes(migrationMemory.peakResidentDeltaBytes)))
        ]))

        // 4) 检索：命中总数（同一套 `SearchRanking.isHit` 判定，不是 SQL LIKE 的近似）
        let hitTotals = try countHitTotals(stores: opened)
        let expectedHits = Self.messageCount / 10
        XCTAssertEqual(hitTotals.primary, expectedHits, "每 10 条 1 条含「约定」，命中总数应当是 \(expectedHits)")
        XCTAssertEqual(hitTotals.secondary, expectedHits, "同一批消息同时含「约定」「harbin」")
        XCTAssertEqual(hitTotals.sqlLikePrimary, expectedHits, "SQL LIKE 计数用来交叉验证")
        print(BenchmarkFormat.table([
            ("命中总数(约定)", "\(hitTotals.primary)（SQL LIKE：\(hitTotals.sqlLikePrimary)）"),
            ("命中总数(约定 harbin)", "\(hitTotals.secondary)（SQL LIKE：\(hitTotals.sqlLikeSecondary)）"),
            ("命中统计耗时", BenchmarkFormat.ms(hitTotals.milliseconds))
        ]))

        // 5) 检索：扫描代价（扫多少块 / 读多少行）+ 每页耗时
        let scanStats = try measureScanStats(stores: opened)
        XCTAssertEqual(scanStats.blocksVisited, expectedBlockCount, "一次检索应当枚举全部块（无候选上限）")
        XCTAssertEqual(scanStats.rowsMaterialized, Self.messageCount, "一次检索应当读一遍全部消息正文")

        var measurements: [PageMeasurement] = []
        for page in [1, 10, 100] {
            let measurement = try measurePage(page, stores: opened)
            measurements.append(measurement)
            let stats = measurement.stats
            print(BenchmarkFormat.table([
                ("第 \(page) 页", "median \(BenchmarkFormat.ms(stats.medianMilliseconds))"
                    + " / min \(BenchmarkFormat.ms(stats.minMilliseconds))"
                    + " / max \(BenchmarkFormat.ms(stats.maxMilliseconds))"
                    + " · 命中 \(measurement.hits) 条 · hasMore=\(measurement.hasMore)"),
                ("  一次性取游标", BenchmarkFormat.ms(measurement.cursorPreparationMilliseconds)
                    + "（limit=\(Self.pageSize * (page - 1))，与逐页翻 \(page - 1) 次等价，不计入页耗时）")
            ]))
        }
        // 跨页正确性：同分并列时按 createdAt 降序，所以越往后越旧。
        for (older, newer) in zip(measurements.dropFirst(), measurements) {
            XCTAssertLessThan(
                older.firstCreatedAt, newer.firstCreatedAt,
                "第 \(older.page) 页的首条应当比第 \(newer.page) 页更旧"
            )
        }
        let secondary = try measureSecondaryQuery(stores: opened)
        XCTAssertEqual(secondary.hits, Self.pageSize)
        XCTAssertTrue(secondary.hasMore)

        // 6) 落盘 JSON（/tmp/ios-bench-<stamp>-history.json）+ 打印总表
        let environment: [String: Any] = BenchmarkEnvironment.capture(extra: [
            "schemaVersionAfterMigration": Int(version),
            "searchImplementation": "MessageStore.search：逐区块枚举 + 每块内存精排 + 键集游标 + 无候选上限",
            "migrationPath": "Schema.create → migrateLegacyData → backfillMessageBlocks（窗口函数回填块号 + 建块行）"
        ])
        let dataset: [String: Any] = [
            "messages": Self.messageCount,
            "sessions": 1,
            "personas": 1,
            "shape": "v10：messages 无 blockIndex、无 message_blocks 表（其余列与 v11 相同）",
            "createdAtBase": Self.baseTimestamp,
            "createdAtStepMs": 1_000,
            "contentPattern": "每 10 条 1 条含「约定」与「harbin」（中英混排），其余为普通中文短句",
            "insertMilliseconds": BenchmarkStats.round(seed.insertMilliseconds),
            "bytesBeforeMigration": Int(seed.fileBytes),
            "filesBeforeMigration": seed.files.mapValues { Int($0) }
        ]
        let migration: [String: Any] = [
            "durationMs": BenchmarkStats.round(migrationMilliseconds),
            "userVersionAfter": Int(version),
            "messagesAfter": messagesAfter,
            "blocksAfter": blockCount,
            "expectedBlocks": expectedBlockCount,
            "sampledBlocks": sampledBlocks,
            "inconsistencies": try opened.messageBlocks.inconsistencies(),
            "memory": migrationMemory.dictionary,
            "bytesAfterMigration": Int(sizeAfter.total),
            "filesAfterMigration": sizeAfter.files.mapValues { Int($0) },
            "bytesDelta": Int(sizeAfter.total - seed.fileBytes)
        ]
        let search: [String: Any] = [
            "query": Self.primaryQuery,
            "secondaryQuery": Self.secondaryQuery,
            "pageSize": Self.pageSize,
            "repeats": Self.repeats,
            "hitsTotal": hitTotals.primary,
            "hitsTotalSecondary": hitTotals.secondary,
            "sqlLikeHits": hitTotals.sqlLikePrimary,
            "sqlLikeHitsSecondary": hitTotals.sqlLikeSecondary,
            "hitTotalMeasurementMs": BenchmarkStats.round(hitTotals.milliseconds),
            "scanStats": scanStats.dictionary,
            "pages": measurements.map { $0.dictionary },
            "secondaryQueryPage1": secondary.dictionary
        ]
        let methodology: [String: Any] = [
            "clock": "DispatchTime.uptimeNanoseconds（单调时钟）",
            "pageTiming": "先串行翻到第 N-1 页拿到游标（不计时），再对第 N 页重复 \(Self.repeats) 次；每次都是完整的一次 MessageStore.search（全量块枚举 + 精排）",
            "cursorPreparationMilliseconds": "一次性检索 limit=30×(N-1) 取第 N 页游标的耗时（与逐页翻 N-1 次等价，不计入页耗时）；Debug 构建下逐页翻 99 次要扫 99 遍 10 万行，纯属重复劳动",
            "memory": "task_info(MACH_TASK_BASIC_INFO).resident_size + task_info(TASK_VM_INFO).phys_footprint；前后基线先 malloc_zone_pressure_relief 再取 3 次最小值；峰值由 5ms 采样线程记录",
            "notes": [
                "迁移耗时包含 SQLite open + PRAGMA journal_mode=WAL + Schema.create 的整个事务（真实启动路径）",
                "检索耗时包含会话/人格联查与摘要构建（界面真正要的东西），不只 SQL",
                "第 N 页耗时是对该页做完整 search(limit: 30, cursor:) 的中位数；到达该页的游标用一次性检索构造，不计时"
            ]
        ]
        let payload: [String: Any] = [
            "kind": "ios-large-history",
            "generatedAt": iso8601Now(),
            "howToRun": "cd ios/YapKit && RUN_BENCHMARKS=1 swift test --disable-sandbox --filter LargeHistoryBenchmarkTests",
            "environment": environment,
            "dataset": dataset,
            "migration": migration,
            "search": search,
            "methodology": methodology,
            "report": BenchmarkFormat.table(seedRows)
        ]
        BenchmarkOutput.write(payload, name: "history")
    }

    // MARK: - v10 数据构造

    private struct SeedResult {
        var insertMilliseconds: Double
        var fileBytes: Int64
        var files: [String: Int64]
    }

    /// 造一个「v10 形状」的库：与 `SchemaMigrationTests.seedV10Database` 同一手法 ——
    /// 先把库建到当前版本，再把 v11 加的东西（块表 / 块号列 / 块索引）拆掉、灌旧数据、
    /// 把 `user_version` 记回 10。这样待会儿打开时走的就是**真实**的升级路径。
    private func seedV10Database(messageCount: Int) throws -> SeedResult {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let database = try XCTUnwrap(stores?.database)

        try database.execute("DROP TABLE \(Schema.messageBlocksTable)")
        try database.execute("DROP INDEX IF EXISTS index_messages_sessionId_blockIndex_createdAt")
        try database.execute("ALTER TABLE messages DROP COLUMN blockIndex")

        try database.execute("""
            INSERT INTO personas (id, name, description, avatarSeed, prompt, category, version,
                                  isDefault, createdAt, updatedAt)
            VALUES ('\(Self.personaId)', '林岸', 'v10 基准数据', 42, '你叫林岸。', '伴侣', 3, 1, 1, 1)
            """)
        let lastCreatedAt = Self.baseTimestamp + Int64(messageCount - 1) * 1_000
        try database.execute("""
            INSERT INTO chat_sessions (id, personaId, apiConfigId, model, title, lastMessagePreview,
                lastMessageAt, unreadCount, pinned, createdAt, updatedAt, lastPersonaVersion,
                syncState, lastMemoryExtractedCount)
            VALUES ('\(Self.sessionId)', '\(Self.personaId)', NULL, 'deepseek-chat', '十万句的对话', '最后一句',
                \(lastCreatedAt), 0, 0, 1, \(lastCreatedAt), 3, 'STATELESS', 0)
            """)

        let insertMilliseconds = try insertMessages(into: database, messageCount: messageCount)
        try database.setUserVersion(10)
        stores = nil
        database.close()

        let sizes = databaseFileBytes(path: databasePath)
        return SeedResult(
            insertMilliseconds: insertMilliseconds,
            fileBytes: sizes.total,
            files: sizes.files
        )
    }

    /// 100k 行必须放进**一个事务**：否则每行一次 fsync，几十秒起步（这也顺带记录插入耗时）。
    private func insertMessages(into database: SQLiteDatabase, messageCount: Int) throws -> Double {
        let sql = """
            INSERT INTO messages (id, sessionId, personaId, role, content, createdAt, updatedAt,
                                  status, edited)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETE', 0)
            """
        let statement = try database.prepare(sql)
        let start = BenchmarkClock.nowNanos()
        try database.inTransaction {
            for index in 0..<messageCount {
                let createdAt = Self.baseTimestamp + Int64(index) * 1_000
                let role = index % 2 == 0 ? MessageRole.user.stored : MessageRole.assistant.stored
                try statement.bind([
                    .text(Self.messageId(index)),
                    .text(Self.sessionId),
                    .text(Self.personaId),
                    .text(role),
                    .text(Self.messageContent(index)),
                    .int(createdAt),
                    .int(createdAt)
                ])
                _ = try statement.step()
                statement.reset()
            }
        }
        return BenchmarkClock.milliseconds(since: start)
    }

    /// 中英混排：每 10 条 1 条带「约定」「harbin」（约 1 万条命中），其余是普通中文短句 +
    /// 英文后缀（`daily-123`），保证「检索要扫过大量不命中的正文」这一真实成本也在里面。
    private static func messageContent(_ index: Int) -> String {
        switch index % 10 {
        case 0:
            return "第 \(index) 句。我们说好的约定还算数，等你从 harbin 回来。plan-\(index)"
        case 5:
            return "第 \(index) 句。今天路过那家旧书店，想起你提过的那本书。memory-\(index)"
        default:
            return "第 \(index) 句。很普通的一天，忙完就睡了，明天再聊。daily-\(index)"
        }
    }

    private static func messageId(_ index: Int) -> String {
        String(format: "m-%05d", index)
    }

    // MARK: - 检索测量

    private struct PageMeasurement {
        var page: Int
        /// 一次性取「第 N 页游标」那次检索的耗时（limit=30×(N-1)，不计入页耗时）。
        var cursorPreparationMilliseconds: Double
        var stats: BenchmarkStats
        var hits: Int
        var hasMore: Bool
        var nextCursorIsNil: Bool
        var firstCreatedAt: Int64

        var dictionary: [String: Any] {
            [
                "page": page,
                "cursorPreparationMs": BenchmarkStats.round(cursorPreparationMilliseconds),
                "timing": stats.dictionary,
                "pageHits": hits,
                "hasMore": hasMore,
                "nextCursorIsNil": nextCursorIsNil,
                "firstHitCreatedAt": firstCreatedAt
            ]
        }
    }

    /// 第 `targetPage` 页的游标（一次性检索得到，不计入该页耗时）。
    ///
    /// 等价性：游标就是「排序后第 `30 × (targetPage-1)` 条命中的位置」。逐页翻 99 次
    /// 与一次 `limit: 30×99` 拿到的是**同一个**游标（同一个排序、同一套四键游标），
    /// 但前者在 Debug 构建下要完整扫 99 遍 10 万行（分钟级），所以这里用一次性检索取游标。
    /// 页耗时仍然是对第 N 页做完整的 `search(limit: 30, cursor:)`（真实界面路径）。
    private func cursorForPage(
        _ targetPage: Int,
        stores: DatabaseStores
    ) throws -> (cursor: SearchCursor?, preparationMilliseconds: Double) {
        guard targetPage > 1 else { return (nil, 0) }
        let limit = Self.pageSize * (targetPage - 1)
        let start = BenchmarkClock.nowNanos()
        let result = try stores.messages.search(query: Self.primaryQuery, limit: limit)
        let preparationMilliseconds = BenchmarkClock.milliseconds(since: start)
        XCTAssertEqual(result.hits.count, limit, "一次性取游标应当拿到前 \(limit) 条命中")
        guard let cursor = result.nextCursor else {
            XCTFail("命中数不足以到达第 \(targetPage) 页（没有 nextCursor）")
            return (nil, preparationMilliseconds)
        }
        return (cursor, preparationMilliseconds)
    }

    private func measurePage(_ page: Int, stores: DatabaseStores) throws -> PageMeasurement {
        let (cursor, cursorPreparationMilliseconds) = try cursorForPage(page, stores: stores)
        // 预热一次（同一游标）：把 SQLite page cache 与分配器热起来，避免把冷启动算进页耗时。
        _ = try stores.messages.search(query: Self.primaryQuery, limit: Self.pageSize, cursor: cursor)

        var samples: [Double] = []
        samples.reserveCapacity(Self.repeats)
        var lastPage = SearchPage.empty
        for _ in 0..<Self.repeats {
            let (result, milliseconds) = try BenchmarkClock.measure {
                try stores.messages.search(query: Self.primaryQuery, limit: Self.pageSize, cursor: cursor)
            }
            samples.append(milliseconds)
            lastPage = result
            XCTAssertEqual(result.hits.count, Self.pageSize, "第 \(page) 页应当正好 \(Self.pageSize) 条")
            XCTAssertTrue(result.hasMore, "第 \(page) 页后面应当还有内容")
            XCTAssertNotNil(result.nextCursor, "第 \(page) 页应当带下一页游标")
        }
        return PageMeasurement(
            page: page,
            cursorPreparationMilliseconds: cursorPreparationMilliseconds,
            stats: BenchmarkStats(samples),
            hits: lastPage.hits.count,
            hasMore: lastPage.hasMore,
            nextCursorIsNil: lastPage.nextCursor == nil,
            firstCreatedAt: lastPage.hits.first?.createdAt ?? 0
        )
    }

    /// 中英混排多词元查询的第 1 页（验证 matchCount=2 的精排路径也要按同样代价扫描）。
    private func measureSecondaryQuery(stores: DatabaseStores) throws -> PageMeasurement {
        var samples: [Double] = []
        samples.reserveCapacity(Self.secondaryRepeats)
        var lastPage = SearchPage.empty
        for _ in 0..<Self.secondaryRepeats {
            let (result, milliseconds) = try BenchmarkClock.measure {
                try stores.messages.search(query: Self.secondaryQuery, limit: Self.pageSize)
            }
            samples.append(milliseconds)
            lastPage = result
            XCTAssertEqual(result.hits.count, Self.pageSize)
            XCTAssertTrue(result.hasMore)
        }
        return PageMeasurement(
            page: 1,
            // 次查询从第 1 页开始，没有「取游标」这一步。
            cursorPreparationMilliseconds: 0,
            stats: BenchmarkStats(samples),
            hits: lastPage.hits.count,
            hasMore: lastPage.hasMore,
            nextCursorIsNil: lastPage.nextCursor == nil,
            firstCreatedAt: lastPage.hits.first?.createdAt ?? 0
        )
    }

    /// 一次检索到底扫了多少块 / 读了多少行（`MessageStore.queryObserver` 是内建的单测钩子）。
    private struct ScanStats {
        var blocksVisited: Int
        var rowsMaterialized: Int

        var dictionary: [String: Any] {
            [
                "blocksVisited": blocksVisited,
                "rowsMaterialized": rowsMaterialized,
                "observerNote": "queryObserver 挂在 MessageStore 上，只统计 searchBlock 这一种读取"
            ]
        }
    }

    private func measureScanStats(stores: DatabaseStores) throws -> ScanStats {
        var blocksVisited = 0
        var rowsMaterialized = 0
        stores.messages.queryObserver = { method, rows in
            guard method == "searchBlock" else { return }
            blocksVisited += 1
            rowsMaterialized += rows
        }
        defer { stores.messages.queryObserver = nil }
        _ = try stores.messages.search(query: Self.primaryQuery, limit: Self.pageSize)
        return ScanStats(blocksVisited: blocksVisited, rowsMaterialized: rowsMaterialized)
    }

    /// 命中总数：与 `MessageStore.search` 用同一个 `SearchRanking.isHit` 判定，逐块扫一遍。
    private func countHitTotals(stores: DatabaseStores) throws -> (
        primary: Int, secondary: Int, sqlLikePrimary: Int, sqlLikeSecondary: Int, milliseconds: Double
    ) {
        let start = BenchmarkClock.nowNanos()
        var primary = 0
        var secondary = 0
        // 只读 content 一列（不物化 MessageEntity）：这一遍只回答「按 ranking 判定有多少命中」。
        let statement = try stores.database.prepare("SELECT content FROM messages")
        while try statement.step() {
            let content = statement.text(at: 0) ?? ""
            if SearchRanking.isHit(query: Self.primaryQuery, content: content) {
                primary += 1
            }
            if SearchRanking.isHit(query: Self.secondaryQuery, content: content) {
                secondary += 1
            }
        }
        let milliseconds = BenchmarkClock.milliseconds(since: start)
        let sqlLikePrimary = Int(try stores.database.scalarInt64(
            "SELECT COUNT(*) FROM messages WHERE content LIKE ?",
            [.text("%\(Self.primaryQuery)%")]
        ))
        let sqlLikeSecondary = Int(try stores.database.scalarInt64(
            "SELECT COUNT(*) FROM messages WHERE content LIKE ? OR content LIKE ?",
            [.text("%约定%"), .text("%harbin%")]
        ))
        return (primary, secondary, sqlLikePrimary, sqlLikeSecondary, milliseconds)
    }

    // MARK: - 小工具

    private func databaseFileBytes(path: String) -> (total: Int64, files: [String: Int64]) {
        let fileManager = FileManager.default
        var total: Int64 = 0
        var files: [String: Int64] = [:]
        for suffix in ["", "-wal", "-shm"] {
            let candidate = path + suffix
            guard let attributes = try? fileManager.attributesOfItem(atPath: candidate),
                  let size = attributes[.size] as? NSNumber else { continue }
            let bytes = size.int64Value
            total += bytes
            files[suffix.isEmpty ? "db" : String(suffix.dropFirst())] = bytes
        }
        return (total, files)
    }

    private func iso8601Now() -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date())
    }
}
