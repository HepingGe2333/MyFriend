import Foundation

// ---------------------------------------------------------------------------
// 服务层 ⇄ 网络层的接缝实现。
//
// 背景：`Services/CompletionClient.swift` 定义了一个「只要一段文本」的窄协议，
// 后台那些事（记忆抽取、每日整理、打分、主动思考、回忆、压缩）全靠它。
// 它刻意不认识 `URLSession`、SSE、usage 形状；这边就负责把这些细节填上。
//
// 为什么要单独一个文件而不是让 `OpenAICompatibleClient` 直接实现两个协议：
// 两个协议的错误语义不一样——服务层要的是 `CompletionFailure`（「这一轮跳过」），
// 网络层给的是 `ApiException`（带状态码，界面要按码给不同的中文）。
// 混在一起会让「后台任务失败」也弹出 401 的界面提示；这里做一次转换，两边都干净。
//
// 记账与并发闸**不在这里**：它们已经在 `OpenAICompatibleClient` 里
// （拿名额 → 发请求 → 拿回 usage 就记一笔），与 Android 的 `OpenAiAdapter` 一致。
// 所以这个适配器只搬参数（含「这笔算在谁头上」）、搬文本、翻错误。
// ---------------------------------------------------------------------------

/// 把 `ChatCompleting`（真实网络实现）接到服务层的 `CompletionClient` 上。
public final class CompletionClientAdapter: CompletionClient {

    private let client: ChatCompleting

    public init(client: ChatCompleting) {
        self.client = client
    }

    public func complete(
        config: CompletionConfig,
        model: String,
        messages: [CompletionMessage],
        params: CompletionParams,
        kind: CompletionKind,
        context: UsageContext
    ) async throws -> CompletionResult {
        let entity = Self.entity(from: config)
        // 服务层的参数是「这一轮想要的」，配置里的是「这个配置的默认」：
        // 显式给了就用显式的，没给就退回配置值（与 Android 的 InferenceParams 行为一致）。
        var inference = entity.inferenceParams()
        if let temperature = params.temperature { inference.temperature = temperature }
        if let topP = params.topP { inference.topP = topP }
        if let maxTokens = params.maxOutputTokens { inference.maxOutputTokens = maxTokens }

        let wire = messages.map { WireMessage(role: $0.role, content: $0.content) }
        let resolvedModel = model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? entity.model
            : model
        do {
            let text = try await client.complete(
                config: entity,
                apiKey: config.apiKey,
                model: resolvedModel,
                messages: wire,
                params: inference,
                extraHeaders: [:],
                kind: kind.rawValue,
                usageContext: context
            )
            // tokens 由网络层记账时自己算过；服务层只关心文本，所以这里不重复解析一遍。
            return CompletionResult(text: text, model: resolvedModel)
        } catch is CancellationError {
            throw CancellationError()
        } catch let api as ApiException {
            throw CompletionFailure(api.message)
        } catch {
            let message = (error as NSError).localizedDescription
            throw CompletionFailure(message.isEmpty ? String(localized: "调用失败") : message)
        }
    }

    /// 服务层的配置形状 → 网络层要的实体。
    ///
    /// 注意 `apiKeyCipher` 留空：iOS 的 Key 在 Keychain，明文只在 `CompletionConfig.apiKey`
    /// 里活这一下，不该被塞进一个「看起来像要落库」的字段。
    static func entity(from config: CompletionConfig) -> ApiConfigEntity {
        ApiConfigEntity(
            id: config.id,
            name: config.name,
            provider: config.provider,
            baseUrl: config.baseUrl,
            model: config.model,
            customHeadersJson: config.customHeadersJson,
            allowInsecureHttp: config.allowInsecureHttp,
            temperature: config.temperature,
            topP: config.topP,
            maxOutputTokens: config.maxOutputTokens,
            frequencyPenalty: config.frequencyPenalty,
            presencePenalty: config.presencePenalty
        )
    }
}
