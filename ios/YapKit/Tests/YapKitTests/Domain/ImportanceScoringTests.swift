import XCTest
@testable import YapKit

/// 重要性打分器的纯逻辑单测（移植自 Android 的 ImportanceScoringTest）。
///
/// 正常、缺字段、坏 JSON、越界、merge 多条都在这里钉死。
final class ImportanceScoringTests: XCTestCase {

    // MARK: 1. parseConsolidation：正常

    func testNormalOutputParsesAllFiveOperations() {
        let raw = """
        {"operations":[
          {"op":"keep","ids":["1","2"],"reason":"这是约定"},
          {"op":"merge","ids":["3","4"],"content":"对方养了两只猫","importance":4,"reason":"同一件事"},
          {"op":"archive","ids":["5"],"reason":"只是近况"},
          {"op":"forget","ids":["6"],"reason":"半年前的一次闲聊"},
          {"op":"raise","ids":["7"],"importance":5,"reason":"重要的事"}
        ],"summary":"整理了一遍"}
        """
        let plan = ImportanceScoring.parseConsolidation(raw)
        XCTAssertFalse(plan.isEmpty)
        XCTAssertEqual(5, plan.operations.count)
        XCTAssertEqual("整理了一遍", plan.summary)
        XCTAssertEqual(MemoryOp.keep, plan.operations[0].op)
        XCTAssertEqual(["1", "2"], plan.operations[0].ids)
        XCTAssertEqual(MemoryOp.merge, plan.operations[1].op)
        XCTAssertEqual("对方养了两只猫", plan.operations[1].content)
        XCTAssertEqual(MemoryOp.archive, plan.operations[2].op)
        XCTAssertEqual(MemoryOp.forget, plan.operations[3].op)
        XCTAssertEqual(MemoryOp.raise, plan.operations[4].op)
        XCTAssertEqual(5, plan.operations[4].importance)
    }

    func testCodeFenceAndChatterAreStripped() {
        let raw = """
        好的，我整理完了：
        ```json
        {"operations":[{"op":"keep","ids":[1]}],"summary":"就这些"}
        ```
        以上。
        """
        let plan = ImportanceScoring.parseConsolidation(raw)
        XCTAssertEqual(1, plan.operations.count)
        XCTAssertEqual(["1"], plan.operations[0].ids)
        XCTAssertEqual("就这些", plan.summary)
    }

    // MARK: 2. 坏 JSON / 缺字段

    func testBadJsonDegradesToNoOp() {
        for raw in ["", "   ", "这不是 JSON", "{坏掉的", "null", "[]"] {
            let plan = ImportanceScoring.parseConsolidation(raw)
            XCTAssertTrue(plan.isEmpty, "「\(raw)」应该是空计划")
            XCTAssertEqual("", plan.summary)
        }
    }

    func testMissingOperationsKeepsSummaryOnly() {
        let plan = ImportanceScoring.parseConsolidation(#"{"summary":"这次没动"}"#)
        XCTAssertTrue(plan.isEmpty)
        XCTAssertEqual("这次没动", plan.summary)
    }

    func testOperationsMissingIdOrOpAreDropped() {
        let raw = """
        {"operations":[
          {"op":"keep"},
          {"ids":["1","2"]},
          {"op":"乱写的指令","ids":["3"]},
          {"op":"keep","ids":["4"]}
        ]}
        """
        let plan = ImportanceScoring.parseConsolidation(raw)
        XCTAssertEqual(1, plan.operations.count)
        XCTAssertEqual(["4"], plan.operations[0].ids)
    }

    func testMergeWithSingleIdIsNotAMerge() {
        let plan = ImportanceScoring.parseConsolidation(
            #"{"operations":[{"op":"merge","ids":["1"],"content":"并起来"}]}"#)
        XCTAssertTrue(plan.isEmpty)
    }

    func testMergeIdsAcceptCommaStringOrArray() {
        let comma = ImportanceScoring.parseConsolidation(
            #"{"operations":[{"op":"merge","ids":"3，4、5","content":"并起来"}]}"#)
        XCTAssertEqual(["3", "4", "5"], comma.operations.first?.ids)
        let array = ImportanceScoring.parseConsolidation(
            #"{"operations":[{"op":"merge","ids":["7","8"],"content":"并起来"}]}"#)
        XCTAssertEqual(["7", "8"], array.operations.first?.ids)
    }

    func testRaiseWithoutImportanceIsDropped() {
        XCTAssertTrue(ImportanceScoring.parseConsolidation(
            #"{"operations":[{"op":"raise","ids":["1"]}]}"#).isEmpty)
    }

    func testChineseOperationNamesRecognized() {
        let raw = """
        {"operations":[
          {"op":"保留","ids":["1"]},
          {"op":"合并","ids":["2","3"],"content":"并起来"},
          {"op":"归档","ids":["4"]},
          {"op":"忘记","ids":["5"]},
          {"op":"提权","ids":["6"],"importance":4}
        ]}
        """
        let ops = ImportanceScoring.parseConsolidation(raw).operations.map(\.op)
        XCTAssertEqual([MemoryOp.keep, .merge, .archive, .forget, .raise], ops)
    }

    // MARK: 3. 越界与上限

    func testOutOfRangeImportanceIsClamped() {
        let plan = ImportanceScoring.parseConsolidation(
            """
            {"operations":[
               {"op":"raise","ids":["1"],"importance":9},
               {"op":"raise","ids":["2"],"importance":-4},
               {"op":"raise","ids":["3"],"importance":"5"}
             ]}
            """)
        XCTAssertEqual([5, 1, 5], plan.operations.map(\.importance))
    }

    func testIdsAreDedupedAndCapped() {
        let ids = (1...12).map { "\"\($0)\"" }.joined(separator: ",")
        let plan = ImportanceScoring.parseConsolidation(
            #"{"operations":[{"op":"keep","ids":[\#(ids), "1", "2"]}]}"#)
        guard let got = plan.operations.first?.ids else { return XCTFail("应该有一条指令") }
        XCTAssertEqual(ImportanceScoring.MAX_IDS_PER_OPERATION, got.count)
        XCTAssertEqual(got.count, Set(got).count)
    }

    func testOperationCountAndContentAreTruncated() {
        let ops = (1...20).map { #"{"op":"keep","ids":["\#($0)"]}"# }.joined(separator: ",")
        let plan = ImportanceScoring.parseConsolidation(#"{"operations":[\#(ops)]}"#)
        XCTAssertEqual(ImportanceScoring.MAX_OPERATIONS, plan.operations.count)

        let long = String(repeating: "猫", count: 2000)
        let merged = ImportanceScoring.parseConsolidation(
            #"{"operations":[{"op":"merge","ids":["1","2"],"content":"\#(long)"}]}"#).operations.first
        XCTAssertLessThanOrEqual(merged?.content?.count ?? 0, 800)
    }

    // MARK: 4. parseSearchScore

    func testNormalScoreParses() {
        guard let score = ImportanceScoring.parseSearchScore(
            #"{"important":true,"importance":4,"takeaway":"对方常去的书店关门了","drawer":"USER"}"#
        ) else {
            return XCTFail("应该能解析")
        }
        XCTAssertTrue(score.important)
        XCTAssertEqual(4, score.importance)
        XCTAssertEqual("对方常去的书店关门了", score.takeaway)
        XCTAssertEqual(MemoryDrawer.user, score.drawer)
        XCTAssertTrue(ImportanceScoring.shouldRemember(score))
    }

    func testMissingFieldsDegradeToDoNotRemember() {
        guard let score = ImportanceScoring.parseSearchScore(#"{"takeaway":"随便一条"}"#) else {
            return XCTFail("应该能解析")
        }
        XCTAssertFalse(score.important)
        XCTAssertEqual(ImportanceScoring.MIN_IMPORTANCE, score.importance)
        XCTAssertEqual(MemoryDrawer.user, score.drawer)
        XCTAssertFalse(ImportanceScoring.shouldRemember(score))
    }

    func testOnlyImportantFallsBackToFloor() {
        guard let score = ImportanceScoring.parseSearchScore(#"{"important":true}"#) else {
            return XCTFail("应该能解析")
        }
        XCTAssertEqual(ImportanceScoring.REMEMBER_FLOOR, score.importance)
        XCTAssertTrue(ImportanceScoring.shouldRemember(score))
    }

    func testOnlyImportanceInfersImportantFlag() {
        XCTAssertTrue(ImportanceScoring.parseSearchScore(#"{"importance":5}"#)?.important == true)
        XCTAssertFalse(ImportanceScoring.parseSearchScore(#"{"importance":2}"#)?.important == true)
    }

    func testBadJsonScoreIsNil() {
        XCTAssertNil(ImportanceScoring.parseSearchScore(""))
        XCTAssertNil(ImportanceScoring.parseSearchScore("我看了下，没什么重要的"))
        XCTAssertNil(ImportanceScoring.parseSearchScore("{坏的"))
    }

    func testScoreImportanceIsClamped() {
        XCTAssertEqual(5, ImportanceScoring.parseSearchScore(#"{"importance":9}"#)?.importance)
        XCTAssertEqual(1, ImportanceScoring.parseSearchScore(#"{"importance":-9}"#)?.importance)
        XCTAssertEqual(5, ImportanceScoring.clampImportance(9))
        XCTAssertEqual(1, ImportanceScoring.clampImportance(0))
    }

    func testUnknownDrawerFallsBackToUser() {
        XCTAssertEqual(MemoryDrawer.user,
                       ImportanceScoring.parseSearchScore(
                        #"{"important":true,"importance":4,"drawer":"怪抽屉"}"#)?.drawer)
        XCTAssertEqual(MemoryDrawer.event,
                       ImportanceScoring.parseSearchScore(
                        #"{"important":true,"importance":4,"drawer":"EVENT"}"#)?.drawer)
        XCTAssertEqual(MemoryDrawer.selfDrawer,
                       ImportanceScoring.parseSearchScore(
                        #"{"important":true,"importance":4,"drawer":"SELF"}"#)?.drawer)
        XCTAssertEqual(MemoryDrawer.user,
                       ImportanceScoring.parseSearchScore(
                        #"{"important":true,"importance":4,"drawer":"FACT"}"#)?.drawer)
        XCTAssertEqual(MemoryDrawer.event,
                       ImportanceScoring.parseSearchScore(
                        #"{"important":true,"importance":4,"drawer":"RELATIONSHIP"}"#)?.drawer)
    }

    func testStringBooleansAndCodeFenceWork() {
        let raw = """
        ```json
        {"important":"是","importance":"4","takeaway":"一件值得记的事"}
        ```
        """
        guard let score = ImportanceScoring.parseSearchScore(raw) else { return XCTFail("应该能解析") }
        XCTAssertTrue(score.important)
        XCTAssertEqual(4, score.importance)
    }

    // MARK: 5. shouldRemember 与提示词

    func testLowImportanceIsNotRemembered() {
        XCTAssertFalse(ImportanceScoring.shouldRemember(
            SearchScore(important: true, importance: 2, takeaway: "x")))
        XCTAssertFalse(ImportanceScoring.shouldRemember(
            SearchScore(important: false, importance: 5, takeaway: "x")))
        XCTAssertTrue(ImportanceScoring.shouldRemember(
            SearchScore(important: true, importance: 3, takeaway: "x")))
    }

    func testBothPromptsShareCriteria() {
        let consolidator = ImportanceScoring.consolidationSystemPrompt(personaSummary: "小满")
        XCTAssertTrue(consolidator.contains("小满"))
        XCTAssertTrue(consolidator.contains("operations"))
        XCTAssertTrue(consolidator.contains("值得记"))

        let scorer = ImportanceScoring.searchScoringSystemPrompt(personaSummary: "小满")
        XCTAssertTrue(scorer.contains("important"))
        XCTAssertTrue(scorer.contains("值得记"))
        // 判据是同一份常量，改一处两处一起变。
        let head = String(ImportanceScoring.SHARED_CRITERIA.prefix(20))
        XCTAssertTrue(consolidator.contains(head))
        XCTAssertTrue(scorer.contains(head))
    }

    func testPromptsWithoutPersonaStillSaySomething() {
        let prompt = ImportanceScoring.searchScoringSystemPrompt(personaSummary: "   ")
        XCTAssertTrue(prompt.contains("他"))
        XCTAssertTrue(prompt.contains("drawer"))
    }

    // MARK: 6. tags 与 happenedAt

    func testTagsAcceptArrayAndCommaStrings() {
        XCTAssertEqual(["妹妹", "失眠"], ImportanceScoring.parseTags(#"["妹妹","失眠"]"#))
        XCTAssertEqual(["妹妹", "失眠", "杭州"], ImportanceScoring.parseTags("妹妹, 失眠、杭州"))
        XCTAssertEqual(["妹妹"], ImportanceScoring.parseTags(#"["妹妹"]"#))
        XCTAssertEqual(["妹妹"], ImportanceScoring.parseTags(#"["妹妹","妹妹"]"#))
    }

    func testTagsBadDataAndLimits() {
        XCTAssertEqual([], ImportanceScoring.parseTags(nil))
        XCTAssertEqual([], ImportanceScoring.parseTags(""))
        XCTAssertEqual([], ImportanceScoring.parseTags("null"))
        let many = ImportanceScoring.parseTags(#"["一","二","三","四","五","六","七"]"#)
        XCTAssertEqual(MemoryTags.MAX_TAGS, many.count, "最多 5 个标签")
        let longTag = ImportanceScoring.parseTags(#"["\#(String(repeating: "很长", count: 20))"]"#)
        XCTAssertEqual(MemoryTags.MAX_TAG_CHARS, longTag.first?.count, "单个标签会被截断")
    }

    func testRelativeTimeConvertsToMillis() {
        let now: Int64 = 1_760_000_000_000
        let day: Int64 = 86_400_000

        guard let yesterday = ImportanceScoring.parseRelativeTime("昨天", now: now) else {
            return XCTFail("「昨天」应该能换算")
        }
        XCTAssertEqual(ChineseTerms.timeSpanOf("昨天", now: now)?.center, yesterday,
                       "取的是「昨天」这段时间的中点")
        XCTAssertTrue(yesterday >= now - 2 * day && yesterday <= now, "应该落在昨天附近")

        guard let winter = ImportanceScoring.parseRelativeTime("去年冬天", now: now) else {
            return XCTFail("「去年冬天」也要能换算")
        }
        XCTAssertLessThan(winter, now, "不该被算成今天")

        XCTAssertNil(ImportanceScoring.parseRelativeTime("他妹妹", now: now), "没有时间说法就不猜")
        XCTAssertNil(ImportanceScoring.parseRelativeTime(nil, now: now))
        XCTAssertNil(ImportanceScoring.parseRelativeTime("", now: now))
        XCTAssertEqual(1_760_000_000_000,
                       ImportanceScoring.parseRelativeTime("1760000000000", now: now),
                       "毫秒时间戳原样采用")
        XCTAssertNil(ImportanceScoring.parseRelativeTime("2025", now: now), "年份不是时间戳")
    }

    func testConsolidatorPromptExplainsTimeAndTags() {
        let prompt = ImportanceScoring.consolidationSystemPrompt(personaSummary: "小满")
        XCTAssertTrue(prompt.contains("标签"), "要讲清标签是什么")
        XCTAssertTrue(prompt.contains("发生在"), "要讲清 happenedAt 的语义")
        XCTAssertTrue(prompt.contains("发生") && prompt.contains("时间"),
                      "要讲清「发生时间 ≠ 记下来的时间」")
        XCTAssertTrue(prompt.contains("合并时不许丢掉时间和标签"), "merge 时必须保留时间与标签")
    }

    func testEmptyPlanMeansNothingToDo() {
        XCTAssertTrue(ConsolidationPlan().isEmpty)
        XCTAssertFalse(ConsolidationPlan(operations: [MemoryOperation(op: .keep, ids: ["1"])]).isEmpty)
    }

    // MARK: MemoryTags

    func testMemoryTagsEncodeDecodeRoundTrip() {
        let encoded = MemoryTags.encode(["妹妹", "失眠"])
        XCTAssertEqual(["妹妹", "失眠"], MemoryTags.decode(encoded))
        XCTAssertNil(MemoryTags.encode([]))
        XCTAssertEqual([], MemoryTags.decode("不是 JSON"))
        XCTAssertEqual([], MemoryTags.decode("null"))
    }

    func testMemoryTagsSanitize() {
        XCTAssertEqual(["妹妹", "失眠"], MemoryTags.sanitize([" 妹妹 ", "失眠", "妹妹"]))
        XCTAssertEqual(["一", "二", "三", "四", "五"], MemoryTags.sanitize(["一", "二", "三", "四", "五", "六"]))
    }
}
