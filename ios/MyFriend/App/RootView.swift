import SwiftUI

/// 根视图：负责「打不开数据库」这种启动失败，以及把 `AppEnvironment` 传给所有页面。
struct RootView: View {
    @EnvironmentObject private var env: AppEnvironment
    @State private var path = NavigationPath()

    var body: some View {
        if let error = env.bootError {
            BootFailureView(message: error)
        } else {
            NavigationStack(path: $path) {
                HomeView()
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .chat(let sessionId):
                            ChatScreen(sessionId: sessionId)
                        case .browser(let url):
                            InAppBrowserView(url: url)
                        case .settings:
                            SettingsScreen()
                        case .personas:
                            PersonaListScreen()
                        case .personaEditor(let personaId):
                            PersonaEditorScreen(personaId: personaId)
                        case .memories:
                            MemoryListScreen()
                        case .backups:
                            BackupScreen()
                        case .proactive:
                            ProactiveListScreen()
                        }
                    }
            }
            .environment(\.mfNavigationPath, $path)
            .onReceive(AppNotifications.shared.$pendingSessionId) { sessionId in
                // 点开通知 → 进他那段聊天。消费完清空，免得返回栈里再被推一次。
                guard let sessionId, !sessionId.isEmpty else { return }
                AppNotifications.shared.pendingSessionId = nil
                path.append(Route.chat(sessionId: sessionId))
            }
            .task {
                // 调试启动助手：Debug 包里读环境变量（建本地 API 配置、可选自动发一句话、直接进会话页）。
                // Release 包里 `DebugSeeding.bootstrap` 是空实现，这段等于不存在。
                DebugSeeding.bootstrap(env: env, path: $path)
            }
        }
    }
}

private struct BootFailureView: View {
    let message: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(.secondary)
            Text(L("打不开他"))
                .font(.system(.title3, design: .serif))
            Text(message)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
    }
}
