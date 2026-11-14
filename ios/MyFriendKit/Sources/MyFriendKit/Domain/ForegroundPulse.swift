import Foundation

// ---------------------------------------------------------------------------
// 前台 30 秒一拍的纯逻辑（0.10.2 审计 P1-3）。
//
// 文档（`docs/IOS-PARITY.md:50,71`、`docs/ARCHITECTURE.md:17`）写的是「前台 30 秒 + 后台
// BGAppRefreshTask」，但 0.10.1 的 iOS 端其实只有「冷启动 / 回前台补算一次」：
// `HeartbeatController.checkIntervalMillis()` 除了测试没有任何调用方，
// 于是用户一直开着 App 聊天时一拍都不会发生 —— 主动唤醒规则不会转念头、心跳不掷骰子。
//
// 定时器本体在 App 层（`AppEnvironment.startForegroundPulse()`，需要 `scenePhase`），
// 但「多久一拍、什么时候该停、下一拍在哪一刻」这些判据必须是纯函数：
// 只有这样才能在 `swift test` 里钉住，而不是靠真机盯着日志猜。
// ---------------------------------------------------------------------------

/// 前台心跳的节拍判定。全部是纯函数。
public enum ForegroundPulse {

    /// 一拍之后，下一次该在什么时刻触发（毫秒时间戳）。
    public static func nextFireAt(now: Int64, intervalMillis: Int64) -> Int64 {
        now + max(intervalMillis, 0)
    }

    /// 距离下次触发还剩多久（毫秒；已经过了就是 0）。
    public static func remainingMillis(now: Int64, nextFireAt: Int64) -> Int64 {
        max(nextFireAt - now, 0)
    }

    /// 一拍隔多久：默认 30 秒，低电量放宽到 2 分钟。
    ///
    /// 常量只有一份（`HeartbeatController.beatIntervalMillis` / `lowPowerBeatIntervalMillis`）：
    /// 概率换算用的 `intervalMinutes()` 与这里必须同一个数，否则「每分钟概率 → 每拍概率」
    /// 会换算错（低电量时骰子会偏得离谱）。
    public static func intervalMillis(isLowPower: Bool) -> Int64 {
        isLowPower ? HeartbeatController.lowPowerBeatIntervalMillis : HeartbeatController.beatIntervalMillis
    }

    /// 定时器该不该跑。
    ///
    /// 三条都会真的让「空转」发生，所以缺一不可：
    /// - 不在前台：后台上系统会挂起进程，`Task.sleep` 到期也不会执行（还可能被系统记账）；
    /// - 没有 API 配置：每拍都会在 `runBackgroundTick` 的第一道 guard 直接返回，纯粹空转；
    /// - 没有可用人格：心跳只会回一句「还没有可以对话的人格」。
    public static func shouldRun(isActive: Bool, hasApiConfig: Bool, hasPersona: Bool = true) -> Bool {
        isActive && hasApiConfig && hasPersona
    }
}
