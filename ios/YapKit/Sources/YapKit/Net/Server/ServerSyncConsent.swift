import Foundation

// ---------------------------------------------------------------------------
// 切换云端时的同步同意（`spec/sync-consent/README.md`，0.13 默认）。
//
// 规则回顾：
// 1. 用户把「处理位置」从本地切到远程服务端时**必须弹窗**，三个选项：
//    同意并同步 / 只同步之后的新内容 / 取消（默认焦点是取消）；
// 2. 同意状态按 `baseURL` 记录（键 `server_sync_consent:<url>`，值 `FULL` / `NEW_ONLY`）；
//    换一台服务端要重新问；
// 3. 取消不改变模式（也不落记录，下次再问）。
//
// 为什么把「问不问」写成纯状态机而不是散在 View 里：
// - View 只能验证「弹没弹」，验证不了「同一个地址第二次不再弹 / 换了地址又弹」；
// - 取消、同意、只同步新内容这三条路径在 UI 上都是按钮，真正的语义（该不该上传历史）
//   必须在 Kit 里定死，App 层只负责把选择转发过来。
//
// 记录放在 `server_sync_state`（与 bootstrap 完成标记同一张簿记表）：
// 它描述的是「这台设备对这台服务端点头到什么程度」，跟着设备走、不进备份、
// 「删除全部本地数据」时一起清掉（清完就该重新问一次）。
// ---------------------------------------------------------------------------

/// 用户对「把历史交给这台服务端」的选择。
public enum ServerSyncConsentDecision: String, Codable, Equatable, CaseIterable, Sendable {
    /// 同意并同步：元数据/文本 + 历史媒体逐项上传。
    case full = "FULL"
    /// 只同步之后的新内容：不推历史消息。
    case newOnly = "NEW_ONLY"

    /// 给日志/调试用的一句话（界面文案在 App 层走 `L(...)`）。
    public var label: String {
        switch self {
        case .full: return "同意并同步"
        case .newOnly: return "只同步之后的新内容"
        }
    }

    /// 这次同意允不允许把历史搬上去。
    public var uploadsHistory: Bool { self == .full }
}

/// 弹窗上的三个按钮（顺序就是界面上的顺序；`cancel` 是默认焦点）。
public enum ServerSyncConsentChoice: Equatable, Sendable {
    case agreeAndSync
    case newOnly
    case cancel

    /// 选中这个按钮之后要落库的决定；`nil` = 取消（不落库、不切换）。
    public var decision: ServerSyncConsentDecision? {
        switch self {
        case .agreeAndSync: return .full
        case .newOnly: return .newOnly
        case .cancel: return nil
        }
    }
}

/// 用户点完按钮之后该怎么走。
public enum ServerSyncConsentResolution: Equatable, Sendable {
    /// 按 `decision` 同步（触发方负责写记录、跑同步、成功后切模式）。
    case proceed(url: String, decision: ServerSyncConsentDecision)
    /// 取消：保持关闭，不写记录。
    case keepOff
}

/// 「现在要不要弹窗」的判定结果。
public enum ServerSyncConsentPlan: Equatable, Sendable {
    /// 这台服务端还没同意过：弹窗，地址是规范化后的 `url`。
    case prompt(url: String)
    /// 已经同意过：不弹窗，直接按记录的方式同步。
    case proceed(url: String, decision: ServerSyncConsentDecision)
    /// 地址不可用（空 / 格式不对 / http 规则不允许）：不弹窗，保持关闭。
    case keepOff(url: String?)
}

// MARK: - 记录

/// `server_sync_consent:<url>` 的读写。
///
/// 值只有 `FULL` / `NEW_ONLY` 两种；读不出来（没记录 / 坏值）一律当「还没同意过」，
/// 宁可多问一次，也不能把「没点头」误判成「同意了」。
public final class ServerSyncConsentStore {

    /// 键名前缀（规范里写死的那个）。
    public static let keyPrefix = ServerSyncStateStore.Key.syncConsentPrefix

    private let state: ServerSyncStateStore
    private let now: () -> Int64

    public init(
        database: SQLiteDatabase,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.state = ServerSyncStateStore(database: database)
        self.now = now
    }

    public convenience init(
        stores: DatabaseStores,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.init(database: stores.database, now: now)
    }

    // MARK: 读

    /// 这台服务端的同意状态；没记录 / 记录坏了 → nil。
    public func decision(forBaseURLText raw: String) -> ServerSyncConsentDecision? {
        guard let url = ServerConfig.normalizedBaseURLText(raw),
              let value = state.string(ServerSyncStateStore.Key.syncConsent(url)) else {
            return nil
        }
        return ServerSyncConsentDecision(rawValue: value)
    }

    /// 已知规范化地址时的读法（App 里已经有 `serverConfig.baseURL` 的场景）。
    public func decision(forBaseURL url: URL?) -> ServerSyncConsentDecision? {
        guard let url else { return nil }
        return decision(forBaseURLText: url.absoluteString)
    }

    /// 地址可用就返回规范化后的字符串（同一台服务端的不同写法得到同一个键）。
    public func normalizedURLText(_ raw: String) -> String? {
        ServerConfig.normalizedBaseURLText(raw)
    }

    // MARK: 写

    /// 记下同意。地址不合法时什么都不写（没有可归属的服务端）。
    public func record(_ decision: ServerSyncConsentDecision, forBaseURLText raw: String) throws {
        guard let url = ServerConfig.normalizedBaseURLText(raw) else { return }
        try state.set(
            decision.rawValue,
            for: ServerSyncStateStore.Key.syncConsent(url),
            at: now()
        )
    }

    /// 忘掉这台服务端的同意（测试 / 「重新问一次」用）。
    public func clear(forBaseURLText raw: String) throws {
        guard let url = ServerConfig.normalizedBaseURLText(raw) else { return }
        try state.set(nil, for: ServerSyncStateStore.Key.syncConsent(url), at: now())
    }
}

// MARK: - 状态机

/// 「这次拨开关该不该弹窗」与「用户选了之后该怎么走」的纯逻辑。
///
/// 刻意不碰存储：调用方先 `store.decision(forBaseURLText:)` 读出 `stored`，
/// 再调 `plan(...)`。这样单测可以只验证状态迁移，不必造数据库。
public enum ServerSyncConsentGate {

    /// 用户把开关从「本地」拨到「云端」时调用。
    ///
    /// - Parameters:
    ///   - baseURLText: 输入框里的地址（可能带末尾斜杠 / 空白）。
    ///   - stored: 这台地址已经记录过的决定（没有就是 nil）。
    public static func plan(
        baseURLText: String,
        stored: ServerSyncConsentDecision?
    ) -> ServerSyncConsentPlan {
        guard let url = ServerConfig.normalizedBaseURLText(baseURLText) else {
            return .keepOff(url: nil)
        }
        guard let stored else { return .prompt(url: url) }
        return .proceed(url: url, decision: stored)
    }

    /// 用户在弹窗上点了某个按钮。
    ///
    /// - Parameter choice: `.cancel` 时返回 `.keepOff`，**不落记录**（下次还会问）。
    public static func resolve(
        _ choice: ServerSyncConsentChoice,
        baseURLText: String
    ) -> ServerSyncConsentResolution {
        guard let url = ServerConfig.normalizedBaseURLText(baseURLText),
              let decision = choice.decision else {
            return .keepOff
        }
        return .proceed(url: url, decision: decision)
    }

    /// 冷启动 / 回前台时该按哪种同意方式跑 bootstrap。
    ///
    /// 没有记录时**按 `NEW_ONLY` 处理**：0.12 之前没有这个弹窗，升级上来的用户
    /// 可能已经开着服务端模式；默认「只同步新内容」意味着「不会在没有点头的情况下
    /// 把历史传上去」。设置页拨开关时仍会重新问一次（`plan` 返回 `.prompt`）。
    public static func activationConsent(
        stored: ServerSyncConsentDecision?
    ) -> ServerSyncConsentDecision {
        stored ?? .newOnly
    }
}
