import Foundation
import XCTest
@testable import YapKit

/// 一次性迁移：把「从没配置过睡眠」的旧默认关掉（0.12 续 7）。
///
/// 迁移的唯一目的是不让老用户升级后继续在夜里被静默，所以**边界比覆盖面重要**：
/// 用户自己填过作息、或者改过人格（version > 1）的人一律不动 —— 那是明确的选择。
///
/// 这里用真库（临时文件 SQLite）跑完整的 `runIfNeeded`：读 → 计划 → 写库 → 写标记 → 日志，
/// 因为「只跑一次」与「只改该改的」这两件事只有落库之后才看得出差别。
final class SleepDefaultsMigrationTests: XCTestCase {

    private var db: TempDatabase!

    override func setUpWithError() throws {
        try super.setUpWithError()
        db = try TempDatabase()
    }

    override func tearDownWithError() throws {
        db.cleanUp()
        db = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func persona(
        id: String,
        sleepEnabled: Bool = true,
        sleep: Int? = nil,
        wake: Int? = nil,
        version: Int = 1
    ) -> PersonaEntity {
        PersonaEntity(
            id: id,
            name: "人格-\(id)",
            description: "在测试里存在过的人",
            avatarSeed: 7,
            prompt: "你叫\(id)。",
            category: "伴侣",
            version: version,
            isDefault: false,
            createdAt: 1_794_500_000_000,
            updatedAt: 1_794_500_000_000,
            quietSleepMinutes: sleep,
            quietWakeMinutes: wake,
            sleepEnabled: sleepEnabled
        )
    }

    private func seed(_ personas: [PersonaEntity]) throws {
        for persona in personas {
            try db.stores.personas.upsert(persona)
        }
    }

    private func sleepEnabled(_ id: String) throws -> Bool {
        try XCTUnwrap(try db.stores.personas.byId(id)).sleepEnabled
    }

    /// 迁移跑一次，返回 (改了几个人, 日志行)。
    @discardableResult
    private func migrateOnce(
        marker: inout Bool,
        logs: inout [String]
    ) -> Int {
        SleepDefaultsMigration.runIfNeeded(
            stores: db.stores,
            isMigrated: { marker },
            markMigrated: { marker = true },
            log: { logs.append($0) }
        )
    }

    // MARK: - 条件

    func testDefaultSleepingPersonaIsTurnedOff() throws {
        try seed([persona(id: "default")])
        var marker = false
        var logs: [String] = []

        XCTAssertEqual(migrateOnce(marker: &marker, logs: &logs), 1)
        XCTAssertFalse(try sleepEnabled("default"), "从没配置过睡眠的人不该继续默认睡")
        XCTAssertTrue(marker, "跑过之后要写标记")
        XCTAssertEqual(logs.count, 1)
        XCTAssertTrue(logs[0].contains("sleepDefaultsMigratedV1"))
        XCTAssertFalse(logs[0].contains("人格-default"), "日志不该带用户内容")
    }

    func testCustomScheduleIsLeftAlone() throws {
        try seed([
            persona(id: "sleep-only", sleep: 23 * 60),
            persona(id: "wake-only", wake: 7 * 60),
            persona(id: "both", sleep: 23 * 60, wake: 7 * 60)
        ])
        var marker = false
        var logs: [String] = []

        XCTAssertEqual(migrateOnce(marker: &marker, logs: &logs), 0)
        for id in ["sleep-only", "wake-only", "both"] {
            XCTAssertTrue(try sleepEnabled(id), "用户自己设过作息的是明确的选择，不能动（\(id)）")
        }
        XCTAssertTrue(marker, "即使没人符合条件也算跑过")
    }

    func testEditedPersonaIsLeftAlone() throws {
        try seed([persona(id: "edited", version: 2), persona(id: "edited-a-lot", version: 9)])
        var marker = false
        var logs: [String] = []

        XCTAssertEqual(migrateOnce(marker: &marker, logs: &logs), 0)
        XCTAssertTrue(try sleepEnabled("edited"))
        XCTAssertTrue(try sleepEnabled("edited-a-lot"))
    }

    func testAlreadyAwakePersonaIsLeftAlone() throws {
        try seed([persona(id: "awake", sleepEnabled: false)])
        var marker = false
        var logs: [String] = []

        XCTAssertEqual(migrateOnce(marker: &marker, logs: &logs), 0)
        XCTAssertFalse(try sleepEnabled("awake"))
    }

    func testOnlyMatchingPersonasAreChanged() throws {
        try seed([
            persona(id: "default-1"),
            persona(id: "custom", sleep: 22 * 60, wake: 6 * 60),
            persona(id: "edited", version: 3),
            persona(id: "default-2"),
            persona(id: "awake", sleepEnabled: false)
        ])
        var marker = false
        var logs: [String] = []

        XCTAssertEqual(migrateOnce(marker: &marker, logs: &logs), 2)
        XCTAssertFalse(try sleepEnabled("default-1"))
        XCTAssertFalse(try sleepEnabled("default-2"))
        XCTAssertTrue(try sleepEnabled("custom"))
        XCTAssertTrue(try sleepEnabled("edited"))
        XCTAssertFalse(try sleepEnabled("awake"))
    }

    /// 1.9 之前的老库：`sleepEnabled` 列是 NULL。解码侧把它当 `true`（会睡），
    /// 所以迁移也必须把它当成「默认睡眠」，否则这批人永远修不到。
    func testNullSleepEnabledCountsAsDefaultSleep() throws {
        try seed([persona(id: "legacy")])
        try db.stores.database.executeUpdate(
            "UPDATE personas SET sleepEnabled = NULL WHERE id = ?", [.text("legacy")]
        )
        XCTAssertTrue(try XCTUnwrap(try db.stores.personas.byId("legacy")).sleepEnabled, "NULL 解码成 true")

        var marker = false
        var logs: [String] = []
        XCTAssertEqual(migrateOnce(marker: &marker, logs: &logs), 1)
        XCTAssertFalse(try sleepEnabled("legacy"))
    }

    /// 不看 `updatedAt`：迁移不是用户改人格，不该打乱首页排序。
    func testUpdatedAtIsUntouched() throws {
        try seed([persona(id: "default")])
        let before = try XCTUnwrap(try db.stores.personas.byId("default")).updatedAt
        var marker = false
        var logs: [String] = []

        migrateOnce(marker: &marker, logs: &logs)
        XCTAssertEqual(try XCTUnwrap(try db.stores.personas.byId("default")).updatedAt, before)
    }

    // MARK: - 只跑一次

    func testRunsOnlyOnce() throws {
        try seed([persona(id: "default")])
        var marker = false
        var logs: [String] = []

        XCTAssertEqual(migrateOnce(marker: &marker, logs: &logs), 1)
        // 用户之后自己打开了睡眠：第二次启动不该再把它关掉。
        var persona = try XCTUnwrap(try db.stores.personas.byId("default"))
        persona.sleepEnabled = true
        try db.stores.personas.upsert(persona)

        XCTAssertEqual(migrateOnce(marker: &marker, logs: &logs), 0, "标记已经在，什么都不该做")
        XCTAssertTrue(try sleepEnabled("default"), "第二次不该动任何人")
        XCTAssertEqual(logs.count, 1, "只该留一条迁移日志")
    }

    func testEmptyDatabaseIsMarkedDone() throws {
        var marker = false
        var logs: [String] = []
        XCTAssertEqual(migrateOnce(marker: &marker, logs: &logs), 0)
        XCTAssertTrue(marker)
        XCTAssertEqual(logs.count, 1)
    }

    // MARK: - 标记本身

    func testSettingsStoreMarkerRoundTrip() {
        let suite = "sleep-defaults-migration-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = SettingsStore(defaults: defaults)
        XCTAssertFalse(store.hasMigratedSleepDefaultsV1)
        store.markSleepDefaultsMigratedV1()
        XCTAssertTrue(store.hasMigratedSleepDefaultsV1)
        // 「恢复默认」之后重来一次：对已经改过的人不再成立，是无害的。
        store.reset()
        XCTAssertFalse(store.hasMigratedSleepDefaultsV1)
    }

    /// 新装：库是空的 → 迁移什么也不改，随后播种的人本来就不睡。
    func testFreshInstallStaysAwake() throws {
        var marker = false
        var logs: [String] = []
        migrateOnce(marker: &marker, logs: &logs)
        let outcome = try PersonaSeeding.seedIfNeeded(
            stores: db.stores,
            language: .chinese,
            sessionTitle: "新的对话",
            now: 1_794_500_000_000
        )
        XCTAssertEqual(outcome.personaIds.count, 3)
        for persona in try db.stores.personas.all() {
            XCTAssertFalse(persona.sleepEnabled)
        }
    }
}
