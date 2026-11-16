import Foundation

// 后台调度这件事里**能测的那一半**：什么时候该补算、下一次什么时候来。
//
// ## 为什么需要「补算」
//
// Android 用前台服务每 30 秒拍一次，系统允许它一直跑；iOS 不允许常驻，
// `BGAppRefreshTask` 由系统择机调（通常几十分钟到数小时一次），`BGProcessingTask` 更少。
// 所以 iOS 上「他每隔半小时想一下」这件事必然会被系统掐断。诚实的做法不是假装实时，
// 而是：**每次有机会就补一次**，并且在 App 重新打开时按「错过的时间」补算一次冲动。
//
// 这个文件只回答两个问题（纯函数、不碰 UI、不碰 BGTaskScheduler）：
// 1. 现在该不该补一次心跳 / 该不该补一次每日整理？
// 2. 下一次（如果系统愿意给）应该排到几点？
//
// 真正注册 `BGTaskScheduler` / 弹通知的活在 `App/` 里；那边拿不到调度时**什么都不做**，
// 也就是「系统不给机会 → 完全靠前台」，不影响任何前台行为。

/// 一次「回到前台 / 系统给了时间片」时该补什么。
public struct BackgroundCatchUpPlan: Equatable, Sendable {
    /// 该补一次「想一下」（心跳 / 自主循环）。
    public var shouldTick: Bool
    /// 该补一次每日整理（4:00 那次错过了）。
    public var shouldConsolidate: Bool
    /// 距离上一次心跳大约错过了几拍（只用于日志，不真的补 N 次）。
    public var missedBeats: Int
    /// 中文说明，写进 `debug.log`。
    public var note: String

    public init(shouldTick: Bool = false, shouldConsolidate: Bool = false, missedBeats: Int = 0, note: String = "") {
        self.shouldTick = shouldTick
        self.shouldConsolidate = shouldConsolidate
        self.missedBeats = missedBeats
        self.note = note
    }
}

public enum BackgroundCatchUp {

    /// 后台把「一拍」按多长算：Android 是 30 秒，但 iOS 后台根本到不了；
    /// 这里用 30 分钟作为「该补一次」的最小间隔，避免频繁打开 App 就频繁调模型。
    public static let heartbeatIntervalMillis: Int64 = 30 * 60_000

    /// 前台心跳（30 秒一拍）如果超过这么久没拍，说明刚被系统挂起过，回来时补一次。
    public static let foregroundStaleMillis: Int64 = 10 * 60_000

    /// 每日整理的固定时刻（本地时间 4:00，与 Android 的 WorkManager 定时一致）。
    public static let dailyHour = 4
    public static let dailyMinute = 0

    /// 两次每日整理之间至少隔这么久：系统连给两次机会时不至于整理两遍。
    public static let dailyMinGapMillis: Int64 = 20 * 60 * 60_000

    /// `BGAppRefreshTask` 最早多久之后可以被调度（系统仍可能更晚，甚至不给）。
    public static let refreshEarliestSeconds: TimeInterval = 15 * 60
    /// `BGProcessingTask` 最早多久之后可以被调度。每日整理不急着跑。
    public static let processingEarliestSeconds: TimeInterval = 2 * 60 * 60

    // MARK: - 该不该补

    /// - Parameters:
    ///   - lastHeartbeatAt: 上一次「想一下」的时间（`AgentLoop.lastTickAt()` 或心跳状态）。
    ///   - lastConsolidatedAt: 上一次每日整理的时间（`memory_consolidations.at` 的最新值）。
    ///   - isForeground: true = App 刚回到前台；false = 系统给了一个后台时间片。
    public static func plan(
        lastHeartbeatAt: Int64?,
        lastConsolidatedAt: Int64?,
        now: Int64,
        isForeground: Bool,
        calendar: Calendar = ServiceTime.calendar()
    ) -> BackgroundCatchUpPlan {
        let stamp = max(now, 0)
        let staleAfter = isForeground ? foregroundStaleMillis : heartbeatIntervalMillis
        let last = (lastHeartbeatAt ?? 0) > 0 ? lastHeartbeatAt! : nil
        let gap = last.map { max(stamp - $0, 0) } ?? Int64.max
        let shouldTick = last == nil || gap >= staleAfter
        // 时钟回拨、跨时区都不会让「错过几拍」变成负数或天文数字。
        let missed = last == nil ? 0 : min(Int(max(stamp - last!, 0) / heartbeatIntervalMillis), 99)

        let todayRun = dailySlot(now: stamp, calendar: calendar)
        let lastDaily = (lastConsolidatedAt ?? 0) > 0 ? lastConsolidatedAt! : nil
        let consolidatedRecently = lastDaily.map { stamp - $0 < dailyMinGapMillis } ?? false
        let missedToday = lastDaily.map { $0 < todayRun } ?? true
        let shouldConsolidate = stamp >= todayRun && missedToday && !consolidatedRecently

        var notes: [String] = []
        if shouldTick {
            if let last {
                notes.append("距上次想事情 \(max(stamp - last, 0) / 60_000) 分钟，补一次")
            } else {
                notes.append("还没有想过事情，补一次")
            }
        } else {
            notes.append("刚想过，不用补")
        }
        if shouldConsolidate {
            notes.append("错过了 \(ProactiveProtocol.formatTimeOfDay(dailyHour * 60 + dailyMinute)) 的整理，补一次")
        }
        return BackgroundCatchUpPlan(
            shouldTick: shouldTick,
            shouldConsolidate: shouldConsolidate,
            missedBeats: missed,
            note: notes.joined(separator: "；")
        )
    }

    // MARK: - 下一次

    /// 下一次「想一下」的最早时间：前台按 30 分钟，系统后台刷新按刷新间隔。
    public static func nextHeartbeatAt(now: Int64, isForeground: Bool) -> Int64 {
        now + (isForeground ? heartbeatIntervalMillis : heartbeatIntervalMillis)
    }

    /// 下一次每日整理的时间：今天的 4:00 还没到就今天，否则明天。
    public static func nextDailyAt(now: Int64, calendar: Calendar = ServiceTime.calendar()) -> Int64 {
        let slot = dailySlot(now: now, calendar: calendar)
        return slot > now ? slot : slot + 24 * 60 * 60_000
    }

    /// 今天（本地时间）的 4:00 对应的毫秒时间戳。
    public static func dailySlot(now: Int64, calendar: Calendar = ServiceTime.calendar()) -> Int64 {
        let startOfToday = ServiceTime.startOfDay(now, calendar: calendar)
        return startOfToday + Int64(dailyHour * 60 + dailyMinute) * 60_000
    }
}

// MARK: - 通知文案

/// 本地通知里放什么。
///
/// 硬规矩（与 Android 的「锁屏不露正文」一致）：**通知正文里永远不放他说的话**，
/// 锁屏上只能看到「他给你发了一条消息」。点开之后 App 才进到对应聊天。
/// 因为这里只是纯文案，测试可以直接钉住「body 里不含消息内容」。
public struct LocalNotificationContent: Equatable, Sendable {

    public static let sessionKey = "sessionId"
    public static let personaKey = "personaId"

    public var title: String
    public var body: String
    public var threadId: String
    public var sessionId: String
    public var personaId: String

    public init(title: String, body: String, threadId: String, sessionId: String, personaId: String) {
        self.title = title
        self.body = body
        self.threadId = threadId
        self.sessionId = sessionId
        self.personaId = personaId
    }

    /// 「林深 发来一条消息」+「点开看看」。
    ///
    /// - Important: `messageText` 只用于判断要不要加「（一张图片）」，**绝不进 body**。
    public static func make(
        personaName: String,
        personaId: String,
        sessionId: String,
        hasAttachments: Bool = false
    ) -> LocalNotificationContent {
        let name = personaName.trimmingCharacters(in: .whitespacesAndNewlines)
        let display = name.isEmpty ? String(localized: "他") : name
        return LocalNotificationContent(
            title: display,
            body: hasAttachments
                ? String(localized: "给你发来一张图片")
                : String(localized: "给你发来一条消息"),
            threadId: "yap.\(personaId)",
            sessionId: sessionId,
            personaId: personaId
        )
    }

    public var userInfo: [String: String] {
        [Self.sessionKey: sessionId, Self.personaKey: personaId]
    }
}
