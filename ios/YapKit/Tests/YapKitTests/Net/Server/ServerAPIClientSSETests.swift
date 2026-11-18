import Foundation
import XCTest
@testable import YapKit

/// `ServerAPIClient.sendMessage` 的 SSE 链路：分帧、事件语义、取消、非 2xx。
final class ServerAPIClientSSETests: XCTestCase {

    override func setUp() {
        super.setUp()
        ServerURLProtocolStub.reset()
    }

    override func tearDown() {
        ServerURLProtocolStub.reset()
        super.tearDown()
    }

    private func collect(
        _ stream: AsyncThrowingStream<ServerChatEvent, Error>
    ) async throws -> [ServerChatEvent] {
        var events: [ServerChatEvent] = []
        for try await event in stream { events.append(event) }
        return events
    }

    private func request() throws -> URLRequest {
        try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
    }

    // MARK: - 完整事件序列

    func testFullEventSequenceAndRequestShape() async throws {
        let text = ServerTestSupport.sseFrame(
            "accepted", #"{"userMessageId":"u1","createdAt":100}"#
        ) + ServerTestSupport.sseFrame(
            "delta", #"{"text":"你"}"#
        ) + ServerTestSupport.sseFrame(
            "delta", #"{"text":"好"}"#
        ) + ServerTestSupport.sseFrame(
            "tool", #"{"name":"web_search","status":"running","summary":null}"#
        ) + ServerTestSupport.sseFrame(
            "done",
            #"{"assistantMessageId":"a1","usage":{"promptTokens":10,"completionTokens":20}}"#
        )
        ServerURLProtocolStub.enqueue(.sse(text))

        let client = ServerTestSupport.client()
        let events = try await collect(client.sendMessage(
            sessionId: "s1",
            clientMessageId: "client-1",
            content: "今天怎么样",
            attachments: ["media-1"],
            replyTo: "m0"
        ))

        XCTAssertEqual(events.count, 5)
        XCTAssertEqual(events[0], .accepted(userMessageId: "u1", createdAt: 100))
        XCTAssertEqual(events[1], .delta(text: "你"))
        XCTAssertEqual(events[2], .delta(text: "好"))
        XCTAssertEqual(events[3], .tool(name: "web_search", status: "running", summary: nil))
        XCTAssertEqual(
            events[4],
            .done(assistantMessageId: "a1", usage: ServerChatUsage(promptTokens: 10, completionTokens: 20))
        )

        let request = try request()
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/sessions/s1/messages")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "text/event-stream")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer secret-token")
        XCTAssertEqual(request.timeoutInterval, 0, "SSE 请求不能有超时，靠消费方取消")

        let body = try XCTUnwrap(ServerURLProtocolStub.recordedBodies.first)
        let json = try JSONDecoder().decode([String: JSONValue].self, from: body)
        XCTAssertEqual(json["clientMessageId"], .string("client-1"))
        XCTAssertEqual(json["content"], .string("今天怎么样"))
        XCTAssertEqual(json["attachments"], .array([.string("media-1")]))
        XCTAssertEqual(json["replyTo"], .string("m0"))
    }

    func testReplyToIsOmittedWhenNil() async throws {
        ServerURLProtocolStub.enqueue(.sse(
            ServerTestSupport.sseFrame("done", #"{"assistantMessageId":"a1"}"#)
        ))
        let client = ServerTestSupport.client()
        _ = try await collect(client.sendMessage(
            sessionId: "s1",
            clientMessageId: "client-2",
            content: "hi"
        ))
        let body = try XCTUnwrap(ServerURLProtocolStub.recordedBodies.first)
        let json = try JSONDecoder().decode([String: JSONValue].self, from: body)
        XCTAssertNil(json["replyTo"])
        XCTAssertEqual(json["attachments"], .array([]))
    }

    // MARK: - 分帧

    func testSSESplitIntoManyChunksStillParses() async throws {
        let text = ServerTestSupport.sseFrame(
            "accepted", #"{"userMessageId":"u1","createdAt":1}"#
        ) + ServerTestSupport.sseFrame(
            "delta", #"{"text":"第一段"}"#
        ) + ServerTestSupport.sseFrame(
            "delta", #"{"text":"第二段"}"#
        ) + ServerTestSupport.sseFrame(
            "done", #"{"assistantMessageId":"a1","usage":{"promptTokens":1,"completionTokens":2}}"#
        )
        // 每 1 个字节写一次：覆盖半包、CR/LF 被拆开、JSON 被拆开的所有情况。
        ServerURLProtocolStub.enqueue(.sse(text, splitEvery: 1))

        let client = ServerTestSupport.client()
        let events = try await collect(client.sendMessage(
            sessionId: "s1", clientMessageId: "c", content: "hi"
        ))

        XCTAssertEqual(events.count, 4)
        XCTAssertEqual(events[1], .delta(text: "第一段"))
        XCTAssertEqual(events[2], .delta(text: "第二段"))
    }

    func testSSEHandlesCRLFCommentsMultiLineDataAndUnknownEvents() async throws {
        let text = ": keep-alive\r\n"
            + "event: delta\r\n"
            + "data: {\"text\":\r\n"
            + "data: \"多行\"}\r\n"
            + "\r\n"
            + "event: ping\r\n"
            + "data: {\"ignored\":true}\r\n"
            + "\r\n"
            + ": another comment\r\n"
            + "event: done\r\n"
            + "data: {\"assistantMessageId\":\"a1\"}\r\n"
            + "\r\n"
        ServerURLProtocolStub.enqueue(.sse(text, splitEvery: 2))

        let client = ServerTestSupport.client()
        let events = try await collect(client.sendMessage(
            sessionId: "s1", clientMessageId: "c", content: "hi"
        ))

        XCTAssertEqual(events.count, 2)
        XCTAssertEqual(events[0], .delta(text: "多行"))
        XCTAssertEqual(
            events[1],
            .done(assistantMessageId: "a1", usage: ServerChatUsage())
        )
    }

    func testDataWithoutEventNameIsIgnored() async throws {
        let text = "data: {\"text\":\"无事件名\"}\n\n"
            + ServerTestSupport.sseFrame("delta", #"{"text":"有事件名"}"#)
        ServerURLProtocolStub.enqueue(.sse(text))
        let client = ServerTestSupport.client()
        let events = try await collect(client.sendMessage(
            sessionId: "s1", clientMessageId: "c", content: "hi"
        ))
        XCTAssertEqual(events, [.delta(text: "有事件名")])
    }

    func testFinalFrameWithoutTrailingBlankLineIsFlushed() async throws {
        let text = ServerTestSupport.sseFrame("delta", #"{"text":"最后"}"#)
            + "event: done\ndata: {\"assistantMessageId\":\"a1\"}"
        ServerURLProtocolStub.enqueue(.sse(text))
        let client = ServerTestSupport.client()
        let events = try await collect(client.sendMessage(
            sessionId: "s1", clientMessageId: "c", content: "hi"
        ))
        XCTAssertEqual(events.count, 2)
        XCTAssertEqual(events[1], .done(assistantMessageId: "a1", usage: ServerChatUsage()))
    }

    // MARK: - done / error

    func testErrorEventEndsTheStreamWithoutThrowing() async throws {
        let text = ServerTestSupport.sseFrame(
            "error", #"{"code":"generation_failed","message":"生成失败，已保存部分"}"#
        )
        ServerURLProtocolStub.enqueue(.sse(text))
        let client = ServerTestSupport.client()
        let events = try await collect(client.sendMessage(
            sessionId: "s1", clientMessageId: "c", content: "hi"
        ))
        XCTAssertEqual(events, [
            .error(ServerAPIError(
                code: "generation_failed",
                status: nil,
                message: "生成失败，已保存部分"
            ))
        ])
    }

    func testErrorEventWithoutPayloadUsesDefaultCodeAndMessage() async throws {
        ServerURLProtocolStub.enqueue(.sse(ServerTestSupport.sseFrame("error", "{}")))
        let client = ServerTestSupport.client()
        let events = try await collect(client.sendMessage(
            sessionId: "s1", clientMessageId: "c", content: "hi"
        ))
        guard case .error(let error) = events.first else {
            return XCTFail("应当是 error 事件：\(events)")
        }
        XCTAssertEqual(error.code, "server_error")
        XCTAssertFalse(error.message.isEmpty)
    }

    // 0.13：服务端「他选择不说话」时 done 带 `silent: true` 且没有 assistantMessageId。
    func testSilentDoneWithoutAssistantMessageIsAccepted() async throws {
        let text = ServerTestSupport.sseFrame(
            "delta", #"{"text":""}"#
        ) + ServerTestSupport.sseFrame(
            "done", #"{"assistantMessageId":"","silent":true}"#
        )
        ServerURLProtocolStub.enqueue(.sse(text))
        let client = ServerTestSupport.client()

        let events = try await collect(client.sendMessage(
            sessionId: "s1", clientMessageId: "c", content: "在吗"
        ))

        XCTAssertEqual(events.last, .done(assistantMessageId: "", usage: ServerChatUsage()))
    }

    func testEmptyDoneWithoutSilentFlagIsStillInvalid() async {
        ServerURLProtocolStub.enqueue(.sse(
            ServerTestSupport.sseFrame("done", #"{"assistantMessageId":""}"#)
        ))
        let client = ServerTestSupport.client()
        do {
            _ = try await collect(client.sendMessage(
                sessionId: "s1", clientMessageId: "c", content: "hi"
            ))
            XCTFail("没有 silent 标记的空 done 仍是坏数据")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.invalidSSE)
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
    }

    func testMalformedKnownEventThrowsInvalidSSE() async {
        ServerURLProtocolStub.enqueue(.sse(
            ServerTestSupport.sseFrame("delta", "not-json")
        ))
        let client = ServerTestSupport.client()
        do {
            _ = try await collect(client.sendMessage(
                sessionId: "s1", clientMessageId: "c", content: "hi"
            ))
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.invalidSSE)
            XCTAssertTrue(error.message.contains("delta"))
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
    }

    // MARK: - 非 2xx / 取消

    func testNon2xxSSERequestMapsServerErrorBody() async {
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"message":"内容不能为空","code":"invalid_argument"}}"#,
            status: 400
        ))
        let client = ServerTestSupport.client()
        do {
            _ = try await collect(client.sendMessage(
                sessionId: "s1", clientMessageId: "c", content: ""
            ))
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, "invalid_argument")
            XCTAssertEqual(error.status, 400)
            XCTAssertEqual(error.message, "内容不能为空")
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
    }

    func testCancellingConsumerClosesTheUnderlyingStream() async throws {
        var text = ""
        for index in 0..<40 {
            text += ServerTestSupport.sseFrame("delta", #"{"text":"片段\#(index)"}"#)
        }
        let firstChunk = expectation(description: "收到第一块 SSE")
        let stopped = expectation(description: "URLSession 任务被取消（stopLoading）")
        var stub = ServerURLProtocolStub.Response.sse(
            text, splitEvery: 8, delay: 0.01
        )
        stub.onChunk = { (index: Int) in
            if index == 0 { firstChunk.fulfill() }
        }
        stub.onStop = { stopped.fulfill() }
        ServerURLProtocolStub.enqueue(stub)

        let client = ServerTestSupport.client()
        let stream = client.sendMessage(
            sessionId: "s1", clientMessageId: "c", content: "hi"
        )
        let consumer = Task { () -> Error? in
            do {
                for try await _ in stream {}
                return nil
            } catch {
                return error
            }
        }

        await fulfillment(of: [firstChunk], timeout: 3)
        consumer.cancel()
        await fulfillment(of: [stopped], timeout: 3)
        _ = await consumer.value
    }
}

// MARK: - 纯分帧器

/// 分帧器单独测：客户端的 `URLSession` 可能把小块合并，不能只靠端到端用例证明半包处理。
final class ServerAPIClientSSEParserTests: XCTestCase {

    func testFeedOneByteAtATimeProducesSameFrames() {
        let text = ServerTestSupport.sseFrame("delta", #"{"text":"你好"}"#)
            + ServerTestSupport.sseFrame("done", #"{"assistantMessageId":"a1"}"#)
        var parser = ServerSSEParser()
        var frames: [ServerSSEFrame] = []
        for byte in Data(text.utf8) {
            frames += parser.feed(Data([byte]))
        }
        frames += parser.flush()
        XCTAssertEqual(frames.map(\.event), ["delta", "done"])
        XCTAssertEqual(frames.first?.data, #"{"text":"你好"}"#)
    }

    func testCRLFAtChunkBoundary() {
        var parser = ServerSSEParser()
        XCTAssertEqual(parser.feed("event: delta\r"), [])
        XCTAssertEqual(parser.feed("\ndata: {\"text\":\"x\"}\r"), [])
        let frames = parser.feed("\n\r\n")
        XCTAssertEqual(frames, [ServerSSEFrame(event: "delta", data: #"{"text":"x"}"#)])
        XCTAssertEqual(parser.flush(), [])
    }

    func testEmptyFeedBetweenCRAndLFDoesNotBreakCRLF() {
        var parser = ServerSSEParser()
        XCTAssertEqual(parser.feed("event: delta\r"), [])
        XCTAssertEqual(parser.feed(""), [], "空块不能清掉待配对的 LF")
        XCTAssertEqual(parser.feed("\ndata: {}\r\n\r\n"), [ServerSSEFrame(event: "delta", data: "{}")])
    }

    func testStandaloneCRActsAsLineEnding() {
        var parser = ServerSSEParser()
        let frames = parser.feed("event: delta\rdata: {}\r\r")
        XCTAssertEqual(frames, [ServerSSEFrame(event: "delta", data: "{}")])
    }

    func testMultiLineDataIsJoinedWithNewline() {
        let frames = ServerSSEParser.frames(in: "event: delta\ndata: line1\ndata: line2\n\n")
        XCTAssertEqual(frames, [ServerSSEFrame(event: "delta", data: "line1\nline2")])
    }

    func testCommentsAndUnknownFieldsAreIgnored() {
        let frames = ServerSSEParser.frames(in: """
        : heartbeat
        id: 7
        retry: 1000
        event: delta
        data: {"text":"x"}

        """)
        XCTAssertEqual(frames, [ServerSSEFrame(event: "delta", data: #"{"text":"x"}"#)])
    }

    func testFlushEmitsLastFrameWithoutBlankLine() {
        var parser = ServerSSEParser()
        XCTAssertEqual(parser.feed("event: done\ndata: {}"), [])
        XCTAssertEqual(
            parser.flush(),
            [ServerSSEFrame(event: "done", data: "{}")]
        )
        XCTAssertEqual(parser.flush(), [])
    }

    func testFramesWithoutDataAreNotDispatched() {
        let frames = ServerSSEParser.frames(in: "event: ping\n\n: comment\n\n")
        XCTAssertTrue(frames.isEmpty)
    }

    func testEmptyDataLineStillFrames() {
        // `data:` 的空值也是一行数据；客户端会把它当坏 JSON 报错，而不是静默吞掉。
        let frames = ServerSSEParser.frames(in: "event: delta\ndata:\n\n")
        XCTAssertEqual(frames, [ServerSSEFrame(event: "delta", data: "")])
    }
}
