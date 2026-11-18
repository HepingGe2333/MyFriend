import Foundation

// ---------------------------------------------------------------------------
// 「本地自主逻辑该不该跑」的唯一判定（0.13，与 Android `domain/LocalAutonomy.kt` 对齐）。
//
// 服务端模式下他在服务端那边独立运行（主动消息、情绪/进化、日记提醒），客户端连上只同步：
// 拉状态/事件、发用户消息、同步写路径。再跑一套本地自主逻辑就会出现双份主动消息、
// 双份情绪判定、双份日记提醒。
//
// 为什么单独抽成纯函数：
// - 心跳、后台时间片、回前台补算、聊天后台任务（压缩/抽记忆/进化/情绪）有多个入口，
//   每个各写一遍判断迟早会漏掉一个；
// - 这是「只同步」的核心策略，值得在 Kit 单测里逐条钉住，而不是只在真机上看有没有双份消息。
// ---------------------------------------------------------------------------

public enum LocalAutonomy {

    /// 本地主动逻辑的三态闸门（读不放宽：读不出来就保持现状）。
    public enum Gate: Equatable, Sendable {
        case run
        case stop
        /// 读不出来：保持现状，不做任何启停。
        case keep
    }

    /// 服务端模式下所有本地自主逻辑都停。
    public static func localAutonomyAllowed(serverModeActive: Bool) -> Bool {
        !serverModeActive
    }

    /// 心跳 / 后台时间片 / 补算该不该继续。
    public static func shouldRunLocalTick(serverModeActive: Bool) -> Bool {
        !serverModeActive
    }

    /// 常驻循环（前台拍子 + 后台时间片）该做什么。
    ///
    /// - Parameters:
    ///   - agentLoopEnabled: 用户拨的「常驻后台」开关；nil = 读不出来
    ///   - serverModeActive: 当前是否服务端模式
    public static func proactiveGate(agentLoopEnabled: Bool?, serverModeActive: Bool) -> Gate {
        if serverModeActive { return .stop }
        switch agentLoopEnabled {
        case .some(true): return .run
        case .some(false): return .stop
        case .none: return .keep
        }
    }

    /// 服务端模式下心跳早退时写进日志的一句话（给本地 debug 看）。
    public static let serverModeWhy = "服务端模式：主动由服务端负责"
}
