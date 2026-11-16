import Foundation

// ---------------------------------------------------------------------------
// 翻译缓存的读写与去重（`spec/persona-translation/README.md` §2 / §4 / §7）。
//
// 触发时机（§2）：进入会话、生成主动消息、导出提示词预览之前。**不在 App 启动时批量翻译**。
// 这一层负责三件事：
// 1. **缓存命中零请求**：`personas.translationJson` 的 lang 与 sourceHash 都对得上就直接用；
// 2. **同一进程内去重**：同一个 `personaId + lang + sourceHash` 同时只跑一次补全；
// 3. **失败不阻塞**：没有 Key / 超时 / 坏 JSON → 用原文，记一次失败状态，下次再试。
//
// 为什么不放在 App 层：聊天与主动消息都要用（Kit 里的 `ChatService` / `AgentLoop`），
// 而 App 层只是装配点；放 Kit 里才能被单测直接驱动（假模型、假设置、真临时库）。
// ---------------------------------------------------------------------------

/// 一次「确保有译文」的结果。界面按它决定要不要显示「翻译失败，可重试」。
public enum PersonaTranslationOutcome: Equatable, Sendable {
    /// 这次真的翻译了（并已写入缓存）。
    case translated(PersonaTranslation)
    /// 缓存命中（零请求）。
    case cached(PersonaTranslation)
    /// 源语言与目标语言相同（零请求）。
    case skippedSameLanguage
    /// 设置里关掉了「自动翻译人设」。
    case disabled
    /// 没有可用的 API 配置（离线）：用原文，不提示。
    case noConfig
    /// 调用失败 / 超时 / 输出读不懂：用原文，可重试。
    case failed(String)

    public var translation: PersonaTranslation? {
        switch self {
        case .translated(let value), .cached(let value): return value
        default: return nil
        }
    }

    public var isUsable: Bool { translation != nil }
}

/// 聊天 / 主动消息 / 编辑页用到的翻译能力（测试可以塞一个空实现）。
public protocol PersonaTranslationProviding: AnyObject {

    /// 缓存命中就返回译文，否则 nil（同步、零网络：提示词拼装走这条）。
    func cachedTranslation(for persona: PersonaEntity, lang: String) -> PersonaTranslation?

    /// 把译文叠到人格上（内存副本；缓存无效时原样返回）。
    func appliedPersona(_ persona: PersonaEntity, lang: String) -> PersonaEntity

    /// 确保有译文：缓存命中零请求，否则最多发一次（同进程按 key 去重）。
    func ensureTranslation(
        personaId: String,
        lang: String,
        config: CompletionConfig?,
        model: String
    ) async -> PersonaTranslationOutcome

    /// 不阻塞地发起一次「确保有译文」（进聊天 / 生成主动消息之前调）。
    func kickTranslation(
        personaId: String,
        lang: String,
        config: CompletionConfig?,
        model: String
    )

    /// 手动「重新翻译」：先清缓存，再调 `ensureTranslation`。
    func clearTranslation(personaId: String) throws
}

// MARK: - 空实现

/// 不做任何翻译（测试 / 没有 API 配置的装配用）。
public final class NoPersonaTranslation: PersonaTranslationProviding {
    public init() {}
    public func cachedTranslation(for persona: PersonaEntity, lang: String) -> PersonaTranslation? { nil }
    public func appliedPersona(_ persona: PersonaEntity, lang: String) -> PersonaEntity { persona }
    public func ensureTranslation(
        personaId: String, lang: String, config: CompletionConfig?, model: String
    ) async -> PersonaTranslationOutcome { .disabled }
    public func kickTranslation(
        personaId: String, lang: String, config: CompletionConfig?, model: String
    ) {}
    public func clearTranslation(personaId: String) throws {}
}

// MARK: - 真实现

public final class PersonaTranslationService: PersonaTranslationProviding, @unchecked Sendable {

    private let stores: DatabaseStores
    private let settings: SettingsProviding
    private let translator: PersonaTranslator
    private let now: () -> Int64
    private let log: (String) -> Void

    private let lock = NSLock()
    /// 同进程去重：key = `personaId|lang|sourceHash` → 正在跑的那一次。
    private var inFlight: [String: Task<PersonaTranslationOutcome, Never>] = [:]
    /// 进程内共享实例（见 `shared(stores:settings:log:)`）。
    private static let sharedLock = NSLock()
    private static var sharedInstance: PersonaTranslationService?

    public init(
        stores: DatabaseStores,
        settings: SettingsProviding,
        translator: PersonaTranslator,
        log: @escaping (String) -> Void = { _ in },
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.stores = stores
        self.settings = settings
        self.translator = translator
        self.log = log
        self.now = now
    }

    // MARK: 缓存

    public func cachedTranslation(for persona: PersonaEntity, lang: String) -> PersonaTranslation? {
        guard let cached = PersonaTranslation.decode(persona.translationJson) else { return nil }
        let source = PersonaTranslatableSource(persona: persona)
        guard cached.isValid(for: source, lang: lang) else { return nil }
        return cached
    }

    public func appliedPersona(_ persona: PersonaEntity, lang: String) -> PersonaEntity {
        guard let cached = cachedTranslation(for: persona, lang: lang) else { return persona }
        return cached.applying(to: persona)
    }

    // MARK: 确保有译文

    public func ensureTranslation(
        personaId: String,
        lang: String,
        config: CompletionConfig?,
        model: String
    ) async -> PersonaTranslationOutcome {
        let target = lang.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty else { return .skippedSameLanguage }
        guard let persona = (try? stores.personas.byId(personaId)) ?? nil else {
            return .failed("人格不存在")
        }
        let source = PersonaTranslatableSource(persona: persona)
        if source.isEmpty { return .skippedSameLanguage }
        // 缓存命中：零网络（§2）。
        if let cached = cachedTranslation(for: persona, lang: target) {
            return .cached(cached)
        }
        let hash = PersonaTranslation.sourceHash(for: source)
        let key = "\(personaId)|\(PersonaTranslationLanguage.normalize(target))|\(hash)"
        // 「查一下有没有在跑」和「占住这个 key」必须在同一把锁里完成：
        // 先查后占的写法会让两个并发调用各建一个 Task，虽然第二个随后被取消，
        // 但模型请求已经发出去了（`slow.count == 2`）。这里用 claim 一次搞定。
        let claimed = claimInFlight(key) {
            Task { [self] in
                await perform(
                    persona: persona, source: source, lang: target, config: config, model: model
                )
            }
        }
        let outcome = await claimed.task.value
        if claimed.created { releaseInFlight(key) }
        return outcome
    }

    // 锁只在同步方法里用：Swift 6 禁止在 async 上下文直接 lock/unlock。
    private func claimInFlight(
        _ key: String,
        _ make: () -> Task<PersonaTranslationOutcome, Never>
    ) -> (task: Task<PersonaTranslationOutcome, Never>, created: Bool) {
        lock.lock()
        defer { lock.unlock() }
        if let running = inFlight[key] { return (running, false) }
        let task = make()
        inFlight[key] = task
        return (task, true)
    }

    private func releaseInFlight(_ key: String) {
        lock.lock()
        inFlight[key] = nil
        lock.unlock()
    }

    public func kickTranslation(
        personaId: String,
        lang: String,
        config: CompletionConfig?,
        model: String
    ) {
        Task { [self] in
            _ = await ensureTranslation(personaId: personaId, lang: lang, config: config, model: model)
        }
    }

    public func clearTranslation(personaId: String) throws {
        _ = try stores.personas.updateTranslation(id: personaId, json: nil, lang: nil)
    }

    // MARK: 一次真正的翻译

    private func perform(
        persona: PersonaEntity,
        source: PersonaTranslatableSource,
        lang: String,
        config: CompletionConfig?,
        model: String
    ) async -> PersonaTranslationOutcome {
        // §6：用户可以在设置里关掉「自动翻译人设」（默认开）。
        guard settings.load().autoTranslatePersona else { return .disabled }
        let detected = PersonaTranslationLanguage.detect(source.joinedText)
        if !detected.isEmpty, PersonaTranslationLanguage.isSame(detected, lang) {
            return .skippedSameLanguage
        }
        guard let config,
              let key = config.apiKey?.trimmingCharacters(in: .whitespacesAndNewlines), !key.isEmpty else {
            return .noConfig
        }
        let attempt = await translator.translate(
            source: source, lang: lang, config: config, model: model, now: now()
        )
        switch attempt.status {
        case .translated:
            let translation = attempt.translation
            // 失败也不覆盖缓存：只有真的拿到译文才写（§7：失败下次再试）。
            do {
                try stores.personas.updateTranslation(
                    id: persona.id,
                    json: translation.jsonString(),
                    lang: PersonaTranslationLanguage.normalize(lang)
                )
            } catch {
                log("[translation] 译文写入失败：\(PersonaTranslator.safeErrorText(error))")
            }
            return .translated(translation)
        case .emptySource, .skippedSameLanguage:
            return .skippedSameLanguage
        case .noConfig:
            return .noConfig
        case .failed:
            log("[translation] 这次没翻成，先用原文（\(attempt.message)）")
            return .failed(attempt.message)
        }
    }
}

// MARK: - App 层默认装配

public extension PersonaTranslationService {

    /// 进程内共享的一份（`spec/persona-translation/README.md` §2：同一进程内只做一次）。
    ///
    /// 为什么需要共享：去重的 key 存在实例里。每次进聊天都 new 一个服务，等于每次都从零开始，
    /// 「同进程按 personaId+lang+sourceHash 去重」就形同虚设。
    /// 装配参数（stores / settings）由第一次调用方给；App 里只有一个 `AppEnvironment`，所以安全。
    static func shared(
        stores: DatabaseStores,
        settings: SettingsProviding,
        log: @escaping (String) -> Void = { _ in }
    ) -> PersonaTranslationService {
        sharedLock.lock()
        defer { sharedLock.unlock() }
        if let instance = sharedInstance { return instance }
        let instance = makeDefault(stores: stores, settings: settings, log: log)
        sharedInstance = instance
        return instance
    }

    /// 测试用：把共享实例清掉（不影响单测里的独立实例）。
    static func resetSharedForTesting() {
        sharedLock.lock()
        sharedInstance = nil
        sharedLock.unlock()
    }

    /// 给聊天 / 编辑页用的默认装配（理由同 `WorldPresetExpander.makeDefault`）。
    static func makeDefault(
        stores: DatabaseStores,
        settings: SettingsProviding,
        log: @escaping (String) -> Void = { _ in }
    ) -> PersonaTranslationService {
        PersonaTranslationService(
            stores: stores,
            settings: settings,
            translator: PersonaTranslator.makeDefault(log: log),
            log: log
        )
    }
}
