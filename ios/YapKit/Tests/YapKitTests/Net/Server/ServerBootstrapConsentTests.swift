import Foundation
import XCTest
@testable import YapKit

/// 切换云端时的 bootstrap（0.13）：同意并同步（媒体逐项上传 → 改写 → import）、
/// 只同步之后的新内容、取消不切换。
final class ServerBootstrapConsentTests: XCTestCase {

    private var temp: TempDatabase!
    private var server: BootstrapFakeServer!
    private var settings: SettingsStore!
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerRouteStub.reset()
        temp = try TempDatabase()
        server = BootstrapFakeServer()
        server.registerRoutes()
        suiteName = "server-bootstrap-consent-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        settings = SettingsStore(defaults: defaults)
    }

    override func tearDownWithError() throws {
        ServerRouteStub.reset()
        temp?.cleanUp()
        temp = nil
        if !suiteName.isEmpty { defaults?.removePersistentDomain(forName: suiteName) }
        defaults = nil
        settings = nil
        server = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private var baseURL: URL { URL(string: "https://server.example.com")! }

    private func client() -> ServerAPIClient {
        ServerAPIClient(
            config: ServerConfig(baseURL: baseURL, token: "secret-token"),
            session: ServerRouteStub.makeSession()
        )
    }

    private func makeService(
        mediaUploader: (any ServerHistoryMediaUploading)? = nil
    ) -> ServerBootstrapService {
        ServerBootstrapService(
            client: client(),
            stores: temp.stores,
            settings: settings,
            mediaUploader: mediaUploader,
            retry: .init(maxAttempts: 1, baseDelaySeconds: 0),
            now: { 1_800_000_000_000 },
            log: { _ in },
            sleeper: { _ in }
        )
    }

    private func realMediaUploader(
        preparer: any ServerHistoryMediaPreparing
    ) -> ServerHistoryMediaUploader {
        ServerHistoryMediaUploader(
            uploader: client(),
            stores: temp.stores,
            baseURL: baseURL,
            preparer: preparer,
            now: { 1_800_000_000_000 },
            log: { _ in }
        )
    }

    /// 本机：一个人格 + 一段会话 + 若干消息（第一条带附件）。
    private func seedLocal(attachments: [[MessageAttachment]]) throws {
        let personaId = "local-persona"
        let sessionId = "local-session"
        try temp.stores.personas.upsert(PersonaEntity(
            id: personaId, name: "林深", description: "慢热", createdAt: 1, updatedAt: 2
        ))
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: sessionId, personaId: personaId, title: "夜里散步", createdAt: 3, updatedAt: 4
        ))
        for (index, items) in attachments.enumerated() {
            try temp.stores.messages.upsert(MessageEntity(
                id: "local-\(index)",
                sessionId: sessionId,
                personaId: personaId,
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index + 1) 句",
                createdAt: 1_000 + Int64(index),
                attachmentsJson: AttachmentsCodec.encode(items)
            ))
        }
    }

    private func localAttachments(messageId: String) throws -> [MessageAttachment] {
        let message = try XCTUnwrap(try temp.stores.messages.byId(messageId))
        return AttachmentsCodec.decode(message.attachmentsJson)
    }

    private func recordedPaths() -> [String] {
        ServerRouteStub.recorded.map(\.path)
    }

    // MARK: - 同意并同步：媒体逐项上传 → 改写 → import

    func testFullConsentUploadsHistoryMediaThenImportsRewrittenReferences() async throws {
        try seedLocal(attachments: [
            [MessageAttachment(uri: "file:///tmp/chat-a.jpg", mime: "image/jpeg")],
            [],
            [MessageAttachment(uri: "file:///tmp/voice-a.m4a", mime: "audio/m4a")]
        ])
        server.validateAttachments = true
        let preparer = FixedHistoryMediaPreparer(payloads: [
            "file:///tmp/chat-a.jpg": ServerHistoryMediaPreparation(
                bytes: Data(repeating: 1, count: 120), mime: "image/jpeg",
                kind: ServerMediaFileKind.chatImage.rawValue
            ),
            "file:///tmp/voice-a.m4a": ServerHistoryMediaPreparation(
                bytes: Data(repeating: 2, count: 240), mime: "audio/m4a",
                kind: ServerMediaFileKind.audio.rawValue
            )
        ])
        let outcome = await makeService(mediaUploader: realMediaUploader(preparer: preparer))
            .bootstrap(force: true, consent: .full)
        guard case .completed(let report) = outcome else {
            return XCTFail("期望 completed，实际 \(outcome)")
        }
        XCTAssertEqual(report.mediaUploaded, 2)
        XCTAssertEqual(report.mediaReused, 0)
        XCTAssertEqual(report.mediaFailed, 0)
        XCTAssertEqual(report.historySkipped, false)
        XCTAssertEqual(report.consent, .full)
        XCTAssertEqual(report.messagesPushed, 3)

        // 服务端收到了两项媒体（图片 + 音频，kind 分开）。
        XCTAssertEqual(server.uploadedMedia.map { $0.kind }, ["CHAT_IMAGE", "AUDIO"])
        XCTAssertEqual(server.uploadedMedia.map { $0.bytes }, [120, 240])
        let imageURI = ServerMediaReference.uri(id: server.uploadedMedia[0].id)
        let audioURI = ServerMediaReference.uri(id: server.uploadedMedia[1].id)

        // import 的附件引用是 `/v1/media/<id>`（本地 file:// 不能硬推）。
        let imported = server.importRequests.flatMap { $0 }
        let withAttachments = imported.filter { !(($0["attachments"] as? [String]) ?? []).isEmpty }
        XCTAssertEqual(withAttachments.count, 2)
        XCTAssertEqual(withAttachments[0]["attachments"] as? [String], [imageURI])
        XCTAssertEqual(withAttachments[1]["attachments"] as? [String], [audioURI])

        // 服务端会话里也拿到了这两条引用（validateAttachments 已经确认 id 存在）。
        let serverSessionId = try XCTUnwrap(server.sessions.first?.id)
        let pushed = server.messages[serverSessionId] ?? []
        XCTAssertEqual(pushed.count, 3)
        XCTAssertEqual(pushed.filter { !$0.attachments.isEmpty }.count, 2)

        // 本地引用也改写成了服务端路径（重试/重复同步不会再传一遍）。
        XCTAssertEqual(try localAttachments(messageId: "local-0").first?.uri, imageURI)
        XCTAssertEqual(try localAttachments(messageId: "local-2").first?.uri, audioURI)

        // 顺序：所有 `POST /v1/media` 必须在任何 `/messages/import` 之前。
        let paths = recordedPaths()
        let lastMediaUpload = paths.lastIndex(of: "/v1/media") ?? -1
        let firstImport = paths.firstIndex { $0.hasSuffix("/messages/import") } ?? Int.max
        XCTAssertGreaterThan(lastMediaUpload, -1)
        XCTAssertLessThan(lastMediaUpload, firstImport, "必须先把媒体传上去，再 import 带引用的消息")
    }

    /// 媒体失败：整次同步仍然完成，失败项保留本地引用并计数；
    /// 服务端那一批消息不带这个附件（bootstrap 的既有兜底会去掉 unknown_media）。
    func testMediaFailureIsCountedButHistoryStillImported() async throws {
        try seedLocal(attachments: [
            [MessageAttachment(uri: "file:///tmp/gone.jpg", mime: "image/jpeg")],
            []
        ])
        let preparer = FixedHistoryMediaPreparer(payloads: [:])
        let outcome = await makeService(mediaUploader: realMediaUploader(preparer: preparer))
            .bootstrap(force: true, consent: .full)
        guard case .completed(let report) = outcome else {
            return XCTFail("媒体失败不应让整次同步失败，实际 \(outcome)")
        }
        XCTAssertEqual(report.mediaUploaded, 0)
        XCTAssertEqual(report.mediaFailed, 1)
        XCTAssertEqual(report.attachmentsDropped, 1)
        XCTAssertEqual(report.messagesPushed, 2)
        XCTAssertEqual(try localAttachments(messageId: "local-0").first?.uri, "file:///tmp/gone.jpg",
                       "失败项保留本地引用")
        let serverSessionId = try XCTUnwrap(server.sessions.first?.id)
        XCTAssertTrue((server.messages[serverSessionId] ?? []).allSatisfy { $0.attachments.isEmpty })
    }

    // MARK: - 只同步之后的新内容

    func testNewOnlyDoesNotPushHistoryOrMediaButPushesEntities() async throws {
        try seedLocal(attachments: [
            [MessageAttachment(uri: "file:///tmp/chat-a.jpg", mime: "image/jpeg")],
            [],
            [],
            [],
            []
        ])
        let media = RecordingHistoryMediaUploader()
        let outcome = await makeService(mediaUploader: media)
            .bootstrap(force: true, consent: .newOnly)
        guard case .completed(let report) = outcome else {
            return XCTFail("期望 completed，实际 \(outcome)")
        }
        XCTAssertTrue(report.historySkipped)
        XCTAssertEqual(report.messagesPushed, 0)
        XCTAssertEqual(report.consent, .newOnly)
        XCTAssertTrue(server.importRequests.isEmpty, "NEW_ONLY 一条历史消息都不推")
        XCTAssertTrue(server.sessions.isEmpty, "NEW_ONLY 连历史会话都不建")
        XCTAssertEqual(server.uploadedMedia.count, 0, "NEW_ONLY 不上传历史媒体")
        XCTAssertEqual(media.calls, 0, "NEW_ONLY 不该调用媒体上传器")
        // 元数据照旧搬：人格要在服务端存在（之后聊天才有归属）。
        XCTAssertEqual(server.personas.count, 1)
        XCTAssertEqual(try temp.stores.personas.count(), 1)

        let done = await makeService().doneRecord()
        // 完成标记按 URL 记录；用同一个 client 读出来（新实例读同一张表）。
        XCTAssertNotNil(done)
        XCTAssertEqual(done?.consent, "NEW_ONLY")
    }

    // MARK: - 取消

    func testCancelledMediaUploadLeavesHistoryUnpushed() async throws {
        try seedLocal(attachments: [
            [MessageAttachment(uri: "file:///tmp/chat-a.jpg", mime: "image/jpeg")],
            []
        ])
        let media = CancellingHistoryMediaUploader()
        let outcome = await makeService(mediaUploader: media)
            .bootstrap(force: true, consent: .full)
        XCTAssertEqual(outcome, .cancelled)
        XCTAssertTrue(server.importRequests.isEmpty, "取消后不该 import 任何历史")
        XCTAssertEqual(server.uploadedMedia.count, 0)
        XCTAssertEqual(try localAttachments(messageId: "local-0").first?.uri, "file:///tmp/chat-a.jpg")
    }
}

// MARK: - 假媒体上传器

/// 记录有没有被调用（NEW_ONLY 不该调用它）。
final class RecordingHistoryMediaUploader: ServerHistoryMediaUploading, @unchecked Sendable {
    private let lock = NSLock()
    private var recorded = 0

    var calls: Int {
        withLock { recorded }
    }

    func uploadAll() async throws -> ServerHistoryMediaReport {
        withLock { recorded += 1 }
        return ServerHistoryMediaReport()
    }

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}

/// 直接抛 CancellationError（模拟用户在进度条上取消）。
final class CancellingHistoryMediaUploader: ServerHistoryMediaUploading, @unchecked Sendable {
    func uploadAll() async throws -> ServerHistoryMediaReport {
        throw CancellationError()
    }
}
