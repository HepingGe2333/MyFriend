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
                networkCard
                connectionCard
                if !isNew { deleteCard }
            }
            .padding(.horizontal, MFTheme.gutter)
            .padding(.vertical, 16)
        }
        .background(MFTheme.canvas(scheme).ignoresSafeArea())
        .navigationTitle(isNew ? "添加 API" : "编辑 API")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") { save() }
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
            Button(pendingAction?.confirmTitle ?? "确定", role: .destructive) {
                guard let action = pendingAction else { return }
                pendingAction = nil
                switch action {
                case .deleteConfig: deleteConfig()
                case .clearKey: clearSavedKey()
                }
            }
            Button("取消", role: .cancel) { pendingAction = nil }
        } message: {
            Text(pendingAction?.message ?? "")
        }
    }

    // MARK: - 基本信息

    private var basicsCard: some View {
        MFCard {
            FieldLabel("配置名称", hint: "只是给你自己看的，比如「DeepSeek 主力」。")
            TextField("例如：DeepSeek 主力", text: $name)
                .textFieldStyle(.plain)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 14)

            FieldLabel("Provider", hint: "影响用量与花费的读法，不影响请求形状。")
            MFChoiceBar(options: MFProviderLabel.all(), selection: $provider, accent: accent)
                .padding(.bottom, 14)

            FieldLabel("Base URL", hint: "OpenAI 兼容地址，例如 https://api.deepseek.com 或 https://api.openai.com/v1。")
            TextField("https://api.openai.com/v1", text: $baseUrl)
                .textFieldStyle(.plain)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(fieldBackground)
                .padding(.bottom, 14)

            FieldLabel("模型", hint: "例如 gpt-4o-mini、deepseek-chat。人格里可以再单独覆盖。")
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
                prompt: Text(hasExistingKey ? "已保存 · 留空不修改" : "sk-…")
            )
            .textFieldStyle(.plain)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(.vertical, 8)
            .padding(.horizontal, 10)
            .background(fieldBackground)

            if hasExistingKey {
                HStack(spacing: 8) {
                    Text("这台手机已经存了一个 Key，界面上不会显示它。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("清掉") { pendingAction = .clearKey }
                        .font(.caption)
                        .tint(MFUI.danger)
                }
                .padding(.top, 6)
            }

            MFNote(text: "Key 存在 Keychain 里，不进数据库、不进备份，删除配置时一起清掉。")
        }
    }

    private var keyHint: String {
        hasExistingKey ? "留空就是继续用已经保存的那个。" : "去服务商后台建一个，粘贴进来。"
    }

    // MARK: - 采样参数

    private var paramsCard: some View {
        MFCard {
            Text("采样参数")
                .font(.system(.body, design: .serif))
            Text("都可以留空——留空就用模型默认值，不替你做决定。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 2)
                .padding(.bottom, 12)

            parameterField(
                title: "Temperature",
                placeholder: "0 – 2",
                text: $temperatureText,
                invalid: temperatureInvalid,
                hint: "越大越发散。聊天一般 0.7 – 1.0。"
            )
            parameterField(
                title: "Max Output Tokens",
                placeholder: "模型默认",
                text: $maxTokensText,
                invalid: maxTokensInvalid,
                hint: "一次回复最长多少 tokens。"
            )
            parameterField(
                title: "Top P",
                placeholder: "0 – 1",
                text: $topPText,
                invalid: topPInvalid,
                hint: "另一种控制发散的方式，通常只调其中一个。"
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
            Text(invalid ? "填个数字，或者留空。" : hint)
                .font(.caption)
                .foregroundStyle(invalid ? MFUI.danger : Color.secondary)
        }
        .padding(.bottom, 10)
    }

    // MARK: - 网络

    private var networkCard: some View {
        MFCard {
            MFToggleRow(
                icon: "lock.open",
                title: "允许不安全 HTTP",
                subtitle: "只有本地 mock、局域网服务才需要打开。公网服务请用 HTTPS：明文 HTTP 等于把 Key 交给同一网段的所有人。",
                isOn: $allowInsecureHttp,
                tint: accent
            )
            MFNote(text: "Gemini / Claude 现在也按 OpenAI 兼容的形状发请求；要用原生接口，请填它们的兼容网关地址。")
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
                        Text(testing ? "测试中…" : "测试连接")
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

            MFNote(text: "测试只会请求 /models，不会真的说一句话，也不花钱。")
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
                    Text("删除这个配置")
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
        hasExistingKey = !(env.apiKeys.apiKey(for: config.id) ?? "").isEmpty
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
        entity.name = trimmedName.isEmpty ? "未命名配置" : trimmedName
        entity.provider = provider
        entity.baseUrl = NetworkGuard.normalizeBaseUrl(trimmedBaseUrl)
        entity.model = trimmedModel
        entity.allowInsecureHttp = allowInsecureHttp
        entity.temperature = parsedDouble(temperatureText, range: 0...2)
        entity.topP = parsedDouble(topPText, range: 0...1)
        entity.maxOutputTokens = parsedInt(maxTokensText)
        entity.updatedAt = now

        do {
            try env.stores.apiConfigs.upsert(entity)
            let typedKey = apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines)
            if !typedKey.isEmpty {
                try env.apiKeys.setAPIKey(typedKey, for: entity.id)
            }
            dismiss()
        } catch {
            banner = MFAlert(title: "没能保存", message: error.localizedDescription)
        }
    }

    private func deleteConfig() {
        guard let configId else { return }
        do {
            try env.stores.apiConfigs.delete(id: configId)
            try? env.apiKeys.deleteKeys(for: configId)
            dismiss()
        } catch {
            banner = MFAlert(title: "没能删掉", message: error.localizedDescription)
        }
    }

    private func clearSavedKey() {
        guard let configId else { return }
        do {
            try env.apiKeys.setAPIKey(nil, for: configId)
            hasExistingKey = false
            apiKeyInput = ""
        } catch {
            banner = MFAlert(title: "没能清掉", message: error.localizedDescription)
        }
    }

    private func testConnection() {
        let entity = draftEntity()
        let key = apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines)
        let apiKey = key.isEmpty ? (configId.flatMap { env.apiKeys.apiKey(for: $0) }) : key
        guard (apiKey ?? "").isEmpty == false else {
            testOK = false
            testMessage = "先填一个 API Key，再测。"
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
                        testMessage = "连上了，但服务没返回模型列表。模型名可以自己填。"
                    } else {
                        let sample = models.prefix(3).joined(separator: "、")
                        testMessage = "连上了：这个 Key 能看到 \(models.count) 个模型，比如 \(sample)。"
                    }
                }
            } catch {
                await MainActor.run {
                    testing = false
                    testOK = false
                    testMessage = "没连上：\((error as NSError).localizedDescription)"
                }
            }
        }
    }

    /// 用当前表单拼一个临时配置去测连接（不落库）。
    private func draftEntity() -> ApiConfigEntity {
        ApiConfigEntity(
            id: configId ?? "draft",
            name: trimmedName.isEmpty ? "草稿" : trimmedName,
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

    private var canSave: Bool {
        !trimmedName.isEmpty
            && !trimmedBaseUrl.isEmpty
            && !trimmedModel.isEmpty
            && !temperatureInvalid
            && !topPInvalid
            && !maxTokensInvalid
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

    /// 两个会删东西的确认共用同一个弹窗（同一层视图挂多个 `confirmationDialog` 只有最后一个可靠）。
    private enum PendingAction {
        case deleteConfig
        case clearKey

        var title: String {
            switch self {
            case .deleteConfig: return "删除这个 API 配置？"
            case .clearKey: return "清掉已保存的 Key？"
            }
        }

        var confirmTitle: String {
            switch self {
            case .deleteConfig: return "删除"
            case .clearKey: return "清掉"
            }
        }

        var message: String {
            switch self {
            case .deleteConfig: return "这台手机上存的 Key 也会一起删掉。"
            case .clearKey: return "清掉以后，这个配置就用不了了；重新填一个就行。"
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
