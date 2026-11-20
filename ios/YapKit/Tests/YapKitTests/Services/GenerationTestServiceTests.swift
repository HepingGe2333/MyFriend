import Foundation
import XCTest
@testable import YapKit

/// 0.15 设置页「测试生成」（`GenerationTestService`）。
///
/// 原则：
/// - 不真的 sleep：耗时的两个读数都来自注入的时钟序列；
/// - 不真的发请求：`stream` 闭包直接返回几段固定增量（或一个错误）；
/// - 不碰数据库：这个服务本来就不写聊天库，测试里也没有 `TempDatabase`。
final class GenerationTestServiceTests: XCTestCase {

    /// 按顺序吐出预设值的毫秒时钟；调用次数也记下来，便于断言「只读了两次」。
    private final class TestClock {
        private var values: [Int64]
        private(set) var calls = 0

        init(_ values: [Int64]) { self.values = values }

        func next() -> Int64 {
            calls += 1
            return values.isEmpty ? 0 : values.removeFirst()
        }
    }

    private func config(
        model: String = "gpt-test",
        temperature: Double? = 0.3,
        maxOutputTokens: Int? = 128
    ) -> ApiConfigEntity {
        ApiConfigEntity(
            id: "cfg-1",
            name: "测试配置",
            baseUrl: "https://example.com/v1",
            model: model,
            temperature: temperature,
            maxOutputTokens: maxOutputTokens
        )
    }

    private func stream(_ chunks: [String]) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            for chunk in chunks { continuation.yield(chunk) }
            continuation.finish()
        }
    }

    private func failingStream(_ error: Error) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            continuation.finish(throwing: error)
        }
    }

    private func service(
        chunks: [String],
        clock: TestClock,
        captured: ((GenerationTestRequest) -> Void)? = nil
    ) -> GenerationTestService {
        GenerationTestService(
            stream: { request in
                captured?(request)
                return self.stream(chunks)
            },
            now: { clock.next() }
        )
    }

    // MARK: - 成功路径

    func testSuccessfulRunUsesInjectedClockAndChunks() async {
        let clock = TestClock([1_000, 2_800])
        let result = await service(chunks: ["收到", "。"], clock: clock).run(
            config: config(), apiKey: "sk-test", model: "gpt-test"
        )

        guard case .success(let report) = result else {
            return XCTFail("应当成功，实际：\(result)")
        }
        XCTAssertEqual(report.elapsedMillis, 1_800)
        XCTAssertEqual(report.elapsedText, "1.8 秒")
        XCTAssertEqual(report.charCount, 3)
        XCTAssertEqual(report.charCountText, "3 字")
        XCTAssertEqual(report.preview, "收到。")
        XCTAssertEqual(report.model, "gpt-test")
        XCTAssertEqual(report.toolNames, [])
        XCTAssertEqual(report.toolsText, "没用工具")
        XCTAssertEqual(clock.calls, 2, "耗时只读开始/结束两次，不真的等待")
    }

    func testElapsedFormattingBoundaries() {
        XCTAssertEqual(report(elapsed: 0).elapsedText, "0 毫秒")
        XCTAssertEqual(report(elapsed: 999).elapsedText, "999 毫秒")
        XCTAssertEqual(report(elapsed: 1_000).elapsedText, "1.0 秒")
        XCTAssertEqual(report(elapsed: 1_560).elapsedText, "1.6 秒")
        XCTAssertEqual(report(elapsed: 65_000).elapsedText, "65.0 秒")
        XCTAssertEqual(report(elapsed: -5).elapsedText, "0 毫秒", "负耗时夹到 0，不显示 -5 毫秒")
    }

    func testEmptyStreamProducesZeroCharsAndEmptyPreview() async {
        let clock = TestClock([0, 5])
        let result = await service(chunks: [], clock: clock).run(
            config: config(), apiKey: nil, model: "gpt-test"
        )
        guard case .success(let report) = result else {
            return XCTFail("空流也是一种成功（只是没说话），实际：\(result)")
        }
        XCTAssertEqual(report.charCount, 0)
        XCTAssertEqual(report.charCountText, "0 字")
        XCTAssertEqual(report.preview, "")
        XCTAssertEqual(report.toolsText, "没用工具")
    }

    func testPreviewIsCappedAtEightyCharacters() async {
        let long = String(repeating: "好", count: 120)
        let clock = TestClock([0, 100])
        let result = await service(chunks: [long], clock: clock).run(
            config: config(), apiKey: nil, model: "gpt-test"
        )
        guard case .success(let report) = result else {
            return XCTFail("应当成功")
        }
        XCTAssertEqual(report.charCount, 120, "字数按完整可见文本算，不因预览截断")
        XCTAssertEqual(report.preview.count, 80)
        XCTAssertEqual(report.preview, String(long.prefix(80)))
    }

    // MARK: - 工具解析 / 可见文本

    func testToolNamesAreParsedFromTheStreamedBuffer() async {
        let chunks = [
            "我先看看\n",
            "```\(ToolProtocol.blockLanguage)\n",
            "{\"name\":\"web_search\",\"arguments\":{\"query\":\"swift\"}}\n",
            "```\n",
            "看完了"
        ]
        let clock = TestClock([0, 1_200])
        let result = await service(chunks: chunks, clock: clock).run(
            config: config(), apiKey: nil, model: "gpt-test"
        )
        guard case .success(let report) = result else {
            return XCTFail("应当成功，实际：\(result)")
        }
        XCTAssertEqual(report.toolNames, ["web_search"])
        XCTAssertEqual(report.toolsText, "web_search")
        XCTAssertEqual(report.preview, "我先看看", "预览只含工具块之前的可见正文")
        XCTAssertEqual(report.charCount, "我先看看".count)
    }

    func testVisibleTextDoesNotContainToolBlock() async {
        let block = "```\(ToolProtocol.blockLanguage)\n"
            + "{\"name\": \"recall\", \"arguments\": {}}\n```"
        let clock = TestClock([0, 10])
        let result = await service(chunks: ["查一下。", block], clock: clock).run(
            config: config(), apiKey: nil, model: "gpt-test"
        )
        guard case .success(let report) = result else {
            return XCTFail("应当成功")
        }
        XCTAssertFalse(report.preview.contains("yap-tool"))
        XCTAssertFalse(report.preview.contains("recall"))
        XCTAssertFalse(report.preview.contains("arguments"))
        XCTAssertEqual(report.charCount, "查一下。".count)
    }

    func testToolBlockSplitAcrossChunksIsStillParsed() async {
        let block = "```\(ToolProtocol.blockLanguage)\n"
            + "{\"name\": \"web_search\", \"arguments\": {\"query\": \"swift\"}}\n```"
        let cut = block.index(block.startIndex, offsetBy: block.count / 2)
        let chunks = ["先说一句\n", String(block[..<cut]), String(block[cut...])]
        let clock = TestClock([0, 900])
        let result = await service(chunks: chunks, clock: clock).run(
            config: config(), apiKey: nil, model: "gpt-test"
        )
        guard case .success(let report) = result else {
            return XCTFail("应当成功")
        }
        XCTAssertEqual(report.toolNames, ["web_search"], "增量被切开也要能解析")
        XCTAssertEqual(report.preview, "先说一句")
    }

    func testDuplicateToolNamesAreDeduplicated() async {
        let block = "```\(ToolProtocol.blockLanguage)\n"
            + "{\"name\": \"web_search\", \"arguments\": {}}\n```"
        let chunks = [block, "\n", block]
        let clock = TestClock([0, 100])
        let result = await service(chunks: chunks, clock: clock).run(
            config: config(), apiKey: nil, model: "gpt-test"
        )
        guard case .success(let report) = result else {
            return XCTFail("应当成功")
        }
        XCTAssertEqual(report.toolNames, ["web_search"], "同一个工具调两次只展示一个名字")
        XCTAssertEqual(report.toolsText, "web_search")
        XCTAssertEqual(report.charCount, 0, "只有工具块时可见正文为 0")
    }

    func testToolsTextJoinsMultipleTools() {
        let report = GenerationTestReport(
            elapsedMillis: 100, charCount: 2,
            toolNames: ["web_search", "recall"], model: "m", preview: "好的"
        )
        XCTAssertEqual(report.toolsText, "web_search、recall")
    }

    // MARK: - 请求形状

    func testRequestCarriesFixedPromptsAndConfigParams() async {
        var captured: GenerationTestRequest?
        let clock = TestClock([0, 10])
        let service = GenerationTestService(
            stream: { request in
                captured = request
                return self.stream(["收到"])
            },
            now: { clock.next() }
        )
        _ = await service.run(config: config(), apiKey: "sk-test", model: "gpt-test")

        guard let request = captured else { return XCTFail("没有发出请求") }
        XCTAssertEqual(request.messages.count, 2)
        XCTAssertEqual(request.messages[0].role, "system")
        XCTAssertEqual(request.messages[0].content, GenerationTestService.systemPrompt)
        XCTAssertEqual(request.messages[1].role, "user")
        XCTAssertEqual(request.messages[1].content, GenerationTestService.userPrompt)
        XCTAssertEqual(request.model, "gpt-test")
        XCTAssertEqual(request.apiKey, "sk-test")
        XCTAssertEqual(request.config.name, "测试配置")
        XCTAssertEqual(request.params.temperature, 0.3)
        XCTAssertEqual(request.params.maxOutputTokens, 128)
    }

    func testModelFallsBackToConfigWhenExplicitModelIsEmpty() async {
        let clock = TestClock([0, 1])
        let result = await service(chunks: ["嗯"], clock: clock).run(
            config: config(model: "config-model"), apiKey: nil, model: "   "
        )
        guard case .success(let report) = result else {
            return XCTFail("应当成功")
        }
        XCTAssertEqual(report.model, "config-model")
    }

    func testExplicitModelWinsOverConfig() async {
        let clock = TestClock([0, 1])
        let result = await service(chunks: ["嗯"], clock: clock).run(
            config: config(model: "config-model"), apiKey: nil, model: "override-model"
        )
        guard case .success(let report) = result else {
            return XCTFail("应当成功")
        }
        XCTAssertEqual(report.model, "override-model")
    }

    // MARK: - 失败（必须是中文、不能抛）

    func testApiExceptionMessageIsShownVerbatim() async {
        let clock = TestClock([0])
        let failing = GenerationTestService(
            stream: { _ in
                self.failingStream(ApiException(statusCode: 401, message: "这个 Key 不对，换一个再试"))
            },
            now: { clock.next() }
        )
        let result = await failing.run(config: config(), apiKey: "bad", model: "gpt-test")
        XCTAssertEqual(result, .failure("这个 Key 不对，换一个再试"))
        XCTAssertNil(result.report)
    }

    func testUnknownErrorUsesChineseFallback() async {
        let english = NSError(
            domain: "test",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "The request timed out."]
        )
        let failing = GenerationTestService(
            stream: { _ in self.failingStream(english) },
            now: { 100 }
        )
        let result = await failing.run(config: config(), apiKey: nil, model: "gpt-test")
        guard case .failure(let message) = result else {
            return XCTFail("应当失败")
        }
        XCTAssertEqual(message, "测试没通过：检查一下地址、Key 和模型名，再试一次")
        XCTAssertFalse(message.contains("timed out"), "不把英文系统错误直接摊给用户")
        XCTAssertTrue(message.contains("测试没通过"))
    }

    func testCancellationMapsToChineseReason() async {
        let failing = GenerationTestService(
            stream: { _ in self.failingStream(CancellationError()) },
            now: { 100 }
        )
        let result = await failing.run(config: config(), apiKey: nil, model: "gpt-test")
        XCTAssertEqual(result, .failure("测试被取消了"))
    }

    func testFailureDoesNotReadTheEndClock() async {
        let clock = TestClock([5_000])
        let failing = GenerationTestService(
            stream: { _ in self.failingStream(ApiException(statusCode: nil, message: "断网了")) },
            now: { clock.next() }
        )
        _ = await failing.run(config: config(), apiKey: nil, model: "gpt-test")
        XCTAssertEqual(clock.calls, 1, "失败时不需要算耗时")
    }

    // MARK: - 内部

    private func report(elapsed: Int64) -> GenerationTestReport {
        GenerationTestReport(
            elapsedMillis: elapsed,
            charCount: 0,
            toolNames: [],
            model: "m",
            preview: ""
        )
    }
}
