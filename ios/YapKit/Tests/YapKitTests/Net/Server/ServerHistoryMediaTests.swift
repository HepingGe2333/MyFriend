import CoreGraphics
import Foundation
import ImageIO
import XCTest
@testable import YapKit

/// 历史媒体逐项上传（0.13「同意并同步」）：类型/上限、逐项上传、失败计数、
/// 取消回滚、sha256 去重。
///
/// 真 SQLite + 假上传器/假准备器：这一层要验的是**编排**（顺序、计数、改写、回滚），
/// 真正的压缩参数由 `MediaCompressionTests` 与下面的 `LocalServerHistoryMediaPreparer`
/// 用例守住。
final class ServerHistoryMediaTests: XCTestCase {

    private var temp: TempDatabase!

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
    }

    override func tearDownWithError() throws {
        temp?.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func seedSession(
        personaId: String = "p1",
        sessionId: String = "s1",
        messages: [(id: String, attachments: [MessageAttachment])]
    ) throws {
        try temp.stores.personas.upsert(PersonaEntity(id: personaId, name: "林深", createdAt: 1, updatedAt: 1))
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: sessionId, personaId: personaId, title: "夜里散步", createdAt: 1, updatedAt: 1
        ))
        for (index, message) in messages.enumerated() {
            try temp.stores.messages.upsert(MessageEntity(
                id: message.id,
                sessionId: sessionId,
                personaId: personaId,
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index + 1) 句",
                createdAt: 1_000 + Int64(index),
                attachmentsJson: AttachmentsCodec.encode(message.attachments)
            ))
        }
    }

    private func attachments(messageId: String) throws -> [MessageAttachment] {
        let message = try XCTUnwrap(try temp.stores.messages.byId(messageId))
        return AttachmentsCodec.decode(message.attachmentsJson)
    }

    private let baseURL = URL(string: "https://server.example.com")!

    private func makeUploader(
        uploader: any ServerMediaFileUploading,
        preparer: any ServerHistoryMediaPreparing,
        progress: (@Sendable (ServerHistoryMediaProgress) -> Void)? = nil
    ) -> ServerHistoryMediaUploader {
        ServerHistoryMediaUploader(
            uploader: uploader,
            stores: temp.stores,
            baseURL: baseURL,
            preparer: preparer,
            now: { 1_800_000_000_000 },
            log: { _ in },
            progress: progress
        )
    }

    // MARK: - 类型与上限（纯函数）

    func testPolicyClassifiesMediaAndEnforcesLimits() {
        XCTAssertEqual(ServerHistoryMediaPolicy.kind(forMime: "image/jpeg"), .image)
        XCTAssertEqual(ServerHistoryMediaPolicy.kind(forMime: "image/png"), .image)
        XCTAssertEqual(ServerHistoryMediaPolicy.kind(forMime: "image/gif"), .sticker)
        XCTAssertEqual(ServerHistoryMediaPolicy.kind(forMime: "audio/m4a"), .audio)
        XCTAssertEqual(ServerHistoryMediaPolicy.kind(forMime: "audio/mpeg"), .audio)
        XCTAssertEqual(ServerHistoryMediaPolicy.kind(forMime: "video/mp4"), .video)
        XCTAssertNil(ServerHistoryMediaPolicy.kind(forMime: "text/plain"))
        XCTAssertNil(ServerHistoryMediaPolicy.kind(forMime: ""))

        // 图片 ≤800KB、音频 ≤8MB、视频 ≤64MB（正好等于上限算合格）。
        XCTAssertTrue(ServerHistoryMediaPolicy.fits(
            byteCount: ServerHistoryMediaPolicy.maxImageBytes, kind: .image
        ))
        XCTAssertFalse(ServerHistoryMediaPolicy.fits(
            byteCount: ServerHistoryMediaPolicy.maxImageBytes + 1, kind: .image
        ))
        XCTAssertTrue(ServerHistoryMediaPolicy.fits(
            byteCount: ServerHistoryMediaPolicy.maxAudioBytes, kind: .audio
        ))
        XCTAssertFalse(ServerHistoryMediaPolicy.fits(
            byteCount: ServerHistoryMediaPolicy.maxAudioBytes + 1, kind: .audio
        ))
        XCTAssertTrue(ServerHistoryMediaPolicy.fits(
            byteCount: ServerHistoryMediaPolicy.maxVideoBytes, kind: .video
        ))
        XCTAssertFalse(ServerHistoryMediaPolicy.fits(
            byteCount: ServerHistoryMediaPolicy.maxVideoBytes + 1, kind: .video
        ))
        XCTAssertFalse(ServerHistoryMediaPolicy.fits(byteCount: 0, kind: .image))

        // 服务端用途与服务端上限一致。
        XCTAssertEqual(ServerHistoryMediaKind.audio.serverKind, .audio)
        XCTAssertEqual(ServerHistoryMediaKind.image.serverKind, .chatImage)
        XCTAssertEqual(ServerHistoryMediaKind.sticker.serverKind, .sticker)
        XCTAssertEqual(ServerMediaFileKind.audio.maxBytes, 8 * 1024 * 1024)
        XCTAssertEqual(ServerMediaFileKind.video.maxBytes, 64 * 1024 * 1024)
        XCTAssertTrue(ServerMediaFileKind.sticker.allowedMimes.contains("image/gif"))
        XCTAssertTrue(ServerMediaFileKind.audio.allowedMimes.contains("audio/m4a"))
        XCTAssertTrue(ServerMediaFileKind.video.allowedMimes.contains("video/mp4"))
        XCTAssertFalse(ServerMediaFileKind.chatImage.allowedMimes.contains("audio/mpeg"))
    }

    func testLocalPreparerCompressesImageKeepsSmallGIFAndRejectsOversizedAudio() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("mf-history-media-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let storage = MediaStorage(directory: directory)
        let preparer = LocalServerHistoryMediaPreparer(storage: storage)

        // 1) 大图：压成 JPEG 且 ≤800KB。
        let big = try makeJPEG(width: 2_400, height: 1_600)
        let bigURL = directory.appendingPathComponent("chat-big.jpg")
        try big.write(to: bigURL)
        let image = try XCTUnwrap(preparer.prepare(
            attachment: MessageAttachment(uri: bigURL.absoluteString, mime: "image/jpeg")
        ))
        XCTAssertEqual(image.mime, "image/jpeg")
        XCTAssertEqual(image.kind, ServerMediaFileKind.chatImage.rawValue)
        XCTAssertTrue(ImageThumbnailDecoder.isJPEG(data: image.bytes))
        XCTAssertLessThanOrEqual(image.bytes.count, ServerHistoryMediaPolicy.maxImageBytes)

        // 2) 小 GIF 表情：原样保留（不把动画压成一张静图），kind=STICKER。
        let gifURL = directory.appendingPathComponent("sticker-small.gif")
        try Data(repeating: 7, count: 4_096).write(to: gifURL)
        let gif = try XCTUnwrap(preparer.prepare(
            attachment: MessageAttachment(uri: gifURL.absoluteString, mime: "image/gif")
        ))
        XCTAssertEqual(gif.kind, ServerMediaFileKind.sticker.rawValue)
        XCTAssertEqual(gif.mime, "image/gif")
        XCTAssertEqual(gif.bytes.count, 4_096)

        // 3) 显式 kind=STICKER 的 JPEG（表情被重压后的形状）：仍按表情传（kind=STICKER）。
        let stickerJPEG = try makeJPEG(width: 240, height: 240)
        let stickerURL = directory.appendingPathComponent("sticker-kind.jpg")
        try stickerJPEG.write(to: stickerURL)
        let sticker = try XCTUnwrap(preparer.prepare(attachment: MessageAttachment(
            uri: stickerURL.absoluteString,
            mime: "image/jpeg",
            kind: MessageAttachment.stickerKind
        )))
        XCTAssertEqual(sticker.kind, ServerMediaFileKind.sticker.rawValue)

        // 4) 超过 8MB 的音频：不传（返回 nil → 算失败并保留本地）。
        let audioURL = directory.appendingPathComponent("voice-big.m4a")
        try Data(count: ServerHistoryMediaPolicy.maxAudioBytes + 1).write(to: audioURL)
        XCTAssertNil(preparer.prepare(
            attachment: MessageAttachment(uri: audioURL.absoluteString, mime: "audio/m4a")
        ))

        // 5) 文件不在：nil。
        XCTAssertNil(preparer.prepare(
            attachment: MessageAttachment(
                uri: directory.appendingPathComponent("missing.jpg").absoluteString
            )
        ))
    }

    // MARK: - 逐项上传 + 改写

    func testUploadsEachItemRewritesAttachmentsAndReportsProgress() async throws {
        try seedSession(messages: [
            ("msg-image", [MessageAttachment(uri: "file:///tmp/chat-a.jpg", mime: "image/jpeg")]),
            ("msg-audio", [MessageAttachment(uri: "file:///tmp/voice-a.m4a", mime: "audio/m4a")])
        ])
        let uploader = FakeHistoryMediaUploader()
        let preparer = FixedHistoryMediaPreparer(payloads: [
            "file:///tmp/chat-a.jpg": ServerHistoryMediaPreparation(
                bytes: Data(repeating: 1, count: 100), mime: "image/jpeg",
                kind: ServerMediaFileKind.chatImage.rawValue
            ),
            "file:///tmp/voice-a.m4a": ServerHistoryMediaPreparation(
                bytes: Data(repeating: 2, count: 200), mime: "audio/m4a",
                kind: ServerMediaFileKind.audio.rawValue
            )
        ])
        let box = HistoryProgressBox()
        let report = try await makeUploader(
            uploader: uploader,
            preparer: preparer,
            progress: { box.append($0) }
        ).uploadAll()

        XCTAssertEqual(report.total, 2)
        XCTAssertEqual(report.uploaded, 2)
        XCTAssertEqual(report.failed, 0)
        XCTAssertEqual(report.messagesRewritten, 2)
        XCTAssertTrue(report.failures.isEmpty)
        XCTAssertEqual(uploader.calls.map(\.kind), ["CHAT_IMAGE", "AUDIO"])
        XCTAssertEqual(uploader.calls.map(\.mime), ["image/jpeg", "audio/m4a"])
        XCTAssertEqual(uploader.calls.map(\.bytes.count), [100, 200])

        // 本地消息的附件引用被改写为 `/v1/media/<id>`（bootstrap 的 import 只认这个）。
        let imageRefs = try attachments(messageId: "msg-image")
        XCTAssertEqual(imageRefs.first?.uri, "/v1/media/fake-1")
        let audioRefs = try attachments(messageId: "msg-audio")
        XCTAssertEqual(audioRefs.first?.uri, "/v1/media/fake-2")
        XCTAssertEqual(audioRefs.first?.mime, "audio/m4a", "改写引用不该丢掉原来的 mime")

        // 逐项进度：从 0 到 2。
        XCTAssertEqual(box.all.first?.total, 2)
        XCTAssertEqual(box.all.last?.completed, 2)
    }

    func testFailureIsCountedAndLocalReferenceIsKept() async throws {
        try seedSession(messages: [
            ("msg-missing", [MessageAttachment(uri: "file:///tmp/gone.jpg")]),
            ("msg-ok", [MessageAttachment(uri: "file:///tmp/ok.jpg")])
        ])
        let uploader = FakeHistoryMediaUploader()
        let preparer = FixedHistoryMediaPreparer(payloads: [
            "file:///tmp/ok.jpg": ServerHistoryMediaPreparation(
                bytes: Data(repeating: 3, count: 50), mime: "image/jpeg",
                kind: ServerMediaFileKind.chatImage.rawValue
            )
        ])
        let report = try await makeUploader(uploader: uploader, preparer: preparer).uploadAll()

        XCTAssertEqual(report.total, 2)
        XCTAssertEqual(report.uploaded, 1)
        XCTAssertEqual(report.failed, 1)
        XCTAssertEqual(report.failures.first?.messageId, "msg-missing")
        XCTAssertEqual(try attachments(messageId: "msg-missing").first?.uri, "file:///tmp/gone.jpg",
                       "失败项必须保留本地引用，不能变成坏引用")
        XCTAssertEqual(try attachments(messageId: "msg-ok").first?.uri, "/v1/media/fake-1")
        XCTAssertEqual(uploader.calls.count, 1)
    }

    func testServerRejectionIsCountedWithoutStoppingOtherItems() async throws {
        try seedSession(messages: [
            ("msg-a", [MessageAttachment(uri: "file:///tmp/a.jpg")]),
            ("msg-b", [MessageAttachment(uri: "file:///tmp/b.jpg")])
        ])
        let uploader = FakeHistoryMediaUploader()
        uploader.failuresRemaining = 1
        let preparer = FixedHistoryMediaPreparer(payloads: [
            "file:///tmp/a.jpg": ServerHistoryMediaPreparation(
                bytes: Data(repeating: 4, count: 10), mime: "image/jpeg",
                kind: ServerMediaFileKind.chatImage.rawValue
            ),
            "file:///tmp/b.jpg": ServerHistoryMediaPreparation(
                bytes: Data(repeating: 5, count: 10), mime: "image/jpeg",
                kind: ServerMediaFileKind.chatImage.rawValue
            )
        ])
        let report = try await makeUploader(uploader: uploader, preparer: preparer).uploadAll()

        XCTAssertEqual(report.uploaded, 1)
        XCTAssertEqual(report.failed, 1)
        XCTAssertEqual(uploader.calls.count, 2, "一项失败不应中断后面的项")
        XCTAssertEqual(try attachments(messageId: "msg-a").first?.uri, "file:///tmp/a.jpg")
        XCTAssertEqual(try attachments(messageId: "msg-b").first?.uri, "/v1/media/fake-2")
    }

    // MARK: - sha256 去重 / 重试

    func testRetryReusesSha256MappingAndDoesNotUploadTwice() async throws {
        try seedSession(messages: [
            ("msg-1", [MessageAttachment(uri: "file:///tmp/same.jpg")])
        ])
        let payload = Data(repeating: 9, count: 64)
        let preparer = FixedHistoryMediaPreparer(payloads: [
            "file:///tmp/same.jpg": ServerHistoryMediaPreparation(
                bytes: payload, mime: "image/jpeg",
                kind: ServerMediaFileKind.chatImage.rawValue
            )
        ])
        let uploader = FakeHistoryMediaUploader()
        let state = ServerSyncStateStore(database: temp.stores.database)
        // 模拟「上次已经上传成功、但本机引用还没改写就被杀掉」：映射已经在簿记里。
        let sha = ImageContentHash.sha256Hex(payload)
        try state.set(
            "/v1/media/known-id",
            for: ServerSyncStateStore.Key.mediaSha256(baseURL.absoluteString, sha),
            at: 1
        )

        let report = try await makeUploader(uploader: uploader, preparer: preparer).uploadAll()
        XCTAssertEqual(report.uploaded, 0, "sha256 命中时不该再传一次")
        XCTAssertEqual(report.reused, 1)
        XCTAssertTrue(uploader.calls.isEmpty)
        XCTAssertEqual(try attachments(messageId: "msg-1").first?.uri, "/v1/media/known-id")

        // 再跑一次：已经改写成服务端引用 → 直接复用，不再上传。
        let second = try await makeUploader(uploader: uploader, preparer: preparer).uploadAll()
        XCTAssertEqual(second.uploaded, 0)
        XCTAssertEqual(second.reused, 1)
        XCTAssertTrue(uploader.calls.isEmpty)
    }

    func testAlreadyServerMediaIsReusedWithoutUpload() async throws {
        try seedSession(messages: [
            ("msg-server", [MessageAttachment(uri: "/v1/media/already-there")])
        ])
        let uploader = FakeHistoryMediaUploader()
        let preparer = FixedHistoryMediaPreparer(payloads: [:])
        let report = try await makeUploader(uploader: uploader, preparer: preparer).uploadAll()
        XCTAssertEqual(report.reused, 1)
        XCTAssertEqual(report.uploaded, 0)
        XCTAssertTrue(uploader.calls.isEmpty)
        XCTAssertEqual(try attachments(messageId: "msg-server").first?.uri, "/v1/media/already-there")
    }

    // MARK: - 取消

    func testCancellationBeforeFirstItemThrowsAndLeavesLocalReferences() async throws {
        try seedSession(messages: [
            ("msg-1", [MessageAttachment(uri: "file:///tmp/a.jpg")])
        ])
        let uploader = FakeHistoryMediaUploader()
        let preparer = FixedHistoryMediaPreparer(payloads: [
            "file:///tmp/a.jpg": ServerHistoryMediaPreparation(
                bytes: Data(repeating: 1, count: 10), mime: "image/jpeg",
                kind: ServerMediaFileKind.chatImage.rawValue
            )
        ])
        let media = makeUploader(uploader: uploader, preparer: preparer)
        let task = Task { try await media.uploadAll() }
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("取消应当抛 CancellationError")
        } catch is CancellationError {
            // 期望
        } catch {
            XCTFail("应当抛 CancellationError，实际 \(error)")
        }
        XCTAssertEqual(try attachments(messageId: "msg-1").first?.uri, "file:///tmp/a.jpg",
                       "取消后本地引用必须还原")
        XCTAssertTrue(uploader.calls.isEmpty)
    }

    func testCancellationMidRunRollsBackEarlierRewritesButKeepsUploadedMedia() async throws {
        try seedSession(messages: [
            ("msg-1", [MessageAttachment(uri: "file:///tmp/a.jpg")]),
            ("msg-2", [MessageAttachment(uri: "file:///tmp/b.jpg")])
        ])
        let preparer = FixedHistoryMediaPreparer(payloads: [
            "file:///tmp/a.jpg": ServerHistoryMediaPreparation(
                bytes: Data(repeating: 1, count: 10), mime: "image/jpeg",
                kind: ServerMediaFileKind.chatImage.rawValue
            ),
            "file:///tmp/b.jpg": ServerHistoryMediaPreparation(
                bytes: Data(repeating: 2, count: 10), mime: "image/jpeg",
                kind: ServerMediaFileKind.chatImage.rawValue
            )
        ])
        let uploader = GatedHistoryMediaUploader(gateOnCall: 2)
        let media = makeUploader(uploader: uploader, preparer: preparer)
        let task = Task { try await media.uploadAll() }
        try await waitUntil { uploader.isWaiting }

        task.cancel()
        uploader.releaseGate()
        do {
            _ = try await task.value
            XCTFail("取消应当抛 CancellationError")
        } catch is CancellationError {
            // 期望
        } catch {
            XCTFail("应当抛 CancellationError，实际 \(error)")
        }

        XCTAssertEqual(uploader.callCount, 2)
        XCTAssertEqual(try attachments(messageId: "msg-1").first?.uri, "file:///tmp/a.jpg",
                       "第一项已经上传成功，但取消后本机引用要还原（否则本地模式显示坏图）")
        XCTAssertEqual(try attachments(messageId: "msg-2").first?.uri, "file:///tmp/b.jpg")
    }

    // MARK: - 夹具实现

    private func waitUntil(
        timeout: TimeInterval = 3,
        _ condition: () -> Bool
    ) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline { return XCTFail("等待超时") }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
    }

    /// 生成一张真的 JPEG（压缩器需要能被 ImageIO 解码）。
    private func makeJPEG(width: Int, height: Int) throws -> Data {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let context = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: colorSpace, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.setFillColor(CGColor(red: 0.4, green: 0.5, blue: 0.6, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try XCTUnwrap(context.makeImage())
        let data = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(
            data as CFMutableData, "public.jpeg" as CFString, 1, nil
        ))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }
}

// MARK: - 假准备器 / 假上传器

struct FixedHistoryMediaPreparer: ServerHistoryMediaPreparing {
    var payloads: [String: ServerHistoryMediaPreparation]

    func prepare(attachment: MessageAttachment) -> ServerHistoryMediaPreparation? {
        payloads[attachment.uri]
    }
}

final class HistoryProgressBox: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [ServerHistoryMediaProgress] = []

    func append(_ progress: ServerHistoryMediaProgress) {
        withLock { items.append(progress) }
    }

    var all: [ServerHistoryMediaProgress] {
        withLock { items }
    }

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}

/// 记录每次上传；可选让前 `failuresRemaining` 次直接失败。
final class FakeHistoryMediaUploader: ServerMediaFileUploading, @unchecked Sendable {
    struct Call: Equatable {
        var bytes: Data
        var mime: String
        var kind: String
        var personaId: String?
    }

    private let lock = NSLock()
    private var recorded: [Call] = []
    private var nextIndex = 0
    /// 让前几次上传失败（模拟服务端 413/500）。
    var failuresRemaining = 0

    var calls: [Call] {
        withLock { recorded }
    }

    func uploadMediaFile(
        bytes: Data,
        mime: String,
        kind: String,
        personaId: String?
    ) async throws -> ServerMedia {
        let (index, shouldFail) = withLock { () -> (Int, Bool) in
            recorded.append(Call(bytes: bytes, mime: mime, kind: kind, personaId: personaId))
            nextIndex += 1
            let shouldFail = failuresRemaining > 0
            if shouldFail { failuresRemaining -= 1 }
            return (nextIndex, shouldFail)
        }
        if shouldFail {
            throw ServerAPIError(code: "content_too_large", status: 413, message: "服务端拒收")
        }
        return ServerMedia(id: "fake-\(index)", kind: kind, mime: mime, bytes: bytes.count)
    }

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}

/// 第 `gateOnCall` 次上传时挂起，直到测试放行（用来测「取消时回滚已改写的引用」）。
final class GatedHistoryMediaUploader: ServerMediaFileUploading, @unchecked Sendable {
    private let lock = NSLock()
    private let gateOnCall: Int
    private var calls = 0
    private var continuation: CheckedContinuation<Void, Never>?
    private var waiting = false

    init(gateOnCall: Int) {
        self.gateOnCall = gateOnCall
    }

    var callCount: Int {
        withLock { calls }
    }

    var isWaiting: Bool {
        withLock { waiting && continuation != nil }
    }

    func releaseGate() {
        let pending = withLock { () -> CheckedContinuation<Void, Never>? in
            let pending = continuation
            continuation = nil
            waiting = false
            return pending
        }
        pending?.resume()
    }

    func uploadMediaFile(
        bytes: Data,
        mime: String,
        kind: String,
        personaId: String?
    ) async throws -> ServerMedia {
        let (index, needsGate) = withLock { () -> (Int, Bool) in
            calls += 1
            return (calls, calls == gateOnCall)
        }
        if needsGate {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                withLock {
                    self.continuation = continuation
                    waiting = true
                }
            }
        }
        try Task.checkCancellation()
        return ServerMedia(id: "gated-\(index)", kind: kind, mime: mime, bytes: bytes.count)
    }

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
