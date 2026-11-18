import Foundation
import XCTest
@testable import YapKit

/// 表情包 / 日记板的传输层契约（0.13）。
///
/// 钉住「客户端与服务端逐字对齐」：路径、query、请求体字段、响应解析、错误语义。
final class ServerCatalogAPITests: XCTestCase {

    override func setUp() {
        super.setUp()
        ServerURLProtocolStub.reset()
    }

    override func tearDown() {
        ServerURLProtocolStub.reset()
        super.tearDown()
    }

    // MARK: - 表情

    func testStickerListPathQueryAndDecoding() async throws {
        ServerURLProtocolStub.enqueue(.json(
            """
            {"serverTime":1794500000000,"stickers":[
              {"id":"stk_1","personaId":"p1","file":"stk_1.gif","mediaId":"stk_1",
               "url":"/v1/media/stk_1","mime":"image/gif","width":320,"height":320,
               "frames":12,"bytes":40123,"source":"IMPORTED","createdBy":"USER",
               "createdAt":1794500000000}
            ]}
            """
        ))
        let client = ServerTestSupport.client()

        let rows = try await client.stickerRows(limit: 100)

        XCTAssertEqual(rows.count, 1)
        let row = try XCTUnwrap(rows.first)
        XCTAssertEqual(row.id, "stk_1")
        XCTAssertEqual(row.mediaRef, "/v1/media/stk_1")
        XCTAssertEqual(row.localId, "stk_1")
        XCTAssertEqual(row.frames, 12)
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/v1/stickers")
        XCTAssertEqual(ServerTestSupport.queryItems(request)["limit"], "100")
    }

    func testStickerListWithPersonaFilter() async throws {
        ServerURLProtocolStub.enqueue(.json(#"{"stickers":[]}"#))
        let client = ServerTestSupport.client()

        _ = try await client.stickerRows(personaId: "p1", limit: 5)

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        let query = ServerTestSupport.queryItems(request)
        XCTAssertEqual(query["personaId"], "p1")
        XCTAssertEqual(query["limit"], "5")
    }

    func testStickerListLimitIsClampedToServerMaximum() async throws {
        ServerURLProtocolStub.enqueue(.json(#"{"stickers":[]}"#))
        let client = ServerTestSupport.client()

        _ = try await client.stickerRows(limit: 9999)

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(
            ServerTestSupport.queryItems(request)["limit"],
            String(ServerAPIClient.maxStickerListLimit)
        )
    }

    func testCreateStickerPostsMetadataAndDecodesRow() async throws {
        ServerURLProtocolStub.enqueue(.json(
            """
            {"created":true,"sticker":{"id":"stk_9","file":"stk_9.gif","url":"/v1/media/stk_9",
             "mime":"image/gif","width":10,"height":10,"frames":1,"bytes":3,
             "source":"IMPORTED","createdBy":"USER","createdAt":7}}
            """
        ))
        let client = ServerTestSupport.client()

        let row = try await client.createSticker([
            "mime": .string("image/gif"),
            "data": .string("R0lGODlh")
        ])

        XCTAssertEqual(row?.localId, "stk_9")
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/stickers")
        let body = try XCTUnwrap(ServerURLProtocolStub.recordedBodies.first)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(object["mime"] as? String, "image/gif")
        XCTAssertEqual(object["data"] as? String, "R0lGODlh")
    }

    func testCreateStickerWithoutStickerObjectReturnsNil() async throws {
        ServerURLProtocolStub.enqueue(.json(#"{"created":false}"#))
        let client = ServerTestSupport.client()
        let row = try await client.createSticker(["data": .string("AA==")])
        XCTAssertNil(row)
    }

    func testDeleteStickerUsesDeletePath() async throws {
        ServerURLProtocolStub.enqueue(.json(#"{"deleted":true,"id":"stk_1"}"#))
        let client = ServerTestSupport.client()

        try await client.deleteSticker(id: "stk_1")

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.url?.path, "/v1/stickers/stk_1")
    }

    func testDeleteStickerSurfacesNotFound() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"message":"没有这个表情","code":"not_found"}}"#, status: 404
        ))
        let client = ServerTestSupport.client()

        do {
            try await client.deleteSticker(id: "gone")
            XCTFail("404 必须抛出来（由服务层决定当不当「已经没了」）")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.status, 404)
            XCTAssertEqual(error.code, "not_found")
        }
    }

    func testStickerPathIdRejectsTraversal() async throws {
        let client = ServerTestSupport.client()
        do {
            try await client.deleteSticker(id: "../personas")
            XCTFail("路径穿越必须被拒绝")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.invalidArgument)
        }
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty, "被拒绝的 id 不该发请求")
    }

    // MARK: - 日记

    func testDiaryListPathQueryAndDecoding() async throws {
        ServerURLProtocolStub.enqueue(.json(
            """
            {"personaId":"p1","notes":[
              {"id":"n1","personaId":"p1","content":"周四给妈妈打电话","createdAt":10,
               "remindAt":99,"remindedAt":null,"updatedAt":10}
            ]}
            """
        ))
        let client = ServerTestSupport.client()

        let notes = try await client.diaryRows(personaId: "p1", limit: 20)

        XCTAssertEqual(notes.count, 1)
        XCTAssertEqual(notes.first?.id, "n1")
        XCTAssertEqual(notes.first?.remindAt, 99)
        XCTAssertNil(notes.first?.remindedAt)
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.url?.path, "/v1/personas/p1/diary")
        XCTAssertEqual(ServerTestSupport.queryItems(request)["limit"], "20")
    }

    func testSaveDiaryPostsContentAndDecodesNote() async throws {
        ServerURLProtocolStub.enqueue(.json(
            """
            {"note":{"id":"n1","personaId":"p1","content":"记得","createdAt":1,
             "remindAt":null,"remindedAt":null,"updatedAt":2}}
            """
        ))
        let client = ServerTestSupport.client()

        let note = try await client.saveDiary(personaId: "p1", body: ["content": .string("记得")])

        XCTAssertEqual(note?.id, "n1")
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/personas/p1/diary")
        let body = try XCTUnwrap(ServerURLProtocolStub.recordedBodies.first)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(object["content"] as? String, "记得")
    }

    func testDeleteDiaryUsesPersonaAndNotePath() async throws {
        ServerURLProtocolStub.enqueue(.json(#"{"deleted":true,"id":"n1"}"#))
        let client = ServerTestSupport.client()

        try await client.deleteDiary(personaId: "p1", noteId: "n1")

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.url?.path, "/v1/personas/p1/diary/n1")
    }

    // MARK: - 宽松解析

    func testStickerRowDecodesMissingFieldsWithDefaults() throws {
        let json = #"{"id":"s1","file":"s1.gif"}"#.data(using: .utf8)!
        let row = try JSONDecoder().decode(ServerStickerRow.self, from: json)
        XCTAssertEqual(row.id, "s1")
        XCTAssertEqual(row.mime, "image/gif")
        XCTAssertEqual(row.frames, 1)
        XCTAssertEqual(row.source, "IMPORTED")
        XCTAssertEqual(row.createdBy, "USER")
        XCTAssertEqual(row.mediaRef, "/v1/media/s1")
    }

    func testDiaryRowDecodesMissingFields() throws {
        let json = #"{"id":"n1","content":"x"}"#.data(using: .utf8)!
        let row = try JSONDecoder().decode(ServerDiaryRow.self, from: json)
        XCTAssertEqual(row.id, "n1")
        XCTAssertEqual(row.content, "x")
        XCTAssertNil(row.remindAt)
    }
}
