import Foundation
import XCTest
@testable import YapKit

/// 0.15：服务端媒体下载的 sha256 校验与本地媒体哈希台账。
///
/// 钉住五件事：
/// 1. **一致**：下载字节的 sha256 与服务端声明一致 → 落盘、记哈希、改写库里的引用；
/// 2. **不一致**：临时文件删掉、目录里不留文件、库里不改、只计一次失败；
/// 3. **旧数据没有哈希**：跳过校验照常落盘（0.14 及更早的行为不变）；
/// 4. **幂等**：同一个 mediaId 已有文件就复用，不重复下载；
/// 5. **本地文件坏掉**：哈希台账发现「文件内容与记录不符」→ 删掉重下。
final class ServerMediaHashTests: XCTestCase {

    private var db: TempDatabase!
    private var root: URL!
    private var localStore: ServerMediaLocalStore!
    private var downloader: FakeHashDownloader!
    private var service: ServerMediaSyncService!
    private var hashStore: ServerMediaLocalHashStore!
    private let personaId = "p1"
    private let sessionId = "s1"
    private let now: Int64 = 1_794_500_000_000

    /// 假下载器：能返回「字节 + 声明哈希」，用来模拟服务端 `/v1/media/<id>` 的 ETag。
    private final class FakeHashDownloader: ServerMediaDownloading {
        var payloads: [String: ServerMediaDownload] = [:]
        var failures: Set<String> = []
        private(set) var requested: [String] = []

        func mediaData(mediaId: String) async throws -> Data {
            try await mediaDownload(mediaId: mediaId).data
        }

        func mediaDownload(mediaId: String) async throws -> ServerMediaDownload {
            requested.append(mediaId)
            if failures.contains(mediaId) {
                throw ServerAPIError(code: "not_found", status: 404, message: "服务端没有这个媒体")
            }
            guard let payload = payloads[mediaId] else {
                throw ServerAPIError(code: "not_found", status: 404, message: "服务端没有这个媒体")
            }
            return payload
        }
    }

    override func setUpWithError() throws {
        try super.setUpWithError()
        db = try TempDatabase()
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("yap-media-hash-\(UUID().uuidString)", isDirectory: true)
        localStore = ServerMediaLocalStore(
            attachmentsDirectory: root.appendingPathComponent("attachments", isDirectory: true),
            stickersDirectory: root.appendingPathComponent("stickers", isDirectory: true),
            audioDirectory: root.appendingPathComponent("audio", isDirectory: true),
            videoDirectory: root.appendingPathComponent("video", isDirectory: true)
        )
        downloader = FakeHashDownloader()
        hashStore = ServerMediaLocalHashStore(database: db.stores.database, now: { self.now })
        service = ServerMediaSyncService(
            downloader: downloader,
            stores: db.stores,
            localStore: localStore,
            hashStore: hashStore,
            log: { _ in }
        )
        try db.stores.personas.upsert([TestData.persona(id: personaId, name: "林深")])
        try db.stores.sessions.upsert([TestData.session(id: sessionId, personaId: personaId)])
    }

    override func tearDownWithError() throws {
        service = nil
        downloader = nil
        hashStore = nil
        localStore = nil
        db.cleanUp()
        db = nil
        if let root { try? FileManager.default.removeItem(at: root) }
        root = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func seedMessage(mediaIds: [String]) throws {
        let attachments = mediaIds.enumerated().map { index, id in
            MessageAttachment(
                uri: ServerMediaReference.uri(id: id),
                mime: "image/jpeg",
                width: 2, height: 2,
                kind: index == 0 ? "IMAGE" : "IMAGE",
                serverMediaId: id
            )
        }
        var message = TestData.message(id: "m1", role: .user, content: "看图", createdAt: now - 1_000)
        message.attachmentsJson = AttachmentsCodec.encode(attachments)
        try db.stores.messages.upsert([message])
    }

    private func files(in directory: URL) -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []).sorted()
    }

    private func storedBytes(in directory: URL) -> Data? {
        let names = files(in: directory)
        guard let first = names.first else { return nil }
        return try? Data(contentsOf: directory.appendingPathComponent(first))
    }

    private static let imageBytes = Data("fake-jpeg-bytes-0.15".utf8)

    private func payload(sha256: String?, bytes: Data = imageBytes) -> ServerMediaDownload {
        ServerMediaDownload(data: bytes, sha256: sha256, mime: "image/jpeg")
    }

    // MARK: - 哈希工具本身

    func testHashNormalization() {
        let hex = ImageContentHash.sha256Hex(Self.imageBytes)
        XCTAssertEqual(hex.count, 64)
        XCTAssertEqual(ServerMediaHash.normalized(hex.uppercased()), hex)
        XCTAssertEqual(ServerMediaHash.normalized("\"\(hex)\""), hex)
        XCTAssertEqual(ServerMediaHash.normalized("W/\"\(hex)\""), hex)
        XCTAssertNil(ServerMediaHash.normalized("not-a-hash"))
        XCTAssertNil(ServerMediaHash.normalized(""))
        XCTAssertTrue(ServerMediaHash.matches(hex, hex.uppercased()))
        XCTAssertFalse(ServerMediaHash.matches(hex, nil))
    }

    // MARK: - 一致

    func testMatchingShaStoresFileRecordsHashAndRewritesMessage() async throws {
        let mediaId = "p1-m1"
        try seedMessage(mediaIds: [mediaId])
        let bytes = Self.imageBytes
        let hash = ImageContentHash.sha256Hex(bytes)
        downloader.payloads[mediaId] = payload(sha256: hash, bytes: bytes)

        let report = await service.syncMessageAttachments()
        XCTAssertEqual(report.downloaded, 1)
        XCTAssertEqual(report.failed, 0)
        XCTAssertEqual(report.messagesUpdated, 1)

        let stored = try XCTUnwrap(storedBytes(in: localStore.attachmentsDirectory))
        XCTAssertEqual(stored, bytes)
        XCTAssertEqual(hashStore.recorded(mediaId: mediaId), hash)
        // 没有残留的临时文件。
        XCTAssertFalse(files(in: localStore.attachmentsDirectory).contains { $0.contains(".tmp-") })

        let message = try XCTUnwrap(db.stores.messages.byId("m1"))
        let attachment = try XCTUnwrap(AttachmentsCodec.decode(message.attachmentsJson).first)
        XCTAssertTrue(attachment.uri.hasPrefix("file://"), "落盘之后引用要指向本地文件：\(attachment.uri)")
        XCTAssertEqual(attachment.serverMediaId, mediaId, "服务端媒体 id 要保留")
        XCTAssertTrue(attachment.isKindValid, "落盘后的附件 kind/mime 必须仍然合法")
    }

    // MARK: - 不一致

    func testMismatchedShaWritesNothingAndKeepsDatabase() async throws {
        let mediaId = "p1-m2"
        try seedMessage(mediaIds: [mediaId])
        let wrong = ImageContentHash.sha256Hex(Data("something-else".utf8))
        downloader.payloads[mediaId] = payload(sha256: wrong)

        let report = await service.syncMessageAttachments()
        XCTAssertEqual(report.failed, 1)
        XCTAssertEqual(report.downloaded, 0)
        XCTAssertEqual(report.messagesUpdated, 0)

        // 目录里没有任何文件（临时文件也被删掉了）。
        XCTAssertTrue(files(in: localStore.attachmentsDirectory).isEmpty,
                      "校验失败不该落任何文件：\(files(in: localStore.attachmentsDirectory))")
        XCTAssertNil(hashStore.recorded(mediaId: mediaId), "校验失败不记哈希")

        // 库里保持原样：引用仍然是服务端媒体（下次同步还会重试）。
        let message = try XCTUnwrap(db.stores.messages.byId("m1"))
        let attachment = try XCTUnwrap(AttachmentsCodec.decode(message.attachmentsJson).first)
        XCTAssertEqual(attachment.uri, ServerMediaReference.uri(id: mediaId))
    }

    /// 直接落盘层也遵守「不一致 → 删临时文件、不落目标」。
    func testStoreVerifiedCleansTemporaryFileOnMismatch() throws {
        let wrong = ImageContentHash.sha256Hex(Data("other".utf8))
        XCTAssertThrowsError(
            try localStore.storeVerified(
                data: Self.imageBytes,
                mediaId: "direct-1",
                kind: .chatImage,
                mime: "image/jpeg",
                expectedSha256: wrong
            )
        ) { error in
            guard case ServerMediaLocalError.hashMismatch = error else {
                return XCTFail("应当是 hashMismatch，实际：\(error)")
            }
        }
        XCTAssertTrue(files(in: localStore.attachmentsDirectory).isEmpty)
    }

    // MARK: - 旧数据没有哈希

    func testLegacyDownloadWithoutShaIsAccepted() async throws {
        let mediaId = "p1-m3"
        try seedMessage(mediaIds: [mediaId])
        // 假下载器只实现 mediaData（没有 ETag）：等价于老服务端/旧数据。
        downloader.payloads[mediaId] = ServerMediaDownload(data: Self.imageBytes, sha256: nil)

        let report = await service.syncMessageAttachments()
        XCTAssertEqual(report.downloaded, 1)
        XCTAssertEqual(report.failed, 0)
        XCTAssertNotNil(storedBytes(in: localStore.attachmentsDirectory))
        // 没有声明哈希时，本机仍然记下**实际**哈希（下次复用可以复核）。
        XCTAssertEqual(
            hashStore.recorded(mediaId: mediaId),
            ImageContentHash.sha256Hex(Self.imageBytes)
        )
    }

    // MARK: - 幂等 / 本地文件坏掉

    func testSecondSyncDoesNotDownloadAgain() async throws {
        let mediaId = "p1-m4"
        try seedMessage(mediaIds: [mediaId])
        let hash = ImageContentHash.sha256Hex(Self.imageBytes)
        downloader.payloads[mediaId] = payload(sha256: hash)

        let first = await service.syncMessageAttachments()
        XCTAssertEqual(first.downloaded, 1)
        XCTAssertEqual(downloader.requested, [mediaId])

        // 第一轮之后引用已经是本机文件，第二轮连「服务端媒体」都不算，什么都不用做。
        let second = await service.syncMessageAttachments()
        XCTAssertEqual(second.downloaded, 0)
        XCTAssertTrue(second.isEmpty, "第二轮没有可做的事：\(second.describe)")
        XCTAssertEqual(downloader.requested, [mediaId], "第二次不该再下载")
    }

    /// 落盘层自己的幂等：同一个 mediaId + 同内容直接复用；内容与声明哈希对不上则删掉重写。
    func testStoreVerifiedIsIdempotentAndRepairsChangedContent() throws {
        let hash = ImageContentHash.sha256Hex(Self.imageBytes)
        let first = try localStore.storeVerified(
            data: Self.imageBytes, mediaId: "idem-1", kind: .chatImage,
            mime: "image/jpeg", expectedSha256: hash
        )
        XCTAssertFalse(first.reused)
        XCTAssertEqual(first.sha256, hash)

        let second = try localStore.storeVerified(
            data: Self.imageBytes, mediaId: "idem-1", kind: .chatImage,
            mime: "image/jpeg", expectedSha256: hash
        )
        XCTAssertTrue(second.reused, "同 id + 同内容应当复用")
        XCTAssertEqual(second.url, first.url)

        // 同一个 id、传入的字节却变了（理论上服务端内容寻址不该发生）：旧的删掉、写新的。
        let newBytes = Data("new-content".utf8)
        let newHash = ImageContentHash.sha256Hex(newBytes)
        let third = try localStore.storeVerified(
            data: newBytes, mediaId: "idem-1", kind: .chatImage,
            mime: "image/jpeg", expectedSha256: newHash
        )
        XCTAssertFalse(third.reused)
        XCTAssertEqual(try Data(contentsOf: third.url), newBytes)
    }

    func testCorruptedLocalFileIsDeletedAndDownloadedAgain() async throws {
        let mediaId = "p1-m5"
        try seedMessage(mediaIds: [mediaId])
        let hash = ImageContentHash.sha256Hex(Self.imageBytes)
        downloader.payloads[mediaId] = payload(sha256: hash)

        _ = await service.syncMessageAttachments()
        let names = files(in: localStore.attachmentsDirectory)
        let file = try XCTUnwrap(names.first)
        // 模拟「文件被改坏 / 截断」。
        try Data("corrupted".utf8).write(
            to: localStore.attachmentsDirectory.appendingPathComponent(file)
        )
        // 服务端又下发了一次这条消息（引用重新变回 `/v1/media/<id>`）。
        try seedMessage(mediaIds: [mediaId])

        let second = await service.syncMessageAttachments()
        XCTAssertEqual(second.downloaded, 1, "本地文件与哈希台账不符时要重新下载")
        XCTAssertEqual(second.reused, 0)
        XCTAssertEqual(downloader.requested, [mediaId, mediaId])
        XCTAssertEqual(storedBytes(in: localStore.attachmentsDirectory), Self.imageBytes)
        XCTAssertEqual(hashStore.recorded(mediaId: mediaId), hash)
    }

    // MARK: - 头像 / 表情也走同一套校验

    func testAvatarWithMismatchedShaIsNotWrittenAndUriStaysServer() async throws {
        var persona = TestData.persona(id: personaId, name: "林深")
        persona.avatarUri = ServerMediaReference.uri(id: "avatar-1")
        try db.stores.personas.upsert([persona])
        let wrong = ImageContentHash.sha256Hex(Data("wrong-avatar".utf8))
        downloader.payloads["avatar-1"] = payload(sha256: wrong)

        let report = await service.syncPersonaAvatars()
        XCTAssertEqual(report.failed, 1)
        XCTAssertEqual(report.avatarsUpdated, 0)
        XCTAssertTrue(files(in: localStore.attachmentsDirectory).isEmpty)
        let reloaded = try XCTUnwrap(db.stores.personas.byId(personaId))
        XCTAssertEqual(reloaded.avatarUri, ServerMediaReference.uri(id: "avatar-1"))
    }

    func testStickerWithMatchingShaIsStoredAndLinked() async throws {
        let gifBytes = Data(base64Encoded: "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")!
        let hash = ImageContentHash.sha256Hex(gifBytes)
        downloader.payloads["st-1"] = ServerMediaDownload(data: gifBytes, sha256: hash, mime: "image/gif")
        try db.stores.stickers.upsert([
            StickerEntity(
                id: "sticker-1", ownerUserId: nil, personaId: personaId,
                file: ServerMediaReference.uri(id: "st-1"), mime: "image/gif",
                width: 1, height: 1, frames: 1, bytes: gifBytes.count,
                source: "IMPORTED", createdBy: "USER", createdAt: now - 1_000
            )
        ])

        let report = await service.syncStickers()
        XCTAssertEqual(report.downloaded, 1)
        XCTAssertEqual(report.stickersUpdated, 1)
        let sticker = try XCTUnwrap(db.stores.stickers.byId("sticker-1"))
        XCTAssertFalse(sticker.file.contains("/v1/media/"), "落盘后应指向本地文件：\(sticker.file)")
        XCTAssertEqual(hashStore.recorded(mediaId: "st-1"), hash)
    }
}
