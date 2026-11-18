import Foundation
import XCTest
@testable import YapKit

/// 服务端模式的语音/视频：按 kind 上传（`/v1/media?kind=AUDIO|VIDEO`）、
/// 同步回来时附件 kind/时长不丢、以及超限在**上传之前**就拒绝。
final class ServerChatGatewayAudioVideoTests: XCTestCase {

    private var temp: TempDatabase!
    private var audioVideo: AudioVideoStore!
    private var mediaRoot: URL!

    private let personaId = "p1"
    private let sessionId = "s1"

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerURLProtocolStub.reset()
        temp = try TempDatabase()
        mediaRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("yap-av-gateway-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: mediaRoot, withIntermediateDirectories: true)
        audioVideo = AudioVideoStore(
            audioDirectory: mediaRoot.appendingPathComponent("audio", isDirectory: true),
            videoDirectory: mediaRoot.appendingPathComponent("video", isDirectory: true)
        )
        try temp.stores.personas.upsert(PersonaEntity(id: personaId, name: "林深"))
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: sessionId, personaId: personaId, title: "夜里散步", createdAt: 100, updatedAt: 200
        ))
    }

    override func tearDownWithError() throws {
        ServerURLProtocolStub.reset()
        try? FileManager.default.removeItem(at: mediaRoot)
        temp?.cleanUp()
        temp = nil
        audioVideo = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private final class FakeImageUploader: ServerMediaUploading {
        var uploads: [(bytes: Data, mime: String, kind: String, personaId: String?)] = []
        var nextId = "img-1"

        func uploadMedia(bytes: Data, mime: String, kind: String, personaId: String?) async throws -> ServerMedia {
            uploads.append((bytes, mime, kind, personaId))
            return ServerMedia(id: nextId, kind: kind, mime: mime, bytes: bytes.count)
        }
    }

    private final class FakeFileUploader: ServerMediaFileUploading {
        var uploads: [(bytes: Data, mime: String, kind: String, personaId: String?)] = []
        var nextId = "media-1"

        func uploadMediaFile(
            bytes: Data, mime: String, kind: String, personaId: String?
        ) async throws -> ServerMedia {
            uploads.append((bytes, mime, kind, personaId))
            return ServerMedia(id: nextId, kind: kind, mime: mime, bytes: bytes.count)
        }
    }

    private func gateway(
        imageUploader: FakeImageUploader = FakeImageUploader(),
        fileUploader: FakeFileUploader = FakeFileUploader()
    ) -> ServerChatGateway {
        ServerChatGateway(
            client: ServerTestSupport.client(),
            stores: temp.stores,
            mediaUploader: imageUploader,
            mediaFileUploader: fileUploader,
            audioVideo: audioVideo,
            now: { 1_800_000_000_000 },
            log: { _ in }
        )
    }

    private static func sseResponse() -> ServerURLProtocolStub.Response {
        let body = ServerTestSupport.sseFrame("accepted", #"{"userMessageId":"u1","createdAt":1700}"#)
            + ServerTestSupport.sseFrame("delta", #"{"text":"听到了"}"#)
            + ServerTestSupport.sseFrame("done", #"{"assistantMessageId":"a1"}"#)
        return .sse(body)
    }

    private func collect(_ stream: AsyncThrowingStream<ChatEvent, Error>) async -> (events: [ChatEvent], error: Error?) {
        var events: [ChatEvent] = []
        do {
            for try await event in stream { events.append(event) }
            return (events, nil)
        } catch {
            return (events, error)
        }
    }

    private func makeAudio(durationMs: Int = 12_000) throws -> MessageAttachment {
        try audioVideo.saveAudio(data: Data(repeating: 9, count: 1_024), durationMs: durationMs)
    }

    private func makeVideo(durationMs: Int = 30_000) throws -> MessageAttachment {
        let source = mediaRoot.appendingPathComponent("clip-\(UUID().uuidString).mp4")
        try Data(repeating: 3, count: 2_048).write(to: source)
        return try audioVideo.importVideo(at: source, durationMs: durationMs)
    }

    // MARK: - 上传

    func testAudioIsUploadedAsAUDIOAndKindSurvivesSyncBack() async throws {
        let audio = try makeAudio()
        let fileUploader = FakeFileUploader()
        fileUploader.nextId = "audio-media-1"
        let gateway = gateway(fileUploader: fileUploader)
        ServerURLProtocolStub.enqueue(Self.sseResponse())

        guard case .started(let stream) = await gateway.sendCheckedWithMedia(
            sessionId: sessionId, text: "听我说", attachments: [audio]
        ) else {
            return XCTFail("带语音的消息应当开始发送")
        }
        let result = await collect(stream)
        XCTAssertNil(result.error)

        // 上传：原始字节、正确 mime/kind、带上人格。
        XCTAssertEqual(fileUploader.uploads.count, 1)
        let upload = try XCTUnwrap(fileUploader.uploads.first)
        XCTAssertEqual(upload.kind, "AUDIO")
        XCTAssertEqual(upload.mime, "audio/m4a")
        XCTAssertEqual(upload.personaId, personaId)
        XCTAssertEqual(upload.bytes, audioVideo.data(for: audio))

        // 服务端回的真实消息里，附件仍然是「一段语音」（kind/时长来自上传时记下的元信息）。
        let stored = try XCTUnwrap(try temp.stores.messages.byId("u1"))
        let attachment = try XCTUnwrap(AttachmentsCodec.decode(stored.attachmentsJson).first)
        XCTAssertTrue(attachment.isAudio)
        XCTAssertEqual(attachment.uri, "/v1/media/audio-media-1")
        XCTAssertEqual(attachment.durationMs, 12_000)
        XCTAssertEqual(attachment.mime, "audio/m4a")
    }

    func testVideoIsUploadedAsVIDEO() async throws {
        let video = try makeVideo()
        let fileUploader = FakeFileUploader()
        fileUploader.nextId = "video-media-1"
        let gateway = gateway(fileUploader: fileUploader)
        ServerURLProtocolStub.enqueue(Self.sseResponse())

        guard case .started(let stream) = await gateway.sendCheckedWithMedia(
            sessionId: sessionId, text: "看看这个", attachments: [video]
        ) else {
            return XCTFail("带视频的消息应当开始发送")
        }
        _ = await collect(stream)

        let upload = try XCTUnwrap(fileUploader.uploads.first)
        XCTAssertEqual(upload.kind, "VIDEO")
        XCTAssertEqual(upload.mime, "video/mp4")
        let stored = try XCTUnwrap(try temp.stores.messages.byId("u1"))
        let attachment = try XCTUnwrap(AttachmentsCodec.decode(stored.attachmentsJson).first)
        XCTAssertTrue(attachment.isVideo)
        XCTAssertEqual(attachment.durationMs, 30_000)
    }

    func testImageStillGoesThroughCompressedUploader() async throws {
        let imageBytes = Data(repeating: 1, count: 512)
        let image = MessageAttachment(uri: "file:///tmp/chat.jpg", mime: "image/jpeg", kind: "IMAGE")
        let imageUploader = FakeImageUploader()
        let fileUploader = FakeFileUploader()
        let preparer = FakePreparer(bytes: imageBytes)
        let gateway = ServerChatGateway(
            client: ServerTestSupport.client(),
            stores: temp.stores,
            mediaUploader: imageUploader,
            mediaFileUploader: fileUploader,
            mediaPreparer: preparer,
            audioVideo: audioVideo,
            now: { 1_800_000_000_000 },
            log: { _ in }
        )
        ServerURLProtocolStub.enqueue(Self.sseResponse())
        guard case .started(let stream) = await gateway.sendCheckedWithMedia(
            sessionId: sessionId, text: "看图", attachments: [image]
        ) else {
            return XCTFail("带图消息应当开始发送")
        }
        _ = await collect(stream)
        XCTAssertEqual(imageUploader.uploads.count, 1)
        XCTAssertEqual(imageUploader.uploads.first?.kind, "CHAT_IMAGE")
        XCTAssertEqual(imageUploader.uploads.first?.bytes, imageBytes)
        XCTAssertTrue(fileUploader.uploads.isEmpty, "图片不该走任意媒体上传")
    }

    // MARK: - 拒绝

    func testOverlongVideoIsRejectedBeforeAnyUpload() async throws {
        var video = try makeVideo(durationMs: 60_000)
        video.durationMs = AudioVideoLimits.videoMaxDurationMs + 1
        let fileUploader = FakeFileUploader()
        let gateway = gateway(fileUploader: fileUploader)

        guard case .rejected(let error) = await gateway.sendCheckedWithMedia(
            sessionId: sessionId, text: "", attachments: [video]
        ) else {
            return XCTFail("超时长视频应当在入队之前被拒")
        }
        XCTAssertEqual((error as? ApiException)?.message, "视频最长 2 分钟")
        XCTAssertTrue(fileUploader.uploads.isEmpty, "注定被服务端拒绝的文件不该上传")
        XCTAssertTrue(gateway.pendingEntries(sessionId: sessionId).isEmpty)
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty)
    }

    /// 超过 64 MiB 的视频：**明确**告诉用户「视频超过 64 MB」，并且一个字节都不上传。
    ///
    /// 用稀疏文件造体积（`truncate`），不真的占 64 MiB 磁盘/内存：这条测的是
    /// 「先看文件大小再谈读字节」这个顺序，而不是传输本身。
    func testOversizedVideoIsRejectedByFileSizeBeforeReadingBytes() async throws {
        let oversized = mediaRoot.appendingPathComponent("huge-\(UUID().uuidString).mp4")
        XCTAssertTrue(FileManager.default.createFile(atPath: oversized.path, contents: nil))
        let handle = try FileHandle(forWritingTo: oversized)
        try handle.truncate(atOffset: UInt64(AudioVideoLimits.videoMaxBytes + 1))
        try handle.close()
        // 直接把「已经落在视频目录里」的附件交给发送链路（跳过 importVideo 的预校验，
        // 模拟「文件在选完之后被换成了超大文件」这条真实路径）。
        let videoDirectory = audioVideo.videoDirectory
        let target = videoDirectory.appendingPathComponent(oversized.lastPathComponent)
        try FileManager.default.moveItem(at: oversized, to: target)
        let attachment = MessageAttachment.video(
            uri: target.absoluteString, durationMs: 10_000
        )

        let fileUploader = FakeFileUploader()
        let gateway = gateway(fileUploader: fileUploader)
        guard case .rejected(let error) = await gateway.sendCheckedWithMedia(
            sessionId: sessionId, text: "", attachments: [attachment]
        ) else {
            return XCTFail("超大视频应当在入队之前被拒")
        }
        XCTAssertEqual((error as? ApiException)?.message, "视频超过 64 MB", "要说清楚是体积超了，而不是「读不出来」")
        XCTAssertTrue(fileUploader.uploads.isEmpty)
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty)
    }

    private final class FakePreparer: ServerChatMediaPreparing {
        let bytes: Data
        init(bytes: Data) { self.bytes = bytes }
        func prepare(attachment: MessageAttachment, kind: MediaKind) -> ServerMediaPreparation? {
            ServerMediaPreparation(bytes: bytes, mime: "image/jpeg", width: 100, height: 100)
        }
    }
}
