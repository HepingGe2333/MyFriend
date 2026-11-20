import Foundation

// ---------------------------------------------------------------------------
// 「把他带走」的那份文件 —— iOS 侧的类型定义。
//
// 权威定义在 Android：app/src/main/java/com/yap/app/domain/BackupCodec.kt。
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
///
/// v3（0.12）：头像与聊天图片的**二进制**也一起带走。
/// - 顶层新增 `media` 数组（永远存在，没有图片时是空数组）：
///   `{ id, kind, personaId?, fileName, mime, bytes, data }`，`data` 是标准 base64；
/// - `personas.avatarUri` / `presets.avatarUri` 只写**裸文件名**（`avatar-<uuid>.jpg`），
///   `messages.attachmentsJson[].uri` 也只写裸文件名（`chatImage-<uuid>.jpg`）：
///   这个裸名是「引用 ↔ media」的关联键，换机后本机路径由导入侧重新生成；
/// - 上限：单张 8 MiB、全部合计 128 MiB，整份备份（含 base64 膨胀）读写上限 256 MiB。
///
/// v4（0.12，schema v12）：`apiConfigs[]` 增加 `webToolsProvider`（缺字段默认 MESSAGEAPI；
/// 0.12 之前这个 provider 叫 DEEPSEEK，读旧备份时归一化）。
/// 搜索 Key 仍然**永不进备份**（与 LLM Key 同一条规矩）；v1–v3 备份继续能读。
///
/// v5（0.12，schema v13，人设引导 `spec/persona-guidance/README.md` §5）：
/// - `personas[]` 增加 `worldJson` / `speechStyleJson` / `activityJson`（都可空）；
/// - `presets[]` 增加 `worldJson` / `speechStyleJson`（都可空）。
/// 三段都是「总结后的设定」，**不含原始聊天记录**（§3.3：原文不进备份）。
/// v1–v4 备份继续能读（缺字段 → nil → 该人格没有世界/话风，行为与旧版本完全一致）。
///
/// v6（0.13，schema v15）：把 0.13 的三样新东西也带走，换机之后他还是「会发表情、
/// 会说话、会给自己记便条」的那个人。
/// - `media[].kind` 多三个取值：`STICKER` / `AUDIO` / `VIDEO`（媒体总数与预算不变：
///   单份 8 MiB、全部 128 MiB，超限的跳过并计数）；
/// - 顶层新增 `stickers[]`：表情收藏（`file` 写**裸文件名**，与 `media` 里的 STICKER 关联）；
/// - 顶层新增 `diaryNotes[]`：他自己的日记便条（含提醒时间；提醒是否已处理也带走，
///   免得换机之后同一条提醒又弹一次）；
/// - `messages.attachmentsJson[]` 的 `kind` / `durationMs` 一起带走（语音/视频的时长
///   在服务端不下发，丢了就永远显示 `--:--`）。
/// v1–v5 备份继续能读：缺 `stickers` / `diaryNotes` → 空数组（= 他没有表情和便条）。
///
/// v7（0.15，schema v19）：「把他带走」也要带走 0.15 新长出来的东西，换机之后他仍然是
/// **同一个人**，而不是「忘了自己刚想过什么、把你说过的话又忘一遍」。
/// - `personas[]` 增加三个拉黑字段：`blockedUntil` / `blockReason` / `blockProposalJson`
///  （0.14 的拉黑状态与未确认提议；到期与否由时间决定，不写第二个状态位）；
/// - `personas[]` 增加 `languageTag`（0.15）：人格自己的提示词语言；
/// - `messages[]` 增加 `blocked`：拉黑期间用户发来的消息在换机后仍然对模型不可见
///  （否则「解除拉黑」后新机器会把那几句喂给模型，与规范 §2 相反）；
/// - 顶层新增 `mentalActivities[]`（最近的心理活动，≤50 条/人格）与 `sessionMemory[]`
///  （会话备忘录：短期 / 长期摘要 + **主题总结** + 三个节流游标）；
/// - `media[]` 增加可选 `sha256`（对 base64 解码后的**原始字节**求，64 位小写 hex）：
///   导入时非空就校验，不一致的那一条跳过并计数（老备份没有这个键 → 跳过校验）；
/// - 服务端备份的 `extensions.mentalActivities` / `extensions.sessionMemory` 会被**当成
///   对应的顶层段读进来**（服务端自己那套键名不用客户端理解），`extensions.proactiveFailures`
///   落到本机诊断表（见 `ProactiveFailureStore`）。客户端导出从不写 `extensions`。
/// v1–v6 备份继续能读：缺上面的新段 → 空数组 / nil / false（行为与 0.14 完全一致）。
public let backupFormatVersion: Int = 7

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
    /// 图片二进制（v3）。没有图片时是空数组而不是省略：老版本读新备份会当成未知字段忽略，
    /// 新版本读老备份则拿到空列表，行为与 v2 完全一致。
    public var media: [BackupMedia]
    public var presets: [BackupPreset]
    public var proactiveRules: [BackupProactiveRule]
    /// 他「想在某个时间段找你」的念头；过期就作废，属于可选内容。
    public var agentIntents: [BackupAgentIntent]
    /// 他整理过记忆的留痕，用来保留「他最近整理过记忆」这条历史。
    public var memoryConsolidations: [BackupMemoryConsolidation]
    /// v6：表情收藏。老备份没有这一段 → 空数组（= 他没有表情）。
    ///
    /// 图片二进制在 `media[]`（`kind=STICKER`），这里只留一行元数据 + 裸文件名；
    /// 分开的理由与头像/聊天图同一条：引用与二进制各自能被单独跳过而不互相拖垮。
    public var stickers: [BackupSticker]
    /// v6：他自己的日记便条（含提醒）。老备份没有这一段 → 空数组。
    public var diaryNotes: [BackupDiaryNote]
    /// v7：他最近的心理活动（私密念头 + 当时的心情快照）。老备份没有这一段 → 空数组。
    ///
    /// 只带每个人的最近若干条（`BackupService.exportMentalActivitiesPerPersona`）：
    /// 这是「最近在想什么」的工作记忆，不是需要全史的业务数据。
    public var mentalActivities: [BackupMentalActivity]
    /// v7：会话备忘录（短期 / 长期摘要 + 主题总结 + 节流游标）。老备份没有这一段 → 空数组。
    ///
    /// 换机后「你的备忘录」要还在：否则他会把你们刚聊过的话题再问一遍。
    public var sessionMemory: [BackupSessionMemory]
    /// v7：服务端诊断段（只在**读取服务端备份**时出现，`extensions.proactiveFailures`）。
    ///
    /// 客户端备份**从不写**这个键：它是服务端专有的失败记录，本机只负责落进诊断表
    /// （`ProactiveFailureStore`），不参与跨端契约。`BackupBundle.encode(to:)` 手写编码
    /// 把它排除在外，所以读到过它的备份再导出也不会多出这个键。
    public var proactiveFailures: [BackupProactiveFailure]?
    public var apiConfigs: [BackupApiConfig]
    /// 可移植的设置。为 nil 表示这份备份里没有设置这一段（老版本或手写的备份）：
    /// 接回来时设置页保持原样，绝不拿一份「没有设置的备份」把用户现在的偏好清成默认值。
    public var settings: BackupSettings?
    /// 写下这份备份时本机的**数据库 schema 版本**（`Schema.version`）。老备份没有这个键 → 0。
    ///
    /// 为什么格式版本之外还要 schema 版本：格式版本说「文件长什么样」，schema 版本说
    /// 「写这份文件时库里有哪几张表、哪些列」。两个数字一起看，才能判断旧 App 硬读新数据
    /// 会缺哪一块。读取侧只把它当提示（> 本机时给一句警告），真正的拒绝仍由 `formatVersion` 做。
    public var schemaVersion: Int
    /// 这份文件被生成的时间（毫秒）。老备份没有这个键 → 0（界面显示「时间不详」）。
    ///
    /// 与 `exportedAt` 同源，但**语义不同**：`exportedAt` 是跨端契约里 Android 也在写的
    /// 「导出时刻」，`createdAt` 是这份文件自己的生日，用来在导入预览里说清
    /// 「这份备份是哪一刻的他」；两者分开记，将来做增量导出时不会互相绑死。
    public var createdAt: Int64
    /// **规范化 JSON 的 SHA-256**（64 位小写 hex）；老备份没有这个键 → nil（跳过校验）。
    ///
    /// 「规范化」的规则是跨端协议，见 `BackupIntegrity`：键排序、无空白、数字保留原始
    /// 字面量、字符串按 kotlinx 的转义表输出。所以：
    /// - 文件的空格/换行/键序被重排 **不影响** 校验（内容没变）；
    /// - 任何一个值被改过，校验一定失败（本机算法的封条才会拒绝导入）。
    /// 服务端备份与 v1–v7 的老备份都没有这个键，行为与之前完全一致。
    public var checksum: String?
    /// 校验和算法标记（`yap-canonical-json-sha256-v1`）；nil / 别的值 = 这份封条不由本机复核。
    ///
    /// 与 Android 的 `checksumAlgorithm` 同一个键、同一套语义：只有标记完全匹配才复核 ——
    /// 别的客户端（或将来换了算法）写的哈希，两端算出来的字节本来就可能不同，
    /// 硬比只会把一份正常备份判成损坏。
    public var checksumAlgorithm: String?

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
        media: [BackupMedia] = [],
        presets: [BackupPreset] = [],
        proactiveRules: [BackupProactiveRule] = [],
        agentIntents: [BackupAgentIntent] = [],
        memoryConsolidations: [BackupMemoryConsolidation] = [],
        stickers: [BackupSticker] = [],
        diaryNotes: [BackupDiaryNote] = [],
        mentalActivities: [BackupMentalActivity] = [],
        sessionMemory: [BackupSessionMemory] = [],
        proactiveFailures: [BackupProactiveFailure]? = nil,
        apiConfigs: [BackupApiConfig] = [],
        settings: BackupSettings? = nil,
        schemaVersion: Int = 0,
        createdAt: Int64 = 0,
        checksum: String? = nil,
        checksumAlgorithm: String? = nil
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
        self.media = media
        self.presets = presets
        self.proactiveRules = proactiveRules
        self.agentIntents = agentIntents
        self.memoryConsolidations = memoryConsolidations
        self.stickers = stickers
        self.diaryNotes = diaryNotes
        self.mentalActivities = mentalActivities
        self.sessionMemory = sessionMemory
        self.proactiveFailures = proactiveFailures
        self.apiConfigs = apiConfigs
        self.settings = settings
        self.schemaVersion = schemaVersion
        self.createdAt = createdAt
        self.checksum = checksum
        self.checksumAlgorithm = checksumAlgorithm
    }

    enum CodingKeys: String, CodingKey {
        case format, formatVersion, appVersion, exportedAt, apiKeysOmitted
        // 0.15 的元数据：schema 版本 / 文件生日 / 封条（都可选，老备份没有）。
        case schemaVersion, createdAt, checksum, checksumAlgorithm
        case personas, memories, sessions, messages
        // `media` 紧跟在 `messages` 后面只为读起来顺：JSON 键顺序对两端都不重要。
        case media
        case presets
        case proactiveRules, agentIntents, memoryConsolidations
        // v6：表情与日记便条。老版本读到会当未知字段忽略（不影响人格/记忆/聊天）。
        case stickers, diaryNotes
        // v7：心理活动 / 会话备忘录（客户端本地也带走）。
        case mentalActivities, sessionMemory
        case apiConfigs, settings
        // 只读字段（只在手写编码里被故意跳过）：服务端 `extensions.proactiveFailures`。
        case proactiveFailures
    }

    /// 手写编码而不是合成：`proactiveFailures` 是**只读**的服务端诊断段。
    ///
    /// 为什么不用合成实现 + 可选类型：合成编码对可选属性用的是 `encodeIfPresent`，
    /// 但「读到过这份诊断数据」时它是非 nil 的，合成实现会把它写回客户端备份 ——
    /// 那就违反了「客户端格式里没有 proactiveFailures」这条跨端契约。
    /// 手写编码把它排除在外，其余键与合成行为逐字一致。
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(format, forKey: .format)
        try container.encode(formatVersion, forKey: .formatVersion)
        try container.encode(appVersion, forKey: .appVersion)
        try container.encode(exportedAt, forKey: .exportedAt)
        try container.encode(apiKeysOmitted, forKey: .apiKeysOmitted)
        // 元数据是**可选**的：值为 0 / nil 时不写。
        //
        // 为什么要这样「省」：跨端黄金样本（`fixtures/backup/*.json`）钉的是
        // 「顶层键集合 = Kotlin 结构里的键集合」，它们由 `BackupCodec.encode` 生成；
        // 元数据只在真正导出（`BackupService` → `encodeSealed`）时才有值，
        // 样本与手写的 bundle 保持逐字不变。Android 读到多出来的键时会直接忽略
        //（`ignoreUnknownKeys`），所以带元数据的 iOS 备份照样能被 Android 接住。
        if schemaVersion > 0 {
            try container.encode(schemaVersion, forKey: .schemaVersion)
        }
        if createdAt > 0 {
            try container.encode(createdAt, forKey: .createdAt)
        }
        try container.encodeIfPresent(checksum, forKey: .checksum)
        try container.encodeIfPresent(checksumAlgorithm, forKey: .checksumAlgorithm)
        try container.encode(personas, forKey: .personas)
        try container.encode(memories, forKey: .memories)
        try container.encode(sessions, forKey: .sessions)
        try container.encode(messages, forKey: .messages)
        try container.encode(media, forKey: .media)
        try container.encode(presets, forKey: .presets)
        try container.encode(proactiveRules, forKey: .proactiveRules)
        try container.encode(agentIntents, forKey: .agentIntents)
        try container.encode(memoryConsolidations, forKey: .memoryConsolidations)
        try container.encode(stickers, forKey: .stickers)
        try container.encode(diaryNotes, forKey: .diaryNotes)
        try container.encode(mentalActivities, forKey: .mentalActivities)
        try container.encode(sessionMemory, forKey: .sessionMemory)
        try container.encode(apiConfigs, forKey: .apiConfigs)
        try container.encodeIfPresent(settings, forKey: .settings)
        // proactiveFailures：故意不写。
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
    /// v5：世界预设 JSON（`WorldPreset` 的宽容 JSON；空 = 没有世界）。
    public var worldJson: String?
    /// v5：话风 JSON（`SpeechStyle`；只含总结后的风格，**不含原始聊天记录**，§3.3）。
    public var speechStyleJson: String?
    /// v5：当前活动 JSON（`PersonaActivity`；过期的活动读进来会自然失效）。
    public var activityJson: String?
    /// 0.15：人格自己的提示词语言（`zh` / `en`；空 = 默认中文）。
    ///
    /// 老备份里没有这个键 → 读成 nil（默认中文），**不影响 v1–v6 的读取**；
    /// 新备份里只有非空才写出（可选字段省略），所以旧版备份文件逐字不变。
    public var languageTag: String?
    /// v7：拉黑到期时间（毫秒；nil = 没被拉黑 / 已到期解除）。
    public var blockedUntil: Int64?
    /// v7：拉黑原因（他当时写下的一句话；nil = 没写）。
    public var blockReason: String?
    /// v7：未确认的拉黑提议（两轮确认，10 分钟过期；原样透传的 JSON 文本）。
    public var blockProposalJson: String?

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
        dndOnSleep: Bool = false,
        worldJson: String? = nil,
        speechStyleJson: String? = nil,
        activityJson: String? = nil,
        languageTag: String? = nil,
        blockedUntil: Int64? = nil,
        blockReason: String? = nil,
        blockProposalJson: String? = nil
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
        self.traits = PersonaTags.normalize(traits)
        self.likes = PersonaTags.normalize(likes)
        self.behaviors = PersonaTags.normalize(behaviors)
        self.backstory = PersonaEntity.clean(backstory, limit: PersonaFieldLimits.backstoryMax)
        self.quirks = PersonaEntity.clean(quirks, limit: PersonaFieldLimits.quirksMax)
        self.proactiveLevel = PersonaFieldLimits.clampLevel(proactiveLevel)
        self.memoryRetention = PersonaFieldLimits.clampLevel(memoryRetention)
        self.extractionEvery = PersonaFieldLimits.clampExtractionEvery(extractionEvery)
        self.sleepEnabled = sleepEnabled
        self.wakeThreshold = PersonaFieldLimits.clampWakeThreshold(wakeThreshold)
        self.dndOnSleep = dndOnSleep
        self.worldJson = PromptGuidanceJSON.clean(worldJson)
        self.speechStyleJson = PromptGuidanceJSON.clean(speechStyleJson)
        self.activityJson = PromptGuidanceJSON.clean(activityJson)
        self.languageTag = languageTag?.trimmingCharacters(in: .whitespacesAndNewlines)
        // 拉黑：`<= 0` 一律当「没拉黑」—— 与 `PersonaEntity` 的初始化同一条口径。
        self.blockedUntil = blockedUntil.flatMap { $0 > 0 ? $0 : nil }
        let trimmedReason = blockReason?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.blockReason = trimmedReason.isEmpty
            ? nil : String(trimmedReason.prefix(PersonaFieldLimits.blockReasonMax))
        self.blockProposalJson = PromptGuidanceJSON.clean(blockProposalJson)
    }

    enum CodingKeys: String, CodingKey {
        case id, name, description, avatarUri, avatarSeed, prompt, category, version
        case defaultApiConfigId, modelOverride, isDefault, createdAt, updatedAt
        case personaCardJson, quietSleepMinutes, quietWakeMinutes
        case mood, moodIntensity, moodReason, moodUpdatedAt, moodNudgedAt
        case traits, likes, behaviors, backstory, quirks
        case proactiveLevel, memoryRetention, extractionEvery
        case sleepEnabled, wakeThreshold, dndOnSleep
        case worldJson, speechStyleJson, activityJson, languageTag
        // v7：拉黑（0.14 的行为，0.15 才进备份）。
        case blockedUntil, blockReason, blockProposalJson
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
    // 注意：`syncState` / `syncedPersonaVersion` / `syncedMemoriesHash` / `serverSessionId` /
    // `lastSyncError` / `lastSyncAt` 这 6 列**只在库里**（对齐 Room 的 v10 表结构），
    // 不进备份 JSON：Android 的 `BackupSession` 没有它们，跨端契约要求字段名逐字一致
    //（0.10.2 安全审计 P1-5）。v1/v2 旧文件里若出现这些键，解码时按未知字段忽略。
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
        case unreadCount, pinned, createdAt, updatedAt, lastPersonaVersion
        case lastMemoryExtractedCount, summary, summarizedUpTo, summaryAt
        case memoryRetentionOverride, extractionEveryOverride
    }
}

/// 一句话。
///
/// `toolTraceJson` 原样带走（「他当时查了两个网页」这种痕迹也是相处的一部分）；
/// `attachmentsJson` 只留元数据 + **裸文件名**（v3）：图片本体在 `media` 里，
/// 导入时按这个裸名把本机新路径接回去。
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
    /// v7：拉黑期间用户发来的消息（模型**永久看不到**，解除后也不可见）。
    ///
    /// 老备份没有这个键 → false；v7 导出**每条都写**（`true` / `false`）——
    /// 跨端契约要求两端的键集一致，省略 false 会让 Android 的 fixture 断言多一种形状。
    public var blocked: Bool

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
        readAt: Int64? = nil,
        blocked: Bool = false
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
        self.blocked = blocked
    }

    enum CodingKeys: String, CodingKey {
        case id, sessionId, personaId, role, content, createdAt, updatedAt, status
        case errorMessage, model, edited, attachmentsJson, toolTraceJson, readAt
        // v7：拉黑期间的消息
        case blocked
    }

    /// 手写编码只为把 `blocked` 固定成 Bool（每条都写）：其余字段与合成实现逐字一致。
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(sessionId, forKey: .sessionId)
        try container.encode(personaId, forKey: .personaId)
        try container.encode(role, forKey: .role)
        try container.encode(content, forKey: .content)
        try container.encode(createdAt, forKey: .createdAt)
        try container.encode(updatedAt, forKey: .updatedAt)
        try container.encode(status, forKey: .status)
        try container.encodeIfPresent(errorMessage, forKey: .errorMessage)
        try container.encodeIfPresent(model, forKey: .model)
        try container.encode(edited, forKey: .edited)
        try container.encodeIfPresent(attachmentsJson, forKey: .attachmentsJson)
        try container.encodeIfPresent(toolTraceJson, forKey: .toolTraceJson)
        try container.encodeIfPresent(readAt, forKey: .readAt)
        try container.encode(blocked, forKey: .blocked)
    }
}

/// 备份里的一张图片（v3）：头像或聊天图片的**二进制**。
///
/// 字段名逐字对齐 Android 的 `BackupMedia`，一个字母都不能改：
/// ```json
/// {"id":"<uuid>","kind":"PERSONA_AVATAR","personaId":"<uuid>","fileName":"avatar-<uuid>.jpg",
///  "mime":"image/jpeg","bytes":12345,"data":"<base64>"}
/// ```
/// - `kind` 是 String 而不是枚举：不认识的 kind 要在**导入时**跳过并计数，
///   而不是让整条记录在解码阶段就消失（那样用户看不到「有几张图没接回来」）。
/// - `personaId` 可空：`USER_AVATAR` 没有归属人格（nil 时这个键不写进 JSON）。
/// - `fileName` 是**裸的源文件名**，也是「引用 ↔ media」的关联键（头像 / 附件里写的就是它）。
/// - `bytes` 是 `data` 解出来的**原始**字节数（JSON 数字）；`data` 是标准 base64、带 padding、不换行。
public struct BackupMedia: Equatable, Sendable, Codable {
    public var id: String
    /// `PERSONA_AVATAR` / `USER_AVATAR` / `CHAT_IMAGE`（v3）+
    /// `STICKER` / `AUDIO` / `VIDEO`（v6）。见类型注释：故意保留为 String。
    public var kind: String
    public var personaId: String?
    public var fileName: String
    public var mime: String
    public var bytes: Int
    public var data: String
    /// v7：`data` 解码后**原始字节**的 SHA-256（64 位小写 hex）。
    ///
    /// 导入时非空就校验：不一致的那一条跳过并计数（不整份失败）。老备份没有这个键 →
    /// nil → 跳过校验（旧数据兼容）。导出时本机文件读得出字节就一定写。
    public var sha256: String?

    public init(
        id: String = "",
        kind: String = "",
        personaId: String? = nil,
        fileName: String = "",
        mime: String = "",
        bytes: Int = 0,
        data: String = "",
        sha256: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.personaId = personaId
        self.fileName = fileName
        self.mime = mime
        self.bytes = bytes
        self.data = data
        self.sha256 = sha256
    }

    enum CodingKeys: String, CodingKey {
        case id, kind, personaId, fileName, mime, bytes, data, sha256
    }

    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            kind: try json.text("kind"),
            personaId: try json.optionalText("personaId"),
            fileName: try json.text("fileName"),
            mime: try json.text("mime"),
            bytes: try json.int("bytes"),
            data: try json.text("data"),
            sha256: try json.optionalText("sha256")
        )
    }
}

/// 一张表情（v6，表 `stickers`）。
///
/// `file` 写的是**裸文件名**（`sticker-<hash>-<uuid>.gif`），二进制在同名 `media[]`
/// 条目的 `kind=STICKER` 里。换机后相对路径一定要重写，所以绝不能把设备绝对路径带出去。
public struct BackupSticker: Equatable, Sendable, Encodable {
    public var id: String
    /// 服务端模式下的用户 id；本地模式为 nil。
    public var ownerUserId: String?
    /// 模型做的表情挂在某个人格名下；用户导入的为 nil。
    public var personaId: String?
    /// 裸文件名（与 `media[].fileName` 关联）；空 = 这份备份里的表情没有二进制。
    ///
    /// 跨端字段名：iOS 历史上叫 `file`，Android 的 `BackupSticker` 叫 `fileName`。
    /// **导出时两个都写**（同一个值），读取时两个都认（`fileName` 优先，与 Android 一致）——
    /// 这样任一端生成的 v6 备份另一端都能接住，也不用为改名做一次格式迁移。
    public var file: String?
    public var mime: String
    public var width: Int
    public var height: Int
    public var frames: Int
    public var bytes: Int
    /// `StickerSource` / `StickerCreator` 的原始值（字符串：新版本加枚举值时老版本不炸）。
    public var source: String
    public var createdBy: String
    public var createdAt: Int64

    public init(
        id: String = "",
        ownerUserId: String? = nil,
        personaId: String? = nil,
        file: String? = nil,
        mime: String = "image/gif",
        width: Int = 0,
        height: Int = 0,
        frames: Int = 1,
        bytes: Int = 0,
        source: String = "IMPORTED",
        createdBy: String = "USER",
        createdAt: Int64 = 0
    ) {
        self.id = id
        self.ownerUserId = ownerUserId
        self.personaId = personaId
        self.file = file
        self.mime = mime
        self.width = max(0, width)
        self.height = max(0, height)
        self.frames = max(1, frames)
        self.bytes = max(0, bytes)
        self.source = source
        self.createdBy = createdBy
        self.createdAt = createdAt
    }

    enum CodingKeys: String, CodingKey {
        case id, ownerUserId, personaId, file, fileName, mime, width, height
        case frames, bytes, source, createdBy, createdAt
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encodeIfPresent(ownerUserId, forKey: .ownerUserId)
        try container.encodeIfPresent(personaId, forKey: .personaId)
        // 两个字段写同一个值：iOS 读 `file`、Android 读 `fileName`（见属性注释）。
        try container.encodeIfPresent(file, forKey: .file)
        try container.encodeIfPresent(file, forKey: .fileName)
        try container.encode(mime, forKey: .mime)
        if width != 0 { try container.encode(width, forKey: .width) }
        if height != 0 { try container.encode(height, forKey: .height) }
        try container.encode(frames, forKey: .frames)
        if bytes != 0 { try container.encode(bytes, forKey: .bytes) }
        try container.encode(source, forKey: .source)
        try container.encode(createdBy, forKey: .createdBy)
        try container.encode(createdAt, forKey: .createdAt)
    }

    init(json: [String: JSONValue]) throws {
        // 跨端契约只能加不能改：iOS 写 `file`、Android 写 `fileName`（两端导出时都写）。
        // 读的时候两个都认，优先 `fileName`（与 Android 的 `portableFileName` 同序）。
        let portableFile = try json.optionalText("fileName")
            ?? json.optionalText("file")
        self.init(
            id: try json.text("id"),
            ownerUserId: try json.optionalText("ownerUserId"),
            personaId: try json.optionalText("personaId"),
            file: portableFile,
            mime: try json.text("mime", "image/gif"),
            width: try json.int("width"),
            height: try json.int("height"),
            frames: try json.int("frames", 1),
            bytes: try json.int("bytes"),
            source: try json.text("source", "IMPORTED"),
            createdBy: try json.text("createdBy", "USER"),
            createdAt: try json.int64("createdAt")
        )
    }
}

/// 一条日记便条（v6，表 `diary_notes`）。
///
/// 这是**他自己的备忘录**（`spec/diary/README.md`）：换机之后他希望「明天记得问他到家没」
/// 还在，所以内容、时间、提醒都带走。`remindedAt` 也带走 —— 否则同一条提醒会在新机器上
/// 再弹一次，用户会觉得「这件事怎么又问了一遍」。
public struct BackupDiaryNote: Equatable, Sendable, Encodable {
    public var id: String
    public var personaId: String
    public var content: String
    public var createdAt: Int64
    /// 什么时候提醒他（毫秒）；nil = 不提醒。
    public var remindAt: Int64?
    /// 这条提醒已经处理过的时间；nil = 还没处理。
    public var remindedAt: Int64?
    public var updatedAt: Int64

    public init(
        id: String = "",
        personaId: String = "",
        content: String = "",
        createdAt: Int64 = 0,
        remindAt: Int64? = nil,
        remindedAt: Int64? = nil,
        updatedAt: Int64 = 0
    ) {
        self.id = id
        self.personaId = personaId
        self.content = content
        self.createdAt = createdAt
        self.remindAt = remindAt
        self.remindedAt = remindedAt
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey {
        case id, personaId, content, createdAt, remindAt, remindedAt, updatedAt
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(personaId, forKey: .personaId)
        try container.encode(content, forKey: .content)
        try container.encode(createdAt, forKey: .createdAt)
        try container.encodeIfPresent(remindAt, forKey: .remindAt)
        try container.encodeIfPresent(remindedAt, forKey: .remindedAt)
        try container.encode(updatedAt, forKey: .updatedAt)
    }

    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            personaId: try json.text("personaId"),
            content: try json.text("content"),
            createdAt: try json.int64("createdAt"),
            remindAt: try json.optionalInt64("remindAt"),
            remindedAt: try json.optionalInt64("remindedAt"),
            updatedAt: try json.int64("updatedAt")
        )
    }
}

/// 一条心理活动（v7，表 `mental_activities`；`spec/experience/README.md` §3）。
///
/// 字段与服务端 `extensions.mentalActivities[]` 逐字相同（Android 的 v7 也用它），
/// 这样服务端备份被客户端读进来时不需要第二套映射。
/// `thought` 是 ≤60 字的私密念头（**不发给用户**）；心情快照是**微调之后**的值。
public struct BackupMentalActivity: Equatable, Sendable, Encodable {
    public var id: String
    public var personaId: String
    /// 哪一段会话里的念头（主动/无会话时为空）。
    public var sessionId: String?
    public var at: Int64
    public var thought: String
    public var moodLabel: String?
    public var moodIntensity: Int?
    public var moodValence: Int?
    public var moodArousal: Int?
    public var moodDominance: Int?

    public init(
        id: String = "",
        personaId: String = "",
        sessionId: String? = nil,
        at: Int64 = 0,
        thought: String = "",
        moodLabel: String? = nil,
        moodIntensity: Int? = nil,
        moodValence: Int? = nil,
        moodArousal: Int? = nil,
        moodDominance: Int? = nil
    ) {
        self.id = id
        self.personaId = personaId
        self.sessionId = sessionId
        self.at = at
        self.thought = thought
        self.moodLabel = moodLabel
        self.moodIntensity = moodIntensity
        self.moodValence = moodValence
        self.moodArousal = moodArousal
        self.moodDominance = moodDominance
    }

    enum CodingKeys: String, CodingKey {
        case id, personaId, sessionId, at, thought
        case moodLabel, moodIntensity, moodValence, moodArousal, moodDominance
    }
}

extension BackupMentalActivity {
    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            personaId: try json.text("personaId"),
            sessionId: try json.optionalText("sessionId"),
            at: try json.int64("at"),
            thought: try json.text("thought"),
            moodLabel: try json.optionalText("moodLabel"),
            moodIntensity: try json.optionalInt("moodIntensity"),
            moodValence: try json.optionalInt("moodValence"),
            moodArousal: try json.optionalInt("moodArousal"),
            moodDominance: try json.optionalInt("moodDominance")
        )
    }
}

/// 一段对话主题（v7）：`sessionMemory[].topics[]` 的一段。
///
/// 形状与服务端 `MemoryContract.TopicSegment` 的备份 JSON 一致：`timeLabel` 是
/// 「2026-11-18 晚」这类时间段，`summary` 是一句摘要（≤120 字）。最多 12 段。
public struct BackupTopicSegment: Equatable, Sendable, Encodable {
    public var timeLabel: String
    public var summary: String

    public init(timeLabel: String = "", summary: String = "") {
        self.timeLabel = timeLabel
        self.summary = summary
    }

    enum CodingKeys: String, CodingKey { case timeLabel, summary }

    init(json: [String: JSONValue]) throws {
        self.init(
            timeLabel: try json.text("timeLabel"),
            summary: try json.text("summary")
        )
    }
}

/// 一段会话的备忘录（v7，表 `session_memory`；`spec/experience/README.md` §6）。
///
/// 字段名以**服务端 `extensions.sessionMemory[]`** 为准（本地列名不同，由
/// `BackupService` 在编解码层映射）：
/// - `shortUserMessages` / `longUserMessages` / `topicUserMessages` 是三个节流游标
///  （「生成这份摘要时，会话里已有多少条用户消息」）—— 丢了它们，换机后会立刻重算一遍；
/// - `longCheckedUserMessages` / `topicCheckedUserMessages` 是「检查过但不需要更新」的游标；
/// - `topics` 用数组而不是 `topicsJson` 字符串：服务端与 Android 都是这个形状。
///   读取时**兼容**旧的 `topicsJson` 字符串（老数据/手写备份），导出只写数组。
public struct BackupSessionMemory: Equatable, Sendable, Encodable {
    public var sessionId: String
    public var personaId: String
    public var shortSummary: String?
    public var shortUpdatedAt: Int64?
    public var shortUserMessages: Int
    public var longSummary: String?
    public var longUpdatedAt: Int64?
    public var longUserMessages: Int
    public var longCheckedUserMessages: Int
    public var topics: [BackupTopicSegment]
    public var topicUpdatedAt: Int64?
    public var topicUserMessages: Int
    public var topicCheckedUserMessages: Int

    public init(
        sessionId: String = "",
        personaId: String = "",
        shortSummary: String? = nil,
        shortUpdatedAt: Int64? = nil,
        shortUserMessages: Int = 0,
        longSummary: String? = nil,
        longUpdatedAt: Int64? = nil,
        longUserMessages: Int = 0,
        longCheckedUserMessages: Int = 0,
        topics: [BackupTopicSegment] = [],
        topicUpdatedAt: Int64? = nil,
        topicUserMessages: Int = 0,
        topicCheckedUserMessages: Int = 0
    ) {
        self.sessionId = sessionId
        self.personaId = personaId
        self.shortSummary = shortSummary
        self.shortUpdatedAt = shortUpdatedAt
        self.shortUserMessages = max(shortUserMessages, 0)
        self.longSummary = longSummary
        self.longUpdatedAt = longUpdatedAt
        self.longUserMessages = max(longUserMessages, 0)
        self.longCheckedUserMessages = max(longCheckedUserMessages, 0)
        self.topics = Array(topics.prefix(MemoryContract.Summary.TOPIC_MAX_SEGMENTS))
        self.topicUpdatedAt = topicUpdatedAt
        self.topicUserMessages = max(topicUserMessages, 0)
        self.topicCheckedUserMessages = max(topicCheckedUserMessages, 0)
    }

    enum CodingKeys: String, CodingKey {
        case sessionId, personaId
        case shortSummary, shortUpdatedAt, shortUserMessages
        case longSummary, longUpdatedAt, longUserMessages, longCheckedUserMessages
        case topics, topicUpdatedAt, topicUserMessages, topicCheckedUserMessages
    }

    /// 空主题数组不写进 JSON（与服务端「有主题才写 topics」同一条口径）。
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(sessionId, forKey: .sessionId)
        try container.encode(personaId, forKey: .personaId)
        try container.encodeIfPresent(shortSummary, forKey: .shortSummary)
        try container.encodeIfPresent(shortUpdatedAt, forKey: .shortUpdatedAt)
        try container.encode(shortUserMessages, forKey: .shortUserMessages)
        try container.encodeIfPresent(longSummary, forKey: .longSummary)
        try container.encodeIfPresent(longUpdatedAt, forKey: .longUpdatedAt)
        try container.encode(longUserMessages, forKey: .longUserMessages)
        try container.encode(longCheckedUserMessages, forKey: .longCheckedUserMessages)
        if !topics.isEmpty { try container.encode(topics, forKey: .topics) }
        try container.encodeIfPresent(topicUpdatedAt, forKey: .topicUpdatedAt)
        try container.encode(topicUserMessages, forKey: .topicUserMessages)
        try container.encode(topicCheckedUserMessages, forKey: .topicCheckedUserMessages)
    }

    init(json: [String: JSONValue]) throws {
        // 游标的两套键名：服务端形态（`*UserMessages`）优先，Android 本地别名兜底。
        func cursor(_ name: String, alias: String) throws -> Int {
            try json.optionalInt(name) ?? (try json.optionalInt(alias)) ?? 0
        }
        let topics: [BackupTopicSegment]
        if let raw = json["topics"], !raw.isNull {
            topics = try json.objects("topics").map(BackupTopicSegment.init(json:))
        } else if let legacy = try json.optionalText("topicsJson") {
            // 旧写法（主题存成 JSON 字符串）：解不出来就当没有主题。
            let decoded = SessionMemoryV2.decodeTopics(legacy)
            topics = decoded.map {
                BackupTopicSegment(timeLabel: $0.timeLabel, summary: $0.summary)
            }
        } else {
            topics = []
        }
        self.init(
            sessionId: try json.text("sessionId"),
            personaId: try json.text("personaId"),
            shortSummary: try json.optionalText("shortSummary"),
            shortUpdatedAt: try json.optionalInt64("shortUpdatedAt"),
            shortUserMessages: try cursor("shortUserMessages", alias: "shortUserCount"),
            longSummary: try json.optionalText("longSummary"),
            longUpdatedAt: try json.optionalInt64("longUpdatedAt"),
            longUserMessages: try cursor("longUserMessages", alias: "longUserCount"),
            longCheckedUserMessages: try json.optionalInt("longCheckedUserMessages") ?? 0,
            topics: topics,
            topicUpdatedAt: try json.optionalInt64("topicUpdatedAt"),
            topicUserMessages: try cursor("topicUserMessages", alias: "topicUserCount"),
            topicCheckedUserMessages: try json.optionalInt("topicCheckedUserMessages") ?? 0
        )
    }
}

/// 一条主动消息失败记录（v7，**只读**：服务端 `extensions.proactiveFailures[]`）。
///
/// 客户端备份从不写它；导入服务端备份时落到本机诊断表（`ProactiveFailureStore`），
/// 让「他今天怎么一句话没说」在客户端也能查。字段与服务端 `ProactiveFailure.toJson()`
/// 逐字一致。
public struct BackupProactiveFailure: Equatable, Sendable, Codable {
    public var id: String
    public var personaId: String
    public var sessionId: String?
    public var at: Int64
    public var stage: String
    public var reason: String
    public var detail: String
    public var text: String

    public init(
        id: String = "",
        personaId: String = "",
        sessionId: String? = nil,
        at: Int64 = 0,
        stage: String = "",
        reason: String = "",
        detail: String = "",
        text: String = ""
    ) {
        self.id = id
        self.personaId = personaId
        self.sessionId = sessionId
        self.at = at
        self.stage = stage
        self.reason = reason
        self.detail = detail
        self.text = text
    }

    init(json: [String: JSONValue]) throws {
        self.init(
            id: try json.text("id"),
            personaId: try json.text("personaId"),
            sessionId: try json.optionalText("sessionId"),
            at: try json.int64("at"),
            stage: try json.text("stage", "generation"),
            reason: try json.text("reason"),
            detail: try json.text("detail"),
            text: try json.text("text")
        )
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
    /// v5：预设自带的世界预设与话风（应用预设时复制到人格）。
    public var worldJson: String?
    public var speechStyleJson: String?

    public init(
        id: String = "",
        name: String = "",
        description: String = "",
        prompt: String = "",
        category: String = "",
        avatarUri: String? = nil,
        avatarSeed: Int = 0,
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0,
        worldJson: String? = nil,
        speechStyleJson: String? = nil
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
        self.worldJson = PromptGuidanceJSON.clean(worldJson)
        self.speechStyleJson = PromptGuidanceJSON.clean(speechStyleJson)
    }

    enum CodingKeys: String, CodingKey {
        case id, name, description, prompt, category, avatarUri, avatarSeed, createdAt, updatedAt
        case worldJson, speechStyleJson
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
    /// 搜索服务：`MESSAGEAPI` / `TAVILY` / `BRAVE` / `SERPER` / `GENERIC`（v4 新增）。
    /// 缺字段（v1–v3 备份）默认 `MESSAGEAPI`；旧备份里的 `DEEPSEEK` 读进来同样归一化成
    /// `MESSAGEAPI`（与升级前的行为等价）。写备份一律写规范值。
    public var webToolsProvider: String
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
        webToolsProvider: String = WebToolsProvider.messageAPI.rawValue,
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
        // 写备份一律写规范值：调用方传旧值（`DEEPSEEK`）或大小写变体，落盘时都收敛成
        // 枚举名，备份里不再出现历史 provider 名。
        self.webToolsProvider = WebToolsProvider.normalizedStored(webToolsProvider)
        self.webToolsBaseUrl = webToolsBaseUrl
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey {
        case id, name, provider, baseUrl, model
        case allowInsecureHttp, temperature, topP, maxOutputTokens, frequencyPenalty
        case presencePenalty, webToolsEnabled, webToolsProvider, webToolsBaseUrl
        case createdAt, updatedAt
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
            traits: json.stringList("traits"),
            likes: json.stringList("likes"),
            behaviors: json.stringList("behaviors"),
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
            dndOnSleep: try json.bool("dndOnSleep", false),
            // v5 的人设引导三列：v1–v4 的备份里没有，读成 nil 正是「没有设定」。
            worldJson: try json.optionalText("worldJson"),
            speechStyleJson: try json.optionalText("speechStyleJson"),
            activityJson: try json.optionalText("activityJson"),
            // 0.15：老备份没有这个键 → nil（默认中文）。
            languageTag: try json.optionalText("languageTag"),
            // v7：老备份没有拉黑字段 → nil（没拉黑 / 没有未确认提议）。
            blockedUntil: try json.optionalInt64("blockedUntil"),
            blockReason: try json.optionalText("blockReason"),
            blockProposalJson: try json.optionalText("blockProposalJson")
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
            // 旧文件里的 6 个 sync 字段在这里被**忽略**（不再读）：
            // 它们不是跨端契约的一部分，读了也只会让 iOS 的备份跟 Android 不一样。
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
            readAt: try json.optionalInt64("readAt"),
            blocked: try json.bool("blocked", false)
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
            updatedAt: try json.int64("updatedAt"),
            worldJson: try json.optionalText("worldJson"),
            speechStyleJson: try json.optionalText("speechStyleJson")
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
            webToolsProvider: WebToolsProvider.normalizedStored(
                try json.text("webToolsProvider", WebToolsProvider.messageAPI.rawValue)
            ),
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
    /// v6：表情 / 日记便条的数量（预览里给一行，用户才知道这次带走了什么）。
    public var stickers: Int
    public var diaryNotes: Int
    public var exportedAt: Int64
    public var appVersion: String
    /// 0.15 的元数据（导入预览里显示；老备份都是 0 / nil）。
    public var formatVersion: Int
    public var schemaVersion: Int
    public var createdAt: Int64
    /// 文件里写的 sha256（规范化 JSON）；nil = 这份备份没有校验值。
    public var checksum: String?
    /// 封条是不是本机算法写的、且已经复核通过。
    public var checksumVerified: Bool
    /// 封条来自别的客户端（或别的算法）：**不在本机复核**，但绝不因此拒绝导入。
    public var checksumForeign: Bool

    public init(
        personas: Int = 0,
        memories: Int = 0,
        sessions: Int = 0,
        messages: Int = 0,
        rules: Int = 0,
        presets: Int = 0,
        apiConfigs: Int = 0,
        stickers: Int = 0,
        diaryNotes: Int = 0,
        exportedAt: Int64 = 0,
        appVersion: String = "",
        formatVersion: Int = backupFormatVersion,
        schemaVersion: Int = 0,
        createdAt: Int64 = 0,
        checksum: String? = nil,
        checksumVerified: Bool = false,
        checksumForeign: Bool = false
    ) {
        self.personas = personas
        self.memories = memories
        self.sessions = sessions
        self.messages = messages
        self.rules = rules
        self.presets = presets
        self.apiConfigs = apiConfigs
        self.stickers = stickers
        self.diaryNotes = diaryNotes
        self.exportedAt = exportedAt
        self.appVersion = appVersion
        self.formatVersion = formatVersion
        self.schemaVersion = schemaVersion
        self.createdAt = createdAt
        self.checksum = checksum
        self.checksumVerified = checksumVerified
        self.checksumForeign = checksumForeign
    }

    public var isEmpty: Bool {
        personas == 0 && memories == 0 && sessions == 0 && messages == 0
    }

    /// 「3 个人格 · 128 条记忆 · 42 段对话 · 1580 句话 · 1 条主动节奏」。
    public func describe() -> String {
        if isEmpty { return String(localized: "这份备份里什么也没有") }
        var parts = [
            PluralText.format("%d 个人格", personas),
            PluralText.format("%d 条记忆", memories),
            PluralText.format("%d 段对话", sessions),
            PluralText.format("%d 句话", messages)
        ]
        if rules > 0 { parts.append(PluralText.format("%d 条主动节奏", rules)) }
        if stickers > 0 { parts.append(PluralText.format("%d 个表情", stickers)) }
        if diaryNotes > 0 { parts.append(PluralText.format("%d 条日记", diaryNotes)) }
        return PluralText.list(parts)
    }

    /// 「2026年11月14日 01:40 带走」。
    public func exportedAtText() -> String {
        exportedAt <= 0
            ? String(localized: "带走的时间不详")
            : String(format: String(localized: "%@ 带走"), formatBackupMoment(exportedAt))
    }

    /// 「格式 v7 · 数据库 v19」：这份文件长什么样、写它的时候库里有哪几版。
    ///
    /// 数据库版本是 0（v7 之前的老备份，或手写样本）时只显示格式版本 ——
    /// 编一个「数据库 v0」出来只会让用户以为文件坏了。
    public func originText() -> String {
        schemaVersion > 0
            ? String(format: String(localized: "备份格式 v%d · 数据库 v%d"), formatVersion, schemaVersion)
            : String(format: String(localized: "备份格式 v%d"), formatVersion)
    }

    /// 校验状态：本机复核通过就给前 8 位；别的客户端的封条说明「未复核」；没有封条就说没有。
    ///
    /// 三种说法对应三种真实情况（与 Android 的预览文案同一套语义）：
    /// - `已校验` —— 文件带着本机算法标记，`BackupCodec.decode` 已经逐字节复核通过；
    /// - `未在本机复核` —— 封条来自别的客户端，**不影响导入**，只是我们算不出同样的字节；
    /// - `没有校验值` —— v1–v7 老备份 / 服务端备份 / 手写文件。
    public func integrityText() -> String {
        if checksumForeign {
            return String(localized: "校验和来自其他客户端，未在本机复核")
        }
        guard checksumVerified, let checksum, checksum.count >= 8 else {
            return String(localized: "没有校验值（更早的备份）")
        }
        return String(format: String(localized: "已校验：SHA-256 %@"), String(checksum.prefix(8)))
    }

    /// 「2026年11月21日 03:40 · v0.15.0 · 格式 v7 · 数据库 v19 · 校验值 …」。
    ///
    /// 导入预览与设置页共用这一行：用户要能一眼看出「这份备份是哪一版的他」。
    public func metaText() -> String {
        var parts: [String] = []
        parts.append(createdAt > 0 ? formatBackupMoment(createdAt) : exportedAtText())
        if !appVersion.isEmpty { parts.append("v" + appVersion) }
        parts.append(originText())
        parts.append(integrityText())
        return parts.joined(separator: " · ")
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
            stickers: bundle.stickers.count,
            diaryNotes: bundle.diaryNotes.count,
            exportedAt: bundle.exportedAt,
            appVersion: bundle.appVersion,
            formatVersion: bundle.formatVersion,
            schemaVersion: bundle.schemaVersion,
            createdAt: bundle.createdAt,
            checksum: bundle.checksum,
            // 解码只在**复核通过**之后才会把带本机算法标记的文件交出来，
            // 所以这两个布尔值可以由 bundle 自己推出来（见 `BackupCodec.decode`）。
            checksumVerified: bundle.checksum != nil
                && bundle.checksumAlgorithm == BackupIntegrity.algorithm,
            checksumForeign: bundle.checksum != nil
                && bundle.checksumAlgorithm != BackupIntegrity.algorithm
        )
    }
}
