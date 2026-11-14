import Foundation
import SwiftUI

/// 导航目的地。
///
/// 用值类型而不是一堆 Bool/可选状态：返回栈由 `NavigationStack` 管，
/// 深链接（比如点消息里的链接打开内置浏览器）只需要 push 一个 `.browser(url)`。
enum Route: Hashable {
    case chat(sessionId: String)
    case browser(url: String)
    case settings
    case personas
    /// 直接进「编辑这个人」（首页长按 / 设置里点进来都走同一条路）。
    case personaEditor(personaId: String)
    case memories
    case backups
    /// 主动唤醒规则（他就按这个节奏自己来找你）。
    case proactive
}

// MARK: - 根视图的导航路径

/// 把根视图的 `NavigationPath` 放进环境。
///
/// 为什么需要它：有几处「点一下之后要先做点事、再跳页」的地方（首页点联系人要先确保他那段
/// 会话存在；聊天页里的菜单项要推一个设置页）。`NavigationLink(value:)` 只能表达「直接跳」，
/// 这些地方要么包一个假的按钮，要么就得拿到路径自己 append。给一个环境值比到处传 Binding 干净。
private struct MFNavigationPathKey: EnvironmentKey {
    static let defaultValue: Binding<NavigationPath>? = nil
}

extension EnvironmentValues {
    var mfNavigationPath: Binding<NavigationPath>? {
        get { self[MFNavigationPathKey.self] }
        set { self[MFNavigationPathKey.self] = newValue }
    }
}
