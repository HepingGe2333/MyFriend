import Foundation
import XCTest
@testable import MyFriendKit

/// `ServerAPIClient` 的普通 JSON 请求：路径/query/headers/解码/错误映射/配置边界。
final class ServerAPIClientTests: XCTestCase {

    override func setUp() {
        super.setUp()
        ServerURLProtocolStub.reset()
    }

    override func tearDown() {
        ServerURLProtocolStub.reset()
        super.tearDown()
    }

    // MARK: - GET /v1/state

    func testStateBuildsPathQueryHeadersAndDecodesSnapshot() async throws {
        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.stateJSON))
        let client = ServerTestSupport.client()

        let snapshot = try await client.state(since: 42, personaId: "p 1")

        XCTAssertEqual(snapshot.serverTime, 1_700_000_000_000)
        XCTAssertEqual(snapshot.serverCursor, 12_345)
        XCTAssertTrue(snapshot.hasMoreMessages)
        XCTAssertEqual(snapshot.personas.count, 1)
        XCTAssertEqual(snapshot.personas.first?.name, "林深")
        XCTAssertTrue(snapshot.personas.first?.isDefault == true)
        XCTAssertEqual(snapshot.sessions.first?.lastBlockIndex, 2)
        XCTAssertEqual(snapshot.sessions.first?.unread, 1)
        XCTAssertEqual(snapshot.messages.first?.attachments, ["media-1"])
        XCTAssertEqual(snapshot.messages.first?.clientMessageId, "client-1")
        XCTAssertEqual(snapshot.mood?.label, "平静")
        XCTAssertEqual(snapshot.presence?.kind, "IDLE")

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/v1/state")
        let query = ServerTestSupport.queryItems(request)
        XCTAssertEqual(query["since"], "42")
        XCTAssertEqual(query["personaId"], "p 1")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "application/json")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer secret-token")
        XCTAssertEqual(request.timeoutInterval, ServerAPIClient.requestTimeout)
    }

    func testStateWithoutTokenOmitsAuthorizationHeader() async throws {
        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.stateJSON))
        let client = ServerTestSupport.client(token: nil)

        _ = try await client.state()

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(ServerTestSupport.queryItems(request)["since"], "0")
        XCTAssertNil(ServerTestSupport.queryItems(request)["personaId"])
    }

    func testBaseURLWithV1SuffixAndTrailingSlashIsNotDuplicated() async throws {
        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.stateJSON))

        let withV1 = ServerTestSupport.client(
            baseURL: URL(string: "https://server.example.com/v1/")!
        )
        _ = try await withV1.state()
        XCTAssertEqual(
            ServerURLProtocolStub.recordedRequests.first?.url?.path,
            "/v1/state"
        )

        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.stateJSON))
        let withBasePath = ServerTestSupport.client(
            baseURL: URL(string: "https://server.example.com/myfriend")!
        )
        _ = try await withBasePath.state()
        XCTAssertEqual(
            ServerURLProtocolStub.recordedRequests.last?.url?.path,
            "/myfriend/v1/state"
        )
    }

    // MARK: - GET /v1/sessions

    func testSessionsBuildsPathAndPersonaQuery() async throws {
        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.sessionsJSON))
        let client = ServerTestSupport.client()

        let page = try await client.sessions(personaId: "p1")

        XCTAssertEqual(page.serverCursor, 77)
        XCTAssertEqual(page.sessions.first?.title, "第一个会话")
        XCTAssertEqual(page.sessions.first?.messageCount, 5)

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.url?.path, "/v1/sessions")
        XCTAssertEqual(ServerTestSupport.queryItems(request)["personaId"], "p1")
    }

    // MARK: - GET /v1/sessions/{id}/messages

    func testMessagesDefaultsToNewestBlockAndDecodesPage() async throws {
        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.messagePageJSON))
        let client = ServerTestSupport.client()

        let page = try await client.messages(sessionId: "s1")

        XCTAssertEqual(page.sessionId, "s1")
        XCTAssertEqual(page.blockIndex, 2)
        XCTAssertTrue(page.hasMoreOlder)
        XCTAssertFalse(page.hasMoreNewer)
        XCTAssertEqual(page.messages.first?.role, "ASSISTANT")
        XCTAssertEqual(page.messages.first?.promptTokens, 12)

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.url?.path, "/v1/sessions/s1/messages")
        XCTAssertEqual(ServerTestSupport.queryItems(request)["limitBlocks"], "1")
        XCTAssertNil(ServerTestSupport.queryItems(request)["beforeBlock"])
        XCTAssertNil(ServerTestSupport.queryItems(request)["afterBlock"])
    }

    func testMessagesPagingUsesExactlyOneDirection() async throws {
        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.emptyMessagePageJSON))
        let client = ServerTestSupport.client()
        let before = try await client.messages(sessionId: "s1", beforeBlock: 3)
        XCTAssertNil(before.blockIndex)
        var request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(ServerTestSupport.queryItems(request)["beforeBlock"], "3")
        XCTAssertNil(ServerTestSupport.queryItems(request)["afterBlock"])

        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.messagePageJSON))
        _ = try await client.messages(sessionId: "s1", afterBlock: 1)
        request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(ServerTestSupport.queryItems(request)["afterBlock"], "1")
        XCTAssertNil(ServerTestSupport.queryItems(request)["beforeBlock"])
    }

    func testMessagesRejectsBothDirectionsWithoutSendingARequest() async {
        let client = ServerTestSupport.client()
        do {
            _ = try await client.messages(sessionId: "s1", beforeBlock: 1, afterBlock: 2)
            XCTFail("应当拒绝同时给两个方向")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.invalidArgument)
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty)
    }

    func testMessagesEncodesSessionIdAsOnePathSegment() async throws {
        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.emptyMessagePageJSON))
        let client = ServerTestSupport.client()
        _ = try await client.messages(sessionId: "s/../1")
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertTrue(
            request.url?.absoluteString.contains("s%2F..%2F1") == true,
            "会话 id 里的斜杠必须编码成一个路径段，不能让 `..` 变成路径穿越：\(request.url?.absoluteString ?? "")"
        )
    }

    func testMessagesRejectsEmptySessionId() async {
        let client = ServerTestSupport.client()
        do {
            _ = try await client.messages(sessionId: "  ")
            XCTFail("应当拒绝空 sessionId")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.invalidArgument)
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
    }

    // MARK: - GET /v1/events

    func testEventsBuildsQueryAndDecodesPayloads() async throws {
        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.eventsJSON))
        let client = ServerTestSupport.client()

        let page = try await client.events(since: 7, timeoutSeconds: 5, limit: 50)

        XCTAssertEqual(page.cursor, 9)
        XCTAssertEqual(page.events.count, 2)
        XCTAssertEqual(page.events.first?.type, "message.created")
        XCTAssertEqual(
            page.events.first?.payloadObject?["message"],
            .object(["id": .string("m1")])
        )
        XCTAssertNil(page.events.last?.sessionId)

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.url?.path, "/v1/events")
        let query = ServerTestSupport.queryItems(request)
        XCTAssertEqual(query["since"], "7")
        XCTAssertEqual(query["timeoutSeconds"], "5")
        XCTAssertEqual(query["limit"], "50")
        XCTAssertEqual(
            request.timeoutInterval, ServerAPIClient.resourceTimeout,
            "长轮询最长可以等 25s，不能用普通请求的 10s 掐断"
        )
    }

    func testEventsOmitsOptionalQueries() async throws {
        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.eventsJSON))
        let client = ServerTestSupport.client()
        _ = try await client.events(since: 0)
        let query = ServerTestSupport.queryItems(
            try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        )
        XCTAssertEqual(query["since"], "0")
        XCTAssertNil(query["timeoutSeconds"])
        XCTAssertNil(query["limit"])
    }

    func testNegativeCursorsAreRejectedWithoutARequest() async {
        let client = ServerTestSupport.client()
        do {
            _ = try await client.state(since: -1)
            XCTFail("应当拒绝负 since")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.invalidArgument)
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
        do {
            _ = try await client.events(since: -1)
            XCTFail("应当拒绝负 since")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.invalidArgument)
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty)
    }

    // MARK: - 错误映射

    func testNon2xxErrorBodyMapsCodeStatusAndMessage() async {
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"message":"Token 不对","code":"unauthorized"}}"#,
            status: 401
        ))
        let client = ServerTestSupport.client()
        do {
            _ = try await client.state()
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, "unauthorized")
            XCTAssertEqual(error.status, 401)
            XCTAssertEqual(error.message, "Token 不对")
            XCTAssertFalse(error.message.contains("secret-token"))
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
    }

    func testMalformedErrorBodyFallsBackToHTTPStatus() async {
        ServerURLProtocolStub.enqueue(.json("not json", status: 503))
        let client = ServerTestSupport.client()
        do {
            _ = try await client.state()
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, "http_503")
            XCTAssertEqual(error.status, 503)
            XCTAssertTrue(error.message.contains("服务端暂时不可用"))
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
    }

    func testInvalidJSONResponseMapsDecodeFailure() async {
        ServerURLProtocolStub.enqueue(.json(#"{"serverTime":"#))
        let client = ServerTestSupport.client()
        do {
            _ = try await client.state()
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.decodeFailed)
            XCTAssertEqual(error.status, 200)
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
    }

    func testNetworkTimeoutMapsToTimeoutError() async {
        ServerURLProtocolStub.enqueue(ServerURLProtocolStub.Response(failure: URLError(.timedOut)))
        let client = ServerTestSupport.client()
        do {
            _ = try await client.state()
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.timeout)
            XCTAssertNil(error.status)
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
    }

    func testOfflineErrorMapsToNetworkError() async {
        ServerURLProtocolStub.enqueue(
            ServerURLProtocolStub.Response(failure: URLError(.notConnectedToInternet))
        )
        let client = ServerTestSupport.client()
        do {
            _ = try await client.state()
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.networkError)
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
    }

    // MARK: - 配置边界

    func testDisabledConfigFailsWithoutARequest() async {
        let client = ServerAPIClient(
            config: ServerConfig(
                baseURL: URL(string: "https://server.example.com")!,
                enabled: false
            ),
            session: ServerURLProtocolStub.makeSession()
        )
        do {
            _ = try await client.state()
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.disabled)
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty)
    }

    func testDefaultSessionTimeoutsAreTenAndThirtySeconds() {
        let normal = ServerAPIClient.makeSession()
        XCTAssertEqual(normal.configuration.timeoutIntervalForRequest, 10)
        XCTAssertEqual(normal.configuration.timeoutIntervalForResource, 30)
        let sse = ServerAPIClient.makeSSESession()
        XCTAssertGreaterThan(
            sse.configuration.timeoutIntervalForResource, ServerAPIClient.resourceTimeout,
            "SSE 不能被普通请求的 30s 资源超时掐断"
        )
    }

    func testMissingBaseURLFailsWithoutARequest() async {
        let client = ServerAPIClient(
            config: ServerConfig(baseURL: nil),
            session: ServerURLProtocolStub.makeSession()
        )
        do {
            _ = try await client.state()
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.notConfigured)
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
    }
}
