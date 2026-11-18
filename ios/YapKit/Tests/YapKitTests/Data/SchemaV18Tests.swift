import Foundation
import XCTest
@testable import YapKit

/// v17 → v18 的迁移：拉黑三列、`messages.blocked`、心理活动与会话备忘录两张新表。
///
/// 做法与 `SchemaMigrationTests` 一致：先把库建成当前版本，再**退回 v17 的形状**并灌旧数据，
/// 然后用 `DatabaseStores.open` 打开 —— 走的是 App 真实升级路径，而不是手写一套旧 DDL。
final class SchemaV18Tests: XCTestCase {

    private var directory: URL!
    private var databasePath: String!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-v18-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databasePath = directory.appendingPathComponent("legacy.sqlite").path
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        try super.tearDownWithError()
    }

    private let personaId = "persona-1"
    private let sessionId = "session-1"
    private let now: Int64 = 1_794_500_000_000

    /// 造一个 v17 的库：把 v18 的三列与两张表拆掉，灌一条人格、一条会话、两条消息。
    private func seedV17Database() throws {
        do {
            let stores = try DatabaseStores.open(path: databasePath)
            let database = stores.database
            for column in Schema.personaBlockColumns {
                try database.execute("ALTER TABLE personas DROP COLUMN \(column.name)")
            }
            try database.execute("ALTER TABLE messages DROP COLUMN \(Schema.messageBlockedColumn.name)")
            try database.execute("DROP TABLE \(Schema.mentalActivitiesTable)")
            try database.execute("DROP TABLE \(Schema.sessionMemoryTable)")

            try database.execute("""
                INSERT INTO personas (id, name, description, avatarSeed, prompt, category, version,
                                      isDefault, createdAt, updatedAt, mood, moodIntensity)
                VALUES ('\(personaId)', '林岸', '旧数据', 7, '你叫林岸。', '朋友', 2, 1, 1, \(now),
                        '平静', 2)
                """)
            try database.execute("""
                INSERT INTO chat_sessions (id, personaId, apiConfigId, model, title, unreadCount,
                    pinned, createdAt, updatedAt, lastPersonaVersion, syncState,
                    lastMemoryExtractedCount)
                VALUES ('\(sessionId)', '\(personaId)', NULL, 'deepseek-chat', '对话', 0, 0, 1,
                        \(now), 2, 'STATELESS', 0)
                """)
            try database.execute("""
                INSERT INTO messages (id, sessionId, personaId, role, content, createdAt, updatedAt,
                                      status, edited, blockIndex)
                VALUES ('msg-1', '\(sessionId)', '\(personaId)', 'USER', '旧消息', 1, 1, 'COMPLETE', 0, 0),
                       ('msg-2', '\(sessionId)', '\(personaId)', 'ASSISTANT', '旧回复', 2, 2, 'COMPLETE', 0, 0)
                """)
            try database.setUserVersion(17)
        }
    }

    func testV17ToV18AddsColumnsAndTables() throws {
        try seedV17Database()
        let stores = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try stores.database.userVersion(), 18, "升级后 user_version 必须是 18")

        // 1) 拉黑三列：老数据是 null（= 未拉黑 / 没有提议）。
        let personaColumns = try columnNames(stores.database, table: "personas")
        for column in Schema.personaBlockColumns {
            XCTAssertTrue(personaColumns.contains(column.name), "v18 要加列：personas.\(column.name)")
        }
        let persona = try XCTUnwrap(try stores.personas.byId(personaId))
        XCTAssertNil(persona.blockedUntil)
        XCTAssertNil(persona.blockReason)
        XCTAssertNil(persona.blockProposalJson)
        XCTAssertEqual(persona.mood, "平静", "迁移不能丢已有列")

        // 2) messages.blocked：老消息一律 0（未被屏蔽）。
        let messageColumns = try columnNames(stores.database, table: "messages")
        XCTAssertTrue(messageColumns.contains(Schema.messageBlockedColumn.name))
        let messages = try stores.messages.bySession(sessionId)
        XCTAssertEqual(messages.map(\.blocked), [false, false])
        XCTAssertEqual(messages.map(\.content), ["旧消息", "旧回复"])

        // 3) 两张新表：建起来、是空的、索引在。
        XCTAssertTrue(try stores.database.hasTable(Schema.mentalActivitiesTable))
        XCTAssertTrue(try stores.database.hasTable(Schema.sessionMemoryTable))
        XCTAssertEqual(try stores.mentalActivities.count(), 0)
        XCTAssertEqual(try stores.sessionMemory.count(), 0)
        let indexNames = try indexNames(stores.database)
        for name in [
            "index_mental_activities_personaId_at",
            "index_mental_activities_sessionId_at",
            "index_mental_activities_at",
            "index_session_memory_personaId",
            "index_session_memory_updatedAt"
        ] {
            XCTAssertTrue(indexNames.contains(name), "v18 要建索引：\(name)")
        }
    }

    func testV18MigrationIsIdempotent() throws {
        try seedV17Database()
        do {
            _ = try DatabaseStores.open(path: databasePath)
        }
        let reopened = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try reopened.database.userVersion(), 18)
        XCTAssertEqual(try reopened.messages.bySession(sessionId).count, 2)
        XCTAssertEqual(try reopened.personas.byId(personaId)?.name, "林岸")
    }

    /// v18 的默认值：`messages.blocked` 必须是 `NOT NULL DEFAULT 0`，
    /// 这样「不提供这一列的老 INSERT」也不会写坏。
    func testBlockedColumnHasDefaultZero() throws {
        try seedV17Database()
        let stores = try DatabaseStores.open(path: databasePath)
        let statement = try stores.database.prepare("PRAGMA table_info(messages)")
        var found: (notNull: Int, defaultValue: String?)?
        while try statement.step() {
            let row = SQLiteRow(statement: statement)
            if try row.text("name") == Schema.messageBlockedColumn.name {
                found = (try row.int("notnull"), try row.optionalText("dflt_value"))
            }
        }
        let column = try XCTUnwrap(found)
        XCTAssertEqual(column.notNull, 1, "blocked 必须 NOT NULL")
        XCTAssertEqual(column.defaultValue, "0", "blocked 默认 0（老库升级不回填）")
    }

    // MARK: - 小工具

    private func columnNames(_ database: SQLiteDatabase, table: String) throws -> [String] {
        let statement = try database.prepare("PRAGMA table_info(\(table))")
        var names: [String] = []
        while try statement.step() {
            names.append(try SQLiteRow(statement: statement).text("name", ""))
        }
        return names
    }

    private func indexNames(_ database: SQLiteDatabase) throws -> [String] {
        let statement = try database.prepare("SELECT name FROM sqlite_master WHERE type = 'index'")
        var names: [String] = []
        while try statement.step() {
            if let name = statement.text(at: 0) { names.append(name) }
        }
        return names
    }
}
