import Foundation

// 来源：app/src/main/java/com/myfriend/app/data/repo/MemoryRepository.kt 的 `maybeExtractMemories` 部分
//（提示词与解析；旁边那套「联网搜索值不值得记」的打分器在 Domain/ImportanceScoring）。
//
// 把一段对话收成记忆：提示词 → 模型返回 JSON 数组 → 宽松解析 → 去重筛查 → 落库。
//
// ## 为什么要「去重筛查」而不是直接落库
//
// 模型每次都会把同一件事又说一遍（「他对芒果过敏」可以在十轮对话里出现十次）。
// 直接落库的话，抽屉里会塞满近乎重复的句子，召回时又互相挤占上下文。
// `MemoryScreening.decide` 的规矩是：**同一件事出现两次才正式落库**，第一次只留一条「回声」；
// 已经写过的再被提到就加固（而不是新增）。
//
// ## 与 Android 的差别
//
// - 模型调用走 `CompletionClient`（服务层自己的接缝），App 层注入 Chat/Net 的实现；
// - 回声表 `memory_echoes` 曾缺失，0.9.1 已补进 Schema（老库升级后自动建表），
//   默认用进程内实现：本次运行里「第二次提到才落库」照常工作，重启后回声会丢；
//   表补上后换 `MemoryEchoStore` 即可，上层逻辑不变。
// - Android 会在落库后通知 PersonaSync（`markNativeSessionsDirtyForPersona`），iOS 的同步尚未移植，
//   这里不调用，也没有留一个空实现假装做了。

public struct MemoryExtractionResult: Equatable, Sendable {
    public var added: Int
    public var message: String
    public var reinforced: Int
    public var echoed: Int
    public var rejected: Int

    public init(added: Int = 0, message: String = "", reinforced: Int = 0, echoed: Int = 0, rejected: Int = 0) {
        self.added = added
        self.message = message
        self.reinforced = reinforced
        self.echoed = echoed
        self.rejected = rejected
    }
}

public final class MemoryExtraction {

    /// 距离上次抽取至少多出多少条消息才值得再抽一次。
    public static let memoryInterval = 6
    /// 抽几次：模型偶尔会吐出一长串，取前 5 条足够。
    public static let maxCandidates = 5
    /// 喂给模型的最近对话条数。
    public static let recentMessageCount = 20

    private let stores: DatabaseStores
    private let memoryRepository: MemoryRepository
    private let completion: CompletionClient
    private let echoStore: MemoryEchoStoring
    private let now: () -> Int64
    private let lock = NSLock()

    public init(
        stores: DatabaseStores,
        memoryRepository: MemoryRepository,
        completion: CompletionClient,
        echoStore: MemoryEchoStoring? = nil,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.stores = stores
        self.memoryRepository = memoryRepository
        self.completion = completion
        self.echoStore = echoStore ?? MemoryEchoStore(database: stores.database)
        self.now = now
    }

    /// 抽取一次。`force = true` 时不看间隔（「现在整理一次」用）。
    public func extract(
        sessionId: String,
        config: CompletionConfig,
        model: String,
        force: Bool = false
    ) async -> Result<MemoryExtractionResult, Error> {
        lock.lock()
        defer { lock.unlock() }

        // 1.9：记忆一律内置开启，抽不抽只看「间隔到了没」，没有开关要读（§一）。
        guard let session = (try? stores.sessions.byId(sessionId)) ?? nil else {
            return .failure(CompletionFailure("会话不存在"))
        }
        guard let persona = (try? stores.personas.byId(session.personaId)) ?? nil else {
            return .failure(CompletionFailure("人格不存在"))
        }
        let personaName = persona.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "他" : persona.name
        let messages = (try? stores.messages.bySession(sessionId)) ?? []
        let conversational = messages.filter {
            $0.role != .system && !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        if conversational.count < 4 {
            return .success(MemoryExtractionResult(added: 0, message: "对话还不够多"))
        }
        let currentCount = messages.count
        // 间隔归人格（`extractionEvery`，会话可覆盖，§三）。
        let interval = session.effectiveExtractionEvery(persona: persona)
        if !force && currentCount - session.lastMemoryExtractedCount < interval {
            return .success(MemoryExtractionResult(added: 0, message: "还未到记忆整理间隔"))
        }

        let recent = Array(conversational.suffix(Self.recentMessageCount))
        let existing = (try? memoryRepository.getForPrompt(personaId: persona.id, limit: 80)) ?? []
        let transcript = recent.map { message -> String in
            let who = message.role == .user ? "对方" : persona.name
            return "\(who)：\(String(message.content.prefix(1800)))"
        }.joined(separator: "\n")
        let existingText = existing.isEmpty
            ? "（暂无）"
            : existing.map { "- \($0.content)" }.joined(separator: "\n")

        let system = Self.systemPrompt(personaName: personaName)
        let user = """
        已有长期记忆：
        \(existingText)

        最近对话：
        \(transcript)
        """

        let result: CompletionResult
        do {
            result = try await completion.complete(
                config: config,
                model: model,
                messages: [.system(system), .user(user)],
                params: CompletionParams(temperature: 0.2, maxOutputTokens: 700),
                kind: .extraction,
                context: UsageContext(personaId: persona.id, sessionId: sessionId)
            )
        } catch {
            return .failure(error)
        }

        let at = now()
        // 抽取里的「昨天/上周三/去年冬天」要用这一轮的 now 换算成毫秒。
        let candidates = Self.parseCandidates(result.text, now: at)
        let existingRows = existing.map { memoryRepository.screenRow($0) }
        var workingEchoes = echoStore.byPersona(persona.id).map { echo -> MemoryCandidate in
            MemoryCandidate(
                content: echo.content,
                drawer: MemoryDrawer(rawValue: echo.drawer.uppercased()) ?? MemoryScreening.drawerOf(echo.categoryName),
                importance: echo.importance,
                sourceQuote: echo.sourceQuote,
                categoryName: echo.categoryName,
                confidence: echo.confidence
            )
        }

        var added = 0
        var reinforced = 0
        var echoed = 0
        var rejected = 0
        var toInsert: [MemoryEntity] = []
        var echoWrites: [MemoryEcho] = []

        for (index, candidate) in candidates.prefix(Self.maxCandidates).enumerated() {
            let decision = MemoryScreening.decide(candidate, existing: existingRows, echoes: workingEchoes, now: at)
            switch decision {
            case .reject:
                rejected += 1
            case .echo(let kept):
                echoed += 1
                echoWrites.append(
                    MemoryEcho(
                        id: UUID().uuidString,
                        personaId: persona.id,
                        drawer: kept.drawer.rawValue,
                        content: String(kept.content.prefix(800)),
                        importance: min(max(kept.importance, 1), 5),
                        sourceQuote: kept.sourceQuote,
                        categoryName: Self.categoryValue(kept).rawValue,
                        confidence: kept.confidence,
                        createdAt: at + Int64(index),
                        lastSeenAt: at + Int64(index)
                    )
                )
                workingEchoes.append(kept)
            case .promote(let kept, _):
                toInsert.append(toEntity(kept, personaId: persona.id, sessionId: sessionId, timestamp: at + Int64(index)))
                // 这条已经下沉成正式记忆，对应的回声可以清掉了。
                echoWrites.removeAll { echo in
                    MemoryScreening.similarity(echo.content, kept.content) >= 0.6
                }
                added += 1
            case .reinforce(let id, let appended):
                _ = memoryRepository.reinforce(memoryId: id, appendedText: appended, importance: nil)
                // 又说到同一件事：这次给的标签与发生时间补贴到已有记忆上（只补不覆盖）。
                memoryRepository.mergeStructure(memoryId: id, tags: candidate.tags, happenedAt: candidate.happenedAt)
                reinforced += 1
            }
        }

        if !echoWrites.isEmpty {
            echoStore.insertAll(echoWrites)
        }
        if !toInsert.isEmpty {
            _ = try? stores.memories.upsert(toInsert)
            for memory in toInsert {
                memoryRepository.rebuildTermIndex(memoryId: memory.id, content: memory.content)
                memoryRepository.vectorIndex.upsert(memoryId: memory.id, content: memory.content)
            }
        }

        // 记下「已经抽到第几条消息」，下一轮就不会重复抽同一段。
        var updatedSession = session
        updatedSession.lastMemoryExtractedCount = currentCount
        updatedSession.updatedAt = at
        _ = try? stores.sessions.upsert(updatedSession)

        let message: String
        if added > 0 {
            message = "记忆已更新（+\(added)）"
        } else if reinforced > 0 {
            message = "又聊到了旧事，记忆更牢了"
        } else if echoed > 0 {
            message = "记下了，还想再确认一次"
        } else {
            message = "没有发现需要新增的长期记忆"
        }
        return .success(
            MemoryExtractionResult(
                added: added,
                message: message,
                reinforced: reinforced,
                echoed: echoed,
                rejected: rejected
            )
        )
    }

    /// 从某个人格「最近聊过的那段对话」里抽一次（「记忆页 → 现在整理一次」用）。
    public func extractFromLatestSession(
        personaId: String,
        config: CompletionConfig,
        model: String
    ) async -> Result<MemoryExtractionResult, Error> {
        let sessions = (try? stores.sessions.byPersona(personaId)) ?? []
        guard let target = sessions.max(by: {
            ($0.lastMessageAt ?? $0.createdAt) < ($1.lastMessageAt ?? $1.createdAt)
        }) else {
            return .failure(CompletionFailure("该人格还没有对话"))
        }
        return await extract(sessionId: target.id, config: config, model: model, force: true)
    }

    // MARK: - 提示词

    /// 抽取用的 system 提示词（照抄 Android，措辞不改：同一个抽屉、同一个判据）。
    public static func systemPrompt(personaName: String) -> String {
        """
        你是一个长期记忆整理器，为「\(personaName)」整理与对方的长期记忆。
        只提取以后相处真正用得上的信息：稳定的身份与生活背景、偏好与忌讳、重要经历、明确的约定，以及他自己在对话里显露的性格与说话方式。
        不提取一次性情绪、寒暄、以及已有记忆里已经写下的内容。
        每条都要指出它是被哪句话触发的（sourceQuote，原话片段，30 字以内）。
        没有值得长期保存的信息时输出空数组 []。
        只输出 JSON，不要 Markdown 代码块，不要解释。格式：
        [{"content":"用第三人称写的一句完整记忆","category":"FACT|PREFERENCE|EVENT|RELATIONSHIP|OTHER","drawer":"SELF|USER|EVENT","importance":1到5的整数,"confidence":0到1的小数,"sourceQuote":"触发它的原话","tags":["短标签"],"happenedAt":"这件事发生的时间"}]
        drawer 含义：USER 关于对方的事，SELF 关于他自己是什么样的人，EVENT 两人之间的经历与约定。
        tags 是以后能被一句话勾出来的短词：人（妹妹、阿杰）、事（搬家、面试）、物（猫、吉他）、情绪（失眠、焦虑）。
        最多 5 个，每个不超过 6 个字；宁可不给，也别拿整句话当标签。
        happenedAt 是**这件事发生的时间**，不是现在：对方说「昨天面试砸了」，就填「昨天」；
        说「去年冬天去过一次哈尔滨」，就填「去年冬天」。原话里没有时间线索就填空字符串，不要猜。
        最多 5 条。每条不超过 300 字。importance 表示未来重要性；拿不准就把 confidence 压低，不要硬凑。
        """
    }

    // MARK: - 解析

    /// 宽松解析模型输出的 JSON 数组。坏数据一律空列表（这次就当没抽到东西）。
    public static func parseCandidates(_ raw: String, now: Int64) -> [MemoryCandidate] {
        var cleaned = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        cleaned = cleaned.replacingOccurrences(of: "^```json", with: "", options: .regularExpression)
        cleaned = cleaned.replacingOccurrences(of: "^```", with: "", options: .regularExpression)
        guard let start = cleaned.firstIndex(of: "["),
              let end = cleaned.lastIndex(of: "]"),
              start < end else { return [] }
        let slice = String(cleaned[start...end])
        guard let data = slice.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])) as? [Any]
        else { return [] }

        var candidates: [MemoryCandidate] = []
        for element in array {
            guard let object = element as? [String: Any] else { continue }
            let content = (stringOf(object, "content") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if content.isEmpty { continue }
            let category = MemoryCategory(stored: stringOf(object, "category")?.uppercased() ?? "OTHER")
            let importance = intOf(object, "importance") ?? 3
            let drawerRaw = (stringOf(object, "drawer") ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            let drawer = MemoryDrawer(rawValue: drawerRaw) ?? MemoryScreening.drawerOf(category.rawValue)
            let confidence = doubleOf(object, "confidence") ?? 0.7
            let sourceQuote = (stringOf(object, "sourceQuote") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            // tags 可能是 JSON 数组也可能是逗号串：都交给 ImportanceScoring 的宽松解析。
            var tagsRaw: String?
            if let array = object["tags"] as? [Any],
               let data = try? JSONSerialization.data(withJSONObject: array),
               let text = String(data: data, encoding: .utf8) {
                tagsRaw = text
            } else {
                tagsRaw = stringOf(object, "tags")
            }
            let tags = ImportanceScoring.parseTags(tagsRaw)
            let happenedAt = ImportanceScoring.parseRelativeTime(stringOf(object, "happenedAt"), now: now)
            candidates.append(
                MemoryCandidate(
                    content: String(content.prefix(800)),
                    drawer: drawer,
                    importance: importance,
                    sourceQuote: String(sourceQuote.prefix(120)),
                    categoryName: category.rawValue,
                    confidence: Float(min(max(confidence, 0), 1)),
                    tags: tags,
                    happenedAt: happenedAt
                )
            )
        }
        return candidates
    }

    // MARK: - 落库

    private static func categoryValue(_ candidate: MemoryCandidate) -> MemoryCategory {
        let raw = candidate.categoryName.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if let category = MemoryCategory(rawValue: raw) { return category }
        switch candidate.drawer {
        case .event: return .event
        case .selfDrawer: return .other
        case .user: return .fact
        }
    }

    private func toEntity(
        _ candidate: MemoryCandidate,
        personaId: String,
        sessionId: String,
        timestamp: Int64
    ) -> MemoryEntity {
        let category = Self.categoryValue(candidate)
        return MemoryEntity(
            id: UUID().uuidString,
            personaId: personaId,
            content: String(candidate.content.prefix(800)),
            category: category,
            importance: min(max(candidate.importance, 1), 5),
            pinned: false,
            sourceSessionId: sessionId,
            createdAt: timestamp,
            updatedAt: timestamp,
            lastUsedAt: 0,
            useCount: 1,
            drawer: candidate.drawer.rawValue,
            sourceQuote: candidate.sourceQuote.isEmpty ? nil : candidate.sourceQuote,
            status: "ACTIVE",
            strength: 1.0,
            tags: MemoryTags.encode(candidate.tags),
            happenedAt: candidate.happenedAt.flatMap { $0 > 0 ? $0 : nil }
        )
    }

    private static func stringOf(_ object: [String: Any], _ key: String) -> String? {
        guard let value = object[key] else { return nil }
        if value is NSNull { return nil }
        if let text = value as? String { return text }
        if let number = value as? NSNumber { return number.stringValue }
        return nil
    }

    private static func intOf(_ object: [String: Any], _ key: String) -> Int? {
        guard let value = object[key] else { return nil }
        if let number = value as? NSNumber { return number.intValue }
        // 模型经常把数字写成字符串（"importance":"4"）：宽松解析比丢一条候选划算。
        if let text = value as? String { return Int(text.trimmingCharacters(in: .whitespacesAndNewlines)) }
        return nil
    }

    private static func doubleOf(_ object: [String: Any], _ key: String) -> Double? {
        guard let value = object[key] else { return nil }
        if let number = value as? NSNumber { return number.doubleValue }
        if let text = value as? String { return Double(text) }
        return nil
    }
}
