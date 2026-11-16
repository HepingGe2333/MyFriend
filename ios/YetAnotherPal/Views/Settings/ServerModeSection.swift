import SwiftUI

/// 设置页的「服务端模式」（M3）：开关 / 地址 / 令牌 / 测试连接。
///
/// 三条设计约束（与任务书一致）：
/// 1. **令牌只进 Keychain**：输入框是 `SecureField`，写盘走 `ServerCredentialStore`；
///    界面上永远只显示「已保存 ••••••••abcd」这种脱敏摘要，`#` 不出现全文；
/// 2. **https 是默认**：http 只在 Debug + 本机/局域网地址下放行（规则在
///    `ServerConfig.validatedURL`，这里只把中文原因显示出来）；
/// 3. **保存即生效**：没有「保存」按钮堆叠——改完点一下「测试并保存」，
///    成功就当场上报（重新装配客户端 + 起同步/事件流），失败就把原因显示出来、保留用户填的内容。
///
/// 这一版只做「连上哪台机器」；人格/记忆/备份的 CRUD 上移属于 M5，不在这个卡片里。
struct ServerModeSection: View {
    @EnvironmentObject private var env: AppEnvironment

    @State private var enabled = false
    @State private var address = ""
    @State private var tokenDraft = ""
    /// 用户在这一轮里有没有动过令牌输入框（没动就不覆盖已保存的令牌）。
    @State private var tokenEdited = false
    @State private var testing = false
    @State private var result: String?
    @State private var resultIsError = false
    @State private var loaded = false

    private var accent: Color { Color(argb: env.currentSettings.accentColor) }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: L("服务端模式"))

            MFCard {
                MFToggleRow(
                    icon: "server.rack",
                    title: L("把状态和聊天交给服务端"),
                    subtitle: L("打开后，聊天与心情都由他那边生成，这台机器只缓存；关掉就回到本机模式。"),
                    isOn: $enabled,
                    tint: accent
                )

                MFRowDivider(leadingInset: 0)

                VStack(alignment: .leading, spacing: 8) {
                    Text(L("地址"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    // 占位符是「示例地址」而不是一句话：用 verbatim 的 Text，别把它塞进本地化资源
                    //（`LocalizationResourceTests` 会把 `L("…")` 里的东西当成必须翻译的文案）。
                    TextField(
                        "",
                        text: $address,
                        prompt: Text(verbatim: "https://chat.example.com")
                    )
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .font(.system(.footnote, design: .monospaced))
                        .padding(10)
                        .background(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(Color.primary.opacity(0.05))
                        )
                    Text(L("必须是 https；调试版可以用本机或局域网的 http。"))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 10)

                MFRowDivider(leadingInset: 0)

                VStack(alignment: .leading, spacing: 8) {
                    Text(L("令牌"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    SecureField(tokenPlaceholder, text: $tokenDraft)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.footnote, design: .monospaced))
                        .padding(10)
                        .background(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(Color.primary.opacity(0.05))
                        )
                        .onChange(of: tokenDraft) { _ in tokenEdited = true }
                    HStack(spacing: 10) {
                        Text(env.serverCredentials.hasToken
                             ? (env.serverCredentials.maskedToken() ?? L("已保存"))
                             : L("还没有令牌"))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Spacer(minLength: 4)
                        if env.serverCredentials.hasToken {
                            Button(L("清除令牌")) {
                                try? env.serverCredentials.setToken(nil)
                                tokenDraft = ""
                                tokenEdited = false
                                result = L("令牌已清除")
                                resultIsError = false
                            }
                            .font(.caption2)
                            .buttonStyle(.plain)
                            .foregroundStyle(.red)
                        }
                    }
                }
                .padding(.vertical, 10)

                MFRowDivider(leadingInset: 0)

                HStack(spacing: 10) {
                    Button {
                        saveAndTest()
                    } label: {
                        HStack(spacing: 6) {
                            if testing {
                                ProgressView().controlSize(.small)
                            }
                            Text(testing ? L("正在连接…") : L("测试并保存"))
                                .font(.system(.footnote, design: .serif))
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(
                            Capsule().fill(accent.opacity(0.14))
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(testing)

                    Spacer(minLength: 4)

                    if env.serverModeActive {
                        Text(L("正在用服务端"))
                            .font(.caption2)
                            .foregroundStyle(accent)
                    }
                }
                .padding(.top, 10)

                if let result {
                    Text(result)
                        .font(.caption)
                        .foregroundStyle(resultIsError ? .red : .secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 6)
                }

                // 首次连上这台服务端时，会在后台把两边记录对齐（人格/记忆/聊天历史）。
                // 这一步可能跑几十秒，所以把它说清楚、给出进度，而不是让用户等一个黑屏。
                if let progress = env.serverBootstrapProgress {
                    HStack(alignment: .top, spacing: 8) {
                        ProgressView().controlSize(.small)
                        // 进度文案由 Kit 的 `ServerBootstrapProgress.describe` 给（中文，
                        // 和错误文案同一套；不新增本地化 key，资源文件不在本次改动范围）。
                        Text(verbatim: progress.describe)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.top, 6)
                } else if let error = env.serverBootstrapError {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(verbatim: error)
                            .font(.caption)
                            .foregroundStyle(.red)
                            .fixedSize(horizontal: false, vertical: true)
                        Button(L("再试一次")) {
                            Task { await env.redoServerBootstrap() }
                        }
                        .font(.caption)
                        .buttonStyle(.plain)
                        .foregroundStyle(accent)
                    }
                    .padding(.top, 6)
                }

                MFNote(text: L("令牌只存在这台设备的钥匙串里，不进备份、不会出现在日志里。"))
            }
        }
        .onAppear(perform: loadFromEnvironment)
    }

    // MARK: - 读

    /// 已保存的令牌不显示全文：输入框留空 + placeholder 说明「已经存过」。
    private var tokenPlaceholder: String {
        env.serverCredentials.hasToken ? L("已保存（留空则不修改）") : L("服务端没开鉴权可以不填")
    }

    private func loadFromEnvironment() {
        guard !loaded else { return }
        loaded = true
        enabled = env.serverCredentials.modeEnabled() ?? env.serverModeActive
        address = env.serverCredentials.storedBaseURLText() ?? env.serverConfig.baseURL?.absoluteString ?? ""
        tokenDraft = ""
        tokenEdited = false
    }

    // MARK: - 写

    private func saveAndTest() {
        testing = true
        result = nil
        resultIsError = false
        let token: String?
        if tokenEdited {
            token = tokenDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            // 没动输入框 = 保留已保存的令牌（空字符串会被当成「清除」，这里是刻意保护的）。
            token = env.serverCredentials.token()
        }
        do {
            try env.saveServerSettings(
                enabled: enabled,
                baseURLText: address.trimmingCharacters(in: .whitespacesAndNewlines),
                token: token
            )
        } catch {
            testing = false
            resultIsError = true
            result = error.localizedDescription
            return
        }
        tokenDraft = ""
        tokenEdited = false

        Task {
            let outcome = await env.testServerConnection()
            await MainActor.run {
                testing = false
                switch outcome {
                case .success(let health):
                    resultIsError = false
                    result = health.describe
                case .failure(let error):
                    resultIsError = true
                    result = error.localizedDescription
                }
            }
        }
    }
}
