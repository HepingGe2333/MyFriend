import Foundation

// 来源：app/src/main/java/com/myfriend/app/data/repo/MemoryConsolidator.kt
//
// 每日记忆整理：把抽屉里的东西过一遍，留下印象深的，把不重要的挪出去。
//
// ## 「随风叉、用完即弃」是什么意思
//
// 整理**不是**一次聊天：这里自己拼一份 messages 直接调模型，不建 session、不写 messages 表、
// 不给工具、不读会话历史，调用一结束就把结果落库、把这次的请求上下文丢掉。
// 所以界面上永远不会出现一段「整理对话」。
//
// ## 谁负责什么
//
// - 模型只负责「下判断」：哪条留着、哪条并起来、哪条归档、哪条忘掉（Domain/ImportanceScoring 的解析）；
// - 本类负责「按判断改库」，并在模型没提到的记忆上跑 Domain/MemoryLifecycle 的时间衰减；
// - 纯逻辑（阈值、衰减公式、JSON 容错解析）都在 Domain 层，单测覆盖。
//
// ## 硬规矩
//
// **绝不删消息**。整理只动 memories / memory_terms / memory_versions / memory_consolidations，
// 一条 messages 都不碰（这也是 `applyPlan` / `applyDecay` 里没有任何 message 写操作的原因）。

public enum ConsolidationStatus: String, Equatable, Sendable {
    case done = "DONE"
    case disabled = "DISABLED"
    case noPersona = "NO_PERSONA"
    case noConfig = "NO_CONFIG"
    case noMemory = "NO_MEMORY"
    case failed = "FAILED"
}

/// 一次「每日记忆整理」的结果。
public struct ConsolidationResult: Equatable, Sendable {
    public var personaId: String
    public var status: ConsolidationStatus
    /// 被明确留下的条数（含 raise 提权的）。
    public var kept: Int
    /// 被并进别人的条数（不含被保留的那条）。
    public var merged: Int
    /// 本轮被归档的条数（不含 merged）。
    public var archived: Int
    /// 本轮被真正删除的条数。
    public var forgotten: Int
    /// 模型给的一句话总结（失败时为中文错误说明）。
    public var summary: String
    public var at: Int64
    public var modelName: String?

    public init(
        personaId: String,
        status: ConsolidationStatus,
        kept: Int = 0,
        merged: Int = 0,
        archived: Int = 0,
        forgotten: Int = 0,
        summary: String = "",
        at: Int64 = 0,
        modelName: String? = nil
    ) {
        self.personaId = personaId
        self.status = status
        self.kept = kept
        self.merged = merged
        self.archived = archived
        self.forgotten = forgotten
        self.summary = summary
        self.at = at
        self.modelName = modelName
    }

    public var didAnything: Bool { status == .done }

    /// 给日志 / 调试面板看的一句中文。
    public func describe() -> String {
        switch status {
        case .done:
            return "整理完记忆：留下 \(kept) 条、合并 \(merged) 条、归档 \(archived) 条、忘了 \(forgotten) 条"
        case .disabled: return "记忆整理已关闭"
        case .noPersona: return "找不到这个人格，没法整理记忆"
        case .noConfig: return "还没有可用的 API 配置，这次没整理记忆"
        case .noMemory: return "还没有需要整理的记忆"
        case .failed: return summary.isEmpty ? "整理记忆时出错了" : summary
        }
    }
}

public final class MemoryConsolidator {

    /// 一次整理最多看多少条记忆（多的留给下一次）。
    public static let maxMemoriesPerRun = 300
    /// 塞进提示词的清单条数上限：再多模型会开始乱抄编号。
    public static let maxPromptMemories = 90
    /// 每个抽屉在清单里最多占多少条：保证 USER 抽屉的老事实一定看得到。
    public static let maxPromptPerDrawer = 30
    /// 清单里每条正文的截断长度。
    public static let maxLineContentChars = 120
    /// 最近聊天摘要取几条。
    public static let maxRecentMessages = 8
    /// 浏览 digest 截断长度。
    public static let maxBrowseDigestChars = 600
    /// 合并后的正文上限。
    public static let maxContentChars = 800

    private let stores: DatabaseStores
    private let memoryRepository: MemoryRepository
    private let completion: CompletionClient
    private let browsingDigest: (String) -> String?
    private let now: () -> Int64
    private let lock = NSLock()

    /// - Parameter browsingDigest: 「他最近自己上网看到的」摘要；App 层接到 IdleBrowsing 的缓存上。
    ///   传 nil 表示还没有这条链路（提示词里会写「这几天没上网看东西」）。
    public init(
        stores: DatabaseStores,
        memoryRepository: MemoryRepository,
        completion: CompletionClient,
        browsingDigest: @escaping (String) -> String? = { _ in nil },
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.stores = stores
        self.memoryRepository = memoryRepository
        self.completion = completion
        self.browsingDigest = browsingDigest
        self.now = now
    }

    /// 整理一个人格的记忆。
    ///
    /// 安全返回：没开记忆整理、没人设、没 API 配置、没记忆时都不会抛异常，只返回对应状态。
    /// 网络/模型出错时返回 `.failed`，**不**写整理留痕（那不算一次整理）。
    public func consolidate(
        personaId: String,
        config: CompletionConfig?,
        model: String,
        now: Int64? = nil
    ) async -> ConsolidationResult {
        lock.lock()
        defer { lock.unlock() }
        let at = now ?? self.now()
        do {
            return try await consolidateLocked(personaId: personaId, config: config, model: model, now: at)
        } catch {
            return ConsolidationResult(
                personaId: personaId,
                status: .failed,
                summary: String("整理记忆时出错了：\(error.localizedDescription)".prefix(120)),
                at: at
            )
        }
    }

    private func consolidateLocked(
        personaId: String,
        config: CompletionConfig?,
        model: String,
        now: Int64
    ) async throws -> ConsolidationResult {
        // 1.9：整理一律内置开启（§一）。`disabled` 这个状态留给「人格不存在」之后的将来用途，
        // 现在只有显式调用才会走到这里，所以不再有开关判断。
        guard let persona = (try? stores.personas.byId(personaId)) ?? nil else {
            return ConsolidationResult(personaId: personaId, status: .noPersona, at: now)
        }
        let all = Array(((try? memoryRepository.getAllForPersona(persona.id)) ?? [])
            .prefix(Self.maxMemoriesPerRun))
        if all.isEmpty {
            return ConsolidationResult(personaId: persona.id, status: .noMemory, at: now)
        }
        guard let config else {
            return ConsolidationResult(personaId: persona.id, status: .noConfig, at: now)
        }

        // ---- 一次用完即弃的分叉调用：没有会话、没有工具、没有历史 ----
        // 清单按抽屉各取一批：USER 抽屉里的老事实（过敏、家人）不能被 EVENT 的琐事挤出去。
        let promptMemories = selectPromptMemories(all)
        let messages = forkedMessages(persona: persona, memories: promptMemories, totalMemories: all.count, now: now)

        let result: CompletionResult
        do {
            result = try await completion.complete(
                config: config,
                model: model,
                messages: messages,
                // 整理是「按规矩分类」，不需要创造力：温度压低到 0.25，输出长度受控。
                params: CompletionParams(temperature: 0.25, maxOutputTokens: 900),
                kind: .consolidation,
                // 整理是「按人整理」，没有具体会话：sessionId 留空。
                context: UsageContext(personaId: persona.id)
            )
        } catch {
            return ConsolidationResult(
                personaId: persona.id,
                status: .failed,
                summary: String("整理记忆时出错了：\(error.localizedDescription)".prefix(120)),
                at: now,
                modelName: model
            )
        }
        // 到这里请求上下文已经用完即弃：文本是唯一留下来的东西。
        let plan = ImportanceScoring.parseConsolidation(result.text)

        let applied = applyPlan(plan, memories: all, promptMemories: promptMemories, now: now)
        let decayed = applyDecay(all, handledIds: applied.handledIds,
                                 decayFactor: persona.memoryDecayFactor, now: now)

        let kept = applied.kept
        let merged = applied.merged
        let archived = applied.archived + decayed.archived
        let forgotten = applied.forgotten + decayed.forgotten
        let summary = plan.summary.isEmpty
            ? defaultSummary(kept: kept, merged: merged, archived: archived, forgotten: forgotten)
            : plan.summary

        // 无论模型给没给操作，都留一条痕迹：界面上要能说「他昨天整理过记忆」。
        _ = try? stores.memoryConsolidations.upsert(
            MemoryConsolidationEntity(
                id: UUID().uuidString,
                personaId: persona.id,
                at: now,
                kept: kept,
                merged: merged,
                archived: archived,
                forgotten: forgotten,
                summary: String(summary.prefix(200)),
                modelName: model
            )
        )

        return ConsolidationResult(
            personaId: persona.id,
            status: .done,
            kept: kept,
            merged: merged,
            archived: archived,
            forgotten: forgotten,
            summary: summary,
            at: now,
            modelName: model
        )
    }

    // MARK: - 分叉会话（用完即弃）

    /// 拼出这次整理用到的 messages：只有 system（整理器提示词）+ user（编号清单 + 最近聊天 + 浏览摘要）。
    ///
    /// 绝不带历史会话、绝不给工具、绝不写进任何 session/message 表。
    /// 模型看到的世界只有这份清单，所以清单里必须自带一切判断依据（抽屉、重要度、hit、年龄、正文）。
    func forkedMessages(
        persona: PersonaEntity,
        memories: [MemoryEntity],
        totalMemories: Int,
        now: Int64
    ) -> [CompletionMessage] {
        let summary = personaSummary(persona)
        let system = ImportanceScoring.consolidationSystemPrompt(personaSummary: summary)
        var user = ""
        user += "现在：\(ServiceTime.formatted(now))\n\n"
        user += "【记忆清单】共 \(totalMemories) 条，列出了 \(memories.count) 条"
        user += "（编号 | 抽屉 | 重要度 | 被提起 | 多久没动 | 状态 | 正文；有的还会带「标签」「发生在」）\n"
        for (index, memory) in memories.enumerated() {
            user += memoryLine(index: index + 1, memory: memory, now: now) + "\n"
        }
        if totalMemories > memories.count {
            user += "（按三个抽屉各取了一批，剩下的 \(totalMemories - memories.count) 条没列出来，它们会自己变淡）\n"
        }
        user += "\n【最近聊到的】\n"
        let chats = recentChatDigest(personaId: persona.id, now: now)
        user += (chats.isEmpty ? "（最近没有聊天）" : chats) + "\n"
        user += "\n【他最近自己上网看到的】\n"
        let browsing = browsingDigest(persona.id) ?? ""
        let trimmedBrowsing = String(browsing.prefix(Self.maxBrowseDigestChars))
        user += (trimmedBrowsing.isEmpty ? "（这几天没上网看东西）" : trimmedBrowsing)
        return [.system(system), .user(user.trimmingCharacters(in: .whitespacesAndNewlines))]
    }

    /// 一行清单：编号 + 判断所需的一切，正文截断到 120 字。
    ///
    /// 「标签」与「发生在」有就写、没有就不写：它们不是必填字段，
    /// 写成「标签：无」只会让模型以为要补一个，反而开始瞎编。
    func memoryLine(index: Int, memory: MemoryEntity, now: Int64) -> String {
        let drawer = memoryRepository.drawerValue(memory).rawValue
        let age = ageText(at: memory.lastUsedAt > 0 ? memory.lastUsedAt : memory.createdAt, now: now)
        let pinned = memory.pinned ? " | 钉住" : ""
        let tags = MemoryTags.decode(memory.tags)
        let tagText = tags.isEmpty ? "" : " | 标签：\(tags.joined(separator: "、"))"
        let happened = memory.happenedAt.flatMap { $0 > 0 ? $0 : nil }
        let happenedText = happened.map { " | 发生在：\(Recall.dateLabel(at: $0, now: now))" } ?? ""
        let body = String(memory.content.replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(Self.maxLineContentChars))
        let tier = tierLabel(MemoryTier.parse(memory.status))
        return "第\(index) 条 | \(drawer) | 重要度\(min(max(memory.importance, 1), 5)) | "
            + "被提起 \(max(memory.useCount, 0)) 次 | \(age) | \(tier)\(pinned)\(tagText)\(happenedText) | \(body)"
    }

    /// 按抽屉各取一批进清单：USER 抽屉的老事实不该被 EVENT 的琐事挤掉。
    ///
    /// 每个抽屉各留 30 个座位（总数不超过 90）；某个抽屉不够时用剩下的记忆按原顺序补满。
    /// 注意：编号是「清单里的第几条」，`applyPlan` 必须用同一份列表建索引表。
    func selectPromptMemories(_ memories: [MemoryEntity]) -> [MemoryEntity] {
        if memories.count <= Self.maxPromptMemories { return memories }
        let ordered = memories.sorted { a, b in
            if a.pinned != b.pinned { return a.pinned && !b.pinned }
            if a.importance != b.importance { return a.importance > b.importance }
            return a.updatedAt > b.updatedAt
        }
        var picked: [String: MemoryEntity] = [:]
        var pickedOrder: [String] = []
        for drawer in MemoryDrawer.allCases {
            var taken = 0
            for memory in ordered where memoryRepository.drawerValue(memory) == drawer {
                if taken >= Self.maxPromptPerDrawer { break }
                if picked[memory.id] == nil {
                    picked[memory.id] = memory
                    pickedOrder.append(memory.id)
                }
                taken += 1
            }
        }
        // 某个抽屉不够 30 条时，用剩下的记忆按原顺序补满，总上限不变。
        if picked.count < Self.maxPromptMemories {
            for memory in ordered where picked[memory.id] == nil {
                if picked.count >= Self.maxPromptMemories { break }
                picked[memory.id] = memory
                pickedOrder.append(memory.id)
            }
        }
        return pickedOrder.compactMap { picked[$0] }.sorted { a, b in
            if a.pinned != b.pinned { return a.pinned && !b.pinned }
            if a.importance != b.importance { return a.importance > b.importance }
            return a.updatedAt > b.updatedAt
        }
    }

    /// 最近 8 句对话的摘要（只取最新一个会话），用于判断「最近在意什么」。
    func recentChatDigest(personaId: String, now: Int64) -> String {
        let sessions = (try? stores.sessions.byPersona(personaId)) ?? []
        guard let session = sessions.max(by: {
            ($0.lastMessageAt ?? $0.createdAt) < ($1.lastMessageAt ?? $1.createdAt)
        }) else { return "" }
        let messages = ((try? stores.messages.bySession(session.id)) ?? [])
            .filter { $0.role != .system && !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .suffix(Self.maxRecentMessages)
        return messages.map { message -> String in
            let who = message.role == .user ? "对方" : "他"
            let body = String(message.content.replacingOccurrences(of: "\n", with: " ").prefix(60))
            return "- \(who)（\(ageText(at: message.createdAt, now: now))）：\(body)"
        }.joined(separator: "\n")
    }

    /// 人设摘要：只取最能说明「他是谁」的几项，避免整理提示词过长。
    ///
    /// iOS 还没有 PersonaCard 的移植，所以这里用 persona 自己的字段拼（名字 / 类别 / 提示词开头）。
    /// 与 Android 的人设卡摘要相比信息更少，但同样是「一句话说清他是谁」。
    func personaSummary(_ persona: PersonaEntity) -> String {
        var parts: [String] = []
        let name = persona.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !name.isEmpty { parts.append(name) }
        let category = persona.category.trimmingCharacters(in: .whitespacesAndNewlines)
        if !category.isEmpty { parts.append("关系：\(category)") }
        let description = persona.description.trimmingCharacters(in: .whitespacesAndNewlines)
        if !description.isEmpty { parts.append(String(description.prefix(60))) }
        if parts.isEmpty {
            let prompt = persona.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
            if !prompt.isEmpty { parts.append(String(prompt.prefix(80))) }
        }
        if parts.isEmpty { parts.append("他") }
        return parts.joined(separator: "；")
    }

    // MARK: - 按模型指示改库

    struct Applied {
        var handledIds: Set<String>
        var kept: Int
        var merged: Int
        var archived: Int
        var forgotten: Int
    }

    struct DecayOutcome {
        var archived: Int
        var forgotten: Int
    }

    /// 执行模型给的指令。
    ///
    /// 顺序固定 raise → keep → merge → archive → forget：先加固/合并，再归档删库，
    /// 避免「先删了后面还想合并它」的尴尬。同一条记忆只被处理一次，模型重复列同一编号不会重复计数。
    func applyPlan(
        _ plan: ConsolidationPlan,
        memories: [MemoryEntity],
        promptMemories: [MemoryEntity],
        now: Int64
    ) -> Applied {
        if plan.isEmpty || memories.isEmpty {
            return Applied(handledIds: [], kept: 0, merged: 0, archived: 0, forgotten: 0)
        }
        // 编号是「清单里的第几条」，必须和提示词里那份列表一一对应。
        var byIndex: [String: MemoryEntity] = [:]
        for (index, memory) in promptMemories.enumerated() {
            byIndex["\(index + 1)"] = memory
        }
        let byId = Dictionary(uniqueKeysWithValues: memories.map { ($0.id, $0) })
        var handled = Set<String>()
        var kept = 0
        var merged = 0
        var archived = 0
        var forgotten = 0

        // 模型抄回来的编号可能是 "3"、"第3条"、"#3"，也可能直接抄了真实 id：都认。
        func resolve(_ raw: String) -> MemoryEntity? {
            var key = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            key = key.trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
            if key.hasPrefix("#") { key = String(key.dropFirst()) }
            if key.hasPrefix("第") { key = String(key.dropFirst()) }
            if key.hasSuffix("条") { key = String(key.dropLast()) }
            if key.hasSuffix("号") { key = String(key.dropLast()) }
            key = key.trimmingCharacters(in: .whitespacesAndNewlines)
            if key.isEmpty { return nil }
            if let found = byIndex[key] { return found }
            if let found = byId[key] { return found }
            let digits = key.filter { $0.isNumber }
            return byIndex[digits]
        }

        let ordered = plan.operations.sorted { opOrder($0.op) < opOrder($1.op) }
        for op in ordered {
            var targets: [MemoryEntity] = []
            var seen = Set<String>()
            for raw in op.ids {
                guard let memory = resolve(raw) else { continue }
                guard !handled.contains(memory.id) else { continue }
                if seen.insert(memory.id).inserted { targets.append(memory) }
            }
            if targets.isEmpty { continue }

            switch op.op {
            case .keep:
                for memory in targets {
                    let state = memoryRepository.lifecycleState(memory)
                    let strength = MemoryLifecycle.keepStrength(state, now: now)
                    // 对已归档的条目说 keep = 把它从归档里拿回上下文（见提示词里的说明）。
                    if state.tier == .archived {
                        memoryRepository.setLifecycle(id: memory.id, status: .active,
                                                      strength: strength, archivedAt: nil, now: now)
                    } else {
                        memoryRepository.touchStrength(id: memory.id, strength: strength, now: now)
                    }
                    handled.insert(memory.id)
                    kept += 1
                }

            case .raise:
                for memory in targets {
                    let state = memoryRepository.lifecycleState(memory)
                    let importance = ImportanceScoring.clampImportance(op.importance ?? memory.importance)
                    let strength = MemoryLifecycle.keepStrength(state, now: now)
                    let newContent = String((op.content ?? memory.content).prefix(Self.maxContentChars))
                    memoryRepository.applyConsolidation(
                        id: memory.id,
                        importance: importance,
                        strength: strength,
                        content: newContent,
                        now: now
                    )
                    // 归档的记忆被提权，同样等于重新拿回来。
                    if state.tier == .archived {
                        memoryRepository.setLifecycle(id: memory.id, status: .active,
                                                      strength: strength, archivedAt: nil, now: now)
                    }
                    handled.insert(memory.id)
                    // 提权 = 留下并加强，算在「留下」里（表里没有单独的 raise 计数）。
                    kept += 1
                }

            case .merge:
                if targets.count < 2 { continue }
                let sorted = targets.sorted { a, b in
                    a.createdAt == b.createdAt ? a.id < b.id : a.createdAt < b.createdAt
                }
                let target = sorted[0]
                let rest = Array(sorted.dropFirst())
                let content = String((op.content ?? mergedContent(sorted)).prefix(Self.maxContentChars))
                let importance = ImportanceScoring.clampImportance(
                    op.importance ?? sorted.map(\.importance).max() ?? target.importance
                )
                memoryRepository.applyConsolidation(
                    id: target.id,
                    importance: importance,
                    strength: MemoryLifecycle.keepStrength(memoryRepository.lifecycleState(target), now: now),
                    content: content,
                    now: now
                )
                // 合并后要把结构字段带过来：applyConsolidation 只改正文/重要度/分量，
                // tags 与 happenedAt 在这里补写，否则并一次就丢了「这件事什么时候发生的」。
                let mergedTags = MemoryTags.sanitize(sorted.flatMap { MemoryTags.decode($0.tags) })
                let mergedHappened = sorted.compactMap { $0.happenedAt.flatMap { $0 > 0 ? $0 : nil } }.first
                if !mergedTags.isEmpty || mergedHappened != nil {
                    memoryRepository.mergeStructure(
                        memoryId: target.id,
                        tags: mergedTags,
                        happenedAt: mergedHappened
                    )
                }
                for memory in rest {
                    // 被并掉的：归档（不是删除），万一并错了还能从「回忆」里找回来。
                    memoryRepository.setLifecycle(id: memory.id, status: .archived,
                                                  strength: 0.0, archivedAt: now, now: now)
                }
                handled.insert(target.id)
                rest.forEach { handled.insert($0.id) }
                merged += rest.count

            case .archive:
                for memory in targets {
                    memoryRepository.setLifecycle(id: memory.id, status: .archived,
                                                  strength: 0.0, archivedAt: now, now: now)
                    handled.insert(memory.id)
                    archived += 1
                }

            case .forget:
                for memory in targets {
                    // 忘掉就是真删：先删记忆再删索引（顺序理由见 MemoryRepository.delete）。
                    memoryRepository.deleteById(memory.id)
                    handled.insert(memory.id)
                    forgotten += 1
                }
            }
        }
        return Applied(handledIds: handled, kept: kept, merged: merged, archived: archived, forgotten: forgotten)
    }

    /// 模型没提到的记忆：交给时间自己处理（变淡 → 沉底 → 归档 → 很久以后忘掉）。
    /// - Parameter decayFactor: 人格的记性系数（`memoryRetention`）：记性好的人掉得慢（§三）。
    func applyDecay(_ memories: [MemoryEntity], handledIds: Set<String>,
                    decayFactor: Double = 1.0, now: Int64) -> DecayOutcome {
        var archived = 0
        var forgotten = 0
        for memory in memories where !handledIds.contains(memory.id) {
            let update = MemoryLifecycle.dailyUpdate(
                memoryRepository.lifecycleState(memory), now: now, retentionFactor: decayFactor
            )
            switch update.action {
            case .keep(let id):
                memoryRepository.touchStrength(id: id, strength: update.strength, now: now)
            case .demote(let id, let tier, _):
                let toArchive = tier == .archived
                memoryRepository.setLifecycle(
                    id: id,
                    status: tier,
                    strength: update.strength,
                    archivedAt: toArchive ? now : nil,
                    now: now
                )
                if toArchive { archived += 1 }
            case .promote(let id, _):
                memoryRepository.setLifecycle(
                    id: id, status: .active, strength: update.strength, archivedAt: nil, now: now
                )
            case .forget(let id, _):
                memoryRepository.deleteById(id)
                forgotten += 1
            }
        }
        return DecayOutcome(archived: archived, forgotten: forgotten)
    }

    // MARK: - 小工具

    func opOrder(_ op: MemoryOp) -> Int {
        switch op {
        case .raise: return 0
        case .keep: return 1
        case .merge: return 2
        case .archive: return 3
        case .forget: return 4
        }
    }

    /// 合并后的正文：保留最老那条的原文，再把其余的不同部分接上去，总长受 maxContentChars 限制。
    func mergedContent(_ sorted: [MemoryEntity]) -> String {
        let base = sorted[0].content.trimmingCharacters(in: .whitespacesAndNewlines)
        let extra = sorted.dropFirst().map { $0.content.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && !base.contains($0) }
        let combined = ([base] + extra).joined(separator: "；")
        return String(combined.prefix(Self.maxContentChars))
    }

    func ageText(at: Int64, now: Int64) -> String {
        if at <= 0 { return "时间不明" }
        let days = max(now - at, 0) / MemoryLifecycle.DAY_MILLIS
        if days <= 0 { return "今天动过" }
        if days == 1 { return "1 天前动过" }
        if days < 30 { return "\(days) 天前动过" }
        return "\(days / 30) 个月前动过"
    }

    /// 状态用中文讲给模型听，别把 ACTIVE/ARCHIVED 这种数据库值丢进提示词。
    func tierLabel(_ tier: MemoryTier) -> String {
        switch tier {
        case .active: return "还在用"
        case .dormant: return "已经沉下去"
        case .archived: return "已归档"
        }
    }

    func defaultSummary(kept: Int, merged: Int, archived: Int, forgotten: Int) -> String {
        var parts: [String] = []
        if kept > 0 { parts.append("留下 \(kept) 条") }
        if merged > 0 { parts.append("并起 \(merged) 条") }
        if archived > 0 { parts.append("挪出去 \(archived) 条") }
        if forgotten > 0 { parts.append("忘掉 \(forgotten) 条") }
        return parts.isEmpty ? "这次没怎么动，让它自己慢慢变淡" : parts.joined(separator: "，")
    }
}
