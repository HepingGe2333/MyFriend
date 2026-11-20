import Foundation

// 来源：app/src/main/java/com/yap/app/data/repo/MoodRepository.kt
//
// 心情的存取。只做三件事：读出来（顺手让时间把它冲淡一点）、写进去、拼成提示词的那一段。
//
// 为什么单独一个文件：心跳要拿心情算冲动概率，自主循环要拿心情算「今天话多话少」，
// 聊天提示词要一句语气分寸。三处都要读同一份状态，不能让任何一处自己拼 persona 的四个 mood 列。
//
// 与 Android 的差别：没有 Flow（iOS 的服务层是拉取式的，界面层自己按需重读），
// 其余判定与「顺手落库」的行为保持一致。

public final class MoodRepository {

    private let stores: DatabaseStores
    private let now: () -> Int64

    public init(stores: DatabaseStores, now: @escaping () -> Int64 = { DomainClock.nowMillis }) {
        self.stores = stores
        self.now = now
    }

    /// 当前心情；如果时间把它冲淡了，顺手落库，免得界面与提示词对不上。
    public func current(personaId: String, now: Int64? = nil) -> MoodState {
        let at = now ?? self.now()
        guard let persona = try? stores.personas.byId(personaId) else {
            return MoodState(label: MoodLexicon.BASELINE, intensity: 1)
        }
        let stored = stateOf(persona)
        let decayed = MoodDrift.decay(stored, now: at)
        if decayed != stored {
            persistDecayed(personaId: personaId, state: decayed)
        }
        return decayed
    }

    /// 记下心情。label 会被规整（去空白、限长）；空标签理解为「回到平静」。
    /// 返回落库后的状态，人设不存在时返回 nil。
    ///
    /// 这是**模型主动说**、或者「被叫醒」这类事件写心情的入口：它只拿到一个词 + 强度，
    /// 所以三维按词表推（`MoodDimensions.fromLegacy`）。自动判定走 `applyJudgement`，
    /// 那条路径的三维是模型看过对话后给的，不能被这里的推断覆盖。
    public func setMood(
        personaId: String,
        label: String,
        intensity: Int? = nil,
        reason: String? = nil,
        valence: Int? = nil,
        arousal: Int? = nil,
        dominance: Int? = nil,
        now: Int64? = nil
    ) -> MoodState? {
        let at = now ?? self.now()
        guard var persona = try? stores.personas.byId(personaId) else { return nil }
        let previous = stateOf(persona)
        let word = MoodLexicon.normalize(label)
        let strength = min(max(intensity ?? max(previous.intensity, 2), 1), 5)
        let cleanReason = String((reason ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
        // 0.15：模型可以连三维一起给（「有点委屈，愉快度 -2」比只给一个词准）；
        // 给了几维就信几维，缺的维度按词表推。**不限制同一轮再改**：以最后一次为准。
        let legacy = MoodDimensions.fromLegacy(label: word, intensity: strength)
        let dimensions = MoodDimensions(
            valence: valence.map(MoodDimensions.clampValence) ?? legacy.valence,
            arousal: arousal.map(MoodDimensions.clampArousal) ?? legacy.arousal,
            dominance: dominance.map(MoodDimensions.clampDominance) ?? legacy.dominance
        )
        let next = MoodState(
            label: word, intensity: strength, reason: cleanReason,
            updatedAt: at, dimensions: dimensions
        )
        persona.mood = next.label
        persona.moodIntensity = next.intensity
        persona.moodReason = next.reason.isEmpty ? nil : next.reason
        persona.moodUpdatedAt = at
        persona.moodValence = dimensions.valence
        persona.moodArousal = dimensions.arousal
        persona.moodDominance = dimensions.dominance
        _ = try? stores.personas.upsert(persona)
        return next
    }

    /// 自动情绪判定的落库入口（`spec/mood/README.md` §2）。
    ///
    /// 判定结果已经过 `MoodJudgementRules` 校验（范围、单次幅度 ≤2、有没有明显变化），
    /// 这里只负责写三列 + label/intensity/reason/时间。
    @discardableResult
    public func applyJudgement(
        personaId: String,
        judgement: MoodJudgement,
        previous: MoodState,
        now: Int64? = nil
    ) -> MoodState? {
        let at = now ?? self.now()
        guard var persona = try? stores.personas.byId(personaId) else { return nil }
        var next = MoodJudgementRules.merged(judgement, previous: previous)
        next.updatedAt = at
        persona.mood = next.label
        persona.moodIntensity = next.intensity
        persona.moodReason = next.reason.isEmpty ? nil : next.reason
        persona.moodUpdatedAt = at
        if let dimensions = next.dimensions {
            persona.moodValence = dimensions.valence
            persona.moodArousal = dimensions.arousal
            persona.moodDominance = dimensions.dominance
        }
        _ = try? stores.personas.upsert(persona)
        return next
    }

    /// 服务端判定结果的落库入口（0.13 云端模式）。
    ///
    /// 与 `applyJudgement` 的区别：那边是**本地模型**的输出，要过 `MoodJudgementRules`
    /// 的校验（范围、单次幅度 ≤2、有没有明显变化）；这边是**服务端已经判定完的结论**，
    /// 服务端是权威，客户端只负责把它缓存在本机（界面、离线提示词、心跳的情绪放大都读本地）。
    /// 所以这里只做「夹取到合法范围 + 照抄」，不重算、不再判一次。
    ///
    /// - Returns: 落库后的状态；人格已经不在本机时 nil。
    @discardableResult
    public func applyServerMood(
        personaId: String,
        label: String,
        intensity: Int,
        dimensions: MoodDimensions?,
        reason: String?,
        now: Int64? = nil
    ) -> MoodState? {
        let at = now ?? self.now()
        guard var persona = (try? stores.personas.byId(personaId)) ?? nil else { return nil }
        let word = MoodLexicon.normalize(label)
        guard !word.isEmpty else { return nil }
        let strength = min(max(intensity, 1), 5)
        let cleanReason = String(
            (reason ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(60)
        )
        // 三维缺省时按词表推（老服务端不懂三维）：本地缓存至少不会因为「少两列」而报错。
        let resolved = dimensions ?? MoodDimensions.fromLegacy(label: word, intensity: strength)
        let next = MoodState(
            label: word, intensity: strength, reason: cleanReason,
            updatedAt: at, dimensions: resolved
        )
        persona.mood = next.label
        persona.moodIntensity = next.intensity
        persona.moodReason = next.reason.isEmpty ? nil : next.reason
        persona.moodUpdatedAt = at
        persona.moodValence = resolved.valence
        persona.moodArousal = resolved.arousal
        persona.moodDominance = resolved.dominance
        _ = try? stores.personas.upsert(persona)
        return next
    }

    /// 心理活动带来的**心情微调**（0.14，`spec/experience/README.md` §3：每维 ≤1）。
    ///
    /// 与 `setMood` / `applyJudgement` 的分工：
    /// - `setMood` 是「模型主动说出一个词」，三维按词表重推；
    /// - `applyJudgement` 是「看过对话后的完整判定」，一次最多动 2；
    /// - 这里是「他心里嘀咕了一句」带来的细微漂移：**只动三维、每维最多 1**，
    ///   标签与强度尽量保持不变（嘀咕一句不该让「累」突然变成「雀跃」）。
    ///
    /// 三列缺失（老数据还没判过）时先按词表推一份出来，再在它上面微调：
    /// 否则「微调」会把老用户的心情悄悄变成基准。
    @discardableResult
    public func applyMicroAdjustment(
        personaId: String,
        valenceDelta: Int,
        arousalDelta: Int,
        dominanceDelta: Int,
        now: Int64? = nil
    ) -> MoodState? {
        let at = now ?? self.now()
        guard var persona = (try? stores.personas.byId(personaId)) ?? nil else { return nil }
        let current = stateOf(persona)
        let base = current.dimensions
            ?? MoodDimensions.fromLegacy(label: current.label, intensity: current.intensity)
        let draft = MentalActivityDraft(
            thought: "微调",
            valenceDelta: valenceDelta,
            arousalDelta: arousalDelta,
            dominanceDelta: dominanceDelta
        )
        guard draft.hasAnyMoodDelta else { return current }
        let adjusted = MentalActivityPolicy.adjusted(base, by: draft)
        var next = current
        next.dimensions = adjusted
        // 标签与强度**保持不变**（除非原来是空的）：与 Android `adjustDimensions` 一致 ——
        // 心里嘀咕一句不该把他对外说的那个心情词（「有点累」）换成另一个。
        if next.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            next.label = MoodLexicon.BASELINE
        }
        next.updatedAt = at
        persona.mood = next.label.isEmpty ? MoodLexicon.BASELINE : next.label
        persona.moodIntensity = next.intensity
        persona.moodUpdatedAt = at
        persona.moodValence = adjusted.valence
        persona.moodArousal = adjusted.arousal
        persona.moodDominance = adjusted.dominance
        _ = try? stores.personas.upsert(persona)
        return next
    }

    /// 判定失败 / 没有可用的 API 配置时，把心情按时间往基准挪一步并落库（规范 §2）。
    ///
    /// 不抛错、不打扰用户：调用方只写一行日志。返回 (当前心情, 这次有没有真的变过)。
    @discardableResult
    public func decayOnFailure(personaId: String, now: Int64? = nil) -> (state: MoodState, changed: Bool) {
        let at = now ?? self.now()
        guard let persona = (try? stores.personas.byId(personaId)) ?? nil else {
            return (MoodState(label: MoodLexicon.BASELINE, intensity: 1), false)
        }
        let stored = stateOf(persona)
        let decayed = MoodJudgementRules.decayedOnFailure(stored, now: at)
        if decayed != stored {
            persistDecayed(personaId: personaId, state: decayed)
        }
        return (decayed, decayed != stored)
    }

    /// 拼进系统提示词的那一段；没有心情时也给一句基准描述。
    public func promptLine(personaId: String, now: Int64? = nil) -> String {
        MoodPrompt.line(current(personaId: personaId, now: now))
    }

    public func stateForAgent(personaId: String, now: Int64? = nil) -> MoodState {
        current(personaId: personaId, now: now)
    }

    /// 该引导心情了吗？该问时返回引导词（调用方拼进消息里），否则 nil。
    ///
    /// 与 Android 一样：先把「已经问过」记下来，免得同一句话下一轮又被追问。
    public func reflectionNudge(personaId: String, now: Int64? = nil, threshold: Int = 3) -> String? {
        let at = now ?? self.now()
        guard var persona = try? stores.personas.byId(personaId) else { return nil }
        let state = current(personaId: personaId, now: at)
        let lastTouch = max(state.updatedAt, persona.moodNudgedAt ?? 0)
        let spoken = spokenSinceTouch(personaId: personaId, since: lastTouch)
        if !MoodDrift.shouldReflect(state, spokenSinceUpdate: spoken, now: at,
                                    threshold: threshold, lastTouchAt: lastTouch) {
            return nil
        }
        persona.moodNudgedAt = at
        _ = try? stores.personas.upsert(persona)
        return MoodPrompt.reflectionNudge(state, spokenSinceUpdate: spoken)
    }

    // MARK: - 内部

    private func stateOf(_ persona: PersonaEntity) -> MoodState {
        let dimensions = Self.dimensionsOf(persona)
        let label = persona.mood.map { MoodLexicon.normalize($0) } ?? ""
        if label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return MoodState(
                label: MoodLexicon.BASELINE, intensity: 1,
                updatedAt: persona.moodUpdatedAt ?? 0,
                dimensions: dimensions
            )
        }
        return MoodState(
            label: label,
            intensity: min(max(persona.moodIntensity ?? 2, 1), 5),
            reason: persona.moodReason ?? "",
            updatedAt: persona.moodUpdatedAt ?? 0,
            dimensions: dimensions
        )
    }

    /// 三列要么都有值（判定过），要么都没有（没判过）。
    ///
    /// 只写了一半的坏数据（例如只有 valence）按「没判过」处理会更安全：
    /// 混着用会让 arousal 悄悄退回基准，而用户看到的心情标签又是另一个。
    static func dimensionsOf(_ persona: PersonaEntity) -> MoodDimensions? {
        guard let valence = persona.moodValence,
              let arousal = persona.moodArousal,
              let dominance = persona.moodDominance else { return nil }
        return MoodDimensions(valence: valence, arousal: arousal, dominance: dominance)
    }

    /// Android 的 `personaDao.fadeMood`：只改心情这几列，不动别的列。
    private func persistDecayed(personaId: String, state: MoodState) {
        guard var persona = try? stores.personas.byId(personaId) else { return }
        persona.mood = state.label
        persona.moodIntensity = state.intensity
        if let dimensions = state.dimensions {
            persona.moodValence = dimensions.valence
            persona.moodArousal = dimensions.arousal
            persona.moodDominance = dimensions.dominance
        }
        _ = try? stores.personas.upsert(persona)
    }

    /// 从上次动过心情（或上次被问过）到现在，他自己说了几句（跨会话）。
    private func spokenSinceTouch(personaId: String, since: Int64) -> Int {
        let sql = "SELECT COUNT(*) FROM messages WHERE personaId = ? AND role = 'ASSISTANT' AND createdAt >= ?"
        return Int((try? stores.database.scalarInt64(sql, [.text(personaId), .int(max(since, 0))])) ?? 0)
    }
}
