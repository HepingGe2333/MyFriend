import Foundation

// ---------------------------------------------------------------------------
// 聊天页的轮询节奏（纯逻辑，0.11.0 审计 P2-3）。
//
// 0.10.2 的实现是两条 Task：每 4 秒查一次「库里多了消息没」、每 30 秒重算心情与
// 「他此刻」；两条都只在 `onDisappear` 时停。于是「长时间停留在一个聊天页」会
// 持续唤醒 CPU 与 SQLite —— 即使这一轮什么都没发生（`isGenerating` 时还照样每 4 秒
// 醒来一次再 `return`）。
//
// 现在的口径：
// 1. **页面不可见 / App 在后台**：两路都停（`nil`）。回到前台由 `ChatScreen` 的
//    `scenePhase == .active` 与 `enter()` 重读一遍 —— 那才是「事件」；
// 2. **正在流式输出**：消息轮询也停。流本身就是事件源（`finishGeneration` 会统一重读），
//    这时候再去查库只是白烧 CPU；
// 3. **停在页面上发呆**：消息轮询降到 20 秒一次。为什么不是 0 —— iOS 的 Kit 是拉取式的
//    （没有 Room 的 Flow），心跳/主动唤醒可能在你盯着这个页面时写进一条消息；
//    20 秒是「他还活着」的保底，不是高频轮询。
//
// 这一层不碰任何 UI/数据库，`ChatViewModel` 只按它返回的间隔睡。
// ---------------------------------------------------------------------------

public enum ChatPollingPolicy {

    /// 一次轮询计划：`nil` 表示这一路停掉。
    public struct Plan: Equatable, Sendable {
        /// 「库里是不是多了消息」的轮询间隔。
        public var messageIntervalNanos: UInt64?
        /// 心情 / 「他此刻」的重算间隔。
        public var contextIntervalNanos: UInt64?

        public init(messageIntervalNanos: UInt64?, contextIntervalNanos: UInt64?) {
            self.messageIntervalNanos = messageIntervalNanos
            self.contextIntervalNanos = contextIntervalNanos
        }

        /// 两路都停。
        public static let stopped = Plan(messageIntervalNanos: nil, contextIntervalNanos: nil)
    }

    /// 停在页面上发呆时，多久核一次新消息（保底，不是高频）。
    public static let idleMessageIntervalNanos: UInt64 = 20 * 1_000_000_000
    /// 心情与 presence 的重算间隔：与 Android 的 Flow 语义一致，30 秒一次很轻。
    public static let contextIntervalNanos: UInt64 = 30 * 1_000_000_000

    /// 给出这一拍该用的间隔。
    ///
    /// - Parameters:
    ///   - isVisible: 页面是否还在屏幕上（`onAppear` 与 `onDisappear` 之间）。
    ///   - sceneActive: `scenePhase == .active`。
    ///   - isGenerating: 这一轮是否正在流式输出（含「点了停止、等收尾」）。
    public static func plan(
        isVisible: Bool,
        sceneActive: Bool,
        isGenerating: Bool
    ) -> Plan {
        guard isVisible, sceneActive else { return .stopped }
        if isGenerating {
            // 流是事件源：不查库，但心情/他的状态仍然每 30 秒更新一次（界面上看得见的那两行）。
            return Plan(messageIntervalNanos: nil, contextIntervalNanos: contextIntervalNanos)
        }
        return Plan(
            messageIntervalNanos: idleMessageIntervalNanos,
            contextIntervalNanos: contextIntervalNanos
        )
    }
}
