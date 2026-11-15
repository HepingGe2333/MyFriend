import Foundation
import XCTest
@testable import MyFriendKit

/// 话风总结 / 世界补全：正常输出、坏 JSON、兜底、隐私（原日志脱敏）。
///
/// 全部用假模型（`FakeCompletionClient`），不联网、不读环境变量；时间与配置都写死。
final class PersonaGuidanceLearningTests: XCTestCase {

    private var logs: [String] = []

    private func config(key: String? = "sk-test") -> CompletionConfig {
        CompletionConfig(
            id: "config-1", name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat", apiKey: key
        )
    }

    private func summarizer(_ response: String) -> SpeechStyleSummarizer {
        logs = []
        return SpeechStyleSummarizer(
            completion: FakeCompletionClient(text: response),
            log: { [weak self] line in self?.logs.append(line) }
        )
    }

    private func expander(_ response: String) -> WorldPresetExpander {
        logs = []
        return WorldPresetExpander(
            completion: FakeCompletionClient(text: response),
            log: { [weak self] line in self?.logs.append(line) }
        )
    }

    private let transcript = """
    林深：在忙，晚点说
    林深：这个方案又被打回来了，烦
    林深：你先睡吧，不用等我
    林深：在忙，晚点说
    """

    // MARK: - 话风：正常

    func testSummarizeUsesModelOutput() async {
        let json = """
        ```json
        {"tone":"简短直接，偏冷淡务实","phrases":["在忙，晚点说","你先睡吧"],"habits":["句子很短"],"avoid":["长篇大论"]}
        ```
        """
        let result = await summarizer(json).summarize(
            transcript: transcript, config: config(), model: "deepseek-chat"
        )
        XCTAssertTrue(result.usedModel)
        XCTAssertEqual(result.style.tone, "简短直接，偏冷淡务实")
        XCTAssertEqual(result.style.phrases, ["在忙，晚点说", "你先睡吧"])
        XCTAssertEqual(result.style.source, .model)
    }

    // MARK: - 话风：坏 JSON → 本地兜底

    func testSummarizeFallsBackOnBadJson() async {
        let result = await summarizer("这不是 JSON，只是一段解释").summarize(
            transcript: transcript, config: config(), model: ""
        )
        XCTAssertFalse(result.usedModel)
        XCTAssertEqual(result.style.source, .heuristic)
        XCTAssertTrue(result.style.phrases.contains("在忙，晚点说"))
    }

    func testSummarizeFallsBackWithoutApiKey() async {
        let result = await summarizer(#"{"tone":"不该用到"}"#).summarize(
            transcript: transcript, config: config(key: nil), model: ""
        )
        XCTAssertFalse(result.usedModel)
        XCTAssertEqual(result.style.source, .heuristic, "没 Key 时完全走本地规则")
        XCTAssertEqual(result.message, "没有可用的 API 配置")
    }

    func testSummarizeFallsBackOnEmptyTranscript() async {
        let result = await summarizer(#"{"tone":"不该用到"}"#).summarize(
            transcript: "   ", config: config(), model: ""
        )
        XCTAssertFalse(result.usedModel)
        XCTAssertTrue(result.style.isEmpty)
    }

    func testSummarizeFallsBackWhenRequestFails() async {
        logs = []
        let failing = FakeCompletionClient(
            fallback: .failure(CompletionFailure("网络不可用"))
        )
        let summarizer = SpeechStyleSummarizer(completion: failing, log: { [weak self] in self?.logs.append($0) })
        let result = await summarizer.summarize(
            transcript: transcript, config: config(), model: ""
        )
        XCTAssertFalse(result.usedModel)
        XCTAssertEqual(result.style.source, .heuristic)
        XCTAssertEqual(result.message, "总结请求失败")
    }

    /// 模型给空的（合法 JSON 但没内容）：也走兜底，不能把界面清空。
    func testSummarizeFallsBackWhenModelReturnsEmptyStyle() async {
        let result = await summarizer("{}").summarize(
            transcript: transcript, config: config(), model: ""
        )
        XCTAssertFalse(result.usedModel)
        XCTAssertFalse(result.style.isEmpty)
    }

    // MARK: - 隐私（§3.3）

    /// 日志里绝不能出现聊天记录原文（只允许字数、条数、失败摘要）。
    func testLogsNeverContainTranscriptText() async {
        _ = await summarizer("坏输出").summarize(transcript: transcript, config: config(), model: "")
        for line in logs {
            XCTAssertFalse(line.contains("在忙，晚点说"), "日志里出现了聊天原文：\(line)")
            XCTAssertFalse(line.contains("这个方案又被打回来了"), "日志里出现了聊天原文：\(line)")
        }
        XCTAssertTrue(logs.contains { $0.contains("本地兜底") }, "应当留下一条「走了兜底」的痕迹")
        // 成功路径也一样：只记条数。
        _ = await summarizer(#"{"tone":"简短","phrases":["在忙，晚点说"]}"#)
            .summarize(transcript: transcript, config: config(), model: "")
        for line in logs {
            XCTAssertFalse(line.contains("在忙，晚点说"), "日志里出现了聊天原文：\(line)")
        }
    }

    /// 总结结果里不含原文以外的内容（phrases 是短句，不是整段记录），且能被 JSON 序列化落库。
    func testSummaryResultIsPersistableAndContainsNoRawTranscript() async throws {
        let json = #"{"tone":"简短","phrases":["在忙，晚点说"],"habits":[],"avoid":[]}"#
        let result = await summarizer(json).summarize(
            transcript: transcript, config: config(), model: ""
        )
        let stored = try XCTUnwrap(result.style.jsonString())
        XCTAssertTrue(stored.contains("在忙，晚点说"))
        XCTAssertFalse(stored.contains("这个方案又被打回来了"), "整段记录不该被带进风格 JSON")
    }

    // MARK: - 世界补全

    func testExpandUsesModelOutput() async {
        let json = """
        {"worldName":"临港新城·云筑建筑事务所","era":"当代，中国沿海新一线城市，2026 年",
         "description":"事务所竞争激烈，甲方频繁改图。",
         "schedule":[{"day":"工作日","start":"09:00","end":"18:30","activity":"画图、审图"}],
         "relationships":[{"name":"母亲","relation":"母亲","note":"每周三晚打电话"}],
         "obligations":["按节点交图"]}
        """
        let result = await expander(json).expand(
            personaSummary: "林深，28岁，建筑事务所项目负责人。", config: config(), model: ""
        )
        XCTAssertTrue(result.usedModel)
        XCTAssertEqual(result.preset.worldName, "临港新城·云筑建筑事务所")
        XCTAssertEqual(result.preset.schedule.count, 1)
        XCTAssertEqual(result.preset.obligations, ["按节点交图"])
    }

    func testExpandFallsBackOnBadJson() async {
        let result = await expander("我给你写一份世界设定：……").expand(
            personaSummary: "林深，28岁，建筑事务所项目负责人。", config: config(), model: ""
        )
        XCTAssertFalse(result.usedModel)
        // 兜底只放用户的描述，不编日程。
        XCTAssertEqual(result.preset.description, "林深，28岁，建筑事务所项目负责人。")
        XCTAssertTrue(result.preset.schedule.isEmpty)
        XCTAssertTrue(result.preset.obligations.isEmpty)
    }

    func testExpandFallsBackWithoutApiKey() async {
        let result = await expander("{}").expand(
            personaSummary: "开旧书店的人。", config: config(key: nil), model: ""
        )
        XCTAssertFalse(result.usedModel)
        XCTAssertEqual(result.preset.description, "开旧书店的人。")
        XCTAssertEqual(result.message, "没有可用的 API 配置")
    }

    func testExpandFallsBackOnEmptySummary() async {
        let result = await expander("{}").expand(personaSummary: "  ", config: config(), model: "")
        XCTAssertFalse(result.usedModel)
        XCTAssertTrue(result.preset.isEmpty)
    }

    /// 补全日志也不含用户原文。
    func testExpandLogsNeverContainPersonaSummary() async {
        _ = await expander("坏输出").expand(
            personaSummary: "林深，28岁，建筑事务所项目负责人。", config: config(), model: ""
        )
        for line in logs {
            XCTAssertFalse(line.contains("林深"), "日志里出现了原文：\(line)")
        }
    }

    /// 两段提示词与规范 §6.1 / §6.2 逐字一致（谁改了措辞，这条先红）。
    func testPromptsMatchSpecVerbatim() {
        XCTAssertTrue(SpeechStyleSummarizer.systemPrompt.hasPrefix("你是对话风格分析器。"))
        XCTAssertTrue(SpeechStyleSummarizer.systemPrompt.contains("只输出 JSON 对象，不要解释、不要 markdown"))
        XCTAssertTrue(SpeechStyleSummarizer.systemPrompt.contains("不要输出人名，不要评价关系"))
        XCTAssertTrue(WorldPresetExpander.systemPrompt.hasPrefix("你是角色设定助手。"))
        XCTAssertTrue(WorldPresetExpander.systemPrompt.contains("不要写剧情大纲，不要写用户"))
        XCTAssertTrue(WorldPresetExpander.systemPrompt.contains("不确定的地方留空数组"))
    }
}
