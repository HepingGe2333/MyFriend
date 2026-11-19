import Foundation

// ---------------------------------------------------------------------------
// 服务端权威模式的「正在输入」规则（`spec/experience/README.md` §8，0.14.1）。
//
// 0.14.1 把延迟的归属改了：服务端在**入队时**就算好 `visibleAt`，客户端不再自己
// 加一层延迟；客户端只做两件事：
// 1. 前台且真的收到 `typing.started` 时显示「正在输入…」；
// 2. 收到 `message.created` 时直接上屏并收起输入指示。
//
// 离线同步回来的消息**不补**打字动画、不再延迟（它们早就过了 `visibleAt`，
// 再演一遍等于让用户等一条已经存在的历史消息）。
//
// 这些判断写成纯函数放在 Kit 里：界面层（App）不写可执行测试，规则必须有单测钉住。
// ---------------------------------------------------------------------------

/// 一条消息是怎么来到客户端的（决定要不要演「正在输入」）。
public enum ServerMessageArrival: String, Equatable, Sendable {
    /// 前台事件流里的 `message.created`：服务端已经按 `visibleAt` 到点，直接上屏。
    case liveEvent
    /// 离线期间落库、连上后由同步/翻页补回来的消息：直接上屏，不补动画。
    case offlineSync
}

public enum ServerTypingPolicy {

    /// 客户端要不要自己加打字延迟。
    ///
    /// 服务端权威模式下**永远是 false**：延迟由服务端的 `visibleAt` 决定，
    /// 客户端再加一次会让消息比服务端承诺的时间晚到（还可能出现「动画演完了字还没来」）。
    public static func appliesLocalTypingDelay(serverMode: Bool) -> Bool {
        !serverMode
    }

    /// 「正在输入…」是否可见：只有服务端模式 + 前台 + 会话开着 + 真的收到了 typing 事件。
    ///
    /// 本地模式的输入指示由 `isGenerating` 自己管，不走这里。
    public static func showsTypingIndicator(
        serverMode: Bool,
        isForeground: Bool,
        isSessionOpen: Bool,
        hasTypingEvent: Bool
    ) -> Bool {
        serverMode && isForeground && isSessionOpen && hasTypingEvent
    }

    /// 某种来源的消息要不要演打字动画。
    ///
    /// 只有「前台事件流入队时的 typing」才演；离线同步回来的消息一律直接上屏。
    public static func showsTypingAnimation(for arrival: ServerMessageArrival) -> Bool {
        arrival == .liveEvent
    }

    /// 客户端为一条服务端消息额外等待的秒数：规范 §8 要求**一律 0**。
    ///
    /// 离线同步与前台事件都不在客户端加延迟；这个函数是给调用点/测试的显式契约，
    /// 免得以后有人又在界面层塞一个 `sleep` 进来。
    public static func extraDelaySeconds(
        serverMode: Bool,
        arrival: ServerMessageArrival
    ) -> Double {
        0
    }
}
