import Foundation

// ---------------------------------------------------------------------------
// 人格提示词的翻译：一次用完即弃的补全（`spec/persona-translation/README.md`）。
//
// - 只发人设 / 世界 / 话风（§6 隐私）：**不发**聊天记录、记忆、API Key，也不发 `phrases`；
// - 提示词逐字来自规范 §5；温度 0.2；8s 超时；失败 / 无 Key / 超时一律"用原文"，
//   不抛给用户看；
// - 返回值是**逐字段合并**后的缓存（§7：坏 JSON / 缺字段回退到原文，不是整份丢弃）。
//
// 和 `WorldPresetExpander` / `SpeechStyleSummarizer` 的区别：那两个是"生成 / 总结"，
// 结果要用户确认；这个只是把已有设定换一种语言，用户不用看过程，所以它一次就是最终缓存。
// ---------------------------------------------------------------------------

/// 一次翻译的结果。
public struct PersonaTranslationAttempt: Equatable, Sendable {

    public enum Status: Equatable, Sendable {
        /// 真的问过模型，拿到可用译文（可能有个别字段回退原文）。
        case translated
        /// 源字段为空 / 不值得翻译。
        case emptySource
        /// 源语言与目标语言相同（零请求）。
        case skippedSameLanguage
        /// 没有可用的 API 配置（离线）——用原文，不提示。
        case noConfig
        /// 调用失败 / 超时 / 输出读不懂——用原文，界面可提示"翻译失败，可重试"。
        case failed
    }

    /// 永远可用：失败时就是原文本身。
    public var translation: PersonaTranslation
    public var status: Status
    /// 排查用的一句话（不含任何设定正文）。
    public var message: String

    public init(translation: PersonaTranslation, status: Status, message: String = "") {
        self.translation = translation
        self.status = status
        self.message = message
    }

    public var usedModel: Bool { status == .translated }
}

public final class PersonaTranslator {

    /// 规范 §5 的提示词，**逐字**照抄（`{目标语言}` 是唯一占位）。
    public static let systemPromptTemplate = """
    你是翻译器。把给定的人设/世界设定翻译成{target}，保留语气、称呼与专有名词的意译一致性。
    只输出 JSON，键名原样保留，不要增删键，不要翻译 phrases 数组，不要解释：
    { "persona": {…}, "world": {…}, "speech": {…} }
    规则：
    - 专有名词首次出现可音译，之后保持一致；
    - 不要润色成书面语，保持口语性格；
    - 不确定的短语可保留原文。
    """

    /// 翻译是"改写措辞"而不是"创作"：温度压低（§5）。
    public static let temperature = 0.2
    public static let maxOutputTokens = 2_400
    /// 一次翻译最多带上多少字的设定（再长就截断：提示词预算与成本都要有上限）。
    public static let maxSourceChars = 12_000
    /// §5：失败 / 超时（8s）就用原文。
    public static let timeoutSeconds: Double = 8

    private let completion: CompletionClient
    private let log: (String) -> Void
    /// 这一份实例的超时（默认就是规范值；测试传 0.05 秒，别让一条用例等 8 秒）。
    public let timeout: Double

    public init(
        completion: CompletionClient,
        log: @escaping (String) -> Void = { _ in },
        timeout: Double = PersonaTranslator.timeoutSeconds
    ) {
        self.completion = completion
        self.log = log
        self.timeout = timeout
    }

    // MARK: - 入口

    /// 把一份设定翻译成 `lang`（`zh` / `en` / BCP-47）。
    ///
    /// 返回值永远是"可用的缓存"：模型没给 / 给坏 / 超时的字段用原文。
    public func translate(
        source: PersonaTranslatableSource,
        lang: String,
        config: CompletionConfig?,
        model: String = "",
        now: Int64
    ) async -> PersonaTranslationAttempt {
        let target = lang.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallback = PersonaTranslation(
            lang: target,
            translatedAt: now,
            sourceHash: PersonaTranslation.sourceHash(for: source),
            persona: PersonaTranslation.PersonaFields(
                name: source.name,
                description: source.description,
                traits: source.traits,
                likes: source.likes,
                behaviors: source.behaviors,
                backstory: source.backstory,
                quirks: source.quirks,
                systemPrompt: source.systemPrompt
            ),
            world: source.world,
            speech: source.speech
        )
        guard !source.isEmpty, !target.isEmpty else {
            return PersonaTranslationAttempt(
                translation: fallback, status: .emptySource, message: "没有可翻译的设定"
            )
        }
        // 源语言检测（§5）：CJK 占比 > 0.2 视为中文；与目标相同就跳过（零请求）。
        let detected = PersonaTranslationLanguage.detect(source.joinedText)
        if !detected.isEmpty, PersonaTranslationLanguage.isSame(detected, target) {
            return PersonaTranslationAttempt(
                translation: fallback, status: .skippedSameLanguage, message: "源语言与目标语言相同"
            )
        }
        guard let config,
              let key = config.apiKey?.trimmingCharacters(in: .whitespacesAndNewlines),
              !key.isEmpty else {
            return PersonaTranslationAttempt(
                translation: fallback, status: .noConfig, message: "没有可用的 API 配置"
            )
        }

        let payload = Self.requestPayload(source: source)
        let messages = [
            CompletionMessage.system(Self.systemPrompt(target: target)),
            CompletionMessage.user(payload)
        ]
        let raw: String
        do {
            let result = try await Self.withTimeout(seconds: self.timeout) {
                try await self.completion.complete(
                    config: config,
                    model: model.isEmpty ? config.model : model,
                    messages: messages,
                    params: CompletionParams(
                        temperature: Self.temperature,
                        maxOutputTokens: Self.maxOutputTokens
                    ),
                    kind: .translation,
                    context: .none
                )
            }
            raw = result.text
        } catch {
            // 只记状态与错误摘要：错误信息里可能带请求体（设定正文），所以不落原文。
            log("[translation] 翻译请求失败，用原文：\(Self.safeErrorText(error))")
            return PersonaTranslationAttempt(
                translation: fallback, status: .failed, message: "翻译请求失败或超时"
            )
        }

        guard let object = PromptGuidanceJSON.object(from: raw) else {
            log("[translation] 模型输出读不懂（\(raw.count) 字），用原文")
            return PersonaTranslationAttempt(
                translation: fallback, status: .failed, message: "模型输出读不懂"
            )
        }
        let merged = PersonaTranslation.merge(
            source: source, lang: target, translatedAt: now, object: object
        )
        log("[translation] 翻译完成（\(target)，\(raw.count) 字输出）")
        return PersonaTranslationAttempt(translation: merged, status: .translated)
    }

    // MARK: - 提示词与载荷

    /// 规范 §5 的提示词（把 `{目标语言}` 换成"中文 / English / 原样 tag"）。
    public static func systemPrompt(target: String) -> String {
        let name: String
        switch PersonaTranslationLanguage.normalize(target) {
        case "zh": name = "中文"
        case "en": name = "English"
        default: name = target
        }
        return systemPromptTemplate.replacingOccurrences(of: "{target}", with: name)
    }

    /// 请求体：只含用户自己写的设定（§3 的字段清单）。
    ///
    /// 形状与 §5 的 `{ "persona": {…}, "world": {…}, "speech": {…} }` 一致；
    /// 没有世界 / 话风时不发对应的键（避免模型顺手编一个），`phrases` 永不发。
    public static func requestPayload(source: PersonaTranslatableSource) -> String {
        var persona: [String: Any] = [:]
        func put(_ key: String, _ value: String) {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { persona[key] = trimmed }
        }
        put("name", source.name)
        put("description", source.description)
        if !source.traits.isEmpty { persona["traits"] = source.traits }
        if !source.likes.isEmpty { persona["likes"] = source.likes }
        if !source.behaviors.isEmpty { persona["behaviors"] = source.behaviors }
        put("backstory", source.backstory)
        put("quirks", source.quirks)
        put("systemPrompt", source.systemPrompt)

        var root: [String: Any] = ["persona": persona]
        if let world = source.world, !world.isEmpty {
            var entry: [String: Any] = [
                "worldName": world.worldName,
                "era": world.era,
                "description": world.description,
                "schedule": world.schedule.map { item in
                    ["day": item.day, "start": item.start, "end": item.end, "activity": item.activity]
                },
                "relationships": world.relationships.map { person in
                    ["name": person.name, "relation": person.relation, "note": person.note]
                },
                "obligations": world.obligations
            ]
            if !world.notes.isEmpty { entry["notes"] = world.notes }
            root["world"] = entry
        }
        if let speech = source.speech, !speech.isEmpty {
            var entry: [String: Any] = [:]
            if !speech.tone.isEmpty { entry["tone"] = speech.tone }
            if !speech.habits.isEmpty { entry["habits"] = speech.habits }
            if !speech.avoid.isEmpty { entry["avoid"] = speech.avoid }
            if !entry.isEmpty { root["speech"] = entry }
        }

        guard JSONSerialization.isValidJSONObject(root),
              let data = try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys]),
              var text = String(data: data, encoding: .utf8) else { return "{}" }
        if text.count > maxSourceChars { text = String(text.prefix(maxSourceChars)) }
        return text
    }

    // MARK: - 超时

    /// 给一次补全加"最多等 N 秒"。
    ///
    /// 为什么用 task group 而不是 URLSession 的 timeout：翻译走的是服务层的
    /// `CompletionClient`（测试里是假实现），超时语义必须在这一层统一；group 取消会把
    /// 取消传给真正的 URLSession 请求。
    static func withTimeout<T: Sendable>(
        seconds: Double,
        _ operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(max(seconds, 0.01) * 1_000_000_000))
                throw CompletionFailure("翻译超时")
            }
            guard let result = try await group.next() else {
                throw CompletionFailure("翻译超时")
            }
            group.cancelAll()
            return result
        }
    }

    /// 错误摘要：只留类型与长度，绝不带请求/响应正文（§6 日志脱敏）。
    static func safeErrorText(_ error: Error) -> String {
        if let failure = error as? CompletionFailure { return failure.message }
        let ns = error as NSError
        return "\(ns.domain)#\(ns.code)"
    }
}

// MARK: - App 层默认装配

public extension PersonaTranslator {
    /// 给聊天 / 编辑页用的默认装配（理由同 `WorldPresetExpander.makeDefault`）。
    static func makeDefault(log: @escaping (String) -> Void = { _ in }) -> PersonaTranslator {
        PersonaTranslator(
            completion: CompletionClientAdapter(client: OpenAICompatibleClient()),
            log: log
        )
    }
}
