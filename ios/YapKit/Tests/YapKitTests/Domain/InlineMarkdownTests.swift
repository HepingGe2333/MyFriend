import XCTest
@testable import YapKit

/// 行内 Markdown（移植自 Android 的 `buildInline` / `appendInline`）。
///
/// Android 端直接产出 `AnnotatedString`，这里产出结构化的 span 列表：
/// 断言的是「哪一段字带哪种修饰」，以及链接目标是不是被正确点亮的。
final class InlineMarkdownTests: XCTestCase {

    func testPlainText() {
        let spans = InlineMarkdown.parse("就说一句普通的话")
        XCTAssertEqual(1, spans.count)
        XCTAssertEqual("就说一句普通的话", spans[0].text)
        XCTAssertNil(spans[0].url)
        XCTAssertFalse(spans[0].isBold)
    }

    func testBold() {
        let spans = InlineMarkdown.parse("这句**很重要**吧")
        XCTAssertEqual(["这句", "很重要", "吧"], spans.map(\.text))
        XCTAssertEqual([false, true, false], spans.map(\.isBold))
    }

    func testItalicWithStarAndUnderscore() {
        XCTAssertEqual(["稍微", "轻一点"], InlineMarkdown.parse("*稍微*轻一点").prefix(2).map(\.text))
        let spans = InlineMarkdown.parse("_斜体_")
        XCTAssertEqual(1, spans.count)
        XCTAssertTrue(spans[0].isItalic)
        XCTAssertEqual("斜体", spans[0].text)
    }

    func testInlineCode() {
        let spans = InlineMarkdown.parse("用 `Compose` 画")
        XCTAssertEqual(["用 ", "Compose", " 画"], spans.map(\.text))
        XCTAssertEqual([false, true, false], spans.map(\.isCode))
    }

    func testStrikethrough() {
        let spans = InlineMarkdown.parse("~~删掉~~")
        XCTAssertEqual(1, spans.count)
        XCTAssertTrue(spans[0].isStrikethrough)
        XCTAssertEqual("删掉", spans[0].text)
    }

    func testInlineUrlIsHighlighted() {
        let spans = InlineMarkdown.parse("看 https://example.com/x 吧")
        let link = spans.first { $0.url != nil }
        XCTAssertEqual("https://example.com/x", link?.url)
        XCTAssertTrue(link?.isUnderlined == true)
    }

    func testMarkdownLinkUsesLabelAndTarget() {
        let spans = InlineMarkdown.parse("请看[官网](https://example.com/home)吧")
        let link = spans.first { $0.url != nil }
        XCTAssertEqual("官网", link?.text)
        XCTAssertEqual("https://example.com/home", link?.url)
    }

    func testMarkdownLinkWithUnsafeTargetIsUnderlinedButNotLinked() {
        let spans = InlineMarkdown.parse("[点我](javascript:alert(1))")
        XCTAssertNil(spans.first { $0.url != nil })
        XCTAssertTrue(spans.contains { $0.isUnderlined && $0.text == "点我" })
    }

    func testBoldContainingLinkKeepsBothStyles() {
        let spans = InlineMarkdown.parse("**[官网](https://example.com)**")
        guard let link = spans.first(where: { $0.url != nil }) else { return XCTFail("应该有链接") }
        XCTAssertTrue(link.isBold)
        XCTAssertEqual("官网", link.text)
    }

    func testMathBecomesReadable() {
        let spans = InlineMarkdown.parse("公式 $\\frac{1}{2}$ 对吧")
        guard let math = spans.first(where: { $0.isMath }) else { return XCTFail("应该有数学片段") }
        XCTAssertEqual("1⁄2", math.text)
    }

    func testMathWithNewlineIsNotParsed() {
        let spans = InlineMarkdown.parse("$a\nb$")
        XCTAssertFalse(spans.contains { $0.isMath })
    }

    func testEscapeKeepsLiteralCharacter() {
        let spans = InlineMarkdown.parse("\\*不是斜体\\*")
        XCTAssertEqual("\\*不是斜体\\*".count - 2, spans.map(\.text).joined().count)
        XCTAssertFalse(spans.contains { $0.isItalic })
        XCTAssertEqual("*不是斜体*", spans.map(\.text).joined())
    }

    func testUnmatchedMarkersStayLiteral() {
        XCTAssertEqual("*", InlineMarkdown.parse("*").map(\.text).joined())
        XCTAssertEqual("**abc", InlineMarkdown.parse("**abc").map(\.text).joined())
        XCTAssertEqual("`abc", InlineMarkdown.parse("`abc").map(\.text).joined())
        XCTAssertEqual("~~", InlineMarkdown.parse("~~").map(\.text).joined())
        XCTAssertFalse(InlineMarkdown.parse("**abc").contains { $0.isBold })
    }

    func testPlainTextJoinsEverything() {
        XCTAssertEqual("这句很重要吧", InlineMarkdown.plainText("这句**很重要**吧"))
    }

    func testAdjacentSameStyleRunsAreMerged() {
        // Android 端逐字 append；Swift 这边合并同一样式的相邻片段，好让 UI 少建几个 run。
        let spans = InlineMarkdown.parse("普通的一句话")
        XCTAssertEqual(1, spans.count)
    }

    func testItalicInsideBold() {
        let spans = InlineMarkdown.parse("**外 *内* 层**")
        XCTAssertTrue(spans.contains { $0.isBold && !$0.isItalic })
        XCTAssertTrue(spans.contains { $0.isBold && $0.isItalic })
        XCTAssertEqual("外 内 层", spans.map(\.text).joined())
    }

    func testOddMarkerCombinationStaysLiteralLikeAndroid() {
        // `*外**内**层*` 在 Kotlin 端也不会变出粗体：斜体分支先吃掉了第一个 `*`。
        let spans = InlineMarkdown.parse("*外**内**层*")
        XCTAssertTrue(spans.allSatisfy { $0.isItalic })
        XCTAssertEqual("外内层", spans.map(\.text).joined())
    }
}

/// LaTeX 转可读文本（移植自 Android 的 `Latex`）。
final class LatexTests: XCTestCase {

    func testFractions() {
        XCTAssertEqual("1⁄2", Latex.toReadable("\\frac{1}{2}"))
        XCTAssertEqual("(a+b)⁄c", Latex.toReadable("\\frac{a+b}{c}"))
    }

    func testSqrt() {
        XCTAssertEqual("√(2)", Latex.toReadable("\\sqrt{2}"))
        XCTAssertEqual("√[3](x)", Latex.toReadable("\\sqrt[3]{x}"))
    }

    func testSuperscriptsAndSubscripts() {
        XCTAssertEqual("x²", Latex.toReadable("x^2"))
        XCTAssertEqual("xⁿ", Latex.toReadable("x^{n}"))
        XCTAssertEqual("H₂O", Latex.toReadable("H_2O"))
    }

    func testGreekAndSymbols() {
        XCTAssertEqual("π", Latex.toReadable("\\pi"))
        XCTAssertEqual("3 × 4 ≤ 12", Latex.toReadable("3 \\times 4 \\le 12"))
        XCTAssertEqual("∑", Latex.toReadable("\\sum"))
        XCTAssertEqual("∑x", Latex.toReadable("\\sum{x}"))
    }

    func testTextWrappersAndLeftRight() {
        XCTAssertEqual("你好", Latex.toReadable("\\text{你好}"))
        XCTAssertEqual("x ", Latex.toReadable("\\left x \\right"), "与 Kotlin 一样不 trim 尾部")
    }

    func testUnknownCommandsLoseBackslashAndBraces() {
        XCTAssertEqual("foo", Latex.toReadable("\\foo"))
        XCTAssertEqual("a b", Latex.toReadable("a\\,b"))
    }
}
