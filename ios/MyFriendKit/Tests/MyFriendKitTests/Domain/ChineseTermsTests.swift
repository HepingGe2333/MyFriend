import XCTest
@testable import MyFriendKit

/// 中文词元与时间指代的单测（移植自 Android 的 ChineseTermsTest）。
///
/// 这些东西决定了「他能不能顺着半句话想起一件旧事」，出错不会崩，但会让他显得记性差，
/// 所以拆词、虚词过滤、英文、空串、超长、重复、时间换算都要钉死。
final class ChineseTermsTests: XCTestCase {

    let day: Int64 = 86_400_000

    /// 固定时区的日历：规则本身与地区无关，但测试必须能在任何机器上得到同一个答案。
    let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return calendar
    }()

    /// 用固定时区的「2026-03-15 12:00」当 now：不依赖跑测试的机器当前时间。
    lazy var now: Int64 = localTime(2026, 2, 15, 12)

    let sameTerms: Set<String> = ["项目", "目压", "压力"]

    func localTime(_ year: Int, _ month0: Int, _ dayOfMonth: Int, _ hour: Int) -> Int64 {
        var components = DateComponents()
        components.year = year
        components.month = month0 + 1
        components.day = dayOfMonth
        components.hour = hour
        components.minute = 0
        components.second = 0
        let date = calendar.date(from: components)!
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    // MARK: 1. 二元组拆分

    func testChineseSplitsIntoCharacterBigrams() {
        let terms = Set(ChineseTerms.terms("项目压力").map(\.term))
        XCTAssertEqual(sameTerms, terms)
    }

    func testSingleCharacterBecomesTerm() {
        let terms = ChineseTerms.terms("猫")
        XCTAssertEqual(1, terms.count)
        XCTAssertEqual("猫", terms[0].term)
        XCTAssertEqual(1.0, terms[0].weight, accuracy: 0.0001)
    }

    func testStopWordsRemovedSoContentWordsStickTogether() {
        let terms = ChineseTerms.terms("我觉得这个项目压力很大").map(\.term)
        XCTAssertTrue(terms.contains("项目"), "「项目」应该被保留，实际 \(terms)")
        XCTAssertTrue(terms.contains("压力"), "「压力」应该被保留，实际 \(terms)")
        XCTAssertFalse(terms.contains { $0.contains("的") || $0.contains("很") },
                       "虚词不该变成词元，实际 \(terms)")
    }

    func testNegationIsNotAStopWord() {
        let terms = ChineseTerms.terms("不喜欢").map(\.term)
        XCTAssertTrue(terms.contains("喜欢"), "「喜欢」应该还在，实际 \(terms)")
        XCTAssertTrue(terms.contains { $0.hasPrefix("不") },
                      "「不」是语义的一部分，不该被删，实际 \(terms)")
    }

    func testPureStopWordsAndPunctuationHaveNoTerms() {
        XCTAssertTrue(ChineseTerms.terms("的了吗呢吧啊").isEmpty)
        XCTAssertTrue(ChineseTerms.terms("，。！？、；：").isEmpty)
        XCTAssertTrue(ChineseTerms.terms("   ").isEmpty)
        XCTAssertTrue(ChineseTerms.terms("").isEmpty)
    }

    // MARK: 2. 英文与混合

    func testEnglishSplitsIntoLowercaseWords() {
        let terms = ChineseTerms.terms("Kotlin Compose Telegram").map(\.term).sorted()
        XCTAssertEqual(["compose", "kotlin", "telegram"], terms)
    }

    func testEnglishStopWordsFiltered() {
        let terms = Set(ChineseTerms.terms("the project is good").map(\.term))
        XCTAssertEqual(Set(["project", "good"]), terms)
    }

    func testMixedChineseAndEnglish() {
        let terms = ChineseTerms.terms("我在杭州做 backend 开发").map(\.term)
        XCTAssertTrue(terms.contains("杭州"), "中文实词要留下，实际 \(terms)")
        XCTAssertTrue(terms.contains("backend"), "英文单词要留下，实际 \(terms)")
    }

    // MARK: 3. 重复与归一化

    func testRepeatedTermWeighsMoreButNeverExceedsOne() {
        let weights = ChineseTerms.weights("项目压力 项目")
        XCTAssertEqual(1.0, weights["项目"]!, accuracy: 0.0001)
        XCTAssertTrue(weights["目压"]! < weights["项目"]!, "只出现一次的「目压」应该更轻")
        XCTAssertTrue(weights.values.allSatisfy { $0 >= 0.0 && $0 <= 1.0 })
    }

    func testVeryLongTextIsTruncatedWithoutThrowing() {
        let long = String(repeating: "项目压力", count: 400)
        let terms = ChineseTerms.terms(long)
        XCTAssertTrue(terms.count <= ChineseTerms.MAX_TERMS_PER_TEXT, "词元数应该有上限，实际 \(terms.count)")
        XCTAssertFalse(terms.isEmpty)
    }

    func testQueryTermsAreDistinctAndLimited() {
        let longQuery = String(repeating: "项目压力", count: 50)
        let terms = ChineseTerms.queryTerms(longQuery, max: 5)
        XCTAssertEqual(Set(terms).count, terms.count)
        XCTAssertTrue(terms.count <= 5)
    }

    // MARK: 4. 相关度与余弦

    func testCosineAndRelevanceBasics() {
        let same = ChineseTerms.cosine(ChineseTerms.weights("项目压力"), ChineseTerms.weights("项目压力"))
        XCTAssertEqual(1.0, same, accuracy: 0.0001)

        XCTAssertEqual(0.0,
                       ChineseTerms.cosine(ChineseTerms.weights("项目压力"), ChineseTerms.weights("宇宙飞船")),
                       accuracy: 0.0001)
        XCTAssertEqual(0.0,
                       ChineseTerms.cosine(TermWeights(), ChineseTerms.weights("项目压力")),
                       accuracy: 0.0001,
                       "空向量不该除零")
    }

    func testRelevanceHandlesParaphrase() {
        let exact = ChineseTerms.relevance(query: "项目压力", document: "项目压力")
        let paraphrase = ChineseTerms.relevance(query: "项目压力", document: "最近项目压力很大，睡不着")
        let unrelated = ChineseTerms.relevance(query: "项目压力", document: "对方喜欢在雨天听老歌")

        XCTAssertEqual(1.0, exact, accuracy: 0.0001)
        XCTAssertTrue(paraphrase > 0.3, "换个说法也该有分，实际 \(paraphrase)")
        XCTAssertEqual(0.0, unrelated, accuracy: 0.0001)
        XCTAssertEqual(0.0, ChineseTerms.relevance(query: "", document: "项目压力"), accuracy: 0.0001,
                       "空查询一律 0")
    }

    func testTagHitsUseTermsNotSubstringLuck() {
        XCTAssertEqual(2, ChineseTerms.tagHits(query: "我妹妹最近失眠", tags: ["妹妹", "失眠"]))
        XCTAssertEqual(0, ChineseTerms.tagHits(query: "我姐妹最近失眠", tags: ["妹妹"]),
                       "「妹」不该因为 query 里有「姐妹」就算命中")
        XCTAssertEqual(1, ChineseTerms.tagHits(query: "我妹妹最近失眠", tags: ["妹妹", "失眠"], max: 1))
    }

    // MARK: 5. 时间指代

    func testYesterdayAndDayBeforeAreSingleDays() {
        guard let yesterday = ChineseTerms.timeSpanOf("昨天", now: now, calendar: calendar) else {
            return XCTFail("「昨天」应该能解析")
        }
        XCTAssertTrue(yesterday.contains(at: now - day), "应该包含昨天中午")
        XCTAssertFalse(yesterday.contains(at: now), "不该包含今天")
        XCTAssertEqual(Double(day), Double(yesterday.to - yesterday.from), accuracy: 1.0,
                       "区间应该正好一天")

        guard let before = ChineseTerms.timeSpanOf("前天", now: now, calendar: calendar) else {
            return XCTFail("「前天」应该能解析")
        }
        XCTAssertTrue(before.contains(at: localTime(2026, 2, 13, 15)))
    }

    func testLastMonthIsPreviousCalendarMonth() {
        guard let span = ChineseTerms.timeSpanOf("上个月", now: now, calendar: calendar) else {
            return XCTFail("「上个月」应该能解析")
        }
        XCTAssertTrue(span.contains(at: localTime(2026, 1, 10, 9)), "应该包含二月")
        XCTAssertFalse(span.contains(at: localTime(2026, 2, 2, 9)), "不该包含本月")
    }

    func testLastWinterCrossesYearBoundary() {
        guard let span = ChineseTerms.timeSpanOf("去年冬天", now: now, calendar: calendar) else {
            return XCTFail("「去年冬天」应该能解析")
        }
        XCTAssertTrue(span.contains(at: localTime(2025, 11, 20, 9)), "应该包含去年十二月")
        XCTAssertTrue(span.contains(at: localTime(2026, 0, 15, 9)), "也该包含今年一月")
        XCTAssertFalse(span.contains(at: localTime(2026, 6, 15, 9)), "不该包含今年夏天")
    }

    func testLastWednesdayIsPreviousWeek() {
        guard let span = ChineseTerms.timeSpanOf("上周三", now: now, calendar: calendar) else {
            return XCTFail("「上周三」应该能解析")
        }
        XCTAssertTrue(span.contains(at: localTime(2026, 2, 4, 15)), "应该包含三月四日")
        XCTAssertFalse(span.contains(at: localTime(2026, 2, 11, 15)), "不该包含本周三")
    }

    func testNumericDaysAndRecentWords() {
        guard let threeDaysAgo = ChineseTerms.timeSpanOf("三天前", now: now, calendar: calendar) else {
            return XCTFail("「三天前」应该能解析")
        }
        XCTAssertTrue(threeDaysAgo.contains(at: localTime(2026, 2, 12, 9)))

        guard let recent = ChineseTerms.timeSpanOf("最近这些天", now: now, calendar: calendar) else {
            return XCTFail("「最近」应该能解析")
        }
        XCTAssertTrue(recent.contains(at: now))
    }

    func testNoTimeHintMeansNoGuess() {
        XCTAssertNil(ChineseTerms.timeSpanOf("他妹妹", now: now, calendar: calendar))
        XCTAssertNil(ChineseTerms.timeSpanOf("", now: now, calendar: calendar))
        XCTAssertNil(ChineseTerms.timeSpanOf("   ", now: now, calendar: calendar))
        XCTAssertFalse(ChineseTerms.hasTimeHint("项目压力", now: now, calendar: calendar))
        XCTAssertTrue(ChineseTerms.hasTimeHint("上周三", now: now, calendar: calendar))
    }

    func testTimeExpressionsDoNotLeakIntoTerms() {
        let stripped = ChineseTerms.stripTimeExpressions("去年冬天他妹妹生病了")
        XCTAssertFalse(stripped.contains("去年"), "时间说法该被抹掉，实际 \(stripped)")
        XCTAssertFalse(stripped.contains("冬天"))
        XCTAssertTrue(stripped.contains("妹妹"), "正文要留下，实际 \(stripped)")

        let terms = ChineseTerms.terms("去年冬天他妹妹生病了").map(\.term)
        XCTAssertTrue(terms.contains("妹妹"), "「妹妹」应该还能查，实际 \(terms)")
    }

    func testNormalizeConvertsFullWidthAndPunctuation() {
        XCTAssertEqual("abc 项目压力", ChineseTerms.normalize("ＡＢＣ，项目压力！"))
        XCTAssertEqual("", ChineseTerms.normalize("   "))
    }

    func testChineseNumbers() {
        XCTAssertEqual(3, ChineseTerms.chineseNumber("三"))
        XCTAssertEqual(12, ChineseTerms.chineseNumber("十二"))
        XCTAssertEqual(20, ChineseTerms.chineseNumber("二十"))
        XCTAssertEqual(2, ChineseTerms.chineseNumber("两"))
        XCTAssertEqual(10, ChineseTerms.chineseNumber("十"))
        XCTAssertEqual(7, ChineseTerms.chineseNumber("7"))
        XCTAssertNil(ChineseTerms.chineseNumber("百"))
    }
}
