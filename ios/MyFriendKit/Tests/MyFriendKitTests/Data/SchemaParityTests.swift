import Foundation
import XCTest
@testable import MyFriendKit

/// iOS 的表结构和 Android Room 导出 schema 的**逐字对照**（`messages` + `message_blocks`）。
///
/// 为什么需要这条：两端共用备份 JSON，但库里结构与「块」的语义必须一致，
/// 否则同一段历史在 Android 上分块、在 iOS 上不分块，或者 `blockIndex` 的默认值 / 索引不同，
/// 迁移与分页就会各说各话。Android 的导出文件（`app/schemas/.../<version>.json`）是这件事的**唯一出处**，
/// 这里直接读它、和 iOS 真库的 `PRAGMA` 结果对比，而不是再抄一份 DDL 常量。
///
/// 文件不存在就 skip（iOS 单独 checkout / Android 还没导出当前版本时正常）：
/// 与 `AgentCoreResponseGoldenTests` 读 Kotlin 协议文件同一个套路。
final class SchemaParityTests: XCTestCase {

    private var temp: TempDatabase!

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 找 Android 的 Room 导出文件

    private static func repositoryRoot(file: String = #filePath) -> URL? {
        var url = URL(fileURLWithPath: file).deletingLastPathComponent()
        let fm = FileManager.default
        for _ in 0..<16 {
            if fm.fileExists(atPath: url.appendingPathComponent("settings.gradle.kts").path),
               fm.fileExists(atPath: url.appendingPathComponent("app/schemas").path) {
                return url
            }
            let parent = url.deletingLastPathComponent()
            if parent.path == url.path { break }
            url = parent
        }
        return nil
    }

    private func roomEntities() throws -> [String: [String: Any]] {
        guard let root = Self.repositoryRoot() else {
            throw XCTSkip("找不到仓库根（需要 settings.gradle.kts + app/schemas/）")
        }
        // 按**当前** schema 版本找文件：v11→v12 升级时 Android 会导出 12.json，
        // 这里如果还硬编码 11.json，就会拿一份旧版本来对照新模式而红掉。
        let file = root.appendingPathComponent(
            "app/schemas/com.myfriend.app.data.local.MyFriendDatabase/\(Schema.version).json"
        )
        guard let data = try? Data(contentsOf: file) else {
            throw XCTSkip("Android 还没有导出 v\(Schema.version) schema：\(file.path)")
        }
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let database = object["database"] as? [String: Any],
              let entities = database["entities"] as? [[String: Any]] else {
            XCTFail("Room 导出文件格式不对：\(file.path)")
            return [:]
        }
        XCTAssertEqual(database["version"] as? Int, Int(Schema.version),
                       "两端 user_version 必须相同")
        var byName: [String: [String: Any]] = [:]
        for entity in entities {
            if let name = entity["tableName"] as? String { byName[name] = entity }
        }
        return byName
    }

    // MARK: - 对照

    func testCurrentVersionTablesMatchAndroidRoomExport() throws {
        let entities = try roomEntities()
        for table in ["messages", Schema.messageBlocksTable] {
            let entity = try XCTUnwrap(entities[table], "Android v\(Schema.version) 里没有这张表：\(table)")
            try assertColumns(table: table, match: entity)
            try assertIndices(table: table, match: entity)
            try assertPrimaryKey(table: table, match: entity)
        }
    }

    /// 列名、顺序、affinity、`NOT NULL`、默认值都要一模一样。
    private func assertColumns(table: String, match entity: [String: Any]) throws {
        let fields = try XCTUnwrap(entity["fields"] as? [[String: Any]])
        let expectedNames = fields.compactMap { $0["columnName"] as? String }
        XCTAssertFalse(expectedNames.isEmpty, "\(table)：Android schema 里没有列")

        let statement = try temp.stores.database.prepare("PRAGMA table_info(\(table))")
        var rows: [(name: String, type: String, notNull: Int, defaultValue: String?, pk: Int)] = []
        while try statement.step() {
            let row = SQLiteRow(statement: statement)
            rows.append((
                name: try row.text("name"),
                type: try row.text("type"),
                notNull: try row.int("notnull"),
                defaultValue: try row.optionalText("dflt_value"),
                pk: try row.int("pk")
            ))
        }
        XCTAssertEqual(rows.map(\.name), expectedNames, "\(table) 的列（含顺序）与 Android 不一致")

        for (index, field) in fields.enumerated() {
            let column = rows[index]
            let name = try XCTUnwrap(field["columnName"] as? String)
            XCTAssertEqual(column.type, field["affinity"] as? String, "\(table).\(name)：类型不一致")
            XCTAssertEqual(
                column.notNull != 0, field["notNull"] as? Bool ?? false,
                "\(table).\(name)：NOT NULL 不一致"
            )
            let expectedDefault = field["defaultValue"] as? String
            if let expectedDefault {
                XCTAssertEqual(column.defaultValue, expectedDefault, "\(table).\(name)：默认值不一致")
            } else {
                XCTAssertNil(column.defaultValue, "\(table).\(name)：iOS 多了一个默认值")
            }
        }
    }

    /// 索引名与列序：`PRAGMA index_list`（跳过 SQLite 为 PK 自动建的那些）。
    private func assertIndices(table: String, match entity: [String: Any]) throws {
        let expected = (entity["indices"] as? [[String: Any]] ?? []).compactMap { raw -> (String, [String])? in
            guard let name = raw["name"] as? String,
                  let columns = raw["columnNames"] as? [String] else { return nil }
            return (name, columns)
        }
        var actual: [String: [String]] = [:]
        let list = try temp.stores.database.prepare("PRAGMA index_list(\(table))")
        var names: [String] = []
        while try list.step() {
            let name = try SQLiteRow(statement: list).text("name", "")
            if !name.hasPrefix("sqlite_autoindex") { names.append(name) }
        }
        for name in names {
            let info = try temp.stores.database.prepare("PRAGMA index_info(\(name))")
            var columns: [String] = []
            while try info.step() {
                columns.append(try SQLiteRow(statement: info).text("name", ""))
            }
            actual[name] = columns
        }
        XCTAssertEqual(Set(actual.keys), Set(expected.map(\.0)), "\(table) 的索引名与 Android 不一致")
        for (name, columns) in expected {
            XCTAssertEqual(actual[name], columns, "\(table) 的索引 \(name) 列序与 Android 不一致")
        }
    }

    /// 主键列与顺序（`PRAGMA table_info` 的 `pk` 是 1 基位置）。
    private func assertPrimaryKey(table: String, match entity: [String: Any]) throws {
        let primaryKey = try XCTUnwrap(entity["primaryKey"] as? [String: Any])
        let expected = try XCTUnwrap(primaryKey["columnNames"] as? [String])
        let statement = try temp.stores.database.prepare("PRAGMA table_info(\(table))")
        var byPosition: [(Int, String)] = []
        while try statement.step() {
            let row = SQLiteRow(statement: statement)
            let position = try row.int("pk")
            if position > 0 { byPosition.append((position, try row.text("name"))) }
        }
        XCTAssertEqual(byPosition.sorted { $0.0 < $1.0 }.map(\.1), expected, "\(table) 的主键与 Android 不一致")
    }
}
