import Foundation
import XCTest
@testable import YapKit

/// 模型工具 `sticker`（`spec/stickers/README.md` §模型工具）：send / make / save 三条路径。
///
/// 这里测的是**工具层**（参数解析、结果回灌文案、动作卡片标题），压缩与文件细节在
/// `StickerGIFTests` / `StickerServiceTests` 里；两边都跑，才知道「模型说的话」和
/// 「真的发生了什么」一致。
final class StickerToolTests: XCTestCase {

    private var temp: TempDatabase!
    private var storage: StickerStorage!
    private var service: StickerService!
    private var executor: ToolExecutor!

    private let personaId = "persona-1"
    private let sessionId = "session-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
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
        let apiKeys = ApiKeyStore(backend: InMemoryKeychain())
        let memory = MemoryRepository(stores: temp.stores, vectorIndex: MemoryVectorIndex(stores: temp.stores))
        executor = ToolExecutor(
            stores: temp.stores,
            apiKeys: apiKeys,
            memory: memory,
            recallService: RecallService(stores: temp.stores, memoryRepository: memory),
            mood: MoodRepository(stores: temp.stores, now: { self.now }),
            personaCards: PersonaCardRepository(stores: temp.stores),
            intents: AgentIntentSupport(
                stores: temp.stores, ticks: AgentTickStore(database: temp.stores.database)
            ),
            stickers: service,
            webTools: WebToolsClient(session: StubURLProtocol.makeSession()),
            now: { self.now }
        )
    }

    override func tearDownWithError() throws {
        StubURLProtocol.reset()
        temp.cleanUp()
        temp = nil
        storage = nil
        service = nil
        executor = nil
        try super.tearDownWithError()
    }

    // MARK: - send

    func testSendSendsExistingStickerAsAssistantMessage() async throws {
        let sticker = try await service.importSticker(data: StickerTestMedia.animatedGIF())
        let result = await run(["action": "send", "stickerId": sticker.id])

        XCTAssertTrue(result.ok, result.errorMessage ?? "")
        XCTAssertEqual(result.traceTitle, "已发送表情")
        XCTAssertTrue(result.contentForModel.contains("已经把表情发出去了"))
        XCTAssertEqual(result.summary, sticker.displaySummary)

        let messages = try temp.stores.messages.bySession(sessionId)
        let sent = try XCTUnwrap(messages.last)
        XCTAssertEqual(sent.role, .assistant)
        let attachment = try XCTUnwrap(AttachmentsCodec.decode(sent.attachmentsJson).first)
        XCTAssertTrue(attachment.isSticker)
        XCTAssertEqual(attachment.mime, "image/gif")
    }

    func testSendWithoutStickerIdFailsLoudly() async throws {
        let result = await run(["action": "send"])
        XCTAssertFalse(result.ok)
        XCTAssertTrue(result.errorMessage?.contains("stickerId") == true)
    }

    // MARK: - make（叠字）

    func testMakeWithTextOnly() async throws {
        let before = try temp.stores.stickers.count()
        let result = await run(["action": "make", "text": "醒醒", "position": "TOP"])

        XCTAssertTrue(result.ok, result.errorMessage ?? "")
        XCTAssertEqual(result.traceTitle, "做好了表情")
        XCTAssertTrue(result.contentForModel.contains("做好了（id="))
        XCTAssertTrue(result.contentForModel.contains("send"))
        XCTAssertEqual(try temp.stores.stickers.count(), before + 1)

        let made = try XCTUnwrap(try temp.stores.stickers.recent(limit: 1).first)
        XCTAssertEqual(made.source, StickerSource.modelMade.rawValue)
        XCTAssertEqual(made.createdBy, StickerCreator.model.rawValue)
        XCTAssertEqual(made.personaId, personaId)
        XCTAssertLessThanOrEqual(made.bytes, StickerLimits.maxBytes)
        XCTAssertTrue(storage.fileURL(for: made.file).map {
            FileManager.default.fileExists(atPath: $0.path)
        } ?? false)
    }

    // MARK: - make（叠图）

    func testMakeWithOverlaySticker() async throws {
        let base = try await service.importSticker(data: StickerTestMedia.staticPNG(width: 320, height: 320))
        let overlay = try await service.importSticker(data: StickerTestMedia.animatedGIF(width: 120, height: 120))
        let result = await run([
            "action": "make",
            "baseStickerId": base.id,
            "overlayStickerId": overlay.id,
            "position": "CENTER"
        ])
        XCTAssertTrue(result.ok, result.errorMessage ?? "")
        XCTAssertEqual(result.traceTitle, "做好了表情")
        XCTAssertEqual(try temp.stores.stickers.count(), 3)
        XCTAssertEqual(StubURLProtocol.recordedRequests.count, 0, "叠图不该发网络请求")
    }

    // MARK: - make（下载 URL）

    func testMakeFromURLUsesDownloadAndCompressionPipeline() async throws {
        StubURLProtocol.enqueue(StubURLProtocol.Stub(
            status: 200,
            headers: ["Content-Type": "image/gif"],
            chunks: [StickerTestMedia.animatedGIF(width: 600, height: 400, frameCount: 10)]
        ))
        let result = await run(["action": "make", "url": "https://example.com/meme.gif"])
        XCTAssertTrue(result.ok, result.errorMessage ?? "")
        let made = try XCTUnwrap(try temp.stores.stickers.recent(limit: 1).first)
        XCTAssertLessThanOrEqual(max(made.width, made.height), StickerLimits.maxLongEdge)
        XCTAssertLessThanOrEqual(made.frames, StickerLimits.maxFrames)
        XCTAssertEqual(StubURLProtocol.recordedRequests.count, 1)
    }

    // MARK: - save

    func testSaveCollectsFromMessageAndSecondTimeIsIdempotent() async throws {
        // 造一条「用户发来的普通 GIF」消息（附件不在表情目录里）→ 第一次收藏真的新增一行。
        let attachmentsDir = temp.directory.appendingPathComponent("attachments", isDirectory: true)
        try FileManager.default.createDirectory(at: attachmentsDir, withIntermediateDirectories: true)
        let sourceURL = attachmentsDir.appendingPathComponent("chat-dog.gif")
        try StickerTestMedia.animatedGIF(width: 300, height: 300, frameCount: 6).write(to: sourceURL)
        let attachment = MessageAttachment(
            uri: sourceURL.absoluteString,
            mime: "image/gif",
            width: 300,
            height: 300,
            kind: MessageAttachment.stickerKind
        )
        let message = MessageEntity(
            id: "msg-user-gif",
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

        let first = await run(["action": "save", "fromMessageId": message.id])
        XCTAssertTrue(first.ok, first.errorMessage ?? "")
        XCTAssertEqual(first.traceTitle, "已收藏表情")
        XCTAssertTrue(first.contentForModel.contains("已收藏（id="))
        XCTAssertEqual(try temp.stores.stickers.count(), 1)

        let second = await run(["action": "save", "fromMessageId": message.id])
        XCTAssertTrue(second.ok)
        XCTAssertEqual(second.traceTitle, "已经在收藏里了")
        XCTAssertEqual(try temp.stores.stickers.count(), 1)
    }

    func testSaveRejectsMessageWithoutSticker() async throws {
        let message = TestData.message(
            id: "plain", sessionId: sessionId, role: .assistant, content: "一句话", createdAt: now
        )
        try temp.stores.messages.upsert(message)
        let result = await run(["action": "save", "fromMessageId": "plain"])
        XCTAssertFalse(result.ok)
        XCTAssertTrue(result.errorMessage?.contains("没有表情") == true)
    }

    // MARK: - 协议与提示词

    func testStickerToolIsInCatalogAndCapabilityPrompt() {
        XCTAssertTrue(ToolCatalog.all.contains { $0.name == ToolCatalog.sticker })
        XCTAssertTrue(
            ToolCatalog.capabilityPrompt(webEnabled: true).contains("- sticker("),
            "「你能做什么」段必须自动包含 sticker"
        )
        let described = ToolProtocol.describe(ToolResult(
            invocation: ToolInvocation(id: "call_1", name: ToolCatalog.sticker, argumentsJson: "{}"),
            ok: true,
            summary: "表情",
            contentForModel: ""
        ))
        XCTAssertEqual(described.title, "表情")
    }

    // MARK: - 辅助

    private func run(
        _ arguments: [String: Any],
        persona: PersonaEntity? = nil,
        sessionId: String? = nil
    ) async -> ToolResult {
        let persona = persona ?? (try? temp.stores.personas.byId(personaId)) ?? TestData.persona(id: personaId)
        let data = try? JSONSerialization.data(withJSONObject: arguments, options: [.sortedKeys])
        let invocation = ToolInvocation(
            id: "call_test",
            name: ToolCatalog.sticker,
            argumentsJson: data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        )
        return await executor.run(
            invocation,
            persona: persona,
            sessionId: sessionId ?? self.sessionId
        )
    }
}
