import Foundation
import XCTest
@testable import MyFriendKit

/// 「两端必须一致」的契约测试（iOS 侧）。
///
/// 用例住在仓库根的 `spec/agent-core/cases/*.json`，Android 的
/// `app/src/test/java/com/myfriend/app/spec/AgentCoreSpecTest.kt` 读的是**同一批文件**。
/// 谁改了实现而没同步另一端，两边必有一边立刻变红。
///
/// 路径不写死：从 `#filePath`（本文件在仓库里的真实路径）往上找
/// 「同时有 `spec/agent-core/cases` 与 `settings.gradle.kts`」的目录。
/// 用例只收纯函数：需要时间的地方由用例显式给 `now`，测试自己补「中午」这种时区无关的锚点。
final class AgentCoreSpecTests: XCTestCase {

    /// 浮点断言容差：`exp()` 在 JVM 与 Darwin libm 上可能差最后一个二进制位。
    private static let tolerance = 1e-9

    // MARK: - 路径与读取

    private static let repoRoot: URL = {
        var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fm = FileManager.default
        while url.path != "/" {
            let cases = url.appendingPathComponent("spec/agent-core/cases")
            if fm.fileExists(atPath: cases.path),
               fm.fileExists(atPath: url.appendingPathComponent("settings.gradle.kts").path) {
                return url
            }
            url = url.deletingLastPathComponent()
        }
        fatalError("找不到仓库根目录（从 #filePath=\(#filePath) 向上没有 spec/agent-core/cases）")
    }()

    private struct SpecCase {
        var name: String
        var input: [String: Any]
        var expect: [String: Any]
    }

    private func loadCases(_ fileName: String) throws -> [SpecCase] {
        let file = Self.repoRoot.appendingPathComponent("spec/agent-core/cases/\(fileName).json")
        let data = try Data(contentsOf: file)
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawCases = root["cases"] as? [[String: Any]] else {
            XCTFail("用例文件格式不对：\(file.path)")
            return []
        }
        XCTAssertFalse(rawCases.isEmpty, "\(fileName) 不能没有用例")
        return rawCases.map { entry in
            SpecCase(
                name: entry["name"] as? String ?? "<unnamed>",
                input: entry["input"] as? [String: Any] ?? [:],
                expect: entry["expect"] as? [String: Any] ?? [:]
            )
        }
    }

    /// 在带用例名的上下文里跑一段断言；失败信息直接指出是哪一条不一致。
    private func check(_ fileName: String, _ body: (SpecCase) throws -> Void) throws {
        for item in try loadCases(fileName) {
            do {
                try body(item)
            } catch {
                XCTFail("[\(fileName) / \(item.name)] \(error)")
            }
        }
    }

    // MARK: - 1. 睡眠

    func testSleepDecisionCases() throws {
        try check("sleep-decision") { item in
            let input = item.input
            let evaluation = SleepGate.evaluate(
                sleepEnabled: try input.bool("sleepEnabled"),
                sleepMinutes: input.optionalInt("sleepMinutes"),
                wakeMinutes: input.optionalInt("wakeMinutes"),
                minuteOfDay: try input.int("nowMinutes"),
                dndOnSleep: try input.bool("dndOnSleep"),
                recentUserMessages: try input.int("userMessagesInWindow"),
                wakeThreshold: try input.int("wakeThreshold")
            )
            try expect(evaluation.asleep, item.expect.bool("asleep"), "asleep")
            try expect(evaluation.dnd, item.expect.bool("dnd"), "dnd")
            try expect(evaluation.woken, item.expect.bool("woken"), "woken")
            try expect(evaluation.shouldReply, item.expect.bool("shouldReply"), "shouldReply")
            try expect(evaluation.marksRead, item.expect.bool("marksRead"), "marksRead")

            if let moodAfter = item.expect["moodAfter"] as? [String: Any] {
                let mood = try input.object("mood")
                let recovered = SleepGate.moodAfterWaking(
                    label: try mood.string("label"),
                    intensity: try mood.int("intensity")
                )
                try expect(recovered.label, try moodAfter.string("label"), "moodAfter.label")
                try expect(recovered.intensity, try moodAfter.int("intensity"), "moodAfter.intensity")
            }
        }
    }

    // MARK: - 2. defer_reply

    func testDeferPlanCases() throws {
        try check("defer-plan") { item in
            let plan = DeferReply.plan(
                minutes: item.input.optionalInt("minutes"),
                reason: item.input.optionalString("reason"),
                now: try item.input.int64("now")
            )
            try expect(plan.minutes, try item.expect.int("minutes"), "minutes")
            try expect(plan.reason, try item.expect.string("reason"), "reason")
            try expect(plan.earliestAt, try item.expect.int64("earliestAt"), "earliestAt")
            try expect(plan.latestAt, try item.expect.int64("latestAt"), "latestAt")
        }
    }

    // MARK: - 3. 主动程度

    /// 固定「本地时间中午」：避免 MoodBehavior 的深夜折扣让结果随跑测试的时刻变化。
    private static let localNoon: Int64 = {
        var components = DateComponents()
        components.year = 2026
        components.month = 1
        components.day = 15
        components.hour = 12
        let date = Calendar.current.date(from: components) ?? Date(timeIntervalSince1970: 0)
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }()

    func testProactiveCapCases() throws {
        try check("proactive-cap") { item in
            let level = try item.input.int("level")
            try expect(ProactiveLevel.normalize(level), try item.expect.int("normalizedLevel"), "normalizedLevel")
            let baseCap = ProactiveLevel.dailyCap(level)
            try expect(baseCap, try item.expect.int("baseDailyCap"), "baseDailyCap")
            try expect(
                ProactiveLevel.heartbeatWeight(level),
                try item.expect.double("heartbeatWeight"),
                "heartbeatWeight"
            )
            try expect(ProactiveLevel.description(level), try item.expect.string("description"), "description")

            let dailyCap: Int
            if let mood = item.input["mood"] as? [String: Any] {
                let tuning = MoodBehavior.from(
                    MoodState(label: try mood.string("label"), intensity: try mood.int("intensity")),
                    now: Self.localNoon
                )
                dailyCap = MoodBehavior.effectiveDailyCap(baseCap: baseCap, tuning: tuning)
            } else {
                dailyCap = baseCap
            }
            try expect(dailyCap, try item.expect.int("dailyCap"), "dailyCap（含心情修正）")
        }
    }

    // MARK: - 4. 心跳

    func testHeartbeatCases() throws {
        try check("heartbeat") { item in
            let input = item.input
            let mood: MoodState = if let raw = input["mood"] as? [String: Any] {
                MoodState(label: try raw.string("label"), intensity: try raw.int("intensity"))
            } else {
                MoodState()
            }
            let probability = Heartbeat.probability(
                minutesSinceReset: try input.double("minutesSinceReset"),
                mood: mood,
                now: 0,
                hourOfDay: try input.int("hourOfDay")
            )
            try expect(
                probability,
                try item.expect.double("probability"),
                "probability",
                accuracy: Self.tolerance
            )

            if let perBeat = input["perBeat"] as? [String: Any] {
                let actual = Heartbeat.perBeatProbability(
                    perMinute: try perBeat.double("perMinute"),
                    intervalMinutes: try perBeat.double("intervalMinutes")
                )
                try expect(
                    actual,
                    try item.expect.double("perBeatProbability"),
                    "perBeatProbability",
                    accuracy: Self.tolerance
                )
            }
            if let fire = input["fire"] as? [String: Any] {
                let actual = Heartbeat.shouldFire(
                    probability: try fire.double("probability"),
                    roll: try fire.double("roll")
                )
                try expect(actual, try item.expect.bool("shouldFire"), "shouldFire")
            }
            if let between = input["minutesBetween"] as? [String: Any] {
                let actual = Heartbeat.minutesBetween(
                    lastResetAt: try between.int64("lastResetAt"),
                    now: try between.int64("now")
                )
                try expect(
                    actual,
                    try item.expect.double("minutesBetween"),
                    "minutesBetween",
                    accuracy: Self.tolerance
                )
            }
        }
    }

    // MARK: - 5. 记忆

    func testMemoryDecayCases() throws {
        try check("memory-decay") { item in
            let input = item.input
            let tier = try XCTUnwrap(MemoryTier(rawValue: try input.string("tier")))
            let drawer = try XCTUnwrap(MemoryDrawer(rawValue: try input.string("drawer")))
            let memory = MemoryLifecycleState(
                id: "m1",
                tier: tier,
                pinned: try input.bool("pinned"),
                importance: try input.int("importance"),
                useCount: try input.int("useCount"),
                lastUsedAt: try input.int64("lastUsedAt"),
                createdAt: try input.int64("createdAt"),
                strength: try input.double("strength"),
                lastConsolidatedAt: try input.int64("lastConsolidatedAt"),
                drawer: drawer
            )
            let now = try input.int64("now")
            let factor: Double? = input.optionalInt("retention").map { MemoryLifecycle.retentionFactor($0) }

            if let expected = item.expect["retentionFactor"] as? NSNumber {
                try expect(factor ?? .nan, expected.doubleValue, "retentionFactor", accuracy: Self.tolerance)
            }
            if let expected = item.expect["effectiveStrength"] as? NSNumber {
                try expect(
                    MemoryLifecycle.effectiveStrength(memory, now: now),
                    expected.doubleValue,
                    "effectiveStrength",
                    accuracy: Self.tolerance
                )
            }
            if let expected = item.expect["decayStep"] as? NSNumber {
                try expect(
                    MemoryLifecycle.decayStep(memory, now: now, retentionFactor: factor ?? 1.0),
                    expected.doubleValue,
                    "decayStep",
                    accuracy: Self.tolerance
                )
            }
            if let expected = item.expect["action"] as? [String: Any] {
                let action = MemoryLifecycle.nextStatus(
                    memory,
                    strength: try input.double("statusStrength"),
                    now: now
                )
                try assertAction(action, expected, id: memory.id)
            }
        }
    }

    private func assertAction(_ action: LifecycleAction, _ expected: [String: Any], id: String) throws {
        let type = try expected.string("type")
        switch type {
        case "keep":
            try expect(action, .keep(id: id), "action")
        case "promote":
            guard case .promote(let actionId, let strength) = action else {
                throw SpecError.message("expect promote，实际 \(action)")
            }
            try expect(actionId, id, "promote.id")
            try expect(strength, try expected.double("strength"), "promote.strength", accuracy: Self.tolerance)
        case "demote":
            guard case .demote(let actionId, let to, let strength) = action else {
                throw SpecError.message("expect demote，实际 \(action)")
            }
            try expect(actionId, id, "demote.id")
            try expect(to.rawValue, try expected.string("to"), "demote.to")
            try expect(strength, try expected.double("strength"), "demote.strength", accuracy: Self.tolerance)
        case "forget":
            guard case .forget(let actionId, let reason) = action else {
                throw SpecError.message("expect forget，实际 \(action)")
            }
            try expect(actionId, id, "forget.id")
            try expect(reason, try expected.string("reason"), "forget.reason")
        default:
            throw SpecError.message("未知 action 类型：\(type)")
        }
    }

    // MARK: - 6. 工具参数

    func testToolArgsCases() throws {
        try check("tool-args") { item in
            let tool = try item.input.string("tool")
            let args = try item.input.object("args")
            let actual = try clampArgs(tool: tool, args: args)
            let expected = try item.expect.object("args")
            try expectDictionary(actual, expected, "夹取后的参数（tool=\(tool)）")
        }
    }

    /// 与 `agentd` 的 `/v1/core/tool-args`、Android 测试里的 `clampArgs` 是同一份规则：
    /// 只夹已知数值键，缺省值补在结果里，其余键原样保留。
    private func clampArgs(tool: String, args: [String: Any]) throws -> [String: Any] {
        var out: [String: Any] = args
        switch tool {
        case "remember":
            out["importance"] = ToolArgClamp.clampImportance(
                (args["importance"] as? NSNumber)?.intValue ?? ToolArgClamp.importanceDefault
            )
        case "web_search":
            out["count"] = ToolArgClamp.clampCount(
                (args["count"] as? NSNumber)?.intValue ?? ToolArgClamp.countDefault
            )
        case "defer_reply":
            out["minutes"] = ToolArgClamp.clampDeferMinutes(
                (args["minutes"] as? NSNumber)?.intValue ?? ToolArgClamp.minutesDefault
            )
        default:
            throw SpecError.message("用例里出现未知 tool：\(tool)")
        }
        return out
    }

    private func expectDictionary(_ actual: [String: Any], _ expected: [String: Any], _ label: String) throws {
        try expect(Set(actual.keys), Set(expected.keys), "\(label) 键集合")
        for key in expected.keys {
            let want = expected[key]
            let got = actual[key]
            if let wantNumber = want as? NSNumber, let gotNumber = got as? NSNumber {
                try expect(gotNumber.intValue, wantNumber.intValue, "\(label).\(key)")
            } else if let wantString = want as? String, let gotString = got as? String {
                try expect(gotString, wantString, "\(label).\(key)")
            } else {
                throw SpecError.message("\(label).\(key) 类型无法比较：want=\(String(describing: want)) got=\(String(describing: got))")
            }
        }
    }

    // MARK: - 断言辅助

    private enum SpecError: Error, CustomStringConvertible {
        case message(String)

        var description: String {
            switch self {
            case .message(let text): return text
            }
        }
    }

    private func expect<T: Equatable>(_ actual: T, _ expected: T, _ label: String) throws {
        if actual != expected {
            throw SpecError.message("\(label)：期望 \(expected)，实际 \(actual)")
        }
    }

    private func expect(_ actual: Double, _ expected: Double, _ label: String, accuracy: Double) throws {
        if !(abs(actual - expected) <= accuracy) {
            throw SpecError.message("\(label)：期望 \(expected)，实际 \(actual)（容差 \(accuracy)）")
        }
    }
}

// MARK: - JSON 取值辅助

private extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) throws -> String {
        guard let value = self[key] as? String else { throw missing(key, "字符串") }
        return value
    }

    func optionalString(_ key: String) -> String? { self[key] as? String }

    func int(_ key: String) throws -> Int {
        guard let number = self[key] as? NSNumber else { throw missing(key, "整数") }
        return number.intValue
    }

    func optionalInt(_ key: String) -> Int? {
        (self[key] as? NSNumber)?.intValue
    }

    func int64(_ key: String) throws -> Int64 {
        guard let number = self[key] as? NSNumber else { throw missing(key, "长整数") }
        return number.int64Value
    }

    func double(_ key: String) throws -> Double {
        guard let number = self[key] as? NSNumber else { throw missing(key, "数字") }
        return number.doubleValue
    }

    func bool(_ key: String) throws -> Bool {
        guard let number = self[key] as? NSNumber else { throw missing(key, "布尔") }
        return number.boolValue
    }

    func object(_ key: String) throws -> [String: Any] {
        guard let value = self[key] as? [String: Any] else { throw missing(key, "对象") }
        return value
    }

    private func missing(_ key: String, _ kind: String) -> NSError {
        NSError(domain: "AgentCoreSpec", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "缺少\(kind)字段 \(key)"])
    }
}
