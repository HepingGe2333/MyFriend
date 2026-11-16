import Foundation
import XCTest
@testable import YapKit

/// 「两端必须一致」的检索契约测试（iOS 侧）。
///
/// 用例住在仓库根的 `spec/search/cases/*.json`，Android 的
/// `app/src/test/java/com/yap/app/spec/SearchSpecTest.kt` 读的是**同一批文件**。
/// 谁改了实现而没同步另一端，两边必有一边立刻变红。
///
/// 路径不写死：从 `#filePath`（本文件在仓库里的真实路径）往上找
/// 「同时有 `spec/search/cases` 与 `settings.gradle.kts`」的目录（与 `AgentCoreSpecTests` 同一套做法）。
///
/// 除了逐条跑规范用例外，这里还钉了几条**实现层**行为：多段高亮、
/// 超长正文只给一个窗口、CJK / 代理对边界不崩、游标分页与整段排序一致。
final class SearchSpecTests: XCTestCase {

    // MARK: - 路径与读取

    private static let repoRoot: URL = {
        var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fm = FileManager.default
        while url.path != "/" {
            let cases = url.appendingPathComponent("spec/search/cases")
            if fm.fileExists(atPath: cases.path),
               fm.fileExists(atPath: url.appendingPathComponent("settings.gradle.kts").path) {
                return url
            }
            url = url.deletingLastPathComponent()
        }
        fatalError("找不到仓库根目录（从 #filePath=\(#filePath) 向上没有 spec/search/cases）")
    }()

    private func loadCases(_ fileName: String) throws -> [[String: Any]] {
        let file = Self.repoRoot.appendingPathComponent("spec/search/cases/\(fileName).json")
        let data = try Data(contentsOf: file)
        guard let root = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            XCTFail("用例文件格式不对（顶层应该是数组）：\(file.path)")
            return []
        }
        XCTAssertFalse(root.isEmpty, "\(fileName) 不能没有用例")
        return root
    }

    private func check(_ fileName: String, _ body: ([String: Any]) throws -> Void) throws {
        for entry in try loadCases(fileName) {
            let name = entry["name"] as? String ?? "<unnamed>"
            do {
                try body(entry)
            } catch {
                XCTFail("[\(fileName) / \(name)] \(error)")
            }
        }
    }

    // MARK: - 1. snippet.json

    func testSnippetCases() throws {
        try check("snippet") { entry in
            let input = try XCTUnwrap(entry["input"] as? [String: Any], "缺 input")
            let expect = try XCTUnwrap(entry["expect"] as? [String: Any], "缺 expect")
            let content = try input.string("content")
            let query = input.optionalString("query") ?? ""
            let window = input.optionalInt("window") ?? SearchSnippet.defaultWindow

            let actual = SearchSnippet.build(content: content, query: query, window: window)
            let expectedText = try expect.string("text")
            XCTAssertEqual(actual.text, expectedText, "text 不一致（query=\(query)）")

            let expectedHighlights = (expect["highlights"] as? [[Int]] ?? []).compactMap { pair -> Range<Int>? in
                guard pair.count == 2 else { return nil }
                return pair[0]..<pair[1]
            }
            XCTAssertEqual(actual.highlights, expectedHighlights, "highlights 不一致（query=\(query)）")

            // 段落拼回去必须等于正文，且高亮段的下标与 highlights 对齐。
            XCTAssertEqual(actual.segments().map(\.text).joined(), actual.text, "segments 拼接必须还原 text")
        }
    }

    /// `snippet.json` 的每一条里窗口宽度都是 60；这里再钉一次默认值，
    /// 免得哪天有人改了 `defaultWindow` 而用例里显式写了 window，导致「默认值坏了但测试不红」。
    func testDefaultWindowMatchesSpec() {
        XCTAssertEqual(SearchSnippet.defaultWindow, 60)
        XCTAssertEqual(SearchSnippet.contextBefore, 30)
    }

    // MARK: - 2. ranking.json

    func testRankingCases() throws {
        try check("ranking") { entry in
            let input = try XCTUnwrap(entry["input"] as? [String: Any], "缺 input")
            let expect = try XCTUnwrap(entry["expect"] as? [String: Any], "缺 expect")
            let query = try input.string("query")

            let rawMessages = try XCTUnwrap(input["messages"] as? [[String: Any]], "缺 messages")
            let items = rawMessages.map { raw in
                SearchRankItem(
                    id: raw["id"] as? String ?? "",
                    content: raw["content"] as? String ?? "",
                    createdAt: (raw["createdAt"] as? NSNumber)?.int64Value ?? 0,
                    personaId: raw["personaId"] as? String ?? ""
                )
            }

            let filter = input["filter"] as? [String: Any]
            let personaId = filter?["personaId"] as? String
            let since = (filter?["since"] as? NSNumber)?.int64Value

            let ranked = SearchRanking.rank(items, query: query, personaId: personaId, since: since)
            let expectedOrder = expect["order"] as? [String] ?? []
            XCTAssertEqual(ranked.map(\.item.id), expectedOrder, "顺序不一致（query=\(query)）")
        }
    }

    // MARK: - 3. 实现层：多段高亮

    func testMultipleHighlightsAreAllReturnedInOrder() {
        let snippet = SearchSnippet.build(
            content: "他说哈尔滨冷，我说哈尔滨也行，哈尔滨真冷。",
            query: "哈尔滨"
        )
        XCTAssertEqual(snippet.highlights.count, 3, "三处命中都要高亮")
        XCTAssertEqual(snippet.highlights, snippet.highlights.sorted { $0.lowerBound < $1.lowerBound })
        for range in snippet.highlights {
            let units = Array(snippet.text.utf16)
            XCTAssertEqual(String(decoding: units[range], as: UTF16.self), "哈尔滨")
        }
        let segments = snippet.segments()
        XCTAssertEqual(segments.filter(\.isHighlight).map(\.text), ["哈尔滨", "哈尔滨", "哈尔滨"])
        XCTAssertEqual(segments.map(\.text).joined(), snippet.text)
    }

    /// 窗口只装得下两处命中时，第三处（在窗口外）不该出现，也不该把偏移算歪。
    func testHighlightsOutsideWindowAreDropped() {
        let head = String(repeating: "a", count: 100)
        let tail = String(repeating: "b", count: 100)
        let content = "\(head)哈尔滨\(tail)哈尔滨"
        let snippet = SearchSnippet.build(content: content, query: "哈尔滨")
        XCTAssertTrue(snippet.text.hasPrefix("…"))
        XCTAssertEqual(snippet.highlights.count, 1, "窗口外的第二处命中不该被画出来")
        let units = Array(snippet.text.utf16)
        let range = try! XCTUnwrap(snippet.highlights.first)
        XCTAssertEqual(String(decoding: units[range], as: UTF16.self), "哈尔滨")
        XCTAssertLessThanOrEqual(snippet.text.utf16.count, 62, "窗口 60 + 两侧省略号")
    }

    // MARK: - 4. 实现层：超长正文

    func testVeryLongContentStaysOneWindow() {
        // 一条一万字的正文：只截一个窗口，绝对不该把整段带进列表。
        let content = String(repeating: "很长的消息正文。", count: 1_500) + "目标词" + String(repeating: "尾巴。", count: 1_500)
        let snippet = SearchSnippet.build(content: content, query: "目标词")
        XCTAssertEqual(snippet.text.utf16.count, 62, "60 个码元 + 前后两个省略号")
        XCTAssertTrue(snippet.text.hasPrefix("…"))
        XCTAssertTrue(snippet.text.hasSuffix("…"))
        XCTAssertEqual(snippet.highlights.count, 1)
        XCTAssertTrue(snippet.text.contains("目标词"))
    }

    /// 没有命中时给的是「开头预览」：绝不返回空串（否则列表里是一列空白行）。
    func testNoMatchFallsBackToHeadPreviewEvenForLongContent() {
        let content = String(repeating: "z", count: 500)
        let snippet = SearchSnippet.build(content: content, query: "哈尔滨")
        XCTAssertEqual(String(snippet.text.dropLast()), String(repeating: "z", count: 60))
        XCTAssertTrue(snippet.text.hasSuffix("…"))
        XCTAssertTrue(snippet.highlights.isEmpty)
        XCTAssertFalse(snippet.hasHighlights)
    }

    // MARK: - 5. 实现层：CJK / 代理对边界

    /// 中文按 UTF-16 码元数（BMP 一码元一字），偏移必须落在字的边界上，
    /// 切出来的高亮文本要逐字等于查询词。
    func testCJKOffsetsLandOnCharacterBoundaries() {
        let content = "前言" + String(repeating: "。", count: 40) + "项目压力很大" + String(repeating: "。", count: 40)
        let snippet = SearchSnippet.build(content: content, query: "压力")
        XCTAssertEqual(snippet.highlights.count, 1)
        let units = Array(snippet.text.utf16)
        let range = try! XCTUnwrap(snippet.highlights.first)
        XCTAssertEqual(String(decoding: units[range], as: UTF16.self), "压力")
        // 反查：高亮区间在原文里的位置确实指向「压力」。
        XCTAssertEqual(snippet.segments().filter(\.isHighlight).count, 1)
    }

    /// emoji（代理对）不能把函数搞崩：规范用例避开了 emoji，但真机上一定会有。
    func testSurrogatePairsDoNotCrash() {
        let content = "🌧️ 下雨了，哈尔滨很冷 🌧️"
        let snippet = SearchSnippet.build(content: content, query: "哈尔滨")
        XCTAssertTrue(snippet.text.contains("哈尔滨"))
        XCTAssertEqual(snippet.highlights.count, 1)
        XCTAssertEqual(snippet.segments().map(\.text).joined(), snippet.text)

        // 窗口正好切在代理对中间也不能崩（会得到一个替换字符，长度仍然对）。
        let cut = "😀😀😀😀😀哈尔滨"
        let tight = SearchSnippet.build(content: cut, query: "哈尔滨", window: 7)
        XCTAssertFalse(tight.text.isEmpty)
        XCTAssertEqual(tight.segments().map(\.text).joined(), tight.text)
    }

    // MARK: - 6. 实现层：排序与游标

    /// 游标分页拿到的顺序必须与「一次排完再切」完全一致（不重不漏）。
    func testCursorPaginationMatchesFullOrdering() {
        var items: [SearchRankItem] = []
        for index in 0..<40 {
            items.append(SearchRankItem(
                id: String(format: "m-%02d", index),
                content: index % 3 == 0 ? "哈尔滨 外套" : "哈尔滨",
                createdAt: 1_000 + Int64(index) * 10,
                personaId: index % 2 == 0 ? "p1" : "p2"
            ))
        }
        let all = SearchRanking.rank(items, query: "哈尔滨")
        XCTAssertEqual(all.count, 40)

        // 每页 7 条，按游标往下翻。
        var collected: [String] = []
        var cursor: SearchCursor?
        var guardCount = 0
        while guardCount < 100 {
            guardCount += 1
            let remaining = cursor.map { c in all.filter { SearchRanking.comesAfter($0, cursor: c) } } ?? all
            let page = Array(remaining.prefix(7))
            if page.isEmpty { break }
            collected.append(contentsOf: page.map(\.item.id))
            cursor = SearchCursor(page[page.count - 1])
        }
        XCTAssertEqual(collected, all.map(\.item.id), "游标分页要与整段排序一致")
        XCTAssertEqual(Set(collected).count, collected.count, "不能重复")
    }

    func testEmptyAndWhitespaceQueryReturnNoHits() {
        let items = [SearchRankItem(id: "m1", content: "harbin", createdAt: 1, personaId: "p1")]
        XCTAssertTrue(SearchRanking.rank(items, query: "").isEmpty)
        XCTAssertTrue(SearchRanking.rank(items, query: "   ").isEmpty)
        XCTAssertFalse(SearchRanking.isHit(query: "  ", content: "harbin"))
    }

    /// 命中数优先于时间：这条规则是整个检索「不是简单按时间倒序」的核心。
    func testMoreMatchedTermsBeatRecency() {
        let items = [
            SearchRankItem(id: "new", content: "哈尔滨", createdAt: 9_000, personaId: "p1"),
            SearchRankItem(id: "old", content: "哈尔滨 外套", createdAt: 1_000, personaId: "p1")
        ]
        let ranked = SearchRanking.rank(items, query: "哈尔滨 外套")
        XCTAssertEqual(ranked.map(\.item.id), ["old", "new"])
        // 中文按字二元组切词：查询「哈尔滨 外套」→ 哈尔 / 尔滨 / 外套 三个词元，
        // 老消息三个全中，新消息只中前两个 —— 数量差就是「更相关」的来源。
        XCTAssertEqual(ranked.first?.matchCount, 3)
        XCTAssertEqual(ranked.last?.matchCount, 2)
    }
}

// MARK: - JSON 取值辅助

private extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) throws -> String {
        guard let value = self[key] as? String else {
            throw NSError(domain: "SearchSpec", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "缺少字符串字段 \(key)"])
        }
        return value
    }

    func optionalString(_ key: String) -> String? { self[key] as? String }

    func optionalInt(_ key: String) -> Int? { (self[key] as? NSNumber)?.intValue }
}
