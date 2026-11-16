import Foundation
import XCTest
@testable import YapKit

/// 批量历史消息导入（`POST /v1/sessions/{id}/messages/import`，0.12.1 契约）。
///
/// 契约里最容易被写错的三件事：body 是**对象** `{"messages":[…]}`、每批 **≤200 条**、
/// **`id` 幂等且原样保留**。这里逐条钉住，另外验证响应解码足够宽容（少一个键不该把一次
/// 成功的导入判成失败）。
final class ServerMessageImportTests: XCTestCase {

    private var server: BootstrapFakeServer!

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerRouteStub.reset()
        server = BootstrapFakeServer()
        server.seedPersona(id: "p1", name: "林深")
        server.seedSession(id: "s1", personaId: "p1")
        server.registerRoutes()
    }

    override func tearDownWithError() throws {
        ServerRouteStub.reset()
        server = nil
        try super.tearDownWithError()
    }

    private func client() -> ServerAPIClient {
        ServerAPIClient(
            config: ServerConfig(
                baseURL: URL(string: "https://server.example.com")!,
                token: "secret-token"
            ),
            session: ServerRouteStub.makeSession()
        )
    }

    private func message(
        _ id: String,
        role: String = "USER",
        content: String = "你好",
        createdAt: Int64 = 1_000,
        readAt: Int64? = nil,
        attachments: [String] = []
    ) -> ServerImportMessage {
        ServerImportMessage(
            id: id, role: role, content: content, createdAt: createdAt,
            readAt: readAt, attachments: attachments
        )
    }

    // MARK: - 分批

    func testBatchesSplitByCount() {
        let messages = (0..<401).map { message("m\($0)") }
        let batches = ServerAPIClient.importBatches(for: messages)
        XCTAssertEqual(batches.map(\.count), [200, 200, 1])
        XCTAssertEqual(batches.flatMap { $0 }.map(\.id), messages.map(\.id), "顺序不能变")
    }

    func testBatchesHandleEmptyAndExactBoundary() {
        XCTAssertTrue(ServerAPIClient.importBatches(for: []).isEmpty)
        XCTAssertEqual(
            ServerAPIClient.importBatches(for: (0..<200).map { message("m\($0)") }).map(\.count),
            [200]
        )
    }

    func testBatchesClampToContractLimit() {
        // 调用方传一个更大的 maxCount 也不能越过 200 的硬上限。
        let batches = ServerAPIClient.importBatches(
            for: (0..<250).map { message("m\($0)") },
            maxCount: 1000
        )
        XCTAssertEqual(batches.map(\.count), [200, 50])
    }

    func testBatchesSplitByBytes() {
        let big = String(repeating: "字", count: 5_000)
        let batches = ServerAPIClient.importBatches(
            for: [message("a", content: big), message("b", content: big)],
            maxBytes: 10_000
        )
        XCTAssertEqual(batches.count, 2, "两条大消息不该挤进同一批")
    }

    // MARK: - id 归一化

    func testNormalizedImportIdKeepsSafeIds() {
        let uuid = UUID().uuidString
        XCTAssertEqual(ServerAPIClient.normalizedImportId(uuid), uuid)
        XCTAssertEqual(ServerAPIClient.normalizedImportId("  abc-DEF_123  "), "abc-DEF_123")
    }

    /// 老数据里可能有 `:` / `.` / 空格：替换成 `-` 且**每次结果相同**（重试还是同一个 id）。
    func testNormalizedImportIdIsDeterministicForOddCharacters() {
        let first = ServerAPIClient.normalizedImportId("server:abc.def ghi")
        let second = ServerAPIClient.normalizedImportId("server:abc.def ghi")
        XCTAssertEqual(first, second)
        XCTAssertEqual(first, "server-abc-def-ghi")
        XCTAssertFalse(first.contains(":"))
        XCTAssertFalse(first.contains("."))
        XCTAssertFalse(first.contains(" "))
    }

    func testNormalizedImportIdTruncatesTo128() {
        let raw = String(repeating: "a", count: 300)
        XCTAssertEqual(ServerAPIClient.normalizedImportId(raw).count, 128)
    }

    // MARK: - 请求形状

    func testRequestBodyIsObjectWithContractFields() async throws {
        let result = try await client().importMessages(
            sessionId: "s1",
            messages: [message("m1", role: "ASSISTANT", content: "在的", createdAt: 1_234, readAt: 1_300),
                       message("m2", content: "还没读的")]
        )
        XCTAssertEqual(result.imported, 2)
        XCTAssertEqual(result.skipped, 0)
        XCTAssertEqual(result.sessionId, "s1")

        let request = try XCTUnwrap(ServerRouteStub.recorded.last)
        XCTAssertEqual(request.method, "POST")
        XCTAssertEqual(request.path, "/v1/sessions/s1/messages/import")
        let object = try XCTUnwrap(request.jsonObject)
        XCTAssertNil(object["messages"] as? [String: Any], "body 是对象不是裸数组")
        let list = try XCTUnwrap(object["messages"] as? [[String: Any]])
        XCTAssertEqual(list.count, 2)

        let first = list[0]
        XCTAssertEqual(first["id"] as? String, "m1")
        XCTAssertEqual(first["role"] as? String, "ASSISTANT")
        XCTAssertEqual(first["content"] as? String, "在的")
        XCTAssertEqual(BootstrapFakeServer.int64(first["createdAt"]), 1_234)
        XCTAssertEqual(BootstrapFakeServer.int64(first["readAt"]), 1_300)
        XCTAssertEqual(first["attachments"] as? [String], [])
        XCTAssertEqual(first["source"] as? String, "IMPORT")

        // 未读：`readAt` 缺键（服务端缺省 = 未读），不要写成 null 也不要写 0。
        XCTAssertNil(list[1]["readAt"])
    }

    func testMoreThan200MessagesIsRejectedBeforeSending() async throws {
        let messages = (0..<201).map { message("m\($0)") }
        do {
            _ = try await client().importMessages(sessionId: "s1", messages: messages)
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.invalidArgument)
        }
        XCTAssertTrue(ServerRouteStub.recorded.isEmpty, "不该发出一个注定 400 的请求")
    }

    func testEmptySessionIdIsRejected() async throws {
        do {
            _ = try await client().importMessages(sessionId: "  ", messages: [message("m1")])
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.invalidArgument)
        }
    }

    // MARK: - 幂等

    func testSameBatchTwiceImportsOnce() async throws {
        let batch = (0..<5).map { message("idem-\($0)", createdAt: 2_000 + Int64($0)) }
        let first = try await client().importMessages(sessionId: "s1", messages: batch)
        XCTAssertEqual(first.imported, 5)
        XCTAssertEqual(first.skipped, 0)

        let second = try await client().importMessages(sessionId: "s1", messages: batch)
        XCTAssertEqual(second.imported, 0)
        XCTAssertEqual(second.skipped, 5)
        XCTAssertEqual(second.messageCount, 5)
        XCTAssertEqual(server.messages["s1"]?.count, 5, "服务端不该出现第二份")
    }

    /// 服务端说附件不存在：错误码必须原样传上来（bootstrap 靠它决定「去掉附件重推」）。
    func testUnknownMediaErrorCodeSurvives() async throws {
        server.rejectUnknownMedia = true
        do {
            _ = try await client().importMessages(
                sessionId: "s1",
                messages: [message("m1", attachments: ["/v1/media/missing"])]
            )
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, "unknown_media")
            XCTAssertEqual(error.status, 400)
        }
        XCTAssertEqual((server.messages["s1"] ?? []).count, 0, "整批拒写：一条都不该进去")
    }

    // MARK: - 响应解码的宽容度

    func testResultDecodesNumericCounts() throws {
        let json = #"{"sessionId":"s1","imported":2,"skipped":1,"messageCount":17}"#
        let result = try JSONDecoder().decode(
            ServerImportResult.self, from: Data(json.utf8)
        )
        XCTAssertEqual(result.sessionId, "s1")
        XCTAssertEqual(result.imported, 2)
        XCTAssertEqual(result.skipped, 1)
        XCTAssertEqual(result.messageCount, 17)
    }

    func testResultAcceptsIdArrayAsImported() throws {
        let json = #"{"imported":["a","b","c"],"skipped":0}"#
        let result = try JSONDecoder().decode(
            ServerImportResult.self, from: Data(json.utf8)
        )
        XCTAssertEqual(result.imported, 3)
        XCTAssertEqual(result.sessionId, "")
    }

    func testResultToleratesMissingFields() throws {
        let result = try JSONDecoder().decode(
            ServerImportResult.self, from: Data("{}".utf8)
        )
        XCTAssertEqual(result.imported, 0)
        XCTAssertEqual(result.skipped, 0)
        XCTAssertNil(result.firstBlock)
    }

    func testResultReadsBlockRange() throws {
        let json = #"{"imported":1,"blockRange":{"first":3,"last":4}}"#
        let result = try JSONDecoder().decode(
            ServerImportResult.self, from: Data(json.utf8)
        )
        XCTAssertEqual(result.firstBlock, 3)
        XCTAssertEqual(result.lastBlock, 4)
    }
}
