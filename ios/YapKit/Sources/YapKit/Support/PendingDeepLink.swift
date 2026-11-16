import Foundation

// ---------------------------------------------------------------------------
// 「等会儿要打开哪个会话」的持久化状态机（0.11.0 审计 P2-7）。
//
// 现场：App 没在运行时点通知 → 系统冷启动 → `UNUserNotificationCenterDelegate` 的回调
// 在首帧之前就到，而 SwiftUI 的 `@Published` 状态与 `.onReceive` 订阅在首帧之后才接上，
// 于是「点通知进会话」这一次被吞掉，只打开了首页。
//
// 两道保险：
// 1. delegate 的装配挪进 `YetAnotherPalApp.init()`（启动完成前），让冷启动那次回调**真的到**；
// 2. 到了之后先**落盘**（`UserDefaults`），首帧之后的根视图再 `.consume()` 取走并清除。
//    这样无论回调与视图谁先谁后，都不会丢。
//
// 这一层只做状态机（`stage` → `consume` → `clear`），存储用一个三行的协议注入，
// 所以可以在 Kit 里直接单测「写入 → 消费 → 清除」与「重复消费幂等」，
// 不需要起 App、也不需要真的 UserDefaults。
// ---------------------------------------------------------------------------

/// 持久化「待打开的会话 id」。
public final class PendingDeepLink {

    /// 真正的键值存储（App 层传 `UserDefaults`，测试传内存实现）。
    public protocol Storage {
        func string(forKey key: String) -> String?
        func set(_ value: String?, forKey key: String)
    }

    /// 默认键。带上 App 前缀，免得和别的库撞。
    public static let defaultKey = "yap.pendingDeepLinkSessionId"

    private let storage: Storage
    private let key: String

    public init(storage: Storage, key: String = PendingDeepLink.defaultKey) {
        self.storage = storage
        self.key = key
    }

    /// 记下「等会儿要打开这段会话」。
    ///
    /// 空 id 当成「没有」：通知里可能只有 personaId（旧版本/降级情况），
    /// 那时候打开一段空 id 的会话只会得到一个空白页，不如什么都不做。
    ///
    /// - Returns: 真的写进去了没有（测试断言用）。
    @discardableResult
    public func stage(_ sessionId: String?) -> Bool {
        guard let trimmed = sessionId?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return false }
        storage.set(trimmed, forKey: key)
        return true
    }

    /// 取走并清除。第二次调用返回 nil（**幂等**：视图重建/状态恢复都不会重复跳一次）。
    public func consume() -> String? {
        guard let value = peek() else { return nil }
        storage.set(nil, forKey: key)
        return value
    }

    /// 看一眼，不清除（启动自检/日志用）。
    public func peek() -> String? {
        guard let raw = storage.string(forKey: key)?
            .trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
        return raw
    }

    /// 显式清掉（用户在别处已经处理掉这条待办时用）。
    ///
    /// 0.11.1 审计 P2-2：温启动（App 正在运行/后台时点通知）也必须消费即清 —— 否则
    /// 「点一次通知」会变成「以后每次冷启动都跳那条旧会话」。
    public func clear() {
        storage.set(nil, forKey: key)
    }

    /// 取走待打开会话，但只有「会话仍然存在」才返回（不存在就丢弃 → 回首页）。
    ///
    /// 通知里的 `sessionId` 可能指向已经被删掉的会话（清空全部数据、删人格、替换导入）。
    /// 不校验就 `path.append`，用户会落到一个空白聊天页、发送只会得到「会话不存在」。
    /// 无论会话在不在，**取走即清**：这是「一次点击」而不是「常驻意图」。
    ///
    /// - Parameter sessionExists: 应用层用 `stores.sessions.byId(id) != nil` 实现。
    public func consumeIfSessionExists(_ sessionExists: (String) -> Bool) -> String? {
        guard let value = consume() else { return nil }
        return sessionExists(value) ? value : nil
    }
}
