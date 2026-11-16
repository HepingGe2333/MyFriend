import Foundation
import XCTest
@testable import YapKit

/// 定价与用量：三种 usage 形状、前缀匹配、取整、月初边界、预算判定顺序。
///
/// 这些数字是**钱**：算错不会崩，只会让「这个月花了多少」悄悄不对。
/// 所以它们全都被钉成纯函数的输入输出。
final class ApiCostTests: XCTestCase {

    // MARK: - 定价表

    func testDeepSeekPricingComesFirst() {
        XCTAssertEqual(
            ApiCost.basePriceFor("deepseek-chat"),
            ModelPrice(inputMicroUsdPerMillion: 270_000, outputMicroUsdPerMillion: 1_100_000)
        )
        // 长前缀优先：reasoner 不能被 "deepseek" 抢先。
        XCTAssertEqual(
            ApiCost.basePriceFor("deepseek-reasoner"),
            ModelPrice(inputMicroUsdPerMillion: 550_000, outputMicroUsdPerMillion: 2_190_000)
        )
        XCTAssertEqual(
            ApiCost.basePriceFor("deepseek-ai/deepseek-chat"),
            ModelPrice(inputMicroUsdPerMillion: 270_000, outputMicroUsdPerMillion: 1_100_000)
        )
    }

    func testLongestPrefixWins() {
        // gpt-4o-mini 不能被 gpt-4o 抢先。
        XCTAssertEqual(ApiCost.basePriceFor("gpt-4o-mini").inputMicroUsdPerMillion, 150_000)
        XCTAssertEqual(ApiCost.basePriceFor("gpt-4o").inputMicroUsdPerMillion, 2_500_000)
        XCTAssertEqual(ApiCost.basePriceFor("openai/gpt-4o-mini").inputMicroUsdPerMillion, 150_000)
        XCTAssertEqual(ApiCost.basePriceFor("claude-3-5-haiku-latest").inputMicroUsdPerMillion, 800_000)
        XCTAssertEqual(ApiCost.basePriceFor("claude-3-opus").outputMicroUsdPerMillion, 75_000_000)
    }

    func testUnknownModelFallsBackToConservativePrice() {
        XCTAssertEqual(ApiCost.basePriceFor("some-new-model"), ApiCost.UNKNOWN_PRICE)
        XCTAssertEqual(ApiCost.UNKNOWN_PRICE.inputMicroUsdPerMillion, 1_500_000)
        XCTAssertEqual(ApiCost.UNKNOWN_PRICE.outputMicroUsdPerMillion, 6_000_000)
        XCTAssertEqual(ApiCost.basePriceFor(nil), ApiCost.UNKNOWN_PRICE)
        XCTAssertEqual(ApiCost.basePriceFor("   "), ApiCost.UNKNOWN_PRICE)
        // 大小写与空白都不影响
        XCTAssertEqual(ApiCost.basePriceFor("  DeepSeek-Chat  ").inputMicroUsdPerMillion, 270_000)
    }

    /// 1.9：用户自定义单价整段删除（§五），内置表是唯一来源。
    func testPriceComesOnlyFromBuiltInTable() {
        let price = ApiCost.priceFor("deepseek-chat")
        XCTAssertEqual(price.inputMicroUsdPerMillion, 270_000)
        XCTAssertEqual(price.outputMicroUsdPerMillion, 1_100_000)
    }

    func testCostRounding() {
        // $0.27/百万 × 1M tokens = $0.27 = 270_000 micro-USD
        let price = ModelPrice(inputMicroUsdPerMillion: 270_000, outputMicroUsdPerMillion: 1_100_000)
        XCTAssertEqual(ApiCost.estimateMicroUsd(price: price, usage: TokenUsage(promptTokens: 1_000_000)),
                       270_000)
        // 1 token 的成本四舍五入到 0（0.27 micro）
        XCTAssertEqual(ApiCost.estimateMicroUsd(price: price, usage: TokenUsage(promptTokens: 1)), 0)
        // 2 tokens = 0.54 → 1（四舍五入）
        XCTAssertEqual(ApiCost.estimateMicroUsd(price: price, usage: TokenUsage(promptTokens: 2)), 1)
        // 负数当 0，不抛异常
        XCTAssertEqual(ApiCost.estimateMicroUsd(price: price, usage: TokenUsage(promptTokens: -5)), 0)
    }

    // MARK: - usage 三种形状

    func testParseOpenAiUsage() {
        let raw = #"{"usage":{"prompt_tokens":120,"completion_tokens":34}}"#
        XCTAssertEqual(ApiCost.parseUsage(rawBody: raw), TokenUsage(promptTokens: 120, completionTokens: 34))
    }

    func testParseGeminiUsage() {
        let raw = #"{"usageMetadata":{"promptTokenCount":77,"candidatesTokenCount":21,"totalTokenCount":98}}"#
        XCTAssertEqual(ApiCost.parseUsage(rawBody: raw), TokenUsage(promptTokens: 77, completionTokens: 21))
    }

    func testParseClaudeUsage() {
        let raw = #"{"usage":{"input_tokens":11,"output_tokens":22}}"#
        XCTAssertEqual(ApiCost.parseUsage(rawBody: raw), TokenUsage(promptTokens: 11, completionTokens: 22))
    }

    func testParseUsageToleratesStringsAndMissingFields() {
        XCTAssertEqual(
            ApiCost.parseUsage(rawBody: #"{"usage":{"prompt_tokens":"9","completion_tokens":3.0}}"#),
            TokenUsage(promptTokens: 9, completionTokens: 3)
        )
        // 只有一边有值
        XCTAssertEqual(ApiCost.parseUsage(rawBody: #"{"usage":{"completion_tokens":5}}"#),
                       TokenUsage(promptTokens: 0, completionTokens: 5))
        // 认不出来一律 0，绝不抛异常
        XCTAssertEqual(ApiCost.parseUsage(rawBody: nil), .zero)
        XCTAssertEqual(ApiCost.parseUsage(rawBody: "   "), .zero)
        XCTAssertEqual(ApiCost.parseUsage(rawBody: "not json"), .zero)
        XCTAssertEqual(ApiCost.parseUsage(rawBody: #"{"usage":{"prompt_tokens":-7}}"#),
                       TokenUsage(promptTokens: 0, completionTokens: 0))
    }

    /// 服务端不报 usage 时的估算：中文 0.7 token/字，英文 4 字符/token。
    func testEstimateTokens() {
        XCTAssertEqual(ApiCost.estimateTokens(""), 0)
        XCTAssertEqual(ApiCost.estimateTokens("   "), 0)
        XCTAssertEqual(ApiCost.estimateTokens("你好"), 1)          // 2 × 0.7 = 1.4 → 1
        XCTAssertEqual(ApiCost.estimateTokens("你好世界你好世界"), 5) // 8 × 0.7 = 5.6 → 5
        XCTAssertEqual(ApiCost.estimateTokens("abcdefgh"), 2)      // 8 / 4 = 2
        XCTAssertEqual(ApiCost.estimateTokens("a"), 1)             // 至少 1
    }

    // MARK: - 月初边界

    func testMonthStartAndNextMonth() throws {
        let zone = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        // 2025-03-15 12:34:56 +08:00
        let now = try XCTUnwrap(calendar.date(from: DateComponents(
            year: 2025, month: 3, day: 15, hour: 12, minute: 34, second: 56
        )))
        let nowMillis = Int64((now.timeIntervalSince1970 * 1000).rounded())
        let start = ApiCost.monthStart(nowMillis, timeZone: zone)
        let startDate = Date(timeIntervalSince1970: Double(start) / 1000)
        XCTAssertEqual(calendar.component(.day, from: startDate), 1)
        XCTAssertEqual(calendar.component(.month, from: startDate), 3)
        XCTAssertEqual(calendar.component(.hour, from: startDate), 0)

        let next = ApiCost.nextMonthStart(nowMillis, timeZone: zone)
        let nextDate = Date(timeIntervalSince1970: Double(next) / 1000)
        XCTAssertEqual(calendar.component(.month, from: nextDate), 4)
        XCTAssertEqual(calendar.component(.day, from: nextDate), 1)
    }

    /// 12 月跨年：下个月是次年 1 月，不能算成 13 月。
    func testNextMonthStartAcrossYearBoundary() throws {
        let zone = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let now = try XCTUnwrap(calendar.date(from: DateComponents(year: 2025, month: 12, day: 31, hour: 23)))
        let next = ApiCost.nextMonthStart(Int64((now.timeIntervalSince1970 * 1000).rounded()), timeZone: zone)
        let nextDate = Date(timeIntervalSince1970: Double(next) / 1000)
        XCTAssertEqual(calendar.component(.year, from: nextDate), 2026)
        XCTAssertEqual(calendar.component(.month, from: nextDate), 1)
    }

    // MARK: - 金额展示

    // 1.9 删掉了预算判定与额度输入框：没有额度、没有拦截（§五）。
    // 金额这一侧只剩「记账时估一个成本」与这个调试用的格式化函数。

    func testFormatUsd() {
        XCTAssertEqual(ApiCost.formatUsd(0), "$0.00")
        XCTAssertEqual(ApiCost.formatUsd(-5), "$0.00")
        XCTAssertEqual(ApiCost.formatUsd(5_000), "<$0.01")
        XCTAssertEqual(ApiCost.formatUsd(420_000), "$0.42")
        XCTAssertEqual(ApiCost.formatUsd(1_000_000), "$1.00")
        XCTAssertEqual(ApiCost.formatUsd(1_234_500), "$1.23")
    }

}
