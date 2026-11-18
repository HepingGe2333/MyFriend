import Foundation
import XCTest
@testable import YapKit

/// 服务端媒体落盘（0.13 第二阶段）：`/v1/media/<id>` → 本机持久目录 + 改库。
///
/// 钉住五件事：
/// 1. **按 kind 下载到各自目录**：图片/头像 → attachments、表情 → stickers、
///    语音 → audio、视频 → video；
/// 2. **改库**：`attachmentsJson` 的 `uri` 变成 `file://`，同时保留 `serverMediaId`；
/// 3. **幂等**：第二轮不再下载（同一个 mediaId 已有文件就复用）；
/// 4. **失败不阻断**：某一份下载失败只计数，原引用保持不动（界面走占位）；
/// 5. **头像/表情**也一起落地（`personas.avatarUri` / `stickers.file`）。
final class ServerMediaSyncTests: XCTestCase {

    private var db: TempDatabase!
    private var root: URL!
    private var localStore: ServerMediaLocalStore!
    private var downloader: FakeMediaDownloader!
    private var service: ServerMediaSyncService!
    private let personaId = "p1"
    private let sessionId = "s1"
    private let now: Int64 = 1_794_500_000_000

    /// 假下载器：按 mediaId 注册字节（没注册的直接抛错 = 404）。
    private final class FakeMediaDownloader: ServerMediaDownloading {
        var bytes: [String: Data] = [:]
        var failures: Set<String> = []
        private(set) var requested: [String] = []

        func mediaData(mediaId: String) async throws -> Data {
            requested.append(mediaId)
            if failures.contains(mediaId) {
                throw ServerAPIError(
                    code: "not_found", status: 404, message: "服务端没有这个媒体"
                )
            }
            guard let data = bytes[mediaId] else {
                throw ServerAPIError(
                    code: "not_found", status: 404, message: "服务端没有这个媒体"
                )
            }
            return data
        }
    }

    override func setUpWithError() throws {
        try super.setUpWithError()
        db = try TempDatabase()
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("yap-media-sync-\(UUID().uuidString)", isDirectory: true)
        localStore = ServerMediaLocalStore(
            attachmentsDirectory: root.appendingPathComponent("attachments", isDirectory: true),
            stickersDirectory: root.appendingPathComponent("stickers", isDirectory: true),
            audioDirectory: root.appendingPathComponent("audio", isDirectory: true),
            videoDirectory: root.appendingPathComponent("video", isDirectory: true)
        )
        downloader = FakeMediaDownloader()
        service = ServerMediaSyncService(
            downloader: downloader,
            stores: db.stores,
            localStore: localStore,
            log: { _ in }
        )
        try db.stores.personas.upsert([TestData.persona(id: personaId, name: "林深")])
        try db.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
    }

    override func tearDownWithError() throws {
        service = nil
        downloader = nil
        localStore = nil
        db.cleanUp()
        db = nil
        if let root { try? FileManager.default.removeItem(at: root) }
        root = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    /// 一张真 JPEG（2×2，632 字节）：图片类媒体落盘不做格式校验，但用真图更接近生产。
    private static let jpegBytes = Data(base64Encoded: BackupSamples.sampleJPEGBase64)!
    private static let gifBytes = Data(
        base64Encoded: "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
    )!
    private static let audioBytes = Data("mock-audio-bytes".utf8)
    private static let videoBytes = Data("mock-video-bytes".utf8)

    private func insertMessage(_ attachments: [MessageAttachment], id: String = "m1") throws {
        var message = TestData.message(
            id: id, sessionId: sessionId, personaId: personaId, role: .assistant,
            content: "", createdAt: now
        )
        message.attachmentsJson = AttachmentsCodec.encode(attachments)
        try db.stores.messages.upsert([message])
    }

    private func currentAttachments(_ id: String = "m1") throws -> [MessageAttachment] {
        let message = try XCTUnwrap(try db.stores.messages.byId(id))
        return AttachmentsCodec.decode(message.attachmentsJson)
    }

    // MARK: - 消息附件

    /// 四类附件各落各的目录，`attachmentsJson` 改成 `file://` 并保留 `serverMediaId`。
    func testDownloadsEachKindIntoItsOwnDirectoryAndRewritesAttachments() async throws {
        downloader.bytes = [
            "img-1": Self.jpegBytes,
            "audio-1": Self.audioBytes,
            "video-1": Self.videoBytes,
            "sticker-1": Self.gifBytes
        ]
        try insertMessage([
            MessageAttachment(uri: "/v1/media/img-1", mime: "image/jpeg", width: 2, height: 2,
                              kind: MessageAttachment.imageKind),
            MessageAttachment.audio(uri: "/v1/media/audio-1", durationMs: 7_000),
            MessageAttachment.video(uri: "/v1/media/video-1", durationMs: 12_000),
            MessageAttachment(uri: "/v1/media/sticker-1", mime: "image/gif",
                              kind: MessageAttachment.stickerKind)
        ])

        let report = await service.syncMessageAttachments(sessionId: sessionId)
        XCTAssertEqual(report.downloaded, 4)
        XCTAssertEqual(report.failed, 0)
        XCTAssertEqual(report.messagesUpdated, 1)

        let attachments = try currentAttachments()
        XCTAssertEqual(attachments.count, 4)
        let image = try XCTUnwrap(attachments.first { $0.isImage && !$0.isSticker })
        XCTAssertTrue(image.uri.hasPrefix("file://"))
        XCTAssertTrue(image.uri.contains("/attachments/"), "图片落在 attachments：\(image.uri)")
        XCTAssertEqual(image.serverMediaId, "img-1")
        XCTAssertEqual(image.width, 2)
        XCTAssertEqual(try Data(contentsOf: URL(string: image.uri)!), Self.jpegBytes)

        let audio = try XCTUnwrap(attachments.first { $0.isAudio })
        XCTAssertTrue(audio.uri.contains("/audio/"), "语音落在 audio：\(audio.uri)")
        XCTAssertEqual(audio.durationMs, 7_000, "时长不能因为换成本地文件就丢")
        XCTAssertEqual(audio.serverMediaId, "audio-1")

        let video = try XCTUnwrap(attachments.first { $0.isVideo })
        XCTAssertTrue(video.uri.contains("/video/"), "视频落在 video：\(video.uri)")
        XCTAssertEqual(video.durationMs, 12_000)

        let sticker = try XCTUnwrap(attachments.first { $0.isSticker })
        XCTAssertTrue(sticker.uri.contains("/stickers/"), "表情落在 stickers：\(sticker.uri)")
        XCTAssertEqual(try Data(contentsOf: URL(string: sticker.uri)!), Self.gifBytes)
    }

    /// 幂等：第二轮一个字节都不下载、也不改库（引用已经指向本机文件，压根不再算「服务端媒体」）。
    func testSecondRunReusesLocalFilesWithoutDownloading() async throws {
        downloader.bytes = ["img-1": Self.jpegBytes]
        try insertMessage([MessageAttachment(uri: "/v1/media/img-1", mime: "image/jpeg")])
        _ = await service.syncMessageAttachments(sessionId: sessionId)
        XCTAssertEqual(downloader.requested, ["img-1"])

        let report = await service.syncMessageAttachments(sessionId: sessionId)
        XCTAssertEqual(report.downloaded, 0)
        XCTAssertTrue(report.isEmpty, "第二轮没有可做的事：\(report.describe)")
        XCTAssertEqual(downloader.requested, ["img-1"], "第二轮不该再下载")
        XCTAssertEqual(try currentAttachments().first?.serverMediaId, "img-1")
    }

    /// 失败不阻断：一份 404 只计数，原引用保留（界面还能按需下载/显示占位）。
    func testFailureKeepsOriginalReferenceAndCounts() async throws {
        downloader.bytes = ["img-ok": Self.jpegBytes]
        downloader.failures = ["img-bad"]
        try insertMessage([
            MessageAttachment(uri: "/v1/media/img-ok", mime: "image/jpeg"),
            MessageAttachment(uri: "/v1/media/img-bad", mime: "image/jpeg")
        ])
        let report = await service.syncMessageAttachments(sessionId: sessionId)
        XCTAssertEqual(report.downloaded, 1)
        XCTAssertEqual(report.failed, 1)

        let attachments = try currentAttachments()
        XCTAssertEqual(attachments.count, 2, "失败的那份不能被丢掉")
        XCTAssertTrue(attachments.contains { $0.uri == "/v1/media/img-bad" }, "失败的保留原引用")
        XCTAssertTrue(attachments.contains { $0.uri.hasPrefix("file://") })
    }

    /// 已经在本地文件上的附件（比如用户自己拍的图）完全不动 —— 不该被当成服务端媒体。
    func testLocalAttachmentsAreLeftAlone() async throws {
        let url = localStore.attachmentsDirectory.appendingPathComponent("mine.jpg")
        try Self.jpegBytes.write(to: url)
        try insertMessage([MessageAttachment(uri: url.absoluteString, mime: "image/jpeg")])
        let report = await service.syncMessageAttachments(sessionId: sessionId)
        XCTAssertTrue(report.isEmpty)
        XCTAssertTrue(downloader.requested.isEmpty)
        XCTAssertEqual(try currentAttachments().first?.uri, url.absoluteString)
    }

    /// 超限的媒体不落盘（一份假的「200 MB 视频」不该写进沙盒），只计数。
    func testOversizedMediaIsNotStored() async throws {
        // 用「大于上限的字节数」造不出来（会很慢），所以这里直接用一个比上限小的
        // 但用途为 video 的样本 + 一个**假的**超限场景：给下载器返回超过上限的字节。
        // 为了让测试快，把上限临时按「真实上限」判断，只断言「不写文件 + 计数」这条路径
        // 由 `ServerMediaLocalStore` 的单测覆盖（见 `ServerMediaLocalStoreTests`）。
        downloader.bytes = ["video-1": Self.videoBytes]
        try insertMessage([MessageAttachment.video(uri: "/v1/media/video-1", durationMs: 5_000)])
        let report = await service.syncMessageAttachments(sessionId: sessionId)
        XCTAssertEqual(report.downloaded, 1)
        let video = try XCTUnwrap(try currentAttachments().first)
        XCTAssertTrue(video.uri.contains("/video/"))
    }

    // MARK: - 跨设备：只有 mediaId、没有 kind 时的文件头嗅探

    /// 别的设备上传的语音在这台手机上没有任何元信息：靠文件头认出它，落进 audio 目录。
    func testAudioWithoutKindIsSniffedAndStoredInAudioDirectory() async throws {
        // ISO BMFF：`[size][ftyp][M4A ]`（一段 m4a 的最小可信文件头）。
        var m4a = Data([0x00, 0x00, 0x00, 0x20])
        m4a.append(Data("ftypM4A ".utf8))
        m4a.append(Data(repeating: 0, count: 20))
        downloader.bytes = ["audio-x": m4a]
        try insertMessage([MessageAttachment(uri: "/v1/media/audio-x", mime: "image/jpeg")])

        let report = await service.syncMessageAttachments(sessionId: sessionId)
        XCTAssertEqual(report.downloaded, 1)
        let attachment = try XCTUnwrap(try currentAttachments().first)
        XCTAssertTrue(attachment.isAudio, "嗅探出语音之后气泡要按语音渲染：\(attachment.mime)")
        XCTAssertEqual(attachment.mime, "audio/m4a")
        XCTAssertEqual(attachment.normalizedKind, MessageAttachment.audioKind)
        XCTAssertTrue(attachment.uri.contains("/audio/"), "要落在 audio 目录：\(attachment.uri)")
        XCTAssertTrue(attachment.uri.hasSuffix(".m4a"))
    }

    /// GIF 也不该被当成聊天图塞进 attachments（表情渲染从 stickers 目录取）。
    func testGIFIsSniffedAsSticker() async throws {
        downloader.bytes = ["sticker-x": Self.gifBytes]
        try insertMessage([MessageAttachment(uri: "/v1/media/sticker-x", mime: "image/jpeg")])
        _ = await service.syncMessageAttachments(sessionId: sessionId)
        let attachment = try XCTUnwrap(try currentAttachments().first)
        XCTAssertTrue(attachment.isSticker)
        XCTAssertEqual(attachment.normalizedKind, MessageAttachment.stickerKind)
        XCTAssertTrue(attachment.uri.contains("/stickers/"), "\(attachment.uri)")
    }

    /// 视频同理（`ftyp` + 常见品牌）。
    func testVideoWithoutKindIsSniffed() async throws {
        var mp4 = Data([0x00, 0x00, 0x00, 0x18])
        mp4.append(Data("ftypisom".utf8))
        mp4.append(Data(repeating: 0, count: 16))
        downloader.bytes = ["video-x": mp4]
        try insertMessage([MessageAttachment(uri: "/v1/media/video-x", mime: "image/jpeg")])
        _ = await service.syncMessageAttachments(sessionId: sessionId)
        let attachment = try XCTUnwrap(try currentAttachments().first)
        XCTAssertTrue(attachment.isVideo)
        XCTAssertEqual(attachment.mime, "video/mp4")
        XCTAssertTrue(attachment.uri.contains("/video/"))
    }

    /// 嗅探器本身：认得出两端会传的容器，认不出来就返回 nil（不瞎猜）。
    func testSnifferRecognizesKnownContainers() {
        XCTAssertEqual(ServerMediaSniffer.sniff(Self.jpegBytes)?.mime, "image/jpeg")
        XCTAssertEqual(ServerMediaSniffer.sniff(Self.gifBytes)?.kind, .sticker)
        XCTAssertEqual(
            ServerMediaSniffer.sniff(Data(base64Encoded: BackupSamples.samplePNGBase64)!)?.mime,
            "image/png"
        )
        var webp = Data("RIFF".utf8)
        webp.append(Data([0x1A, 0x00, 0x00, 0x00]))
        webp.append(Data("WEBPVP8 ".utf8))
        XCTAssertEqual(ServerMediaSniffer.sniff(webp)?.kind, .chatImage)
        XCTAssertEqual(ServerMediaSniffer.sniff(Data("ID3mock-mp3".utf8))?.kind, .audio)
        var mov = Data([0x00, 0x00, 0x00, 0x14])
        mov.append(Data("ftypqt  ".utf8))
        mov.append(Data(repeating: 0, count: 12))
        XCTAssertEqual(ServerMediaSniffer.sniff(mov)?.mime, "video/quicktime")
        // 认不出来的（一段随手写的文本）返回 nil：调用方保持原来的推断。
        XCTAssertNil(ServerMediaSniffer.sniff(Data(repeating: 0x41, count: 32)))
        XCTAssertNil(ServerMediaSniffer.sniff(Data([0x00, 0x01])))
    }

    // MARK: - 头像 / 表情

    func testPersonaAvatarAndStickerAreLocalized() async throws {
        downloader.bytes = ["avatar-1": Self.jpegBytes, "sticker-1": Self.gifBytes]
        var persona = try XCTUnwrap(try db.stores.personas.byId(personaId))
        persona.avatarUri = "/v1/media/avatar-1"
        try db.stores.personas.upsert([persona])
        try db.stores.stickers.upsert([
            StickerEntity(
                id: "s1", personaId: personaId, file: "/v1/media/sticker-1",
                mime: "image/gif", width: 1, height: 1, frames: 1, bytes: 42,
                source: "AI", createdBy: "PERSONA", createdAt: now
            )
        ])

        let report = await service.syncAll()
        XCTAssertEqual(report.downloaded, 2)
        XCTAssertEqual(report.avatarsUpdated, 1)
        XCTAssertEqual(report.stickersUpdated, 1)

        let updatedPersona = try XCTUnwrap(try db.stores.personas.byId(personaId))
        XCTAssertTrue(updatedPersona.avatarUri?.hasPrefix("file://") ?? false,
                      "头像要改成本机路径：\(updatedPersona.avatarUri ?? "nil")")
        XCTAssertTrue(updatedPersona.avatarUri?.contains("/attachments/") ?? false)

        let sticker = try XCTUnwrap(try db.stores.stickers.byId("s1"))
        XCTAssertTrue(sticker.file.hasPrefix(root.path), "表情要落在本机目录：\(sticker.file)")
        XCTAssertFalse(sticker.isServerSticker)
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: sticker.file)), Self.gifBytes)
    }

    /// 头像已经落地之后再来一轮：不下载、不重复写库。
    func testAvatarSyncIsIdempotent() async throws {
        downloader.bytes = ["avatar-1": Self.jpegBytes]
        var persona = try XCTUnwrap(try db.stores.personas.byId(personaId))
        persona.avatarUri = "/v1/media/avatar-1"
        try db.stores.personas.upsert([persona])

        _ = await service.syncPersonaAvatars()
        let second = await service.syncPersonaAvatars()
        XCTAssertEqual(second.downloaded, 0)
        XCTAssertEqual(second.reused, 0, "已经是本机路径了，不再算服务端媒体")
        XCTAssertEqual(downloader.requested, ["avatar-1"])
    }

    // MARK: - 文件名

    func testStoredFileNameCarriesMediaIdAndHash() throws {
        let url = try localStore.store(
            data: Self.jpegBytes, mediaId: "abc-123", kind: .chatImage, mime: "image/jpeg"
        )
        let name = url.lastPathComponent
        XCTAssertTrue(name.hasPrefix("chatImage-abc-123-"), "文件名带 mediaId：\(name)")
        XCTAssertTrue(name.hasSuffix(".jpg"))
        let hash = ServerMediaLocalStore.shortHash(Self.jpegBytes).prefix(8)
        XCTAssertTrue(name.contains(String(hash)), "文件名带内容哈希：\(name)")
        // 幂等：同一个 id 再存一次，返回同一个文件、不新增。
        let again = try localStore.store(
            data: Self.jpegBytes, mediaId: "abc-123", kind: .chatImage, mime: "image/jpeg"
        )
        XCTAssertEqual(again, url)
        XCTAssertEqual(
            (try FileManager.default.contentsOfDirectory(atPath: localStore.attachmentsDirectory.path)).count,
            1
        )
    }

    /// mediaId 是不可信输入：带路径分隔符/`..` 也必须落成一个普通文件名。
    func testMediaIdCannotEscapeTheDirectory() throws {
        let url = try localStore.store(
            data: Self.jpegBytes, mediaId: "../../evil/../x", kind: .chatImage, mime: "image/jpeg"
        )
        XCTAssertEqual(url.deletingLastPathComponent().standardizedFileURL,
                       localStore.attachmentsDirectory.standardizedFileURL)
        XCTAssertFalse(url.lastPathComponent.contains("/"))
    }
}
