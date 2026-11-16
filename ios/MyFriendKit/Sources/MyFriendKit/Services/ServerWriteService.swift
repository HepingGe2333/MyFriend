import Foundation

// ---------------------------------------------------------------------------
// 服务端写路径的**编排层**（M5）：先写服务端，成功之后再更新本地 SQLite 缓存。
//
// 为什么顺序不能反过来：服务端是权威。先写本地再写服务端，一旦服务端失败，库里的
// 状态就变成「这台手机以为改过了」，而另一端/服务端完全不知道；用户下次同步会看到
// 改动莫名其妙地回滚。所以：
//
//   服务端成功 → 本地缓存（upsert / 级联删除）→ 返回
//   服务端失败 → **不动本地**，把中文错误抛给界面
//
// 删除与缓存一致：人格删除走 `PersonaDeletion`（先索引/子表再父表的既有顺序），
// 记忆删除走 `MemoryRepository.deleteById`（连带倒排索引与向量索引），预设直接删行。
// 本地缓存更新失败会抛 `ServerWriteError.cacheUpdateFailed` —— 服务端已经改了，
// 这不是「保存失败」，但必须明确告诉用户「这里没跟上，下次同步会补回来」，
// 绝不能假装成功。
// ---------------------------------------------------------------------------

/// 服务端写路径的本地编排错误（服务端本身已经成功/失败由 `ServerAPIError` 表达）。
public enum ServerWriteError: Error, LocalizedError, CustomStringConvertible {
    /// 服务端 2xx 但响应里没有该有的对象。
    case invalidResponse(String)
    /// 服务端已经改了，本地缓存没跟上（下次同步会把权威值补回来）。
    case cacheUpdateFailed(String)
    /// 需要本地导入器才能更新缓存（主流程一定会注入；单独构造服务对象时才会缺）。
    case localCacheUnavailable

    public var errorDescription: String? {
        switch self {
        case .invalidResponse(let detail):
            return "服务端返回的数据不完整：\(detail)"
        case .cacheUpdateFailed(let detail):
            return "服务端已经保存，但本地缓存没更新：\(detail)"
        case .localCacheUnavailable:
            return "服务端已经保存，但本地缓存没有更新（缺少导入器）"
        }
    }

    public var description: String { errorDescription ?? "服务端写路径错误" }
}

public final class ServerWriteService {

    private let client: ServerAPIClient
    private let stores: DatabaseStores
    private let memoryRepository: MemoryRepository
    private let backup: BackupService?
    private let now: () -> Int64

    public init(
        client: ServerAPIClient,
        stores: DatabaseStores,
        memoryRepository: MemoryRepository,
        backup: BackupService? = nil,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.client = client
        self.stores = stores
        self.memoryRepository = memoryRepository
        self.backup = backup
        self.now = now
    }

    // MARK: - 人格

    /// 保存人格：服务端成功后再 upsert 本地缓存。
    ///
    /// - Parameter isNew: 新建时不要带 `id`（服务端自己生成；带一个不存在的 id 会 404）。
    /// - Returns: 服务端确认后的人格（带服务端 id/时间戳），调用方应使用它刷新界面。
    public func savePersona(_ persona: PersonaEntity, isNew: Bool) async throws -> PersonaEntity {
        let body = Self.personaBody(persona, includeID: !isNew)
        let envelope = try await client.savePersona(body)
        guard let object = envelope.persona else {
            throw ServerWriteError.invalidResponse("缺少 persona")
        }
        let existing = ((try? stores.personas.byId(persona.id)) ?? nil) ?? persona
        let saved = try Self.persona(from: object, existing: existing)
        do {
            try stores.personas.upsert(saved)
        } catch {
            throw ServerWriteError.cacheUpdateFailed(error.localizedDescription)
        }
        return saved
    }

    /// 删除人格：服务端删成功后再走本地级联删除。
    ///
    /// - Returns: 本地级联删除的结果（删默认人格时的接替、`activePersonaId` 是否失效）。
    @discardableResult
    public func deletePersona(
        id: String,
        activePersonaId: String?
    ) async throws -> PersonaDeletion.Outcome {
        _ = try await client.deletePersona(id: id)
        let outcome = PersonaDeletion.delete(
            personaId: id,
            stores: stores,
            memoryRepository: memoryRepository,
            activePersonaId: activePersonaId
        )
        guard outcome.personaDeleted else {
            throw ServerWriteError.cacheUpdateFailed("本地没有删掉这个人格")
        }
        return outcome
    }

    // MARK: - 记忆

    /// 新建/更新一条记忆（服务端只认 content/importance/tags；其余本地列保留）。
    public func saveMemory(_ memory: MemoryEntity, isNew: Bool) async throws -> MemoryEntity {
        let envelope = try await client.saveMemory(
            personaId: memory.personaId,
            body: Self.memoryBody(memory, includeID: !isNew)
        )
        guard let object = envelope.memory else {
            throw ServerWriteError.invalidResponse("缺少 memory")
        }
        let existing = ((try? stores.memories.byId(memory.id)) ?? nil) ?? memory
        let saved = try Self.memory(from: object, existing: existing, personaId: memory.personaId)
        do {
            try stores.memories.upsert(saved)
            // 倒排/向量索引跟着正文走（与 MemoryRepository 的写入路径同一套维护）。
            memoryRepository.reindexTerms(memoryId: saved.id)
            memoryRepository.vectorIndex.upsert(memoryId: saved.id, content: saved.content)
        } catch {
            throw ServerWriteError.cacheUpdateFailed(error.localizedDescription)
        }
        return saved
    }

    /// 删除一条记忆：服务端删成功后再删本地（连带索引）。
    public func deleteMemory(id: String) async throws {
        _ = try await client.deleteMemory(id: id)
        memoryRepository.deleteById(id)
        guard ((try? stores.memories.byId(id)) ?? nil) == nil else {
            throw ServerWriteError.cacheUpdateFailed("本地没有删掉这条记忆")
        }
    }

    /// 从服务端拉一次记忆并写进本地缓存（服务端模式下打开记忆页/详情页时用）。
    ///
    /// 只覆盖服务端认识的列（content/importance/tags/id/时间），本地列（pinned/drawer/
    /// status/strength/sourceQuote…）保留 —— 它们是本机缓存自己的元数据，
    /// 服务端的记忆模型里没有（见 `CrudRoutes.memoryFromRequest`）。
    ///
    /// - Returns: 这次写进缓存的条数（0 也可能是「服务端确实没有记忆」）。
    @discardableResult
    public func refreshMemories(personaId: String, limit: Int = 500) async throws -> Int {
        let response = try await client.memories(personaId: personaId, limit: limit)
        let records = response["memories"]?.arrayValue?.compactMap(\.objectValue) ?? []
        var written = 0
        for object in records {
            guard let id = object["id"]?.stringValue, !id.isEmpty else { continue }
            let existing = (try? stores.memories.byId(id)) ?? nil
            let fallback = MemoryEntity(id: id, personaId: personaId, content: "")
            let memory = try Self.memory(
                from: object,
                existing: existing ?? fallback,
                personaId: personaId
            )
            try stores.memories.upsert(memory)
            memoryRepository.reindexTerms(memoryId: id)
            memoryRepository.vectorIndex.upsert(memoryId: id, content: memory.content)
            written += 1
        }
        return written
    }

    /// 从服务端拉一次预设并写进本地缓存（服务端模式下打开预设列表时用）。
    @discardableResult
    public func refreshPresets() async throws -> Int {
        let records = try await client.presets()
        var written = 0
        for object in records {
            guard let id = object["id"]?.stringValue, !id.isEmpty else { continue }
            let existing = (try? stores.presets.byId(id)) ?? nil
            let fallback = PersonaPresetEntity(id: id, name: object["name"]?.stringValue ?? "")
            let preset = try Self.preset(from: object, existing: existing ?? fallback)
            try stores.presets.upsert(preset)
            written += 1
        }
        return written
    }

    /// 手动记一条：先 POST 新建，再按服务端返回的 id 写本地缓存。
    ///
    /// 与 `MemoryRepository.addDetailed` 的区别：本地模式那条会在本地生成 id；
    /// 服务端模式必须**先**让服务端生成 id，否则缓存里会有一条服务端不存在的记忆。
    public func addMemory(
        personaId: String,
        content: String,
        category: MemoryCategory,
        importance: Int,
        tags: [String] = []
    ) async throws -> MemoryEntity {
        let text = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "记忆内容不能为空")
        }
        let at = now()
        let draft = MemoryEntity(
            id: UUID().uuidString,
            personaId: personaId,
            content: text,
            category: category,
            importance: min(max(importance, 1), 5),
            createdAt: at,
            updatedAt: at,
            useCount: 1,
            tags: MemoryTags.encode(tags)
        )
        return try await saveMemory(draft, isNew: true)
    }

    // MARK: - 预设

    public func savePreset(_ preset: PersonaPresetEntity, isNew: Bool) async throws -> PersonaPresetEntity {
        let envelope = try await client.savePreset(Self.presetBody(preset, includeID: !isNew))
        guard let object = envelope.preset else {
            throw ServerWriteError.invalidResponse("缺少 preset")
        }
        let existing = ((try? stores.presets.byId(preset.id)) ?? nil) ?? preset
        let saved = try Self.preset(from: object, existing: existing)
        do {
            try stores.presets.upsert(saved)
        } catch {
            throw ServerWriteError.cacheUpdateFailed(error.localizedDescription)
        }
        return saved
    }

    public func deletePreset(id: String) async throws {
        _ = try await client.deletePreset(id: id)
        do {
            try stores.presets.delete(id: id)
        } catch {
            throw ServerWriteError.cacheUpdateFailed(error.localizedDescription)
        }
    }

    // MARK: - 设置

    /// 把设置的白名单部分推给服务端；成功后调用方再写本机（UserDefaults）。
    ///
    /// 服务端会忽略白名单外的键（包括任何密钥形状的键），所以这里传整份也不会泄密。
    @discardableResult
    public func saveSettings(_ settings: AppSettings) async throws -> [String: JSONValue] {
        try await client.updateSettings(Self.settingsBody(settings))
    }

    // MARK: - 备份

    /// 导出：取服务端的备份对象（客户端不再自己拼一份本机备份）。
    public func exportBackup() async throws -> String {
        try await client.backupJSON()
    }

    /// 导入：先让服务端接（merge/replace），成功后把同一份 JSON 导进本地缓存。
    ///
    /// 本地导入沿用 `BackupService.importBackup`（事务、媒体落盘、索引重建都在那里）；
    /// 服务端成功而本地失败时抛 `cacheUpdateFailed` —— 服务端已经接回来了，
    /// 下次同步会补齐本地缓存，界面要如实说明这一点。
    public func importBackup(text: String, mode: ImportMode) async throws -> ImportSummary {
        let serverResult = try await client.importBackup(text: text, mode: mode)
        guard let backup else {
            throw ServerWriteError.localCacheUnavailable
        }
        do {
            return try backup.importBackup(text: text, mode: mode)
        } catch {
            throw ServerWriteError.cacheUpdateFailed(error.localizedDescription)
        }
    }

    /// 服务端导入的计数（调试/日志用；导入摘要以本地 `ImportSummary` 为准）。
    public func serverImportCounts(text: String, mode: ImportMode) async throws -> ServerBackupImportResult {
        try await client.importBackup(text: text, mode: mode)
    }

    // MARK: - 线上 body 构造

    /// 人格 body：服务端不认识但必须保留的列全部带上（它就是 `extraFields`）。
    static func personaBody(_ persona: PersonaEntity, includeID: Bool) -> [String: JSONValue] {
        var body: [String: JSONValue] = [:]
        if includeID { body["id"] = .string(persona.id) }
        body["name"] = .string(persona.name)
        body["description"] = .string(persona.description)
        body["isDefault"] = .bool(persona.isDefault)
        body["prompt"] = .string(persona.prompt)
        body["category"] = .string(persona.category)
        body["version"] = .int(Int64(persona.version))
        body["traits"] = .array(persona.traits.map { .string($0) })
        body["likes"] = .array(persona.likes.map { .string($0) })
        body["behaviors"] = .array(persona.behaviors.map { .string($0) })
        body["proactiveLevel"] = .int(Int64(persona.proactiveLevel))
        body["memoryRetention"] = .int(Int64(persona.memoryRetention))
        body["extractionEvery"] = .int(Int64(persona.extractionEvery))
        body["sleepEnabled"] = .bool(persona.sleepEnabled)
        body["wakeThreshold"] = .int(Int64(persona.wakeThreshold))
        body["dndOnSleep"] = .bool(persona.dndOnSleep)
        body["avatarSeed"] = .int(Int64(persona.avatarSeed))
        // 可空字段用 JSON null：服务端会把它们原样放进 extras（显式 null 比「缺键」更好读）。
        body["worldJson"] = persona.worldJson.map(JSONValue.string) ?? .null
        body["speechStyleJson"] = persona.speechStyleJson.map(JSONValue.string) ?? .null
        body["activityJson"] = persona.activityJson.map(JSONValue.string) ?? .null
        body["personaCardJson"] = persona.personaCardJson.map(JSONValue.string) ?? .null
        body["translationJson"] = persona.translationJson.map(JSONValue.string) ?? .null
        body["translationLang"] = persona.translationLang.map(JSONValue.string) ?? .null
        body["backstory"] = persona.backstory.map(JSONValue.string) ?? .null
        body["quirks"] = persona.quirks.map(JSONValue.string) ?? .null
        body["defaultApiConfigId"] = persona.defaultApiConfigId.map(JSONValue.string) ?? .null
        body["modelOverride"] = persona.modelOverride.map(JSONValue.string) ?? .null
        body["quietSleepMinutes"] = persona.quietSleepMinutes.map { .int(Int64($0)) } ?? .null
        body["quietWakeMinutes"] = persona.quietWakeMinutes.map { .int(Int64($0)) } ?? .null
        body["mood"] = persona.mood.map(JSONValue.string) ?? .null
        body["moodIntensity"] = persona.moodIntensity.map { .int(Int64($0)) } ?? .null
        body["moodReason"] = persona.moodReason.map(JSONValue.string) ?? .null
        body["moodUpdatedAt"] = persona.moodUpdatedAt.map { .int($0) } ?? .null
        body["moodNudgedAt"] = persona.moodNudgedAt.map { .int($0) } ?? .null
        return body
    }

    static func memoryBody(_ memory: MemoryEntity, includeID: Bool) -> [String: JSONValue] {
        var body: [String: JSONValue] = [:]
        if includeID { body["id"] = .string(memory.id) }
        body["content"] = .string(memory.content)
        // 服务端用 0–100，本地用 1–5：换算保持单调（20 分 = 1 星）。
        body["importance"] = .int(Int64(min(max(memory.importance, 1), 5) * 20))
        body["tags"] = .array(MemoryTags.decode(memory.tags).map { .string($0) })
        return body
    }

    static func presetBody(_ preset: PersonaPresetEntity, includeID: Bool) -> [String: JSONValue] {
        var body: [String: JSONValue] = [:]
        if includeID { body["id"] = .string(preset.id) }
        body["name"] = .string(preset.name)
        body["description"] = .string(preset.description)
        body["prompt"] = .string(preset.prompt)
        body["category"] = .string(preset.category)
        body["avatarSeed"] = .int(Int64(preset.avatarSeed))
        body["worldJson"] = preset.worldJson.map(JSONValue.string) ?? .null
        body["speechStyleJson"] = preset.speechStyleJson.map(JSONValue.string) ?? .null
        return body
    }

    static func settingsBody(_ settings: AppSettings) -> [String: JSONValue] {
        var body: [String: JSONValue] = [
            "themeMode": .string(settings.themeMode.stored),
            "accentColor": .int(settings.accentColor),
            "fontScale": .double(Double(settings.fontScale)),
            "bubbleStyle": .string(settings.bubbleStyle.stored),
            "animationsEnabled": .bool(settings.animationsEnabled),
            "hapticsEnabled": .bool(settings.hapticsEnabled),
            "agentLoopEnabled": .bool(settings.agentLoopEnabled),
            "autoTranslatePersona": .bool(settings.autoTranslatePersona)
        ]
        body["chatBackgroundColor"] = settings.chatBackgroundColor.map { .int($0) } ?? .null
        body["activePersonaId"] = settings.activePersonaId.map(JSONValue.string) ?? .null
        return body
    }

    // MARK: - 线上对象 → 本地实体

    static func persona(from object: [String: JSONValue], existing: PersonaEntity) throws -> PersonaEntity {
        do {
            var persona = existing
            persona.id = try object.text("id", existing.id)
            persona.name = try object.text("name", existing.name)
            persona.description = try object.text("description", existing.description)
            persona.isDefault = try object.bool("isDefault", existing.isDefault)
            persona.createdAt = try object.optionalInt64("createdAt") ?? existing.createdAt
            persona.updatedAt = try object.optionalInt64("updatedAt") ?? existing.updatedAt
            persona.prompt = try object.text("prompt", existing.prompt)
            persona.category = try object.text("category", existing.category)
            persona.version = try object.int("version", existing.version)
            persona.traits = try object.stringList("traits").isEmpty
                ? existing.traits : object.stringList("traits")
            persona.likes = try object.stringList("likes").isEmpty
                ? existing.likes : object.stringList("likes")
            persona.behaviors = try object.stringList("behaviors").isEmpty
                ? existing.behaviors : object.stringList("behaviors")
            persona.backstory = PromptGuidanceJSON.clean(try object.optionalText("backstory"))
                ?? existing.backstory
            persona.quirks = PromptGuidanceJSON.clean(try object.optionalText("quirks")) ?? existing.quirks
            persona.personaCardJson = PromptGuidanceJSON.clean(try object.optionalText("personaCardJson"))
                ?? existing.personaCardJson
            persona.worldJson = PromptGuidanceJSON.clean(try object.optionalText("worldJson"))
                ?? existing.worldJson
            persona.speechStyleJson = PromptGuidanceJSON.clean(try object.optionalText("speechStyleJson"))
                ?? existing.speechStyleJson
            persona.activityJson = PromptGuidanceJSON.clean(try object.optionalText("activityJson"))
                ?? existing.activityJson
            persona.translationJson = PromptGuidanceJSON.clean(try object.optionalText("translationJson"))
                ?? existing.translationJson
            persona.translationLang = try object.optionalText("translationLang") ?? existing.translationLang
            persona.defaultApiConfigId = try object.optionalText("defaultApiConfigId")
                ?? existing.defaultApiConfigId
            persona.modelOverride = try object.optionalText("modelOverride") ?? existing.modelOverride
            persona.quietSleepMinutes = try object.optionalInt("quietSleepMinutes")
                ?? existing.quietSleepMinutes
            persona.quietWakeMinutes = try object.optionalInt("quietWakeMinutes")
                ?? existing.quietWakeMinutes
            persona.proactiveLevel = PersonaFieldLimits.clampLevel(
                try object.optionalInt("proactiveLevel") ?? existing.proactiveLevel
            )
            persona.memoryRetention = PersonaFieldLimits.clampLevel(
                try object.optionalInt("memoryRetention") ?? existing.memoryRetention
            )
            persona.extractionEvery = PersonaFieldLimits.clampExtractionEvery(
                try object.optionalInt("extractionEvery") ?? existing.extractionEvery
            )
            persona.sleepEnabled = try object.bool("sleepEnabled", existing.sleepEnabled)
            persona.wakeThreshold = PersonaFieldLimits.clampWakeThreshold(
                try object.optionalInt("wakeThreshold") ?? existing.wakeThreshold
            )
            persona.dndOnSleep = try object.bool("dndOnSleep", existing.dndOnSleep)
            persona.mood = try object.optionalText("mood") ?? existing.mood
            persona.moodIntensity = try object.optionalInt("moodIntensity") ?? existing.moodIntensity
            persona.moodReason = try object.optionalText("moodReason") ?? existing.moodReason
            persona.moodUpdatedAt = try object.optionalInt64("moodUpdatedAt") ?? existing.moodUpdatedAt
            persona.moodNudgedAt = try object.optionalInt64("moodNudgedAt") ?? existing.moodNudgedAt
            // 头像：服务端的 `avatarUrl` 是 `/v1/media/<id>`，本地 `avatarUri` 是 `file://`，
            // 两者不是同一个东西。M5 先把「头像上传/下发」留给后续（见交付报告），
            // 所以这里保留本地头像，不被服务端值覆盖成坏引用。
            return persona
        } catch {
            throw ServerWriteError.invalidResponse("人格字段类型不对：\(error.localizedDescription)")
        }
    }

    static func memory(
        from object: [String: JSONValue],
        existing: MemoryEntity,
        personaId: String
    ) throws -> MemoryEntity {
        do {
            var memory = existing
            memory.id = try object.text("id", existing.id)
            memory.personaId = try object.text("personaId", personaId.isEmpty ? existing.personaId : personaId)
            memory.content = try object.text("content", existing.content)
            let serverImportance = try object.optionalInt("importance") ?? (memory.importance * 20)
            memory.importance = min(max(Int((Double(serverImportance) / 20.0).rounded()), 1), 5)
            let tags = (object["tags"]?.arrayValue ?? []).compactMap(\.stringValue)
            if !tags.isEmpty || existing.tags != nil {
                memory.tags = MemoryTags.encode(tags)
            }
            memory.createdAt = try object.optionalInt64("createdAt") ?? existing.createdAt
            memory.updatedAt = try object.optionalInt64("updatedAt") ?? existing.updatedAt
            return memory
        } catch {
            throw ServerWriteError.invalidResponse("记忆字段类型不对：\(error.localizedDescription)")
        }
    }

    static func preset(from object: [String: JSONValue], existing: PersonaPresetEntity) throws -> PersonaPresetEntity {
        do {
            var preset = existing
            preset.id = try object.text("id", existing.id)
            preset.name = try object.text("name", existing.name)
            preset.description = try object.text("description", existing.description)
            preset.prompt = try object.text("prompt", existing.prompt)
            preset.category = try object.text("category", existing.category)
            preset.avatarSeed = try object.int("avatarSeed", existing.avatarSeed)
            preset.worldJson = PromptGuidanceJSON.clean(try object.optionalText("worldJson"))
                ?? existing.worldJson
            preset.speechStyleJson = PromptGuidanceJSON.clean(try object.optionalText("speechStyleJson"))
                ?? existing.speechStyleJson
            preset.createdAt = try object.optionalInt64("createdAt") ?? existing.createdAt
            preset.updatedAt = try object.optionalInt64("updatedAt") ?? existing.updatedAt
            return preset
        } catch {
            throw ServerWriteError.invalidResponse("预设字段类型不对：\(error.localizedDescription)")
        }
    }
}
