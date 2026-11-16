import Foundation

// ---------------------------------------------------------------------------
// 服务端事件 → 现有本地通知（复用 `AppNotifications`）。
//
// `ServerEventService`（Kit）只知道「该弹一条」这件事，不知道 `UNUserNotificationCenter`
// 长什么样；这里就是那条接到 App 的线：
// - **正文照旧不露**：`AppNotifications.notifyMessage` 的 body 永远是「给你发来一条消息」，
//   服务端下发的正文只用于「有没有内容值得弹」的判断（这条产品线的底线，见那边注释）；
// - **正在看这个会话就不弹**：由 `ServerEventService` 的 `isSessionOpen` 决定，
//   这里再加一次 `AppNotifications.isAppActive` 的兜底。
//
// 为什么标 `@unchecked Sendable`：真正干活的是主线程上的 `AppNotifications`（`@MainActor`），
// 这个类型自己没有任何可变状态，所以它可以被 Kit 的 actor 拿去调用。
// ---------------------------------------------------------------------------

final class ServerNotifications: ServerEventNotifying, @unchecked Sendable {

    /// 这个会话此刻开着吗（由 `ChatViewModel` 维护；没开就是 false）。
    private let isSessionOpen: @Sendable (String) -> Bool

    init(isSessionOpen: @escaping @Sendable (String) -> Bool) {
        self.isSessionOpen = isSessionOpen
    }

    func notifyIncoming(_ notification: ServerIncomingNotification) async {
        await MainActor.run {
            AppNotifications.shared.notifyMessage(
                personaName: notification.title,
                personaId: notification.personaId,
                sessionId: notification.sessionId,
                isSessionOpen: isSessionOpen(notification.sessionId)
            )
        }
    }
}
