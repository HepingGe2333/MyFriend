import Foundation
import XCTest
@testable import MyFriendKit

/// 主动唤醒：协议解析 + 规则落库（`Services/ProactiveRuleService.swift`）。
///
/// 对齐 Android 的 `ProactiveProtocol.kt` / `ProactiveRepository.createFromProposal`：
/// 代码块语言是 `myfriend-wake`、kind 只有 daily / interval、时间写成 HH:mm、
/// 同一条节奏重复提议只更新不新增、`nextTriggerAt` 按本地时区算。
final class ProactiveRuleServiceTests: XCTestCase {

    private var temp: TempDatabase!
    private var service: ProactiveRuleService!
    private let personaId = "persona-1"
    private let sessionId = "session-1"
    private var now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        try temp.stores.personas.upsert(TestData.persona(id: personaId))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
        // 固定时区：presenter 的「今天/明天」与 createFromProposal 的 nextTriggerAt 必须用同一本日历，
        // 否则 CI（UTC）上 22:30 会被算成次日 06:30。
        service = ProactiveRuleService(
            stores: temp.stores, now: { self.now },
            calendar: ServiceTime.calendar(TimeZone(identifier: "Asia/Shanghai")!)
        )
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 协议解析

    func testParsesDailyProposalLikeAndroid() throws {
        let payload = #"{"kind":"daily","time":"22:30","note":"每晚睡前问候","hint":"轻声问今天过得怎么样"}"#
        let proposal = try XCTUnwrap(ProactiveProtocol.parse(payload))
        XCTAssertEqual(proposal.kind, .daily)
        XCTAssertEqual(proposal.timeOfDayMinutes, 22 * 60 + 30)
        XCTAssertNil(proposal.intervalMinutes)
        XCTAssertEqual(proposal.note, "每晚睡前问候")
        XCTAssertEqual(proposal.hint, "轻声问今天过得怎么样")
    }

    func testParsesIntervalProposalAndClampsHours() throws {
        let proposal = try XCTUnwrap(ProactiveProtocol.parse(
            #"{"kind":"interval","intervalHours":3,"note":"每三小时问一句"}"#
        ))
        XCTAssertEqual(proposal.kind, .interval)
        XCTAssertEqual(proposal.intervalMinutes, 180)
        // 越界的间隔不该被接受（Android 也是直接返回 null）。
        XCTAssertNil(ProactiveProtocol.parse(#"{"kind":"interval","intervalHours":48}"#))
        XCTAssertNil(ProactiveProtocol.parse(#"{"kind":"interval"}"#))
    }

    func testParsesOnceAliasAsDaily() throws {
        // 模型偶尔写 once（「就这一次」）：按「每天这个点」处理比丢掉更接近本意。
        let proposal = try XCTUnwrap(ProactiveProtocol.parse(
            #"{"kind":"once","time":"09:15","note":"早上问一句"}"#
        ))
        XCTAssertEqual(proposal.kind, .daily)
        XCTAssertEqual(proposal.timeOfDayMinutes, 9 * 60 + 15)
    }

    func testBadPayloadNeverThrows() {
        for payload in ["", "不是 JSON", "[]", #"{"kind":"daily"}"#, #"{"kind":"daily","time":"25:00"}"#] {
            XCTAssertNil(ProactiveProtocol.parse(payload), "「\(payload)」不该被当成一条规则")
        }
    }

    func testParseTimeOfDayIsLenientAboutFullWidthColon() {
        XCTAssertEqual(ProactiveProtocol.parseTimeOfDay("22：30"), 22 * 60 + 30)
        XCTAssertEqual(ProactiveProtocol.parseTimeOfDay(" 07:00 "), 7 * 60)
        XCTAssertNil(ProactiveProtocol.parseTimeOfDay("7 点"))
        XCTAssertNil(ProactiveProtocol.parseTimeOfDay(nil))
        XCTAssertEqual(ProactiveProtocol.formatTimeOfDay(22 * 60 + 30), "22:30")
        XCTAssertEqual(ProactiveProtocol.formatTimeOfDay(-30), "23:30")
    }

    func testExtractStripsBlockAndKeepsBody() throws {
        let text = """
        好呀，那我们就这样约定。

        ```myfriend-wake
        {"kind":"daily","time":"22:30","note":"每晚睡觉前问候"}
        ```
        """
        let extracted = ProactiveProtocol.extract(text)
        XCTAssertEqual(extracted.body, "好呀，那我们就这样约定。")
        XCTAssertEqual(extracted.proposal?.timeOfDayMinutes, 22 * 60 + 30)
        XCTAssertEqual(extracted.payloadJson, #"{"kind":"daily","time":"22:30","note":"每晚睡觉前问候"}"#)
    }

    func testExtractWithoutBlockReturnsTextUnchanged() {
        let extracted = ProactiveProtocol.extract("  今天过得怎么样？  ")
        XCTAssertEqual(extracted.body, "今天过得怎么样？")
        XCTAssertNil(extracted.proposal)
        XCTAssertNil(extracted.payloadJson)
    }

    func testCapabilityPromptIsTheAndroidOne() {
        XCTAssertEqual(ProactiveProtocol.blockLanguage, "myfriend-wake")
        let prompt = ProactiveProtocol.capabilityPrompt
        XCTAssertTrue(prompt.contains("## 你可以主动联系对方"))
        XCTAssertTrue(prompt.contains("```myfriend-wake"))
        XCTAssertTrue(prompt.contains("intervalHours"))
        // PromptBuilder 里那个旧入口现在是同一个文案来源。
        XCTAssertEqual(ProactiveCapabilityPrompt.text, prompt)
        XCTAssertEqual(ProactiveCapabilityPrompt.blockLanguage, "myfriend-wake")
    }

    // MARK: - 落库

    func testCreateFromProposalWritesRule() throws {
        let proposal = try XCTUnwrap(ProactiveProtocol.parse(
            #"{"kind":"daily","time":"22:30","note":"每晚睡前问候","hint":"轻声问"}"#
        ))
        let id = try service.createFromProposal(
            personaId: personaId, sessionId: sessionId, proposal: proposal, at: now
        ).get()
        let rule = try XCTUnwrap(try temp.stores.proactiveRules.byId(id))
        XCTAssertEqual(rule.personaId, personaId)
        XCTAssertEqual(rule.sessionId, sessionId)
        XCTAssertEqual(rule.kind, .daily)
        XCTAssertEqual(rule.timeOfDayMinutes, 22 * 60 + 30)
        XCTAssertEqual(rule.note, "每晚睡前问候")
        XCTAssertEqual(rule.hint, "轻声问")
        XCTAssertTrue(rule.enabled)
        XCTAssertEqual(rule.source, .ai)
        XCTAssertEqual(rule.createdAt, now)
        XCTAssertEqual(rule.updatedAt, now)
        XCTAssertGreaterThan(rule.nextTriggerAt, now)
    }

    func testSameRhythmIsUpdatedInsteadOfDuplicated() throws {
        let first = try XCTUnwrap(ProactiveProtocol.parse(
            #"{"kind":"daily","time":"22:30","note":"每晚问候"}"#
        ))
        let id = try service.createFromProposal(
            personaId: personaId, sessionId: sessionId, proposal: first, at: now
        ).get()
        let second = try XCTUnwrap(ProactiveProtocol.parse(
            #"{"kind":"daily","time":"22:30","note":"每晚十点半问一句"}"#
        ))
        let again = try service.createFromProposal(
            personaId: personaId, sessionId: sessionId, proposal: second, at: now + 60_000
        ).get()
        XCTAssertEqual(id, again, "同样的节奏只更新那一条")
        let rules = try temp.stores.proactiveRules.byPersona(personaId)
        XCTAssertEqual(rules.count, 1)
        XCTAssertEqual(rules.first?.note, "每晚十点半问一句")
        XCTAssertEqual(rules.first?.updatedAt, now + 60_000)
    }

    func testCreateFromJsonRejectsUnparsablePayload() {
        let result = service.createFromJson(
            personaId: personaId, sessionId: sessionId, proposalJson: "{}"
        )
        XCTAssertThrowsError(try result.get())
        XCTAssertTrue(try! temp.stores.proactiveRules.byPersona(personaId).isEmpty)
    }

    func testSetEnabledClearsNextTriggerWhenOff() throws {
        let proposal = try XCTUnwrap(ProactiveProtocol.parse(#"{"kind":"daily","time":"08:00"}"#))
        let id = try service.createFromProposal(
            personaId: personaId, sessionId: sessionId, proposal: proposal, at: now
        ).get()
        XCTAssertTrue(service.setEnabled(id: id, enabled: false, at: now))
        let off = try XCTUnwrap(try temp.stores.proactiveRules.byId(id))
        XCTAssertFalse(off.enabled)
        XCTAssertEqual(off.nextTriggerAt, 0, "关掉的规则不该留着到点时间")
        XCTAssertTrue(service.setEnabled(id: id, enabled: true, at: now))
        let on = try XCTUnwrap(try temp.stores.proactiveRules.byId(id))
        XCTAssertTrue(on.enabled)
        XCTAssertGreaterThan(on.nextTriggerAt, now)
    }

    func testDueRulesAreVisibleToTheAgentLoop() throws {
        let proposal = try XCTUnwrap(ProactiveProtocol.parse(#"{"kind":"interval","intervalHours":1}"#))
        let id = try service.createFromProposal(
            personaId: personaId, sessionId: sessionId, proposal: proposal, at: now
        ).get()
        XCTAssertTrue(service.due(at: now).isEmpty, "还没到点")
        let rule = try XCTUnwrap(try temp.stores.proactiveRules.byId(id))
        XCTAssertEqual(service.due(at: rule.nextTriggerAt).count, 1, "到点了就要能被调度读到")
    }

    // MARK: - 列表页：映射 / 开关 / 删除

    private var shanghai: Calendar { ServiceTime.calendar(TimeZone(identifier: "Asia/Shanghai")!) }

    func testPresenterMapsDailyRule() throws {
        let proposal = try XCTUnwrap(ProactiveProtocol.parse(
            #"{"kind":"daily","time":"22:30","note":"睡前问一句今天过得怎么样"}"#
        ))
        let id = try service.createFromProposal(
            personaId: personaId, sessionId: sessionId, proposal: proposal, at: now
        ).get()
        let rule = try XCTUnwrap(try temp.stores.proactiveRules.byId(id))
        let morning = ServiceTime.startOfDay(now, calendar: shanghai) + 9 * 60 * 60_000
        let row = ProactiveRulePresenter.present(
            rule: rule, personaName: "林深", now: morning, calendar: shanghai,
            locale: Locale(identifier: "zh_CN")
        )
        XCTAssertEqual(row.personaName, "林深")
        XCTAssertEqual(row.note, "睡前问一句今天过得怎么样")
        XCTAssertEqual(row.kindLabel, "每天")
        XCTAssertEqual(row.scheduleLabel, "每天 22:30")
        XCTAssertEqual(row.nextLabel, "下次大概 今天 22:30 前后")
        XCTAssertEqual(row.sourceLabel, "他自己安排的", "createFromProposal 建的是 AI 规则")
        XCTAssertTrue(row.enabled)
    }

    func testPresenterMapsIntervalAndDisabledRule() throws {
        let rule = ProactiveRuleEntity(
            id: "rule-2", personaId: personaId, kind: .interval,
            intervalMinutes: 3 * 60, hint: "随口问", note: "",
            enabled: false, pending: false, source: .manual, nextTriggerAt: now + 3 * 60 * 60_000
        )
        let row = ProactiveRulePresenter.present(
            rule: rule, personaName: nil, now: now, calendar: shanghai
        )
        XCTAssertEqual(row.personaName, "他", "没有名字时兜底成「他」")
        XCTAssertEqual(row.note, ProactiveRulePresenter.emptyNoteFallback, "没写说明时兜底")
        XCTAssertEqual(row.scheduleLabel, "每 3 小时")
        XCTAssertEqual(row.nextLabel, "现在关着")
        XCTAssertEqual(row.sourceLabel, "你设的")
        XCTAssertFalse(row.enabled)
    }

    func testPresenterFormatsTomorrowAndLaterDays() {
        let tomorrow = ServiceTime.startOfDay(now, calendar: shanghai) + 24 * 60 * 60_000 + 7 * 60 * 60_000
        let tomorrowRow = ProactiveRulePresenter.present(
            rule: ProactiveRuleEntity(
                id: "r-tomorrow", personaId: personaId, kind: .daily,
                timeOfDayMinutes: 7 * 60, note: "早安", nextTriggerAt: tomorrow
            ),
            personaName: "林深", now: now, calendar: shanghai
        )
        XCTAssertEqual(tomorrowRow.nextLabel, "下次大概 明天 07:00 前后")

        let later = ServiceTime.startOfDay(now, calendar: shanghai) + 5 * 24 * 60 * 60_000 + 20 * 60 * 60_000
        let laterRow = ProactiveRulePresenter.present(
            rule: ProactiveRuleEntity(
                id: "r-later", personaId: personaId, kind: .daily,
                timeOfDayMinutes: 20 * 60, note: "晚上好", nextTriggerAt: later
            ),
            personaName: "林深", now: now, calendar: shanghai,
            locale: Locale(identifier: "zh_CN")
        )
        XCTAssertTrue(laterRow.nextLabel.hasPrefix("下次大概 "), laterRow.nextLabel)
        XCTAssertTrue(laterRow.nextLabel.contains("20:00"), laterRow.nextLabel)
        XCTAssertTrue(laterRow.nextLabel.contains("月"), "隔了几天的要带日期")
    }

    func testPresenterSortsEnabledFirstThenByNextTrigger() throws {
        let rules = [
            ProactiveRuleEntity(id: "off", personaId: personaId, kind: .daily,
                                timeOfDayMinutes: 600, note: "关着的", enabled: false,
                                nextTriggerAt: now + 1_000),
            ProactiveRuleEntity(id: "late", personaId: personaId, kind: .daily,
                                timeOfDayMinutes: 720, note: "晚的", enabled: true,
                                nextTriggerAt: now + 5 * 60_000),
            ProactiveRuleEntity(id: "early", personaId: personaId, kind: .daily,
                                timeOfDayMinutes: 660, note: "早的", enabled: true,
                                nextTriggerAt: now + 2 * 60_000)
        ]
        let rows = ProactiveRulePresenter.present(rules: rules, personas: [TestData.persona(id: personaId)])
        XCTAssertEqual(rows.map(\.id), ["early", "late", "off"], "开着的按下次触发排前面，关掉的排最后")
    }

    func testDeleteRemovesOnlyThatRule() throws {
        let proposal = try XCTUnwrap(ProactiveProtocol.parse(#"{"kind":"daily","time":"08:00"}"#))
        let keep = try service.createFromProposal(
            personaId: personaId, sessionId: sessionId, proposal: proposal, at: now
        ).get()
        let drop = try service.createManual(
            personaId: personaId, sessionId: sessionId, kind: .interval,
            timeOfDayMinutes: nil, intervalMinutes: 120, hint: "", note: "两小时问一次"
        ).get()
        XCTAssertEqual(try temp.stores.proactiveRules.byPersona(personaId).count, 2)
        XCTAssertTrue(service.delete(id: drop))
        XCTAssertFalse(service.delete(id: drop), "重复删同一个要返回 false，不崩")
        let left = try temp.stores.proactiveRules.byPersona(personaId)
        XCTAssertEqual(left.map(\.id), [keep])
    }

    func testTogglingOffThenOnRecomputesNextTrigger() throws {
        let rule = ProactiveRuleEntity(
            id: "r-1", personaId: personaId, kind: .daily, timeOfDayMinutes: 8 * 60,
            hint: "", note: "早上问一句", enabled: true, nextTriggerAt: now - 60 * 60_000
        )
        try temp.stores.proactiveRules.upsert(rule)
        XCTAssertTrue(service.setEnabled(id: "r-1", enabled: false, at: now))
        XCTAssertEqual(try temp.stores.proactiveRules.byId("r-1")?.nextTriggerAt, 0)

        XCTAssertTrue(service.setEnabled(id: "r-1", enabled: true, at: now))
        let reopened = try XCTUnwrap(try temp.stores.proactiveRules.byId("r-1"))
        XCTAssertTrue(reopened.enabled)
        XCTAssertGreaterThan(reopened.nextTriggerAt, now, "重新打开时要排到下一个 08:00，而不是沿用过期时间")
    }

    // MARK: - 时间

    func testComputeNextTriggerDailyRollsForward() {
        let calendar = ServiceTime.calendar(TimeZone(identifier: "Asia/Shanghai")!)
        let base = ServiceTime.startOfDay(now, calendar: calendar) + 8 * 60 * 60_000  // 当地 08:00
        // 今天晚于 08:00 的 22:30 → 今天。
        XCTAssertEqual(
            ProactiveRuleService.computeNextTrigger(
                kind: .daily, timeOfDayMinutes: 22 * 60 + 30, intervalMinutes: nil,
                from: base, calendar: calendar
            ),
            base + 14 * 60 * 60_000 + 30 * 60_000
        )
        // 已经过了 07:00 → 明天。
        XCTAssertEqual(
            ProactiveRuleService.computeNextTrigger(
                kind: .daily, timeOfDayMinutes: 7 * 60, intervalMinutes: nil,
                from: base, calendar: calendar
            ),
            base + 24 * 60 * 60_000 - 60 * 60_000
        )
    }

    func testComputeNextTriggerIntervalHasFloor() {
        XCTAssertEqual(
            ProactiveRuleService.computeNextTrigger(
                kind: .interval, timeOfDayMinutes: nil, intervalMinutes: 180, from: now
            ),
            now + 180 * 60_000
        )
        // 间隔小于 15 分钟时夹到 15 分钟（与 Android 的 coerceAtLeast(15) 一致）。
        XCTAssertEqual(
            ProactiveRuleService.computeNextTrigger(
                kind: .interval, timeOfDayMinutes: nil, intervalMinutes: 1, from: now
            ),
            now + 15 * 60_000
        )
    }
}
