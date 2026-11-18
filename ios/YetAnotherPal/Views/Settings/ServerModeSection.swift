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
/// 0.13 起多了一条**安全闸**（`spec/sync-consent/README.md`）：
/// - 从本地拨到云端时先弹窗（同意并同步 / 只同步之后的新内容 / 取消，默认焦点是取消）；
///   取消保持关闭、不写同意记录，换地址会重新问；
/// - 「同意并同步」会先把元数据/文本同步 + 历史媒体逐项上传跑完，**成功才切模式**；
///   进度条上有取消按钮（取消后仍用本地模式，已上传的媒体留在服务端）；
/// - 从云端拨回本地时先做一次全量下载并校验条数，**通过才关闭**，失败保持云端模式。
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
    /// 正在跑「切换云端」或「切回本地」（同步 + 全量下载都可能跑几十秒）。
    @State private var switching = false
    /// 进度条上的「取消」真正取消的就是它。
    @State private var switchTask: Task<Void, Never>?
    /// 弹窗状态（非 nil = 正在问「要不要把对话交给云端」）。
    @State private var consentPrompt: ConsentPrompt?

    private var accent: Color { Color(argb: env.currentSettings.accentColor) }

    /// 开关的绑定。
    ///
    /// 为什么不用 `$enabled` + `onChange`：`loadFromEnvironment()` 会把开关**恢复**成
    /// 「当前其实开着」，那不是用户的切换动作；用 onChange 会误弹一次同意弹窗。
    /// 这里只在用户真的点开关时（setter 被调用）才走 `handleToggle`，
    /// 具体把开关停在哪由切换结果决定（取消/失败都停在关闭）。
    private var toggleBinding: Binding<Bool> {
        Binding(
            get: { enabled },
            set: { next in
                guard loaded, !switching else { return }
                guard next != enabled else { return }
                handleToggle(next)
            }
        )
    }

    /// 同意弹窗的上下文：只记地址，不记令牌（弹窗文案里不出现任何秘密）。
    private struct ConsentPrompt: Identifiable, Equatable {
        let id = UUID()
        let urlText: String
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            MFSectionTitle(text: L("服务端模式"))

            MFCard {
                MFToggleRow(
                    icon: "server.rack",
                    title: L("把状态和聊天交给服务端"),
                    subtitle: L("打开后，聊天与心情都由他那边生成，这台机器只缓存；关掉就回到本机模式。"),
                    isOn: toggleBinding,
                    tint: accent
                )
                .disabled(switching)

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
                            if testing || switching {
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
                    .disabled(testing || switching)

                    Spacer(minLength: 4)

                    if env.serverModeActive {
                        Text(L("正在用服务端"))
                            .font(.caption2)
                            .foregroundStyle(accent)
                    }
                }
                .padding(.top, 10)

                // 切换云端 / 切回本地正在跑：给一行文字 + 一个真的能取消的按钮。
                if switching {
                    HStack(spacing: 8) {
                        Text(L("正在把云端的记录取回来…"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer(minLength: 4)
                        Button(L("取消")) {
                            switchTask?.cancel()
                        }
                        .font(.caption)
                        .buttonStyle(.plain)
                        .foregroundStyle(accent)
                    }
                    .padding(.top, 6)
                }

                if let result {
                    Text(result)
                        .font(.caption)
                        .foregroundStyle(resultIsError ? .red : .secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 6)
                }

                // 本机部署时，手机上的模型配置已经交给服务端（云端情绪/进化要靠它调模型）。
                // 文案由 Kit 生成（含模型名），所以用 verbatim，不进本地化资源。
                if let note = env.serverLlmConfigPushMessage {
                    Text(verbatim: note)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 4)
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
        // 同意弹窗：三个选项，默认焦点是「取消」（`.cancel` 角色 + Esc 触发）。
        // 规范：取消保持关闭、不写同意记录；换地址会重新问。
        .alert(
            L("把对话交给云端处理？"),
            isPresented: Binding(
                get: { consentPrompt != nil },
                set: { if !$0 { consentPrompt = nil } }
            ),
            presenting: consentPrompt
        ) { prompt in
            Button(L("同意并同步")) {
                chooseConsent(.agreeAndSync, urlText: prompt.urlText)
            }
            Button(L("只同步之后的新内容")) {
                chooseConsent(.newOnly, urlText: prompt.urlText)
            }
            Button(L("取消"), role: .cancel) {
                // 取消：留在本地模式，不写记录（下次拨开关还会问）。
                enabled = false
                resultIsError = false
                result = L("已取消，仍用本机模式")
            }
            .keyboardShortcut(.cancelAction)
        } message: { prompt in
            Text(L("会把你本机的历史会话、人格、记忆与图片/语音/视频上传到该服务端；可取消。"))
        }
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

    // MARK: - 开关

    /// 拨开关：开 → 先看要不要弹同意；关 → 先全量下载再关。
    private func handleToggle(_ next: Bool) {
        guard loaded, !switching else {
            enabled = env.serverModeActive
            return
        }
        guard next else {
            if env.serverModeActive {
                startDeactivation()
            } else {
                enabled = false
            }
            return
        }
        let urlText = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !urlText.isEmpty else {
            enabled = false
            resultIsError = true
            result = L("请先填服务端地址")
            return
        }
        switch env.serverSyncConsentPlan(baseURLText: urlText) {
        case .prompt(let url):
            // 先保持关闭，等用户在弹窗里选；选「取消」就停在这儿。
            enabled = false
            result = nil
            resultIsError = false
            consentPrompt = ConsentPrompt(urlText: url)
        case .proceed(_, let decision):
            startActivation(decision: decision)
        case .keepOff:
            enabled = false
            resultIsError = true
            result = L("必须是 https；调试版可以用本机或局域网的 http。")
        }
    }

    /// 用户在弹窗里选了某项：落记录（取消不落）→ 跑同步 → 成功才把开关拨上去。
    private func chooseConsent(_ choice: ServerSyncConsentChoice, urlText: String) {
        consentPrompt = nil
        let resolution = env.recordServerConsent(choice, baseURLText: urlText)
        switch resolution {
        case .proceed(_, let decision):
            address = urlText
            startActivation(decision: decision)
        case .keepOff:
            enabled = false
            resultIsError = false
            result = L("已取消，仍用本机模式")
        }
    }

    private func startActivation(decision: ServerSyncConsentDecision) {
        switching = true
        result = nil
        resultIsError = false
        let urlText = address.trimmingCharacters(in: .whitespacesAndNewlines)
        let token = currentToken()
        switchTask?.cancel()
        switchTask = Task {
            let outcome = await env.enableServerModeWithConsent(
                baseURLText: urlText,
                token: token,
                consent: decision
            )
            await MainActor.run {
                switching = false
                switchTask = nil
                switch outcome {
                case .activated(let report):
                    enabled = true
                    tokenDraft = ""
                    tokenEdited = false
                    resultIsError = false
                    var text = L("已经切到云端")
                    if report.mediaUploaded + report.mediaFailed > 0 {
                        text += " · " + Lf("媒体上传 %d 项（失败 %d 项）", report.mediaUploaded, report.mediaFailed)
                    }
                    if report.historySkipped {
                        text += " · " + L("只同步了之后的新内容")
                    }
                    result = text
                case .cancelled:
                    enabled = false
                    resultIsError = false
                    result = L("已取消，仍用本机模式")
                case .failed(let message):
                    enabled = false
                    resultIsError = true
                    result = message
                }
            }
        }
    }

    private func startDeactivation() {
        switching = true
        result = nil
        resultIsError = false
        switchTask?.cancel()
        switchTask = Task {
            let outcome = await env.disableServerModeDownloadingAll()
            await MainActor.run {
                switching = false
                switchTask = nil
                switch outcome {
                case .disabled(let report):
                    enabled = false
                    resultIsError = false
                    // 条数校验给的是 Kit 的中文结论（`ServerRestoreVerification.describe`）：
                    // 它比在 App 层另拼一句更准确（会说清取回多少条、本机独有保留多少）。
                    result = L("已切回本机模式") + " · " + report.verification.describe
                case .failed(let message):
                    // 下载/校验失败：开关弹回打开（仍用云端），否则会永久缺一段历史。
                    enabled = true
                    resultIsError = true
                    result = message
                }
            }
        }
    }

    // MARK: - 写

    private func saveAndTest() {
        let urlText = address.trimmingCharacters(in: .whitespacesAndNewlines)
        if enabled {
            // 同一台服务端、已经连着：老行为（只更新令牌 + 测连接），不用再问一遍。
            let sameServer = env.serverModeActive
                && ServerConfig.normalizedBaseURLText(urlText) == env.serverConfig.baseURL?.absoluteString
                && !urlText.isEmpty
            if env.serverModeActive && !sameServer {
                // 换一台服务端之前必须先关掉当前这台：关掉时才会把云端记录全量取回来。
                // 直接切过去等于丢掉「旧服务端上还没同步到本机」的那段历史。
                resultIsError = true
                result = L("换服务端之前要先关掉开关：关掉时会先把云端的记录取回来。")
                return
            }
            if !sameServer {
                switch env.serverSyncConsentPlan(baseURLText: urlText) {
                case .prompt(let url):
                    consentPrompt = ConsentPrompt(urlText: url)
                    return
                case .proceed(_, let decision):
                    startActivation(decision: decision)
                    return
                case .keepOff:
                    resultIsError = true
                    result = L("必须是 https；调试版可以用本机或局域网的 http。")
                    return
                }
            }
        } else if env.serverModeActive {
            // 拨回本地：必须先全量下载 + 校验。
            startDeactivation()
            return
        }

        testing = true
        result = nil
        resultIsError = false
        do {
            try env.saveServerSettings(
                enabled: enabled,
                baseURLText: urlText,
                token: currentToken()
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

    /// 没动输入框 = 保留已保存的令牌（空字符串会被当成「清除」，这里是刻意保护的）。
    private func currentToken() -> String? {
        if tokenEdited { return tokenDraft.trimmingCharacters(in: .whitespacesAndNewlines) }
        return env.serverCredentials.token()
    }
}
