import Foundation

// ---------------------------------------------------------------------------
// 「把他带走」的那份文件 —— iOS 侧的类型定义。
//
// 权威定义在 Android：app/src/main/java/com/myfriend/app/domain/BackupCodec.kt。
// 这里逐字段照抄，包括**默认值**与**可空性**，因为两边读同一份 JSON：
//
// - 字段名逐字相同（CodingKeys 显式列出，属性改名不会悄悄改掉格式）；
// - 缺字段用默认值（老备份没有 sessions[].summary 三个字段时按「没压过」处理）；
// - 未知字段忽略（新版本备份给旧 App 时要能读）；
// - 值为 nil 的字段不写进 JSON（Android 用 explicitNulls = false，文件更小、也更好读）。
//
// 两条硬规矩，与 Android 一致：
// 1. 密钥永远不进备份：结构里根本没有 `apiKeyCipher` / `webToolsApiKeyCipher` /
//    `customHeadersJson`；`BackupCodec.containsSecretField` 是导出前的最后一道报警器。
// 2. 读坏数据不许崩：`BackupCodec.decode` 永远返回 `BackupDecodeResult`。
// ---------------------------------------------------------------------------

/// 写在文件里的格式标记：拿错文件时能早点说「这不是他的备份」。
public let backupFormatName: String = "myfriend.backup"

/// 当前格式版本。比这个高的备份明确拒绝，而不是半懂不懂地写库。
///
/// v2（1.9）：
/// - `personas` 增加「细分字段」9+2 个（性格 / 喜欢 / 习惯 / 过去 / 设定 / 主动程度 / 记性 /
///   抽记忆间隔 / 会不会主动睡觉 / 唤醒阈值 / 睡前免打扰）；
/// - `sessions` 增加两个覆盖列，并且**每个人格只有一条**（导入时同一人格多条只留最新一条）；
/// - `messages` 增加 `readAt`；
/// - `settings` 删掉记忆开关、回复节奏、额度与单价、每日主动次数；
/// - `apiConfigs` 删掉 Harness 三列。
public let backupFormatVersion: Int = 2

// MARK: - 顶层

/// 备份文件的顶层结构。
public struct BackupBundle: Equatable, Sendable, Encodable {
    public var format: String
    public var formatVersion: Int
    public var appVersion: String
    public var exportedAt: Int64
    /// 常量 true，写给「打开这份 JSON 的人」看：里面没有他的 Key。
    /// 这不是开关，只是一句声明 —— 真正的保证是下面的结构里根本没有密钥字段。
    public var apiKeysOmitted: Bool
    public var personas: [BackupPersona]
    public var memories: [BackupMemory]
    public var sessions: [BackupSession]
    public var messages: [BackupMessage]
    public var presets: [BackupPreset]
    public var proactiveRules: [BackupProactiveRule]
    /// 他「想在某个时间段找你」的念头；过期就作废，属于可选内容。
    public var agentIntents: [BackupAgentIntent]
    /// 他整理过记忆的留痕，用来保留「他最近整理过记忆」这条历史。
    public var memoryConsolidations: [BackupMemoryConsolidation]
    public var apiConfigs: [BackupApiConfig]
    /// 可移植的设置。为 nil 表示这份备份里没有设置这一段（老版本或手写的备份）：
    /// 接回来时设置页保持原样，绝不拿一份「没有设置的备份」把用户现在的偏好清成默认值。
    public var settings: BackupSettings?

    public init(
        format: String = backupFormatName,
        formatVersion: Int = backupFormatVersion,
        appVersion: String = "",
        exportedAt: Int64 = 0,
        apiKeysOmitted: Bool = true,
        personas: [BackupPersona] = [],
        memories: [BackupMemory] = [],
        sessions: [BackupSession] = [],
        messages: [BackupMessage] = [],
        presets: [BackupPreset] = [],
        proactiveRules: [BackupProactiveRule] = [],
        agentIntents: [BackupAgentIntent] = [],
        memoryConsolidations: [BackupMemoryConsolidation] = [],
        apiConfigs: [BackupApiConfig] = [],
        settings: BackupSettings? = nil
    ) {
        self.format = format
        self.formatVersion = formatVersion
        self.appVersion = appVersion
        self.exportedAt = exportedAt
        self.apiKeysOmitted = apiKeysOmitted
        self.personas = personas
        self.memories = memories
        self.sessions = sessions
        self.messages = messages
        self.presets = presets
        self.proactiveRules = proactiveRules
        self.agentIntents = agentIntents
        self.memoryConsolidations = memoryConsolidations
        self.apiConfigs = apiConfigs
        self.settings = settings
    }

    enum CodingKeys: String, CodingKey {
        case format, formatVersion, appVersion, exportedAt, apiKeysOmitted
        case personas, memories, sessions, messages, presets
        case proactiveRules, agentIntents, memoryConsolidations, apiConfigs, settings
    }
}

// MARK: - 各段的传输结构
//
// 全部字段都有默认值，所以缺字段不会炸；未知字段解码时忽略。

/// 人格。人设卡 JSON、作息、心情都在这里 —— 这是他「是谁」的全部。
public struct BackupPersona: Equatable, Sendable, Encodable {
    public var id: String
    public var name: String
    public var description: String
    public var avatarUri: String?
    public var avatarSeed: Int
    public var prompt: String
    public var category: String
    public var version: Int
    public var defaultApiConfigId: String?
    public var modelOverride: String?
    public var isDefault: Bool
    public var createdAt: Int64
    public var updatedAt: Int64
    /// 结构化人设卡（PersonaCardCodec 的 JSON），原样带走。
    public var personaCardJson: String?
    /// 他自己安排的作息：几点睡、几点醒（距零点分钟数），空表示不设。
    public var quietSleepMinutes: Int?
    public var quietWakeMinutes: Int?
    /// 此刻的心情：一个词 + 强度 + 原因 + 上次变化时间。
    public var mood: String?
    public var moodIntensity: Int?
    public var moodReason: String?
    public var moodUpdatedAt: Int64?
    /// 上次被引导着回看心情的时间，避免同一句话反复追问。
    public var moodNudgedAt: Int64?
    /// 1.9 的细分字段：性格 / 喜欢的事 / 行为方式（JSON 数组，空数组不写进文件）。
    public var traits: [String]
    public var likes: [String]
    public var behaviors: [String]
    /// 成长经历 / 历史。
    public var backstory: String?
    /// 个人设定：称呼、口头禅、忌讳。
    public var quirks: String?
    /// 主动程度 1–5 / 记性 1–5 / 每多少条消息抽一次记忆 3–12。
    public var proactiveLevel: Int
    public var memoryRetention: Int
    public var extractionEvery: Int
    /// 会不会主动睡觉 / 睡眠中被唤醒需要几条消息 / 睡前是否免打扰（模型可改）。
    public var sleepEnabled: Bool
    public var wakeThreshold: Int
    public var dndOnSleep: Bool

    public init(
        id: String = "",
        name: String = "",
        description: String = "",
        avatarUri: String? = nil,
        avatarSeed: Int = 0,
        prompt: String = "",
        category: String = "",
        version: Int = 1,
        defaultApiConfigId: String? = nil,
        modelOverride: String? = nil,
        isDefault: Bool = false,
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0,
        personaCardJson: String? = nil,
        quietSleepMinutes: Int? = nil,
        quietWakeMinutes: Int? = nil,
        mood: String? = nil,
        moodIntensity: Int? = nil,
        moodReason: String? = nil,
        moodUpdatedAt: Int64? = nil,
        moodNudgedAt: Int64? = nil,
        traits: [String] = [],
        likes: [String] = [],
        behaviors: [String] = [],
        backstory: String? = nil,
        quirks: String? = nil,
        proactiveLevel: Int = PersonaFieldLimits.proactiveLevelDefault,
        memoryRetention: Int = PersonaFieldLimits.memoryRetentionDefault,
        extractionEvery: Int = PersonaFieldLimits.extractionEveryDefault,
        sleepEnabled: Bool = true,
        wakeThreshold: Int = PersonaFieldLimits.wakeThresholdDefault,
        dndOnSleep: Bool = false
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.avatarUri = avatarUri
        self.avatarSeed = avatarSeed
        self.prompt = prompt
        self.category = category
        self.version = version
        self.defaultApiConfigId = defaultApiConfigId
        self.modelOverride = modelOverride
        self.isDefault = isDefault
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.personaCardJson = personaCardJson
        self.quietSleepMinutes = quietSleepMinutes
        self.quietWakeMinutes = quietWakeMinutes
        self.mood = mood
        self.moodIntensity = moodIntensity
        self.moodReason = moodReason
        self.moodUpdatedAt = moodUpdatedAt
        self.moodNudgedAt = moodNudgedAt
        self.traits = PersonaTags.normalize(traits, limit: PersonaFieldLimits.maxTraits)
        self.likes = PersonaTags.normalize(likes, limit: PersonaFieldLimits.maxLikes)
        self.behaviors = PersonaTags.normalize(behaviors, limit: PersonaFieldLimits.maxBehaviors)
        self.backstory = PersonaEntity.clean(backstory, limit: PersonaFieldLimits.backstoryMax)
        self.quirks = PersonaEntity.clean(quirks, limit: PersonaFieldLimits.quirksMax)
        self.proactiveLevel = PersonaFieldLimits.clampLevel(proactiveLevel)
        self.memoryRetention = PersonaFieldLimits.clampLevel(memoryRetention)
        self.extractionEvery = PersonaFieldLimits.clampExtractionEvery(extractionEvery)
        self.sleepEnabled = sleepEnabled
        self.wakeThreshold = PersonaFieldLimits.clampWakeThreshold(wakeThreshold)
        self.dndOnSleep = dndOnSleep
    }

    enum CodingKeys: String, CodingKey {
        case id, name, description, avatarUri, avatarSeed, prompt, category, version
        case defaultApiConfigId, modelOverride, isDefault, createdAt, updatedAt
        case personaCardJson, quietSleepMinutes, quietWakeMinutes
        case mood, moodIntensity, moodReason, moodUpdatedAt, moodNudgedAt
        case traits, likes, behaviors, backstory, quirks
        case proactiveLevel, memoryRetention, extractionEvery
        case sleepEnabled, wakeThreshold, dndOnSleep
    }
}

/// 一条长期记忆，连「它还剩下多少分量」一起带走。
public struct BackupMemory: Equatable, Sendable, Encodable {
    public var id: String
    public var personaId: String
    public var content: String
    public var category: String
    public var importance: Int
    public var pinned: Bool
    public var sourceSessionId: String?
    public var createdAt: Int64
    public var updatedAt: Int64
    public var lastUsedAt: Int64
    public var useCount: Int
    /// 记忆抽屉：SELF / USER / EVENT；空时按 category 推断。
    public var drawer: String?
    /// 触发这条记忆的原话片段，用于「这条记忆是怎么来的」。
    public var sourceQuote: String?
    /// ACTIVE 还在用 / DORMANT 沉下去了 / ARCHIVED 已归档（不再进上下文）。
    public var status: String
    /// 还剩多少分量：时间和整理都会让它变小，先掉到阈值以下的人先被归档。
    public var strength: Double
    public var archivedAt: Int64?
    public var consolidatedAt: Int64?
    /// 结构化标签（JSON 数组）：人、事、物、情绪。
    public var tags: String?
    /// 这件事发生的时间（不是被记住的时间）；推断不出来就空着。
    public var happenedAt: Int64?
    /// 回忆重构：第一次被写下来时的原文。内容会随回忆漂移，这里是锚点。
    public var originalContent: String?
    /// 被重新回忆过几次 —— 回忆本身会改写它。
    public var driftCount: Int

    public init(
        id: String = "",
        personaId: String = "",
        content: String = "",
        category: String = "OTHER",
        importance: Int = 50,
        pinned: Bool = false,
        sourceSessionId: String? = nil,
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0,
        lastUsedAt: Int64 = 0,
        useCount: Int = 0,
        drawer: String? = nil,
        sourceQuote: String? = nil,
        status: String = "ACTIVE",
        strength: Double = 1.0,
        archivedAt: Int64? = nil,
        consolidatedAt: Int64? = nil,
        tags: String? = nil,
        happenedAt: Int64? = nil,
        originalContent: String? = nil,
        driftCount: Int = 0
    ) {
        self.id = id
        self.personaId = personaId
        self.content = content
        self.category = category
        self.importance = importance
        self.pinned = pinned
        self.sourceSessionId = sourceSessionId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.lastUsedAt = lastUsedAt
        self.useCount = useCount
        self.drawer = drawer
        self.sourceQuote = sourceQuote
        self.status = status
        self.strength = strength
        self.archivedAt = archivedAt
        self.consolidatedAt = consolidatedAt
        self.tags = tags
        self.happenedAt = happenedAt
        self.originalContent = originalContent
        self.driftCount = driftCount
    }

    enum CodingKeys: String, CodingKey {
        case id, personaId, content, category, importance, pinned, sourceSessionId
        case createdAt, updatedAt, lastUsedAt, useCount, drawer, sourceQuote
        case status, strength, archivedAt, consolidatedAt, tags, happenedAt
        case originalContent, driftCount
    }
}

/// 一段对话（一个会话）。
public struct BackupSession: Equatable, Sendable, Encodable {
    public var id: String
    public var personaId: String
    public var apiConfigId: String?
    public var model: String
    public var title: String
    public var lastMessagePreview: String?
    public var lastMessageAt: Int64?
    public var unreadCount: Int
    public var pinned: Bool
    public var createdAt: Int64
    public var updatedAt: Int64
    public var lastPersonaVersion: Int
    public var syncState: String
    public var syncedPersonaVersion: Int?
    public var syncedMemoriesHash: String?
    public var serverSessionId: String?
    public var lastSyncError: String?
    public var lastSyncAt: Int64?
    public var lastMemoryExtractedCount: Int
    /// 长会话被收成的那段「他还记得的话」（1.7 的滚动摘要）。
    ///
    /// 换端接回来时要一起带走：不然在同一段对话里，他会突然把你说过的事忘掉。
    /// 老备份没有这三个字段，读出来是 nil，等同于「还没压过」，行为与 1.7 之前一致。
    public var summary: String?
    /// 摘要覆盖到哪条消息为止（该消息及更早的已被压缩）。
    public var summarizedUpTo: Int64?
    public var summaryAt: Int64?
    /// 1.9：会话级覆盖（空 = 继承人格）。
    public var memoryRetentionOverride: Int?
    public var extractionEveryOverride: Int?

    public init(
        id: String = "",
        personaId: String = "",
        apiConfigId: String? = nil,
        model: String = "",
        title: String = "",
        lastMessagePreview: String? = nil,
        lastMessageAt: Int64? = nil,
        unreadCount: Int = 0,
        pinned: Bool = false,
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0,
        lastPersonaVersion: Int = 0,
        syncState: String = "STATELESS",
        syncedPersonaVersion: Int? = nil,
        syncedMemoriesHash: String? = nil,
        serverSessionId: String? = nil,
        lastSyncError: String? = nil,
        lastSyncAt: Int64? = nil,
        lastMemoryExtractedCount: Int = 0,
        summary: String? = nil,
        summarizedUpTo: Int64? = nil,
        summaryAt: Int64? = nil,
        memoryRetentionOverride: Int? = nil,
        extractionEveryOverride: Int? = nil
    ) {
        self.id = id
        self.personaId = personaId
        self.apiConfigId = apiConfigId
        self.model = model
        self.title = title
        self.lastMessagePreview = lastMessagePreview
        self.lastMessageAt = lastMessageAt
        self.unreadCount = unreadCount
        self.pinned = pinned
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.lastPersonaVersion = lastPersonaVersion
        self.syncState = syncState
        self.syncedPersonaVersion = syncedPersonaVersion
        self.syncedMemoriesHash = syncedMemoriesHash
        self.serverSessionId = serverSessionId
        self.lastSyncError = lastSyncError
        self.lastSyncAt = lastSyncAt
        self.lastMemoryExtractedCount = lastMemoryExtractedCount
        self.summary = summary
        self.summarizedUpTo = summarizedUpTo
        self.summaryAt = summaryAt
        self.memoryRetentionOverride = memoryRetentionOverride.flatMap {
            PersonaFieldLimits.levelRange.contains($0) ? $0 : nil
        }
        self.extractionEveryOverride = extractionEveryOverride.flatMap {
            PersonaFieldLimits.extractionEveryRange.contains($0) ? $0 : nil
        }
    }

    enum CodingKeys: String, CodingKey {
        case id, personaId, apiConfigId, model, title, lastMessagePreview, lastMessageAt
        case unreadCount, pinned, createdAt, updatedAt, lastPersonaVersion, syncState
        case syncedPersonaVersion, syncedMemoriesHash, serverSessionId, lastSyncError
        case lastSyncAt, lastMemoryExtractedCount, summary, summarizedUpTo, summaryAt
        case memoryRetentionOverride, extractionEveryOverride
    }
}

/// 一句话。
///
/// `toolTraceJson` 原样带走（「他当时查了两个网页」这种痕迹也是相处的一部分）；
/// `attachmentsJson` 只留元数据，图片本体不导 —— 换机后 uri 已经指不到东西了。
public struct BackupMessage: Equatable, Sendable, Encodable {
    public var id: String
    public var sessionId: String
    public var personaId: String
    public var role: String
    public var content: String
    public var createdAt: Int64
    public var updatedAt: Int64
    public var status: String
    public var errorMessage: String?
    public var model: String?
    public var edited: Bool
    public var attachmentsJson: String?
    public var toolTraceJson: String?
    /// 1.9：用户消息被模型看到的时刻（空 = 还没被看到）。
    public var readAt: Int64?

    public init(
        id: String = "",
        sessionId: String = "",
        personaId: String = "",
        role: String = "USER",
        content: String = "",
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0,
        status: String = "COMPLETE",
        errorMessage: String? = nil,
        model: String? = nil,
        edited: Bool = false,
        attachmentsJson: String? = nil,
        toolTraceJson: String? = nil,
        readAt: Int64? = nil
    ) {
        self.id = id
        self.sessionId = sessionId
        self.personaId = personaId
        self.role = role
        self.content = content
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.status = status
        self.errorMessage = errorMessage
        self.model = model
        self.edited = edited
        self.attachmentsJson = attachmentsJson
        self.toolTraceJson = toolTraceJson
        self.readAt = readAt
    }

    enum CodingKeys: String, CodingKey {
        case id, sessionId, personaId, role, content, createdAt, updatedAt, status
        case errorMessage, model, edited, attachmentsJson, toolTraceJson, readAt
    }
}

public struct BackupPreset: Equatable, Sendable, Encodable {
    public var id: String
    public var name: String
    public var description: String
    public var prompt: String
    public var category: String
    public var avatarUri: String?
    public var avatarSeed: Int
    public var createdAt: Int64
    public var updatedAt: Int64

    public init(
        id: String = "",
        name: String = "",
        description: String = "",
        prompt: String = "",
        category: String = "",
        avatarUri: String? = nil,
        avatarSeed: Int = 0,
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.prompt = prompt
        self.category = category
        self.avatarUri = avatarUri
        self.avatarSeed = avatarSeed
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey {
        case id, name, description, prompt, category, avatarUri, avatarSeed, createdAt, updatedAt
    }
}

/// 他给自己定的主动节奏（每天几点 / 每几小时）。
public struct BackupProactiveRule: Equatable, Sendable, Encodable {
    public var id: String
    public var personaId: String
    public var sessionId: String?
    public var kind: String
    public var timeOfDayMinutes: Int?
    public var intervalMinutes: Int?
    public var hint: String
    public var note: String
    public var enabled: Bool
    public var pending: Bool
    public var source: String
    public var nextTriggerAt: Int64
    public var lastTriggeredAt: Int64?
    public var lastError: String?
    public var createdAt: Int64
    public var updatedAt: Int64

    public init(
        id: String = "",
        personaId: String = "",
        sessionId: String? = nil,
        kind: String = "DAILY",
        timeOfDayMinutes: Int? = nil,
        intervalMinutes: Int? = nil,
        hint: String = "",
        note: String = "",
        enabled: Bool = true,
        pending: Bool = false,
        source: String = "MANUAL",
        nextTriggerAt: Int64 = 0,
        lastTriggeredAt: Int64? = nil,
        lastError: String? = nil,
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0
    ) {
        self.id = id
        self.personaId = personaId
        self.sessionId = sessionId
        self.kind = kind
        self.timeOfDayMinutes = timeOfDayMinutes
        self.intervalMinutes = intervalMinutes
        self.hint = hint
        self.note = note
        self.enabled = enabled
        self.pending = pending
        self.source = source
        self.nextTriggerAt = nextTriggerAt
        self.lastTriggeredAt = lastTriggeredAt
        self.lastError = lastError
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey {
        case id, personaId, sessionId, kind, timeOfDayMinutes, intervalMinutes
        case hint, note, enabled, pending, source, nextTriggerAt, lastTriggeredAt
        case lastError, createdAt, updatedAt
    }
}

public struct BackupAgentIntent: Equatable, Sendable, Encodable {
    public var id: String
    public var personaId: String
    public var sessionId: String?
    public var kind: String
    public var earliestAt: Int64
    public var latestAt: Int64
    public var note: String
    public var hint: String
    public var status: String
    public var ruleId: String?
    public var createdAt: Int64
    public var sentAt: Int64?
    public var lastError: String?

    public init(
        id: String = "",
        personaId: String = "",
        sessionId: String? = nil,
        kind: String = "REACH_OUT",
        earliestAt: Int64 = 0,
        latestAt: Int64 = 0,
        note: String = "",
        hint: String = "",
        status: String = "PENDING",
        ruleId: String? = nil,
        createdAt: Int64 = 0,
        sentAt: Int64? = nil,
        lastError: String? = nil
    ) {
        self.id = id
        self.personaId = personaId
        self.sessionId = sessionId
        self.kind = kind
        self.earliestAt = earliestAt
        self.latestAt = latestAt
        self.note = note
        self.hint = hint
        self.status = status
        self.ruleId = ruleId
        self.createdAt = createdAt
        self.sentAt = sentAt
        self.lastError = lastError
    }

    enum CodingKeys: String, CodingKey {
        case id, personaId, sessionId, kind, earliestAt, latestAt, note, hint
        case status, ruleId, createdAt, sentAt, lastError
    }
}

public struct BackupMemoryConsolidation: Equatable, Sendable, Encodable {
    public var id: String
    public var personaId: String
    public var at: Int64
    public var kept: Int
    public var merged: Int
    public var archived: Int
    public var forgotten: Int
    public var summary: String
    public var modelName: String?

    public init(
        id: String = "",
        personaId: String = "",
        at: Int64 = 0,
        kept: Int = 0,
        merged: Int = 0,
        archived: Int = 0,
        forgotten: Int = 0,
        summary: String = "",
        modelName: String? = nil
    ) {
        self.id = id
        self.personaId = personaId
        self.at = at
        self.kept = kept
        self.merged = merged
        self.archived = archived
        self.forgotten = forgotten
        self.summary = summary
        self.modelName = modelName
    }

    enum CodingKeys: String, CodingKey {
        case id, personaId, at, kept, merged, archived, forgotten, summary, modelName
    }
}

/// API 配置里**可以带走的那部分**。
///
/// 刻意只有白名单字段：没有 `apiKeyCipher`、没有 `webToolsApiKeyCipher`、
/// 也没有 `customHeadersJson`（自定义 Header 里可能藏着 `Authorization: Bearer sk-…`）。
/// 接回来以后：地址、模型、开关都在，Key 需要在这台手机上重新填一次。
public struct BackupApiConfig: Equatable, Sendable, Encodable {
    public var id: String
    public var name: String
    public var provider: String
    public var baseUrl: String
    public var model: String
    public var allowInsecureHttp: Bool
    public var temperature: Double?
    public var topP: Double?
    public var maxOutputTokens: Int?
    public var frequencyPenalty: Double?
    public var presencePenalty: Double?
    public var webToolsEnabled: Bool
    public var webToolsBaseUrl: String
    public var createdAt: Int64
    public var updatedAt: Int64

    public init(
        id: String = "",
        name: String = "",
        provider: String = "OPENAI",
        baseUrl: String = "",
        model: String = "",
        allowInsecureHttp: Bool = false,
        temperature: Double? = nil,
        topP: Double? = nil,
        maxOutputTokens: Int? = nil,
        frequencyPenalty: Double? = nil,
        presencePenalty: Double? = nil,
        webToolsEnabled: Bool = false,
        webToolsBaseUrl: String = "",
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0
    ) {
        self.id = id
        self.name = name
        self.provider = provider
        self.baseUrl = baseUrl
        self.model = model
        self.allowInsecureHttp = allowInsecureHttp
        self.temperature = temperature
        self.topP = topP
        self.maxOutputTokens = maxOutputTokens
        self.frequencyPenalty = frequencyPenalty
        self.presencePenalty = presencePenalty
        self.webToolsEnabled = webToolsEnabled
        self.webToolsBaseUrl = webToolsBaseUrl
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey {
        case id, name, provider, baseUrl, model
        case allowInsecureHttp, temperature, topP, maxOutputTokens, frequencyPenalty
        case presencePenalty, webToolsEnabled, webToolsBaseUrl, createdAt, updatedAt
    }
}

/// 可移植的设置。
///
/// 故意不在这里的：`chatBackgroundImageUri`（设备上的私有路径，换机无意义）、
/// `backgroundTipDismissed`（纯本地的引导状态）、以及任何跟密钥有关的东西。
public struct BackupSettings: Equatable, Sendable, Encodable {
    public var themeMode: String
    /// ARGB，Long。iOS 侧用 Int64 接，避免 UInt32 转来转去丢符号位。
    public var accentColor: Int64
    public var fontScale: Float
    public var bubbleStyle: String
    public var animationsEnabled: Bool
    public var hapticsEnabled: Bool
    public var chatBackgroundColor: Int64?
    public var agentLoopEnabled: Bool
    public var activePersonaId: String?

    public init(
        themeMode: String = "SYSTEM",
        accentColor: Int64 = 0xFFC76F4E,
        fontScale: Float = 1,
        bubbleStyle: String = "SOFT",
        animationsEnabled: Bool = true,
        hapticsEnabled: Bool = true,
        chatBackgroundColor: Int64? = nil,
        agentLoopEnabled: Bool = true,
        activePersonaId: String? = nil
    ) {
        self.themeMode = themeMode
        self.accentColor = accentColor
        self.fontScale = fontScale
        self.bubbleStyle = bubbleStyle
        self.animationsEnabled = animationsEnabled
        self.hapticsEnabled = hapticsEnabled
        self.chatBackgroundColor = chatBackgroundColor
        self.agentLoopEnabled = agentLoopEnabled
        self.activePersonaId = activePersonaId
    }

    enum CodingKeys: String, CodingKey {
        case themeMode, accentColor, fontScale, bubbleStyle, animationsEnabled
        case hapticsEnabled, chatBackgroundColor
        case agentLoopEnabled, activePersonaId
    }
}

// ---------------------------------------------------------------------------
// JSON 解码：值树 → 结构体
//
// 每条记录的 init 都可能抛错（字段类型写错了），由 BackupCodec 换算成
// 「这一条读不出来，已跳过」。缺失字段走默认值，未知字段自然被忽略。
// ---------------------------------------------------------------------------

extension BackupPersona {
    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            name: try json.text("name"),
            description: try json.text("description"),
            avatarUri: try json.optionalText("avatarUri"),
            avatarSeed: try json.int("avatarSeed"),
            prompt: try json.text("prompt"),
            category: try json.text("category"),
            version: try json.int("version", 1),
            defaultApiConfigId: try json.optionalText("defaultApiConfigId"),
            modelOverride: try json.optionalText("modelOverride"),
            isDefault: try json.bool("isDefault"),
            createdAt: try json.int64("createdAt"),
            updatedAt: try json.int64("updatedAt"),
            personaCardJson: try json.optionalText("personaCardJson"),
            quietSleepMinutes: try json.optionalInt("quietSleepMinutes"),
            quietWakeMinutes: try json.optionalInt("quietWakeMinutes"),
            mood: try json.optionalText("mood"),
            moodIntensity: try json.optionalInt("moodIntensity"),
            moodReason: try json.optionalText("moodReason"),
            moodUpdatedAt: try json.optionalInt64("moodUpdatedAt"),
            moodNudgedAt: try json.optionalInt64("moodNudgedAt"),
            // 列表字段用永不抛错的宽容解码：为了一个标签丢掉整个人格是错的取舍。
            traits: json.stringList("traits", limit: PersonaFieldLimits.maxTraits),
            likes: json.stringList("likes", limit: PersonaFieldLimits.maxLikes),
            behaviors: json.stringList("behaviors", limit: PersonaFieldLimits.maxBehaviors),
            backstory: try json.optionalText("backstory"),
            quirks: try json.optionalText("quirks"),
            proactiveLevel: try json.optionalInt("proactiveLevel")
                ?? PersonaFieldLimits.proactiveLevelDefault,
            memoryRetention: try json.optionalInt("memoryRetention")
                ?? PersonaFieldLimits.memoryRetentionDefault,
            extractionEvery: try json.optionalInt("extractionEvery")
                ?? PersonaFieldLimits.extractionEveryDefault,
            sleepEnabled: try json.bool("sleepEnabled", true),
            wakeThreshold: try json.optionalInt("wakeThreshold")
                ?? PersonaFieldLimits.wakeThresholdDefault,
            dndOnSleep: try json.bool("dndOnSleep", false)
        )
    }
}

extension BackupMemory {
    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            personaId: try json.text("personaId"),
            content: try json.text("content"),
            category: try json.text("category", "OTHER"),
            importance: try json.int("importance", 50),
            pinned: try json.bool("pinned"),
            sourceSessionId: try json.optionalText("sourceSessionId"),
            createdAt: try json.int64("createdAt"),
            updatedAt: try json.int64("updatedAt"),
            lastUsedAt: try json.int64("lastUsedAt"),
            useCount: try json.int("useCount"),
            drawer: try json.optionalText("drawer"),
            sourceQuote: try json.optionalText("sourceQuote"),
            status: try json.text("status", "ACTIVE"),
            strength: try json.double("strength", 1.0),
            archivedAt: try json.optionalInt64("archivedAt"),
            consolidatedAt: try json.optionalInt64("consolidatedAt"),
            tags: try json.optionalText("tags"),
            happenedAt: try json.optionalInt64("happenedAt"),
            originalContent: try json.optionalText("originalContent"),
            driftCount: try json.int("driftCount")
        )
    }
}

extension BackupSession {
    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            personaId: try json.text("personaId"),
            apiConfigId: try json.optionalText("apiConfigId"),
            model: try json.text("model"),
            title: try json.text("title"),
            lastMessagePreview: try json.optionalText("lastMessagePreview"),
            lastMessageAt: try json.optionalInt64("lastMessageAt"),
            unreadCount: try json.int("unreadCount"),
            pinned: try json.bool("pinned"),
            createdAt: try json.int64("createdAt"),
            updatedAt: try json.int64("updatedAt"),
            lastPersonaVersion: try json.int("lastPersonaVersion"),
            syncState: try json.text("syncState", "STATELESS"),
            syncedPersonaVersion: try json.optionalInt("syncedPersonaVersion"),
            syncedMemoriesHash: try json.optionalText("syncedMemoriesHash"),
            serverSessionId: try json.optionalText("serverSessionId"),
            lastSyncError: try json.optionalText("lastSyncError"),
            lastSyncAt: try json.optionalInt64("lastSyncAt"),
            lastMemoryExtractedCount: try json.int("lastMemoryExtractedCount"),
            summary: try json.optionalText("summary"),
            summarizedUpTo: try json.optionalInt64("summarizedUpTo"),
            summaryAt: try json.optionalInt64("summaryAt"),
            memoryRetentionOverride: try json.optionalInt("memoryRetentionOverride"),
            extractionEveryOverride: try json.optionalInt("extractionEveryOverride")
        )
    }
}

extension BackupMessage {
    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            sessionId: try json.text("sessionId"),
            personaId: try json.text("personaId"),
            role: try json.text("role", "USER"),
            content: try json.text("content"),
            createdAt: try json.int64("createdAt"),
            updatedAt: try json.int64("updatedAt"),
            status: try json.text("status", "COMPLETE"),
            errorMessage: try json.optionalText("errorMessage"),
            model: try json.optionalText("model"),
            edited: try json.bool("edited"),
            attachmentsJson: try json.optionalText("attachmentsJson"),
            toolTraceJson: try json.optionalText("toolTraceJson"),
            readAt: try json.optionalInt64("readAt")
        )
    }
}

extension BackupPreset {
    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            name: try json.text("name"),
            description: try json.text("description"),
            prompt: try json.text("prompt"),
            category: try json.text("category"),
            avatarUri: try json.optionalText("avatarUri"),
            avatarSeed: try json.int("avatarSeed"),
            createdAt: try json.int64("createdAt"),
            updatedAt: try json.int64("updatedAt")
        )
    }
}

extension BackupProactiveRule {
    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            personaId: try json.text("personaId"),
            sessionId: try json.optionalText("sessionId"),
            kind: try json.text("kind", "DAILY"),
            timeOfDayMinutes: try json.optionalInt("timeOfDayMinutes"),
            intervalMinutes: try json.optionalInt("intervalMinutes"),
            hint: try json.text("hint"),
            note: try json.text("note"),
            enabled: try json.bool("enabled", true),
            pending: try json.bool("pending"),
            source: try json.text("source", "MANUAL"),
            nextTriggerAt: try json.int64("nextTriggerAt"),
            lastTriggeredAt: try json.optionalInt64("lastTriggeredAt"),
            lastError: try json.optionalText("lastError"),
            createdAt: try json.int64("createdAt"),
            updatedAt: try json.int64("updatedAt")
        )
    }
}

extension BackupAgentIntent {
    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            personaId: try json.text("personaId"),
            sessionId: try json.optionalText("sessionId"),
            kind: try json.text("kind", "REACH_OUT"),
            earliestAt: try json.int64("earliestAt"),
            latestAt: try json.int64("latestAt"),
            note: try json.text("note"),
            hint: try json.text("hint"),
            status: try json.text("status", "PENDING"),
            ruleId: try json.optionalText("ruleId"),
            createdAt: try json.int64("createdAt"),
            sentAt: try json.optionalInt64("sentAt"),
            lastError: try json.optionalText("lastError")
        )
    }
}

extension BackupMemoryConsolidation {
    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            personaId: try json.text("personaId"),
            at: try json.int64("at"),
            kept: try json.int("kept"),
            merged: try json.int("merged"),
            archived: try json.int("archived"),
            forgotten: try json.int("forgotten"),
            summary: try json.text("summary"),
            modelName: try json.optionalText("modelName")
        )
    }
}

extension BackupApiConfig {
    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            name: try json.text("name"),
            provider: try json.text("provider", "OPENAI"),
            baseUrl: try json.text("baseUrl"),
            model: try json.text("model"),
            allowInsecureHttp: try json.bool("allowInsecureHttp"),
            temperature: try json.optionalDouble("temperature"),
            topP: try json.optionalDouble("topP"),
            maxOutputTokens: try json.optionalInt("maxOutputTokens"),
            frequencyPenalty: try json.optionalDouble("frequencyPenalty"),
            presencePenalty: try json.optionalDouble("presencePenalty"),
            webToolsEnabled: try json.bool("webToolsEnabled"),
            webToolsBaseUrl: try json.text("webToolsBaseUrl"),
            createdAt: try json.int64("createdAt"),
            updatedAt: try json.int64("updatedAt")
        )
    }
}

extension BackupSettings {
    init(json: [String: JSONValue]) throws {
        self.init(
            themeMode: try json.text("themeMode", "SYSTEM"),
            accentColor: try json.int64("accentColor", 0xFFC76F4E),
            fontScale: try json.float("fontScale", 1),
            bubbleStyle: try json.text("bubbleStyle", "SOFT"),
            animationsEnabled: try json.bool("animationsEnabled", true),
            hapticsEnabled: try json.bool("hapticsEnabled", true),
            chatBackgroundColor: try json.optionalInt64("chatBackgroundColor"),
            agentLoopEnabled: try json.bool("agentLoopEnabled", true),
            activePersonaId: try json.optionalText("activePersonaId")
        )
    }
}

// ---------------------------------------------------------------------------
// 解码结果与预览
// ---------------------------------------------------------------------------

/// `BackupCodec.decode` 的返回值：坏数据也只在这里说话，不往上抛。
public enum BackupDecodeResult: Equatable, Sendable {
    /// 读懂了。`warnings` 是「有东西没读出来 / 有东西带不回来」的中文说明，可以直接给用户看。
    case success(BackupBundle, warnings: [String])
    /// 读不懂。`reason` 是一句中文，说明为什么接不回来。
    case failure(reason: String)

    public var isSuccess: Bool {
        if case .success = self { return true }
        return false
    }

    public var bundle: BackupBundle? {
        if case .success(let bundle, _) = self { return bundle }
        return nil
    }

    public var warnings: [String] {
        if case .success(_, let warnings) = self { return warnings }
        return []
    }

    public var failureReason: String? {
        if case .failure(let reason) = self { return reason }
        return nil
    }
}

/// 选好文件、还没决定怎么接回来时，先给用户看的一句话摘要。
public struct BackupPreview: Equatable, Sendable {
    public var personas: Int
    public var memories: Int
    public var sessions: Int
    public var messages: Int
    public var rules: Int
    public var presets: Int
    public var apiConfigs: Int
    public var exportedAt: Int64
    public var appVersion: String

    public init(
        personas: Int = 0,
        memories: Int = 0,
        sessions: Int = 0,
        messages: Int = 0,
        rules: Int = 0,
        presets: Int = 0,
        apiConfigs: Int = 0,
        exportedAt: Int64 = 0,
        appVersion: String = ""
    ) {
        self.personas = personas
        self.memories = memories
        self.sessions = sessions
        self.messages = messages
        self.rules = rules
        self.presets = presets
        self.apiConfigs = apiConfigs
        self.exportedAt = exportedAt
        self.appVersion = appVersion
    }

    public var isEmpty: Bool {
        personas == 0 && memories == 0 && sessions == 0 && messages == 0
    }

    /// 「3 个人格 · 128 条记忆 · 42 段对话 · 1580 句话 · 1 条主动节奏」。
    public func describe() -> String {
        if isEmpty { return "这份备份里什么也没有" }
        var text = "\(personas) 个人格"
        text += " · \(memories) 条记忆"
        text += " · \(sessions) 段对话"
        text += " · \(messages) 句话"
        if rules > 0 { text += " · \(rules) 条主动节奏" }
        return text
    }

    /// 「2026年11月14日 01:40 带走」。
    public func exportedAtText() -> String {
        exportedAt <= 0 ? "带走的时间不详" : "\(formatBackupMoment(exportedAt)) 带走"
    }

    public static func from(_ bundle: BackupBundle) -> BackupPreview {
        BackupPreview(
            personas: bundle.personas.count,
            memories: bundle.memories.count,
            sessions: bundle.sessions.count,
            messages: bundle.messages.count,
            rules: bundle.proactiveRules.count,
            presets: bundle.presets.count,
            apiConfigs: bundle.apiConfigs.count,
            exportedAt: bundle.exportedAt,
            appVersion: bundle.appVersion
        )
    }
}
