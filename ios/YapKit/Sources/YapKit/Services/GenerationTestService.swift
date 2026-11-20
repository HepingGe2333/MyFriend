import Foundation

// ---------------------------------------------------------------------------
// 设置页的「测试生成」（0.15）。
//
// 与它旁边那个「测试连接」的区别：
// - 测试连接只请求 `/models`，证明地址与 Key 有效，**不证明这个配置能说一句话**；
// - 测试生成真的发一次补全，把「多久开口 / 吐了多少字 / 用了什么工具」摊开给用户看。
//
// 它必须**完全不碰聊天库**：不建会话、不写消息表、不抽记忆、不触发任何后台任务。
// 所以这里不经过 `ChatService`（那条路会落库、会跑工具、会更新会话预览），只借用
// 网络层的 `streamChat(...)` 接缝，把流收完就结束。
//
// 为什么要流式而不是 `complete`：用户点「开始测试」之后要看到「正在测试」，
// 而且工具协议块（```yap-tool）可能夹在任意一段增量里；按流累积 buffer 再交给
// `ToolProtocol.extract` / `visiblePrefix`，才能与聊天页用**同一套**协议解析。
// ---------------------------------------------------------------------------

/// 一次测试生成要发出去的请求（由 `GenerationTestService` 拼好，网络层只负责发）。
public struct GenerationTestRequest: Sendable {
    public var config: ApiConfigEntity
    public var apiKey: String?
    public var model: String
    public var messages: [WireMessage]
    public var params: InferenceParams

    public init(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: [WireMessage],
        params: InferenceParams
    ) {
        self.config = config
        self.apiKey = apiKey
        self.model = model
        self.messages = messages
        self.params = params
    }
}

/// 一次测试生成的结果（只用于展示，不落库）。
public struct GenerationTestReport: Equatable, Sendable {
    /// 从发出请求到流结束的毫秒数（真实时钟由调用方注入，单测不 sleep）。
    public var elapsedMillis: Int64
    /// 可见正文的字数（`ToolProtocol.visiblePrefix` 之后，不含工具协议块）。
    public var charCount: Int
    /// 这一轮解析出来的工具名（按出现顺序去重；没有就是空数组）。
    public var toolNames: [String]
    /// 实际请求的模型名。
    public var model: String
    /// 可见正文的开头（最多 80 字）。
    public var preview: String

    public init(
        elapsedMillis: Int64,
        charCount: Int,
        toolNames: [String],
        model: String,
        preview: String
    ) {
        self.elapsedMillis = max(0, elapsedMillis)
        self.charCount = max(0, charCount)
        self.toolNames = toolNames
        self.model = model
        self.preview = preview
    }

    /// 耗时文案：「860 毫秒」/「1.8 秒」（一位小数，固定用 `.`，不随地区变逗号）。
    public var elapsedText: String {
        if elapsedMillis < 1_000 {
            return "\(elapsedMillis) 毫秒"
        }
        let seconds = Double(elapsedMillis) / 1_000.0
        let text = String(
            format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), seconds
        )
        return text + " 秒"
    }

    /// 字数文案：「0 字」/「42 字」。
    public var charCountText: String { "\(charCount) 字" }

    /// 工具文案：没有就明确写「没用工具」，而不是留空让用户猜。
    public var toolsText: String {
        toolNames.isEmpty ? "没用工具" : toolNames.joined(separator: "、")
    }
}

/// 测试生成的结果：成功给报告，失败给一句**中文**原因（不抛到界面）。
public enum GenerationTestOutcome: Equatable, Sendable {
    case success(GenerationTestReport)
    case failure(String)

    public var report: GenerationTestReport? {
        if case .success(let report) = self { return report }
        return nil
    }

    public var failureMessage: String? {
        if case .failure(let message) = self { return message }
        return nil
    }
}

/// 设置页「测试生成」的服务层：拼一句固定测试话 → 收流 → 解析工具 → 出报告。
///
/// 依赖注入的形状有意做成一个闭包（而不是直接依赖 `OpenAICompatibleClient`）：
/// 服务层只需要「给我一条字符串流」，App 层用 `ChatCompleting.streamChat` 满足它，
/// 单测用几段固定增量满足它；两边都不必起 URLSession。
public final class GenerationTestService {

    /// 固定自检提示：只要一句话，别用工具（用了也不影响结果，只是让耗时更诚实）。
    public static let systemPrompt = "这是一次接口自检。请用一句很短的话回应，不要使用任何工具。"
    /// 固定测试话：与真实聊天无关，不会被写进任何会话。
    public static let userPrompt = "你好，如果你收到了这条消息，就回一句「收到」。"

    /// 「发一条流式补全」的注入面。
    public typealias Streamer = (GenerationTestRequest) -> AsyncThrowingStream<String, Error>

    private let stream: Streamer
    private let now: () -> Int64

    /// - Parameters:
    ///   - stream: 发请求并返回字符串增量流（生产 = `ChatCompleting.streamChat`）。
    ///   - now: 毫秒时钟。单测注入固定序列，断言耗时而不真的等待。
    public init(
        stream: @escaping Streamer,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.stream = stream
        self.now = now
    }

    /// 跑一次测试。**永远不抛错**：任何失败都折成中文原因交给界面显示。
    public func run(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String
    ) async -> GenerationTestOutcome {
        let resolvedModel = Self.resolvedModel(explicit: model, config: config)
        let request = GenerationTestRequest(
            config: config,
            apiKey: apiKey,
            model: resolvedModel,
            messages: [
                WireMessage(role: "system", content: Self.systemPrompt),
                WireMessage(role: "user", content: Self.userPrompt)
            ],
            // 与聊天/后台任务同一个口径：配置里的显式参数优先，缺的用 ApiDefaults。
            params: config.effectiveInferenceParams()
        )

        let startedAt = now()
        var buffer = ""
        do {
            for try await chunk in stream(request) {
                buffer += chunk
            }
        } catch {
            return .failure(Self.chineseReason(for: error))
        }

        let elapsed = max(0, now() - startedAt)
        // 与聊天页同一套协议解析：可见正文切掉未闭合/已闭合的工具块，工具名从完整 buffer 解析。
        let visible = ToolProtocol.visiblePrefix(buffer)
        let calls = ToolProtocol.extract(buffer).calls
        var seenTools = Set<String>()
        let toolNames = calls.compactMap { call -> String? in
            let name = call.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, seenTools.insert(name).inserted else { return nil }
            return name
        }
        return .success(GenerationTestReport(
            elapsedMillis: elapsed,
            charCount: visible.count,
            toolNames: toolNames,
            model: resolvedModel,
            preview: String(visible.prefix(Self.previewLength))
        ))
    }

    /// 预览字数上限（需求：开头 80 字）。
    public static let previewLength = 80

    // MARK: - 内部

    /// 模型解析：显式（会话/人格覆盖）优先，否则用配置里的；都不填就空串
    /// （界面会显示「还没填模型」，真正的错误由服务端在请求时给出）。
    static func resolvedModel(explicit: String, config: ApiConfigEntity) -> String {
        let trimmed = explicit.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? config.model.trimmingCharacters(in: .whitespacesAndNewlines) : trimmed
    }

    /// 错误 → 中文原因。
    ///
    /// 只信任**我们自己**写的错误类型（`ApiException` / `CompletionFailure` 的消息本来就是
    /// 中文人话）；其它错误的 `localizedDescription` 可能是英文、也可能是 URLSession 的
    /// 「The request timed out.」——直接摊给用户看既不符合产品语气，也没告诉他能怎么办。
    /// 所以未知错误统一折成一句「检查地址、Key、模型名」。
    static func chineseReason(for error: Error) -> String {
        if error is CancellationError {
            return "测试被取消了"
        }
        if let api = error as? ApiException {
            let message = api.message.trimmingCharacters(in: .whitespacesAndNewlines)
            return message.isEmpty ? "接口返回了错误" : message
        }
        if let failure = error as? CompletionFailure {
            let message = failure.message.trimmingCharacters(in: .whitespacesAndNewlines)
            return message.isEmpty ? "接口返回了错误" : message
        }
        return "测试没通过：检查一下地址、Key 和模型名，再试一次"
    }
}
