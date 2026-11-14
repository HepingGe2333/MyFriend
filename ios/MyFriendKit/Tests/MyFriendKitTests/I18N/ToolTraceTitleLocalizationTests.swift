import Foundation
import XCTest
@testable import MyFriendKit

/// 工具痕迹标题的**最终口径**（0.10.2 i18n 收尾）。
///
/// 两端（Android / iOS）同一工具的中英文案必须逐字一致，所以这一组测试做三件事：
///
/// 1. **中文侧**：按工具名构造 `ToolResult`，断言 `ToolProtocol.describe(result).title`
///    就是表里的中文。`swift test` 没有 App 的资源包，`String(localized:)` 会回落成 key，
///    而 key 就是中文原文——所以这里断言的正是「中文界面会看到什么」。
/// 2. **英文侧**：直接解析 `en.lproj/Localizable.strings`，断言同一批中文 key 的英文值
///    **逐字**等于表里的 English。这样「两端表格一致」就有了可执行的证据，而不是靠人眼。
/// 3. **模型文本未变**：`renderResultsForModel` / `toolMessageContent` 仍是中文 golden，
///    标题本地化不许顺手把提示词也翻了。
///
/// 表格的来源是 0.10.2 的跨端定稿；Android 端同名资源写的也是这些字。
final class ToolTraceTitleLocalizationTests: XCTestCase {

    /// 一行表格：工具 → 中文标题 → 英文标题。
    private struct TitleRow {
        /// 工具名（未知工具用 `"unknown_tool"`）。
        var tool: String
        var chinese: String
        var english: String
    }

    /// 按工具名走 `ToolProtocol.describe` 的九种（含未知工具）。
    private static let toolRows: [TitleRow] = [
        TitleRow(tool: ToolCatalog.think, chinese: "想了想", english: "Thought it over"),
        TitleRow(tool: ToolCatalog.webSearch, chinese: "联网搜索", english: "Web search"),
        TitleRow(tool: ToolCatalog.webFetch, chinese: "读了网页", english: "Read a page"),
        TitleRow(tool: ToolCatalog.remember, chinese: "记住了一件事", english: "Remembered something"),
        TitleRow(tool: ToolCatalog.updatePersona, chinese: "调整了自己的人设", english: "Adjusted his profile"),
        TitleRow(tool: ToolCatalog.setQuietHours, chinese: "安排了自己的作息", english: "Set his sleep hours"),
        TitleRow(tool: ToolCatalog.setMood, chinese: "心情变了", english: "Mood changed"),
        TitleRow(tool: ToolCatalog.recall, chinese: "回想了一下", english: "Recalled"),
        TitleRow(tool: ToolCatalog.deferReply, chinese: "决定先不回", english: "Decided not to reply yet"),
        // 工具名为空（模型给了一个空名字）时的兜底标题。
        //
        // 注意与 Android 的差异：Android 的 `ToolTraceTitle.forTool` 对**任何**认不出的名字都给
        // `tool_trace_unknown`；iOS 这边认不出但名字非空时显示原始工具名（`ToolProtocolTests`
        // 的 `testDescribeTitlesMatchAndroid` 已经钉住这个行为），只有名字为空才用「动作」。
        // 本轮按「不改既有行为」处理，这条差异已写进交付报告。
        TitleRow(tool: "", chinese: "动作", english: "Action")
    ]

    /// `ToolExecutor` 按**结果**分叉出来的标题（走 `ToolResult.traceTitle` 覆盖）。
    private static let branchRows: [TitleRow] = [
        TitleRow(tool: ToolCatalog.remember, chinese: "记下了（还在确认）", english: "Noted (still confirming)"),
        TitleRow(tool: ToolCatalog.remember, chinese: "记住了", english: "Remembered"),
        TitleRow(tool: ToolCatalog.remember, chinese: "又确认了一次", english: "Confirmed again"),
        TitleRow(tool: ToolCatalog.remember, chinese: "这次先不记", english: "Not saving this one"),
        TitleRow(tool: ToolCatalog.remember, chinese: "想了想要不要记", english: "Thought about whether to remember it"),
        TitleRow(tool: ToolCatalog.updatePersona, chinese: "想了想，没有改", english: "Thought it over, left it unchanged")
    ]

    private func stubResult(tool: String, traceTitle: String? = nil) -> ToolResult {
        ToolResult(
            invocation: ToolInvocation(id: "call_test", name: tool, argumentsJson: "{}"),
            ok: true,
            summary: "测试摘要",
            contentForModel: "测试正文",
            traceTitle: traceTitle
        )
    }

    // MARK: - 1. 中文侧（=`String(localized:)` 的回落值）

    func testChineseTitlesForEveryToolMatchTheFinalTable() {
        for row in Self.toolRows {
            let result = stubResult(tool: row.tool)
            XCTAssertEqual(
                ToolProtocol.describe(result).title, row.chinese,
                "工具 \(row.tool) 的标题与 0.10.2 定稿表格不一致（中文侧）"
            )
        }
        // 认不出但名字非空：iOS 显示原始工具名（与既有测试一致），不是「动作」。
        XCTAssertEqual(
            ToolProtocol.describe(stubResult(tool: "unknown_tool")).title, "unknown_tool",
            "认不出的工具名保留原文，方便排查；空的工具名才回落成「动作」"
        )
        // think 固定没有副标题：它是「想了想」，不该带上一句搜索词。
        XCTAssertNil(ToolProtocol.describe(stubResult(tool: ToolCatalog.think)).subtitle)
        // defer_reply 修掉了旧文案「这次先没说话」（英文 "Stayed quiet this time"）。
        XCTAssertEqual(
            ToolProtocol.describe(stubResult(tool: ToolCatalog.deferReply)).title, "决定先不回",
            "defer_reply 的标题必须与 ToolExecutor 的结果标题用同一条资源"
        )
    }

    func testBranchTitlesKeepTheirChineseText() {
        for row in Self.branchRows {
            let result = stubResult(tool: row.tool, traceTitle: String(localized: String.LocalizationValue(row.chinese)))
            let trace = ToolTraceItem.from(result)
            XCTAssertEqual(trace.title, row.chinese, "\(row.tool) 的分叉标题（中文侧）被改动了")
        }
        // 没有 traceTitle 覆盖时仍回落到 describe 的默认标题。
        XCTAssertEqual(ToolTraceItem.from(stubResult(tool: ToolCatalog.remember)).title, "记住了一件事")
    }

    // MARK: - 2. 英文侧（逐字对照资源）

    func testEnglishTitlesMatchTheFinalTableVerbatim() throws {
        let english = try I18NTestResources.map("en")
        for row in Self.toolRows + Self.branchRows {
            guard let value = english[row.chinese] else {
                XCTFail("en.lproj 里没有「\(row.chinese)」这条 key（\(row.tool) 的标题）")
                continue
            }
            XCTAssertEqual(
                value, row.english,
                "「\(row.chinese)」的英文值与 0.10.2 定稿表格不一致（Android 端写的是同一句）"
            )
            XCTAssertFalse(
                I18NTestResources.containsCJK(value),
                "「\(row.chinese)」的英文值里还有中文：\(value)"
            )
        }
    }

    /// 旧文案「这次先没说话」已经没人用了，资源里不许再留着（否则守卫会报死 key）。
    func testObsoleteDeferReplyTitleIsGone() throws {
        let zh = try I18NTestResources.map("zh-Hans")
        let en = try I18NTestResources.map("en")
        XCTAssertNil(zh["这次先没说话"], "zh-Hans 里还留着没人用的「这次先没说话」")
        XCTAssertNil(en["这次先没说话"], "en 里还留着没人用的「Stayed quiet this time」")
    }

    // MARK: - 3. 模型文本未变（继续中文）

    func testModelFacingToolTextStaysChinese() {
        let search = ToolResult(
            invocation: ToolInvocation(
                id: "call_1",
                name: ToolCatalog.webSearch,
                argumentsJson: #"{"query":"哈尔滨 天气"}"#
            ),
            ok: true,
            summary: "查了 3 个网页",
            contentForModel: "1. 哈尔滨天气预报\n2. 一周天气"
        )
        XCTAssertEqual(
            ToolProtocol.renderResultsForModel([search]),
            """
            【工具结果】web_search
            查询：哈尔滨 天气
            摘要：查了 3 个网页
            结果：1. 哈尔滨天气预报
            2. 一周天气
            """
        )
        XCTAssertEqual(ToolProtocol.toolMessageContent(search), "1. 哈尔滨天气预报\n2. 一周天气")

        let failed = ToolResult(
            invocation: ToolInvocation(
                id: "call_2",
                name: ToolCatalog.webFetch,
                argumentsJson: #"{"url":"https://example.com/a"}"#
            ),
            ok: false,
            summary: "读到一半断了",
            contentForModel: "",
            errorMessage: "请求超时"
        )
        XCTAssertEqual(
            ToolProtocol.renderResultsForModel([failed]),
            """
            【工具结果】web_fetch
            链接：https://example.com/a
            摘要：读到一半断了
            失败：请求超时
            """
        )
        // 内容为空时用错误原因兜底，也不许变成英文。
        XCTAssertEqual(ToolProtocol.toolMessageContent(failed), "请求超时")

        // `argumentHint` 是回灌给模型的一行中文（「问题：…」），同样不跟着界面语言走。
        let think = ToolResult(
            invocation: ToolInvocation(
                id: "call_3",
                name: ToolCatalog.think,
                argumentsJson: #"{"question":"该不该主动约他"}"#
            ),
            ok: true,
            summary: "",
            contentForModel: "想清楚了"
        )
        XCTAssertEqual(
            ToolProtocol.renderResultsForModel([think]),
            """
            【工具结果】think
            问题：该不该主动约他
            结果：想清楚了
            """
        )
    }
}
