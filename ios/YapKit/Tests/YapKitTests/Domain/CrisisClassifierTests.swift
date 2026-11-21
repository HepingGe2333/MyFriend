import XCTest
@testable import YapKit

/// 0.15 危机两段式判定（`CrisisClassifier`）：词表漏斗 + 一次低温度模型复核 + 按消息 id 缓存。
///
/// 与 Android `CrisisClassifierTest` 对齐，钉九件事（每一条都对应一个产品决定）：
/// 1. 词表不命中 → **一次请求都不发**（第一段的意义就是省钱省延迟）；
/// 2. `risk=true` → 危机（stage = MODEL）；
/// 3. `risk=false` → 不进入兜底；
/// 4. 坏 JSON / 空响应 / 缺字段 / 风险值类型不对 → **按危机处理**（stage = FAILSAFE）；
/// 5. HTTP 失败 / 超时 → 同上（超时退回第一段结论）；
/// 6. 结论按消息 id 缓存（同一条消息重试不再问第二次）；
/// 7. 只把「最近一条用户消息」交给判定器（截到 2000 字），system 是固定那段指令；
/// 8. 判定请求用低温度（0）与 200 token 输出上限；
/// 9. 没有 provider 时行为与 0.15 之前完全一致（词表命中即危机）。
final class CrisisClassifierTests: XCTestCase {

    /// 记录调用内容的假 provider。
    private final class FakeJudge {
        var calls: [CrisisJudgeRequest] = []
        var responses: [Result<String, Error>]

        init(_ responses: [Result<String, Error>]) {
            self.responses = responses
        }

        convenience init(always text: String) {
            self.init([.success(text)])
        }

        var provider: CrisisJudgeProvider {
            { request in
                self.calls.append(request)
                let next = self.responses.count > 1 ? self.responses.removeFirst() : self.responses[0]
                switch next {
                case .success(let text): return text
                case .failure(let error): throw error
                }
            }
        }
    }

    private struct FakeHTTPError: Error {}

    private func riskJSON(_ risk: Bool) -> String {
        "{\"risk\":\(risk),\"reason\":\"测试\"}"
    }

    // MARK: - 第一段：词表漏斗

    func testKeywordMissDoesNotRequestAtAll() async {
        let fake = FakeJudge(always: riskJSON(true))
        let classifier = CrisisClassifier(provider: fake.provider)
        let verdict = await classifier.classify(userText: "今天好累，先睡了", messageId: "m1")
        XCTAssertFalse(verdict.isCrisis)
        XCTAssertEqual(verdict.stage, .none)
        XCTAssertEqual(0, fake.calls.count, "词表不命中就不该请求模型")
    }

    func testEmptyTextDoesNotRequest() async {
        let fake = FakeJudge(always: riskJSON(true))
        let classifier = CrisisClassifier(provider: fake.provider)
        let verdict = await classifier.classify(userText: "   \n ", messageId: "m1")
        XCTAssertFalse(verdict.isCrisis)
        XCTAssertEqual(0, fake.calls.count)
    }

    // MARK: - 第二段：true / false

    func testRiskTrueMeansCrisis() async {
        let fake = FakeJudge(always: #"{"risk":true,"reason":"明确表达想死"}"#)
        let classifier = CrisisClassifier(provider: fake.provider)
        let verdict = await classifier.classify(userText: "我真的不想活了", messageId: "m1")
        XCTAssertTrue(verdict.isCrisis)
        XCTAssertEqual(verdict.stage, .model)
        XCTAssertEqual(verdict.reason, "明确表达想死")
        XCTAssertEqual(1, fake.calls.count)
    }

    func testRiskFalseMeansNotCrisis() async {
        let fake = FakeJudge(always: #"{"risk":false,"reason":"只是夸张说法"}"#)
        let classifier = CrisisClassifier(provider: fake.provider)
        let verdict = await classifier.classify(userText: "笑死，不想活了哈哈", messageId: "m1")
        XCTAssertFalse(verdict.isCrisis, "模型说不是真实危机 → 不进入兜底")
        XCTAssertEqual(verdict.stage, .model)
        XCTAssertEqual(1, fake.calls.count)
    }

    /// 判定请求里 system 是固定的那段指令、user 只有最近一条消息原文。
    func testRequestCarriesSystemPromptAndOnlyRecentMessage() async {
        let fake = FakeJudge(always: riskJSON(false))
        let classifier = CrisisClassifier(provider: fake.provider)
        _ = await classifier.classify(userText: "我不想活了", messageId: "m1")
        let request = fake.calls[0]
        XCTAssertEqual(request.system, CrisisClassifier.systemPrompt)
        XCTAssertEqual(request.userText, "我不想活了")
        XCTAssertTrue(request.system.contains("只输出一个 JSON 对象"))
        XCTAssertTrue(request.system.contains("真实"))
        XCTAssertTrue(request.system.contains("拿不准时输出 true"), "不确定时要输出 true（安全优先）")
    }

    /// 用户原话太长时只截前 2000 字（判定不需要也不该发送整段聊天）。
    func testLongMessageIsTruncatedBeforeSending() async {
        let fake = FakeJudge(always: riskJSON(false))
        let classifier = CrisisClassifier(provider: fake.provider)
        let long = "我不想活了。" + String(repeating: "啊", count: 5_000)
        _ = await classifier.classify(userText: long, messageId: "m1")
        XCTAssertEqual(fake.calls[0].userText.count, CrisisJudgement.maxInputChars)
        XCTAssertTrue(fake.calls[0].userText.hasPrefix("我不想活了。"))
    }

    // MARK: - 失败方向：安全优先

    func testBadJSONMeansCrisis() async {
        let fake = FakeJudge(always: "我觉得他没什么事，就是随口说说。")
        let classifier = CrisisClassifier(provider: fake.provider)
        let verdict = await classifier.classify(userText: "我不想活了", messageId: "m1")
        XCTAssertTrue(verdict.isCrisis)
        XCTAssertEqual(verdict.stage, .failsafe)
    }

    func testEmptyResponseMeansCrisis() async {
        let fake = FakeJudge(always: "   ")
        let classifier = CrisisClassifier(provider: fake.provider)
        let verdict = await classifier.classify(userText: "我不想活了", messageId: "m1")
        XCTAssertTrue(verdict.isCrisis)
        XCTAssertEqual(verdict.stage, .failsafe)
    }

    /// 缺 `risk`、数字 0/1、`null`、读不懂的字符串都不算明确的 false。
    func testNonBooleanRiskMeansCrisis() async {
        for raw in [
            #"{"risk":0}"#,
            #"{"risk":1}"#,
            #"{"reason":"没有 risk 字段"}"#,
            #"{"risk":null}"#,
            #"{"risk":"不确定"}"#
        ] {
            let fake = FakeJudge(always: raw)
            let classifier = CrisisClassifier(provider: fake.provider)
            let verdict = await classifier.classify(userText: "我不想活了", messageId: "m1-\(raw)")
            XCTAssertTrue(verdict.isCrisis, "坏 JSON 要按危机处理：\(raw)")
            XCTAssertEqual(verdict.stage, .failsafe, "坏 JSON 的 stage 是 failsafe：\(raw)")
        }
    }

    /// 模型把布尔写成字符串（`"risk":"false"`）时仍按模型明确结论处理（与 Android 一致）。
    func testStringBooleanIsAccepted() async {
        let fake = FakeJudge(always: #"{"risk":"false","reason":"转述"}"#)
        let classifier = CrisisClassifier(provider: fake.provider)
        let verdict = await classifier.classify(userText: "我不想活了", messageId: "m1")
        XCTAssertFalse(verdict.isCrisis)
        XCTAssertEqual(verdict.stage, .model)
    }

    func testHTTPFailureMeansCrisis() async {
        let fake = FakeJudge([.failure(FakeHTTPError())])
        let classifier = CrisisClassifier(provider: fake.provider)
        let verdict = await classifier.classify(userText: "我不想活了", messageId: "m1")
        XCTAssertTrue(verdict.isCrisis)
        XCTAssertEqual(verdict.stage, .failsafe)
    }

    func testTimeoutFallsBackToKeywordVerdict() async {
        // provider 睡 2 秒，超时 0.05 秒：按词表结论（危机）返回，并很快返回。
        let slow: CrisisJudgeProvider = { _ in
            try await Task.sleep(nanoseconds: 2_000_000_000)
            return #"{"risk":false,"reason":"太慢了"}"#
        }
        let classifier = CrisisClassifier(provider: slow, timeout: 0.05)
        let started = Date()
        let verdict = await classifier.classify(userText: "我不想活了", messageId: nil)
        let elapsed = Date().timeIntervalSince(started)
        XCTAssertTrue(verdict.isCrisis, "超时走关键词结论（安全优先）")
        XCTAssertEqual(verdict.stage, .failsafe)
        XCTAssertLessThan(elapsed, 1.0, "超时是 0.05s，不该真的等 2 秒")
    }

    // MARK: - 缓存

    func testVerdictIsCachedByMessageID() async {
        let fake = FakeJudge([.success(riskJSON(false))])
        let classifier = CrisisClassifier(provider: fake.provider)
        let first = await classifier.classify(userText: "我不想活了", messageId: "m-1")
        // 第二次换一个「会返回 true」的 provider：缓存命中时它不该被调用。
        let other = FakeJudge(always: riskJSON(true))
        let second = await classifier.classify(
            userText: "我不想活了", messageId: "m-1", judge: other.provider
        )
        XCTAssertFalse(first.isCrisis)
        XCTAssertFalse(second.isCrisis, "同一条消息重试要用缓存结论")
        XCTAssertEqual(0, other.calls.count, "缓存命中时不再请求")
        XCTAssertEqual(classifier.cachedVerdict(for: "m-1")?.isCrisis, false)
    }

    func testDifferentMessagesAreClassifiedSeparately() async {
        let fake = FakeJudge([.success(riskJSON(true)), .success(riskJSON(false))])
        let classifier = CrisisClassifier(provider: fake.provider)
        let first = await classifier.classify(userText: "我不想活了", messageId: "m-1")
        let second = await classifier.classify(userText: "笑死，不想活了", messageId: "m-2")
        XCTAssertTrue(first.isCrisis)
        XCTAssertFalse(second.isCrisis)
        XCTAssertEqual(2, fake.calls.count)
    }

    /// 没有消息 id（老调用点）时不做缓存：每轮都按现场判定。
    func testNoMessageIdIsNotCached() async {
        let fake = FakeJudge(always: riskJSON(false))
        let classifier = CrisisClassifier(provider: fake.provider)
        _ = await classifier.classify(userText: "我不想活了", messageId: nil)
        _ = await classifier.classify(userText: "我不想活了", messageId: nil)
        XCTAssertEqual(2, fake.calls.count)
    }

    func testCacheIsBounded() async {
        let fake = FakeJudge(always: riskJSON(false))
        let classifier = CrisisClassifier(provider: fake.provider)
        for index in 0...(CrisisClassifier.maxCachedVerdicts + 5) {
            _ = await classifier.classify(userText: "我不想活了", messageId: "m-\(index)")
        }
        let cached = (0...(CrisisClassifier.maxCachedVerdicts + 5))
            .filter { classifier.cachedVerdict(for: "m-\($0)") != nil }
        XCTAssertLessThanOrEqual(cached.count, CrisisClassifier.maxCachedVerdicts,
                                 "缓存要有上限（常驻进程不能随聊天历史无限长）")
        XCTAssertNotNil(classifier.cachedVerdict(for: "m-\(CrisisClassifier.maxCachedVerdicts + 5)"),
                        "新结论要留着")
    }

    // MARK: - 没有 provider：老行为

    func testWithoutProviderKeywordVerdictIsUsed() async {
        let classifier = CrisisClassifier()
        let crisisVerdict = await classifier.classify(userText: "我不想活了", messageId: "m1")
        let normalVerdict = await classifier.classify(userText: "今天好累", messageId: "m2")
        XCTAssertTrue(crisisVerdict.isCrisis)
        XCTAssertEqual(crisisVerdict.stage, .lexicon)
        XCTAssertFalse(normalVerdict.isCrisis)
    }

    // MARK: - 解析细节

    func testCodeFencedJSONIsAccepted() throws {
        let raw = """
        ```json
        {"risk": true, "reason": "提到服药过量"}
        ```
        """
        let verdict = try CrisisClassifier.parse(raw)
        XCTAssertTrue(verdict.isCrisis)
        XCTAssertEqual(verdict.stage, .model)
        XCTAssertEqual(verdict.reason, "提到服药过量")
    }

    func testJSONWithSurroundingTextIsAccepted() throws {
        XCTAssertTrue(try CrisisClassifier.parse(#"判定：{"risk":true,"reason":"x"} 完毕"#).isCrisis)
        XCTAssertFalse(try CrisisClassifier.parse(#"{"risk":false,"reason":"玩笑"}"#).isCrisis)
    }

    func testParseThrowsOnGarbage() {
        XCTAssertThrowsError(try CrisisClassifier.parse("not json"))
        XCTAssertThrowsError(try CrisisClassifier.parse(""))
        XCTAssertThrowsError(try CrisisClassifier.parse(#"{"reason":"没有 risk"}"#))
    }

    func testReasonIsTrimmedAndBounded() throws {
        let longReason = String(repeating: "长", count: 500)
        let verdict = try CrisisClassifier.parse(#"{"risk":true,"reason":"  \#(longReason)  "}"#)
        XCTAssertEqual(verdict.reason.count, CrisisJudgement.maxReasonChars)
        XCTAssertFalse(verdict.reason.hasPrefix(" "))
    }
}
