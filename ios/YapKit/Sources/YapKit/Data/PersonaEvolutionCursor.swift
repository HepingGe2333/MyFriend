import Foundation

// ---------------------------------------------------------------------------
// 人格进化的「上次评估到哪儿了」（`docs/design/persona-evolution.md` §2）。
//
// 触发条件是「每 20 条用户消息或每 24 小时」，所以需要两个游标：
// 上次评估的时刻、当时用户消息的总数。两个都存，才能同时满足两种节奏
//（消息数到了立刻评估；消息不多但过了 24 小时也评估一次）。
//
// 为什么放 UserDefaults 而不是数据库：它是**本机的调度状态**，不是用户数据 ——
// 不进备份、不参与同步、丢了最多是「这次多评估一次」。与 `heartbeat_last_reset_at`
// 同一性质。也刻意不进 `AppSettings` / 备份：那不是设置项。
// ---------------------------------------------------------------------------

public struct PersonaEvolutionCursor: Equatable, Codable, Sendable {
    /// 上次评估的时刻（毫秒）；0 = 还没有基线。
    public var lastEvaluatedAt: Int64
    /// 上次评估时这个人的用户消息总数。
    public var userMessageCount: Int

    public init(lastEvaluatedAt: Int64 = 0, userMessageCount: Int = 0) {
        self.lastEvaluatedAt = lastEvaluatedAt
        self.userMessageCount = userMessageCount
    }
}

public protocol PersonaEvolutionCursorStoring: AnyObject {
    func cursor(personaId: String) -> PersonaEvolutionCursor?
    func save(_ cursor: PersonaEvolutionCursor, personaId: String)
}

/// 生产实现：一个 JSON 字典 `{personaId: cursor}` 存在 UserDefaults 里。
public final class UserDefaultsEvolutionCursorStore: PersonaEvolutionCursorStoring {

    /// 键名与 Android 的 DataStore 键同名（排查时一眼能认出）。
    static let key = "persona_evolution_cursor"

    private let defaults: UserDefaults
    private let lock = NSLock()

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func cursor(personaId: String) -> PersonaEvolutionCursor? {
        all()[personaId]
    }

    public func save(_ cursor: PersonaEvolutionCursor, personaId: String) {
        lock.lock()
        var map = all()
        map[personaId] = cursor
        if let data = try? JSONEncoder().encode(map) {
            defaults.set(data, forKey: Self.key)
        }
        lock.unlock()
    }

    private func all() -> [String: PersonaEvolutionCursor] {
        guard let data = defaults.data(forKey: Self.key),
              let map = try? JSONDecoder().decode([String: PersonaEvolutionCursor].self, from: data) else {
            return [:]
        }
        return map
    }
}

/// 测试用：进程内存里的游标。
public final class InMemoryEvolutionCursorStore: PersonaEvolutionCursorStoring {
    private var map: [String: PersonaEvolutionCursor] = [:]
    private let lock = NSLock()

    public init() {}

    public func cursor(personaId: String) -> PersonaEvolutionCursor? {
        lock.lock()
        defer { lock.unlock() }
        return map[personaId]
    }

    public func save(_ cursor: PersonaEvolutionCursor, personaId: String) {
        lock.lock()
        map[personaId] = cursor
        lock.unlock()
    }
}
