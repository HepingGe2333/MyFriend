import Foundation
import XCTest
@testable import YapKit

/// 服务端表情 / 日记的「拉取缓存 + 写路径」编排（0.13）。
///
/// 用临时库 + 假 API：不联网，钉住「只同步」下的具体行为：
/// 1. 拉取写成本机缓存行；表情字节落盘成功时 file 指向本地，失败保留 `/v1/media/<id>`；
/// 2. 日记缺 personaId 时用本机人格兜底；空内容行丢掉；
/// 3. 新建/删除表情与日记先过服务端（服务端失败就失败，不能只写本机）；
/// 4. 不是服务端模式（provider 为 nil）时一个请求都不发。
final class ServerCatalogServiceTests: XCTestCase {

    private var db: TempDatabase!

    override func setUpWithError() throws {
        db = try TempDatabase()
        try db.stores.personas.upsert(TestData.persona())
    }

    override func tearDownWithError() throws {
        db.cleanUp()
        db = nil
    }

    // MARK: - 假 API

    private final class FakeCatalogAPI: ServerCatalogAPI {
        var stickerRowsToReturn: [ServerStickerRow] = []
        var diaryRowsToReturn: [ServerDiaryRow] = []
        var createdSticker: ServerStickerRow?
        var createdDiary: ServerDiaryRow?
        var media: [String: Data] = [:]
        var deletedStickers: [String] = []
        var deletedDiary: [(personaId: String, noteId: String)] = []
        var createStickerBodies: [[String: JSONValue]] = []
        var createDiaryBodies: [[String: JSONValue]] = []
        var listStickerCalls = 0
        var failNext = false
        var thrown: ServerAPIError?

        func stickerRows(personaId: String?, limit: Int) async throws -> [ServerStickerRow] {
            listStickerCalls += 1
            if let thrown { throw thrown }
            return stickerRowsToReturn
        }

        var uploadedStickerMedia: [(bytes: Int, personaId: String?)] = []
        var mediaIdToReturn = "media-uploaded"

        func createSticker(_ body: [String: JSONValue]) async throws -> ServerStickerRow? {
            createStickerBodies.append(body)
            if let thrown { throw thrown }
            return createdSticker
        }

        func uploadStickerMedia(data: Data, personaId: String?) async throws -> String {
            uploadedStickerMedia.append((data.count, personaId))
            if let thrown { throw thrown }
            return mediaIdToReturn
        }

        func deleteSticker(id: String) async throws {
            if let thrown { throw thrown }
            deletedStickers.append(id)
        }

        func diaryRows(personaId: String, limit: Int, since: Int64) async throws -> [ServerDiaryRow] {
            if let thrown { throw thrown }
            return diaryRowsToReturn
        }

        func saveDiary(personaId: String, body: [String: JSONValue]) async throws -> ServerDiaryRow? {
            createDiaryBodies.append(body)
            if let thrown { throw thrown }
            return createdDiary
        }

        func deleteDiary(personaId: String, noteId: String) async throws {
            if let thrown { throw thrown }
            deletedDiary.append((personaId, noteId))
        }

        func mediaData(mediaId: String) async throws -> Data {
            media[mediaId] ?? Data()
        }
    }

    private func makeService(
        api: FakeCatalogAPI?,
        mediaStore: ServerMediaLocalStore = .shared
    ) -> ServerCatalogService {
        ServerCatalogService(
            stores: db.stores,
            provider: { api },
            mediaStore: mediaStore
        )
    }

    // MARK: - 拉取

    func testSyncStickersWritesCacheAndKeepsServerRefWhenMediaMissing() async throws {
        let api = FakeCatalogAPI()
        api.stickerRowsToReturn = [
            ServerStickerRow(
                id: "stk_1", personaId: "p1", file: "stk_1.gif",
                mediaId: "stk_1", url: "/v1/media/stk_1",
                mime: "image/gif", width: 320, height: 320, frames: 3, bytes: 10,
                source: "IMPORTED", createdBy: "USER", createdAt: 11
            )
        ]
        // 不提供字节：必须保留服务端引用（界面仍能按需下载），而不是把这行丢掉。
        let written = await makeService(api: api).syncStickers()
        XCTAssertEqual(written, 1)
        let entity = try XCTUnwrap(db.stores.stickers.byId("stk_1"))
        XCTAssertEqual(entity.file, "/v1/media/stk_1")
        XCTAssertEqual(entity.frames, 3)
        XCTAssertEqual(entity.createdAt, 11)
    }

    func testSyncStickersStoresMediaLocallyWhenBytesAvailable() async throws {
        let api = FakeCatalogAPI()
        api.stickerRowsToReturn = [
            ServerStickerRow(
                id: "stk_2", file: "stk_2.gif", mediaId: "stk_2", url: "/v1/media/stk_2",
                mime: "image/gif", width: 8, height: 8, frames: 1, bytes: 6, createdAt: 5
            )
        ]
        let gifBytes = Data([0x47, 0x49, 0x46, 0x38, 0x39, 0x61])
        api.media = ["stk_2": gifBytes]

        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("catalog-media-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let mediaStore = ServerMediaLocalStore(
            attachmentsDirectory: directory,
            stickersDirectory: directory,
            audioDirectory: directory,
            videoDirectory: directory
        )

        let synced = await makeService(api: api, mediaStore: mediaStore).syncStickers()
        XCTAssertEqual(synced, 1)
        let entity = try XCTUnwrap(db.stores.stickers.byId("stk_2"))
        XCTAssertEqual(entity.file, mediaStore.existingFile(mediaId: "stk_2", kind: .sticker)?.path)
        XCTAssertFalse(entity.file.hasPrefix("/v1/media/"), "字节落盘后应指向本地文件")
    }

    func testSyncDiaryUsesFallbackPersonaAndSkipsEmptyRows() async throws {
        let api = FakeCatalogAPI()
        api.diaryRowsToReturn = [
            ServerDiaryRow(id: "n1", content: "周四给妈妈打电话", createdAt: 10, updatedAt: 10),
            ServerDiaryRow(id: "n2", content: "")
        ]
        let persona = try XCTUnwrap(db.stores.personas.all().first)

        let written = await makeService(api: api)
            .syncDiary(personaIds: [(localId: persona.id, serverId: "server-\(persona.id)")])

        XCTAssertEqual(written, 1)
        let note = try XCTUnwrap(db.stores.diaryNotes.byId("n1"))
        XCTAssertEqual(note.content, "周四给妈妈打电话")
        XCTAssertEqual(note.personaId, persona.id)
        XCTAssertNil(try db.stores.diaryNotes.byId("n2"))
    }

    func testSyncDoesNothingWhenServerModeIsOff() async throws {
        let api = FakeCatalogAPI()
        api.stickerRowsToReturn = [ServerStickerRow(id: "stk_1")]
        let stickersOffline = await makeService(api: nil).syncStickers()
        let diaryOffline = await makeService(api: nil).syncDiary(personaIds: [("p1", "p1")])
        XCTAssertEqual(stickersOffline, 0)
        XCTAssertEqual(diaryOffline, 0)
        XCTAssertTrue(api.deletedStickers.isEmpty)
    }

    func testSyncFailuresAreSwallowed() async throws {
        let api = FakeCatalogAPI()
        api.thrown = ServerAPIError(code: "server_error", status: 500, message: "炸了")
        let stickersFailed = await makeService(api: api).syncStickers()
        let diaryFailed = await makeService(api: api).syncDiary(personaIds: [("p1", "p1")])
        XCTAssertEqual(stickersFailed, 0)
        XCTAssertEqual(diaryFailed, 0)
    }

    // MARK: - 写路径

    func testCreateStickerSendsBase64AndReturnsServerRow() async throws {
        let api = FakeCatalogAPI()
        api.createdSticker = ServerStickerRow(
            id: "stk_new", mediaId: "stk_new", url: "/v1/media/stk_new", createdAt: 9
        )
        let service = makeService(api: api)

        let row = try await service.createSticker(
            data: Data([0x47, 0x49, 0x46]),
            width: 10, height: 10, frames: 1,
            source: "IMPORTED", createdBy: "USER", personaId: "p1"
        )

        XCTAssertEqual(row.localId, "stk_new")
        let body = try XCTUnwrap(api.createStickerBodies.first)
        XCTAssertEqual(body["data"]?.stringValue, Data([0x47, 0x49, 0x46]).base64EncodedString())
        XCTAssertEqual(body["personaId"]?.stringValue, "p1")
    }

    func testLargeStickerGoesThroughMediaUploadInsteadOfInlineBase64() async throws {
        let api = FakeCatalogAPI()
        api.createdSticker = ServerStickerRow(
            id: "stk_big", mediaId: "media-uploaded", url: "/v1/media/media-uploaded", createdAt: 1
        )
        let service = makeService(api: api)
        let big = Data(repeating: 0x47, count: ServerCatalogService.inlineBase64LimitBytes + 1)

        let row = try await service.createSticker(
            data: big, width: 320, height: 320, frames: 1,
            source: "IMPORTED", createdBy: "USER", personaId: "p1"
        )

        XCTAssertEqual(row.localId, "stk_big")
        XCTAssertEqual(api.uploadedStickerMedia.first?.bytes, big.count)
        let body = try XCTUnwrap(api.createStickerBodies.first)
        XCTAssertEqual(body["mediaId"]?.stringValue, "media-uploaded")
        XCTAssertNil(body["data"], "大表情不该走 inline base64")
    }

    func testSmallStickerUsesInlineBase64() async throws {
        let api = FakeCatalogAPI()
        api.createdSticker = ServerStickerRow(id: "stk_small", mediaId: "stk_small", createdAt: 1)
        let service = makeService(api: api)

        _ = try await service.createSticker(
            data: Data([0x47, 0x49]), width: 8, height: 8, frames: 1,
            source: "IMPORTED", createdBy: "USER", personaId: nil
        )

        XCTAssertTrue(api.uploadedStickerMedia.isEmpty)
        let body = try XCTUnwrap(api.createStickerBodies.first)
        XCTAssertNotNil(body["data"])
        XCTAssertNil(body["mediaId"])
    }

    func testCreateStickerFailsWhenServerReturnsNoRow() async throws {
        let api = FakeCatalogAPI()
        api.createdSticker = nil
        do {
            _ = try await makeService(api: api).createSticker(
                data: Data([1]), width: 1, height: 1, frames: 1,
                source: "IMPORTED", createdBy: "USER", personaId: nil
            )
            XCTFail("服务端没回行时不能假装成功")
        } catch {
            // 预期：调用方会把失败告诉用户。
        }
    }

    func testDeleteStickerTreats404AsAlreadyGone() async throws {
        let api = FakeCatalogAPI()
        api.thrown = ServerAPIError(code: "not_found", status: 404, message: "没有这个表情")
        // 404 视为已删（本机缓存也该消失）。
        try await makeService(api: api).deleteSticker(id: "stk_x")
    }

    func testDeleteDiaryCallsServer() async throws {
        let api = FakeCatalogAPI()
        try await makeService(api: api).deleteDiary(serverPersonaId: "server-p1", noteId: "n1")
        XCTAssertEqual(api.deletedDiary.first?.personaId, "server-p1")
        XCTAssertEqual(api.deletedDiary.first?.noteId, "n1")
    }

    func testSaveDiarySendsNullReminderWhenAbsent() async throws {
        let api = FakeCatalogAPI()
        api.createdDiary = ServerDiaryRow(id: "n1", personaId: "p1", content: "记得", createdAt: 1)
        let service = makeService(api: api)

        let row = try await service.saveDiary(
            personaId: "p1", serverPersonaId: "server-p1", content: "记得", remindAt: nil
        )

        XCTAssertEqual(row.id, "n1")
        XCTAssertEqual(api.createDiaryBodies.first?["remindAt"], JSONValue.null)
    }

    // MARK: - 纯映射

    func testDiaryEntityMappingKeepsExistingTimesWhenServerOmitsThem() {
        let existing = DiaryNoteEntity(
            id: "n1", personaId: "p-old", content: "旧", createdAt: 7, remindAt: nil, updatedAt: 8
        )
        let note = ServerCatalogService.diaryEntity(
            row: ServerDiaryRow(id: "n1", content: ""),
            existing: existing,
            fallbackPersonaId: "p9"
        )
        XCTAssertEqual(note?.personaId, "p-old")
        XCTAssertEqual(note?.content, "旧")
        XCTAssertEqual(note?.createdAt, 7)
        XCTAssertEqual(note?.updatedAt, 8)
    }

    func testStickerEntityMappingRejectsRowsWithoutIdOrFile() {
        XCTAssertNil(ServerCatalogService.stickerEntity(
            row: ServerStickerRow(id: "", file: ""), localFile: nil, now: 1
        ))
        XCTAssertNotNil(ServerCatalogService.stickerEntity(
            row: ServerStickerRow(id: "s1", file: "s1.gif"), localFile: nil, now: 1
        ))
    }
}

/// 表情服务在服务端模式下的写路径（0.13）。
///
/// 只钉一件事：**新建/删除先过服务端**。表情压缩、渲染、发送的行为在别处测，
/// 这里用假 catalog 把「服务端失败 → 本机也不能成功」这条规则单独钉住。
final class StickerServiceServerModeTests: XCTestCase {

    private var db: TempDatabase!

    override func setUpWithError() throws {
        db = try TempDatabase()
    }

    override func tearDownWithError() throws {
        db.cleanUp()
        db = nil
    }

    private final class FakeStickerCatalog: StickerServerCatalog {
        var createdRow: ServerStickerRow?
        var createError: Error?
        var deleteError: Error?
        var created: [(source: String, createdBy: String, personaId: String?)] = []
        var deleted: [String] = []

        func createSticker(
            data: Data, width: Int, height: Int, frames: Int,
            source: String, createdBy: String, personaId: String?
        ) async throws -> ServerStickerRow {
            if let createError { throw createError }
            created.append((source, createdBy, personaId))
            guard let createdRow else {
                throw ServerAPIError(code: "invalid_response", message: "没有行")
            }
            return createdRow
        }

        func deleteSticker(id: String) async throws {
            if let deleteError { throw deleteError }
            deleted.append(id)
        }
    }

    private func makeService(catalog: StickerServerCatalog) -> StickerService {
        StickerService(stores: db.stores, serverCatalog: catalog)
    }

    func testPersistCreatesServerRowAndLocalCache() async throws {
        let catalog = FakeStickerCatalog()
        catalog.createdRow = ServerStickerRow(
            id: "stk_new", personaId: "p1", file: "stk_new.gif",
            mediaId: "stk_new", url: "/v1/media/stk_new",
            mime: "image/gif", width: 16, height: 16, frames: 2, bytes: 4,
            source: "MODEL_MADE", createdBy: "MODEL", createdAt: 99
        )
        let service = makeService(catalog: catalog)

        let entity = try await service.persist(
            result: StickerGIFResult(data: Data([1, 2, 3, 4]), width: 16, height: 16, frames: 2, durationMillis: 100),
            source: .modelMade,
            personaId: "p1",
            createdBy: .model
        )

        XCTAssertEqual(entity.id, "stk_new")
        XCTAssertEqual(entity.file, "/v1/media/stk_new")
        XCTAssertEqual(entity.ownerUserId, nil)
        XCTAssertEqual(catalog.created.first?.source, "MODEL_MADE")
        XCTAssertEqual(try db.stores.stickers.byId("stk_new")?.id, "stk_new")
    }

    func testPersistFailsWhenServerFails() async throws {
        let catalog = FakeStickerCatalog()
        catalog.createError = ServerAPIError(code: "server_error", status: 500, message: "炸了")
        let service = makeService(catalog: catalog)

        do {
            _ = try await service.persist(
                result: StickerGIFResult(data: Data([1]), width: 8, height: 8, frames: 1, durationMillis: 0),
                source: .imported,
                personaId: nil,
                createdBy: .user
            )
            XCTFail("服务端失败时不能悄悄只写本机")
        } catch {
            XCTAssertTrue((try? db.stores.stickers.all())?.isEmpty ?? true)
        }
    }

    func testDeleteGoesThroughServerFirst() async throws {
        let catalog = FakeStickerCatalog()
        let service = makeService(catalog: catalog)
        try db.stores.stickers.upsert(StickerEntity(id: "stk_1", file: "/v1/media/stk_1"))

        let removed = try await service.delete(id: "stk_1")

        XCTAssertTrue(removed)
        XCTAssertEqual(catalog.deleted, ["stk_1"])
        XCTAssertNil(try db.stores.stickers.byId("stk_1"))
    }

    func testSaveFromMessageCreatesServerRowInServerMode() async throws {
        // 一条引用服务端媒体的表情消息：收藏必须先在服务端建行（否则下次同步就没了）。
        let catalog = FakeStickerCatalog()
        catalog.createdRow = ServerStickerRow(
            id: "stk_saved", mediaId: "stk_saved", url: "/v1/media/stk_saved", createdAt: 12
        )
        let persona = TestData.persona()
        try db.stores.personas.upsert(persona)
        let session = TestData.session(lastMessageAt: TestData.now)
        try db.stores.sessions.upsert(session)
        let attachment = MessageAttachment(
            uri: "/v1/media/stk_media", mime: "image/gif", width: 200, height: 200,
            kind: MessageAttachment.stickerKind
        )
        try db.stores.messages.upsert([
            MessageEntity(
                id: "msg-sticker", sessionId: session.id, personaId: persona.id,
                role: .user, content: "", createdAt: TestData.now,
                updatedAt: TestData.now, status: .complete,
                attachmentsJson: AttachmentsCodec.encode([attachment])
            )
        ])
        let service = StickerService(
            stores: db.stores,
            serverDownloader: { _ in StickerTestMedia.animatedGIF(frameCount: 2) },
            serverCatalog: catalog
        )

        let saved = try await service.save(fromMessageId: "msg-sticker")

        XCTAssertEqual(saved.sticker.id, "stk_saved")
        XCTAssertEqual(saved.sticker.file, "/v1/media/stk_saved")
        XCTAssertEqual(catalog.created.first?.source, "FROM_USER_MESSAGE")
        XCTAssertEqual(catalog.created.first?.personaId, persona.id)
    }

    func testDeleteFailsWhenServerFails() async throws {
        let catalog = FakeStickerCatalog()
        catalog.deleteError = ServerAPIError(code: "network_error", message: "断网")
        let service = makeService(catalog: catalog)
        try db.stores.stickers.upsert(StickerEntity(id: "stk_1", file: "/v1/media/stk_1"))

        do {
            _ = try await service.delete(id: "stk_1")
            XCTFail("服务端没删掉时不能删本机缓存")
        } catch {
            XCTAssertNotNil(try db.stores.stickers.byId("stk_1"))
        }
    }
}
