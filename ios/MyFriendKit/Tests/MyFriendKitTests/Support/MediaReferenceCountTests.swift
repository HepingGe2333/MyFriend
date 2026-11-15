import Foundation
import XCTest
@testable import MyFriendKit

/// 媒体引用计数（0.12 缺陷修复）。
///
/// 附件文件名是 `<kind>-<sha256 前 16>-<uuid>.jpg`，选图/导入按内容哈希复用文件：
/// 于是同一张图会被多条消息、人格头像、预设头像或「我的头像」共享。以前删消息/删人格/
/// 换头像时直接删文件，其它引用就变成「图片已不在本机」。
///
/// 这里钉住三件事：
/// 1. 先删数据库行、再查引用、零引用才删文件；
/// 2. 人格/预设/我的头像共享同一个文件时，最后一条引用消失前文件必须还在；
/// 3. `LIKE` 查询里的 `%` / `_` 必须按字面量转义，不能把不相关的图算成引用。
final class MediaReferenceCountTests: XCTestCase {

    private var temp: TempDatabase!
    private var settings: InMemorySettings!
    private var media: MediaStorage!
    private var directory: URL!
    private let fileManager = FileManager.default

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        settings = InMemorySettings(AppSettings(activePersonaId: "persona-1"))
        directory = fileManager.temporaryDirectory
            .appendingPathComponent("mf-media-refs-\(UUID().uuidString)", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        media = MediaStorage(directory: directory)
    }

    override func tearDownWithError() throws {
        if let directory { try? fileManager.removeItem(at: directory) }
        media = nil
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

    private func exists(_ url: URL) -> Bool {
        fileManager.fileExists(atPath: url.path)
    }

    private func references() -> MediaReferences {
        MediaReferences(stores: temp.stores, settings: settings)
    }

    private func makeMessage(
        id: String,
        sessionId: String = "session-1",
        attachments: [MessageAttachment],
        createdAt: Int64 = TestData.now
    ) -> MessageEntity {
        var message = TestData.message(
            id: id, sessionId: sessionId, role: .user, content: "图", createdAt: createdAt
        )
        message.attachmentsJson = AttachmentsCodec.encode(attachments)
        return message
    }

    private func seedChat() throws {
        try temp.stores.personas.upsert(TestData.persona())
        try temp.stores.sessions.upsert(TestData.session())
    }

    // MARK: - 两条消息共用一张图

    func testSharedChatImageSurvivesUntilLastMessageIsDeleted() throws {
        try seedChat()
        let url = try writeFile("chatImage-0123456789abcdef-11111111-1111-1111-1111-111111111111.jpg")
        let attachment = MessageAttachment(uri: url.absoluteString, width: 2, height: 2)
        let first = makeMessage(id: "m-1", attachments: [attachment], createdAt: TestData.now)
        let second = makeMessage(id: "m-2", attachments: [attachment], createdAt: TestData.now + 1)
        try temp.stores.messages.upsert([first, second])

        let refs = references()
        XCTAssertEqual(try refs.chatImageReferenceCount(fileName: url.lastPathComponent), 2)

        // 删第一条 + 删文件守卫：文件仍被第二条引用，不能删。
        try temp.stores.messages.delete(id: first.id)
        let removedFirst = media.deleteAttachmentsIfUnreferenced(
            in: first.attachmentsJson, references: refs
        )
        XCTAssertEqual(removedFirst, 0)
        XCTAssertTrue(exists(url), "另一条消息还在引用这张图")
        XCTAssertEqual(try refs.chatImageReferenceCount(fileName: url.lastPathComponent), 1)

        // 删第二条：零引用了，才允许真删。
        try temp.stores.messages.delete(id: second.id)
        let removedSecond = media.deleteAttachmentsIfUnreferenced(
            in: second.attachmentsJson, references: refs
        )
        XCTAssertEqual(removedSecond, 1)
        XCTAssertFalse(exists(url))
        XCTAssertEqual(try refs.chatImageReferenceCount(fileName: url.lastPathComponent), 0)
    }

    // MARK: - 预设头像与人格头像共享（以及「我的头像」）

    func testAvatarSharedByPersonaPresetAndMyAvatarIsDeletedOnlyWhenUnreferenced() throws {
        let url = try writeFile("avatar-fedcba9876543210-22222222-2222-2222-2222-222222222222.jpg")
        var persona = TestData.persona()
        persona.avatarUri = url.absoluteString
        try temp.stores.personas.upsert(persona)
        try temp.stores.presets.upsert(PersonaPresetEntity(
            id: "preset-1", name: "模板", description: "", prompt: "", category: "",
            avatarUri: url.absoluteString, avatarSeed: 1,
            createdAt: TestData.now, updatedAt: TestData.now
        ))
        var current = settings.load()
        current.myAvatarUri = url.absoluteString
        settings.save(current)

        let refs = references()
        XCTAssertEqual(try refs.avatarReferenceCount(fileName: url.lastPathComponent), 3)

        // 删人格（库行先删）：预设与我的头像还引用着，文件不能删。
        try temp.stores.personas.delete(id: persona.id)
        XCTAssertFalse(media.deleteIfUnreferenced(url.absoluteString, references: refs))
        XCTAssertTrue(exists(url), "预设头像与我的头像还在引用它")

        // 删预设：只剩我的头像。
        try temp.stores.presets.delete(id: "preset-1")
        XCTAssertFalse(media.deleteIfUnreferenced(url.absoluteString, references: refs))
        XCTAssertTrue(exists(url), "我的头像还在引用它")

        // 清掉「我的头像」：零引用，才删。
        current = settings.load()
        current.myAvatarUri = nil
        settings.save(current)
        XCTAssertTrue(media.deleteIfUnreferenced(url.absoluteString, references: refs))
        XCTAssertFalse(exists(url))
    }

    /// 头像引用不依赖旧容器 UUID：数据库里存旧绝对路径、目录里是同一文件名，也算引用。
    func testAvatarReferenceMatchesByFileNameEvenWhenContainerPathChanged() throws {
        let url = try writeFile("avatar-0000111122223333-33333333-3333-3333-3333-333333333333.jpg")
        var persona = TestData.persona()
        persona.avatarUri = "file:///var/mobile/Containers/Data/Application/OLD/Documents/attachments/"
            + url.lastPathComponent
        try temp.stores.personas.upsert(persona)

        let refs = references()
        XCTAssertEqual(try refs.avatarReferenceCount(fileName: url.lastPathComponent), 1)
        XCTAssertFalse(media.deleteIfUnreferenced(url.absoluteString, references: refs))
        XCTAssertTrue(exists(url))
    }

    // MARK: - LIKE 转义

    func testLikePatternEscapesPercentAndUnderscore() throws {
        // 文件名里理论上不会出现 `%` / `_`（哈希是十六进制），但这条查询是数据入口：
        // 一条手工/损坏的 attachmentsJson 不该让 `_` 变成通配符，误判成「还有引用」。
        XCTAssertEqual(MediaReferences.escapeLike("a_b%c\\d"), "a\\_b\\%c\\\\d")

        try seedChat()
        let url = try writeFile("chatImage-a_b%c.jpg")
        // 注意：`URL.absoluteString` 会把 `%` 转义成 `%25`，那样就测不到 LIKE 通配符了。
        // 这里直接写一条历史/手工数据形状的 attachmentsJson（URI 是原始字面量）。
        var message = makeMessage(id: "m-like", attachments: [])
        message.attachmentsJson = #"[{"uri":"file:///tmp/chatImage-a_b%c.jpg"}]"#
        try temp.stores.messages.upsert(message)

        let refs = references()
        XCTAssertEqual(try refs.chatImageReferenceCount(fileName: "chatImage-a_b%c.jpg"), 1,
                       "完全相同的文件名当然算引用")
        // 未转义时 `_` 会匹配任意一个字符、`%` 会匹配任意串 —— 这两个都必须是 0。
        XCTAssertEqual(try refs.chatImageReferenceCount(fileName: "chatImage-aXb%c.jpg"), 0)
        XCTAssertEqual(try refs.chatImageReferenceCount(fileName: "chatImage-a_bZZZ.jpg"), 0)
        XCTAssertEqual(try refs.chatImageReferenceCount(fileName: "chatImage-aXbYc.jpg"), 0)

        // 守卫也走同一条查询：删一条别的消息不能误删这张。
        let otherURL = try writeFile("chatImage-other.jpg")
        let other = makeMessage(
            id: "m-other", attachments: [MessageAttachment(uri: otherURL.absoluteString)]
        )
        try temp.stores.messages.upsert(other)
        XCTAssertFalse(media.deleteIfUnreferenced(otherURL.absoluteString, references: refs))
        XCTAssertTrue(exists(otherURL))
    }

    // MARK: - 批量清理（清空聊天）

    func testDeleteAllChatImagesKeepsFilesStillReferencedByAvatars() throws {
        try seedChat()
        let chatOnly = try writeFile("chatImage-only.jpg")
        let shared = try writeFile("chatImage-shared.jpg")
        // 头像与聊天图同名（伪造的共享场景）：清空聊天时它仍被「我的头像」引用。
        var current = settings.load()
        current.myAvatarUri = shared.absoluteString
        settings.save(current)

        XCTAssertEqual(
            media.deleteAllChatImages(references: references()), 1,
            "只该删掉零引用的那张聊天图"
        )
        XCTAssertFalse(exists(chatOnly))
        XCTAssertTrue(exists(shared), "还被我的头像引用着")
    }

    /// 引用查询失败时**保守不删**：删库之后数据库被外力关掉，不能让文件凭空消失。
    func testDeleteIsConservativeWhenReferenceQueryFails() throws {
        let url = try writeFile("avatar-closed-db.jpg")
        temp.stores.database.close()
        XCTAssertFalse(media.deleteIfUnreferenced(url.absoluteString, references: references()))
        XCTAssertTrue(exists(url))
    }
}
