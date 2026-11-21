import XCTest
@testable import YapKit

/// 0.15（schema v19）的两组列：人格的提示词语言 + 会话主题总结。
/// 这一版只 ADD COLUMN（不回填），所以两类测试：
/// 1. 新库：列存在、能存取、默认值正确；
/// 2. 老库（v18）：升级后列补上了，旧数据一行不少（读成 nil / 空 = 默认中文、还没总结过）。
final class SchemaV19Tests: XCTestCase {

    private var directory: URL!
    private var databasePath: String!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-v19-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databasePath = directory.appendingPathComponent("v19.sqlite").path
    }

    override func tearDownWithError() throws {
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(atPath: databasePath + suffix)
        }
        try? FileManager.default.removeItem(at: directory)
        try super.tearDownWithError()
    }

    private func columnNames(_ database: SQLiteDatabase, table: String) throws -> [String] {
        var names: [String] = []
        let statement = try database.prepare("PRAGMA table_info(\(table))")
        while try statement.step() {
            if let name = statement.text(at: 1) { names.append(name) }
        }
        return names
    }

    // MARK: - 新库

    func testFreshDatabaseHasLanguageTagAndTopicColumns() throws {
        let stores = try DatabaseStores.open(path: databasePath)
        defer { stores.database.close() }
        XCTAssertEqual(try stores.database.userVersion(), Schema.version)
        XCTAssertEqual(Schema.version, 20, "v19 的列仍然在（v20 只多 modelProfile，不删列）")
        XCTAssertTrue(try columnNames(stores.database, table: "personas").contains("languageTag"))
        let memoryColumns = try columnNames(stores.database, table: Schema.sessionMemoryTable)
        for column in ["topicsJson", "topicUserCount", "topicUpdatedAt"] {
            XCTAssertTrue(memoryColumns.contains(column), "v19 缺列：session_memory.\(column)")
        }
    }

    func testLanguageTagRoundTripsAndDefaultsToNil() throws {
        let stores = try DatabaseStores.open(path: databasePath)
        defer { stores.database.close() }
        try stores.personas.upsert(PersonaEntity(id: "p1", name: "林深", languageTag: "en"))
        try stores.personas.upsert(PersonaEntity(id: "p2", name: "小满"))
        XCTAssertEqual(try stores.personas.byId("p1")?.languageTag, "en")
        XCTAssertNil(try stores.personas.byId("p2")?.languageTag, "没设过 = nil（默认中文）")
        // 解析口径：nil → 中文，en-US → 英文。
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: try stores.personas.byId("p2")?.languageTag), .zh)
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: "en-US"), .en)
    }

    func testTopicSummaryRoundTripsThroughSessionMemory() throws {
        let stores = try DatabaseStores.open(path: databasePath)
        defer { stores.database.close() }
        let repository = SessionMemoryRepository(stores: stores, now: { 1 })
        let topics = [
            MemoryContract.TopicSegment(timeLabel: "2026-11-18 晚", summary: "加班"),
            MemoryContract.TopicSegment(timeLabel: "2026-11-19 上午", summary: "爬山")
        ]
        repository.applyTopics(
            sessionId: "s1", personaId: "p1", topics: topics, userCount: 15, at: 1
        )
        let stored = try XCTUnwrap(try stores.sessionMemory.bySession("s1"))
        XCTAssertEqual(stored.topics, topics)
        XCTAssertEqual(stored.topicUserCount, 15)
        XCTAssertEqual(stored.topicUpdatedAt, 1)
        // 坏 JSON / 空行也要安静降级（老库或手改数据）。
        var broken = stored
        broken.topicsJson = "{不是数组}"
        try stores.sessionMemory.upsert(broken)
        XCTAssertEqual(try stores.sessionMemory.bySession("s1")?.topics, [])
    }

    // MARK: - v18 → v19 升级

    func testV18DatabaseUpgradesAndKeepsOldRows() throws {
        // 1. 先造一个完整的 v19 库，灌一行人格 + 一行会话备忘录。
        do {
            let stores = try DatabaseStores.open(path: databasePath)
            try stores.personas.upsert(PersonaEntity(
                id: "p1", name: "林深", prompt: "你叫林深。", languageTag: "en"
            ))
            try stores.sessionMemory.upsert(SessionMemoryEntity(
                sessionId: "s1", personaId: "p1", shortSummary: "最近在赶方案",
                shortUserCount: 5, updatedAt: 1
            ))
            stores.database.close()
        }
        // 2. 模拟 v18：把 v19 新加的列拆掉、版本号退回 18。
        do {
            let database = try SQLiteDatabase(path: databasePath)
            for column in ["languageTag"] {
                try? database.execute("ALTER TABLE personas DROP COLUMN \(column)")
            }
            for column in ["topicsJson", "topicUserCount", "topicUpdatedAt"] {
                try? database.execute("ALTER TABLE session_memory DROP COLUMN \(column)")
            }
            guard try !columnNames(database, table: "personas").contains("languageTag") else {
                database.close()
                throw XCTSkip("这台机器的 SQLite 不支持 DROP COLUMN，跳过多版本模拟")
            }
            try database.setUserVersion(18)
            database.close()
        }
        // 3. 重新打开：迁移补列、版本到当前 schema，旧数据还在（新列读成默认值）。
        let reopened = try DatabaseStores.open(path: databasePath)
        defer { reopened.database.close() }
        XCTAssertEqual(try reopened.database.userVersion(), Schema.version)
        XCTAssertTrue(try columnNames(reopened.database, table: "personas").contains("languageTag"))
        let persona = try XCTUnwrap(try reopened.personas.byId("p1"))
        XCTAssertEqual(persona.name, "林深")
        XCTAssertNil(persona.languageTag, "升级后没设过语言 = 默认中文")
        let memory = try XCTUnwrap(try reopened.sessionMemory.bySession("s1"))
        XCTAssertEqual(memory.shortSummary, "最近在赶方案")
        XCTAssertEqual(memory.topics, [], "升级后主题总结为空（还没总结过）")
        XCTAssertEqual(memory.topicUserCount, 0)
    }
}
