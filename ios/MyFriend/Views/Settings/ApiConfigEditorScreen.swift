import SwiftUI

/// 一条 API 配置的编辑页。
///
/// 只有三件事值得在这里反复解释：
/// 1. **Key 不回显**：保存进 Keychain 之后输入框永远是空的，占位文案会说「已保存」；
///    想换就直接填新的，留空就是不动它。
/// 2. **采样参数可以留空**：留空 = 用模型默认值，不要替用户猜一个数字。
/// 3. **地址先过 `NetworkGuard`**：http 必须显式打开「允许不安全 HTTP」，
///    这条开关是给本地 mock / 局域网服务用的，不是给公网用的。
struct ApiConfigEditorScreen: View {
    let configId: String?

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    @State private var name = ""
    @State private var provider: ProviderType = .openai
    @State private var baseUrl = ""
    @State private var model = ""
    @State private var apiKeyInput = ""
    @State private var hasExistingKey = false
    @State private var allowInsecureHttp = false
    @State private var temperatureText = ""
    @State private var maxTokensText = ""
    @State private var topPText = ""

    @State private var webToolsEnabled = false
    @State private var webToolsProvider: WebToolsProvider = .deepSeek
    @State private var webToolsBaseUrl = ""
    @State private var webToolsKeyInput = ""
    @State private var hasExistingWebToolsKey = false

    @State private var loaded = false
    @State private var testing = false
    @State private var testMessage: String?
    @State private var testOK = false
    @State private var pendingAction: PendingAction?
    @State private var banner: MFAlert?

    private var isNew: Bool { configId == nil }
    private var accent: Color { Color(argb: env.currentSettings.accentColor) }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedBaseUrl: String { baseUrl.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedModel: String { model.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                basicsCard
                paramsCard
                webToolsCard
                networkCard
                connectionCard
                if !isNew { deleteCard }
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.vertical, 16)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle(isNew ? L("添加 API") : L("编辑 API"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(L("保存")) { save() }
                    .fontWeight(.medium)
                    .disabled(!canSave)
            }
        }
        .onAppear(perform: loadIfNeeded)
        .mfAlert($banner)
        .confirmationDialog(
            pendingAction?.title ?? "",
            isPresented: Binding(
                get: { pendingAction != nil },
                set: { presented in if !presented { pendingAction = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(pendingAction?.confirmTitle ?? L("确定"), role: .destructive) {
                guard let action = pendingAction else { return }
                pendingAction = nil
                switch action {
                case .deleteConfig: deleteConfig()
                case .clearKey: clearSavedKey()
                case .clearWebToolsKey: clearSavedWebToolsKey()
                }
            }
            Button(L("取消"), role: .cancel) { pendingAction = nil }
        } message: {
            Text(pendingAction?.message ?? "")
        }
    }

    // MARK: - 基本信息

    private var basicsCard: some View {
        MFCard {
            FieldLabel(L("配置名称"), hint: L("只是给你自己看的，比如「DeepSeek 主力」。"))
            TextField(L("例如：DeepSeek 主力"), text: $name)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 14)

            FieldLabel("Provider", hint: L("影响用量与花费的读法，不影响请求形状。"))
            MFChoiceBar(options: MFProviderLabel.all(), selection: $provider, accent: accent)
                .padding(.bottom, 14)

            FieldLabel("Base URL", hint: L("OpenAI 兼容地址，例如 https://api.deepseek.com 或 https://api.openai.com/v1。"))
            TextField("https://api.openai.com/v1", text: $baseUrl)
                .textFieldStyle(.plain)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 14)

            FieldLabel(L("模型"), hint: L("例如 gpt-4o-mini、deepseek-chat。人格里可以再单独覆盖。"))
            TextField("gpt-4o-mini / deepseek-chat / …", text: $model)
                .textFieldStyle(.plain)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 14)

            FieldLabel("API Key", hint: keyHint)
            SecureField(
                "",
                text: $apiKeyInput,
                prompt: Text(hasExistingKey ? L("已保存 · 留空不修改") : "sk-…")
            )
            .textFieldStyle(.plain)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(.vertical, 8)
            .padding(.horizontal, 10)
            .background(fieldBackground)

            if hasExistingKey {
                HStack(spacing: 8) {
                    Text(L("这台手机已经存了一个 Key，界面上不会显示它。"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button(L("清掉")) { pendingAction = .clearKey }
                        .font(.caption)
                        .tint(MFUI.danger)
                }
                .padding(.top, 6)
            }

            MFNote(text: L("Key 存在 Keychain 里，不进数据库、不进备份，删除配置时一起清掉。"))
        }
    }

    private var keyHint: String {
        hasExistingKey ? L("留空就是继续用已经保存的那个。") : L("去服务商后台建一个，粘贴进来。")
    }

    // MARK: - 采样参数

    private var paramsCard: some View {
        MFCard {
            Text(L("采样参数"))
                .font(.system(.body, design: .serif))
            Text(L("都可以留空——留空就用模型默认值，不替你做决定。"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 2)
                .padding(.bottom, 12)

            parameterField(
                title: "Temperature",
                placeholder: "0 – 2",
                text: $temperatureText,
                invalid: temperatureInvalid,
                hint: L("越大越发散。聊天一般 0.7 – 1.0。")
            )
            parameterField(
                title: "Max Output Tokens",
                placeholder: L("模型默认"),
                text: $maxTokensText,
                invalid: maxTokensInvalid,
                hint: L("一次回复最长多少 tokens。")
            )
            parameterField(
                title: "Top P",
                placeholder: "0 – 1",
                text: $topPText,
                invalid: topPInvalid,
                hint: L("另一种控制发散的方式，通常只调其中一个。")
            )
        }
    }

    private func parameterField(
        title: String,
        placeholder: String,
        text: Binding<String>,
        invalid: Bool,
        hint: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.footnote)
                .foregroundStyle(.secondary)
            TextField(placeholder, text: text)
                .textFieldStyle(.plain)
                .keyboardType(.decimalPad)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
            Text(invalid ? L("填个数字，或者留空。") : hint)
                .font(.caption)
                .foregroundStyle(invalid ? MFUI.danger : Color.secondary)
        }
        .padding(.bottom, 10)
    }

    // MARK: - 联网工具

    /// 联网工具（`web_search` / `web_fetch`）的搜索服务配置。
    ///
    /// 这一节只在打开「联网工具」之后才展开：关掉时它连字段都不出现，表单不会莫名其妙
    /// 长出五六个跟聊天无关的输入框。DEEPSEEK 复用上面聊天的地址与 Key（所以留空就行），
    /// GENERIC 是自建服务、没有默认地址，必须填完整地址。
    private var webToolsCard: some View {
        MFCard {
            MFToggleRow(
                icon: "globe",
                title: L("联网工具"),
                subtitle: L("让他能搜索和读网页。关掉以后，这两个工具对他不可用。"),
                isOn: $webToolsEnabled,
                tint: accent
            )

            if webToolsEnabled {
                FieldLabel(L("搜索服务"), hint: L("用哪家的搜索接口。DeepSeek 官方接口和聊天共用同一个地址与 Key。"))
                MFChoiceBar(options: webToolsProviderOptions, selection: $webToolsProvider, accent: accent)
                    .padding(.top, 4)
                    .padding(.bottom, 14)

                FieldLabel("Base URL", hint: webToolsBaseHint)
                TextField(webToolsBasePlaceholder, text: $webToolsBaseUrl)
                    .textFieldStyle(.plain)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.vertical, 8)
                    .padding(.horizontal, 10)
                    .background(fieldBackground)
                    .padding(.bottom, webToolsBaseInvalid ? 4 : 14)

                if webToolsBaseInvalid {
                    Text(L("这个地址不能用：只支持 http/https，不能是本机或内网地址。"))
                        .font(.caption)
                        .foregroundStyle(MFUI.danger)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.bottom, 14)
                }

                FieldLabel(L("搜索 Key"), hint: webToolsKeyHint)
                SecureField(
                    "",
                    text: $webToolsKeyInput,
                    prompt: Text(hasExistingWebToolsKey ? L("已保存 · 留空不修改") : L("留空会复用上面的 API Key"))
                )
                .textFieldStyle(.plain)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)

                if hasExistingWebToolsKey {
                    HStack(spacing: 8) {
                        Text(L("这台手机已经存了联网 Key，界面上不会显示它。"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button(L("清掉")) { pendingAction = .clearWebToolsKey }
                            .font(.caption)
                            .tint(MFUI.danger)
                    }
                    .padding(.top, 6)
                }

                MFNote(text: L("搜索 Key 与主 Key 一样只存在 Keychain 里，不进数据库、不进备份。"))
                    .padding(.top, 10)
            }
        }
    }

    private var webToolsProviderOptions: [(WebToolsProvider, String)] {
        WebToolsProvider.allCases.map { ($0, Self.webToolsProviderLabel($0)) }
    }

    /// 产品名（Tavily / Brave / Serper）不翻译：它们就是服务自己的名字。
    private static func webToolsProviderLabel(_ provider: WebToolsProvider) -> String {
        switch provider {
        case .deepSeek: return L("DeepSeek 官方")
        case .tavily: return "Tavily"
        case .brave: return "Brave"
        case .serper: return "Serper"
        case .generic: return L("自建服务")
        }
    }

    private var webToolsBasePlaceholder: String {
        switch webToolsProvider {
        case .deepSeek: return L("留空就用上面聊天配置的地址")
        case .tavily: return "https://api.tavily.com"
        case .brave: return "https://api.search.brave.com/res/v1"
        case .serper: return "https://google.serper.dev"
        case .generic: return L("https://你的搜索服务地址/search")
        }
    }

    private var webToolsBaseHint: String {
        webToolsProvider == .generic
            ? L("自建服务必须填完整地址，请求会原样发到这个地址。")
            : L("留空就用默认地址；填了会覆盖默认值。")
    }

    private var webToolsKeyHint: String {
        webToolsProvider == .generic
            ? L("自建服务不需要 Key 的话可以留空。")
            : L("这一项可以留空：留空就复用上面的 API Key。")
    }

    // MARK: - 网络

    private var networkCard: some View {
        MFCard {
            MFToggleRow(
                icon: "lock.open",
                title: L("允许不安全 HTTP"),
                subtitle: L("只有本地 mock、局域网服务才需要打开。公网服务请用 HTTPS：明文 HTTP 等于把 Key 交给同一网段的所有人。"),
                isOn: $allowInsecureHttp,
                tint: accent
            )
            MFNote(text: L("Gemini / Claude 现在也按 OpenAI 兼容的形状发请求；要用原生接口，请填它们的兼容网关地址。"))
        }
    }

    // MARK: - 测试连接

    private var connectionCard: some View {
        MFCard {
            HStack(spacing: 10) {
                Button(action: testConnection) {
                    HStack(spacing: 6) {
                        if testing {
                            ProgressView().scaleEffect(0.7)
                        } else {
                            Image(systemName: "bolt.horizontal.circle")
                        }
                        Text(testing ? L("测试中…") : L("测试连接"))
                    }
                    .font(.footnote)
                }
                .tint(accent)
                .disabled(testing || trimmedBaseUrl.isEmpty)

                Spacer()
            }

            if let testMessage {
                Text(testMessage)
                    .font(.caption)
                    .foregroundStyle(testOK ? accent : MFUI.danger)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)
            }

            MFNote(text: L("测试只会请求 /models，不会真的说一句话，也不花钱。"))
        }
    }

    // MARK: - 删除

    private var deleteCard: some View {
        MFCard {
            Button {
                pendingAction = .deleteConfig
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "trash")
                        .font(.system(size: 14))
                        .frame(width: 22)
                    Text(L("删除这个配置"))
                        .font(.system(.body, design: .serif))
                    Spacer()
                }
                .foregroundStyle(MFUI.danger)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - 载入 / 保存 / 动作

    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        guard let configId, let config = (try? env.stores.apiConfigs.byId(configId)) ?? nil else {
            if isNew && baseUrl.isEmpty { baseUrl = "https://api.openai.com/v1" }
            return
        }
        name = config.name
        provider = config.provider
        baseUrl = config.baseUrl
        model = config.model
        allowInsecureHttp = config.allowInsecureHttp
        temperatureText = Self.format(config.temperature)
        maxTokensText = config.maxOutputTokens.map { "\($0)" } ?? ""
        topPText = Self.format(config.topP)
        hasExistingKey = env.apiKeys.isConfigured(for: config.id)
        webToolsEnabled = config.webToolsEnabled
        webToolsProvider = WebToolsProvider(stored: config.webToolsProvider)
        webToolsBaseUrl = config.webToolsBaseUrl
        hasExistingWebToolsKey = (env.apiKeys.webToolsAPIKey(for: config.id) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }

    private func save() {
        let now = DomainClock.nowMillis
        let existing: ApiConfigEntity? = configId.flatMap { try? env.stores.apiConfigs.byId($0) } ?? nil
        var entity = existing ?? ApiConfigEntity(
            id: UUID().uuidString,
            name: trimmedName,
            createdAt: now,
            updatedAt: now
        )
        entity.name = trimmedName.isEmpty ? L("未命名配置") : trimmedName
        entity.provider = provider
        entity.baseUrl = NetworkGuard.normalizeBaseUrl(trimmedBaseUrl)
        entity.model = trimmedModel
        entity.allowInsecureHttp = allowInsecureHttp
        entity.temperature = parsedDouble(temperatureText, range: 0...2)
        entity.topP = parsedDouble(topPText, range: 0...1)
        entity.maxOutputTokens = parsedInt(maxTokensText)
        entity.webToolsEnabled = webToolsEnabled
        entity.webToolsProvider = webToolsProvider.rawValue
        // 空串是合法值：默认地址由客户端按 provider 解析，不在这里替用户填死。
        entity.webToolsBaseUrl = trimmedWebToolsBaseUrl
        entity.updatedAt = now

        do {
            try env.stores.apiConfigs.upsert(entity)
            let typedKey = apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines)
            if !typedKey.isEmpty {
                try env.apiKeys.setAPIKey(typedKey, for: entity.id)
            }
            let typedWebToolsKey = webToolsKeyInput.trimmingCharacters(in: .whitespacesAndNewlines)
            if !typedWebToolsKey.isEmpty {
                try env.apiKeys.setWebToolsAPIKey(typedWebToolsKey, for: entity.id)
            }
            // 「删除全部本地数据」之后重新播种的会话还没有绑定配置（那时一个配置都没有）：
            // 新配置存下来时补一次绑定，否则第一次发消息只会得到「请先为该会话选择 API 配置」。
            env.bindMissingApiConfigs()
            dismiss()
        } catch {
            banner = MFAlert(title: L("没能保存"), message: error.localizedDescription)
        }
    }

    private func deleteConfig() {
        guard let configId else { return }
        do {
            try env.stores.apiConfigs.delete(id: configId)
            try? env.apiKeys.deleteKeys(for: configId)
            dismiss()
        } catch {
            banner = MFAlert(title: L("没能删掉"), message: error.localizedDescription)
        }
    }

    private func clearSavedKey() {
        guard let configId else { return }
        do {
            try env.apiKeys.setAPIKey(nil, for: configId)
            hasExistingKey = false
            apiKeyInput = ""
        } catch {
            banner = MFAlert(title: L("没能清掉"), message: error.localizedDescription)
        }
    }

    private func clearSavedWebToolsKey() {
        guard let configId else { return }
        do {
            try env.apiKeys.setWebToolsAPIKey(nil, for: configId)
            hasExistingWebToolsKey = false
            webToolsKeyInput = ""
        } catch {
            banner = MFAlert(title: L("没能清掉"), message: error.localizedDescription)
        }
    }

    private func testConnection() {
        let entity = draftEntity()
        let key = apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines)
        let apiKey = key.isEmpty ? (configId.flatMap { env.apiKeys.apiKey(for: $0) }) : key
        guard (apiKey ?? "").isEmpty == false else {
            testOK = false
            testMessage = L("先填一个 API Key，再测。")
            return
        }
        let client = env.webClient
        testing = true
        testMessage = nil
        Task {
            do {
                let models = try await client.fetchModels(config: entity, apiKey: apiKey)
                await MainActor.run {
                    testing = false
                    testOK = true
                    if models.isEmpty {
                        testMessage = L("连上了，但服务没返回模型列表。模型名可以自己填。")
                    } else {
                        let sample = models.prefix(3).joined(separator: LText.listSeparator)
                        testMessage = Lcount("连上了：这个 Key 能看到 %d 个可用项，比如 %@。", models.count, sample)
                    }
                }
            } catch {
                await MainActor.run {
                    testing = false
                    testOK = false
                    testMessage = Lf("没连上：%@", (error as NSError).localizedDescription)
                }
            }
        }
    }

    /// 用当前表单拼一个临时配置去测连接（不落库）。
    private func draftEntity() -> ApiConfigEntity {
        ApiConfigEntity(
            id: configId ?? "draft",
            name: trimmedName.isEmpty ? L("草稿") : trimmedName,
            provider: provider,
            baseUrl: NetworkGuard.normalizeBaseUrl(trimmedBaseUrl),
            model: trimmedModel,
            allowInsecureHttp: allowInsecureHttp,
            temperature: parsedDouble(temperatureText, range: 0...2),
            topP: parsedDouble(topPText, range: 0...1),
            maxOutputTokens: parsedInt(maxTokensText)
        )
    }

    // MARK: - 校验与解析

    private var trimmedWebToolsBaseUrl: String {
        webToolsBaseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canSave: Bool {
        !trimmedName.isEmpty
            && !trimmedBaseUrl.isEmpty
            && !trimmedModel.isEmpty
            && !temperatureInvalid
            && !topPInvalid
            && !maxTokensInvalid
            && !webToolsBaseInvalid
    }

    /// 联网工具的地址校验：关掉联网工具时不校验；GENERIC 没有默认地址，必须填；
    /// 填了就得是可以真正发出去的 http(s) 地址（本机 / 内网 / 带账号密码都由 `WebUrlPolicy` 拦）。
    private var webToolsBaseInvalid: Bool {
        guard webToolsEnabled else { return false }
        if webToolsProvider == .generic && trimmedWebToolsBaseUrl.isEmpty { return true }
        guard !trimmedWebToolsBaseUrl.isEmpty else { return false }
        return WebUrlPolicy.check(trimmedWebToolsBaseUrl) != nil
    }

    private var temperatureInvalid: Bool { !validatedDouble(temperatureText, range: 0...2) }
    private var topPInvalid: Bool { !validatedDouble(topPText, range: 0...1) }
    private var maxTokensInvalid: Bool {
        let text = maxTokensText.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return false }
        guard let value = Int(text) else { return true }
        return value <= 0
    }

    private func validatedDouble(_ text: String, range: ClosedRange<Double>) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return true }
        guard let value = Double(trimmed) else { return false }
        return range.contains(value)
    }

    private func parsedDouble(_ text: String, range: ClosedRange<Double>) -> Double? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let value = Double(trimmed), range.contains(value) else { return nil }
        return value
    }

    private func parsedInt(_ text: String) -> Int? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let value = Int(trimmed), value > 0 else { return nil }
        return value
    }

    private static func format(_ value: Double?) -> String {
        guard let value else { return "" }
        if value == value.rounded() { return String(Int(value)) }
        return String(format: "%g", value)
    }

    private var fieldBackground: some View {
        RoundedRectangle(cornerRadius: 9, style: .continuous)
            .fill(Color.secondary.opacity(0.08))
    }

    /// 几个会删东西的确认共用同一个弹窗（同一层视图挂多个 `confirmationDialog` 只有最后一个可靠）。
    private enum PendingAction {
        case deleteConfig
        case clearKey
        case clearWebToolsKey

        var title: String {
            switch self {
            case .deleteConfig: return L("删除这个 API 配置？")
            case .clearKey: return L("清掉已保存的 Key？")
            case .clearWebToolsKey: return L("清掉联网 Key？")
            }
        }

        var confirmTitle: String {
            switch self {
            case .deleteConfig: return L("删除")
            case .clearKey: return L("清掉")
            case .clearWebToolsKey: return L("清掉")
            }
        }

        var message: String {
            switch self {
            case .deleteConfig: return L("这台手机上存的 Key 也会一起删掉。")
            case .clearKey: return L("清掉以后，这个配置就用不了了；重新填一个就行。")
            case .clearWebToolsKey: return L("清掉以后，联网搜索会复用上面的 API Key；想再单独填就重新输入。")
            }
        }
    }
}

/// 表单里的字段标题 + 一句解释。
private struct FieldLabel: View {
    let title: String
    let hint: String

    init(_ title: String, hint: String) {
        self.title = title
        self.hint = hint
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.footnote.weight(.medium))
            Text(hint)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.bottom, 6)
    }
}
