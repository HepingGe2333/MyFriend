import SwiftUI

@main
struct MyFriendApp: App {

    @StateObject private var env = AppEnvironment()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // 后台调度必须在启动完成前注册（标识符与 Config/Info.plist 里的一致）。
        // 模拟器上系统不会执行这两种任务，`BackgroundScheduler` 会直接跳过注册；
        // 注册失败也不影响前台，一切只是「少了一次后台机会」。
        BackgroundScheduler.shared.register()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(env)
                .preferredColorScheme(colorScheme(for: env.currentSettings.themeMode))
                .dynamicTypeSize(dynamicType(for: env.currentSettings.fontScale))
                .tint(Color(argb: env.currentSettings.accentColor))
                .task {
                    // 通知权限（第一次启动问一次）+ 后台任务真的能拿到 AppEnvironment。
                    AppNotifications.shared.configure()
                    BackgroundScheduler.shared.attach(env: env)
                    // 启动自检：清掉库里那些「不像记忆」的旧摘要（1.7 事故的脏数据不会自己消失）。
                    _ = env.repairHistorySummariesIfNeeded()
                    // 打开 App 时补算错过的时间（iOS 不允许常驻后台，只能在这里补）。
                    await env.performCatchUp()
                }
                .onChange(of: scenePhase) { phase in
                    switch phase {
                    case .active:
                        AppNotifications.shared.setAppActive(true)
                        Task { await env.performCatchUp() }
                    case .background:
                        AppNotifications.shared.setAppActive(false)
                        // 进后台前把下一次后台时间排上；拿不到就算了。
                        BackgroundScheduler.shared.scheduleNext()
                    default:
                        break
                    }
                }
        }
    }

    /// 字号缩放 → 系统的动态字号档位。
    ///
    /// 用系统档位而不是到处乘 `fontScale`：这样图标、行高、间距会一起跟着变，
    /// 也不会出现「字放大了但气泡没变大」这种错位。
    private func dynamicType(for scale: Float) -> DynamicTypeSize {
        switch scale {
        case ..<0.9: return .small
        case ..<1.05: return .large
        case ..<1.2: return .xLarge
        default: return .xxLarge
        }
    }

    /// 主题模式 → SwiftUI 的 `ColorScheme`。跟随系统时传 nil，交给系统决定。
    private func colorScheme(for mode: ThemeMode) -> ColorScheme? {
        switch mode {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}
