import Foundation
import XCTest
@testable import YapKit

/// 备份元数据与完整性（0.15）：schema 版本 / 文件生日 / 规范化 data 的 SHA-256。
///
/// 三件要钉住的事：
/// 1. **导出写全元数据**（App 版本已有，新增 schema 版本、createdAt、checksum）；
/// 2. **导入会校验**：哈希对不上就整体拒绝（不把可能传坏/被改过的数据写进他的记忆）；
/// 3. **老备份照读**：v1–v7 的样本没有这些键 → 默认值、跳过校验、行为与之前完全一致。
final class BackupMetadataTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var service: BackupService!
    private let fileManager = FileManager.default
    private let personaId = "persona-1"
    private let sessionId = "session-1"

    override func setUpWithError() throws {
        try super.setUpWithError()
        db = try TempDatabase()
        settings = InMemorySettings(AppSettings(activePersonaId: personaId))
        service = BackupService(
            stores: db.stores,
            settings: settings,
            appVersion: "0.15.0-ios",
            now: { TestData.now }
        )
        try seed()
    }

    override func tearDownWithError() throws {
        service = nil
        settings = nil
        db.cleanUp()
        db = nil
        try super.tearDownWithError()
    }

    private func seed() throws {
        try db.stores.personas.upsert([TestData.persona(id: personaId, name: "林岸")])
        try db.stores.sessions.upsert([TestData.session(id: sessionId, personaId: personaId)])
        try db.stores.messages.upsert([
            TestData.message(
                id: "m1", sessionId: sessionId, personaId: personaId,
                role: .user, content: "你在吗", createdAt: TestData.now - 2_000
            ),
            TestData.message(
                id: "m2", sessionId: sessionId, personaId: personaId,
                role: .assistant, content: "在。", createdAt: TestData.now - 1_000
            )
        ])
    }

    private func root(_ json: String) throws -> [String: JSONValue] {
        try XCTUnwrap(JSONValueParser.object(from: json), "导出的不是一份 JSON 对象")
    }

    // MARK: - 导出写入

    func testExportWritesSchemaVersionCreatedAtAndChecksum() throws {
        let json = try service.export()
        let object = try root(json)

        XCTAssertEqual(object.rawText("appVersion"), "0.15.0-ios")
        XCTAssertEqual(object.rawInt64("exportedAt"), TestData.now)
        XCTAssertEqual(object.rawInt64("createdAt"), TestData.now, "createdAt 与 exportedAt 同源（一次导出一个快照）")
        XCTAssertEqual(object.rawInt("schemaVersion"), Int(Schema.version))

        XCTAssertEqual(object.rawText("checksumAlgorithm"), BackupIntegrity.algorithm,
                       "导出必须带算法标记，否则别的客户端不会复核它")
        let checksum = try XCTUnwrap(object.rawText("checksum"), "文件里必须带 sha256")
        XCTAssertEqual(checksum.count, 64, "sha256 是 64 位 hex")
        XCTAssertEqual(checksum, checksum.lowercased(), "统一小写，{否则大小写不同的两份文件会被当成不同}")
        XCTAssertTrue(checksum.allSatisfy { $0.isHexDigit })

        // 重新解码会走一遍哈希复核：能读出来就说明文件里的哈希与 data 对得上。
        let decoded = try XCTUnwrap(BackupCodec.decode(json).bundle)
        XCTAssertEqual(decoded.checksum, checksum)
        XCTAssertEqual(decoded.checksumAlgorithm, BackupIntegrity.algorithm)
        XCTAssertEqual(decoded.schemaVersion, Int(Schema.version))
        XCTAssertEqual(decoded.createdAt, TestData.now)
    }

    func testChecksumIsStableAcrossExports() throws {
        let first = try service.export()
        let second = try service.export()
        XCTAssertEqual(first, second, "同样的数据、同样的时钟：两次导出必须逐字相同（含哈希）")
    }

    /// 校验的对象是**内容**而不是排版：往文件里加空格/换行，data 没变就不该被判成损坏。
    func testChecksumIgnoresFormattingWhitespace() throws {
        let sealed = try service.export()
        let reformatted = sealed.replacingOccurrences(
            of: #""format":"myfriend.backup""#,
            with: #""format" :  "myfriend.backup""#
        )
        XCTAssertNotEqual(reformatted, sealed)
        let result = BackupCodec.decode(reformatted)
        XCTAssertTrue(result.isSuccess, "只是排版变了，不该拒绝：\(result.failureReason ?? "?")")
        XCTAssertEqual(result.bundle?.checksum, try root(sealed).rawText("checksum"))
    }

    func testChecksumMatchesCanonicalJSON() throws {
        // 文件里的哈希 = 「整份 JSON 去掉封条自己」规范化之后的 SHA-256（跨端协议，见 BackupIntegrity）。
        let json = try service.export()
        let declared = try XCTUnwrap(try root(json).rawText("checksum"))
        XCTAssertEqual(BackupIntegrity.checksum(ofJSONText: json), declared)
    }

    // MARK: - 导入校验

    func testTamperedValueIsRejected() throws {
        let json = try service.export()
        XCTAssertFalse(json.contains("你在吗？"))
        let tampered = json.replacingOccurrences(of: "你在吗", with: "你在吗？")
        XCTAssertNotEqual(tampered, json, "用例前提：文件里确实有这句话")

        let result = BackupCodec.decode(tampered)
        XCTAssertFalse(result.isSuccess, "被改过的备份必须拒绝")
        XCTAssertTrue(result.failureReason?.contains("校验") ?? false,
                      "原因要说清楚是校验不通过：\(result.failureReason ?? "?")")
    }

    func testTamperedChecksumIsRejected() throws {
        let json = try service.export()
        let checksum = try XCTUnwrap(try root(json).rawText("checksum"))
        let tampered = json.replacingOccurrences(
            of: checksum,
            with: String(repeating: "a", count: 64)
        )
        XCTAssertFalse(BackupCodec.decode(tampered).isSuccess)
    }

    func testMissingChecksumIsAccepted() throws {
        // 手写/老备份没有 checksum：不校验，照常读（兼容性优先于严格性）。
        let json = #"{"format":"myfriend.backup","formatVersion":7,"personas":[{"id":"p1","name":"他"}]}"#
        let bundle = try XCTUnwrap(BackupCodec.decode(json).bundle)
        XCTAssertNil(bundle.checksum)
        XCTAssertEqual(bundle.schemaVersion, 0)
        XCTAssertEqual(bundle.createdAt, 0)
    }

    func testServiceImportRejectsTamperedBackup() throws {
        let tampered = try service.export().replacingOccurrences(of: "你在吗", with: "你在吗？")
        XCTAssertThrowsError(try service.importBackup(text: tampered, mode: .replace)) { error in
            let message = (error as? LocalizedError)?.errorDescription ?? "\(error)"
            XCTAssertTrue(message.contains("校验"), "服务层要把校验失败原样交给用户：\(message)")
        }
        // 拒绝的备份不能写进库：原来的两句话还在。
        XCTAssertEqual(try db.stores.messages.count(), 2)
    }

    // MARK: - 往返幂等

    func testSealedRoundTripIsIdempotent() throws {
        var bundle = try XCTUnwrap(BackupCodec.decode(try service.export()).bundle)
        bundle.checksum = nil

        let first = try BackupCodec.encodeSealed(bundle)
        let decoded = try XCTUnwrap(BackupCodec.decode(first).bundle)
        XCTAssertEqual(decoded.checksum, BackupIntegrity.checksum(ofJSONText: first))
        XCTAssertEqual(try BackupCodec.encodeSealed(decoded), first, "解→编→解 必须逐字幂等（含哈希）")
        // 内容不变。
        XCTAssertEqual(decoded.personas, bundle.personas)
        XCTAssertEqual(decoded.messages, bundle.messages)
    }

    // MARK: - 版本门槛

    func testFutureFormatVersionIsRejectedWithUpgradeHint() throws {
        let raw = #"{"format":"myfriend.backup","formatVersion":8,"personas":[{"id":"p1"}]}"#
        let result = BackupCodec.decode(raw)
        XCTAssertFalse(result.isSuccess)
        let reason = try XCTUnwrap(result.failureReason)
        XCTAssertTrue(reason.contains("v8"))
        XCTAssertTrue(reason.contains("升级"))
        XCTAssertTrue(reason.contains("v\(BackupCodec.formatVersion)"))
    }

    func testFutureSchemaVersionWarnsButStillReads() throws {
        let raw = """
        {"format":"myfriend.backup","formatVersion":\(BackupCodec.formatVersion),"schemaVersion":99,
         "personas":[{"id":"p1","name":"他"}]}
        """
        let result = BackupCodec.decode(raw)
        let bundle = try XCTUnwrap(result.bundle, "schema 更新不拒绝（格式版本才是硬门槛）")
        XCTAssertEqual(bundle.schemaVersion, 99)
        XCTAssertEqual(bundle.personas.first?.name, "他")
        XCTAssertTrue(
            result.warnings.contains { $0.contains("数据库 v99") && $0.contains("v\(Schema.version)") },
            "要提示「来自更新版本的库」：\(result.warnings)"
        )
    }

    // MARK: - 老备份（v1–v7）

    func testOldFixturesStillDecode() throws {
        guard let directory = RepoLayout.backupFixtureDirectory() else {
            throw XCTSkip("找不到 fixtures/backup")
        }
        let names = [
            "android-export-v1.json", "android-export-v2.json", "android-export-v3.json",
            "android-export-v4.json", "android-export-v5.json", "android-export-v6.json",
            "android-export-v7.json",
            "ios-export-v1.json", "ios-export-v2.json", "ios-export-v3.json",
            "ios-export-v4.json", "ios-export-v5.json", "ios-export-v6.json",
            "ios-export-v7.json",
            "server-export-v7-extensions.json"
        ]
        var checked = 0
        for name in names {
            let url = directory.appendingPathComponent(name)
            guard fileManager.fileExists(atPath: url.path) else { continue }
            let raw = try String(contentsOf: url, encoding: .utf8)
            let bundle = try XCTUnwrap(
                BackupCodec.decode(raw).bundle,
                "\(name) 应当仍然能读"
            )
            // 老样本没有 0.15 的元数据；但 v7 样本可能被 Android 0.15 重新生成（带上封条），
            // 那时也必须**读得出来**——带本机算法标记的封条只有复核通过才会走到这里。
            if let checksum = bundle.checksum {
                XCTAssertEqual(bundle.checksumAlgorithm, BackupIntegrity.algorithm,
                               "\(name) 带封条就必须带本机算法标记：\(checksum)")
                XCTAssertEqual(BackupPreview.from(bundle).integrityText(),
                               "已校验：SHA-256 " + String(checksum.prefix(8)))
            } else {
                XCTAssertNil(bundle.checksumAlgorithm, "\(name) 没有封条就不该有算法标记")
            }
            XCTAssertGreaterThanOrEqual(bundle.schemaVersion, 0)
            XCTAssertGreaterThanOrEqual(bundle.createdAt, 0)
            XCTAssertEqual(bundle.formatVersion, BackupCodec.formatVersion)
            checked += 1
        }
        XCTAssertGreaterThanOrEqual(checked, 14, "应当覆盖 v1–v7 的两端样本（实际 \(checked) 份）")
    }

    // MARK: - 预览里的备份信息

    func testPreviewShowsBackupIdentity() throws {
        let bundle = try XCTUnwrap(BackupCodec.decode(try service.export()).bundle)
        let preview = BackupPreview.from(bundle)

        XCTAssertEqual(preview.formatVersion, BackupCodec.formatVersion)
        XCTAssertEqual(preview.schemaVersion, Int(Schema.version))
        XCTAssertEqual(preview.createdAt, TestData.now)
        XCTAssertEqual(
            preview.originText(),
            "备份格式 v\(BackupCodec.formatVersion) · 数据库 v\(Schema.version)"
        )

        let checksum = try XCTUnwrap(preview.checksum)
        XCTAssertTrue(preview.checksumVerified)
        XCTAssertFalse(preview.checksumForeign)
        XCTAssertEqual(preview.integrityText(), "已校验：SHA-256 \(checksum.prefix(8))")
        XCTAssertTrue(preview.metaText().contains("v0.15.0-ios"), preview.metaText())
        XCTAssertTrue(preview.metaText().contains("备份格式 v7"), preview.metaText())
        XCTAssertTrue(preview.metaText().contains(String(checksum.prefix(8))), preview.metaText())
    }

    func testPreviewForOldBackupSaysNoChecksum() {
        let preview = BackupPreview(
            personas: 1,
            formatVersion: 6,
            schemaVersion: 0,
            createdAt: 0,
            checksum: nil
        )
        XCTAssertEqual(preview.originText(), "备份格式 v6", "没有数据库版本时不能编一个 v0 出来")
        XCTAssertEqual(preview.integrityText(), "没有校验值（更早的备份）")
        XCTAssertTrue(preview.metaText().contains("备份格式 v6"))
    }
}
