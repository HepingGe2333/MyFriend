import XCTest
@testable import YapKit

/// 0.15 工具协议的两组补充：
/// 1. **XML 形状的工具块**（评测里模型实际输出的 `<tool_call>` / `<tool_code>` / `<tool>`）
///    必须被当成协议块解析，并且**绝不显示给用户**；
/// 2. `memorize` 的协议形状与「必须调用 remember / memorize」的提示词强调。
final class ToolProtocolXMLTests: XCTestCase {

    // MARK: - XML 工具块

    func testToolCallTagIsParsedAndRemovedFromBody() {
        let raw = """
        我帮你查一下。
        <tool_call>{"name":"web_search","arguments":{"query":"今天天气"}}</tool_call>
        """
        let (body, calls) = ToolProtocol.extract(raw)
        XCTAssertEqual(body, "我帮你查一下。", "XML 标签与 JSON 都不能露给用户")
        XCTAssertEqual(calls.count, 1)
        XCTAssertEqual(calls.first?.name, "web_search")
        XCTAssertEqual(calls.first?.argument("query"), "今天天气")
    }

    func testToolCodeAndToolTagsAreParsed() {
        for tag in ["tool_code", "tool"] {
            let raw = "<\(tag)>{\"name\":\"think\",\"arguments\":{\"question\":\"要不要回\"}}</\(tag)>"
            let (body, calls) = ToolProtocol.extract(raw)
            XCTAssertEqual(body, "", "\(tag)：整段都该被移除")
            XCTAssertEqual(calls.map(\.name), ["think"], "\(tag) 没被解析")
        }
    }

    func testXmlTagWithSurroundingProseKeepsOnlyProse() {
        let raw = """
        好。
        <tool_call>
        现在查一下 {"name":"web_fetch","arguments":{"url":"https://example.com"}}
        </tool_call>
        等我。
        """
        let (body, calls) = ToolProtocol.extract(raw)
        XCTAssertFalse(body.contains("<tool_call"), "标签不能进正文：\(body)")
        XCTAssertFalse(body.contains("\"name\""), "JSON 不能进正文：\(body)")
        XCTAssertTrue(body.contains("好。"))
        XCTAssertTrue(body.contains("等我。"))
        XCTAssertEqual(calls.first?.name, "web_fetch")
    }

    func testUnclosedXmlTagIsStrippedFromBody() {
        let raw = "我想想。<tool_call>{\"name\":\"think\",\"arguments\":{\"question\":\"x\"}"
        let (body, calls) = ToolProtocol.extract(raw)
        XCTAssertEqual(body, "我想想。", "没闭合的标签连同后面的半截 JSON 都不能显示")
        XCTAssertTrue(calls.isEmpty, "半截 JSON 不该被执行")
    }

    func testMixedCodeBlockAndXmlBothParsed() {
        let raw = """
        ```yap-tool
        {"name":"think","arguments":{"question":"第一步"}}
        ```
        中间一句话。
        <tool_call>{"name":"remember","arguments":{"content":"他怕冷"}}</tool_call>
        """
        let (body, calls) = ToolProtocol.extract(raw)
        XCTAssertEqual(calls.map(\.name), ["think", "remember"])
        XCTAssertEqual(body, "中间一句话。")
    }

    func testStreamingVisiblePrefixHidesXmlTag() {
        XCTAssertEqual(ToolProtocol.visiblePrefix("我查一下 <tool_call>{\"name\":"), "我查一下")
        XCTAssertEqual(ToolProtocol.visiblePrefix("正常的一句话"), "正常的一句话")
        // 大小写照认（模型有时会写 `<TOOL_CALL>`）。
        XCTAssertEqual(ToolProtocol.visiblePrefix("嗯 <TOOL_CALL>"), "嗯")
    }

    func testNonToolXmlIsNotTouched() {
        let raw = "他说 <b>不要</b> 熬夜，还有 <toolbox> 这种词。"
        let (body, calls) = ToolProtocol.extract(raw)
        XCTAssertEqual(body, raw, "普通 XML/尖括号不该被当成工具块")
        XCTAssertTrue(calls.isEmpty)
    }

    // MARK: - memorize 协议

    func testMemorizeIsInCatalogWithChineseDescription() throws {
        let spec = try XCTUnwrap(ToolCatalog.byName("memorize"))
        XCTAssertTrue(spec.description.contains("让我想想") || spec.description.contains("想不起"))
        XCTAssertTrue(spec.description.contains("会话"))
        XCTAssertFalse(spec.sideEffect.isEmpty)
        let description = ToolProtocol.describe(ToolResult(
            invocation: ToolInvocation(id: "1", name: "memorize", argumentsJson: "{}"),
            ok: true, summary: "", contentForModel: ""
        ))
        XCTAssertEqual(description.title, "（他想了想）")
    }

    func testRememberAndMemorizeAreMandatoryInCapabilityPrompt() {
        let prompt = ToolCatalog.capabilityPrompt(webEnabled: true)
        XCTAssertTrue(prompt.contains("必须调用 remember"), "要明确「只在正文说记住了不算」")
        XCTAssertTrue(prompt.contains("memorize"), "想起过去必须调用 memorize")
        XCTAssertTrue(prompt.contains("- memorize(query)："))
    }

    func testRememberDescriptionForbidsPretendingToRemember() throws {
        let spec = try XCTUnwrap(ToolCatalog.byName("remember"))
        XCTAssertTrue(spec.description.contains("必须调用"), "“记住”不能只写在正文里")
        XCTAssertTrue(spec.description.contains("不算记住"))
    }

    // MARK: - 旁白 / 舞台说明

    func testAntiAIPromptForbidsNarration() {
        let text = AntiAIPrompt.text()
        XCTAssertTrue(text.contains("旁白") || text.contains("舞台说明"))
        XCTAssertTrue(text.contains("沉默了一会儿"), "要给模型一个具体的反例")
        // 共享的七条不许被改长（与 Android / `PromptContract.AntiAi.RULES` 逐字一致）。
        XCTAssertEqual(AntiAIPrompt.rules.count, 7)
        XCTAssertFalse(AntiAIPrompt.rules.contains(AntiAIPrompt.noNarrationRule))
    }

    func testToolTagXmlInsideYapToolBlockIsNotDoubleParsed() {
        // 极端情况：代码块里又包了一层 XML —— 至少不能崩，也不能把标签漏到正文。
        let raw = """
        ```yap-tool
        <tool_call>{"name":"think","arguments":{"question":"x"}}</tool_call>
        ```
        """
        let (body, calls) = ToolProtocol.extract(raw)
        XCTAssertFalse(body.contains("<tool_call"))
        XCTAssertEqual(calls.count, 1)
        XCTAssertEqual(calls.first?.name, "think")
    }
}
