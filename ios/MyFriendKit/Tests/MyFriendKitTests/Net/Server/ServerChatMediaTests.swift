import Foundation
import XCTest
@testable import MyFriendKit

/// M5：服务端模式带图消息（压缩 → 上传 → 入队 → SSE）。
///
/// 用假 uploader / 假 preparer 钉顺序与参数：真实压缩路径
/// （`LocalChatMediaPreparer`）另有单测，这里关心的是「什么时候上传、上传了什么、
/// 失败之后库里有没有留下半个消息」。
final class ServerChatMediaTests: XCTestCase {

    private var temp: TempDatabase!

    /// 记录调用顺序的假上传器。
    private final class FakeUploader: ServerMediaUploading {
        enum Call: Equatable {
            case upload(bytes: Int, mime: String, kind: String, personaId: String?)
        }
        private(set) var calls: [Call] = []
        var failure: Error?
        var next: ServerMedia

        init(next: ServerMedia = ServerMedia(id: "m-1")) { self.next = next }

        func uploadMedia(
            bytes: Data, mime: String, kind: String, personaId: String?
        ) async throws -> ServerMedia {
            calls.append(.upload(bytes: bytes.count, mime: mime, kind: kind, personaId: personaId))
            if let failure { throw failure }
            return next
        }
    }

    /// 记录「准备（压缩）」的假实现；与 uploader 共用一个顺序数组。
    private final class FakePreparer: ServerChatMediaPreparing {
        private let order: OrderLog
        var bytes: Data
        var fail = false

        init(order: OrderLog, bytes: Data = Data(repeating: 7, count: 321)) {
            self.order = order
            self.bytes = bytes
        }

        func prepare(attachment: MessageAttachment, kind: MediaKind) -> ServerMediaPreparation? {
            order.append("prepare")
            if fail { return nil }
            return ServerMediaPreparation(bytes: bytes, mime: "image/jpeg", width: 640, height: 480)
        }
    }

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerURLProtocolStub.reset()
        temp = try TempDatabase()
        try temp.stores.personas.upsert(PersonaEntity(id: "p1", name: "林深"))
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: "s1", personaId: "p1", title: "夜里散步", createdAt: 100, updatedAt: 200
        ))
    }

    override func tearDownWithError() throws {
        ServerURLProtocolStub.reset()
        temp?.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func gateway(
        uploader: FakeUploader,
        preparer: FakePreparer
    ) -> ServerChatGateway {
        ServerChatGateway(
            client: ServerTestSupport.client(),
            stores: temp.stores,
            mediaUploader: uploader,
            mediaPreparer: preparer,
            now: { 1_800_000_000_000 },
            log: { _ in }
        )
    }

    private func localAttachment(name: String = "chat.jpg") -> MessageAttachment {
        MessageAttachment(uri: "file:///tmp/\(name)", mime: "image/jpeg", width: 800, height: 600)
    }

    private static func sseResponse(delay: TimeInterval = 0) -> ServerURLProtocolStub.Response {
        let body = ServerTestSupport.sseFrame(
            "accepted", #"{"userMessageId":"u1","createdAt":1700}"#
        )
            + ServerTestSupport.sseFrame("delta", #"{"text":"收到"}"#)
            + ServerTestSupport.sseFrame(
                "done", #"{"assistantMessageId":"a1","usage":{"promptTokens":1,"completionTokens":2}}"#
            )
        return .sse(body, delay: delay)
    }

    private func collect(_ stream: AsyncThrowingStream<ChatEvent, Error>) async -> Error? {
        do {
            for try await _ in stream {}
            return nil
        } catch {
            return error
        }
    }

    // MARK: - 顺序与形状

    func testCompressThenUploadThenQueueInOrder() async throws {
        // 延迟半秒再回 SSE：先检查「排队中」的本地气泡（否则 accepted 可能已经把它替换掉）。
        ServerURLProtocolStub.enqueue(Self.sseResponse(delay: 0.3))
        let order = OrderLog()
        let uploader = FakeUploader()
        // 让 uploader 也记一笔到同一个顺序表（用子类不方便，包一层闭包更直接）。
        let proxy = RecordingUploader(inner: uploader, order: order)
        let preparer = FakePreparer(order: order)
        let gateway = ServerChatGateway(
            client: ServerTestSupport.client(),
            stores: temp.stores,
            mediaUploader: proxy,
            mediaPreparer: preparer,
            now: { 1_800_000_000_000 },
            log: { _ in }
        )

        guard case .started(let stream) = await gateway.sendCheckedWithMedia(
            sessionId: "s1", text: "给你看张图", attachments: [localAttachment()]
        ) else {
            return XCTFail("应当开始发送")
        }

        XCTAssertEqual(order.all, ["prepare", "upload"], "必须先准备（压缩）再上传")
        XCTAssertEqual(uploader.calls, [
            .upload(bytes: 321, mime: "image/jpeg", kind: "CHAT_IMAGE", personaId: "p1")
        ])

        let entries = gateway.pendingEntries(sessionId: "s1")
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries.first?.attachments, ["m-1"], "附件只发 mediaId")
        XCTAssertEqual(entries.first?.content, "给你看张图")

        // 排队中的本地气泡已经带上 /v1/media/<id>，界面据此走服务端图片渲染。
        guard let pending = (try? temp.stores.messages.byId(
            ServerChatGateway.pendingIdPrefix + (entries.first?.clientMessageId ?? "")
        )) ?? nil else {
            return XCTFail("排队中的消息应当已经落库")
        }
        let attachmentsJson = try XCTUnwrap(pending.attachmentsJson)
        XCTAssertEqual(AttachmentsCodec.decode(attachmentsJson).first?.uri, "/v1/media/m-1")

        let error = await collect(stream)
        XCTAssertNil(error)
        // accepted 之后补回真实用户消息，附件仍然是服务端路径。
        guard let userMessage = (try? temp.stores.messages.byId("u1")) ?? nil else {
            return XCTFail("accepted 之后应当写入服务端那条用户消息")
        }
        XCTAssertEqual(
            AttachmentsCodec.decode(userMessage.attachmentsJson).first?.uri,
            "/v1/media/m-1"
        )
    }

    func testUploadFailureRejectsWithoutQueueingAnything() async throws {
        let order = OrderLog()
        let uploader = FakeUploader()
        uploader.failure = ServerAPIError(code: "content_too_large", status: 413, message: "文件太大")
        let proxy = RecordingUploader(inner: uploader, order: order)
        let preparer = FakePreparer(order: order)
        let gateway = ServerChatGateway(
            client: ServerTestSupport.client(),
            stores: temp.stores,
            mediaUploader: proxy,
            mediaPreparer: preparer,
            now: { 1_800_000_000_000 },
            log: { _ in }
        )

        let outcome = await gateway.sendCheckedWithMedia(
            sessionId: "s1", text: "发张图", attachments: [localAttachment()]
        )
        guard case .rejected(let error) = outcome else {
            return XCTFail("上传失败必须 rejected（草稿与图片还给界面）")
        }
        XCTAssertEqual((error as? ServerAPIError)?.code, "content_too_large")
        XCTAssertEqual(order.all, ["prepare", "upload"])
        XCTAssertTrue(gateway.pendingEntries(sessionId: "s1").isEmpty, "失败不该入队")
        XCTAssertTrue(((try? temp.stores.messages.count(sessionId: "s1")) ?? 0) == 0, "失败不该落库")
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty, "上传失败后不该再发消息请求")
    }

    func testPrepareFailureRejectsWithReadableMessage() async throws {
        let order = OrderLog()
        let uploader = FakeUploader()
        let proxy = RecordingUploader(inner: uploader, order: order)
        let preparer = FakePreparer(order: order)
        preparer.fail = true
        let gateway = ServerChatGateway(
            client: ServerTestSupport.client(),
            stores: temp.stores,
            mediaUploader: proxy,
            mediaPreparer: preparer,
            now: { 1_800_000_000_000 },
            log: { _ in }
        )
        let outcome = await gateway.sendCheckedWithMedia(
            sessionId: "s1", text: "发张图", attachments: [localAttachment()]
        )
        guard case .rejected(let error) = outcome else { return XCTFail("应当 rejected") }
        XCTAssertTrue(error.localizedDescription.contains("读不出来"))
        XCTAssertEqual(order.all, ["prepare"], "准备失败就不该上传")
        XCTAssertTrue(uploader.calls.isEmpty)
    }

    func testAlreadyUploadedServerMediaIsNotUploadedAgain() async throws {
        ServerURLProtocolStub.enqueue(Self.sseResponse())
        let order = OrderLog()
        let uploader = FakeUploader()
        let proxy = RecordingUploader(inner: uploader, order: order)
        let preparer = FakePreparer(order: order)
        let gateway = ServerChatGateway(
            client: ServerTestSupport.client(),
            stores: temp.stores,
            mediaUploader: proxy,
            mediaPreparer: preparer,
            now: { 1_800_000_000_000 },
            log: { _ in }
        )
        let existing = MessageAttachment(uri: "/v1/media/old-media")
        guard case .started(let stream) = await gateway.sendCheckedWithMedia(
            sessionId: "s1", text: "转给你", attachments: [existing]
        ) else { return XCTFail("应当开始发送") }
        XCTAssertEqual(order.all, [], "服务端媒体不需要再压缩/上传")
        XCTAssertEqual(gateway.pendingEntries(sessionId: "s1").first?.attachments, ["old-media"])
        _ = await collect(stream)
    }

    func testTextOnlyStillWorksThroughMediaEntrypoint() async throws {
        ServerURLProtocolStub.enqueue(Self.sseResponse())
        let gateway = gateway(uploader: FakeUploader(), preparer: FakePreparer(order: OrderLog()))
        guard case .started(let stream) = await gateway.sendCheckedWithMedia(
            sessionId: "s1", text: "只有文字", attachments: []
        ) else { return XCTFail("应当开始发送") }
        XCTAssertEqual(gateway.pendingEntries(sessionId: "s1").first?.attachments, [])
        _ = await collect(stream)
    }

    // MARK: - 同步映射

    func testServerAttachmentStringsMapToCanonicalURI() {
        let json = try? XCTUnwrap(ServerSyncMapping.attachmentsJson(
            from: ["/v1/media/m-1", "server:m-2", " ", "m-3"]
        ))
        let items = AttachmentsCodec.decode(json ?? nil)
        XCTAssertEqual(
            items.map(\.uri),
            ["/v1/media/m-1", "/v1/media/m-2", "/v1/media/m-3"],
            "两种服务端写法都归一到 /v1/media/<id>；空白项丢掉"
        )
        XCTAssertNil(ServerSyncMapping.attachmentsJson(from: []))
    }
}

/// 记录调用顺序（preparer 与 uploader 共用）。
private final class OrderLog {
    private let lock = NSLock()
    private var values: [String] = []
    func append(_ value: String) { lock.lock(); values.append(value); lock.unlock() }
    var all: [String] { lock.lock(); defer { lock.unlock() }; return values }
}

/// 把「上传」也记进同一个顺序表（假 uploader 自己不持有它）。
private final class RecordingUploader: ServerMediaUploading {
    private let inner: ServerMediaUploading
    private let order: OrderLog

    init(inner: ServerMediaUploading, order: OrderLog) {
        self.inner = inner
        self.order = order
    }

    func uploadMedia(
        bytes: Data, mime: String, kind: String, personaId: String?
    ) async throws -> ServerMedia {
        order.append("upload")
        return try await inner.uploadMedia(
            bytes: bytes, mime: mime, kind: kind, personaId: personaId
        )
    }
}
