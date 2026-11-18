import Foundation

// ---------------------------------------------------------------------------
// 把「本机已经配好的模型配置」推给**本机部署**的服务端（0.13 第二阶段）。
//
// 为什么需要它：0.13 起服务端要自己跑情绪判定与人格进化（`POST /v1/personas/{id}/mood|evolution/…`）。
// 那些端点在服务端进程里调模型，用的是**服务端自己**的配置；本机模式下用户的 Key 只在
// 手机的 Keychain 里，服务端根本不知道。让用户去命令行敲 `--llm-key` 是把「装一个 App」
// 变成「部署一个后端」，所以本机部署时客户端连上之后顺手推一次。
//
// 三条规矩（与服务端 `LlmConfigRoutes` 对齐）：
// 1. **只有本机回环才推**（`ServerConfig.isLoopback`）。远程服务器一律不推：
//    远程的 Key 是部署者的责任，客户端能推 Key 等于「任何客户端都能把它换掉」。
// 2. **失败只记日志**。推送是让云端功能更好用的顺带动作，不是连接的一部分；
//    推不上去（老版本服务端没有这个端点、服务端没开数据目录）不该让用户看到任何错误。
// 3. **Key 不落盘、不打印**。它只出现在这一个请求体里；`Outcome` 里没有任何凭据字段，
//    日志也只说「推了」/「没推」与原因。
// ---------------------------------------------------------------------------

/// `POST /v1/llm-config` 的请求体（字段名与服务端逐字一致）。
public struct ServerLlmConfigRequest: Encodable, Equatable, Sendable {
    public var baseUrl: String
    public var model: String
    public var apiKey: String
    /// `OPENAI` / `GEMINI` / `CLAUDE` / `DEEPSEEK`（服务端只认这四个）。
    public var provider: String

    public init(baseUrl: String, model: String, apiKey: String, provider: String) {
        self.baseUrl = baseUrl
        self.model = model
        self.apiKey = apiKey
        self.provider = provider
    }

    private enum CodingKeys: String, CodingKey { case baseUrl, model, apiKey, provider }
}

/// `POST /v1/llm-config` 的回执：**不含 Key**（服务端从不回显）。
public struct ServerLlmConfigResult: Decodable, Equatable, Sendable {
    public var ok: Bool
    public var id: String?
    public var provider: String?
    public var baseUrl: String?
    public var model: String?

    public init(
        ok: Bool = true, id: String? = nil, provider: String? = nil,
        baseUrl: String? = nil, model: String? = nil
    ) {
        self.ok = ok
        self.id = id
        self.provider = provider
        self.baseUrl = baseUrl
        self.model = model
    }

    private enum CodingKeys: String, CodingKey { case ok, id, provider, baseUrl, model }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ok = try container.decodeIfPresent(Bool.self, forKey: .ok) ?? true
        id = try container.decodeIfPresent(String.self, forKey: .id)
        provider = try container.decodeIfPresent(String.self, forKey: .provider)
        baseUrl = try container.decodeIfPresent(String.self, forKey: .baseUrl)
        model = try container.decodeIfPresent(String.self, forKey: .model)
    }
}

public extension ServerAPIClient {

    /// 推一次模型配置（仅本机部署的服务端接受；远程会回 403 `remote_config_disallowed`）。
    ///
    /// 校验全部在本地做完：一个注定 400/403 的请求不发出去（设置页反复连一次服务端
    /// 不该每次都留一条失败日志）。
    @discardableResult
    func pushLlmConfig(_ payload: ServerLlmConfigRequest) async throws -> ServerLlmConfigResult {
        let baseUrl = payload.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let model = payload.model.trimmingCharacters(in: .whitespacesAndNewlines)
        let apiKey = payload.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let provider = payload.provider.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !baseUrl.isEmpty, !model.isEmpty, !apiKey.isEmpty else {
            throw ServerAPIError(
                code: ServerAPIError.Code.invalidArgument,
                message: "本机的模型配置还不完整（地址 / 模型 / Key 缺一不可）"
            )
        }
        guard baseUrl.hasPrefix("http://") || baseUrl.hasPrefix("https://") else {
            throw ServerAPIError(
                code: ServerAPIError.Code.invalidArgument,
                message: "模型地址必须是 http(s) 地址"
            )
        }
        guard Self.llmConfigProviders.contains(provider) else {
            throw ServerAPIError(
                code: ServerAPIError.Code.invalidArgument,
                message: "服务端不认识这种模型服务商（\(provider)）"
            )
        }
        return try await post(
            path: "/llm-config",
            body: ServerLlmConfigRequest(
                baseUrl: baseUrl, model: model, apiKey: apiKey, provider: provider
            ),
            as: ServerLlmConfigResult.self
        )
    }

    /// 服务端 `LlmConfigRoutes` 认的四种服务商。
    static let llmConfigProviders: Set<String> = ["OPENAI", "GEMINI", "CLAUDE", "DEEPSEEK"]
}

// MARK: - 推送编排

/// 「连上本机服务端之后，把手机上的模型配置推过去」这件事本身。
///
/// 装配层只需要在连接成功之后调一次 `pushIfNeeded()`；判断（该不该推、推什么）全在这里。
public final class ServerLlmConfigPusher {

    /// 一次推送的结果。**不含任何凭据**：日志/界面上可以放心打印 `describe`。
    public struct Outcome: Equatable, Sendable {
        public enum Status: String, Sendable {
            /// 推上去了。
            case pushed
            /// 对方是远程服务器，按规矩不推。
            case skippedRemote
            /// 本机没有可用的模型配置（没填 Key / 没有配置）。
            case skippedNoConfig
            /// 服务端模式没开 / 地址无效。
            case skippedDisabled
            /// 推了但失败（老服务端没有这个端点、服务端没开数据目录……）。
            case failed
        }

        public var status: Status
        /// 一句人话（进日志；也可以显示在设置页的调试信息里）。
        public var message: String

        public init(status: Status, message: String) {
            self.status = status
            self.message = message
        }

        public var didPush: Bool { status == .pushed }
    }

    private let client: ServerAPIClient
    private let apiConfig: ApiConfigEntity?
    private let apiKey: String?
    private let log: (String) -> Void

    /// - Parameters:
    ///   - client: 当前连接（配置变化时装配层会重建，所以每次 use 现造这个类型）。
    ///   - apiConfig: 本机当前生效的 API 配置（`AppEnvironment.resolveConfig` 的产物）。
    ///   - apiKey: 那个配置的 Key（从 Keychain 读出来的明文，只在本类型里活一次）。
    public init(
        client: ServerAPIClient,
        apiConfig: ApiConfigEntity?,
        apiKey: String?,
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.client = client
        self.apiConfig = apiConfig
        self.apiKey = apiKey
        self.log = log
    }

    /// 该不该推（纯判断，方便单测直接喂配置）。
    public static func shouldPush(config: ServerConfig) -> Bool {
        config.isUsable && config.isLoopback
    }

    /// 推一次；任何失败都只写日志、返回 `Outcome`（绝不抛）。
    @discardableResult
    public func pushIfNeeded() async -> Outcome {
        let config = client.config
        guard config.isUsable else {
            return report(Outcome(status: .skippedDisabled, message: "服务端模式没开，这次不推配置"))
        }
        guard config.isLoopback else {
            return report(Outcome(
                status: .skippedRemote,
                message: "服务端在远程（\(config.baseURL?.host ?? "?")），本机配置不推上去"
            ))
        }
        guard let apiConfig else {
            return report(Outcome(status: .skippedNoConfig, message: "本机还没有模型配置，这次没东西可推"))
        }
        let key = apiKey?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let model = apiConfig.model.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty, !model.isEmpty,
              !apiConfig.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return report(Outcome(
                status: .skippedNoConfig,
                message: "本机的模型配置还不完整（地址 / 模型 / Key 缺一不可），这次不推"
            ))
        }
        do {
            let result = try await client.pushLlmConfig(
                ServerLlmConfigRequest(
                    baseUrl: apiConfig.baseUrl,
                    model: model,
                    apiKey: key,
                    provider: Self.serverProvider(for: apiConfig)
                )
            )
            return report(Outcome(
                status: .pushed,
                message: "已经把本机的模型配置交给服务端（\(result.model ?? model)）"
            ))
        } catch {
            // 老版本服务端没有这个端点（404）、或者它在远程（403）、或者没开数据目录（503）：
            // 都不是用户要处理的事 —— 云端功能自己会退回到「没配置就不做」。
            return report(Outcome(
                status: .failed,
                message: "模型配置没推上去（\(ServerSyncService.describe(error))）"
            ))
        }
    }

    /// 本机 provider → 服务端认的 provider。
    ///
    /// iOS 的 `ProviderType` 只有三种（OpenAI 兼容 / Gemini / Claude）；DeepSeek 走的是
    /// 「OpenAI 兼容」那一档，而服务端把 `DEEPSEEK` 单独列出来了（它有自己的默认地址与
    /// 计费口径）。这里按**地址**再认一次 DeepSeek，让服务端日志里看到的是真实的服务商。
    static func serverProvider(for config: ApiConfigEntity) -> String {
        let baseUrl = config.baseUrl.lowercased()
        if baseUrl.contains("deepseek") { return "DEEPSEEK" }
        switch config.provider {
        case .openai: return "OPENAI"
        case .gemini: return "GEMINI"
        case .claude: return "CLAUDE"
        }
    }

    private func report(_ outcome: Outcome) -> Outcome {
        switch outcome.status {
        case .pushed, .skippedRemote, .skippedNoConfig, .skippedDisabled:
            log("[llm-config] \(outcome.message)")
        case .failed:
            log("[llm-config] \(outcome.message)")
        }
        return outcome
    }
}
