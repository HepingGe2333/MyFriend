import XCTest
@testable import YapKit

/// 0.15（schema v20）的「模型优化」档位：`personas.modelProfile` 与 `persona_presets.modelProfile`。
///
/// 这一版只 ADD COLUMN（`TEXT NOT NULL DEFAULT 'AUTO'`，不回填），所以两类测试：
/// 1. 新库：两列都在、默认 `AUTO`、三档都能存取；
/// 2. 老库（v19）：升级后列补上了，旧数据一行不少，读出来是 `AUTO`（= 按当前模型名推断），
///    行为与升级之前完全一致。
final class SchemaV20Tests: XCTestCase {

    private var directory: URL!
    private var databasePath: String!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-v20-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databasePath = directory.appendingPathComponent("v20.sqlite").path
    }

    override func tearDownWithError() throws {
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(atPath: databasePath + suffix)
        }
        try? FileManager.default.removeItem(at: directory)
        try super.tearDownWithError()
    }

    private func columns(_ database: SQLiteDatabase, table: String) throws
        -> [(name: String, type: String, notNull: Int, defaultValue: String?)] {
        var rows: [(String, String, Int, String?)] = []
        let statement = try database.prepare("PRAGMA table_info(\(table))")
        while try statement.step() {
            let row = SQLiteRow(statement: statement)
            rows.append((
                try row.text("name"),
                try row.text("type"),
                try row.int("notnull"),
                try row.optionalText("dflt_value")
            ))
        }
        return rows
    }

    // MARK: - 新库

    func testFreshDatabaseHasModelProfileColumnsWithAutoDefault() throws {
        let stores = try DatabaseStores.open(path: databasePath)
        defer { stores.database.close() }
        XCTAssertEqual(Schema.version, 20)
        XCTAssertEqual(try stores.database.userVersion(), 20)

        for table in ["personas", "persona_presets"] {
            let rows = try columns(stores.database, table: table)
            let column = try XCTUnwrap(
                rows.first { $0.name == "modelProfile" },
                "\(table) 缺列 modelProfile"
            )
            XCTAssertEqual(column.type, "TEXT", "\(table).modelProfile 存枚举名（TEXT）")
            XCTAssertEqual(column.notNull, 1, "\(table).modelProfile 是 NOT NULL（默认 AUTO）")
            XCTAssertEqual(column.defaultValue, "'AUTO'", "\(table).modelProfile 的默认值必须是 'AUTO'")
        }
    }

    func testModelProfileRoundTripsOnPersonaAndPreset() throws {
        let stores = try DatabaseStores.open(path: databasePath)
        defer { stores.database.close() }

        try stores.personas.upsert(PersonaEntity(id: "p-auto", name: "默认"))
        try stores.personas.upsert(PersonaEntity(
            id: "p-deepseek", name: "深度", modelProfile: .deepseek
        ))
        try stores.personas.upsert(PersonaEntity(id: "p-glm", name: "GLM", modelProfile: .glm))

        XCTAssertEqual(try stores.personas.byId("p-auto")?.modelProfile, .auto)
        XCTAssertEqual(try stores.personas.byId("p-deepseek")?.modelProfile, .deepseek)
        XCTAssertEqual(try stores.personas.byId("p-glm")?.modelProfile, .glm)

        try stores.presets.upsert(PersonaPresetEntity(id: "preset-1", name: "底稿"))
        try stores.presets.upsert(PersonaPresetEntity(
            id: "preset-2", name: "GLM 底稿", modelProfile: .glm
        ))
        XCTAssertEqual(try stores.presets.byId("preset-1")?.modelProfile, .auto)
        XCTAssertEqual(try stores.presets.byId("preset-2")?.modelProfile, .glm)
    }

    /// 数据库里出现认不出的值时回落 `AUTO`（手改库 / 未来版本的新档位都不该让读库失败）。
    func testUnknownStoredValueFallsBackToAuto() throws {
        let stores = try DatabaseStores.open(path: databasePath)
        defer { stores.database.close() }
        try stores.personas.upsert(PersonaEntity(id: "p1", name: "他", modelProfile: .glm))
        try stores.database.execute("UPDATE personas SET modelProfile = 'SOMETHING_NEW' WHERE id = 'p1'")
        XCTAssertEqual(try stores.personas.byId("p1")?.modelProfile, .auto)
    }

    // MARK: - v19 → v20 升级

    func testV19DatabaseUpgradesAndKeepsOldRows() throws {
        // 1. 先造一个当前版本的库，灌一行人格 + 一行预设。
        do {
            let stores = try DatabaseStores.open(path: databasePath)
            try stores.personas.upsert(PersonaEntity(
                id: "p1", name: "林深", prompt: "你叫林深。", languageTag: "zh"
            ))
            try stores.presets.upsert(PersonaPresetEntity(id: "preset-1", name: "旧预设"))
            stores.database.close()
        }
        // 2. 模拟 v19：把两列拆掉、版本号退回 19。
        do {
            let database = try SQLiteDatabase(path: databasePath)
            try? database.execute("ALTER TABLE personas DROP COLUMN modelProfile")
            try? database.execute("ALTER TABLE persona_presets DROP COLUMN modelProfile")
            guard try !columns(database, table: "personas").contains(where: { $0.name == "modelProfile" })
            else {
                database.close()
                throw XCTSkip("这台机器的 SQLite 不支持 DROP COLUMN，跳过多版本模拟")
            }
            try database.setUserVersion(19)
            database.close()
        }
        // 3. 重新打开：迁移补列、版本到 20，旧数据还在；新列读成 AUTO（不回填、行为不变）。
        let reopened = try DatabaseStores.open(path: databasePath)
        defer { reopened.database.close() }
        XCTAssertEqual(try reopened.database.userVersion(), Schema.version)
        XCTAssertTrue(try columns(reopened.database, table: "personas")
            .contains { $0.name == "modelProfile" })
        XCTAssertTrue(try columns(reopened.database, table: "persona_presets")
            .contains { $0.name == "modelProfile" })

        let persona = try XCTUnwrap(try reopened.personas.byId("p1"))
        XCTAssertEqual(persona.name, "林深")
        XCTAssertEqual(persona.languageTag, "zh", "v19 的列一个字都不能丢")
        XCTAssertEqual(persona.modelProfile, .auto, "升级后默认 AUTO（按当前模型名推断）")
        let preset = try XCTUnwrap(try reopened.presets.byId("preset-1"))
        XCTAssertEqual(preset.name, "旧预设")
        XCTAssertEqual(preset.modelProfile, .auto)
    }
}
