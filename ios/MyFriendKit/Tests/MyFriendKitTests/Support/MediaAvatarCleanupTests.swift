import Foundation
import XCTest
@testable import MyFriendKit

/// 媒体与失效路径：
/// 1. 备份导入时 `personas.avatarUri` 指向不存在的文件 → 清空字段并计入 warnings；
/// 2. `MediaStorage.exists/deleteFileIfOwned` 只认自己目录、只删自己目录（临时目录注入）；
/// 3. 编辑器取消/保存失败后的孤儿头像清理、删除人格时删头像——这两处在 App target，
///    用源码守卫钉住「确实接上了」（SwiftUI 状态机没法在 `swift test` 里跑）。
final class MediaAvatarCleanupTests: XCTestCase {

    private var temp: TempDatabase!
    private var media: MediaStorage!
    private var mediaDirectory: URL!

    private let personaId = "persona-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        mediaDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-media-\(UUID().uuidString)", isDirectory: true)
        media = MediaStorage(directory: mediaDirectory)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: mediaDirectory)
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - MediaStorage

    func testExistsAndDeleteOnlyTouchOwnedFiles() throws {
        let owned = mediaDirectory.appendingPathComponent("avatar-1.jpg")
        try Data([0xFF, 0xD8, 0xFF]).write(to: owned)
        XCTAssertTrue(media.exists(uriString: owned.absoluteString))

        let outside = FileManager.default.temporaryDirectory
            .appendingPathComponent("outside-\(UUID().uuidString).jpg")
        try Data([0xFF]).write(to: outside)
        // 0.11.1 安全审计 P2-3：`exists` 现在也要过归属守卫 —— 目录外的真实文件
        // 不能再被当成「本机附件还在」（否则它会以头像/附件的身份落库）。
        XCTAssertFalse(media.exists(uriString: outside.absoluteString), "目录外的文件不算自家附件")
        media.deleteFileIfOwned(outside.absoluteString)
        XCTAssertTrue(FileManager.default.fileExists(atPath: outside.path), "不属于自家目录的文件不能删")
        try? FileManager.default.removeItem(at: outside)

        media.deleteFileIfOwned(owned.absoluteString)
        XCTAssertFalse(FileManager.default.fileExists(atPath: owned.path))
        XCTAssertFalse(media.exists(uriString: owned.absoluteString))
        XCTAssertFalse(media.exists(uriString: nil))
    }

    func testMissingFileIsReportedAsNotExisting() {
        let gone = mediaDirectory.appendingPathComponent("never-written.jpg")
        XCTAssertFalse(media.exists(uriString: gone.absoluteString))
    }

    /// `abs(Int.min)` 会溢出 trap（0.10.2 审计 P2-10）：`avatarSeed` 能从一份手工/损坏的备份
    /// 直接进来，首页渲染时就会崩。这里钉住「取模前不取绝对值」。
    func testAvatarSeedIndexIsOverflowSafe() throws {
        var current = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var path: URL?
        for _ in 0..<12 {
            let candidate = current.appendingPathComponent("MyFriend/Views/Shared/MFAvatar.swift")
            if FileManager.default.fileExists(atPath: candidate.path) { path = candidate; break }
            current = current.deletingLastPathComponent()
        }
        let source = try String(contentsOf: try XCTUnwrap(path), encoding: .utf8)
        XCTAssertFalse(source.contains("abs(avatarSeed)"), "abs(Int.min) 会崩；用 magnitude 取模")
        XCTAssertTrue(source.contains("avatarSeed.magnitude"), "要改成对 magnitude 取模")
    }

    // MARK: - 备份导入：失效头像

    private func makeBackupText(withAvatarExists: Bool) throws -> (String, String) {
        let avatar = mediaDirectory.appendingPathComponent("backup-avatar.jpg")
        try Data([0xFF, 0xD8]).write(to: avatar)
        var persona = TestData.persona(id: personaId)
        persona.avatarUri = avatar.absoluteString
        try temp.stores.personas.upsert(persona)
        let settings = InMemorySettings(AppSettings(activePersonaId: personaId))
        let service = BackupService(
            stores: temp.stores, settings: settings, appVersion: "0.10.1-ios",
            avatarFileExists: { _ in withAvatarExists }, now: { self.now }
        )
        let exported = try service.export()
        // 0.11.0 安全审计 P2-5：导出已经不写本机绝对路径了。这里把 `avatarUri` 塞回去，
        // 模拟「老版本 / 另一端导出的备份」——导入侧「文件不存在则清空」的逻辑必须照旧兜住它。
        let text = exported.replacingOccurrences(
            of: "\"name\":\"\(persona.name)\"",
            with: "\"name\":\"\(persona.name)\",\"avatarUri\":\"\(avatar.absoluteString)\""
        )
        XCTAssertTrue(text.contains(avatar.absoluteString), "夹具要把 avatarUri 写进备份")
        if !withAvatarExists { try? FileManager.default.removeItem(at: avatar) }
        return (text, avatar.absoluteString)
    }

    func testImportClearsAvatarUriWhenFileIsGone() throws {
        let (text, avatarUri) = try makeBackupText(withAvatarExists: false)
        let target = try TempDatabase()
        defer { target.cleanUp() }
        let settings = InMemorySettings(AppSettings(activePersonaId: personaId))
        let service = BackupService(
            stores: target.stores, settings: settings, appVersion: "0.10.1-ios",
            avatarFileExists: { _ in false }, now: { self.now }
        )
        let summary = try service.import(text: text, mode: .replace)
        let imported = try XCTUnwrap(try target.stores.personas.byId(personaId) ?? nil)
        XCTAssertNil(imported.avatarUri, "指向不存在文件的头像要清空，别画一个永远加载不出来的头像")
        XCTAssertTrue(
            summary.warnings.contains { $0.contains("头像") },
            "要如实告诉用户头像没接回来：\(summary.warnings)"
        )
        XCTAssertFalse(avatarUri.isEmpty)
    }

    func testImportKeepsAvatarUriWhenFileExists() throws {
        let (text, avatarUri) = try makeBackupText(withAvatarExists: true)
        let target = try TempDatabase()
        defer { target.cleanUp() }
        let settings = InMemorySettings(AppSettings(activePersonaId: personaId))
        let service = BackupService(
            stores: target.stores, settings: settings, appVersion: "0.10.1-ios",
            avatarFileExists: { _ in true },
            // 0.11.1 安全审计 P2-4：导入会把头像归一成**当前附件目录**里的路径，
            // 所以这里要把目录注入进去（生产默认与 `MediaStorage.shared` 同一个）。
            mediaDirectory: mediaDirectory,
            now: { self.now }
        )
        let summary = try service.import(text: text, mode: .replace)
        let imported = try XCTUnwrap(try target.stores.personas.byId(personaId) ?? nil)
        XCTAssertEqual(imported.avatarUri, avatarUri)
        XCTAssertFalse(summary.warnings.contains { $0.contains("头像") })
    }

    // MARK: - App 层接线（源码守卫）

    private func repoRoot() throws -> URL {
        let fixture = try XCTUnwrap(RepoLayout.backupFixtureDirectory())
        // fixtures/backup → 仓库根
        return fixture.deletingLastPathComponent().deletingLastPathComponent()
    }

    func testPersonaEditorDiscardsUnsavedAvatarAndUsesDomainCopy() throws {
        let url = try repoRoot().appendingPathComponent("ios/MyFriend/Views/Persona/PersonaEditorScreen.swift")
        let text = try String(contentsOf: url, encoding: .utf8)
        XCTAssertTrue(text.contains("discardUnsavedAvatar"), "取消/保存失败时要清掉刚落盘的头像")
        XCTAssertTrue(text.contains("onDisappear(perform: discardUnsavedAvatar)"),
                      "清理必须挂在离开编辑页时")
        XCTAssertTrue(text.contains("ProactiveLevel.description("),
                      "主动程度文案必须用 domain 的唯一实现")
        XCTAssertFalse(text.contains("proactiveLevelText"),
                       "不许再自己手写一套主动程度文案（已经和 domain 漂移过）")
    }

    func testDeletingPersonaDeletesItsAvatarFile() throws {
        let url = try repoRoot().appendingPathComponent("ios/MyFriend/App/AppEnvironment.swift")
        let text = try String(contentsOf: url, encoding: .utf8)
        XCTAssertTrue(text.contains("deleteFileIfOwned(persona.avatarUri)"),
                      "删人格时要连头像文件一起删（文件不属于数据库）")
    }

    // MARK: - 编辑器文案 == domain 文案

    func testEditorCopyComesFromProactiveLevel() throws {
        let url = try repoRoot().appendingPathComponent("ios/MyFriend/Views/Persona/PersonaEditorScreen.swift")
        let text = try String(contentsOf: url, encoding: .utf8)
        XCTAssertFalse(text.contains("很少主动。他会等你来找他。"),
                       "旧的手写文案已经删了；再出现就说明又漂移了")
        // 编辑器引用的是 domain 的同一句：`swift test` 没有资源包，回落成中文 key，可逐字断言。
        XCTAssertEqual(
            ProactiveLevel.description(1),
            "很少主动。你不找他，他基本不会来打扰。"
        )
        XCTAssertEqual(
            ProactiveLevel.description(3),
            "会主动，但有分寸。大多数日子会来找你说一句话。"
        )
        for level in 1...5 {
            let copy = ProactiveLevel.description(level)
            XCTAssertFalse(copy.isEmpty)
            XCTAssertFalse(copy.contains("%"), "编辑器那句人话不该带占位符")
        }
    }
}
