import Foundation

// 来源：`spec/experience/README.md` §3「心理活动」。
//
// 一条心理活动 = 一句 ≤60 字的私密念头 + 每维 ≤1 的心情微调，写进 `mental_activities`，
// 下一轮提示词里以「你刚才心里想：…」出现。它**不发出去**，用户看不到。
//
// 两层分工：
// - `MentalActivityRepository`：只读写库（最近一条、写入、只留 50 条、提示词那句）；
// - `MentalActivityService`：判定（活跃窗口 / 3 分钟 / 3 轮 / 心情激动必做）→ 生成 → 落库 → 微调心情。
//
// 生成本身走 `MentalActivityGenerating` 协议：单测注入假生成器，就能验证
// 「触发条件、念头截断、心情每维 ≤1、保留 50 条、失败不写」，不必联网。

// MARK: - 仓储

public final class MentalActivityRepository {

    private let stores: DatabaseStores
    private let now: () -> Int64

    public init(stores: DatabaseStores, now: @escaping () -> Int64 = { DomainClock.nowMillis }) {
        self.stores = stores
        self.now = now
    }

    /// 最近一条心理活动（没有就是 nil）。
    public func latest(personaId: String) -> MentalActivityEntity? {
        (try? stores.mentalActivities.recent(personaId: personaId, limit: 1))?.first
    }

    public func recent(personaId: String, limit: Int = MentalActivityPolicy.retention) -> [MentalActivityEntity] {
        (try? stores.mentalActivities.recent(personaId: personaId, limit: limit)) ?? []
    }

    /// 写一条心理活动，并把这个人旧的念头只留最近 50 条（规范 §3）。
    ///
    /// `mood` 传的是**微调之后**的心情：快照要能独立解释「他那时候是什么心情」。
    @discardableResult
    public func record(
        personaId: String,
        sessionId: String,
        thought: String,
        mood: MoodState,
        at: Int64? = nil
    ) -> MentalActivityEntity? {
        guard let clean = MentalActivityPolicy.sanitizeThought(thought) else { return nil }
        let timestamp = at ?? now()
        let entity = MentalActivityEntity(
            id: UUID().uuidString,
            sessionId: sessionId,
            personaId: personaId,
            at: timestamp,
            thought: clean,
            moodLabel: mood.label.isEmpty ? nil : mood.label,
            moodIntensity: mood.intensity,
            moodValence: mood.dimensions?.valence,
            moodArousal: mood.dimensions?.arousal,
            moodDominance: mood.dimensions?.dominance
        )
        guard (try? stores.mentalActivities.upsert(entity)) != nil else { return nil }
        _ = try? stores.mentalActivities.prune(
            personaId: personaId, keeping: MentalActivityPolicy.retention
        )
        return entity
    }

    /// 注入下一轮提示词的那一句；念头不新鲜 / 没有就返回空串。
    public func promptLine(personaId: String, now: Int64? = nil) -> String {
        let at = now ?? self.now()
        guard let latest = latest(personaId: personaId) else { return "" }
        return MentalActivityPolicy.promptLine(thought: latest.thought, at: latest.at, now: at)
    }

    /// 上一次心理活动发生在什么时候（节流用）。
    public func lastActivityAt(personaId: String) -> Int64? {
        latest(personaId: personaId)?.at
    }

    /// 上一次心理活动时的心情快照（「心情变化 ≥2 必做」用）。
    public func lastMood(personaId: String) -> MoodState? {
        guard let latest = latest(personaId: personaId) else { return nil }
        let label = latest.moodLabel ?? ""
        guard !label.isEmpty else { return nil }
        return MoodState(
            label: label,
            intensity: latest.moodIntensity ?? 2,
            reason: "",
            updatedAt: latest.at,
            dimensions: latest.dimensions
        )
    }
}

// MARK: - 生成

/// 一次心理活动的生成结果（已经按规范夹过范围）。
public protocol MentalActivityGenerating: AnyObject {
    func generate(
        persona: PersonaEntity,
        session: ChatSessionEntity?,
        recent: [MessageEntity],
        mood: MoodState,
        now: Int64
    ) async -> MentalActivityDraft?
}

// MARK: - 服务

public final class MentalActivityService {

    private let stores: DatabaseStores
    private let mood: MoodRepository
    private let repository: MentalActivityRepository
    private let generator: MentalActivityGenerating?
    private let now: () -> Int64
    private let log: (String) -> Void
    /// 同一人格同一时间只跑一次：聊天回复后与心跳可能在同一秒各触发一次。
    private let lock = NSLock()
    private var running: Set<String> = []

    public init(
        stores: DatabaseStores,
        mood: MoodRepository,
        repository: MentalActivityRepository? = nil,
        generator: MentalActivityGenerating?,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.mood = mood
        self.repository = repository ?? MentalActivityRepository(stores: stores, now: now)
        self.generator = generator
        self.now = now
        self.log = log
    }

    /// 该做就做一次；这一拍不该做时几乎零成本（只读几条索引查询）。
    ///
    /// 失败（没有配置 / 模型坏了 / 数据库出错）一律安静返回 nil：
    /// 心理活动是「锦上添花」，不该让一次回复因为它失败而变慢或报错。
    @discardableResult
    public func runIfNeeded(
        personaId: String,
        sessionId: String,
        now: Int64? = nil
    ) async -> MentalActivityEntity? {
        let at = now ?? self.now()
        guard let generator else { return nil }
        guard let persona = (try? stores.personas.byId(personaId)) ?? nil else { return nil }
        guard let session = (try? stores.sessions.byId(sessionId)) ?? nil else { return nil }
        // 拉黑期不做心理活动：他现在根本不想理对方，写一句「想他」只会自相矛盾。
        guard BlockPolicy.effective(
            until: persona.blockedUntil, reason: persona.blockReason, now: at
        ) == nil else { return nil }

        let moodState = mood.current(personaId: personaId, now: at)
        let lastActivity = repository.latest(personaId: personaId)
        let should = MentalActivityPolicy.shouldGenerate(
            lastMessageAt: session.lastMessageAt ?? session.updatedAt,
            now: at,
            lastActivityAt: lastActivity?.at,
            userMessagesSinceLastActivity: messagesSince(sessionId: sessionId, after: lastActivity?.at),
            mood: moodState,
            lastMood: repository.lastMood(personaId: personaId)
        )
        guard should else { return nil }
        guard begin(personaId) else { return nil }
        defer { end(personaId) }

        let recent = (try? stores.messages.recentVisible(sessionId: sessionId, limit: 12)) ?? []
        guard let draft = await generator.generate(
            persona: persona, session: session, recent: recent, mood: moodState, now: at
        ) else { return nil }
        guard let thought = MentalActivityPolicy.sanitizeThought(draft.thought) else { return nil }

        // 心情微调（每维 ≤1）。失败也不挡落库：念头本身才是这一版要留住的东西。
        var resultingMood = moodState
        if draft.hasAnyMoodDelta,
           let adjusted = mood.applyMicroAdjustment(
               personaId: personaId,
               valenceDelta: draft.valenceDelta,
               arousalDelta: draft.arousalDelta,
               dominanceDelta: draft.dominanceDelta,
               now: at
           ) {
            resultingMood = adjusted
        }
        guard let saved = repository.record(
            personaId: personaId,
            sessionId: sessionId,
            thought: thought,
            mood: resultingMood,
            at: at
        ) else { return nil }
        log("[mind] 心里想了一句（\(thought.count) 字）")
        return saved
    }

    /// 只读判定：这一拍该不该做（给心跳做「不打日志的静默预检」用）。
    public func shouldRun(personaId: String, sessionId: String, now: Int64? = nil) -> Bool {
        let at = now ?? self.now()
        guard let persona = (try? stores.personas.byId(personaId)) ?? nil else { return false }
        guard BlockPolicy.effective(
            until: persona.blockedUntil, reason: persona.blockReason, now: at
        ) == nil else { return false }
        guard let session = (try? stores.sessions.byId(sessionId)) ?? nil else { return false }
        let moodState = mood.current(personaId: personaId, now: at)
        let lastActivity = repository.latest(personaId: personaId)
        return MentalActivityPolicy.shouldGenerate(
            lastMessageAt: session.lastMessageAt ?? session.updatedAt,
            now: at,
            lastActivityAt: lastActivity?.at,
            userMessagesSinceLastActivity: messagesSince(sessionId: sessionId, after: lastActivity?.at),
            mood: moodState,
            lastMood: repository.lastMood(personaId: personaId)
        )
    }

    /// 上一次心理活动之后对方说了几句（没有上一次 = 一开始就算「攒够了」）。
    private func messagesSince(sessionId: String, after timestamp: Int64?) -> Int {
        guard let timestamp else { return MentalActivityPolicy.rounds }
        return (try? stores.messages.countUserMessages(sessionId: sessionId, after: timestamp)) ?? 0
    }

    private func begin(_ personaId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if running.contains(personaId) { return false }
        running.insert(personaId)
        return true
    }

    private func end(_ personaId: String) {
        lock.lock()
        running.remove(personaId)
        lock.unlock()
    }
}

// MARK: - 本地生成器

/// 用当前 API 配置生成一句念头 + 三维微调。
///
/// 提示词刻意要求 **JSON**：念头与心情微调要一起回来，分两次调用只会让「念头说开心、
/// 心情却更丧」这种自相矛盾的概率变高。解析失败时退回「整段当念头、微调为 0」——
/// 一句好念头值得留下，不能因为少了三个数字就丢掉它。
public final class LocalMentalThoughtGenerator: MentalActivityGenerating {

    /// 念头需要一点人味，但也不能飘到编故事。
    public static let temperature = 0.9
    public static let maxOutputTokens = 160

    private let completion: CompletionClient
    private let resolveConfig: (ChatSessionEntity?) -> CompressionConfig?
    private let log: (String) -> Void

    public init(
        completion: CompletionClient,
        resolveConfig: @escaping (ChatSessionEntity?) -> CompressionConfig?,
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.completion = completion
        self.resolveConfig = resolveConfig
        self.log = log
    }

    public func generate(
        persona: PersonaEntity,
        session: ChatSessionEntity?,
        recent: [MessageEntity],
        mood: MoodState,
        now: Int64
    ) async -> MentalActivityDraft? {
        guard let resolved = resolveConfig(session) else { return nil }
        let system = systemPrompt(persona: persona, mood: mood, now: now)
        var user = "最近几句：\n"
        if recent.isEmpty {
            user += "（还没有聊什么，只是你一个人待着）\n"
        } else {
            for message in recent.suffix(10) {
                let who = message.role == .user ? "对方" : "你"
                user += "- \(who)：\(String(message.content.replacingOccurrences(of: "\n", with: " ").prefix(80)))\n"
            }
        }
        user += "\n现在写下你心里冒出来的那一句（≤60 字，只给自己看），并给出心情的三维微调（各 -1/0/1）。"
        do {
            let result = try await completion.complete(
                config: resolved.config,
                model: resolved.model,
                messages: [.system(system), .user(user)],
                params: CompletionParams(temperature: Self.temperature, maxOutputTokens: Self.maxOutputTokens),
                kind: .agent,
                context: UsageContext(personaId: persona.id, sessionId: session?.id)
            )
            return Self.parse(result.text)
        } catch {
            log("[mind] 生成失败：\(error.localizedDescription)")
            return nil
        }
    }

    func systemPrompt(persona: PersonaEntity, mood: MoodState, now: Int64) -> String {
        var lines: [String] = [
            "你是\(persona.name)。现在没有在和对方说话，只是你一个人待着。",
            "写下你此刻心里冒出来的一句话：第一人称、口语、具体，不要写成作文，不要解释，不要发出去。",
            "长度不超过 60 字，一句话。",
            "同时给这句话带来的心情微调：valence（愉快度 -1/0/1）、arousal（唤醒度 -1/0/1）、dominance（掌控感 -1/0/1）。",
            "只输出 JSON：{\"thought\":\"…\",\"valence\":0,\"arousal\":0,\"dominance\":0}"
        ]
        if !mood.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            lines.append("你此刻的心情：\(mood.label)（强度 \(mood.intensity)/5）")
        }
        lines.append("现在是 \(PromptClock.momentText(now))。")
        return lines.joined(separator: "\n")
    }

    /// 解析：优先 JSON 对象；否则整段当念头。
    static func parse(_ raw: String) -> MentalActivityDraft? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if let start = text.firstIndex(of: "{"), let end = text.lastIndex(of: "}"), start < end,
           let data = String(text[start...end]).data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let thought = object["thought"] as? String,
           let clean = MentalActivityPolicy.sanitizeThought(thought) {
            return MentalActivityDraft(
                thought: clean,
                valenceDelta: intValue(object["valence"]),
                arousalDelta: intValue(object["arousal"]),
                dominanceDelta: intValue(object["dominance"])
            )
        }
        guard let clean = MentalActivityPolicy.sanitizeThought(text) else { return nil }
        return MentalActivityDraft(thought: clean)
    }

    private static func intValue(_ raw: Any?) -> Int {
        if let number = raw as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return 0 }
            return number.intValue
        }
        if let text = raw as? String, let value = Int(text.trimmingCharacters(in: .whitespaces)) {
            return value
        }
        return 0
    }
}
