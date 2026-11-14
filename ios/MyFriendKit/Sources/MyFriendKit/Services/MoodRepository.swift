import Foundation

// 来源：app/src/main/java/com/myfriend/app/data/repo/MoodRepository.kt
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
    public func setMood(
        personaId: String,
        label: String,
        intensity: Int? = nil,
        reason: String? = nil,
        now: Int64? = nil
    ) -> MoodState? {
        let at = now ?? self.now()
        guard var persona = try? stores.personas.byId(personaId) else { return nil }
        let previous = stateOf(persona)
        let word = MoodLexicon.normalize(label)
        let strength = min(max(intensity ?? max(previous.intensity, 2), 1), 5)
        let cleanReason = String((reason ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
        let next = MoodState(label: word, intensity: strength, reason: cleanReason, updatedAt: at)
        persona.mood = next.label
        persona.moodIntensity = next.intensity
        persona.moodReason = next.reason.isEmpty ? nil : next.reason
        persona.moodUpdatedAt = at
        _ = try? stores.personas.upsert(persona)
        return next
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
        let label = persona.mood.map { MoodLexicon.normalize($0) } ?? ""
        if label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return MoodState(label: MoodLexicon.BASELINE, intensity: 1, updatedAt: persona.moodUpdatedAt ?? 0)
        }
        return MoodState(
            label: label,
            intensity: min(max(persona.moodIntensity ?? 2, 1), 5),
            reason: persona.moodReason ?? "",
            updatedAt: persona.moodUpdatedAt ?? 0
        )
    }

    /// Android 的 `personaDao.fadeMood`：只改心情的两个字段，不动别的列。
    private func persistDecayed(personaId: String, state: MoodState) {
        guard var persona = try? stores.personas.byId(personaId) else { return }
        persona.mood = state.label
        persona.moodIntensity = state.intensity
        _ = try? stores.personas.upsert(persona)
    }

    /// 从上次动过心情（或上次被问过）到现在，他自己说了几句（跨会话）。
    private func spokenSinceTouch(personaId: String, since: Int64) -> Int {
        let sql = "SELECT COUNT(*) FROM messages WHERE personaId = ? AND role = 'ASSISTANT' AND createdAt >= ?"
        return Int((try? stores.database.scalarInt64(sql, [.text(personaId), .int(max(since, 0))])) ?? 0)
    }
}
