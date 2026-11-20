import XCTest
@testable import YapKit

/// 0.15 记忆 v2 的规则测试：更新节奏（5 / 15–40）、主题段的时间段与滚动、以及
/// `memorize` 的「关键词 + 主题 + 时间衰减」检索与 `<回忆：…>` 渲染。
final class SessionMemoryV2Tests: XCTestCase {

    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai") ?? .current
        return calendar
    }

    private func message(
        _ id: String,
        _ content: String,
        at: Int64,
        role: MessageRole = .user
    ) -> MessageEntity {
        MessageEntity(
            id: id, sessionId: "s", personaId: "p", role: role,
            content: content, createdAt: at, updatedAt: at
        )
    }

    private func millis(_ iso: String) -> Int64 {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        let date = formatter.date(from: iso) ?? Date(timeIntervalSince1970: 0)
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    // MARK: - 短期

    func testShortMemoryUpdatesEveryFiveRoundsOrPlanChange() {
        XCTAssertFalse(SessionMemoryV2.shouldUpdateShort(currentUserCount: 4, upTo: 0, planChanged: false))
        XCTAssertTrue(SessionMemoryV2.shouldUpdateShort(currentUserCount: 5, upTo: 0, planChanged: false))
        // 计划变了：不等 5 轮，立刻更新。
        XCTAssertTrue(SessionMemoryV2.shouldUpdateShort(currentUserCount: 1, upTo: 0, planChanged: true))
        // 上一次失败没写 upTo 时，下一条消息会重试（差值是累加的）。
        XCTAssertTrue(SessionMemoryV2.shouldUpdateShort(currentUserCount: 6, upTo: 1, planChanged: false))
        XCTAssertFalse(SessionMemoryV2.shouldUpdateShort(currentUserCount: 0, upTo: 0, planChanged: false))
    }

    // MARK: - 长期 / 主题

    func testLongMemoryIsModelDecidedBetween15And40Rounds() {
        XCTAssertFalse(SessionMemoryV2.shouldUpdateLong(currentUserCount: 14, upTo: 0, modelWantsUpdate: true))
        XCTAssertFalse(SessionMemoryV2.shouldUpdateLong(currentUserCount: 20, upTo: 0, modelWantsUpdate: false))
        XCTAssertTrue(SessionMemoryV2.shouldUpdateLong(currentUserCount: 20, upTo: 0, modelWantsUpdate: true))
        // 40 轮是硬上限：模型不想更新也必须更新。
        XCTAssertTrue(SessionMemoryV2.shouldUpdateLong(currentUserCount: 40, upTo: 0, modelWantsUpdate: false))
    }

    func testTopicsUpdateOnTopicSwitchAfterFirstSegmentExists() {
        // 已经有主题段：话题一换就更新，不等 15 轮。
        XCTAssertTrue(SessionMemoryV2.shouldUpdateTopics(
            currentUserCount: 8, upTo: 5, topicChanged: true, modelWantsUpdate: false
        ))
        // 还没有主题段：第一次仍要攒够 15 轮，免得聊两句就多一段噪音。
        XCTAssertFalse(SessionMemoryV2.shouldUpdateTopics(
            currentUserCount: 3, upTo: 0, topicChanged: true, modelWantsUpdate: false
        ))
        XCTAssertTrue(SessionMemoryV2.shouldUpdateTopics(
            currentUserCount: 15, upTo: 0, topicChanged: true, modelWantsUpdate: false
        ))
        XCTAssertFalse(SessionMemoryV2.shouldUpdateTopics(
            currentUserCount: 3, upTo: 0, topicChanged: false, modelWantsUpdate: false
        ))
        XCTAssertTrue(SessionMemoryV2.shouldUpdateTopics(
            currentUserCount: 20, upTo: 0, topicChanged: false, modelWantsUpdate: true
        ))
    }

    func testTopicChangedUsesKeywordOverlap() {
        let topics = [MemoryContract.TopicSegment(timeLabel: "2026-11-18 晚", summary: "加班与方案改版")]
        XCTAssertFalse(SessionMemoryV2.topicChanged("那个方案还要改吗", topics: topics))
        XCTAssertTrue(SessionMemoryV2.topicChanged("明天去爬山吗", topics: topics))
        // 空 query / 空主题都不该判成「换话题」或崩溃。
        XCTAssertFalse(SessionMemoryV2.topicChanged("", topics: topics))
        XCTAssertTrue(SessionMemoryV2.topicChanged("随便聊聊", topics: []))
    }

    // MARK: - 时间段

    func testTimeLabelSplitsDayIntoPeriods() {
        XCTAssertEqual(SessionMemoryV2.timeLabel(at: millis("2026-11-18 21:30"), calendar: calendar),
                       "2026-11-18 晚")
        XCTAssertEqual(SessionMemoryV2.timeLabel(at: millis("2026-11-18 02:10"), calendar: calendar),
                       "2026-11-18 深夜")
        XCTAssertEqual(SessionMemoryV2.timeLabel(at: millis("2026-11-18 09:10"), calendar: calendar),
                       "2026-11-18 上午")
        XCTAssertEqual(SessionMemoryV2.timeLabel(at: millis("2026-11-18 15:10"), calendar: calendar),
                       "2026-11-18 下午")
        XCTAssertEqual(SessionMemoryV2.timeLabel(at: 0, calendar: calendar), "")
    }

    func testAppendTopicMergesSamePeriodAndDropsOldest() {
        let at = millis("2026-11-18 20:00")
        var topics = SessionMemoryV2.appendTopic([], summary: "加班", at: at,
                                                calendar: calendar, maxSegments: 12)
        topics = SessionMemoryV2.appendTopic(topics, summary: "方案改版", at: at + 60_000,
                                            calendar: calendar, maxSegments: 12)
        XCTAssertEqual(topics.count, 1, "同一时间段应该合并成一段")
        XCTAssertTrue(topics[0].summary.contains("加班"))
        XCTAssertTrue(topics[0].summary.contains("方案改版"))

        var many: [MemoryContract.TopicSegment] = []
        for index in 0..<20 {
            many = SessionMemoryV2.appendTopic(
                many, summary: "第\(index)件事", at: at + Int64(index) * 86_400_000,
                calendar: calendar, maxSegments: 12
            )
        }
        XCTAssertEqual(many.count, 12, "最多 12 段，超出从最旧的丢")
        XCTAssertTrue(many.last?.summary.contains("第19件事") == true)
    }

    func testTopicJSONRoundTripAndBadData() {
        let topics = [
            MemoryContract.TopicSegment(timeLabel: "2026-11-18 晚", summary: "加班"),
            MemoryContract.TopicSegment(timeLabel: "2026-11-19 上午", summary: "爬山")
        ]
        let json = SessionMemoryV2.encodeTopics(topics)
        XCTAssertNotNil(json)
        XCTAssertEqual(SessionMemoryV2.decodeTopics(json), topics)
        XCTAssertEqual(SessionMemoryV2.decodeTopics("坏数据"), [])
        XCTAssertEqual(SessionMemoryV2.decodeTopics(nil), [])
        XCTAssertNil(SessionMemoryV2.encodeTopics([]))
    }

    func testTopicBlockUsesSharedTitles() {
        let block = SessionMemoryV2.topicBlock([
            MemoryContract.TopicSegment(timeLabel: "2026-11-18 晚", summary: "加班")
        ])
        XCTAssertTrue(block.hasPrefix(MemoryContract.Injection.TOPIC_TITLE))
        XCTAssertTrue(block.contains("- 2026-11-18 晚：加班"))
        XCTAssertEqual(SessionMemoryV2.topicBlock([]), "")
    }

    // MARK: - memorize 检索

    func testMemorizeRanksKeywordHitsAndDropsUnrelated() {
        let now = millis("2026-11-20 12:00")
        let messages = [
            message("m1", "我怕冷，冬天手脚冰凉", at: now - 2 * 86_400_000),
            message("m2", "今天中午吃什么", at: now - 86_400_000),
            message("m3", "记得带围巾", at: now - 60_000, role: .assistant)
        ]
        let hits = MemorizeSearch.search(
            query: "怕冷 围巾", messages: messages, topics: [], now: now, calendar: calendar
        )
        XCTAssertEqual(hits.count, 2, "不相关的「吃什么」不该被回忆起来")
        XCTAssertTrue(hits.contains { $0.messageId == "m1" })
        XCTAssertTrue(hits.contains { $0.messageId == "m3" })
        XCTAssertTrue(hits.allSatisfy { !$0.timeLabel.isEmpty })
    }

    func testMemorizeTimeDecayPrefersRecentWhenEquallyRelevant() {
        let now = millis("2026-11-20 12:00")
        let messages = [
            message("old", "计划去哈尔滨", at: now - 90 * 86_400_000),
            message("new", "计划去哈尔滨", at: now - 86_400_000)
        ]
        let hits = MemorizeSearch.search(
            query: "哈尔滨计划", messages: messages, topics: [], now: now, calendar: calendar
        )
        XCTAssertEqual(hits.first?.messageId, "new", "同样相关时，近的应该排前面")
        XCTAssertGreaterThan(hits[0].score, hits[1].score)
    }

    func testMemorizeTopicBoostAndTopicAttachment() {
        let now = millis("2026-11-20 12:00")
        let topics = [MemoryContract.TopicSegment(timeLabel: "2026-11-18 晚", summary: "加班与方案改版")]
        let messages = [message("m1", "方案还得改一版", at: now - 86_400_000)]
        let hits = MemorizeSearch.search(
            query: "方案", messages: messages, topics: topics, now: now, calendar: calendar
        )
        XCTAssertEqual(hits.count, 1)
        XCTAssertEqual(hits[0].topic?.summary, "加班与方案改版", "命中的主题段要能解释这段回忆")
    }

    func testMemorizeIgnoresBlockedAndErrorMessages() {
        let now = millis("2026-11-20 12:00")
        var blocked = message("m1", "怕冷", at: now - 1000)
        blocked.blocked = true
        var failed = message("m2", "怕冷", at: now - 2000)
        failed.status = .error
        let hits = MemorizeSearch.search(
            query: "怕冷", messages: [blocked, failed], topics: [], now: now, calendar: calendar
        )
        XCTAssertTrue(hits.isEmpty)
    }

    func testMemorizeRendersRecallBlock() {
        let now = millis("2026-11-20 12:00")
        let messages = [message("m1", "我怕冷，冬天手脚冰凉", at: now - 86_400_000)]
        let hits = MemorizeSearch.search(
            query: "怕冷", messages: messages, topics: [], now: now, calendar: calendar
        )
        let text = MemorizeSearch.render(hits, query: "怕冷")
        XCTAssertTrue(text.hasPrefix("<回忆："), "注入格式必须是 <回忆：…>：\(text)")
        XCTAssertTrue(text.contains("你：「"))
        XCTAssertTrue(text.contains("怕冷"))
    }

    func testMemorizeEmptyResultSaysSoInChinese() {
        let text = MemorizeSearch.render([], query: "去年冬天")
        XCTAssertTrue(text.contains("想不起"))
        XCTAssertTrue(text.contains("去年冬天"))
        XCTAssertFalse(text.contains("<回忆：想不起关于「」"))
    }

    func testMemorizeQueryLimitAndTokenFiltering() {
        let longQuery = String(repeating: "怕冷", count: 200)
        let tokens = MemorizeSearch.tokens(longQuery)
        XCTAssertFalse(tokens.isEmpty)
        XCTAssertTrue(tokens.allSatisfy { $0.count >= 1 })
        // 虚词/单字不该成为唯一词元。
        XCTAssertTrue(MemorizeSearch.tokens("的了是在").isEmpty)
        XCTAssertEqual(MemorizeSearch.maxQueryCharsForTesting, 300)
    }
}

// MARK: - 测试用的常量出口

extension MemorizeSearch {
    /// 与共享契约 `MemoryContract.Retrieval.MAX_QUERY_CHARS` 同值（测试钉住）。
    static var maxQueryCharsForTesting: Int { MemoryContract.Retrieval.MAX_QUERY_CHARS }
}
