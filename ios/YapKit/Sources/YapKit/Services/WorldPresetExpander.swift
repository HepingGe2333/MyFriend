import Foundation

// ---------------------------------------------------------------------------
// 世界补全：把「一句角色简介」补全成一个完整的世界预设。
//
// 规范：`spec/persona-guidance/README.md` §2.2 / §6.2（提示词逐字照抄实测那条）。
//
// 设计取舍：
// - 和话风总结一样，这是一次**用完即弃**的分叉调用：不建 session、不写 messages、不给工具；
// - 用户随时可以手填（§2.2：任何时候都可以手填，不依赖 LLM），所以失败不是错误，
//   只是「这次没补全」——调用方拿到 `usedModel = false` 与一句可显示的原因；
// - 没有配置 / 调用失败 / 输出读不懂 → 本地兜底：拿简介本身当 `description`，
//   其余留空。空的世界节在提示词里整节不出现，比一段编出来的假设定好。
// - 原文（角色简介）不落库、不进备份：落库的只有补全后的 `WorldPreset`。
// ---------------------------------------------------------------------------

/// 一次世界补全的结果。
public struct WorldPresetExpansion: Equatable, Sendable {
    public var preset: WorldPreset
    /// 是否真的问过模型。
    public var usedModel: Bool
    /// 排查用的一句话（不含原文）。
    public var message: String

    public init(preset: WorldPreset, usedModel: Bool, message: String = "") {
        self.preset = preset
        self.usedModel = usedModel
        self.message = message
    }

    public var isEmpty: Bool { preset.isEmpty }
}

public final class WorldPresetExpander {

    /// 规范 §6.2 的提示词，**逐字**照抄。
    public static let systemPrompt = """
    你是角色设定助手。用户给出一个角色的简要设定，你要补全一个“世界预设”（角色所处的现实/虚构世界），
    只输出 JSON 对象，不要解释：
    {"worldName":"世界/城市一句话名","era":"时代与地点","description":"100-200字世界与角色处境概述",
     "schedule":[{"day":"工作日|周末|每天","start":"HH:mm","end":"HH:mm","activity":"在做什么"}],
     "relationships":[{"name":"称呼","relation":"关系","note":"一句细节"}],
     "obligations":["3-6 条角色必须承担的事/压力"]}
    规则：
    - 具体、可执行，不要空话；schedule 覆盖角色主要时间与占用；
    - 不要写剧情大纲，不要写用户；
    - 与角色设定不矛盾；不确定的地方留空数组。
    """

    /// 设定补全是「整理事实」：温度略低于默认，仍然允许一点措辞空间。
    public static let temperature = 0.4
    public static let maxOutputTokens = 1600
    /// 输入简介的字数上限。
    public static let maxSummaryChars = 1_500

    private let completion: CompletionClient
    private let log: (String) -> Void

    public init(completion: CompletionClient, log: @escaping (String) -> Void = { _ in }) {
        self.completion = completion
        self.log = log
    }

    // MARK: - 入口

    /// 把一句角色简介补全成世界预设。
    ///
    /// - Parameters:
    ///   - personaSummary: 用户写的角色简介（只在内存里用一次，不落库）。
    ///   - config: 本机 API 配置；nil 或没有 Key 时走本地兜底。
    public func expand(
        personaSummary: String,
        config: CompletionConfig?,
        model: String = ""
    ) async -> WorldPresetExpansion {
        let trimmed = String(personaSummary.prefix(Self.maxSummaryChars))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return WorldPresetExpansion(
                preset: WorldPreset(), usedModel: false, message: "没有可补全的设定"
            )
        }
        guard let config,
              let key = config.apiKey?.trimmingCharacters(in: .whitespacesAndNewlines), !key.isEmpty else {
            log("[world] 没有可用的 API 配置，走本地兜底")
            return WorldPresetExpansion(
                preset: Self.fallback(trimmed), usedModel: false, message: "没有可用的 API 配置"
            )
        }

        let messages = [
            CompletionMessage.system(Self.systemPrompt),
            CompletionMessage.user(trimmed)
        ]
        let raw: String
        do {
            let result = try await completion.complete(
                config: config,
                model: model.isEmpty ? config.model : model,
                messages: messages,
                params: CompletionParams(temperature: Self.temperature, maxOutputTokens: Self.maxOutputTokens),
                kind: .extraction,
                context: .none
            )
            raw = result.text
        } catch {
            log("[world] 补全请求失败，走本地兜底：\(SpeechStyleSummarizer.safeErrorText(error))")
            return WorldPresetExpansion(
                preset: Self.fallback(trimmed), usedModel: false, message: "补全请求失败"
            )
        }

        guard let preset = WorldPreset.parse(raw), !preset.isEmpty else {
            log("[world] 模型输出读不懂（\(raw.count) 字），走本地兜底")
            return WorldPresetExpansion(
                preset: Self.fallback(trimmed), usedModel: false, message: "模型输出读不懂"
            )
        }
        log("[world] 补全成功（日程 \(preset.schedule.count) 条）")
        return WorldPresetExpansion(preset: preset, usedModel: true)
    }

    // MARK: - 本地兜底

    /// 没有模型可用时的最小世界：把简介本身放进 `description`，其余留空。
    ///
    /// 为什么不编一份日程：编出来的「09:00 上班」会直接进提示词，模型会当真话讲出来；
    /// 而用户手填一次比看到一段假设定要省事得多。
    public static func fallback(_ personaSummary: String) -> WorldPreset {
        let trimmed = personaSummary.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return WorldPreset() }
        return WorldPreset(description: trimmed)
    }
}

// MARK: - App 层默认装配

public extension WorldPresetExpander {
    /// 给编辑页用的默认装配（理由同 `SpeechStyleSummarizer.makeDefault`）。
    static func makeDefault(log: @escaping (String) -> Void = { _ in }) -> WorldPresetExpander {
        WorldPresetExpander(
            completion: CompletionClientAdapter(client: OpenAICompatibleClient()),
            log: log
        )
    }
}
