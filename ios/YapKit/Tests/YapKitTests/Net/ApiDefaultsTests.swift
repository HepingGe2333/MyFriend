import Foundation
import XCTest
@testable import YapKit

/// 模型 API 的默认参数（`spec/api-defaults/README.md`，0.13）。
///
/// 三件事必须被钉住：
/// 1. 请求体里 `temperature=0.75`、`max_tokens=16384`、`top_p=1.0`、惩罚 0；
/// 2. 配置里显式填了就按配置（用户的 0.3 不能被悄悄改回 0.75）；
/// 3. 上下文预算按 1M 上限算（而不是继续用某个写死的 128K/28K 当窗口）。
final class ApiDefaultsTests: XCTestCase {

    private func config(
        temperature: Double? = nil,
        topP: Double? = nil,
        maxOutputTokens: Int? = nil,
        frequencyPenalty: Double? = nil,
        presencePenalty: Double? = nil
    ) -> ApiConfigEntity {
        ApiConfigEntity(
            id: "cfg-1", name: "默认值", baseUrl: "https://api.example.com", model: "deepseek-chat",
            temperature: temperature, topP: topP, maxOutputTokens: maxOutputTokens,
            frequencyPenalty: frequencyPenalty, presencePenalty: presencePenalty
        )
    }

    private func requestBody(_ params: InferenceParams) throws -> [String: Any] {
        let data = try OpenAICompatibleClient.encodeRequest(
            model: "deepseek-chat",
            messages: [WireMessage(role: "user", content: "在吗")],
            params: params,
            stream: false
        )
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    // MARK: - 常量

    func testDefaultsMatchSpec() {
        XCTAssertEqual(ApiDefaults.temperature, 0.75)
        XCTAssertEqual(ApiDefaults.topP, 1.0)
        XCTAssertEqual(ApiDefaults.maxOutputTokens, 16_384)
        XCTAssertEqual(ApiDefaults.contextWindowTokens, 1_000_000)
        XCTAssertEqual(ApiDefaults.frequencyPenalty, 0)
        XCTAssertEqual(ApiDefaults.presencePenalty, 0)
    }

    // MARK: - 请求体

    /// 没填任何参数的配置：请求体里必须有 0.75 / 16384。
    func testRequestBodyCarriesDefaultTemperatureAndMaxTokens() throws {
        let body = try requestBody(config().inferenceParams())
        XCTAssertEqual(body["temperature"] as? Double, 0.75)
        XCTAssertEqual(body["max_tokens"] as? Int, 16_384)
        XCTAssertEqual(body["top_p"] as? Double, 1.0)
        XCTAssertEqual(body["frequency_penalty"] as? Double, 0)
        XCTAssertEqual(body["presence_penalty"] as? Double, 0)
        XCTAssertEqual(body["stream"] as? Bool, false)
    }

    /// 工具回路用的是同一份 `config.inferenceParams()`：每轮都是 16384，不会掉回服务端默认。
    func testToolLoopRoundsUseTheSameDefaults() throws {
        let params = config().inferenceParams()
        for _ in 0..<3 {
            let body = try requestBody(params)
            XCTAssertEqual(body["max_tokens"] as? Int, ApiDefaults.maxOutputTokens)
            XCTAssertEqual(body["temperature"] as? Double, ApiDefaults.temperature)
        }
    }

    /// 配置显式值优先（0 也要保留：0 是合法温度，不能被 `??` 吃掉）。
    func testExplicitConfigValuesWin() throws {
        let body = try requestBody(config(
            temperature: 0.3, topP: 0.8, maxOutputTokens: 256,
            frequencyPenalty: 0.5, presencePenalty: 0.6
        ).inferenceParams())
        XCTAssertEqual(body["temperature"] as? Double, 0.3)
        XCTAssertEqual(body["top_p"] as? Double, 0.8)
        XCTAssertEqual(body["max_tokens"] as? Int, 256)
        XCTAssertEqual(body["frequency_penalty"] as? Double, 0.5)
        XCTAssertEqual(body["presence_penalty"] as? Double, 0.6)

        let zero = try requestBody(config(temperature: 0).inferenceParams())
        XCTAssertEqual(zero["temperature"] as? Double, 0, "显式的 0 不能被默认值覆盖")
    }

    /// `InferenceParams` 本身仍是「nil = 不发这个字段」（测试/特殊调用点用）。
    /// 默认值只在 `ApiConfigEntity.inferenceParams()` 这一层补齐——这样 `encodeRequest`
    /// 的行为不变，报文差异完全来自「客户端决定发什么」。
    func testEmptyInferenceParamsStillOmitsFields() throws {
        let body = try requestBody(InferenceParams())
        XCTAssertNil(body["temperature"])
        XCTAssertNil(body["max_tokens"])
        XCTAssertNil(body["top_p"])
    }

    // MARK: - 上下文预算

    func testContextWindowCapIsOneMillion() {
        XCTAssertEqual(ApiDefaults.contextTokenLimit(), 1_000_000)
        XCTAssertEqual(ApiDefaults.contextTokenLimit(configured: nil), 1_000_000)
        // 显式值优先，但不超过 1M 上限。
        XCTAssertEqual(ApiDefaults.contextTokenLimit(configured: 200_000), 200_000)
        XCTAssertEqual(ApiDefaults.contextTokenLimit(configured: 2_000_000), 1_000_000)
        XCTAssertEqual(ApiDefaults.contextTokenLimit(configured: 0), 1_000_000, "0 当作没配")
    }

    /// 预算换算：先扣掉输出额度，再按 2 字符/token 折算。
    func testHistoryCharBudgetSubtractsOutputTokens() {
        XCTAssertEqual(
            ApiDefaults.historyCharBudget(contextTokens: 1_000_000),
            Int((Double(1_000_000 - 16_384) * 2).rounded(.down))
        )
        // 窗口比输出还小：预算为 0（调用方会退回默认值），不能变成负数。
        XCTAssertEqual(ApiDefaults.historyCharBudget(contextTokens: 8_000), 0)
    }

    /// 聊天实际用的预算：默认 1M 时仍是产品上限 28 000 字（1M 只是「最多」）；
    /// 窗口配小了才真的收紧。
    func testPromptBuilderBudgetUsesWindowButKeepsProductCap() {
        XCTAssertEqual(PromptBuilder.contextTokenBudget(), 1_000_000)
        XCTAssertEqual(
            PromptBuilder.historyCharBudget(contextTokens: ApiDefaults.contextWindowTokens),
            PromptBuilder.contextCharBudget
        )
        XCTAssertEqual(
            PromptBuilder.historyCharBudget(contextTokens: 20_000),
            Int((Double(20_000 - 16_384) * 2).rounded(.down))
        )
        XCTAssertLessThan(
            PromptBuilder.historyCharBudget(contextTokens: 20_000),
            PromptBuilder.contextCharBudget
        )
    }
}
