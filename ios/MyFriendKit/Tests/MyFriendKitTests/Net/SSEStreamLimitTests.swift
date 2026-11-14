import Foundation
import XCTest
@testable import MyFriendKit

/// SSE 单行上限必须加在**生产真正累加的那个缓冲区**上（0.11.1 审计 P1-1）。
///
/// 旧实现里 `SSEParser` 有一条 `buffer.count > MAX_LINE_BYTES` 的分支，但生产代码
/// （`OpenAICompatibleClient.runStream`）是「客户端自己 append 字节、攒到换行才 feed」，
/// 于是喂进解析器的块一定以换行结尾、解析器那条分支永远不执行；真正无上限增长的是
/// 客户端的 `buffer`。这条测试走的是**真实的网络 → 解析 → 流**链路（`StubURLProtocol`），
/// 而不是直接给解析器喂一个 8 MB 字符串（旧测试正是那样，所以测的是不可达的入口）。
final class SSEStreamLimitTests: XCTestCase {

    override func setUp() {
        super.setUp()
        StubURLProtocol.reset()
    }

    override func tearDown() {
        StubURLProtocol.reset()
        super.tearDown()
    }

    // MARK: - 夹具

    private func makeClient() -> OpenAICompatibleClient {
        OpenAICompatibleClient(
            session: StubURLProtocol.makeSession(),
            usage: nil,
            gate: LlmConcurrencyGate()
        )
    }

    private func makeConfig() -> ApiConfigEntity {
        ApiConfigEntity(
            id: "cfg-sse-limit",
            name: "测试配置",
            provider: .openai,
            baseUrl: "https://api.example.com",
            model: "deepseek-chat",
            customHeadersJson: "{}",
            allowInsecureHttp: false,
            temperature: 0.7,
            topP: 0.9,
            maxOutputTokens: 512,
            webToolsEnabled: false
        )
    }

    private func start(_ client: OpenAICompatibleClient) -> AsyncThrowingStream<String, Error> {
        client.streamChat(
            config: makeConfig(),
            apiKey: "sk-test",
            model: "deepseek-chat",
            messages: [WireMessage(role: "user", content: "在吗")],
            params: InferenceParams(),
            kind: UsageKind.chat
        )
    }

    private func collect(_ stream: AsyncThrowingStream<String, Error>) async throws -> [String] {
        var pieces: [String] = []
        for try await piece in stream { pieces.append(piece) }
        return pieces
    }

    // MARK: - 无换行的长流

    /// 服务端声明 `text/event-stream`，却一直不吐换行：客户端必须在 8 MB 附近中止。
    ///
    /// 桩一共给 12 MB（12 × 1 MB 的 `x`）。如果客户端没有上限，它会默默攒满 12 MB，
    /// 然后因为「没有解析出任何文字」抛出那条无关的错误 —— 断言错误文案正好能区分两者。
    func testUnterminatedStreamAbortsAtEightMegabytes() async throws {
        XCTAssertEqual(SSEParser.MAX_LINE_BYTES, 8 << 20, "上限常量仍与 Android 的 8 MB 对齐")

        let megabyte = Data(repeating: UInt8(ascii: "x"), count: 1 << 20)
        StubURLProtocol.enqueue(
            StubURLProtocol.Stub(
                status: 200,
                headers: ["Content-Type": "text/event-stream"],
                chunks: Array(repeating: megabyte, count: 12)
            )
        )

        let client = makeClient()
        let started = Date()
        do {
            _ = try await collect(start(client))
            XCTFail("无换行的超长流必须抛错，而不是无声地把内存吃光")
        } catch let error as ApiException {
            XCTAssertTrue(
                error.message.contains("8 MB"),
                "中止原因要说清楚是「数据过长」；实际文案：\(error.message)"
            )
        } catch {
            XCTFail("期望 ApiException（响应过长），实际 \(error)")
        }
        // 只要在中止后立刻收流，消耗的字节数就有界（远小于桩给的 12 MB 的量级耗时）。
        XCTAssertLessThan(Date().timeIntervalSince(started), 30, "中止应该很快发生，而不是读完整个流")
    }

    /// 同一毫秒内先给几帧正常数据、再给无换行的长尾巴：已经吐出去的字不能丢，
    /// 「没有返回任何文字」这条兜底也不该被触发（错误必须是「过长」）。
    func testLongLineAfterValidFramesStillAborts() async throws {
        let frame = Data("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n".utf8)
        var chunks = [frame]
        chunks.append(Data(repeating: UInt8(ascii: "y"), count: (8 << 20) + 64))
        StubURLProtocol.enqueue(
            StubURLProtocol.Stub(
                status: 200,
                headers: ["Content-Type": "text/event-stream"],
                chunks: chunks
            )
        )

        let client = makeClient()
        var pieces: [String] = []
        do {
            for try await piece in start(client) { pieces.append(piece) }
            XCTFail("长尾巴必须中止整条流")
        } catch let error as ApiException {
            XCTAssertTrue(error.message.contains("8 MB"), "实际文案：\(error.message)")
        }
        XCTAssertEqual(pieces, ["你"], "中止前已经解析出来的正文不该被回滚")
    }

    /// 正常的多帧流不受新上限影响（回归守卫：上限不能把正常 SSE 也拦掉）。
    func testNormalStreamIsUnaffected() async throws {
        let text = """
        data: {"choices":[{"delta":{"content":"在"}}]}
        data: {"choices":[{"delta":{"content":"的"}}]}
        data: [DONE]

        """
        StubURLProtocol.enqueue(.sse(text, splitEvery: 7))
        let client = makeClient()
        let pieces = try await collect(start(client))
        XCTAssertEqual(pieces, ["在", "的"])
    }

    /// 解析器自己的上限常量与「超限按整行吐出」的降级语义保持不变（直接调用的老用例仍成立）。
    func testParserLimitBranchIsIndependentOfClientGuard() {
        var parser = SSEParser()
        let long = String(repeating: "y", count: SSEParser.MAX_LINE_BYTES + 10)
        let events = parser.feed("data: " + long)
        XCTAssertEqual(events.count, 1, "解析器的直接入口保持原有降级语义")
    }
}
