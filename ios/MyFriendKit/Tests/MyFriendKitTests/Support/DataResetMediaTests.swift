import Foundation
import XCTest
@testable import MyFriendKit

/// 清空类动作的媒体清理（0.10.2 审计 P1-7 / P1-8）。
///
/// 两条缺陷都在「用户以为删干净了」这一类隐私问题上：
/// 1. 备份页的「删除全部」不删附件与头像文件（媒体清理挂在两个 View 上，备份页漏了）；
/// 2. `MediaStorage.deleteAll()` 重建目录后丢掉 `isExcludedFromBackup`，于是清空过一次之后，
///    几百 MB 的聊天图开始进 iCloud/iTunes 整机备份。
///
/// 修法：媒体清理收进 `DataReset`（两个入口都只调它），`deleteAll` 重建后重新打排除标记。
final class DataResetMediaTests: XCTestCase {

    private var temp: TempDatabase!
    private var settings: InMemorySettings!
    private var media: MediaStorage!
    private var directory: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        settings = InMemorySettings(AppSettings(activePersonaId: "persona-1"))
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("mf-media-reset-\(UUID().uuidString)", isDirectory: true)
        media = MediaStorage(directory: directory)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 小工具

    private func writeFile(_ name: String) throws -> URL {
        let url = directory.appendingPathComponent(name)
        try Data([0xFF, 0xD8, 0xFF]).write(to: url)
        return url
    }

    private func filesInDirectory() -> Set<String> {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return Set(names)
    }

    private func isExcludedFromBackup() -> Bool {
        ((try? directory.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup) ?? false
    }

    private func makeReset() -> DataReset {
        DataReset(stores: temp.stores, settings: settings, media: media)
    }

    // MARK: - P1-8：重建目录后仍不进备份

    func testDeleteAllKeepsDirectoryOutOfBackup() throws {
        try writeFile("chatImage-a.jpg")
        XCTAssertTrue(isExcludedFromBackup(), "init 时就该排除 iCloud 备份")

        media.deleteAll()
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.path), "目录要重建出来")
        XCTAssertTrue(isExcludedFromBackup(), "deleteAll 重建目录后必须重新打上「不进备份」标记")
    }

    func testDeleteAllRemovesEverything() throws {
        try writeFile("chatImage-a.jpg")
        try writeFile("avatar-b.jpg")
        media.deleteAll()
        XCTAssertTrue(filesInDirectory().isEmpty)
    }

    // MARK: - 只删聊天图（头像留着）

    func testDeleteAllChatImagesKeepsAvatars() throws {
        try writeFile("chatImage-a.jpg")
        try writeFile("chatImage-b.jpg")
        try writeFile("avatar-c.jpg")
        let removed = media.deleteAllChatImages()
        XCTAssertEqual(removed, 2)
        XCTAssertEqual(filesInDirectory(), ["avatar-c.jpg"])
    }

    // MARK: - P1-7：两个清空入口都会清媒体

    func testClearChatsDeletesChatImagesButKeepsAvatars() throws {
        try writeFile("chatImage-a.jpg")
        let avatar = try writeFile("avatar-b.jpg")
        try temp.stores.personas.upsert(TestData.persona())
        try temp.stores.sessions.upsert(TestData.session())
        try temp.stores.messages.upsert([
            TestData.message(id: "m-1", role: .user, content: "图", createdAt: TestData.now)
        ])

        let summary = try makeReset().clearChats()
        XCTAssertEqual(summary.messages, 1)
        XCTAssertFalse(filesInDirectory().contains("chatImage-a.jpg"), "清空聊天与记忆要删掉聊天图")
        XCTAssertTrue(FileManager.default.fileExists(atPath: avatar.path), "人还在，头像不能删")
    }

    func testClearAllDeletesEveryMediaFile() throws {
        try writeFile("chatImage-a.jpg")
        try writeFile("avatar-b.jpg")
        try temp.stores.personas.upsert(TestData.persona())

        _ = makeReset().clearAll()
        XCTAssertTrue(filesInDirectory().isEmpty, "「删除全部本地数据」必须连图片与头像一起删")
        XCTAssertTrue(isExcludedFromBackup(), "清空之后重建的目录仍然不能进备份")
    }

    // MARK: - P2-12：「删除全部」逐表吞错，但要如实上报

    func testClearAllReportsTablesThatFailedToClear() throws {
        try temp.stores.personas.upsert(TestData.persona())
        try temp.stores.presets.upsert(PersonaPresetEntity(
            id: "preset-1", name: "模板", description: "", prompt: "", category: "",
            avatarSeed: 1, createdAt: TestData.now, updatedAt: TestData.now)
        )
        // 让其中一张表「删不掉」：表被外力干掉了（迁移失败/库里损坏的真实形态）。
        try temp.stores.database.execute("DROP TABLE memory_terms")

        let summary = makeReset().clearAll()
        XCTAssertEqual(summary.warnings, ["memory_terms"], "哪张表没删成功要如实收集")
        XCTAssertTrue(
            summary.describeAll().contains("没删成功"),
            "界面文案要说明「有表没删掉」，不能只报「都删掉了」：\(summary.describeAll())"
        )
    }

    /// 装配层必须真的把 `MediaStorage` 传进 `DataReset`（否则清理又是一条死路）。
    func testAppEnvironmentWiresMediaIntoDataReset() throws {
        let environment = try Self.readIOSFile("MyFriend/App/AppEnvironment.swift")
        XCTAssertTrue(
            environment.contains("media: .shared"),
            "AppEnvironment 要把 MediaStorage.shared 传给 DataReset"
        )
    }

    /// 备份页的两个危险动作都必须只走 `DataReset`（0.10.1 就是这里漏了媒体清理）。
    func testBackupScreenClearsThroughDataResetOnly() throws {
        let screen = try Self.readIOSFile("MyFriend/Views/Backup/BackupScreen.swift")
        XCTAssertTrue(screen.contains("env.dataReset.clearChats()"), "备份页要调 DataReset.clearChats")
        XCTAssertTrue(screen.contains("env.dataReset.clearAll()"), "备份页要调 DataReset.clearAll")
        XCTAssertFalse(
            screen.contains("MediaStorage.shared.deleteAll()"),
            "备份页不该自己删文件（清理只在 DataReset 里做一次，两个入口才不会走偏）"
        )
    }

    private static func readIOSFile(_ relative: String) throws -> String {
        var current = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<12 {
            let candidate = current.appendingPathComponent("MyFriend/Resources", isDirectory: true)
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: candidate.path, isDirectory: &isDir), isDir.boolValue {
                return try String(contentsOf: current.appendingPathComponent(relative), encoding: .utf8)
            }
            current = current.deletingLastPathComponent()
        }
        fatalError("找不到 ios/ 目录")
    }
}
