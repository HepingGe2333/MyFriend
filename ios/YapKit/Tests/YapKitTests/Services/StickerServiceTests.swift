import Foundation
import XCTest
@testable import YapKit

/// 表情服务（`Services/StickerService.swift`）：导入 / 制作 / 发送 / 收藏 / 服务端模式。
///
/// 每个用例都用临时库 + 临时表情目录，所以「文件到底落在哪、落库的是路径还是
/// `/v1/media/<id>`」都是可断言的，不用去碰真实 App 的 `Application Support`。
final class StickerServiceTests: XCTestCase {

    private var temp: TempDatabase!
    private var storage: StickerStorage!
    private var service: StickerService!

    private let personaId = "persona-1"
    private let sessionId = "session-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        storage = StickerStorage(directory: temp.directory.appendingPathComponent("stickers", isDirectory: true))
        service = StickerService(
            stores: temp.stores,
            storage: storage,
            session: StubURLProtocol.makeSession(),
            now: { self.now }
        )
        try temp.stores.personas.upsert(TestData.persona(id: personaId))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
    }

    override func tearDownWithError() throws {
        StubURLProtocol.reset()
        temp.cleanUp()
        temp = nil
        storage = nil
        service = nil
        try super.tearDownWithError()
    }

    // MARK: - 导入

    func testImportCompressesAndWritesFileUnderStickerDirectory() async throws {
        let gif = StickerTestMedia.animatedGIF(width: 640, height: 480, frameCount: 20, delaySeconds: 0.04)
        let sticker = try await service.importSticker(data: gif)

        XCTAssertEqual(sticker.mime, "image/gif")
        XCTAssertLessThanOrEqual(max(sticker.width, sticker.height), StickerLimits.maxLongEdge)
        XCTAssertLessThanOrEqual(sticker.frames, StickerLimits.maxFrames)
        XCTAssertLessThanOrEqual(sticker.bytes, StickerLimits.maxBytes)
        XCTAssertEqual(sticker.source, StickerSource.imported.rawValue)
        XCTAssertEqual(sticker.createdBy, StickerCreator.user.rawValue)
        XCTAssertEqual(sticker.createdAt, now)

        // 文件真的在表情目录里（不是聊天附件目录）。
        let url = try XCTUnwrap(storage.fileURL(for: sticker.file))
        XCTAssertTrue(url.path.hasPrefix(storage.directory.path))
        XCTAssertTrue(url.lastPathComponent.hasSuffix(".gif"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))

        // 库里那一行与文件一致。
        let stored = try XCTUnwrap(try temp.stores.stickers.byId(sticker.id))
        XCTAssertEqual(stored, sticker)
        XCTAssertEqual(try service.recent().map(\.id), [sticker.id])
    }

    func testImportRejectsBrokenAndOversizedInput() async throws {
        do {
            _ = try await service.importSticker(data: Data("not an image".utf8))
            XCTFail("坏图片应当失败")
        } catch let error as StickerError {
            XCTAssertEqual(error, .compressionFailed)
        }
    }

    // MARK: - 发送

    func testSendCreatesAssistantMessageWithStickerAttachment() async throws {
        let sticker = try await service.importSticker(data: StickerTestMedia.animatedGIF())
        let message = try service.send(stickerId: sticker.id, personaId: personaId, sessionId: sessionId)

        XCTAssertEqual(message.role, .assistant)
        XCTAssertEqual(message.sessionId, sessionId)
        XCTAssertTrue(message.content.isEmpty)
        let attachments = AttachmentsCodec.decode(message.attachmentsJson)
        XCTAssertEqual(attachments.count, 1)
        let attachment = try XCTUnwrap(attachments.first)
        XCTAssertEqual(attachment.mime, "image/gif")
        XCTAssertEqual(attachment.kind, MessageAttachment.stickerKind)
        XCTAssertTrue(attachment.isSticker)
        XCTAssertEqual(attachment.width, sticker.width)

        // 消息真的落库了，会话预览也更新了。
        XCTAssertEqual(try temp.stores.messages.byId(message.id), message)
        XCTAssertEqual(try temp.stores.sessions.byId(sessionId)?.lastMessagePreview, "表情")
    }

    func testSendMissingStickerFails() throws {
        XCTAssertThrowsError(try service.send(stickerId: "nope", personaId: personaId, sessionId: sessionId)) {
            XCTAssertEqual($0 as? StickerError, .stickerNotFound)
        }
        XCTAssertThrowsError(try service.send(stickerId: "  ", personaId: personaId, sessionId: sessionId)) {
            XCTAssertEqual($0 as? StickerError, .missingStickerId)
        }
    }

    // MARK: - 收藏

    func testSaveOfAlreadyCollectedStickerIsIdempotent() async throws {
        // 用户发过的一条表情消息：文件已经在表情目录里、库里已经有这一行。
        let imported = try await service.importSticker(data: StickerTestMedia.animatedGIF())
        let message = try service.send(stickerId: imported.id, personaId: personaId, sessionId: sessionId)

        let first = try await service.save(fromMessageId: message.id)
        XCTAssertTrue(first.alreadySaved, "本来就在「我的表情」里")
        XCTAssertEqual(first.sticker.id, imported.id)
        XCTAssertEqual(try temp.stores.stickers.count(), 1, "不该重复占一行")

        let second = try await service.save(fromMessageId: message.id)
        XCTAssertTrue(second.alreadySaved)
        XCTAssertEqual(second.sticker.id, imported.id)
        XCTAssertEqual(try temp.stores.stickers.count(), 1)
    }

    /// 用户从聊天里发来的普通 GIF（附件在聊天附件目录，不是表情目录）：收藏要复制进表情目录。
    func testSaveCopiesPlainChatGIFIntoStickerDirectory() async throws {
        let attachmentsDir = temp.directory.appendingPathComponent("attachments", isDirectory: true)
        try FileManager.default.createDirectory(at: attachmentsDir, withIntermediateDirectories: true)
        let sourceURL = attachmentsDir.appendingPathComponent("chat-cat.gif")
        try StickerTestMedia.animatedGIF(width: 300, height: 300, frameCount: 8).write(to: sourceURL)

        let attachment = MessageAttachment(
            uri: sourceURL.absoluteString,
            mime: "image/gif",
            width: 300,
            height: 300,
            kind: MessageAttachment.stickerKind
        )
        let message = MessageEntity(
            id: "msg-user-sticker",
            sessionId: sessionId,
            personaId: personaId,
            role: .user,
            content: "",
            createdAt: now,
            updatedAt: now,
            status: .complete,
            attachmentsJson: AttachmentsCodec.encode([attachment])
        )
        try temp.stores.messages.upsert(message)

        let result = try await service.save(fromMessageId: message.id)
        XCTAssertFalse(result.alreadySaved)
        XCTAssertEqual(result.sticker.source, StickerSource.fromUserMessage.rawValue)
        XCTAssertEqual(result.sticker.createdBy, StickerCreator.user.rawValue)
        XCTAssertEqual(try temp.stores.stickers.count(), 1)
        let storedURL = try XCTUnwrap(storage.fileURL(for: result.sticker.file))
        XCTAssertTrue(storedURL.path.hasPrefix(storage.directory.path), "收藏的文件必须复制进表情目录")
        XCTAssertTrue(FileManager.default.fileExists(atPath: storedURL.path))
        XCTAssertEqual(try Data(contentsOf: storedURL), try Data(contentsOf: sourceURL))

        let again = try await service.save(fromMessageId: message.id)
        XCTAssertTrue(again.alreadySaved)
        XCTAssertEqual(again.sticker.id, result.sticker.id)
    }

    func testSaveRejectsMessageWithoutSticker() async throws {
        let message = TestData.message(
            id: "msg-text", sessionId: sessionId, role: .user, content: "只有文字", createdAt: now
        )
        try temp.stores.messages.upsert(message)
        do {
            _ = try await service.save(fromMessageId: "msg-text")
            XCTFail("没有表情的消息应当失败")
        } catch let error as StickerError {
            XCTAssertEqual(error, .messageHasNoSticker)
        }
    }

    // MARK: - 制作：叠字

    func testMakeWithBlankBaseAndText() async throws {
        let sticker = try await service.makeSticker(text: "今天也要开心", position: "TOP")
        XCTAssertEqual(sticker.source, StickerSource.modelMade.rawValue)
        XCTAssertEqual(sticker.createdBy, StickerCreator.model.rawValue)
        XCTAssertEqual(sticker.frames, 1, "空白底 + 文字是静态表情")
        XCTAssertLessThanOrEqual(max(sticker.width, sticker.height), StickerLimits.maxLongEdge)
        XCTAssertLessThanOrEqual(sticker.bytes, StickerLimits.maxBytes)
        let url = try XCTUnwrap(storage.fileURL(for: sticker.file))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
    }

    func testMakeWithAnimatedBaseKeepsAnimation() async throws {
        let base = try await service.importSticker(
            data: StickerTestMedia.animatedGIF(width: 320, height: 320, frameCount: 12, delaySeconds: 0.1)
        )
        let made = try await service.makeSticker(baseStickerId: base.id, text: "啊？", position: "BOTTOM")
        XCTAssertGreaterThan(made.frames, 1, "动图基底 + 文字应当还是动图")
        XCTAssertLessThanOrEqual(made.frames, StickerLimits.maxFrames)
        let madeURL = try XCTUnwrap(storage.fileURL(for: made.file))
        let inspected = try XCTUnwrap(StickerGIF.inspect(data: try Data(contentsOf: madeURL)))
        XCTAssertLessThanOrEqual(inspected.durationMillis, StickerLimits.maxDurationMillis)
    }

    // MARK: - 制作：叠图

    func testMakeWithOverlaySticker() async throws {
        let base = try await service.importSticker(data: StickerTestMedia.staticPNG(width: 320, height: 320))
        let overlay = try await service.importSticker(data: StickerTestMedia.animatedGIF(width: 160, height: 160))
        let made = try await service.makeSticker(
            baseStickerId: base.id, overlayStickerId: overlay.id, position: "CENTER"
        )
        XCTAssertEqual(made.frames, 1)
        XCTAssertLessThanOrEqual(made.bytes, StickerLimits.maxBytes)
        XCTAssertNotEqual(made.id, base.id)
        XCTAssertNotEqual(made.id, overlay.id)
    }

    // MARK: - 制作：下载 URL

    func testMakeFromURLDownloadsAndRecordsWebSource() async throws {
        let gif = StickerTestMedia.animatedGIF(width: 480, height: 480, frameCount: 8)
        StubURLProtocol.enqueue(StubURLProtocol.Stub(
            status: 200,
            headers: ["Content-Type": "image/gif"],
            chunks: [gif]
        ))
        let sticker = try await service.makeSticker(url: "https://example.com/cat.gif")
        XCTAssertEqual(sticker.source, StickerSource.web.rawValue)
        XCTAssertLessThanOrEqual(max(sticker.width, sticker.height), StickerLimits.maxLongEdge)
        XCTAssertEqual(StubURLProtocol.recordedRequests.count, 1)
    }

    func testMakeRejectsPrivateAddressWithoutRequesting() async throws {
        do {
            _ = try await service.makeSticker(url: "http://127.0.0.1:8080/cat.gif")
            XCTFail("本机地址应当被 WebUrlPolicy 拒绝")
        } catch let error as StickerError {
            guard case .downloadRejected = error else {
                return XCTFail("应当是 downloadRejected，实际 \(error)")
            }
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "被拒的链接不该发出去")
    }

    func testMakeRejectsOversizedDownloadByContentLength() async throws {
        StubURLProtocol.enqueue(StubURLProtocol.Stub(
            status: 200,
            headers: ["Content-Type": "image/gif", "Content-Length": "99999999"],
            chunks: [Data([0x47, 0x49, 0x46])]
        ))
        do {
            _ = try await service.makeSticker(url: "https://example.com/huge.gif")
            XCTFail("超过 8 MB 应当失败")
        } catch let error as StickerError {
            XCTAssertEqual(error, .downloadTooLarge)
        }
    }

    func testMakeWithOnlyBaseRefusesToDuplicate() async throws {
        let base = try await service.importSticker(data: StickerTestMedia.staticPNG(width: 320, height: 320))
        do {
            _ = try await service.makeSticker(baseStickerId: base.id)
            XCTFail("只给底图做不出新表情")
        } catch let error as StickerError {
            XCTAssertEqual(error, .nothingToCompose)
        }
    }

    func testMakeRequiresAtLeastOneInput() async throws {
        do {
            _ = try await service.makeSticker()
            XCTFail("什么参数都不给应当失败")
        } catch let error as StickerError {
            XCTAssertEqual(error, .missingActionInput)
        }
    }

    // MARK: - 删除

    func testDeleteKeepsFileThatIsStillReferencedByAMessage() async throws {
        let sticker = try await service.importSticker(data: StickerTestMedia.animatedGIF())
        let fileURL = try XCTUnwrap(storage.fileURL(for: sticker.file))
        _ = try service.send(stickerId: sticker.id, personaId: personaId, sessionId: sessionId)

        let removed = try await service.delete(id: sticker.id)
        XCTAssertTrue(removed)
        XCTAssertNil(try temp.stores.stickers.byId(sticker.id))
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: fileURL.path),
            "消息还在引用这个文件：删收藏不能把历史里的表情弄坏"
        )
    }

    func testDeleteRemovesUnreferencedFile() async throws {
        let sticker = try await service.importSticker(data: StickerTestMedia.animatedGIF())
        let fileURL = try XCTUnwrap(storage.fileURL(for: sticker.file))
        let removed = try await service.delete(id: sticker.id)
        XCTAssertTrue(removed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path))
    }

    /// 服务端模式：删除必须**先**过服务端，再删本机缓存行；面板重新读列表就看不到它了。
    func testServerModeDeleteCallsServerFirstThenRemovesLocalRow() async throws {
        let catalog = FakeStickerCatalog()
        let serverService = StickerService(
            stores: temp.stores,
            storage: storage,
            session: StubURLProtocol.makeSession(),
            serverCatalog: catalog,
            now: { self.now }
        )
        let sticker = StickerEntity(
            id: "stk_1", ownerUserId: "owner-1", personaId: personaId,
            file: "/v1/media/stk_1", mime: "image/gif", width: 1, height: 1,
            frames: 1, bytes: 42, source: "IMPORTED", createdBy: "USER",
            createdAt: now
        )
        try temp.stores.stickers.upsert([sticker])

        let removed = try await serverService.delete(id: sticker.id)

        XCTAssertTrue(removed)
        XCTAssertEqual(catalog.deletedIds, ["stk_1"], "服务端模式必须先 DELETE /v1/stickers/{id}")
        XCTAssertNil(try temp.stores.stickers.byId(sticker.id), "本地缓存行也要删掉")
        XCTAssertTrue(try serverService.all().isEmpty, "面板重新读列表时它必须消失")
    }

    /// 服务端删除失败：**保留**本机行并把错误交给界面提示（不能假装删掉了）。
    func testServerModeDeleteFailureKeepsLocalRowAndThrows() async throws {
        let catalog = FakeStickerCatalog()
        catalog.deleteError = ServerAPIError(code: "server_error", status: 500, message: "服务端挂了")
        let serverService = StickerService(
            stores: temp.stores,
            storage: storage,
            session: StubURLProtocol.makeSession(),
            serverCatalog: catalog,
            now: { self.now }
        )
        let sticker = StickerEntity(
            id: "stk_2", ownerUserId: "owner-1", personaId: personaId,
            file: "/v1/media/stk_2", mime: "image/gif", width: 1, height: 1,
            frames: 1, bytes: 42, source: "IMPORTED", createdBy: "USER",
            createdAt: now
        )
        try temp.stores.stickers.upsert([sticker])

        do {
            _ = try await serverService.delete(id: sticker.id)
            XCTFail("服务端失败时不能当成功")
        } catch let error as StickerError {
            XCTAssertEqual(error, .writeFailed)
        }
        XCTAssertEqual(catalog.deletedIds, ["stk_2"])
        XCTAssertNotNil(try temp.stores.stickers.byId(sticker.id), "失败时保留，用户可以重试")
    }

    /// 服务端已经没了（404）：当成功，继续删本机缓存。
    func testServerModeDeleteTreats404AsAlreadyGone() async throws {
        let catalog = FakeStickerCatalog()
        catalog.deleteError = ServerAPIError(code: "not_found", status: 404, message: "没找到")
        let serverService = StickerService(
            stores: temp.stores,
            storage: storage,
            session: StubURLProtocol.makeSession(),
            serverCatalog: catalog,
            now: { self.now }
        )
        let sticker = StickerEntity(
            id: "stk_3", ownerUserId: "owner-1", personaId: personaId,
            file: "/v1/media/stk_3", mime: "image/gif", width: 1, height: 1,
            frames: 1, bytes: 42, source: "IMPORTED", createdBy: "USER",
            createdAt: now
        )
        try temp.stores.stickers.upsert([sticker])

        let removed = try await serverService.delete(id: sticker.id)

        XCTAssertTrue(removed)
        XCTAssertNil(try temp.stores.stickers.byId(sticker.id))
    }

    // MARK: - 服务端模式

    func testServerModeStoresMediaURIAndSkipsLocalFile() async throws {
        let uploaded = UploadRecorder()
        let serverService = StickerService(
            stores: temp.stores,
            storage: storage,
            session: StubURLProtocol.makeSession(),
            serverUploader: { data, _ in
                uploaded.bytes.append(data)
                return "/v1/media/media-1"
            },
            serverDownloader: { uri in
                uploaded.downloads.append(uri)
                return StickerTestMedia.staticPNG(width: 100, height: 100)
            },
            now: { self.now }
        )
        let sticker = try await serverService.importSticker(data: StickerTestMedia.animatedGIF())
        XCTAssertEqual(sticker.file, "/v1/media/media-1")
        XCTAssertTrue(sticker.isServerSticker)
        XCTAssertEqual(uploaded.bytes.count, 1)
        XCTAssertFalse(uploaded.bytes[0].isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: storage.directory.appendingPathComponent(
            StickerStorage.fileName(for: sticker.id)
        ).path))

        // 用户发送：已经是服务端媒体，不再上传。
        let attachment = try await serverService.prepareForSending(sticker)
        XCTAssertEqual(attachment.uri, "/v1/media/media-1")
        XCTAssertEqual(attachment.kind, MessageAttachment.stickerKind)
        XCTAssertEqual(uploaded.bytes.count, 1)
    }

    func testLocalModePrepareForSendingUsesFileURI() async throws {
        let sticker = try await service.importSticker(data: StickerTestMedia.animatedGIF())
        let attachment = try await service.prepareForSending(sticker)
        XCTAssertTrue(attachment.uri.hasPrefix("file://"))
        XCTAssertTrue(attachment.uri.hasSuffix(".gif"))
        XCTAssertEqual(attachment.mime, "image/gif")
        XCTAssertTrue(attachment.isSticker)
    }
}

/// 记录服务端上传/下载（服务端模式用例用）。
private final class UploadRecorder {
    var bytes: [Data] = []
    var downloads: [String] = []
}


/// 表情服务端写口的假实现（只记录删除，够钉住「先服务端、后本机」）。
private final class FakeStickerCatalog: StickerServerCatalog {
    var deletedIds: [String] = []
    var deleteError: Error?

    func createSticker(
        data: Data, width: Int, height: Int, frames: Int,
        source: String, createdBy: String, personaId: String?
    ) async throws -> ServerStickerRow {
        ServerStickerRow(
            id: "stk_fake", ownerUserId: "owner-1", personaId: personaId,
            file: "stk_fake.gif", fileName: "stk_fake.gif", mediaId: "stk_fake",
            mime: "image/gif", width: width, height: height, frames: frames,
            bytes: data.count, source: source, createdBy: createdBy, createdAt: 1
        )
    }

    func deleteSticker(id: String) async throws {
        deletedIds.append(id)
        if let deleteError { throw deleteError }
    }
}
