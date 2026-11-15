import Foundation

// ---------------------------------------------------------------------------
// 人设卡仓储（iOS 端移植）。
//
// 来源：app/src/main/java/com/myfriend/app/data/repo/PersonaCardRepository.kt。
//
// 设计取舍（与 Android 一致）：
// - 卡是给人看、给模型看、能给 AI 自己改的结构；prompt 是它的渲染镜像（mirror），
//   用来兼容旧流程（备份、导出、旧提示词编辑器）。写卡就一定重写 prompt，两者不会互相漂移。
// - 「空卡」= 还没填结构化设定，读的时候从旧提示词临时拆一份（不落库）。
// - 版本历史：Android 有 `persona_revisions` 表，iOS 没有（也不建）。这里只维护
//   `persona.version`：渲染结果真的变了才 +1，不假装有版本表。
// - 补丁直接生效：1.3 起 Android 也不再等用户确认，所以 iOS 不引入「待确认补丁」。
//
// 与 Android 的差异：
// - `setQuietHours` 与 Android 一样只写 `personaCardJson` 与两个作息列，**不重写 prompt**
//   （Android 把它当作「不是人格变化」，不产生新版本）；
// - `setDndOnSleep` 同样只写 `dndOnSleep` 一列（Android 的 DAO 也只改这一列）；
// - 没有 `ensureCard` / pending patch / revision 相关方法（iOS 没有对应表与流程）。
// ---------------------------------------------------------------------------

/// 人设卡仓储的错误。文案是给界面直接显示的中文（`localizedDescription` 可用）。
public enum PersonaCardRepositoryError: Error, LocalizedError {
    case personaNotFound

    public var errorDescription: String? {
        switch self {
        case .personaNotFound: return String(localized: "人格不存在")
        }
    }
}

public final class PersonaCardRepository {

    /// 镜像 prompt 的字数上限（Android: `MIRROR_MAX = 8000`）。
    private static let mirrorMax = 8000

    private let stores: DatabaseStores
    private let now: () -> Int64

    public init(
        stores: DatabaseStores,
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.stores = stores
        self.now = now
    }

    // MARK: - 读

    /// 读出一张卡：`personaCardJson` 优先，没有就从旧的一整段提示词里尽力拆一份（不写库）。
    public func readCard(_ persona: PersonaEntity) -> PersonaCard {
        if let json = persona.personaCardJson, !json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return PersonaCardCodec.decode(json)
        }
        return PersonaCardPrompt.fromLegacyPrompt(persona.prompt)
    }

    /// 按 id 读卡；人格不存在时返回空卡（Android: `cardOf`）。
    public func card(personaId: String) -> PersonaCard {
        guard let persona = try? stores.personas.byId(personaId) else { return PersonaCard() }
        return readCard(persona)
    }

    /// 聊天/主动消息要用的系统人格文本。
    ///
    /// 有卡时用「稳定策略 + 按预算渲染的卡」，空卡且提示词非空时退回旧的一段提示词；
    /// 最后追加 1.9 的五个结构化小节（`PromptBuilder.personaProfileBody`，不带「## 你是谁」标题）。
    ///
    /// 注意：`personaProfileBody` 自己会把 `persona.prompt` 放在最前面（它本来是给
    /// 「整段人格文本」用的）。而这里的 `base` 已经包含那段文字（空卡时是原文，有卡时是镜像），
    /// 所以追加前先把重复的开头剥掉——否则同一段设定会在 system 里出现两遍。
    /// Android 的 `PersonaProfile.promptBlock` 不含 prompt，行为与这里剥完的结果一致。
    public func systemText(_ persona: PersonaEntity, budget: PromptBudget = .normal) -> String {
        let card = readCard(persona)
        let prompt = persona.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let base: String
        if card.isEmpty && !prompt.isEmpty {
            base = persona.prompt
        } else {
            base = (PersonaCardPrompt.stableStrategy(card) + "\n\n"
                + PersonaCardPrompt.renderContext(card, budget: budget))
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }

        var profile = PromptBuilder.personaProfileBody(persona)
        if !prompt.isEmpty, profile.hasPrefix(prompt) {
            profile = String(profile.dropFirst(prompt.count))
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return profile.isEmpty ? base : base + "\n\n" + profile
    }

    // MARK: - 写

    /// 保存整张卡（用户在卡编辑器里改，或 AI 的补丁）。
    ///
    /// prompt 会按卡重新渲染成镜像；版本号只在渲染结果真的变了时才 +1。
    /// 空卡渲染出来是空串（见 `mirrorOf`），所以调用方**不要**用空卡覆盖用户手写的提示词——
    /// 编辑页只在「卡非空且真的动过」时才调这里。
    @discardableResult
    public func saveCard(personaId: String, card: PersonaCard, reason: String) -> Result<Void, Error> {
        Result {
            guard var persona = try stores.personas.byId(personaId) else {
                throw PersonaCardRepositoryError.personaNotFound
            }
            let mirrored = String(mirrorOf(card).prefix(Self.mirrorMax))
            let changed = mirrored != persona.prompt
            persona.personaCardJson = PersonaCardCodec.encode(card)
            persona.prompt = mirrored
            if changed {
                persona.version = max(persona.version, 1) + 1
            }
            persona.updatedAt = now()
            try stores.personas.upsert(persona)
            // reason 只用于 Android 的版本历史备注；iOS 没有 revisions 表，参数保留给调用方语义一致。
            _ = reason
        }
    }

    /// AI 的补丁（`update_persona` 工具用）：apply → 和原卡比。
    ///
    /// - 没变化：`.success(false)`（「本来就是这样」，不算失败）；
    /// - 有变化：落库并 `.success(true)`。
    public func applyPatch(
        personaId: String,
        patchJson: String,
        reason: String
    ) -> Result<Bool, Error> {
        do {
            guard let persona = try stores.personas.byId(personaId) else {
                throw PersonaCardRepositoryError.personaNotFound
            }
            let before = readCard(persona)
            let after = PersonaCardPatch.apply(before, patchJson: patchJson)
            guard after != before else { return .success(false) }
            let note = reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "自我调整"
                : "自我调整：\(reason)"
            try saveCard(personaId: personaId, card: after, reason: note).get()
            return .success(true)
        } catch {
            return .failure(error)
        }
    }

    /// 他自己安排作息：把一句话写进卡的 `life.schedule`，同时落两个作息列。
    ///
    /// 只动作息，不产生新版本（不是人格变化），也不重写 prompt——与 Android 一致。
    public func setQuietHours(
        personaId: String,
        sleepMinutes: Int,
        wakeMinutes: Int,
        reason: String
    ) -> Result<Void, Error> {
        Result {
            guard var persona = try stores.personas.byId(personaId) else {
                throw PersonaCardRepositoryError.personaNotFound
            }
            let note = reason.trimmingCharacters(in: .whitespacesAndNewlines)
            var schedule = "每天 \(Self.formatMinutes(sleepMinutes)) 睡觉，"
                + "\(Self.formatMinutes(wakeMinutes)) 醒来"
            if !note.isEmpty { schedule += "（\(note)）" }

            var card = readCard(persona)
            card.life.schedule = schedule
            persona.personaCardJson = PersonaCardCodec.encode(card)
            persona.quietSleepMinutes = sleepMinutes
            persona.quietWakeMinutes = wakeMinutes
            persona.updatedAt = now()
            try stores.personas.upsert(persona)
        }
    }

    /// 睡前免打扰：只由模型的动作写（界面上没有开关）。只改 `dndOnSleep` 一列。
    public func setDndOnSleep(personaId: String, enabled: Bool) -> Result<Void, Error> {
        Result {
            guard var persona = try stores.personas.byId(personaId) else {
                throw PersonaCardRepositoryError.personaNotFound
            }
            persona.dndOnSleep = enabled
            persona.updatedAt = now()
            try stores.personas.upsert(persona)
        }
    }

    /// 当前活动（`set_activity` 工具）：只写 `activityJson` 一列。
    ///
    /// 为什么它不属于人设卡：卡是「他是谁」（长期、可版本化），活动是「他此刻在做什么」
    /// （十几分钟到几小时就过期）。把它塞进卡会让每次 `set_activity` 都变成一次人设变更，
    /// 版本号乱涨、镜像 prompt 反复重写。所以它单独一列、不产生新版本、不重写 prompt。
    @discardableResult
    public func setActivity(personaId: String, activity: PersonaActivity?) -> Result<Void, Error> {
        Result {
            guard var persona = try stores.personas.byId(personaId) else {
                throw PersonaCardRepositoryError.personaNotFound
            }
            persona.activityJson = activity?.jsonString()
            persona.updatedAt = now()
            try stores.personas.upsert(persona)
        }
    }

    /// 世界预设 / 话风（预设编辑器与 AI 补全的落库入口）。
    ///
    /// 传 nil 就是清空（用户点「清空」时同一句话），不是「保持原样」——
    /// 想保持原样就别调它。同样不产生新版本：这两样是「环境与说话方式」，不是人格本身。
    @discardableResult
    public func saveGuidance(
        personaId: String,
        worldJson: String?,
        speechStyleJson: String?
    ) -> Result<Void, Error> {
        Result {
            guard var persona = try stores.personas.byId(personaId) else {
                throw PersonaCardRepositoryError.personaNotFound
            }
            persona.worldJson = PromptGuidanceJSON.clean(worldJson)
            persona.speechStyleJson = PromptGuidanceJSON.clean(speechStyleJson)
            persona.updatedAt = now()
            try stores.personas.upsert(persona)
        }
    }

    /// 给 App 装配用：编辑页把结构化字段拆开传进来，内部组装成 `PersonaCard` 再走 `saveCard`。
    ///
    /// 返回 `true` 表示真的写了（卡有变化），`false` 表示和库里一样、什么都没动。
    ///
    /// 与 `saveCard` 的唯一差别：**卡是空的时候不覆盖 `prompt`**。
    /// 「空卡」在编辑页里的含义是「我还是想用手写的那段提示词」，这时只把空的卡写回去，
    /// 不拿空镜像抹掉用户写的一大段设定（需求里明确要求的保护）。
    @discardableResult
    public func saveCardFields(
        personaId: String,
        identity: PersonaIdentity,
        voice: PersonaVoice,
        traits: [PersonaTrait],
        values: [String],
        boundaries: [String],
        life: PersonaLife,
        bond: String,
        reason: String = "编辑人设卡"
    ) -> Result<Bool, Error> {
        let card = PersonaCard(
            identity: identity,
            voice: voice,
            traits: traits,
            values: values,
            boundaries: boundaries,
            life: life,
            bond: bond
        ).sanitized()

        do {
            guard let persona = try stores.personas.byId(personaId) else {
                throw PersonaCardRepositoryError.personaNotFound
            }
            guard card != readCard(persona) else { return .success(false) }

            if card.isEmpty {
                // 空卡：只落卡 JSON，prompt 保持用户手写的那一段。
                var updated = persona
                updated.personaCardJson = PersonaCardCodec.encode(card)
                updated.updatedAt = now()
                try stores.personas.upsert(updated)
                return .success(true)
            }
            try saveCard(personaId: personaId, card: card, reason: reason).get()
            return .success(true)
        } catch {
            return .failure(error)
        }
    }

    // MARK: - 内部

    /// prompt 镜像：卡渲染出来的纯文本（Android: `mirrorOf`）。
    /// 空卡渲染成空串——这是「还没填卡」的语义，不能被当作新提示词写库。
    private func mirrorOf(_ card: PersonaCard) -> String {
        if card.isEmpty { return "" }
        return (PersonaCardPrompt.stableStrategy(card) + "\n\n"
            + PersonaCardPrompt.renderContext(card, budget: .rich))
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// `23:30` 这类时钟文本；与 Android 的 `formatMinutes` 一样对负数和超过一天的值取模。
    private static func formatMinutes(_ minutes: Int) -> String {
        let wrapped = ((minutes % 1440) + 1440) % 1440
        return String(format: "%02d:%02d", wrapped / 60, wrapped % 60)
    }
}
