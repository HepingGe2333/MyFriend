import Foundation

// ---------------------------------------------------------------------------
// 服务层与网络层之间唯一的接缝：一次「非流式补全」。
//
// 为什么服务层自己定义这个协议，而不是直接依赖 `Net/` 里的东西：
//
// 1. **依赖方向**：记忆抽取、每日整理、自主循环都是「跑在后台的一次模型调用」，
//    它们不关心 URLSession、SSE、provider 的 usage 形状，只关心「给我一段文本」。
//    把这些细节挡在协议后面，服务层的单测可以完全离线跑（假实现返回固定 JSON）。
// 2. **不让两个移植者互相踩**：`Net/` 与 `Chat/` 由另一条线在写，它那边会有
//    `UsageRecorder` / `ChatService`。这里的协议是**服务层的入参形状**，
//    App 层装配时用 Chat 层（或 Net 层）的实现去满足它即可，两边不需要共享类型。
// 3. **用量记账留在外面**：服务层不记 api_usage——那是网络层的职责。
//    这里传 `kind` 与 `usageContext` 只是为了让实现方能把它们带给 UsageRecorder；
//    并发闸也在网络层那一处（`OpenAICompatibleClient`），服务层不需要知道有闸。
//
// 协议刻意保持「同步语义的一次请求」：没有流式、没有工具循环、没有重试。
// 后台这些调用都是「发一次、拿一段 JSON、落库」，重试与退避属于更上层。
// ---------------------------------------------------------------------------

/// 一次请求里的一条消息（system / user / assistant）。
public struct CompletionMessage: Equatable, Sendable {
    public var role: String
    public var content: String

    public init(role: String, content: String) {
        self.role = role
        self.content = content
    }

    public static func system(_ content: String) -> CompletionMessage {
        CompletionMessage(role: "system", content: content)
    }

    public static func user(_ content: String) -> CompletionMessage {
        CompletionMessage(role: "user", content: content)
    }
}

/// 采样参数。nil = 用配置里的值 / 用实现方的默认值。
public struct CompletionParams: Equatable, Sendable {
    public var temperature: Double?
    public var maxOutputTokens: Int?
    public var topP: Double?

    public init(temperature: Double? = nil, maxOutputTokens: Int? = nil, topP: Double? = nil) {
        self.temperature = temperature
        self.maxOutputTokens = maxOutputTokens
        self.topP = topP
    }
}

/// 这次调用算哪一类用途：网络层用它记账（api_usage.kind 的取值与 Android 一致）。
public enum CompletionKind: String, CaseIterable, Sendable {
    case chat = "CHAT"
    case consolidation = "CONSOLIDATION"
    case scoring = "SCORING"
    case agent = "AGENT"
    case recall = "RECALL"
    case extraction = "EXTRACTION"
    case reconsolidation = "RECONSOLIDATION"
    /// 历史压缩（滚动摘要）。Android 的 `UsageKind.HISTORY` 同名，
    /// 用量页要把「他在整理自己的记性」和聊天分开。
    case history = "HISTORY"
}

/// 服务层能看到的 API 配置：只包含「发一次请求需要的东西」。
///
/// 与 `ApiConfigEntity` 的差别：没有 `apiKeyCipher`（iOS 的 Key 在 Keychain 里，
/// 由 App 层解析成明文后放进 `apiKey`），也没有自定义 Header 的落库字符串。
/// 这个结构只在内存里活一次请求，不落盘、不进日志。
public struct CompletionConfig: Equatable, Sendable {
    public var id: String
    public var name: String
    public var provider: ProviderType
    public var baseUrl: String
    public var model: String
    /// 明文 Key。由 App 层从 Keychain 取出来填；服务层不持久化它。
    public var apiKey: String?
    public var temperature: Double?
    public var topP: Double?
    public var maxOutputTokens: Int?
    public var frequencyPenalty: Double?
    public var presencePenalty: Double?
    public var allowInsecureHttp: Bool
    public var customHeadersJson: String

    public init(
        id: String,
        name: String,
        provider: ProviderType = .openai,
        baseUrl: String = "",
        model: String = "",
        apiKey: String? = nil,
        temperature: Double? = nil,
        topP: Double? = nil,
        maxOutputTokens: Int? = nil,
        frequencyPenalty: Double? = nil,
        presencePenalty: Double? = nil,
        allowInsecureHttp: Bool = false,
        customHeadersJson: String = "{}"
    ) {
        self.id = id
        self.name = name
        self.provider = provider
        self.baseUrl = baseUrl
        self.model = model
        self.apiKey = apiKey
        self.temperature = temperature
        self.topP = topP
        self.maxOutputTokens = maxOutputTokens
        self.frequencyPenalty = frequencyPenalty
        self.presencePenalty = presencePenalty
        self.allowInsecureHttp = allowInsecureHttp
        self.customHeadersJson = customHeadersJson
    }

    /// 从本机配置行 + Keychain 里的 Key 组装。App 层用这个，不必自己拼字段。
    public init(entity: ApiConfigEntity, apiKey: String?) {
        self.init(
            id: entity.id,
            name: entity.name,
            provider: entity.provider,
            baseUrl: entity.baseUrl,
            model: entity.model,
            apiKey: apiKey,
            temperature: entity.temperature,
            topP: entity.topP,
            maxOutputTokens: entity.maxOutputTokens,
            frequencyPenalty: entity.frequencyPenalty,
            presencePenalty: entity.presencePenalty,
            allowInsecureHttp: entity.allowInsecureHttp,
            customHeadersJson: entity.customHeadersJson
        )
    }
}

/// 一次补全的返回：只保留服务层用得到的东西。
public struct CompletionResult: Equatable, Sendable {
    public var text: String
    public var model: String?
    public var promptTokens: Int?
    public var completionTokens: Int?

    public init(text: String, model: String? = nil, promptTokens: Int? = nil, completionTokens: Int? = nil) {
        self.text = text
        self.model = model
        self.promptTokens = promptTokens
        self.completionTokens = completionTokens
    }
}

/// 服务层唯一依赖的模型入口。实现由 App 层注入（Chat/Net 层），测试里用假实现。
public protocol CompletionClient {
    func complete(
        config: CompletionConfig,
        model: String,
        messages: [CompletionMessage],
        params: CompletionParams,
        kind: CompletionKind,
        context: UsageContext
    ) async throws -> CompletionResult
}

public extension CompletionClient {
    /// 不关心「这笔算在谁头上」的调用点用这个重载。
    func complete(
        config: CompletionConfig,
        model: String,
        messages: [CompletionMessage],
        params: CompletionParams,
        kind: CompletionKind
    ) async throws -> CompletionResult {
        try await complete(
            config: config, model: model, messages: messages, params: params,
            kind: kind, context: .none
        )
    }
}

/// 服务层内部用的「调用失败」：后台任务要的是「失败了，跳过这一轮」而不是崩溃。
public struct CompletionFailure: Error, LocalizedError, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var errorDescription: String? { message }
    public var description: String { message }
}

extension CompletionResult {
    /// 便利：多数调用点只关心文本。
    public var trimmedText: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }
}
