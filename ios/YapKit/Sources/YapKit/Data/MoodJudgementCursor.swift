import Foundation

// ---------------------------------------------------------------------------
// 自动情绪判定的「上次判到哪儿了」（`spec/mood/README.md` §2）。
//
// 触发条件是「每 4 条用户消息或每 24 小时」，所以需要两个游标：上次判定的时刻、当时用户消息总数。
//
// 为什么放 UserDefaults 而不是数据库：它是**本机的调度状态**，不是用户数据 ——
// 不进备份、不参与同步、丢了最多是「这次多判一次」。与 `PersonaEvolutionCursor`、
// `heartbeat_last_reset_at` 同一性质。也不进 `AppSettings`：它不是设置项。
// ---------------------------------------------------------------------------

public struct MoodJudgementCursor: Equatable, Codable, Sendable {
    /// 上次判定的时刻（毫秒）；0 = 还没有基线。
    public var lastJudgedAt: Int64
    /// 上次判定时这个人的用户消息总数。
    public var userMessageCount: Int

    public init(lastJudgedAt: Int64 = 0, userMessageCount: Int = 0) {
        self.lastJudgedAt = lastJudgedAt
        self.userMessageCount = userMessageCount
    }
}

public protocol MoodJudgementCursorStoring: AnyObject {
    func cursor(personaId: String) -> MoodJudgementCursor?
    func save(_ cursor: MoodJudgementCursor, personaId: String)
}

/// 生产实现：一个 JSON 字典 `{personaId: cursor}` 存在 UserDefaults 里。
public final class UserDefaultsMoodJudgementCursorStore: MoodJudgementCursorStoring {

    /// 键名与另外几个本机游标同一风格（`persona_evolution_cursor`），排查时一眼能认出。
    static let key = "mood_judgement_cursor"

    private let defaults: UserDefaults
    private let lock = NSLock()

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func cursor(personaId: String) -> MoodJudgementCursor? {
        all()[personaId]
    }

    public func save(_ cursor: MoodJudgementCursor, personaId: String) {
        lock.lock()
        var map = all()
        map[personaId] = cursor
        if let data = try? JSONEncoder().encode(map) {
            defaults.set(data, forKey: Self.key)
        }
        lock.unlock()
    }

    private func all() -> [String: MoodJudgementCursor] {
        guard let data = defaults.data(forKey: Self.key),
              let map = try? JSONDecoder().decode([String: MoodJudgementCursor].self, from: data) else {
            return [:]
        }
        return map
    }
}

/// 测试用：进程内存里的游标。
public final class InMemoryMoodJudgementCursorStore: MoodJudgementCursorStoring {
    private var map: [String: MoodJudgementCursor] = [:]
    private let lock = NSLock()

    public init() {}

    public func cursor(personaId: String) -> MoodJudgementCursor? {
        lock.lock()
        defer { lock.unlock() }
        return map[personaId]
    }

    public func save(_ cursor: MoodJudgementCursor, personaId: String) {
        lock.lock()
        map[personaId] = cursor
        lock.unlock()
    }
}
