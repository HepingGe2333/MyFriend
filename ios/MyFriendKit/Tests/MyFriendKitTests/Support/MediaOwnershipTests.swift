import Foundation
import XCTest
@testable import MyFriendKit

/// 媒体路径的所有权守卫（0.11.1 安全审计 P2-3 / P2-4）。
///
/// 审计实测：`AttachmentLocation.resolve("../../myfriend.db")` 会被当成附件目录下的文件
/// （`exists` 返回 true），而 `MediaStorage.delete` 没有目录检查 —— 删一条消息就能删掉
/// App 容器里的任意文件。头像导入也只判断「文件存在吗」，于是 `../../myfriend.db`
/// 会被原样写进 `personas.avatarUri`。
///
/// 这里钉住三件事：解析、删除、头像落库都只认「canonical path 落在 attachments/ 下」。
final class MediaOwnershipTests: XCTestCase {

    private var root: URL!
    private var attachments: URL!
    private var storage: MediaStorage!
    private let fileManager = FileManager.default

    override func setUpWithError() throws {
        try super.setUpWithError()
        root = fileManager.temporaryDirectory
            .appendingPathComponent("mf-ownership-\(UUID().uuidString)", isDirectory: true)
        attachments = root.appendingPathComponent("attachments", isDirectory: true)
        try fileManager.createDirectory(at: attachments, withIntermediateDirectories: true)
        storage = MediaStorage(directory: attachments)
    }

    override func tearDownWithError() throws {
        if let root { try? fileManager.removeItem(at: root) }
        storage = nil
        try super.tearDownWithError()
    }

    private func write(_ relative: String, in directory: URL? = nil) throws -> URL {
        let url = (directory ?? attachments).appendingPathComponent(relative)
        try fileManager.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        try Data([0xFF, 0xD8, 0xFF]).write(to: url)
        return url
    }

    // MARK: - 解析：拒绝穿越与沙盒外路径

    func testResolveRejectsParentTraversal() throws {
        try write("myfriend.db", in: root)   // 目录外真实存在
        XCTAssertNil(AttachmentLocation.resolve("../../myfriend.db", directory: attachments))
        XCTAssertFalse(storage.exists(uriString: "../../myfriend.db"), "指针不能越出 attachments/")
    }

    func testResolveRejectsPercentEncodedTraversal() throws {
        try write("myfriend.db", in: root)
        XCTAssertNil(AttachmentLocation.resolve("%2e%2e%2fmyfriend.db", directory: attachments))
        XCTAssertFalse(storage.exists(uriString: "%2e%2e%2fmyfriend.db"))
    }

    func testResolveRejectsAbsolutePathOutsideDirectory() throws {
        let outside = try write("secret.jpg", in: root)
        XCTAssertNil(AttachmentLocation.resolve(outside.absoluteString, directory: attachments))
        XCTAssertFalse(storage.exists(uriString: outside.path))
    }

    func testResolveRejectsSiblingDirectoryWithSamePrefix() throws {
        let sibling = root.appendingPathComponent("attachments2", isDirectory: true)
        let file = try write("x.jpg", in: sibling)
        XCTAssertNil(AttachmentLocation.resolve(file.absoluteString, directory: attachments),
                     "同前缀但不是子目录")
        XCTAssertFalse(AttachmentLocation.isWithin(attachments, file))
    }

    func testResolveRejectsSymlinkEscape() throws {
        let outside = try write("outside.jpg", in: root)
        let link = attachments.appendingPathComponent("avatar-link.jpg")
        try fileManager.createSymbolicLink(at: link, withDestinationURL: outside)
        XCTAssertNil(
            AttachmentLocation.resolve(link.absoluteString, directory: attachments),
            "软链指向目录外时必须拒绝（canonical path 检查）"
        )
        XCTAssertFalse(AttachmentLocation.isWithin(attachments, link))
    }

    func testResolveStillRebasesWhenContainerUUIDChanged() throws {
        // 旧容器里的绝对路径已经不存在了，但同名文件还在当前附件目录：这是换机/重装后的正常路径。
        let current = try write("chatImage-abc.jpg")
        let old = "file:///var/mobile/Containers/Data/Application/OLD/Documents/attachments/chatImage-abc.jpg"
        let resolved = try XCTUnwrap(AttachmentLocation.resolve(old, directory: attachments))
        XCTAssertEqual(resolved.standardizedFileURL.path, current.standardizedFileURL.path)
        XCTAssertTrue(storage.exists(uriString: old))
    }

    func testResolveAcceptsFileInsideDirectory() throws {
        let current = try write("chatImage-ok.jpg")
        XCTAssertEqual(
            storage.fileURL(for: current.absoluteString)?.standardizedFileURL.path,
            current.standardizedFileURL.path
        )
        // 相对路径（历史手工数据）按附件目录解。
        XCTAssertEqual(
            storage.fileURL(for: "chatImage-ok.jpg")?.standardizedFileURL.path,
            current.standardizedFileURL.path
        )
    }

    func testIsWithinBasics() throws {
        let inside = try write("a/b.jpg")
        XCTAssertTrue(AttachmentLocation.isWithin(attachments, inside))
        XCTAssertFalse(AttachmentLocation.isWithin(attachments, attachments), "目录自身不是「目录里的文件」")
        XCTAssertFalse(AttachmentLocation.isWithin(attachments, root.appendingPathComponent("nope.jpg")))
    }

    // MARK: - 删除：只删自己目录

    func testDeleteRefusesTraversalTarget() throws {
        let victim = try write("myfriend.db", in: root)
        storage.delete(MessageAttachment(uri: "../../myfriend.db", mime: "image/jpeg", width: 1, height: 1))
        XCTAssertTrue(fileManager.fileExists(atPath: victim.path), "目录外的文件不能被删")
    }

    func testDeleteRemovesOwnedFile() throws {
        let owned = try write("chatImage-own.jpg")
        storage.delete(MessageAttachment(
            uri: owned.absoluteString, mime: "image/jpeg", width: 1, height: 1
        ))
        XCTAssertFalse(fileManager.fileExists(atPath: owned.path))
    }

    func testDeleteAttachmentsInJsonRefusesOutsidePath() throws {
        let victim = try write("myfriend.db", in: root)
        let json = #"[{"uri":"../../myfriend.db","mime":"image/jpeg"}]"#
        storage.deleteAttachments(in: json)
        XCTAssertTrue(fileManager.fileExists(atPath: victim.path))
    }

    // MARK: - 头像：备份导入的准入规则（与 Android BackupAvatarPath 等价）

    func testAvatarFileNameRejectsTraversalAndJunk() {
        XCTAssertNil(BackupAvatarPath.fileName("../../myfriend.db"))
        XCTAssertNil(BackupAvatarPath.fileName("..%2fmyfriend.db"))
        XCTAssertNil(BackupAvatarPath.fileName("C:\\windows\\avatar-1.jpg"))
        XCTAssertNil(BackupAvatarPath.fileName(""))
        XCTAssertNil(BackupAvatarPath.fileName("   "))
        XCTAssertEqual(BackupAvatarPath.fileName("avatar-1.jpg"), "avatar-1.jpg")
        XCTAssertEqual(
            BackupAvatarPath.fileName(
                "file:///var/mobile/Containers/Data/Application/OLD/attachments/avatar-1.jpg"
            ),
            "avatar-1.jpg"
        )
    }

    func testAvatarPortableOnlyKeepsOwnFileNames() {
        XCTAssertEqual(BackupAvatarPath.portable("avatar-abc.jpg"), "avatar-abc.jpg")
        XCTAssertNil(BackupAvatarPath.portable("file:///etc/passwd"), "不能把任意文件导出成 passwd")
        XCTAssertNil(BackupAvatarPath.portable("preset.png"))
    }

    func testAvatarImportResolvesOnlyOwnDirectoryAndExistingFile() throws {
        try write("avatar-ok.jpg")
        try write("myfriend.db", in: root)

        let ok = BackupAvatarPath.resolveImported(
            "avatar-ok.jpg", directory: attachments, fileExists: fileManager.fileExists(atPath:)
        )
        XCTAssertEqual(ok, attachments.appendingPathComponent("avatar-ok.jpg").standardizedFileURL.absoluteString)

        XCTAssertNil(
            BackupAvatarPath.resolveImported(
                "../../myfriend.db", directory: attachments, fileExists: fileManager.fileExists(atPath:)
            ),
            "目录外真实存在也不能当头像"
        )
        XCTAssertNil(
            BackupAvatarPath.resolveImported(
                "avatar-missing.jpg", directory: attachments, fileExists: fileManager.fileExists(atPath:)
            )
        )
    }
}
