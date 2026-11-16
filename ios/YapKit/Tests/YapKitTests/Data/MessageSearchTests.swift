import Foundation
import XCTest
@testable import YapKit

/// 聊天记录检索的存储层语义：`LIKE` 粗筛 + 内存精排 + 游标分页 + 会话/人格联查。
///
/// 规范用例（`spec/search/cases`）覆盖的是「纯函数」那两层；
/// 这里钉的是它们和数据库接起来之后的行为，尤其是几件只有真 SQL 才会暴露的事：
/// - 用户输入里的 `%` / `_` 必须当普通字符（不能把「100%」搜成「100 开头的任何话」）；
/// - 中文二元组召回（`LIKE '%项目压力%'` 找不到「项目压得我睡不着」，但词元能）；
/// - 分页游标与「一次排完再切」完全一致（不重不漏）；
/// - 摘要里的高亮偏移对得上 `snippet` 本身。
final class MessageSearchTests: XCTestCase {

    private var temp: TempDatabase!
    private let base: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        try temp.stores.personas.upsert(TestData.persona(id: "persona-1", name: "林岸"))
        var second = TestData.persona(id: "persona-2", name: "阿澈", isDefault: false)
        second.avatarUri = "file:///tmp/ache.png"
        second.avatarSeed = 7
        try temp.stores.personas.upsert(second)
        try temp.stores.sessions.upsert(TestData.session(id: "session-1", personaId: "persona-1"))
        var secondSession = TestData.session(id: "session-2", personaId: "persona-2")
        secondSession.title = "晚风里的电话"
        try temp.stores.sessions.upsert(secondSession)
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    @discardableResult
    private func insert(
        _ id: String,
        _ content: String,
        at offset: Int64 = 0,
        sessionId: String = "session-1",
        personaId: String = "persona-1",
        role: MessageRole = .user
    ) throws -> MessageEntity {
        let message = TestData.message(
            id: id,
            sessionId: sessionId,
            personaId: personaId,
            role: role,
            content: content,
            createdAt: base + offset
        )
        try temp.stores.messages.upsert(message)
        return message
    }

    // MARK: - 基本命中与联查

    func testHitCarriesSessionPersonaAndSnippet() throws {
        try insert("m1", "明天去哈尔滨，记得带外套。", at: 1_000)
        try insert("m2", "我在，慢慢说。", at: 2_000)
        try insert("m3", "哈尔滨的雪很大。", at: 3_000, sessionId: "session-2", personaId: "persona-2", role: .assistant)

        let page = try temp.stores.messages.search(query: "哈尔滨")
        XCTAssertEqual(page.hits.count, 2)
        XCTAssertFalse(page.hasMore)

        let first = try XCTUnwrap(page.hits.first)
        XCTAssertEqual(first.id, "m3", "新消息在前（命中数相同时按 createdAt 降序）")
        XCTAssertEqual(first.sessionId, "session-2")
        XCTAssertEqual(first.sessionTitle, "晚风里的电话")
        XCTAssertEqual(first.personaId, "persona-2")
        XCTAssertEqual(first.personaName, "阿澈")
        XCTAssertEqual(first.avatar, "file:///tmp/ache.png")
        XCTAssertEqual(first.avatarSeed, 7)
        XCTAssertEqual(first.role, .assistant)
        XCTAssertEqual(first.createdAt, base + 3_000)
        XCTAssertTrue(first.snippet.contains("哈尔滨"))
        XCTAssertEqual(first.highlights.count, 1)
        XCTAssertEqual(try highlightedText(first), "哈尔滨")

        let second = try XCTUnwrap(page.hits.last)
        XCTAssertEqual(second.sessionTitle, "下个月去哈尔滨")
        XCTAssertEqual(second.personaName, "林岸")
        XCTAssertNil(second.avatar)
    }

    /// 会话标题为空时回落成人格名：界面不该出现一行「（空标题） · 3 天前」。
    func testEmptySessionTitleFallsBackToPersonaName() throws {
        var session = try XCTUnwrap(try temp.stores.sessions.byId("session-1"))
        session.title = ""
        try temp.stores.sessions.upsert(session)
        try insert("m1", "哈尔滨冷不冷", at: 1_000)

        let page = try temp.stores.messages.search(query: "哈尔滨")
        XCTAssertEqual(page.hits.first?.sessionTitle, "林岸")
    }

    /// 高亮偏移必须落在 `snippet` 内部且切出来的文本就是查询词。
    private func highlightedText(_ hit: SearchHit) throws -> String {
        let units = Array(hit.snippet.utf16)
        let range = try XCTUnwrap(hit.highlights.first)
        XCTAssertLessThanOrEqual(range.upperBound, units.count, "高亮越界了")
        return String(decoding: units[range], as: UTF16.self)
    }

    // MARK: - `%` / `_` 必须当普通字符
    //
    // 这里用「纯通配符查询」来隔离被测对象：`%` / `_` / `\` 经 `ChineseTerms.normalize`
    // 会被当成标点剔掉，于是查询不产生任何词元 —— 命中只能来自正文里的**字面**包含，
    // 于是这些用例钉的正是 `LIKE ... ESCAPE '\'` 有没有生效。
    // （带数字的查询比如「100%」还会因为词元「100」召回「1000」，那是规范规定的词元召回，
    //  见下面 `testPunctuationQueryStillRecallsThroughTokenizer`。）

    func testPercentInQueryIsLiteral() throws {
        try insert("m1", "进度 100% 了，终于跑完", at: 1_000)
        try insert("m2", "进度 1000 了，还早", at: 2_000)

        let page = try temp.stores.messages.search(query: "%")
        XCTAssertEqual(page.hits.map(\.id), ["m1"], "「%」不该被当成通配符匹配到 1000")
    }

    func testUnderscoreInQueryIsLiteral() throws {
        try insert("m1", "文件名是 report_2026.csv", at: 1_000)
        try insert("m2", "文件名是 reportX2026.csv", at: 2_000)

        let page = try temp.stores.messages.search(query: "_")
        XCTAssertEqual(page.hits.map(\.id), ["m1"], "「_」不该被当成「任意一个字符」")
    }

    func testBackslashInQueryIsLiteral() throws {
        try insert("m1", #"路径是 C:\Users\me"#, at: 1_000)
        try insert("m2", "路径是 C:/Users/me", at: 2_000)

        let page = try temp.stores.messages.search(query: #"\"#)
        XCTAssertEqual(page.hits.map(\.id), ["m1"])
    }

    /// 只由通配符组成的查询：转义之后应该一条都不匹配（而不是匹配全部）。
    func testWildcardOnlyQueryMatchesNothing() throws {
        try insert("m1", "别的什么话", at: 1_000)
        try insert("m2", "另一句话", at: 2_000)

        XCTAssertTrue(try temp.stores.messages.search(query: "%%").hits.isEmpty,
                      "「%%」是字面量，库里没有这种句子")
        XCTAssertTrue(try temp.stores.messages.search(query: "__").hits.isEmpty)
    }

    /// 规范里「命中 = 正文包含查询 **或** 任意词元命中」的后半句：带标点/数字的查询会
    /// 先被 `ChineseTerms.normalize` 拆成词元再召回。这条行为两端共享（Android 同源实现），
    /// 所以这里显式钉住，免得哪天被当成「通配符泄漏」误修。
    func testPunctuationQueryStillRecallsThroughTokenizer() throws {
        try insert("m1", "进度 100% 了，终于跑完", at: 1_000)
        try insert("m2", "进度 1000 了，还早", at: 2_000)

        let page = try temp.stores.messages.search(query: "100%")
        XCTAssertEqual(page.hits.map(\.id), ["m2", "m1"],
                       "词元「100」把 1000 也召回了 —— 这是规范的词元召回，不是通配符生效")
    }

    // MARK: - 中文召回

    func testCJKRecallThroughTokenizer() throws {
        // 字面 LIKE「项目压力」找不到这句话，但二元组「项目」能。
        try insert("m1", "最近项目压得我睡不着", at: 1_000)
        try insert("m2", "今天天气不错", at: 2_000)

        let page = try temp.stores.messages.search(query: "项目压力")
        XCTAssertEqual(page.hits.map(\.id), ["m1"], "中文要按词元召回，不能只靠整串 LIKE")
        let hit = try XCTUnwrap(page.hits.first)
        XCTAssertTrue(hit.snippet.contains("项目"))
        XCTAssertTrue(hit.highlights.isEmpty, "整串没出现，所以没有字面高亮（摘要仍给上下文）")
    }

    func testCJKLatinMixedQuery() throws {
        try insert("m1", "Harbin 的雪比哈尔滨还大", at: 1_000)
        try insert("m2", "只是随便聊聊", at: 2_000)

        XCTAssertEqual(try temp.stores.messages.search(query: "harbin 哈尔滨").hits.map(\.id), ["m1"])
        XCTAssertEqual(try temp.stores.messages.search(query: "HARBIN").hits.map(\.id), ["m1"])
    }

    // MARK: - 过滤

    func testPersonaFilterKeepsOnlyThatPersona() throws {
        try insert("m1", "哈尔滨", at: 1_000)
        try insert("m2", "哈尔滨", at: 2_000, sessionId: "session-2", personaId: "persona-2")
        try insert("m3", "哈尔滨", at: 3_000, sessionId: "session-2", personaId: "persona-2")

        let page = try temp.stores.messages.search(query: "哈尔滨", personaId: "persona-2")
        XCTAssertEqual(page.hits.map(\.id), ["m3", "m2"])
        XCTAssertTrue(page.hits.allSatisfy { $0.personaId == "persona-2" })
    }

    func testSinceFilterIsInclusiveOfTheBoundary() throws {
        try insert("m1", "哈尔滨", at: -1_000)
        try insert("m2", "哈尔滨", at: 0)
        try insert("m3", "哈尔滨", at: 1_000)

        let page = try temp.stores.messages.search(query: "哈尔滨", since: base)
        XCTAssertEqual(page.hits.map(\.id), ["m3", "m2"], "边界那一刻（>=）要算在内")
    }

    func testPersonaAndSinceFiltersCombine() throws {
        try insert("m1", "哈尔滨", at: -5_000, sessionId: "session-2", personaId: "persona-2")
        try insert("m2", "哈尔滨", at: 5_000, sessionId: "session-1", personaId: "persona-1")
        try insert("m3", "哈尔滨", at: 6_000, sessionId: "session-2", personaId: "persona-2")

        let page = try temp.stores.messages.search(query: "哈尔滨", personaId: "persona-2", since: base)
        XCTAssertEqual(page.hits.map(\.id), ["m3"])
    }

    // MARK: - 排序

    func testMoreMatchedTermsOutrankRecency() throws {
        try insert("m1", "哈尔滨", at: 9_000)
        try insert("m2", "明天去哈尔滨，记得带外套", at: 1_000)

        let page = try temp.stores.messages.search(query: "哈尔滨 外套")
        XCTAssertEqual(page.hits.map(\.id), ["m2", "m1"], "命中词元多的排在更前面，即使它更旧")
    }

    func testNonMatchingMessagesAreDropped() throws {
        try insert("m1", "哈尔滨", at: 1_000)
        try insert("m2", "完全无关的话", at: 2_000)

        XCTAssertEqual(try temp.stores.messages.search(query: "哈尔滨").hits.map(\.id), ["m1"])
    }

    // MARK: - 空查询

    func testEmptyOrWhitespaceQueryReturnsEmptyPage() throws {
        try insert("m1", "哈尔滨", at: 1_000)
        for query in ["", "   ", "\n\t"] {
            let page = try temp.stores.messages.search(query: query)
            XCTAssertTrue(page.hits.isEmpty, "空查询不能把整个库倒出来")
            XCTAssertFalse(page.hasMore)
            XCTAssertNil(page.nextCursor)
        }
    }

    func testZeroLimitReturnsEmptyPage() throws {
        try insert("m1", "哈尔滨", at: 1_000)
        XCTAssertTrue(try temp.stores.messages.search(query: "哈尔滨", limit: 0).hits.isEmpty)
    }

    // MARK: - 分页

    func testCursorPaginationCoversEverythingWithoutDuplicates() throws {
        // 45 条命中，其中每三条有一条多命中所查询的两个词元 → 顺序不是纯时间序。
        for index in 0..<45 {
            let content = index % 3 == 0 ? "哈尔滨 外套 \(index)" : "哈尔滨 \(index)"
            try insert(String(format: "m-%02d", index), content, at: Int64(index) * 1_000)
        }

        let all = try temp.stores.messages.search(query: "哈尔滨 外套", limit: 500).hits.map(\.id)
        XCTAssertEqual(all.count, 45)

        var collected: [String] = []
        var cursor: SearchCursor?
        var pages = 0
        while pages < 20 {
            pages += 1
            let page = try temp.stores.messages.search(query: "哈尔滨 外套", limit: 30, cursor: cursor)
            collected.append(contentsOf: page.hits.map(\.id))
            guard page.hasMore, let next = page.nextCursor else { break }
            cursor = next
        }

        XCTAssertEqual(pages, 2, "45 条按每页 30 条正好两页")
        XCTAssertEqual(collected, all, "游标分页的顺序必须与一次排完完全一致")
        XCTAssertEqual(Set(collected).count, collected.count, "不能重复")
    }

    func testHasMoreIsFalseWhenExactlyOnePage() throws {
        for index in 0..<30 {
            try insert(String(format: "m-%02d", index), "哈尔滨 \(index)", at: Int64(index) * 1_000)
        }
        let page = try temp.stores.messages.search(query: "哈尔滨", limit: 30)
        XCTAssertEqual(page.hits.count, 30)
        XCTAssertFalse(page.hasMore, "恰好一页时不该显示「还有更多」")
        XCTAssertNil(page.nextCursor)
    }

    // MARK: - 摘要窗口

    func testSnippetWindowIsHonoured() throws {
        let long = String(repeating: "前", count: 100) + "哈尔滨" + String(repeating: "后", count: 100)
        try insert("m1", long, at: 1_000)

        let wide = try temp.stores.messages.search(query: "哈尔滨")
        let narrow = try temp.stores.messages.search(query: "哈尔滨", snippetWindow: 40)
        let wideText = try XCTUnwrap(wide.hits.first?.snippet)
        let narrowText = try XCTUnwrap(narrow.hits.first?.snippet)
        XCTAssertGreaterThan(wideText.utf16.count, narrowText.utf16.count)
        XCTAssertEqual(narrowText.utf16.count, 42, "40 个码元 + 前后省略号")
        XCTAssertTrue(narrowText.contains("哈尔滨"), "窗口再窄也要把命中包进去（这里 40 > 30 的前缀 + 3）")
    }

    /// 规范把「命中点前留 30 个码元」写死，所以 `window < 33` 时命中会被挤出窗口。
    /// 这条**不是**我们改规范，而是把它钉住：界面永远用 60，别拿更小的值去调。
    func testWindowSmallerThanContextCannotShowTheHit() throws {
        let long = String(repeating: "前", count: 100) + "哈尔滨" + String(repeating: "后", count: 100)
        try insert("m1", long, at: 1_000)
        let tiny = try temp.stores.messages.search(query: "哈尔滨", snippetWindow: 20)
        let text = try XCTUnwrap(tiny.hits.first?.snippet)
        XCTAssertFalse(text.contains("哈尔滨"), "20 < 30：窗口整段落在命中点左边")
        XCTAssertEqual(text.utf16.count, 22)
    }

    // MARK: - 只读

    func testSearchDoesNotWriteAnything() throws {
        try insert("m1", "哈尔滨", at: 1_000)
        let before = try temp.stores.messages.count()
        _ = try temp.stores.messages.search(query: "哈尔滨")
        XCTAssertEqual(try temp.stores.messages.count(), before)
    }
}
