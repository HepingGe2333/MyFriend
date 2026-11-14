import Foundation
import XCTest
@testable import MyFriendKit

/// 备份导出/导入的加固（0.11.1 审计 P1-4 / P2-4 + 安全审计 P2-4 / P2-12）。
///
/// 三条：
/// 1. 导出与导入的尺寸口径一致（导不回来的备份不允许写出来）；
/// 2. 导入的头像路径只认 attachments/ 下的真实文件（`../../myfriend.db` 不能落库）；
/// 3. 「读文件 + JSON 预解析」在后台线程（`BackupImportLoader`）+ 有界读取。
final class BackupHardeningTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var root: URL!
    private var attachments: URL!
    private var fileManager = FileManager.default

    override func setUpWithError() throws {
        try super.setUpWithError()
        db = try TempDatabase()
        settings = InMemorySettings(AppSettings(activePersonaId: "persona-1"))
        root = fileManager.temporaryDirectory
            .appendingPathComponent("mf-backup-hardening-\(UUID().uuidString)", isDirectory: true)
        attachments = root.appendingPathComponent("attachments", isDirectory: true)
        try fileManager.createDirectory(at: attachments, withIntermediateDirectories: true)
        try seed()
    }

    override func tearDownWithError() throws {
        if let root { try? fileManager.removeItem(at: root) }
        db.cleanUp()
        db = nil
        try super.tearDownWithError()
    }

    private func seed() throws {
        try db.stores.personas.upsert([TestData.persona(id: "persona-1")])
        try db.stores.sessions.upsert(TestData.session(id: "session-1", personaId: "persona-1"))
        try db.stores.messages.upsert([
            TestData.message(id: "msg-1", role: .user, content: "在吗", createdAt: TestData.now - 1000),
            TestData.message(id: "msg-2", role: .assistant, content: "在", createdAt: TestData.now)
        ])
    }

    private func makeService(exportLimitBytes: Int = BackupService.maxExportBytes) -> BackupService {
        BackupService(
            stores: db.stores,
            settings: settings,
            appVersion: "0.11.1-ios",
            avatarFileExists: { [fileManager] path in fileManager.fileExists(atPath: path) },
            mediaDirectory: attachments,
            exportLimitBytes: exportLimitBytes,
            now: { TestData.now }
        )
    }

    // MARK: - 1. 导出上限（P1-4）

    func testExportLimitDefaultsToImportLimit() {
        XCTAssertEqual(BackupService.maxExportBytes, BackupService.maxImportBytes)
        XCTAssertEqual(BackupService.maxExportBytes, 64 * 1024 * 1024)
    }

    /// 超限时明确报错、不返回半份 JSON（调用点因此不会写出文件）。
    func testExportRejectsBackupLargerThanLimit() throws {
        let service = makeService(exportLimitBytes: 256)
        let full = try makeService().export()
        XCTAssertGreaterThan(full.utf8.count, 256, "夹具要真的比上限大，否则用例测不到东西")
        do {
            _ = try service.export()
            XCTFail("超过导出上限必须抛错")
        } catch let error as BackupImportError {
            XCTAssertTrue(error.message.contains("没有写出文件"), "错误要说清楚没写文件：\(error.message)")
            XCTAssertTrue(error.message.contains("256") || error.message.contains("bytes"), error.message)
        }
    }

    func testExportUnderLimitStillWorks() throws {
        let text = try makeService(exportLimitBytes: 1 << 20).export()
        XCTAssertTrue(text.contains("\"formatVersion\":2"))
        XCTAssertTrue(text.contains("在吗"))
    }

    /// 导出不再把设备绝对路径写进备份（安全审计 P2-12）：附件里的 `file:///var/mobile/...` 会被置空。
    func testExportDoesNotCarryAttachmentAbsolutePaths() throws {
        try db.stores.messages.upsert([
            TestData.message(id: "msg-img", role: .user, content: "看图",
                             createdAt: TestData.now - 500)
        ])
        let withPath = MessageEntity(
            id: "msg-img",
            sessionId: "session-1",
            personaId: "persona-1",
            role: .user,
            content: "看图",
            createdAt: TestData.now - 500,
            updatedAt: TestData.now - 500,
            status: .complete,
            attachmentsJson: "[{\"uri\":\"file:///var/mobile/Containers/book.jpg\",\"mime\":\"image/jpeg\"}]"
        )
        try db.stores.messages.upsert(withPath)

        let text = try makeService().export()
        XCTAssertFalse(text.contains("file:///var/mobile"), "设备沙盒路径不该进备份")
        XCTAssertFalse(text.contains("book.jpg"))
    }

    // MARK: - 2. 导入的头像路径（安全审计 P2-4）

    private func bundleWithAvatar(_ avatarUri: String) throws -> BackupBundle {
        let decoded = try XCTUnwrap(BackupCodec.decode(try makeService().export()).bundle)
        var bundle = decoded
        bundle.personas = bundle.personas.map { persona in
            var copy = persona
            copy.avatarUri = avatarUri
            return copy
        }
        return bundle
    }

    func testImportRejectsAvatarTraversalAndClearsIt() throws {
        // 目录外真实存在的文件：旧实现会让它落库。
        try Data([0xFF, 0xD8]).write(to: root.appendingPathComponent("myfriend.db"))
        let bundle = try bundleWithAvatar("../../myfriend.db")
        let summary = try makeService().importBundle(bundle, mode: .merge)
        let stored = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        XCTAssertNil(stored.avatarUri, "穿越路径不能写成头像引用")
        XCTAssertTrue(summary.warnings.contains(where: { $0.contains("头像") }))
    }

    func testImportNormalizesOwnAvatarToCurrentContainer() throws {
        try Data([0xFF, 0xD8]).write(to: attachments.appendingPathComponent("avatar-1.jpg"))
        let oldPath = "file:///var/mobile/Containers/Data/Application/OLD/attachments/avatar-1.jpg"
        let bundle = try bundleWithAvatar(oldPath)
        _ = try makeService().importBundle(bundle, mode: .merge)
        let stored = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        let expected = attachments.appendingPathComponent("avatar-1.jpg").standardizedFileURL.absoluteString
        XCTAssertEqual(stored.avatarUri, expected, "容器 UUID 变了，但同名头像文件还在 → 归一成当前路径")
    }

    func testImportClearsAvatarWhenFileMissing() throws {
        let bundle = try bundleWithAvatar("avatar-missing.jpg")
        _ = try makeService().importBundle(bundle, mode: .merge)
        let stored = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        XCTAssertNil(stored.avatarUri)
    }

    // MARK: - 3. 读 + 预解析（P2-4）

    func testBoundedReadRejectsFileOverLimit() throws {
        let url = root.appendingPathComponent("big-backup.json")
        try Data(repeating: UInt8(ascii: "a"), count: 4096).write(to: url)
        do {
            _ = try BackupService.readBoundedText(at: url, maxBytes: 1024)
            XCTFail("超过上限必须拒绝")
        } catch let error as BackupImportError {
            XCTAssertEqual(error.message, BackupService.tooLargeMessage)
        }
    }

    func testImportLoaderReadsAndPreviewsOffMainThread() async throws {
        let url = root.appendingPathComponent("backup.json")
        let text = try makeService().export()
        try Data(text.utf8).write(to: url)
        let result = await BackupImportLoader.load(at: url)
        switch result {
        case .failure(let error):
            XCTFail("应当读得出来：\(error)")
        case .success(let preparation):
            XCTAssertEqual(preparation.text, text)
            XCTAssertTrue(preparation.decoded.isSuccess)
            XCTAssertEqual(preparation.decoded.bundle?.messages.count, 2)
        }
    }

    func testImportLoaderSurfacesUnreadableFile() async throws {
        let url = root.appendingPathComponent("missing.json")
        let result = await BackupImportLoader.load(at: url)
        guard case .failure(let error) = result else {
            return XCTFail("不存在的文件应当失败")
        }
        XCTAssertFalse(error.localizedDescription.isEmpty)
    }
}
