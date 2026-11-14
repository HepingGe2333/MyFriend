import XCTest
@testable import MyFriendKit

/// 回忆的三档强度（移植自 Android 的 RecallTest）：该翻多深、怎么排、翻不到时怎么说人话。
final class RecallTests: XCTestCase {

    let now: Int64 = 1_700_000_000_000
    let day: Int64 = 86_400_000
    let hour: Int64 = 3_600_000

    func memory(
        _ id: String,
        _ content: String,
        state: RecallMemoryState = .active,
        importance: Int = 3,
        lastUsedAt: Int64? = nil,
        pinned: Bool = false,
        drawer: MemoryDrawer = .user,
        strength: Double = 1.0,
        tags: [String] = [],
        happenedAt: Int64? = nil
    ) -> RecallMemoryInput {
        let usedAt = lastUsedAt ?? now
        return RecallMemoryInput(
            id: id,
            content: content,
            drawer: drawer,
            importance: importance,
            pinned: pinned,
            lastUsedAt: usedAt,
            createdAt: usedAt,
            state: state,
            strength: strength,
            tags: tags,
            happenedAt: happenedAt
        )
    }

    // MARK: 三档取数差异

    func testQuickOnlySeesActiveMemories() {
        let inputs = [
            memory("active", "对方怕冷，冬天总是手脚冰凉"),
            memory("dormant", "对方喜欢在雨天听老歌", state: .dormant),
            memory("archived", "很久以前一起看过海", state: .archived)
        ]
        let quick = Recall.memoryHits(memories: inputs, query: "对方", depth: .quick, now: now)
        XCTAssertEqual(["active"], quick.map(\.refId))
    }

    func testNormalAddsDormantButNotArchived() {
        let inputs = [
            memory("active", "对方怕冷，冬天总是手脚冰凉"),
            memory("dormant", "对方喜欢在雨天听老歌", state: .dormant),
            memory("archived", "很久以前一起看过海", state: .archived)
        ]
        let normal = Recall.memoryHits(memories: inputs, query: "对方", depth: .normal, now: now)
        XCTAssertEqual(["active", "dormant"], normal.map(\.refId).sorted())
    }

    func testDeepFindsArchivedWithUncertainNote() {
        let inputs = [
            memory("active", "对方怕冷，冬天总是手脚冰凉"),
            memory("dormant", "对方喜欢在雨天听老歌", state: .dormant),
            memory("archived", "很久以前对方提过想去看海", state: .archived)
        ]
        let deep = Recall.memoryHits(memories: inputs, query: "对方", depth: .deep, now: now)
        XCTAssertEqual(3, deep.count)
        guard let archived = deep.first(where: { $0.refId == "archived" }) else {
            return XCTFail("归档的那条应该被翻出来")
        }
        XCTAssertTrue(archived.note.contains("不太确定"), "归档命中要说「不太确定」")
        let rendered = Recall.render(deep, depth: .deep, query: "对方", now: now)
        XCTAssertTrue(rendered.contains("不太确定"))
    }

    func testDepthFlagsMatchProductSemantics() {
        // QUICK：只看还在用的记忆，不碰聊天与浏览
        XCTAssertFalse(RecallDepth.quick.includeDormant)
        XCTAssertFalse(RecallDepth.quick.includeArchived)
        XCTAssertFalse(RecallDepth.quick.includeChatSearch)
        XCTAssertFalse(RecallDepth.quick.includeBrowsing)
        // NORMAL：加上沉下去的记忆与最近聊天、他看到的
        XCTAssertTrue(RecallDepth.normal.includeDormant)
        XCTAssertFalse(RecallDepth.normal.includeArchived)
        XCTAssertTrue(RecallDepth.normal.includeChatSearch)
        XCTAssertTrue(RecallDepth.normal.includeBrowsing)
        XCTAssertFalse(RecallDepth.normal.scanRecentChats)
        // DEEP：连归档都翻，还按会话扫最近的聊天
        XCTAssertTrue(RecallDepth.deep.includeArchived)
        XCTAssertTrue(RecallDepth.deep.scanRecentChats)
        XCTAssertTrue(RecallDepth.deep.includeBrowsing)
        // 越深给的字数越多
        XCTAssertLessThan(RecallDepth.quick.maxChars, RecallDepth.normal.maxChars)
        XCTAssertLessThan(RecallDepth.normal.maxChars, RecallDepth.deep.maxChars)
        XCTAssertLessThan(RecallDepth.quick.level, RecallDepth.normal.level)
        XCTAssertLessThan(RecallDepth.normal.level, RecallDepth.deep.level)
        XCTAssertTrue(RecallDepth.quick.allows(.active))
        XCTAssertFalse(RecallDepth.quick.allows(.archived))
        XCTAssertTrue(RecallDepth.deep.allows(.archived))
    }

    // MARK: 排序

    func testKeywordMatchOutranksNewerUnrelatedContent() {
        let hits = [
            RecallHit(kind: .chat, text: "今天天气不错", at: now, weight: 1.0),
            RecallHit(kind: .memory, text: "对方养了一只叫团子的猫", at: now - 40 * day, weight: 0.2)
        ]
        let ranked = Recall.rank(hits, query: "团子", depth: .normal, now: now)
        XCTAssertEqual("对方养了一只叫团子的猫", ranked.first?.text)
    }

    func testKeywordScoreHandlesBlankQueryAndPartialMatch() {
        XCTAssertEqual(0.0, Recall.keywordScore(text: "随便一段话", query: ""), accuracy: 0.0001)
        XCTAssertEqual(0.0, Recall.keywordScore(text: "随便一段话", query: "   "), accuracy: 0.0001)
        XCTAssertEqual(1.0, Recall.keywordScore(text: "对方怕冷", query: "怕冷"), accuracy: 0.0001)
        XCTAssertEqual(0.0, Recall.keywordScore(text: "对方怕冷", query: "宇宙飞船"), accuracy: 0.0001)
        XCTAssertGreaterThan(Recall.keywordScore(text: "对方喜欢在雨天听老歌", query: "雨天 老歌"), 0.0)
    }

    func testNewerWinsWhenEverythingElseEqual() {
        let hits = [
            RecallHit(kind: .memory, text: "很早以前的事", at: now - 200 * day, weight: 0.5),
            RecallHit(kind: .memory, text: "最近的事", at: now - day, weight: 0.5)
        ]
        XCTAssertEqual("最近的事", Recall.rank(hits, query: "", depth: .quick, now: now).first?.text)
    }

    func testDeeperDepthToleratesOldButImportant() {
        let oldImportant = RecallHit(kind: .memory, text: "最早的那次约定", at: now - 300 * day, weight: 1.0)
        let freshLight = RecallHit(kind: .chat, text: "刚才的闲聊", at: now, weight: 0.45)
        let hits = [oldImportant, freshLight]
        // 随口想想时更信「最近」的；翻箱倒柜时，很久以前但很重要的排得上来
        XCTAssertEqual("刚才的闲聊", Recall.rank(hits, query: "", depth: .quick, now: now).first?.text)
        XCTAssertEqual("最早的那次约定", Recall.rank(hits, query: "", depth: .deep, now: now).first?.text)
    }

    func testPinnedAndImportantMemoryRanksFirst() {
        let inputs = [
            memory("plain", "对方喜欢猫", importance: 2, lastUsedAt: now - 30 * day),
            memory("pinned", "对方说养猫是这辈子最对的决定", importance: 5,
                   lastUsedAt: now - 30 * day, pinned: true)
        ]
        let hits = Recall.memoryHits(memories: inputs, query: "猫", depth: .quick, now: now)
        XCTAssertEqual("pinned", hits.first?.refId)
    }

    func testBlankQueryFallsBackToImportanceAndFreshness() {
        let inputs = [
            memory("old-important", "很久以前的重要约定", importance: 5, lastUsedAt: now - 30 * day),
            memory("recent-light", "最近随口说的一句话", importance: 1, lastUsedAt: now)
        ]
        let hits = Recall.memoryHits(memories: inputs, query: "", depth: .quick, now: now)
        XCTAssertEqual(2, hits.count)
        XCTAssertEqual("old-important", hits.first?.refId)
    }

    func testArchivedMemoryComesBackFaint() {
        let active = Recall.memoryHits(memories: [memory("a", "对方怕冷", importance: 4)],
                                       query: "", depth: .quick, now: now)[0]
        let archived = Recall.memoryHits(
            memories: [memory("z", "很久以前对方提过怕冷", state: .archived, importance: 4)],
            query: "", depth: .deep, now: now)[0]
        XCTAssertLessThan(archived.weight, active.weight, "归档的应该更轻")
        XCTAssertTrue(archived.note.contains("不太确定"))
    }

    // MARK: 渲染

    func testRenderLabelsEachSourceWithRelativeDate() {
        let memoryHit = Recall.memoryHits(
            memories: [memory("m1", "对方怕冷", lastUsedAt: now - 21 * day)],
            query: "", depth: .quick, now: now)[0]
        let chatHit = Recall.chatHit(text: "上次说的那家店很好吃", sessionTitle: "关于猫的那次聊天",
                                     at: now - day, role: "USER")
        let browsingHit = Recall.browsingHit(digest: "看到一条关于降噪耳机的评测", at: now - 2 * hour)

        let rendered = Recall.render([memoryHit, chatHit, browsingHit], depth: .normal,
                                     query: "店", now: now)

        XCTAssertTrue(rendered.contains("（记忆 · 3 周前 · 关于对方）"))
        XCTAssertTrue(rendered.contains("（聊天 · 昨天 · 《关于猫的那次聊天》"))
        XCTAssertTrue(rendered.contains("（他自己看到的 · 今天）"))
        XCTAssertTrue(rendered.contains("关于「店」"))
    }

    func testRenderTrimsContentByDepthBudget() {
        let hits = (1...60).map { index in
            RecallHit(kind: .memory,
                      text: "第\(index)条：" + String(repeating: "很久以前的事", count: 20),
                      at: now - Int64(index) * day,
                      weight: 0.5)
        }
        let quick = Recall.render(hits, depth: .quick, query: "事", now: now)
        let deep = Recall.render(hits, depth: .deep, query: "事", now: now)
        XCTAssertLessThanOrEqual(quick.count, RecallDepth.quick.maxChars,
                                 "QUICK 不该超过 \(RecallDepth.quick.maxChars) 字")
        XCTAssertLessThanOrEqual(deep.count, RecallDepth.deep.maxChars,
                                 "DEEP 不该超过 \(RecallDepth.deep.maxChars) 字")
        XCTAssertGreaterThan(deep.count, quick.count, "越深翻出来的越多")
        XCTAssertTrue(quick.contains("随口想想"))
    }

    func testEmptyAnswerDiffersByDepthAndAdmitsForgetting() {
        let quick = Recall.emptyAnswer(query: "猫", depth: .quick)
        let normal = Recall.emptyAnswer(query: "猫", depth: .normal)
        let deep = Recall.emptyAnswer(query: "猫", depth: .deep)

        XCTAssertTrue(quick.contains("想不太起来"))
        XCTAssertTrue(normal.contains("想不太起来"))
        XCTAssertTrue(deep.contains("确实想不起来"))
        XCTAssertTrue(deep.contains("可能早就忘了"))
        XCTAssertFalse(deep.contains("想不太起来"), "DEEP 不该只说「想不太起来」")
        XCTAssertTrue(deep.contains("猫"))
        // 空查询也要能说人话
        XCTAssertTrue(Recall.emptyAnswer(query: "", depth: .quick).contains("这件事"))
    }

    func testRenderFallsBackToEmptyAnswer() {
        XCTAssertEqual(Recall.emptyAnswer(query: "猫", depth: .deep),
                       Recall.render([], depth: .deep, query: "猫", now: now))
    }

    // MARK: 参数解析

    func testParseDepthAcceptsNumbersEnglishAndChinese() {
        XCTAssertEqual(RecallDepth.quick, RecallDepth.parse("1"))
        XCTAssertEqual(RecallDepth.normal, RecallDepth.parse("2"))
        XCTAssertEqual(RecallDepth.deep, RecallDepth.parse("3"))
        XCTAssertEqual(RecallDepth.quick, RecallDepth.parse("quick"))
        XCTAssertEqual(RecallDepth.quick, RecallDepth.parse("QUICK"))
        XCTAssertEqual(RecallDepth.normal, RecallDepth.parse("normal"))
        XCTAssertEqual(RecallDepth.deep, RecallDepth.parse("Deep"))
        XCTAssertEqual(RecallDepth.quick, RecallDepth.parse("随口想想"))
        XCTAssertEqual(RecallDepth.normal, RecallDepth.parse("认真回忆"))
        XCTAssertEqual(RecallDepth.deep, RecallDepth.parse("翻箱倒柜"))
        XCTAssertEqual(RecallDepth.quick, RecallDepth.parse("第一档"))
        XCTAssertEqual(RecallDepth.normal, RecallDepth.parse("第二档"))
        XCTAssertEqual(RecallDepth.deep, RecallDepth.parse("第三档"))
        // 认不出来时退回「认真回忆」，不抛异常
        XCTAssertEqual(RecallDepth.normal, RecallDepth.parse(nil))
        XCTAssertEqual(RecallDepth.normal, RecallDepth.parse("   "))
        XCTAssertEqual(RecallDepth.normal, RecallDepth.parse("看不懂的写法"))
    }

    func testParseDepthClampsOutOfRangeLevels() {
        XCTAssertEqual(RecallDepth.quick, RecallDepth.parse("0"))
        XCTAssertEqual(RecallDepth.quick, RecallDepth.parse("-1"))
        XCTAssertEqual(RecallDepth.deep, RecallDepth.parse("9"))
        XCTAssertEqual(RecallDepth.deep, RecallDepth.parse("第3档"))
        XCTAssertEqual(RecallDepth.quick, RecallDepth.fromLevel(-7))
        XCTAssertEqual(RecallDepth.deep, RecallDepth.fromLevel(42))
    }

    func testDateLabelCoversChineseRanges() {
        XCTAssertEqual("很久以前", Recall.dateLabel(at: 0, now: now))
        XCTAssertEqual("刚刚", Recall.dateLabel(at: now, now: now))
        XCTAssertEqual("5 分钟前", Recall.dateLabel(at: now - 5 * 60_000, now: now))
        XCTAssertEqual("今天", Recall.dateLabel(at: now - 2 * hour, now: now))
        XCTAssertEqual("昨天", Recall.dateLabel(at: now - day, now: now))
        XCTAssertEqual("前天", Recall.dateLabel(at: now - 2 * day, now: now))
        XCTAssertEqual("5 天前", Recall.dateLabel(at: now - 5 * day, now: now))
        XCTAssertEqual("3 周前", Recall.dateLabel(at: now - 21 * day, now: now))
        XCTAssertEqual("2 个月前", Recall.dateLabel(at: now - 65 * day, now: now))
        XCTAssertEqual("2 年前", Recall.dateLabel(at: now - 800 * day, now: now))
    }

    // MARK: 聊天 / 浏览命中

    func testChatHitCarriesSessionTitleAndSpeaker() {
        let user = Recall.chatHit(text: "上次说的那家店很好吃", sessionTitle: "关于猫的那次聊天",
                                  at: now - day, role: "USER")
        XCTAssertEqual(RecallKind.chat, user.kind)
        XCTAssertTrue(user.text.hasPrefix("对方说："))
        XCTAssertEqual("《关于猫的那次聊天》", user.source)
        XCTAssertTrue(Recall.sourceLabel(user, now: now).contains(RecallKind.chat.label))

        let assistant = Recall.chatHit(text: "我也记得那家店", sessionTitle: "关于猫的那次聊天",
                                       at: now - day, role: "assistant")
        XCTAssertTrue(assistant.text.hasPrefix("我说过："))

        let unknown = Recall.chatHit(text: "没有角色的旧消息", sessionTitle: "旧会话", at: now - day)
        XCTAssertEqual("没有角色的旧消息", unknown.text)
    }

    func testBrowsingHitKeepsDigestAndKind() {
        let hit = Recall.browsingHit(digest: "  看到一条关于降噪耳机的评测  ", at: now)
        XCTAssertEqual(RecallKind.browsing, hit.kind)
        XCTAssertEqual("看到一条关于降噪耳机的评测", hit.text)
        XCTAssertTrue(hit.weight >= 0.0 && hit.weight <= 1.0)
        XCTAssertEqual("", hit.source)
        XCTAssertTrue(Recall.sourceLabel(hit, now: now).hasPrefix("他自己看到的"))
    }

    // MARK: 倒排索引 / 标签 / 时间指代

    func testInvertedIndexHitWinsOnTie() {
        let hits = Recall.memoryHits(
            memories: [memory("first", "对方喜欢在雨天听老歌"), memory("second", "对方喜欢在雨天听老歌")],
            query: "老歌",
            depth: .quick,
            now: now,
            indexHits: ["second"]
        )
        XCTAssertEqual("second", hits.first?.refId, "词元真的对上比子串偶然更可信")
    }

    func testTagHitLiftsTheTaggedMemory() {
        let tagged = memory("tagged", "对方最近睡不好", tags: ["妹妹", "失眠"])
        let plain = memory("plain", "对方最近睡不好")
        let hits = Recall.memoryHits(memories: [plain, tagged], query: "我妹妹最近怎么样",
                                     depth: .quick, now: now)
        XCTAssertEqual("tagged", hits.first?.refId, "标签命中应该靠前")
    }

    func testHappenedAtNearTimeHintWins() {
        // now 是 2023-11 附近：去年冬天 = 2022 年 12 月到 2023 年 3 月。
        let inside = memory("inside", "那次旅行挺开心的", happenedAt: now - 300 * day)
        let outside = memory("outside", "那次旅行挺开心的", happenedAt: now - 20 * day)
        let hits = Recall.memoryHits(memories: [outside, inside], query: "去年冬天那次旅行",
                                     depth: .quick, now: now)
        XCTAssertEqual("inside", hits.first?.refId, "发生时间落在时间指代里的应该靠前")
    }

    func testKeywordScoreSharesTermsWithContextSelection() {
        XCTAssertEqual(1.0, Recall.keywordScore(text: "项目压力", query: "项目压力"), accuracy: 0.0001)
        XCTAssertGreaterThan(Recall.keywordScore(text: "他最近项目压得睡不着", query: "项目压力"), 0.0,
                             "换一种说法也该有分")
        XCTAssertEqual(0.0, Recall.keywordScore(text: "对方喜欢在雨天听老歌", query: "项目压力"),
                       accuracy: 0.0001)
    }

    func testBadDataNeverThrows() {
        XCTAssertTrue(Recall.memoryHits(memories: [], query: "猫", depth: .deep, now: now).isEmpty)
        XCTAssertTrue(Recall.memoryHits(memories: [memory("blank", "   ")], query: "猫",
                                        depth: .deep, now: now).isEmpty)
        XCTAssertTrue(Recall.rank([], query: "猫", depth: .quick, now: now).isEmpty)
        XCTAssertEqual("很久以前", Recall.dateLabel(at: -5, now: now))
        XCTAssertEqual(0.0, Recall.keywordScore(text: "", query: ""))
        // 一条正文为空的聊天记录不该炸，只是没有内容
        XCTAssertEqual(RecallKind.chat, Recall.chatHit(text: "  ", sessionTitle: "会话", at: now).kind)
    }
}
