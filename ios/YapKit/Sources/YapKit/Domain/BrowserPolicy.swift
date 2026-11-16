import Foundation

// ---------------------------------------------------------------------------
// 内置浏览器的加固策略（纯逻辑，0.11.0 审计 P2-8）。
//
// 入口是模型消息里的链接（`Domain/LinkDetector.swift` 已经只放行 http/https），
// 但**页面内的跳转与重定向不受那是控制**：`<a href="javascript:…">`、`data:`、
// `file:`、`myapp://` 都能把应用内浏览器带到别处；共享的持久 Cookie / LocalStorage
// 又让「同一个 WebView 里打开的站点」互相认得，等于跨站追踪。
//
// 这一层的三条决定（与项目「克制」的取向一致）：
// 1. 数据仓 `nonPersistent()`：Cookie、缓存、LocalStorage 只活在这次浏览里，退出即弃；
// 2. **默认关闭 JavaScript**：分享/阅读场景够用，少一整类攻击面。
//    需要脚本的页面会被降级成「读得到正文」，用户要交互可以点右上角去系统浏览器；
// 3. 导航白名单只放行 `http` / `https`（且 host 非空）；危险 scheme 一律 `cancel`；
//    `mailto:` / `tel:` / 自定义 scheme 交给系统（或干脆不处理），绝不交回 WebView。
//
// 这里刻意不 import WebKit：策略是纯数据，单测在 macOS 上直接跑；App 层拿着
// `BrowserPolicy.allowsJavaScript` / `BrowserPolicy.dataStore` 去装配 `WKWebViewConfiguration`。
// ---------------------------------------------------------------------------

/// 一次导航该怎么处理。
public enum BrowserNavigationDecision: Equatable, Sendable {
    /// 应用内放行。
    case allow
    /// 取消（不加载、也不外跳）：`javascript:` / `data:` / `file:` / `about:` 之类。
    case cancel
    /// 交给系统打开（`mailto:` / `tel:` / 自定义 scheme）。
    case external
}

public enum BrowserPolicy {

    /// 浏览数据的存放方式。`nonPersistent` = Cookie/LocalStorage 退出即弃。
    public enum DataStoreMode: Equatable, Sendable {
        case nonPersistent
        case persistent
    }

    /// 与 Android 的 WebView 一致：不给页面脚本能力（阅读场景不需要）。
    public static let allowsJavaScript = false
    /// 会话级数据仓：不写盘、不跨启动共享。
    public static let dataStore: DataStoreMode = .nonPersistent

    /// 允许在应用内加载的 scheme。
    public static let allowedSchemes: Set<String> = ["http", "https"]

    /// 一律取消（不加载也不外跳）的 scheme。
    ///
    /// `file:` 单列的理由：允许它等于允许页面读沙盒里的文件（附件、数据库）。
    public static let blockedSchemes: Set<String> = [
        "javascript", "data", "file", "about", "blob", "filesystem", "ws", "wss", "ftp"
    ]

    /// 导航白名单。
    ///
    /// - Parameter url: 目标地址（`nil` / 空串都当成「取消」，不给 WebView 一个猜的机会）。
    public static func decide(_ url: URL?) -> BrowserNavigationDecision {
        guard let url else { return .cancel }
        let raw = url.absoluteString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return .cancel }
        let scheme = (url.scheme ?? "").lowercased()
        guard !scheme.isEmpty else { return .cancel }
        if allowedSchemes.contains(scheme) {
            // `https://` 没有 host 同样是坏地址：WebView 只会停在空白页，不如明确取消。
            let host = (url.host ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return host.isEmpty ? .cancel : .allow
        }
        if blockedSchemes.contains(scheme) { return .cancel }
        // 剩下的（mailto / tel / itms-apps / 自己的 scheme）不该在应用内浏览器里加载。
        return .external
    }

    /// 字符串入口（链接来自模型消息，先经过 `URL(string:)` 的宽松解析）。
    public static func decide(rawString: String?) -> BrowserNavigationDecision {
        guard let rawString else { return .cancel }
        let trimmed = rawString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return .cancel }
        return decide(URL(string: trimmed))
    }
}
