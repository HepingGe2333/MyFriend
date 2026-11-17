import Foundation

// ---------------------------------------------------------------------------
// 「这一轮可以不回」：`stay_silent`（`spec/mood/README.md` §4）。
//
// 与 `defer_reply` 的分工（规范明文写死，提示词里也这么讲）：
// - `defer_reply` = 「过一会儿再回」：会落一个到点的念头（`agent_intents`），心跳到点再问他；
// - `stay_silent` = 「这一轮就不回」：**不产生消息、不留念头**，用户端只看到「已读」。
//
// 为什么需要它：真机上很多轮对话的正确答案就是「嗯」或者干脆不接话。逼模型每轮都回，
// 它就只能敷衍、反问、总结——那正是「AI 味」。给他一个体面的沉默，他反而更像人。
//
// 连续上限（≤2 轮）刻意做得保守：沉默是态度，不是逃避。连着三轮不回，用户会以为坏了。
// 计数存在本机（`UserDefaults`，与人格进化/情绪判定的游标同一性质）：不进备份、不参与同步，
// 丢了最多是「重启之后又能沉默两次」。
// ---------------------------------------------------------------------------

public enum StaySilent {

    /// 工具名（与 `ToolCatalog.staySilent` 一致）。
    public static let toolName = "stay_silent"

    /// 最多连续几轮可以只标已读不回（规范 §4）。
    public static let maxConsecutiveRounds = 2

    /// reason 只给自己看，限长（与 `defer_reply` 同一口径）。
    public static let maxReasonChars = 80

    /// 「连续」的时间窗口：超过这么久没有新的沉默，就从 0 重新数。
    ///
    /// 为什么需要窗口：`stay_silent` 之后用户可能半天不说话；下次再聊时把上午那两轮
    /// 也算成「连续」，他就一整天都不能沉默了 —— 那是惩罚，不是性格。
    public static let streakWindowMillis: Int64 = 2 * 60 * 60 * 1000

    /// 这一轮的调用里有没有 `stay_silent`（只认工具名，不看模型有没有顺带写正文）。
    public static func wasRequested(_ toolNames: [String]) -> Bool {
        toolNames.contains {
            $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == toolName
        }
    }

    /// 清洗 reason：trim + 截到 80 字。
    public static func cleanReason(_ raw: String?) -> String {
        String((raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxReasonChars))
    }
}

/// 「他最近连着沉默了几轮」的存储。
///
/// 只存两个数：连续轮数、最后一次沉默的时间。两个都要，否则「连续」无从判断。
public protocol StaySilentRecording: AnyObject {
    func consecutiveRounds(personaId: String, now: Int64) -> Int
    func recordSilent(personaId: String, at: Int64)
    func reset(personaId: String)
}

public extension StaySilentRecording {
    /// 这一轮还能不能再沉默（规范 §4：连续 ≤2 轮）。
    func canStaySilent(personaId: String, now: Int64) -> Bool {
        consecutiveRounds(personaId: personaId, now: now) < StaySilent.maxConsecutiveRounds
    }
}

/// 一条记录：`count` 轮连续沉默，最后一次在 `lastAt`。
public struct StaySilentStreak: Equatable, Codable, Sendable {
    public var count: Int
    public var lastAt: Int64

    public init(count: Int = 0, lastAt: Int64 = 0) {
        self.count = count
        self.lastAt = lastAt
    }
}

/// 生产实现：一个 JSON 字典 `{personaId: streak}` 存在 UserDefaults 里。
public final class UserDefaultsStaySilentLedger: StaySilentRecording {

    static let key = "stay_silent_streak"

    private let defaults: UserDefaults
    private let lock = NSLock()

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func consecutiveRounds(personaId: String, now: Int64) -> Int {
        lock.lock()
        defer { lock.unlock() }
        guard let streak = all()[personaId] else { return 0 }
        if streak.lastAt <= 0 || now - streak.lastAt > StaySilent.streakWindowMillis { return 0 }
        return Swift.max(streak.count, 0)
    }

    public func recordSilent(personaId: String, at: Int64) {
        lock.lock()
        defer { lock.unlock() }
        var map = all()
        let previous = map[personaId]
        let continued = previous.map { $0.lastAt > 0 && at - $0.lastAt <= StaySilent.streakWindowMillis } ?? false
        let count = continued ? Swift.max(previous?.count ?? 0, 0) + 1 : 1
        map[personaId] = StaySilentStreak(count: count, lastAt: at)
        save(map)
    }

    public func reset(personaId: String) {
        lock.lock()
        defer { lock.unlock() }
        var map = all()
        guard map.removeValue(forKey: personaId) != nil else { return }
        save(map)
    }

    private func save(_ map: [String: StaySilentStreak]) {
        if let data = try? JSONEncoder().encode(map) {
            defaults.set(data, forKey: Self.key)
        }
    }

    private func all() -> [String: StaySilentStreak] {
        guard let data = defaults.data(forKey: Self.key),
              let map = try? JSONDecoder().decode([String: StaySilentStreak].self, from: data) else {
            return [:]
        }
        return map
    }
}

/// 测试用：进程内存里的记录。
public final class InMemoryStaySilentLedger: StaySilentRecording {
    private var map: [String: StaySilentStreak] = [:]
    private let lock = NSLock()

    public init() {}

    public func consecutiveRounds(personaId: String, now: Int64) -> Int {
        lock.lock()
        defer { lock.unlock() }
        guard let streak = map[personaId] else { return 0 }
        if streak.lastAt <= 0 || now - streak.lastAt > StaySilent.streakWindowMillis { return 0 }
        return Swift.max(streak.count, 0)
    }

    public func recordSilent(personaId: String, at: Int64) {
        lock.lock()
        defer { lock.unlock() }
        let previous = map[personaId]
        let continued = previous.map { $0.lastAt > 0 && at - $0.lastAt <= StaySilent.streakWindowMillis } ?? false
        let count = continued ? Swift.max(previous?.count ?? 0, 0) + 1 : 1
        map[personaId] = StaySilentStreak(count: count, lastAt: at)
    }

    public func reset(personaId: String) {
        lock.lock()
        map.removeValue(forKey: personaId)
        lock.unlock()
    }
}
