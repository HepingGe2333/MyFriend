import XCTest
@testable import YapKit

/// 链接识别的边界（移植自 Android 的 LinkDetectorTest）。
///
/// 这些用例就是渲染层「蓝色可点」的唯一依据，
/// 误判会把普通文字点成网页，漏判会让用户看不到他发的地址。
final class LinkDetectorTests: XCTestCase {

    func slice(_ text: String, _ span: LinkSpan) -> String {
        let chars = Array(text)
        return String(chars[span.start..<span.end])
    }

    // MARK: 正常形态

    func testFindsHttpsUrlWithOffsets() {
        let text = "看这个 https://example.com/a?b=1#c 吧"
        let spans = LinkDetector.findAll(text)
        XCTAssertEqual(1, spans.count)
        let span = spans[0]
        XCTAssertEqual(LinkKind.url, span.kind)
        XCTAssertEqual(4, span.start)
        XCTAssertEqual(4 + "https://example.com/a?b=1#c".count, span.end)
        XCTAssertEqual("https://example.com/a?b=1#c", span.url)
        XCTAssertEqual(span.url, slice(text, span))
        XCTAssertEqual(span.url.count, span.length)
    }

    func testFindsHttpUrlWithPort() {
        let text = "旧地址 http://example.com:8080/x 还能用"
        guard let span = LinkDetector.findAll(text).first else { return XCTFail("应该找到链接") }
        XCTAssertEqual(LinkKind.url, span.kind)
        XCTAssertEqual("http://example.com:8080/x", span.url)
        XCTAssertEqual(span.url, slice(text, span))
    }

    func testFindsBareDomainAndAddsHttps() {
        let text = "文档在 example.com/guide 里"
        guard let span = LinkDetector.findAll(text).first else { return XCTFail("应该找到链接") }
        XCTAssertEqual(LinkKind.bareDomain, span.kind)
        XCTAssertEqual("https://example.com/guide", span.url)
        XCTAssertEqual(4, span.start)
        XCTAssertEqual("example.com/guide", slice(text, span))
    }

    func testFindsWwwDomain() {
        guard let span = LinkDetector.findAll("看 www.example.org 吧").first else {
            return XCTFail("应该找到链接")
        }
        XCTAssertEqual("https://www.example.org", span.url)
        XCTAssertEqual(LinkKind.bareDomain, span.kind)
    }

    func testFindsBareDomainRightAfterChineseWithoutSpace() {
        let text = "看example.com"
        guard let span = LinkDetector.findAll(text).first else { return XCTFail("应该找到链接") }
        XCTAssertEqual("https://example.com", span.url)
        XCTAssertEqual(1, span.start)
        XCTAssertEqual(text.count, span.end)
    }

    func testFindsUrlRightAfterChineseWithoutSpace() {
        guard let span = LinkDetector.findAll("看https://example.com").first else {
            return XCTFail("应该找到链接")
        }
        XCTAssertEqual("https://example.com", span.url)
        XCTAssertEqual(1, span.start)
    }

    func testFindsUrlInsideBoldMarkers() {
        let text = "**https://example.com**"
        guard let span = LinkDetector.findAll(text).first else { return XCTFail("应该找到链接") }
        XCTAssertEqual(2, span.start)
        XCTAssertEqual(2 + "https://example.com".count, span.end)
    }

    func testFindsMarkdownLinkAndCoversLabelOnly() {
        let text = "请看[官网](https://example.com/home)吧"
        guard let span = LinkDetector.findAll(text).first else { return XCTFail("应该找到链接") }
        XCTAssertEqual(LinkKind.markdown, span.kind)
        XCTAssertEqual("https://example.com/home", span.url)
        XCTAssertEqual("官网", slice(text, span))
    }

    func testMarkdownLinkAcceptsBareDomainTarget() {
        guard let span = LinkDetector.findAll("[官网](example.com)").first else {
            return XCTFail("应该找到链接")
        }
        XCTAssertEqual(LinkKind.markdown, span.kind)
        XCTAssertEqual("https://example.com", span.url)
    }

    func testFindsMultipleLinksInOrderWithoutOverlap() {
        let text = "A https://a.com/1 和 b.example.org/x 还有 [文](https://c.net)"
        let spans = LinkDetector.findAll(text)
        XCTAssertEqual(3, spans.count)
        XCTAssertEqual([LinkKind.url, LinkKind.bareDomain, LinkKind.markdown], spans.map(\.kind))
        XCTAssertEqual(["https://a.com/1", "https://b.example.org/x", "https://c.net"], spans.map(\.url))
        var previousEnd = 0
        for span in spans {
            XCTAssertGreaterThanOrEqual(span.start, previousEnd, "区间不应重叠")
            XCTAssertGreaterThan(span.end, span.start)
            previousEnd = span.end
        }
    }

    func testKeepsQueryAndFragment() {
        let url = "https://example.com/a?b=1&c=2#d"
        XCTAssertEqual(url, LinkDetector.normalize(url))
        XCTAssertEqual(url, LinkDetector.findAll("看 \(url) 。").first?.url)
    }

    // MARK: 标点边界

    func testStripsChinesePunctuationAfterUrl() {
        let text = "链接：https://example.com/x，还有https://example.com/y。"
        let spans = LinkDetector.findAll(text)
        XCTAssertEqual(["https://example.com/x", "https://example.com/y"], spans.map(\.url))
        for span in spans {
            XCTAssertEqual(span.url, slice(text, span))
        }
    }

    func testStripsAsciiSentencePunctuation() {
        let text = "见https://example.com/a."
        guard let span = LinkDetector.findAll(text).first else { return XCTFail("应该找到链接") }
        XCTAssertEqual("https://example.com/a", span.url)
        XCTAssertEqual("https://example.com/a", slice(text, span))
    }

    func testKeepsBalancedParenthesesInUrl() {
        let url = "https://en.wikipedia.org/wiki/Foo_(bar)"
        let text = "维基 \(url) 吧"
        guard let span = LinkDetector.findAll(text).first else { return XCTFail("应该找到链接") }
        XCTAssertEqual(url, span.url)
        XCTAssertEqual(url, slice(text, span))
    }

    func testStripsUnbalancedClosingParenthesis() {
        let text = "(见 https://example.com/a)"
        guard let span = LinkDetector.findAll(text).first else { return XCTFail("应该找到链接") }
        XCTAssertEqual("https://example.com/a", span.url)
        XCTAssertEqual("https://example.com/a", slice(text, span))
    }

    func testNormalizeStripsTrailingPunctuation() {
        XCTAssertEqual("https://example.com/x", LinkDetector.normalize("https://example.com/x。"))
        XCTAssertEqual("https://example.com", LinkDetector.normalize("example.com，"))
        XCTAssertEqual("https://example.com", LinkDetector.normalize(" https://example.com "))
    }

    // MARK: 协议与写法

    func testNormalizeAddsHttpsForBareDomain() {
        XCTAssertEqual("https://example.com", LinkDetector.normalize("example.com"))
        XCTAssertEqual("https://example.com:8080/x", LinkDetector.normalize("example.com:8080/x"))
    }

    func testNormalizeHandlesProtocolRelative() {
        XCTAssertEqual("https://example.com/a", LinkDetector.normalize("//example.com/a"))
    }

    func testNormalizeStripsAngleBrackets() {
        XCTAssertEqual("https://example.com", LinkDetector.normalize("<https://example.com>"))
    }

    func testAcceptsUppercaseScheme() {
        XCTAssertEqual("HTTPS://Example.com/Path", LinkDetector.normalize("HTTPS://Example.com/Path"))
    }

    func testParsesDocumentedSampleUrl() {
        let text = "他说这个例子不错：https://example.com/x。你看看。"
        guard let span = LinkDetector.findAll(text).first else { return XCTFail("应该找到链接") }
        XCTAssertEqual("https://example.com/x", span.url)
        XCTAssertEqual(LinkKind.url, span.kind)
    }

    // MARK: 拒绝

    func testRejectsDangerousSchemes() {
        for raw in [
            "javascript:alert(1)",
            "javascript:void(0)",
            "file:///etc/passwd",
            "content://media/external/images/1",
            "intent://scan#Intent;scheme=zxing;end",
            "data:text/html;base64,PHNjcmlwdD4=",
            "mailto:someone@example.com"
        ] {
            XCTAssertNil(LinkDetector.normalize(raw), "不该放行：\(raw)")
        }
    }

    func testDoesNotLinkDangerousSchemeInText() {
        XCTAssertTrue(LinkDetector.findAll("点这里 javascript:alert(1) 和 file:///etc/passwd").isEmpty)
    }

    func testIgnoresEmptyAndBlankInput() {
        XCTAssertTrue(LinkDetector.findAll("").isEmpty)
        XCTAssertTrue(LinkDetector.findAll("   ").isEmpty)
        XCTAssertNil(LinkDetector.normalize(""))
        XCTAssertNil(LinkDetector.normalize("    "))
        XCTAssertNil(LinkDetector.normalize("https://"))
        XCTAssertNil(LinkDetector.normalize("http://"))
    }

    func testIgnoresInvalidHosts() {
        XCTAssertNil(LinkDetector.normalize("https://abc"))
        XCTAssertNil(LinkDetector.normalize("https://.com"))
        XCTAssertNil(LinkDetector.normalize("https://a..b.com"))
        XCTAssertNil(LinkDetector.normalize("https://exa mple.com/a"))
        XCTAssertNil(LinkDetector.normalize("https://-example.com"))
    }

    func testIgnoresEmailAddress() {
        XCTAssertTrue(LinkDetector.findAll("联系 foo@example.com 谢谢").isEmpty)
    }

    func testIgnoresFileLikeNames() {
        XCTAssertTrue(LinkDetector.findAll("文件 说明.txt 和 main.kt、build.gradle").isEmpty)
    }

    func testIgnoresVersionNumbers() {
        XCTAssertTrue(LinkDetector.findAll("版本 v1.7.0 和 1.2.3 都过时了").isEmpty)
    }

    func testRejectsOverlongUrl() {
        let longUrl = "https://example.com/" + String(repeating: "a", count: 3000)
        XCTAssertNil(LinkDetector.normalize(longUrl))
        XCTAssertTrue(LinkDetector.findAll(longUrl).isEmpty)
        XCTAssertTrue(LinkDetector.findAll("说明 \(longUrl) 结束").isEmpty)
    }

    // MARK: 坏输入不炸

    func testDoesNotThrowOnWeirdInput() {
        for text in [
            "[[[[]]]]",
            "()[]<>",
            "[]()",
            "[标题]()",
            "https://",
            "http://",
            "\n\n\n",
            "😀🌐",
            String(repeating: "a", count: 10_000),
            String(repeating: "[", count: 500),
            "https://example.com/" + String(repeating: "(", count: 50),
            "%%%"
        ] {
            for span in LinkDetector.findAll(text) {
                XCTAssertGreaterThanOrEqual(span.start, 0)
                XCTAssertGreaterThan(span.end, span.start)
                XCTAssertLessThanOrEqual(span.end, text.count)
                XCTAssertTrue(span.url.hasPrefix("http"))
            }
        }
    }

    func testDoesNotSplitWords() {
        // 单词里的点、路径里的点都不该被当成域名
        XCTAssertTrue(LinkDetector.findAll("文件是 report.final.docx").isEmpty)
        XCTAssertTrue(LinkDetector.findAll("自增字段 user_id 不能点").isEmpty)
    }

    func testHandlesManyLinksWithoutOverlap() {
        let text = (1...200).map { "https://example.com/p/\($0)" }.joined(separator: " ")
        let spans = LinkDetector.findAll(text)
        XCTAssertEqual(200, spans.count)
        XCTAssertEqual("https://example.com/p/200", spans.last?.url)
        var previousEnd = 0
        for span in spans {
            XCTAssertGreaterThanOrEqual(span.start, previousEnd)
            previousEnd = span.end
        }
    }

    func testMarkdownLinkWithUnsafeTargetFallsBackToPlainText() {
        XCTAssertTrue(LinkDetector.findAll("[点我](javascript:alert(1))").isEmpty)
    }

    func testBalancedParensInMarkdownTargetAreNotTruncated() {
        let text = "看这个 [维基](https://en.wikipedia.org/wiki/Foo_(bar)) 挺有意思"
        let spans = LinkDetector.findAll(text)
        XCTAssertEqual(1, spans.count)
        guard let span = spans.first else { return }
        XCTAssertEqual(LinkKind.markdown, span.kind)
        XCTAssertEqual("维基", slice(text, span))
        XCTAssertEqual("https://en.wikipedia.org/wiki/Foo_(bar)", span.url)
    }

    func testMarkdownTargetWithSpaceIsRejected() {
        // 地址段不该有空格；有空格说明这不是链接，宁可少识别也别把后半段正文吃进去。
        let text = "[标题](https://example.com/a b) 后面还有话"
        XCTAssertFalse(LinkDetector.findAll(text).contains { $0.kind == .markdown })
    }

    func testMarkdownTargetAcrossLinesIsRejected() {
        let text = "[标题](https://example.com/a\nb)"
        XCTAssertFalse(LinkDetector.findAll(text).contains { $0.kind == .markdown })
    }

    func testOffsetsAreCharacterBasedNotUTF16() {
        // 有意的移植差异：Kotlin 的 String 下标是 UTF-16 码元，Swift 这里统一用 Character。
        // 纯 ASCII / 中文完全一致；带 emoji 时 Swift 的偏移更小，但区间仍然是完整字符。
        let text = "😀 https://example.com"
        guard let span = LinkDetector.findAll(text).first else { return XCTFail("应该找到链接") }
        XCTAssertEqual(2, span.start)
        XCTAssertEqual("https://example.com", slice(text, span))
    }

    func testMarkdownLabelMayContainClosingBracket() {
        // 与 Kotlin 的 indexOf("](") 一致：找的是「] 紧跟 (」这个两字符序列。
        let spans = LinkDetector.findAll("[a]b](https://example.com)")
        XCTAssertEqual(1, spans.count)
        XCTAssertEqual("a]b", spans.first.map { slice("[a]b](https://example.com)", $0) })
    }
}
