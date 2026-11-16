import Foundation
import XCTest
@testable import YapKit

/// 记账：写进 `api_usage`、按月聚合、按用途与按会话分桶、清理旧流水。
///
/// 记账是旁路（失败不能让聊天挂），但**记错**会让「这个月用了多少」失真，
/// 所以这里用真实临时库跑，而不是假 DAO。
///
/// 1.9：额度/单价覆盖/拦截全部删除，只统计 token（§五）。
final class UsageRecorderTests: XCTestCase {

    private var databaseURL: URL!
    private var stores: DatabaseStores!
    private var recorder: UsageRecorder!

    override func setUpWithError() throws {
        try super.setUpWithError()
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-usage-tests", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databaseURL = directory.appendingPathComponent("usage-\(UUID().uuidString).sqlite")
        stores = try DatabaseStores.open(path: databaseURL.path)
        recorder = UsageRecorder(stores: stores)
    }

    override func tearDownWithError() throws {
        stores = nil
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(atPath: databaseURL.path + suffix)
        }
        try super.tearDownWithError()
    }

    private var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    func testRecordWritesRowWithCostAndNormalizedKind() throws {
        let ok = recorder.record(
            kind: "  chat ", modelName: " deepseek-chat ",
            usage: TokenUsage(promptTokens: 1_000, completionTokens: 500),
            at: now, personaId: "p-1", sessionId: "s-1"
        )
        XCTAssertTrue(ok)
        let rows = try stores.apiUsage.all()
        XCTAssertEqual(rows.count, 1)
        let row = try XCTUnwrap(rows.first)
        XCTAssertEqual(row.kind, UsageKind.chat, "kind 要归一成大写的已知值")
        XCTAssertEqual(row.modelName, "deepseek-chat", "模型名去空白后落库")
        XCTAssertEqual(row.personaId, "p-1")
        XCTAssertEqual(row.sessionId, "s-1")
        XCTAssertEqual(row.promptTokens, 1_000)
        XCTAssertEqual(row.completionTokens, 500)
        XCTAssertEqual(
            row.costMicroUsd,
            ApiCost.estimateCostMicroUsd(modelName: "deepseek-chat",
                                         usage: TokenUsage(promptTokens: 1_000, completionTokens: 500))
        )
        XCTAssertFalse(row.id.isEmpty)
    }

    func testUnknownKindFallsBackToUnknownAndNegativeTokensClamped() throws {
        recorder.record(kind: "WHAT_IS_THIS", modelName: nil,
                        usage: TokenUsage(promptTokens: -10, completionTokens: -3), at: now)
        let row = try XCTUnwrap(try stores.apiUsage.all().first)
        XCTAssertEqual(row.kind, UsageKind.unknown)
        XCTAssertEqual(row.promptTokens, 0)
        XCTAssertEqual(row.completionTokens, 0)
        XCTAssertEqual(row.costMicroUsd, 0)
    }

    func testMonthTotalsAggregateByKindAndBySession() throws {
        let at = now
        recorder.record(kind: UsageKind.chat, modelName: "deepseek-chat",
                        usage: TokenUsage(promptTokens: 100, completionTokens: 50),
                        at: at, personaId: "p-1", sessionId: "s-1")
        recorder.record(kind: UsageKind.chat, modelName: "deepseek-chat",
                        usage: TokenUsage(promptTokens: 100, completionTokens: 50),
                        at: at + 1, personaId: "p-1", sessionId: "s-1")
        recorder.record(kind: UsageKind.consolidation, modelName: "deepseek-chat",
                        usage: TokenUsage(promptTokens: 200, completionTokens: 0),
                        at: at + 2, personaId: "p-2", sessionId: "s-2")
        recorder.record(kind: "garbage", modelName: "deepseek-chat",
                        usage: TokenUsage(promptTokens: 10, completionTokens: 0), at: at + 3)

        let totals = recorder.monthTotals(now: at + 10)
        XCTAssertEqual(totals.calls, 4)
        XCTAssertEqual(totals.promptTokens, 410)
        XCTAssertEqual(totals.completionTokens, 100)
        XCTAssertEqual(totals.totalTokens, 510)
        XCTAssertFalse(totals.isEmpty)

        XCTAssertEqual(totals.byKind.map(\.kind),
                       [UsageKind.chat, UsageKind.consolidation, UsageKind.unknown],
                       "展示顺序固定：聊天 → 整理 → … → 其他；没用过的不出现")
        XCTAssertEqual(totals.byKind.first?.calls, 2)
        XCTAssertEqual(totals.byKind.first?.label, "聊天")
        XCTAssertEqual(totals.byKind[1].label, "整理记忆")
        XCTAssertEqual(totals.byKind.last?.label, "其他")

        // 按人（会话）排行：s-1 用了 300，s-2 用了 200；没有 sessionId 的老流水不进排行。
        XCTAssertEqual(totals.bySession.map(\.sessionId), ["s-1", "s-2"])
        XCTAssertEqual(totals.bySession.first?.totalTokens, 300)
        XCTAssertEqual(totals.bySession.first?.personaId, "p-1")
        XCTAssertEqual(recorder.monthTokens(now: at + 10), 510)
    }

    /// 上个月的流水不该算进本月。
    func testPreviousMonthRowsAreExcluded() throws {
        let at = now
        let lastMonth = at - 40 * 24 * 3_600_000
        recorder.record(kind: UsageKind.chat, modelName: "deepseek-chat",
                        usage: TokenUsage(promptTokens: 1_000_000, completionTokens: 0), at: lastMonth)
        recorder.record(kind: UsageKind.chat, modelName: "deepseek-chat",
                        usage: TokenUsage(promptTokens: 1_000, completionTokens: 0), at: at)
        let totals = recorder.monthTotals(now: at)
        XCTAssertEqual(totals.calls, 1)
        XCTAssertEqual(totals.promptTokens, 1_000)
    }

    func testEmptyDatabaseReturnsZeroAndEmptyTotals() {
        XCTAssertEqual(recorder.monthTokens(now: now), 0)
        XCTAssertEqual(recorder.monthTotals(now: now), .empty)
        XCTAssertTrue(recorder.monthTotals(now: now).isEmpty)
    }

    func testPruneOlderThanKeepsRecentRows() throws {
        let at = now
        recorder.record(kind: UsageKind.chat, modelName: "m",
                        usage: TokenUsage(promptTokens: 1), at: at - 100 * 24 * 3_600_000)
        recorder.record(kind: UsageKind.chat, modelName: "m",
                        usage: TokenUsage(promptTokens: 1), at: at)
        recorder.pruneOlderThan(days: UsageRecorder.retainDays, now: at)
        let rows = try stores.apiUsage.all()
        XCTAssertEqual(rows.count, 1, "只留最近 90 天")
    }

    func testWipeClearsEverything() throws {
        recorder.record(kind: UsageKind.chat, modelName: "m",
                        usage: TokenUsage(promptTokens: 1), at: now)
        XCTAssertEqual(try stores.apiUsage.count(), 1)
        recorder.wipe()
        XCTAssertEqual(try stores.apiUsage.count(), 0)
    }

    /// 汇总的入口也能吃原始行（设置页直接拿库里一批流水算）。
    func testUsageTotalsFromRows() {
        let rows = [
            ApiUsageEntity(id: "1", at: 10, kind: "CHAT", promptTokens: 5, completionTokens: 7),
            ApiUsageEntity(id: "2", at: 20, kind: "CHAT", promptTokens: 5, completionTokens: 7),
            ApiUsageEntity(id: "3", at: 30, kind: "AGENT", promptTokens: 1, completionTokens: 2)
        ]
        let totals = UsageTotals.from(rows)
        XCTAssertEqual(totals.calls, 3)
        XCTAssertEqual(totals.promptTokens, 11)
        XCTAssertEqual(totals.completionTokens, 16)
        XCTAssertEqual(totals.byKind.map(\.kind), [UsageKind.chat, UsageKind.agent])
        XCTAssertEqual(totals.byKind.first?.calls, 2)
        XCTAssertEqual(totals.bySession, [], "老行为空 sessionId：不进按人排行")
        XCTAssertEqual(UsageTotals.from([]), .empty)
    }

    /// 按人排行只取前 N 名，且用量相同时顺序稳定（界面不该抖）。
    func testSessionRankingIsCappedAndStable() {
        var rows: [ApiUsageEntity] = []
        for index in 0..<14 {
            rows.append(ApiUsageEntity(
                id: "row-\(index)", at: Int64(index), kind: "CHAT", sessionId: "s-\(index)",
                promptTokens: 100 + index, completionTokens: 0
            ))
        }
        let totals = UsageTotals.from(rows)
        XCTAssertEqual(totals.bySession.count, UsageTotals.bySessionLimit)
        XCTAssertEqual(totals.bySession.first?.sessionId, "s-13", "用得最多的排最前")
    }
}
