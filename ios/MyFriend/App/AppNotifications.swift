@preconcurrency import Foundation
@preconcurrency import UserNotifications

// 本地通知：他主动发来一条消息时，让手机「响一下」。
//
// 平台对齐（见 docs/IOS-PARITY.md 的「平台差异」表）：
// - Android 是渠道 + 分组 + 摘要；iOS 是单渠道 + `threadIdentifier` 分组（同一个人一组）；
// - **锁屏不露正文**：通知的 body 永远是「给你发来一条消息」，绝不包含他说的话。
//   这不是保守，是这条产品线的底线——他发的东西只属于用户自己；
// - 点开通知 → 进了对应那段聊天（`pendingSessionId` 由根视图消费）。
//
// 失败兜底：通知权限被拒 / 系统不给调度时，这里什么都不做。前台功能一点不受影响。
//
// 为什么 delegate 单独一个类：`UNUserNotificationCenterDelegate` 的回调不在主线程上，
// 让一个 `@MainActor` 的类直接遵守它会在 Swift 6 里变成并发错误（数据竞争）。
// 所以这边把「收系统回调」和「改界面状态」拆开：回调类只转发，状态仍然只活在主线程上。

@MainActor
final class AppNotifications: NSObject, ObservableObject {

    static let shared = AppNotifications()

    /// 用户点了通知，界面上该跳去哪个会话。根视图消费完把它清空。
    ///
    /// 只是「当下这一帧」的提示：真正的可信来源是下面那份持久化的待办
    ///（0.11.0 审计 P2-7：冷启动时 delegate 回调可能早于 SwiftUI 的订阅）。
    @Published var pendingSessionId: String?

    /// 「等会儿要打开的会话」的持久化状态机（UserDefaults 支撑，Kit 里定义）。
    ///
    /// 冷启动链路：系统回调 → `stage` 落盘 → 首帧之后 `RootView` 在 `.task` 里 `consume`。
    /// 落盘这一步是关键：`@Published` 在视图订阅之前发布的事件没人收得到。
    let pendingDeepLink = PendingDeepLink(storage: UserDefaultsPendingDeepLinkStorage())

    /// App 是不是正在前台。前台的会话页里再弹一条通知是打扰。
    private(set) var isAppActive = true

    /// 权限被拒过就不再反复打扰用户（每次启动弹一个系统框会被讨厌）。
    private(set) var authorizationDenied = false

    private var configured = false
    private let center: UNUserNotificationCenter
    private let delegate = NotificationDelegate()

    private init(center: UNUserNotificationCenter = .current()) {
        self.center = center
        super.init()
    }

    // MARK: - 装配

    /// App 启动时调一次：设成 delegate，并（第一次）申请权限。
    func configure() {
        guard !configured else { return }
        configured = true
        center.delegate = delegate
        #if DEBUG
        // 调试/截图：模拟器上 `simctl` 没有触控注入，系统权限框会把整屏挡住、点不掉。
        // 用 `SIMCTL_CHILD_MYFRIEND_DEBUG_SKIP_NOTIFY=1` 启动即可跳过申请（delegate 照设）。
        // 只影响 Debug 包，Release 行为不变。
        if ProcessInfo.processInfo.environment["MYFRIEND_DEBUG_SKIP_NOTIFY"] == "1" {
            DebugSeeding.log("[notify] 调试：跳过通知权限申请")
            return
        }
        #endif
        center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, error in
            Task { @MainActor in
                guard let self else { return }
                if let error {
                    self.authorizationDenied = true
                    DebugSeeding.log("[notify] 申请通知权限失败：\(error.localizedDescription)")
                    return
                }
                self.authorizationDenied = !granted
                DebugSeeding.log("[notify] 通知权限 granted=\(granted)")
            }
        }
    }

    func setAppActive(_ active: Bool) {
        isAppActive = active
        // 回到前台时把「人已经看到的那条会话」的通知清掉，别留在锁屏上。
        if active, let sessionId = pendingSessionId {
            clearDelivered(sessionId: sessionId)
        }
    }

    // MARK: - 发通知

    /// 他主动说了一句话：如果用户不在看这段对话，就弹一条不露正文的本地通知。
    ///
    /// - Parameter isSessionOpen: 这段会话此刻是否正开着（开着就不弹）。
    func notifyMessage(
        personaName: String,
        personaId: String,
        sessionId: String,
        hasAttachments: Bool = false,
        isSessionOpen: Bool = false
    ) {
        if isAppActive && isSessionOpen { return }
        if authorizationDenied { return }
        let notification = LocalNotificationContent.make(
            personaName: personaName,
            personaId: personaId,
            sessionId: sessionId,
            hasAttachments: hasAttachments
        )
        let content = UNMutableNotificationContent()
        content.title = notification.title
        content.body = notification.body
        content.threadIdentifier = notification.threadId
        content.sound = .default
        content.userInfo = notification.userInfo
        let request = UNNotificationRequest(
            identifier: "myfriend.message.\(sessionId).\(Int(Date().timeIntervalSince1970 * 1000))",
            content: content,
            trigger: nil
        )
        center.add(request) { error in
            guard let error else { return }
            DebugSeeding.log("[notify] 通知没发出去：\(error.localizedDescription)")
        }
    }

    /// 清掉某段会话已经投递的通知（用户正在看它时留着只会碍事）。
    ///
    /// 用 `UNUserNotificationCenter.current()` 而不是捕获属性：回调是 `@Sendable` 的，
    /// 捕获一个非 Sendable 的中心对象会变成并发警告；这里本来也不需要别的上下文。
    func clearDelivered(sessionId: String) {
        let key = LocalNotificationContent.sessionKey
        UNUserNotificationCenter.current().getDeliveredNotifications { delivered in
            let ids = delivered
                .filter { ($0.request.content.userInfo[key] as? String) == sessionId }
                .map(\.request.identifier)
            if !ids.isEmpty {
                UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
            }
        }
    }

    // MARK: - 点开通知（由 delegate 转发到主线程）

    func handleNotificationTap(sessionId: String?) {
        guard let sessionId, !sessionId.isEmpty else { return }
        // 先落盘再发通知：落盘的那一份负责「冷启动时也不丢」，`@Published` 只负责当前这帧。
        pendingDeepLink.stage(sessionId)
        pendingSessionId = sessionId
    }
}

/// `PendingDeepLink` 的 `UserDefaults` 实现（Kit 不碰全局 `UserDefaults.standard`，
/// 由 App 层决定存在哪；测试用内存实现）。
struct UserDefaultsPendingDeepLinkStorage: PendingDeepLink.Storage {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func string(forKey key: String) -> String? {
        defaults.string(forKey: key)
    }

    func set(_ value: String?, forKey key: String) {
        if let value {
            defaults.set(value, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }
}

/// 只负责收系统的通知回调，然后把结果丢回主线程。
///
/// 刻意不持有任何可变状态：这样它可以在任意线程被调用，也不会和 `@MainActor` 打架。
private final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {

    /// 前台收到通知：仍然显示（他主动来找你这件事本身就是内容），但不放声音。
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list])
    }

    /// 点开通知：把会话 id 交给主线程，由根视图 push 进去。
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        let sessionId = info[LocalNotificationContent.sessionKey] as? String
        let personaId = info[LocalNotificationContent.personaKey] as? String
        if sessionId?.isEmpty != false, let personaId, !personaId.isEmpty {
            DebugSeeding.log("[notify] 通知里只有 personaId（\(personaId)），没有会话 id")
        }
        Task { @MainActor in
            AppNotifications.shared.handleNotificationTap(sessionId: sessionId)
        }
        completionHandler()
    }
}
