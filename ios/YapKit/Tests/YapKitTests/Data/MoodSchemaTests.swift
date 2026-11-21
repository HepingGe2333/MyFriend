import Foundation
import XCTest
@testable import YapKit

/// schema v17：`personas` 的三维心情（`spec/mood/README.md` §1）。
///
/// 两条路径都要钉住：
/// 1. **新库**：三列存在、实体读写往返一致、空值仍是空值；
/// 2. **16 → 17 升级**：加列 + 旧数据映射（累/烦 → -1、平静 → 0、开心 → 1；arousal 由 intensity 推；
///    dominance = 3），且判定过的行不被覆盖、重复打开幂等。
final class MoodSchemaTests: XCTestCase {

    private var directory: URL!
    private var databasePath: String!

    private let personaId = "persona-mood"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-mood-schema-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databasePath = directory.appendingPathComponent("mood.sqlite").path
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        try super.tearDownWithError()
    }

    private func columnNames(_ database: SQLiteDatabase, table: String) throws -> Set<String> {
        let statement = try database.prepare("PRAGMA table_info(\(table))")
        var names = Set<String>()
        while try statement.step() {
            if let name = statement.text(at: 1) { names.insert(name) }
        }
        return names
    }

    // MARK: - 新库

    func testFreshDatabaseHasMoodDimensionColumns() throws {
        let stores = try DatabaseStores.open(path: databasePath)
        defer { stores.database.close() }
        XCTAssertEqual(Schema.version, 20, "当前 schema 是 v20（v17 三维心情 + v18 拉黑 + v19 人格语言/主题总结 + v20 模型优化）")
        let columns = try columnNames(stores.database, table: "personas")
        for column in Schema.personaMoodDimensionColumns {
            XCTAssertTrue(columns.contains(column.name), "缺列：personas.\(column.name)")
        }
        XCTAssertEqual(try stores.database.userVersion(), Schema.version)
    }

    func testPersonaMoodDimensionsRoundTrip() throws {
        let stores = try DatabaseStores.open(path: databasePath)
        defer { stores.database.close() }
        try stores.personas.upsert(PersonaEntity(
            id: personaId, name: "林深", prompt: "你叫林深。", createdAt: now, updatedAt: now,
            mood: "有点累", moodIntensity: 3, moodReason: "加班",
            moodUpdatedAt: now, moodValence: -1, moodArousal: 1, moodDominance: 2
        ))
        let stored = try XCTUnwrap(try stores.personas.byId(personaId))
        XCTAssertEqual(-1, stored.moodValence)
        XCTAssertEqual(1, stored.moodArousal)
        XCTAssertEqual(2, stored.moodDominance)
        XCTAssertEqual(
            MoodDimensions(valence: -1, arousal: 1, dominance: 2),
            MoodRepository.dimensionsOf(stored)
        )
    }

    func testUnjudgedPersonaKeepsNilDimensions() throws {
        let stores = try DatabaseStores.open(path: databasePath)
        defer { stores.database.close() }
        try stores.personas.upsert(PersonaEntity(
            id: personaId, name: "林深", prompt: "你叫林深。", createdAt: now, updatedAt: now
        ))
        let stored = try XCTUnwrap(try stores.personas.byId(personaId))
        XCTAssertNil(stored.moodValence)
        XCTAssertNil(stored.moodArousal)
        XCTAssertNil(stored.moodDominance)
        XCTAssertNil(MoodRepository.dimensionsOf(stored))
    }

    func testOutOfRangeValuesAreClampedOnConstruction() {
        let persona = PersonaEntity(
            id: "p", name: "林深",
            moodValence: -9, moodArousal: 99, moodDominance: -4
        )
        XCTAssertEqual(-2, persona.moodValence)
        XCTAssertEqual(5, persona.moodArousal)
        XCTAssertEqual(0, persona.moodDominance)
    }

    // MARK: - 16 → 17 升级

    func testV16ToV17AddsColumnsAndBackfillsLegacyMood() throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let database = try XCTUnwrap(stores?.database)
        try database.execute("""
            INSERT INTO personas (id, name, description, avatarSeed, prompt, category, version,
                                  isDefault, createdAt, updatedAt, mood, moodIntensity)
            VALUES ('\(personaId)', '林深', 'v16 的数据', 7, '你叫林深。', '朋友', 3, 1, 1, \(now),
                    '有点累', 4)
            """)
        // 退回 v16：删掉三维三列、版本号改回 16（老库就是这个样子）。
        for column in Schema.personaMoodDimensionColumns {
            try database.execute("ALTER TABLE personas DROP COLUMN \(column.name)")
        }
        try database.setUserVersion(16)
        XCTAssertFalse(try columnNames(database, table: "personas").contains("moodValence"))
        stores = nil
        database.close()

        let reopened = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try reopened.database.userVersion(), Schema.version)
        let columns = try columnNames(reopened.database, table: "personas")
        for column in Schema.personaMoodDimensionColumns {
            XCTAssertTrue(columns.contains(column.name), "v17 要加列：\(column.name)")
        }
        // 旧数据映射：累 → valence -1、arousal 由 intensity 推、dominance = 3。
        let migrated = try XCTUnwrap(try reopened.personas.byId(personaId))
        XCTAssertEqual(-1, migrated.moodValence)
        XCTAssertEqual(4, migrated.moodArousal)
        XCTAssertEqual(3, migrated.moodDominance)
        XCTAssertEqual("有点累", migrated.mood)
        reopened.database.close()
    }

    func testV16ToV17DoesNotOverwriteJudgedMood() throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let database = try XCTUnwrap(stores?.database)
        // 已经判定过的行（三维有值）在升级时必须原样保留。
        try database.execute("""
            INSERT INTO personas (id, name, description, avatarSeed, prompt, category, version,
                                  isDefault, createdAt, updatedAt, mood, moodIntensity,
                                  moodValence, moodArousal, moodDominance)
            VALUES ('\(personaId)', '林深', '判过的', 7, '你叫林深。', '朋友', 3, 1, 1, \(now),
                    '烦', 4, -2, 5, 1)
            """)
        try database.setUserVersion(16)
        stores = nil
        database.close()

        let reopened = try DatabaseStores.open(path: databasePath)
        let stored = try XCTUnwrap(try reopened.personas.byId(personaId))
        XCTAssertEqual(-2, stored.moodValence)
        XCTAssertEqual(5, stored.moodArousal)
        XCTAssertEqual(1, stored.moodDominance)
        reopened.database.close()
    }

    func testV16ToV17IsIdempotentAndLeavesQuietPersonasNull() throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let database = try XCTUnwrap(stores?.database)
        try database.execute("""
            INSERT INTO personas (id, name, description, avatarSeed, prompt, category, version,
                                  isDefault, createdAt, updatedAt)
            VALUES ('quiet', '林深', '从来没有心情', 7, '你叫林深。', '朋友', 3, 1, 1, \(now))
            """)
        try database.setUserVersion(16)
        stores = nil
        database.close()

        // 连开两次：列只加一次、回填只做一次。
        let first = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try first.database.userVersion(), Schema.version)
        first.database.close()
        let again = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try again.database.userVersion(), Schema.version)
        let stored = try XCTUnwrap(try again.personas.byId("quiet"))
        XCTAssertNil(stored.moodValence, "没有心情的人不该被编出一个心情")
        XCTAssertEqual(try again.database.scalarInt64(
            "SELECT COUNT(*) FROM pragma_table_info('personas') WHERE name = 'moodValence'", []
        ), 1, "列只该加一次")
        again.database.close()
    }
}
