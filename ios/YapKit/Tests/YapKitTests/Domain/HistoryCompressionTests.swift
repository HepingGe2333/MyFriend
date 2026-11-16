import XCTest
@testable import YapKit

/// 长会话滚动摘要的纯逻辑单测（移植自 Android 的 HistoryCompressionTest）。
///
/// 只测「什么时候该压、压的时候怎么跟他说、写回来的能不能用」这三件事：
/// 不碰数据库、不碰网络。压缩本身是为了「他还记得住」，
/// 所以这里的断言也刻意盯着「最近几条必须逐字留着」和「不许编造」这两条底线。
final class HistoryCompressionTests: XCTestCase {

    let step: Int64 = 1_000

    /// 造一串按时间递增的消息：0 是我说的，1 是你说的，正文长度固定，方便算字符。
    func messages(_ count: Int, startAt: Int64 = 1_000, chars: Int = 20) -> [MsgView] {
        (0..<count).map { index in
            var text = "第\(index)句"
            if text.count < chars {
                text += String(repeating: "话", count: chars - text.count)
            }
            return MsgView(id: "m\(index)",
                           at: startAt + Int64(index) * step,
                           fromUser: index % 2 == 1,
                           text: text)
        }
    }

    // MARK: plan：什么时候该压

    func testEmptyListCompressesNothing() {
        let plan = HistoryCompression.plan(messages: [], summaryUpTo: nil)
        XCTAssertFalse(plan.shouldCompress)
        XCTAssertNil(plan.compressUpTo)
        XCTAssertNil(plan.keepFrom)
        XCTAssertFalse(plan.reason.isEmpty)
    }

    func testFiveMessagesNotCompressed() {
        let plan = HistoryCompression.plan(messages: messages(5), summaryUpTo: nil)
        XCTAssertFalse(plan.shouldCompress)
        // 全部逐字留着，所以「从第一条开始保留」。
        XCTAssertEqual(1_000, plan.keepFrom)
    }

    func testExactlyTwentyFourNotCompressed() {
        let plan = HistoryCompression.plan(messages: messages(24), summaryUpTo: nil)
        XCTAssertFalse(plan.shouldCompress)
        XCTAssertEqual(1_000, plan.keepFrom)
    }

    func testTwentyFiveMessagesStillNotWorthIt() {
        // 多出的一条比「值得压一批」的门槛（12 条）少得多，为它花一次调用不划算。
        let plan = HistoryCompression.plan(messages: messages(25), summaryUpTo: nil)
        XCTAssertFalse(plan.shouldCompress)
        // 最近 24 条逐字留着，从第 2 条开始。
        XCTAssertEqual(2_000, plan.keepFrom)
    }

    func testThirtySixMessagesCompressEarliestTwelve() {
        let plan = HistoryCompression.plan(messages: messages(36), summaryUpTo: nil)
        XCTAssertTrue(plan.shouldCompress)
        XCTAssertEqual(12_000, plan.compressUpTo)      // 第 12 条（索引 11）
        XCTAssertEqual(13_000, plan.keepFrom)          // 最近 24 条的第一条
        XCTAssertLessThan(plan.compressUpTo!, plan.keepFrom!)
    }

    func testHundredMessagesOnlyCompressOneBatchOfForty() {
        let plan = HistoryCompression.plan(messages: messages(100), summaryUpTo: nil)
        XCTAssertTrue(plan.shouldCompress)
        XCTAssertEqual(40_000, plan.compressUpTo)      // 索引 39
        XCTAssertEqual(77_000, plan.keepFrom)          // 索引 76
    }

    func testAlreadyCoveredPartIsNotCompressedAgain() {
        let all = messages(40)
        // 摘要已经盖到索引 19。
        let plan = HistoryCompression.plan(messages: all, summaryUpTo: all[19].at)
        XCTAssertFalse(plan.shouldCompress)
        XCTAssertEqual(17_000, plan.keepFrom)
        XCTAssertTrue(plan.reason.contains("摘要"))
    }

    func testPartiallyCoveredCompressesOnlyTheRest() {
        let all = messages(60)
        let plan = HistoryCompression.plan(messages: all, summaryUpTo: all[19].at)
        XCTAssertTrue(plan.shouldCompress)
        XCTAssertEqual(all[35].at, plan.compressUpTo)   // 索引 20..35 共 16 条
        XCTAssertEqual(all[36].at, plan.keepFrom)
    }

    func testHugeCharsCompressEvenWithFewMessages() {
        // 三条很长的话（每条 9000 字）就够撑爆上下文了，等不到 12 条。
        let long: [MsgView] = (0..<27).map { (index: Int) -> MsgView in
            // 拆成局部常量：整段构造函数里的三元 + String(repeating:) 在
            // 较慢的 Swift 工具链上会「类型检查超时」（CI 实测）。
            let at: Int64 = 1_000 + Int64(index) * step
            let fromUser: Bool = index % 2 == 0
            let text: String = index < 3 ? String(repeating: "长话", count: 4_500) : "短的"
            return MsgView(id: "m\(index)", at: at, fromUser: fromUser, text: text)
        }
        let plan = HistoryCompression.plan(messages: long, summaryUpTo: nil, maxChars: 24_000)
        XCTAssertTrue(plan.shouldCompress)
        XCTAssertEqual(long[2].at, plan.compressUpTo)
    }

    func testNegativeVerbatimCountMeansKeepAtLeastOne() {
        let all = messages(40)
        let plan = HistoryCompression.plan(messages: all, summaryUpTo: nil, maxVerbatim: -5)
        XCTAssertTrue(plan.shouldCompress)
        XCTAssertEqual(all.last!.at, plan.keepFrom)
        XCTAssertLessThan(plan.compressUpTo!, plan.keepFrom!)
    }

    func testNegativeBatchSizeMeansAtLeastOne() {
        let all = messages(60)
        let plan = HistoryCompression.plan(messages: all, summaryUpTo: nil, maxBatch: -3)
        XCTAssertTrue(plan.shouldCompress)
        XCTAssertEqual(all[0].at, plan.compressUpTo)
    }

    func testBrokenThresholdStillCompressesSomething() {
        let all = messages(30)
        let plan = HistoryCompression.plan(messages: all, summaryUpTo: nil, minToCompress: -9)
        XCTAssertTrue(plan.shouldCompress)
        XCTAssertNotNil(plan.compressUpTo)
    }

    func testOutOfOrderInputIsSortedBeforePlanning() {
        let ordered = messages(60)
        let shuffled = ordered.shuffled()
        let a = HistoryCompression.plan(messages: ordered, summaryUpTo: nil)
        let b = HistoryCompression.plan(messages: shuffled, summaryUpTo: nil)
        XCTAssertEqual(a.compressUpTo, b.compressUpTo)
        XCTAssertEqual(a.keepFrom, b.keepFrom)
    }

    func testBadTimestampsIgnored() {
        let valid = messages(40)
        let withBad = valid + [
            MsgView(id: "bad1", at: 0, fromUser: true, text: "时间不明的一句"),
            MsgView(id: "bad2", at: -5, fromUser: false, text: "时间更不明的一句")
        ]
        let a = HistoryCompression.plan(messages: valid, summaryUpTo: nil)
        let b = HistoryCompression.plan(messages: withBad, summaryUpTo: nil)
        XCTAssertEqual(a.compressUpTo, b.compressUpTo)
        XCTAssertEqual(a.keepFrom, b.keepFrom)
    }

    func testSameTimestampsMeansDoNotCompress() {
        // 老消息和「要逐字保留的」落在同一个毫秒上：分不清谁更早，就不动。
        let same = (0..<40).map { index in
            MsgView(id: "m\(index)", at: 5_000, fromUser: index % 2 == 0, text: "第\(index)句")
        }
        let plan = HistoryCompression.plan(messages: same, summaryUpTo: nil)
        XCTAssertFalse(plan.shouldCompress)
        XCTAssertEqual(5_000, plan.keepFrom)
        XCTAssertFalse(plan.reason.isEmpty)
    }

    // MARK: summaryPrompt / mergePrompt

    func testSummaryPromptDemandsFirstPersonAndNoInvention() {
        let prompt = HistoryCompression.summaryPrompt(previous: nil, chunk: messages(12))
        XCTAssertTrue(prompt.contains("第一人称"))
        XCTAssertTrue(prompt.contains("不许编造"))
        XCTAssertTrue(prompt.contains("200 字"))
        // 对话要按「你／我」给进去，不能是「用户／助手」。
        XCTAssertTrue(prompt.contains("你："))
        XCTAssertTrue(prompt.contains("我："))
        // 绝不放行「随便写」这类话术。
        XCTAssertFalse(prompt.contains("随便"))
        XCTAssertFalse(prompt.contains("自由发挥"))
    }

    func testWithPreviousSummaryItAsksForMerge() {
        let prompt = HistoryCompression.summaryPrompt(
            previous: "你提过下个月要去哈尔滨，怕冷。",
            chunk: messages(8)
        )
        XCTAssertTrue(prompt.contains("你提过下个月要去哈尔滨"))
        XCTAssertTrue(prompt.contains("220 字"))
        XCTAssertTrue(prompt.contains("合成一段记忆"))
    }

    func testEmptyChunkDoesNotCrash() {
        let prompt = HistoryCompression.summaryPrompt(previous: nil, chunk: [])
        XCTAssertFalse(prompt.isEmpty)
        XCTAssertTrue(prompt.contains("第一人称"))
        XCTAssertTrue(prompt.contains("没有说出什么内容"))
    }

    func testMergePromptCarriesBothSides() {
        let prompt = HistoryCompression.mergePrompt(
            previous: "你怕冷，说下个月想去哈尔滨。",
            chunkSummary: "你买了件厚羽绒服，说等出发那天穿。"
        )
        XCTAssertTrue(prompt.contains("你怕冷"))
        XCTAssertTrue(prompt.contains("厚羽绒服"))
        XCTAssertTrue(prompt.contains("220 字"))
        XCTAssertTrue(prompt.contains("第一人称"))
    }

    func testSystemPromptForbidsInvention() {
        let system = HistoryCompression.systemPrompt()
        XCTAssertTrue(system.contains("不许编造"))
        XCTAssertTrue(system.contains("第一人称"))
        XCTAssertFalse(system.contains("随便"))
    }

    // MARK: isSummaryAcceptable

    func testNormalMemoryAccepted() {
        XCTAssertTrue(HistoryCompression.isSummaryAcceptable("你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套。"))
    }

    func testTooLongRejected() {
        let tooLong = String(repeating: "你提过下个月要去哈尔滨，怕冷。", count: 30)
        XCTAssertGreaterThan(tooLong.count, HistoryCompression.MAX_SUMMARY_CHARS)
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable(tooLong))
    }

    func testEmptyShortAndPunctuationOnlyRejected() {
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable(""))
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable("   \n\t "))
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable("你怕冷"))              // 3 个字，太短
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable("。。。……！！！……"))     // 标点不是记忆
    }

    func testPlaceholderRejected() {
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable("无"))
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable("没有。"))
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable("（无）"))
    }

    func testIdenticalToPreviousRejected() {
        let previous = "你提过下个月要去哈尔滨，怕冷。"
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable(previous, previous: previous))
        // 标点或空白变了但意思没变的，也算没写新东西。
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable("你提过下个月要去哈尔滨，怕冷", previous: previous))
    }

    func testRefusalRejected() {
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable("抱歉，我无法完成这个请求。"))
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable("对不起，这段内容我不能处理。"))
    }

    func testToolCallAndCodeBlocksRejected() {
        // 真机上出现过：模型把一次 remember 调用当成「回忆」写了回来。
        let toolBlock = """
        嗯，这个我想记下来。

        ```yap-tool
        {"name":"remember","arguments":{"content":"对方怕冷","drawer":"USER","importance":4}}
        ```
        """
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable(toolBlock))
        // 任何代码块都不该成为记忆正文。
        XCTAssertFalse(HistoryCompression.isSummaryAcceptable("你提过下个月要去哈尔滨。\n```kotlin\nval x = 1\n```"))
        // 只是提到工具名、没有围栏的一段正常记忆仍然收下。
        XCTAssertTrue(HistoryCompression.isSummaryAcceptable("你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套。"))
    }

    // MARK: salvageSummary

    func testSalvageRejectsToolCallLeftovers() {
        let raw = """
        嗯，这个我想记下来。

        ```yap-tool
        {"name":"remember","arguments":{"content":"对方怕冷","drawer":"USER","importance":4}}
        ```
        """
        XCTAssertNil(HistoryCompression.salvageSummary(raw))
    }

    func testSalvageKeepsBodyAroundCodeBlock() {
        let raw = """
        你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套；还说不想被反复叮嘱。

        ```json
        {"name":"remember"}
        ```
        """
        guard let salvaged = HistoryCompression.salvageSummary(raw) else {
            return XCTFail("应该能洗出一段记忆")
        }
        XCTAssertTrue(salvaged.contains("哈尔滨"))
        XCTAssertFalse(salvaged.contains("```"))
        XCTAssertTrue(HistoryCompression.isSummaryAcceptable(salvaged))
    }

    func testSalvageKeepsWholeFencedSummary() {
        let raw = "```\n你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套；还说不想被反复叮嘱。\n```"
        guard let salvaged = HistoryCompression.salvageSummary(raw) else {
            return XCTFail("整段被围栏包住的摘要应该取出来")
        }
        XCTAssertTrue(HistoryCompression.isSummaryAcceptable(salvaged))
    }

    func testSalvageStripsPrefixesAndQuotes() {
        XCTAssertEqual("你提过下个月要去哈尔滨，怕冷。",
                       HistoryCompression.salvageSummary("摘要：「你提过下个月要去哈尔滨，怕冷。」"))
        XCTAssertEqual("你提过下个月要去哈尔滨，怕冷。",
                       HistoryCompression.salvageSummary("【记忆】你提过下个月要去哈尔滨，怕冷。"))
    }

    func testLegacyCheckIsStricterThanGeneralCheck() {
        // 通用判据认为这半句「合法」，但作为一段回忆太短，启动自检要清掉。
        let stub = "嗯，这个我想记下来。"
        XCTAssertTrue(HistoryCompression.isSummaryAcceptable(stub))
        XCTAssertFalse(HistoryCompression.isLegacySummaryHealthy(stub))
        XCTAssertTrue(HistoryCompression.isLegacySummaryHealthy("你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套。"))
    }

    // MARK: memoryBlock

    func testMemoryBlockOnlyWhenReallyCompressed() {
        let block = HistoryCompression.memoryBlock(summary: "你提过下个月要去哈尔滨，怕冷。", summarizedUpTo: 1_700_000_000_000)
        XCTAssertTrue(block.contains(HistoryCompression.SUMMARY_HEADER))
        XCTAssertTrue(block.contains("哈尔滨"))
    }

    func testMemoryBlockEmptyWhenMissing() {
        XCTAssertEqual("", HistoryCompression.memoryBlock(summary: nil, summarizedUpTo: 1_700_000_000_000))
        XCTAssertEqual("", HistoryCompression.memoryBlock(summary: "   ", summarizedUpTo: 1_700_000_000_000))
        XCTAssertEqual("", HistoryCompression.memoryBlock(summary: "你提过去哈尔滨。", summarizedUpTo: nil))
        XCTAssertEqual("", HistoryCompression.memoryBlock(summary: "你提过去哈尔滨。", summarizedUpTo: 0))
    }

    func testMemoryBlockClipsLongSummary() {
        let long = String(repeating: "哈尔滨", count: 200)
        let block = HistoryCompression.memoryBlock(summary: long, summarizedUpTo: 1)
        XCTAssertTrue(block.hasSuffix("…"))
    }
}
