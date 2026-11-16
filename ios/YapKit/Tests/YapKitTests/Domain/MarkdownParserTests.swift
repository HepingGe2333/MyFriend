import XCTest
@testable import YapKit

/// Markdown 块级解析（移植自 Android 的 MarkdownParser）。
///
/// 只钉「一段回复被切成哪些块、每块内容是什么」。排版不在这里测。
final class MarkdownParserTests: XCTestCase {

    func testParagraph() {
        XCTAssertEqual([.paragraph(text: "他说今天有点累。")],
                       MarkdownParser.parse("他说今天有点累。"))
    }

    func testMultiLineParagraphKeptTogether() {
        XCTAssertEqual([.paragraph(text: "第一行\n第二行")],
                       MarkdownParser.parse("第一行\n第二行"))
    }

    func testHeadingsByLevel() {
        XCTAssertEqual([.heading(level: 1, text: "标题"),
                        .heading(level: 2, text: "小标题"),
                        .heading(level: 6, text: "六级")],
                       MarkdownParser.parse("# 标题\n## 小标题\n###### 六级"))
    }

    func testHeadingNeedsSpace() {
        XCTAssertEqual([.paragraph(text: "#没有空格不是标题")],
                       MarkdownParser.parse("#没有空格不是标题"))
    }

    func testCodeBlockWithLanguage() {
        XCTAssertEqual([.codeBlock(language: "swift", code: "let x = 1\nlet y = 2")],
                       MarkdownParser.parse("```swift\nlet x = 1\nlet y = 2\n```"))
    }

    func testCodeBlockWithoutLanguage() {
        XCTAssertEqual([.codeBlock(language: "", code: "plain")],
                       MarkdownParser.parse("```\nplain\n```"))
    }

    func testUnclosedCodeBlockStillProducesBlock() {
        XCTAssertEqual([.codeBlock(language: "kt", code: "val x = 1")],
                       MarkdownParser.parse("```kt\nval x = 1"))
    }

    func testQuoteMergesConsecutiveLines() {
        XCTAssertEqual([.quote(text: "第一句\n第二句")],
                       MarkdownParser.parse("> 第一句\n>第二句"))
    }

    func testBullets() {
        XCTAssertEqual([.bullets(items: ["一", "二"])],
                       MarkdownParser.parse("- 一\n* 二"))
    }

    func testOrderedList() {
        XCTAssertEqual([.ordered(items: ["第一", "第二"])],
                       MarkdownParser.parse("1. 第一\n2) 第二"))
    }

    func testDividers() {
        XCTAssertEqual([.divider, .divider, .divider],
                       MarkdownParser.parse("---\n***\n___"))
    }

    func testInlineFilterConversionBlock() {
        XCTAssertEqual([.mathBlock(latex: "a^2 + b^2")],
                       MarkdownParser.parse("$$a^2 + b^2$$"))
    }

    func testMultiLineMathBlock() {
        XCTAssertEqual([.mathBlock(latex: "a\nb")],
                       MarkdownParser.parse("$$\na\nb\n$$"))
    }

    func testMixedDocumentKeepsOrder() {
        let source = """
        # 今天

        他说有点累。

        - 早睡
        - 少喝咖啡

        > 明天再说。

        ```
        code
        ```

        ---
        """
        XCTAssertEqual([
            .heading(level: 1, text: "今天"),
            .paragraph(text: "他说有点累。"),
            .bullets(items: ["早睡", "少喝咖啡"]),
            .quote(text: "明天再说。"),
            .codeBlock(language: "", code: "code"),
            .divider
        ], MarkdownParser.parse(source))
    }

    func testCrlfIsNormalized() {
        XCTAssertEqual([.paragraph(text: "第一行"), .paragraph(text: "第二行")],
                       MarkdownParser.parse("第一行\r\n\r\n第二行"))
    }

    func testEmptyInputProducesNoBlocks() {
        XCTAssertTrue(MarkdownParser.parse("").isEmpty)
        XCTAssertTrue(MarkdownParser.parse("\n\n\n").isEmpty)
    }

    func testParagraphStopsAtBlockStarts() {
        let blocks = MarkdownParser.parse("正文\n## 标题\n正文2")
        XCTAssertEqual([.paragraph(text: "正文"), .heading(level: 2, text: "标题"),
                        .paragraph(text: "正文2")], blocks)
    }
}
