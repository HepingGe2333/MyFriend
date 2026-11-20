import Foundation

// ---------------------------------------------------------------------------
// 数据实体：与 Android `data/local/Entities.kt` 一一对应。
//
// 这里刻意「照抄」Kotlin data class：字段同名同义、可空性一致、枚举字符串一致。
// 原因只有一个 —— 备份 JSON 里的 id / 外键 / 枚举名要能原样写回数据库，
// 两端任何一边自行改名，都会在导入时变成一堆找不到的数据。
//
// 命名差异只有一处：Kotlin 的 `ChatSessionEntity.historySummary` 在备份 JSON 里叫
// `summary`（BackupCodec.kt 里显式映射），实体这边保持 Room 的列名。
//
// 布尔用 Bool（库里是 INTEGER 0/1），时间用 Int64 毫秒时间戳，金额用 Int64 micro-USD，
// 向量用 Data（BLOB）。这些都与 Room 的 affinity 对得上。
// ---------------------------------------------------------------------------

// MARK: - 枚举
//
// 一律 String rawValue = Kotlin 枚举名（大写）。数据库和备份里存的就是这个名字；
// 遇到不认识的档位（新版本写入）用 `init(stored:)` 退回默认值，不抛异常。

public enum ProviderType: String, CaseIterable, Sendable {
    case openai = "OPENAI"
    case gemini = "GEMINI"
    case claude = "CLAUDE"

    public init(stored raw: String?) { self = backupEnumOr(raw, .openai) }
    public var stored: String { rawValue }
}

// 1.9：「原生 Harness」那一整组字段（模式 / 地址 / 同步路径）从实体、备份与建表语句里删干净。
// 为什么不是「留着不用」：同一个字段留在实体与备份里，下一个人就会以为原生模式还在，
// 于是又给它写一套分支。Android 1.8 已经删了，两端字段表必须一样。
// 老备份里的这几个键读进来时被当成未知字段忽略（BackupCodec 的宽容解码）；
// 老数据库里的这几列由 `Schema.migrateLegacyData` 丢掉（那里必须写出旧列名才能删）。

public enum MessageRole: String, CaseIterable, Sendable {
    case user = "USER"
    case assistant = "ASSISTANT"
    case system = "SYSTEM"

    public init(stored raw: String?) { self = backupEnumOr(raw, .user) }
    public var stored: String { rawValue }
}

public enum MessageStatus: String, CaseIterable, Sendable {
    case complete = "COMPLETE"
    case stopped = "STOPPED"
    case error = "ERROR"

    public init(stored raw: String?) { self = backupEnumOr(raw, .complete) }
    public var stored: String { rawValue }
}

public enum SessionSyncState: String, CaseIterable, Sendable {
    case stateless = "STATELESS"
    case notSynced = "NOT_SYNCED"
    case syncing = "SYNCING"
    case synced = "SYNCED"
    case failed = "FAILED"

    public init(stored raw: String?) { self = backupEnumOr(raw, .stateless) }
    public var stored: String { rawValue }
}

public enum MemoryCategory: String, CaseIterable, Sendable {
    case fact = "FACT"
    case preference = "PREFERENCE"
    case event = "EVENT"
    case relationship = "RELATIONSHIP"
    case other = "OTHER"

    public init(stored raw: String?) { self = backupEnumOr(raw, .other) }
    public var stored: String { rawValue }
}

/// 主动唤醒的触发方式：每天固定时刻，或每隔 N 小时。
public enum ProactiveKind: String, CaseIterable, Sendable {
    case daily = "DAILY"
    case interval = "INTERVAL"

    public init(stored raw: String?) { self = backupEnumOr(raw, .daily) }
    public var stored: String { rawValue }
}

/// 规则来源：由对话中的 AI 提议创建，或用户手动创建。
///
/// Android 的枚举只有 `AI` / `MANUAL`，但它的黄金样本里写了 `"source":"MODEL"`
/// （BackupFixtureTest 的样例），读回来会落到 MANUAL —— 这是 Android 侧的一个小漏洞，
/// iOS 这边按「不认识就退默认」处理，不额外扩展枚举，免得导出时又写回 Android 不认的值。
public enum ProactiveSource: String, CaseIterable, Sendable {
    case ai = "AI"
    case manual = "MANUAL"

    public init(stored raw: String?) { self = backupEnumOr(raw, .manual) }
    public var stored: String { rawValue }
}

public enum AgentIntentKind: String, CaseIterable, Sendable {
    case reachOut = "REACH_OUT"
    case followUp = "FOLLOW_UP"

    public init(stored raw: String?) { self = backupEnumOr(raw, .reachOut) }
    public var stored: String { rawValue }
}

public enum AgentIntentStatus: String, CaseIterable, Sendable {
    case pending = "PENDING"
    case sent = "SENT"
    case dropped = "DROPPED"
    case expired = "EXPIRED"

    public init(stored raw: String?) { self = backupEnumOr(raw, .pending) }
    public var stored: String { rawValue }
}

// MARK: - Persona

/// 人格（= 联系人）身上的那些「细分字段」的上限与默认值。
///
/// 为什么集中在一处：这些数字会被编辑界面（滑杆上下界）、提示词渲染、备份归一化
/// 三处同时用到。散着写迟早会出现「界面上能拖到 7，备份里夹回 5」这种对不上的事。
public enum PersonaFieldLimits {
    /// 性格标签最多几个（再多模型也记不住，人也不会用 20 个词形容一个人）。
    public static let maxTraits = 6
    /// 喜欢的事最多几条。
    public static let maxLikes = 8
    /// 行为方式最多几条。
    public static let maxBehaviors = 6
    /// 成长经历的字数上限。
    public static let backstoryMax = 600
    /// 个人设定（称呼、口头禅、忌讳）的字数上限。
    public static let quirksMax = 300
    /// 拉黑原因的字数上限（0.14 §2：一句中文，不用写小作文）。
    public static let blockReasonMax = 200

    public static let levelRange = 1...5
    public static let proactiveLevelDefault = 3
    public static let memoryRetentionDefault = 3
    public static let extractionEveryDefault = 6
    public static let extractionEveryRange = 3...12
    /// 睡眠中被叫醒需要的连续消息数默认值（1–20）。
    ///
    /// 0.12 续 7 从 6 降到 3：原来「睡着」意味着夜里发 1–5 条都只是已读，
    /// 看起来像「发什么都没反应」。新建与种子人格用 3，夜里发几条就能把他叫起来；
    /// 已经在库里的人格不受影响（读列里的值，只有 NULL 才落到这个默认）。
    public static let wakeThresholdDefault = 3
    public static let wakeThresholdRange = 1...20

    /// 1–5 的档位：滑杆、备份、老数据都走这一个夹取。
    public static func clampLevel(_ value: Int?) -> Int {
        min(max(value ?? proactiveLevelDefault, levelRange.lowerBound), levelRange.upperBound)
    }

    public static func clampExtractionEvery(_ value: Int?) -> Int {
        min(max(value ?? extractionEveryDefault, extractionEveryRange.lowerBound),
            extractionEveryRange.upperBound)
    }

    public static func clampWakeThreshold(_ value: Int?) -> Int {
        min(max(value ?? wakeThresholdDefault, wakeThresholdRange.lowerBound),
            wakeThresholdRange.upperBound)
    }
}

/// `traits` / `likes` / `behaviors` 三个列表列在库里是 **TEXT(JSON 数组)**。
///
/// 为什么不做成关联表：这三样东西永远整组读写（提示词要一次拿全、编辑页一次保存），
/// 而且需要跟着备份 JSON 原样搬走；拆成子表只会多三条 join，换不到任何查询好处。
/// 解码永远宽容：坏 JSON、空串元素都会被安静地清掉，绝不抛异常。
///
/// **不按条数截断**（0.12）：以前这里有一个 `limit` 参数，把 `traits` / `likes` /
/// `behaviors` 以及世界里的 `obligations` 悄悄砍到上限以内；同一份人格在两端会被
/// 砍成不同的条数，`sourceHash` 也跟着漂。存储层只负责清洗，条数只是编辑器的提示。
public enum PersonaTags {
    public static func encode(_ values: [String]) -> String? {
        let cleaned = normalize(values)
        if cleaned.isEmpty { return nil }
        guard let data = try? JSONSerialization.data(withJSONObject: cleaned, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    /// 库里读出来：JSON 数组；也容忍「一整个字符串」这种被手改坏的写法。
    public static func decode(_ raw: String?) -> [String] {
        guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return [] }
        if let data = raw.data(using: .utf8),
           let array = try? JSONSerialization.jsonObject(with: data) as? [Any] {
            return normalize(array.map { $0 as? String ?? "" })
        }
        // 不是 JSON：按顿号/逗号/换行拆一次，总比整段丢掉好。
        let pieces = raw.split(whereSeparator: { ",，、\n".contains($0) }).map(String.init)
        return normalize(pieces)
    }

    /// 去空白、去空项、去重（保序）；**不截断**。
    public static func normalize(_ values: [String]) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        for value in values {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty || seen.contains(trimmed) { continue }
            seen.insert(trimmed)
            result.append(trimmed)
        }
        return result
    }
}

public struct PersonaEntity: Equatable, Sendable {
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
    /// 结构化人设卡 JSON（PersonaCardCodec），为空表示还在用旧的一整段提示词。
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
    /// v17 的多维心情（`spec/mood/README.md` §1）：愉快度 -2..2、唤醒度 0..5、掌控感 0..5。
    ///
    /// 三列都可空，而且**必须**可空：`nil` = 还没自动判定过（老数据 / 模型只写过 label），
    /// 读取时退回 `MoodDimensions.fromLegacy` 的词表推断；有值才是模型真的看过对话的结论。
    public var moodValence: Int?
    public var moodArousal: Int?
    public var moodDominance: Int?

    // MARK: 1.9 的细分字段
    //
    // 全部可空 + 有默认值：老库里没有这些列时不回填也能用（迁移只 ADD COLUMN）。
    // `proactiveLevel` / `memoryRetention` 是**行为参数**（主动上限、记忆衰减），
    // 刻意不进提示词正文——他不需要知道自己是「3 级主动」。

    /// 性格标签，如 ["慢热","嘴硬心软"]，≤ 6 个。
    public var traits: [String]
    /// 喜欢的事，如 ["夜里散步"]，≤ 8 个。
    public var likes: [String]
    /// 行为方式，如 ["先听完再给建议"]，≤ 6 个。
    public var behaviors: [String]
    /// 成长经历 / 历史，≤ 600 字。
    public var backstory: String?
    /// 个人设定：称呼、口头禅、忌讳，≤ 300 字。
    public var quirks: String?
    /// 主动程度 1–5：1 很少主动，5 很黏。决定「每天最多主动几次」。
    public var proactiveLevel: Int
    /// 记性 1–5：1 忘得快，5 什么都记得。决定记忆衰减的快慢。
    public var memoryRetention: Int
    /// 每多少条消息抽一次记忆（3–12）。
    public var extractionEvery: Int
    /// 他会不会主动睡觉。false = 他不睡觉，任何时间都会回。
    public var sleepEnabled: Bool
    /// 睡眠中被唤醒需要的连续消息数（1–20）。
    public var wakeThreshold: Int
    /// 睡前是否开启免打扰。**模型可改**，用户不能直接改（§六）。
    public var dndOnSleep: Bool

    // MARK: v13 的人设引导字段（`spec/persona-guidance/README.md` §5）
    //
    // 全部可空：null = 没有世界预设 / 没学过话风 / 此刻没在忙。
    // 三列都是 JSON 文本（宽容解析，坏 JSON 等于没有），理由见 `Schema.personaGuidanceColumns`。

    /// 世界预设 JSON（`WorldPreset`）：他所在的世界、日程、关系、义务。
    public var worldJson: String?
    /// 话风 JSON（`SpeechStyle`）：tone / phrases / habits / avoid。
    ///
    /// 只存**总结后的风格数据**：原始聊天记录不落库、不进日志、不进备份（§3.3）。
    public var speechStyleJson: String?
    /// 当前活动的 JSON（`PersonaActivity`）：他在做什么、忙到什么时候。
    public var activityJson: String?
    /// v14 的翻译缓存（`PersonaTranslation`）：人设/世界/话风翻成当前界面语言后的快照。
    ///
    /// 与 `translationLang` 一起用：`lang` 与当前界面语言不一致、或 `sourceHash` 与源字段
    /// 对不上，就视为没有缓存（下次启动重译）。原文永远保留在本行其他列里，翻译只影响提示词。
    public var translationJson: String?
    /// 这份翻译的目标语言（`zh` / `en` / BCP-47）。
    public var translationLang: String?

    // MARK: v18 的拉黑（`spec/experience/README.md` §2）

    /// 拉黑到期时间（毫秒时间戳）；`nil` = 没被拉黑。
    ///
    /// 「到期」是**读的时候算**的（`BlockPolicy.effective`），不是定时任务：
    /// App 可能几天不开，靠定时器解除必然错过；存到期时间则任何一次读都对。
    public var blockedUntil: Int64?
    /// 拉黑原因（只给他自己看；原因可能为空）。
    public var blockReason: String?
    /// 未确认的拉黑提议 JSON（10 分钟有效；null = 没有提议）。
    ///
    /// 为什么放人格行：Android 18.json 就是这么定义 `blockProposalJson` 的，
    /// 两端 schema 要逐字一致；而且「提议」天然属于某一条人格。
    public var blockProposalJson: String?

    /// 这个人格自己的**提示词语言**（0.15，schema v19）。
    ///
    /// 与页面语言无关：人格说中文还是英文由人格自己决定，用户换界面语言不会让聊天对象
    /// 突然改口。空 = 沿用默认（中文），读取时用 `PromptLanguage.resolve` 解析。
    public var languageTag: String?

    public init(
        id: String,
        name: String,
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
        moodValence: Int? = nil,
        moodArousal: Int? = nil,
        moodDominance: Int? = nil,
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
        translationJson: String? = nil,
        translationLang: String? = nil,
        blockedUntil: Int64? = nil,
        blockReason: String? = nil,
        blockProposalJson: String? = nil,
        languageTag: String? = nil
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
        // 三列要么全空（没判过），要么夹到合法范围里：库里的坏数据不该让提示词出现 -7。
        self.moodValence = moodValence.map(MoodDimensions.clampValence)
        self.moodArousal = moodArousal.map(MoodDimensions.clampArousal)
        self.moodDominance = moodDominance.map(MoodDimensions.clampDominance)
        self.traits = PersonaTags.normalize(traits)
        self.likes = PersonaTags.normalize(likes)
        self.behaviors = PersonaTags.normalize(behaviors)
        // 0.12：长文本字段保留原文（只 trim）。`backstory/quirks` 是人设哈希的输入，
        // 按建议长度截断会让同一份人格在两端算出不同 `sourceHash`；渲染时再按上限取。
        self.backstory = Self.clean(backstory)
        self.quirks = Self.clean(quirks)
        self.proactiveLevel = PersonaFieldLimits.clampLevel(proactiveLevel)
        self.memoryRetention = PersonaFieldLimits.clampLevel(memoryRetention)
        self.extractionEvery = PersonaFieldLimits.clampExtractionEvery(extractionEvery)
        self.sleepEnabled = sleepEnabled
        self.wakeThreshold = PersonaFieldLimits.clampWakeThreshold(wakeThreshold)
        self.dndOnSleep = dndOnSleep
        // JSON 一律走宽容清洗：空串 / 坏 JSON 都当「没有」（§2.1 / §3.2）。
        self.worldJson = PromptGuidanceJSON.clean(worldJson)
        self.speechStyleJson = PromptGuidanceJSON.clean(speechStyleJson)
        self.activityJson = PromptGuidanceJSON.clean(activityJson)
        self.translationJson = PromptGuidanceJSON.clean(translationJson)
        self.translationLang = translationLang?.trimmingCharacters(in: .whitespacesAndNewlines)
        // 0 与负数都当「没拉黑」：到期时间必须是未来某一刻。
        self.blockedUntil = blockedUntil.flatMap { $0 > 0 ? $0 : nil }
        self.blockReason = blockReason.map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200)) }
            .flatMap { $0.isEmpty ? nil : $0 }
        self.blockProposalJson = PromptGuidanceJSON.clean(blockProposalJson)
        // 语言标签只 trim：认不出来的标签留给 `PromptLanguage.resolve` 回落，不在写入时丢信息。
        self.languageTag = languageTag?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 长文本字段的清洗：去首尾空白；**不截断**（0.12）。
    ///
    /// 保留 `limit` 参数是为了兼容备份导入侧「与 Android 同一条边界」的调用：
    /// 传了 limit 就按它截（备份 v5 的 `backstory` 与 Android `take(MAX_BACKSTORY)` 对齐），
    /// 不传就原样保留（库/编辑器/哈希路径）。
    static func clean(_ raw: String?, limit: Int? = nil) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        guard let limit, limit > 0, trimmed.count > limit else { return trimmed }
        return String(trimmed.prefix(limit))
    }

    /// 每天最多主动几次：由 `proactiveLevel` 决定（1→1 次、3→3 次、5→5 次）。
    ///
    /// 为什么就是它本身：这一版把「每天最多主动几次」这个设置删掉了，档位就是次数，
    /// 用户能理解的还是「他黏不黏」这件事，而不是一个数字输入框。
    public var proactiveDailyCap: Int {
        ProactiveLevel.dailyCap(proactiveLevel)
    }

    /// 记性对记忆衰减的系数：记性越好衰减越慢（1 → ×1.6，3 → ×1.0，5 → ×0.5）。
    ///
    /// 只影响「淡下去的速度」，不影响写入与召回排序，所以它是一个安全的旋钮：
    /// 调大调小都不会让他突然忘掉某件具体的事，只是忘得更快或更慢。
    public var memoryDecayFactor: Double {
        MemoryLifecycle.retentionFactor(memoryRetention)
    }
}


// MARK: - API 配置

/// API 配置。
///
/// `apiKeyCipher` / `webToolsApiKeyCipher` / `customHeadersJson` **只存在于本机数据库**：
/// 它们是 Keychain 里的密文引用与自定义 Header，绝不进备份（备份结构是 `BackupApiConfig`，
/// 白名单字段，见 BackupModels.swift）。
public struct ApiConfigEntity: Equatable, Sendable {
    public var id: String
    public var name: String
    public var provider: ProviderType
    public var baseUrl: String
    public var apiKeyCipher: String?
    public var model: String
    /// 自定义 Header JSON；可能夹带 `Authorization`，所以只在本机存。
    public var customHeadersJson: String
    public var allowInsecureHttp: Bool
    public var temperature: Double?
    public var topP: Double?
    public var maxOutputTokens: Int?
    public var frequencyPenalty: Double?
    public var presencePenalty: Double?
    public var createdAt: Int64
    public var updatedAt: Int64
    /// 是否让这个配置启用联网工具（搜索 / 抓网页）。
    public var webToolsEnabled: Bool
    /// 联网工具用哪个搜索服务：`MESSAGEAPI` / `TAVILY` / `BRAVE` / `SERPER` / `GENERIC`。
    ///
    /// 0.12 之前 `MESSAGEAPI` 叫 `DEEPSEEK`：老库/老备份里的旧值在读取时归一化成新值
    /// （见 `WebToolsProvider(stored:)`），写库一律写规范值。
    ///
    /// 存在库里的是规范 §1 的枚举名（TEXT），不是 Swift 枚举：这样以后 Android 加一个
    /// 新 provider、备份先到 iOS 时，iOS 不会在类型层面把这一列丢掉。
    /// 业务侧统一用 `WebToolsProvider(stored:)` 解释它。
    public var webToolsProvider: String
    /// 联网工具地址，留空用该 provider 的默认地址（见 `WebToolsProvider.defaultBaseUrl`）。
    public var webToolsBaseUrl: String
    /// 联网工具专用的密钥（加密存储）；留空则复用主 Key。
    public var webToolsApiKeyCipher: String?

    public init(
        id: String,
        name: String,
        provider: ProviderType = .openai,
        baseUrl: String = "",
        apiKeyCipher: String? = nil,
        model: String = "",
        customHeadersJson: String = "{}",
        allowInsecureHttp: Bool = false,
        temperature: Double? = nil,
        topP: Double? = nil,
        maxOutputTokens: Int? = nil,
        frequencyPenalty: Double? = nil,
        presencePenalty: Double? = nil,
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0,
        webToolsEnabled: Bool = false,
        webToolsProvider: String = WebToolsProvider.messageAPI.rawValue,
        webToolsBaseUrl: String = "",
        webToolsApiKeyCipher: String? = nil
    ) {
        self.id = id
        self.name = name
        self.provider = provider
        self.baseUrl = baseUrl
        self.apiKeyCipher = apiKeyCipher
        self.model = model
        self.customHeadersJson = customHeadersJson
        self.allowInsecureHttp = allowInsecureHttp
        self.temperature = temperature
        self.topP = topP
        self.maxOutputTokens = maxOutputTokens
        self.frequencyPenalty = frequencyPenalty
        self.presencePenalty = presencePenalty
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.webToolsEnabled = webToolsEnabled
        self.webToolsProvider = webToolsProvider
        self.webToolsBaseUrl = webToolsBaseUrl
        self.webToolsApiKeyCipher = webToolsApiKeyCipher
    }
}

// MARK: - 会话与消息

/// 一段对话。
///
/// 1.9 起**一个人只有一段**（库里 `personaId` 上有唯一索引）：会话不再有「新建」这个动作，
/// 创建人格时自动为他建那一段。这个实体本身没变，变的只是仓储与迁移的约束。
public struct ChatSessionEntity: Equatable, Sendable {
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
    public var syncState: SessionSyncState
    public var syncedPersonaVersion: Int?
    public var syncedMemoriesHash: String?
    public var serverSessionId: String?
    public var lastSyncError: String?
    public var lastSyncAt: Int64?
    public var lastMemoryExtractedCount: Int
    /// 早期对话压成的一段摘要：太长的历史不逐字塞进上下文，只留这段「我记得的」。
    public var historySummary: String?
    /// 摘要覆盖到哪条消息为止（该消息及更早的已被压缩）。
    public var historySummarizedUpTo: Int64?
    public var historySummaryAt: Int64?
    /// 记性覆盖（空 = 继承人格）。
    public var memoryRetentionOverride: Int?
    /// 抽记忆间隔覆盖（空 = 继承人格）。
    public var extractionEveryOverride: Int?

    public init(
        id: String,
        personaId: String,
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
        syncState: SessionSyncState = .stateless,
        syncedPersonaVersion: Int? = nil,
        syncedMemoriesHash: String? = nil,
        serverSessionId: String? = nil,
        lastSyncError: String? = nil,
        lastSyncAt: Int64? = nil,
        lastMemoryExtractedCount: Int = 0,
        historySummary: String? = nil,
        historySummarizedUpTo: Int64? = nil,
        historySummaryAt: Int64? = nil,
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
        self.historySummary = historySummary
        self.historySummarizedUpTo = historySummarizedUpTo
        self.historySummaryAt = historySummaryAt
        self.memoryRetentionOverride = memoryRetentionOverride
        self.extractionEveryOverride = extractionEveryOverride
    }

    /// 会话级覆盖优先，没有就听人格的。
    public func effectiveMemoryRetention(persona: PersonaEntity?) -> Int {
        PersonaFieldLimits.clampLevel(memoryRetentionOverride ?? persona?.memoryRetention)
    }

    /// 每多少条消息抽一次记忆。
    public func effectiveExtractionEvery(persona: PersonaEntity?) -> Int {
        PersonaFieldLimits.clampExtractionEvery(extractionEveryOverride ?? persona?.extractionEvery)
    }
}

public struct MessageEntity: Equatable, Sendable {
    public var id: String
    public var sessionId: String
    public var personaId: String
    public var role: MessageRole
    public var content: String
    public var createdAt: Int64
    public var updatedAt: Int64
    public var status: MessageStatus
    public var errorMessage: String?
    public var model: String?
    public var edited: Bool
    /// 这轮他调用过哪些工具（JSON 数组），用于在气泡下展示「查了 2 个网页」。
    public var toolTraceJson: String?
    /// JSON 数组：[{"uri":"file:///...","mime":"image/jpeg"}]，为空表示纯文本消息。
    public var attachmentsJson: String?
    /// 1.9：用户消息在**模型看到它的那一刻**写入的时刻；空 = 还没被看到。
    ///
    /// 为什么放在消息上而不是会话上：界面要判断「这一句到底被读了没」，
    /// 而「已读」不等于「会回」（模型可以 `defer_reply`，也可以在他睡觉时先不回）。
    public var readAt: Int64?
    /// v11：这条消息属于这段会话的第几块（分块历史，见 `spec/messages-blocks/README.md`）。
    ///
    /// 由 `MessageBlockStore` 在写入时**算好**（追加规则 / 锚点插入），业务代码不要自己赋值：
    /// 新建消息默认 0，落库那一刻被替换成真实块号。读出来时它是可信的（区块分页就按它取页）。
    public var blockIndex: Int
    /// v18：这条消息是他在拉黑期间收到的（`spec/experience/README.md` §2）。
    ///
    /// 用户仍能发、消息仍落库，但**模型上下文永久排除**它（解除拉黑之后也不可见）：
    /// 界面靠这一列显示红色感叹号与「对方已将你拉黑」。
    public var blocked: Bool

    public init(
        id: String,
        sessionId: String,
        personaId: String,
        role: MessageRole,
        content: String,
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0,
        status: MessageStatus = .complete,
        errorMessage: String? = nil,
        model: String? = nil,
        edited: Bool = false,
        attachmentsJson: String? = nil,
        toolTraceJson: String? = nil,
        readAt: Int64? = nil,
        blockIndex: Int = 0,
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
        self.blockIndex = blockIndex
        self.blocked = blocked
    }
}

// MARK: - 记忆

public struct MemoryEntity: Equatable, Sendable {
    public var id: String
    public var personaId: String
    public var content: String
    public var category: MemoryCategory
    public var importance: Int
    public var pinned: Bool
    public var sourceSessionId: String?
    public var createdAt: Int64
    public var updatedAt: Int64
    public var lastUsedAt: Int64
    public var useCount: Int
    /// 记忆抽屉：SELF / USER / EVENT；为空时按 category 推断。
    public var drawer: String?
    /// 触发这条记忆的原话片段，用于「这条记忆是怎么来的」。
    public var sourceQuote: String?
    /// ACTIVE 还在用 / DORMANT 沉下去了 / ARCHIVED 已归档（不再进上下文）。
    public var status: String
    /// 还剩多少分量：时间和整理都会让它变小，先掉到阈值以下的人先被归档。
    public var strength: Double
    public var archivedAt: Int64?
    public var consolidatedAt: Int64?
    /// 结构化标签（JSON 数组）：人、事、物、情绪，让「他妹妹那件事」能被找回来。
    public var tags: String?
    /// 这件事发生的时间（不是被记住的时间）；推断不出来就空着。
    public var happenedAt: Int64?
    /// 回忆重构：第一次被写下来时的原文。内容会随回忆漂移，这里是锚点。
    public var originalContent: String?
    /// 被重新回忆过几次 —— 回忆本身会改写它。
    public var driftCount: Int

    public init(
        id: String,
        personaId: String,
        content: String,
        category: MemoryCategory = .other,
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
}

/// 记忆的中文倒排索引：字二元组 → 记忆。主键 (memoryId, term)。
public struct MemoryTermEntity: Equatable, Sendable {
    public var memoryId: String
    public var term: String
    public var weight: Double

    public init(memoryId: String, term: String, weight: Double = 1.0) {
        self.memoryId = memoryId
        self.term = term
        self.weight = weight
    }
}

/// 本地向量索引：一条记忆一个向量。主键 (memoryId, provider)。
public struct MemoryVectorEntity: Equatable, Sendable {
    public var memoryId: String
    /// 嵌入实现的名字（换模型时旧向量自然失效，不会混用）。
    public var provider: String
    public var dim: Int
    /// L2 归一化后的 float32 小端字节。
    public var vector: Data
    public var updatedAt: Int64

    public init(memoryId: String, provider: String, dim: Int, vector: Data, updatedAt: Int64) {
        self.memoryId = memoryId
        self.provider = provider
        self.dim = dim
        self.vector = vector
        self.updatedAt = updatedAt
    }
}

/// 回忆重构的留痕：每次他「想起」一件事，措辞可能被当时的心情改写一点。
public struct MemoryVersionEntity: Equatable, Sendable {
    public var id: String
    public var memoryId: String
    public var at: Int64
    public var content: String
    public var beforeContent: String?
    public var reason: String?
    public var moodLabel: String?

    public init(
        id: String,
        memoryId: String,
        at: Int64,
        content: String,
        beforeContent: String? = nil,
        reason: String? = nil,
        moodLabel: String? = nil
    ) {
        self.id = id
        self.memoryId = memoryId
        self.at = at
        self.content = content
        self.beforeContent = beforeContent
        self.reason = reason
        self.moodLabel = moodLabel
    }
}

/// 一次「每日记忆整理」的留痕。
public struct MemoryConsolidationEntity: Equatable, Sendable {
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
        id: String,
        personaId: String,
        at: Int64,
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
}

// MARK: - 预设 / 规则 / 念头

public struct PersonaPresetEntity: Equatable, Sendable {
    public var id: String
    public var name: String
    public var description: String
    public var prompt: String
    public var category: String
    public var avatarUri: String?
    public var avatarSeed: Int
    public var createdAt: Int64
    public var updatedAt: Int64
    /// v13：预设自带的世界预设与话风（应用预设时**复制**到人格，之后预设变化不自动覆盖人格）。
    public var worldJson: String?
    public var speechStyleJson: String?

    public init(
        id: String,
        name: String,
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
}

public struct ProactiveRuleEntity: Equatable, Sendable {
    public var id: String
    public var personaId: String
    public var sessionId: String?
    public var kind: ProactiveKind
    public var timeOfDayMinutes: Int?
    public var intervalMinutes: Int?
    public var hint: String
    public var note: String
    public var enabled: Bool
    public var pending: Bool
    public var source: ProactiveSource
    public var nextTriggerAt: Int64
    public var lastTriggeredAt: Int64?
    public var lastError: String?
    public var createdAt: Int64
    public var updatedAt: Int64

    public init(
        id: String,
        personaId: String,
        sessionId: String? = nil,
        kind: ProactiveKind = .daily,
        timeOfDayMinutes: Int? = nil,
        intervalMinutes: Int? = nil,
        hint: String = "",
        note: String = "",
        enabled: Bool = true,
        pending: Bool = false,
        source: ProactiveSource = .manual,
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
}

/// 他想在某个时间段来找你：带一个 earliestAt–latestAt 的窗口 —— 人不会准点。
public struct AgentIntentEntity: Equatable, Sendable {
    public var id: String
    public var personaId: String
    public var sessionId: String?
    public var kind: AgentIntentKind
    public var earliestAt: Int64
    public var latestAt: Int64
    public var note: String
    public var hint: String
    public var status: AgentIntentStatus
    public var ruleId: String?
    public var createdAt: Int64
    public var sentAt: Int64?
    public var lastError: String?

    public init(
        id: String,
        personaId: String,
        sessionId: String? = nil,
        kind: AgentIntentKind = .reachOut,
        earliestAt: Int64 = 0,
        latestAt: Int64 = 0,
        note: String = "",
        hint: String = "",
        status: AgentIntentStatus = .pending,
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
}

// MARK: - 用量

/// 一次模型调用用了多少 token。记在本机，不上传；只用于「用量」页的统计。
///
/// 1.9 起不再有预算闸：`costMicroUsd` 这一列还在（老数据里有钱数），但界面只显示 token；
/// 新记录仍然会按内置定价表估一个成本填进去，纯粹是为了将来真要算账时不用重跑。
public struct ApiUsageEntity: Equatable, Sendable {
    public var id: String
    public var at: Int64
    /// CHAT / CONSOLIDATION / SCORING / AGENT / RECALL / EXTRACTION / RECONSOLIDATION
    public var kind: String
    public var personaId: String?
    /// 1.9 起**必须写**：设置页要按「按人排行」统计 token（老行为空，统计时归到「不知道」）。
    public var sessionId: String?
    public var modelName: String?
    public var promptTokens: Int
    public var completionTokens: Int
    /// 估算成本，单位：百万分之一美元（micro-USD），避免浮点误差。
    public var costMicroUsd: Int64

    public init(
        id: String,
        at: Int64,
        kind: String,
        personaId: String? = nil,
        sessionId: String? = nil,
        modelName: String? = nil,
        promptTokens: Int = 0,
        completionTokens: Int = 0,
        costMicroUsd: Int64 = 0
    ) {
        self.id = id
        self.at = at
        self.kind = kind
        self.personaId = personaId
        self.sessionId = sessionId
        self.modelName = modelName
        self.promptTokens = promptTokens
        self.completionTokens = completionTokens
        self.costMicroUsd = costMicroUsd
    }
}

// MARK: - v18 心理活动（`spec/experience/README.md` §3）

/// 他的一句私密念头 + 那一刻的心情快照。
///
/// 它不是消息：不发出去、不进聊天记录、用户也看不到（除非以后做「他最近在想什么」）。
/// 它存在的意义只有一个 —— 让**下一轮**的他知道「我刚刚心里想过这个」，
/// 从而接上自己刚才的情绪，而不是每轮都像第一次见到对方。
public struct MentalActivityEntity: Equatable, Sendable {
    public var id: String
    /// 哪一段会话里的念头（可空：主动／无会话时也可能产生）。
    public var sessionId: String?
    public var personaId: String
    /// 念头产生的时刻（毫秒时间戳）。
    public var at: Int64
    /// ≤60 字的念头（规范 §3：私密、不发给用户）。
    public var thought: String
    /// 那一刻的心情标签与强度（微调之后的快照）。
    public var moodLabel: String?
    public var moodIntensity: Int?
    /// 那一刻的三维心情快照（v17 的三列；没判过时为空）。
    public var moodValence: Int?
    public var moodArousal: Int?
    public var moodDominance: Int?

    public init(
        id: String,
        sessionId: String?,
        personaId: String,
        at: Int64,
        thought: String,
        moodLabel: String? = nil,
        moodIntensity: Int? = nil,
        moodValence: Int? = nil,
        moodArousal: Int? = nil,
        moodDominance: Int? = nil
    ) {
        self.id = id
        self.sessionId = sessionId
        self.personaId = personaId
        self.at = at
        self.thought = String(thought.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
        self.moodLabel = moodLabel
        self.moodIntensity = moodIntensity
        self.moodValence = moodValence.map(MoodDimensions.clampValence)
        self.moodArousal = moodArousal.map(MoodDimensions.clampArousal)
        self.moodDominance = moodDominance.map(MoodDimensions.clampDominance)
    }

    /// 三维快照（三列都有值才算；与 `MoodRepository.dimensionsOf` 同一口径）。
    public var dimensions: MoodDimensions? {
        guard let valence = moodValence, let arousal = moodArousal, let dominance = moodDominance else {
            return nil
        }
        return MoodDimensions(valence: valence, arousal: arousal, dominance: dominance)
    }
}

// MARK: - v18 会话备忘录（`spec/experience/README.md` §6）

/// 一段会话的两份滚动摘要（「你的备忘录」）。
///
/// 为什么不复用 `chat_sessions.historySummary`：那个是**上下文压缩**（把早已滚出窗口的
/// 历史压成一段），语义是「更早说过什么」；这里的短期摘要覆盖**最近 5 轮**，
/// 长期摘要覆盖「更早的对话」，是每分钟都在变的工作记忆。混在一起会让两边互相覆盖。
public struct SessionMemoryEntity: Equatable, Sendable {
    public var sessionId: String
    public var personaId: String
    /// ≤200 字：最近 5 轮。
    public var shortSummary: String?
    /// ≤500 字：更早的对话。
    public var longSummary: String?
    /// 生成短期摘要时，会话里已有多少条用户消息（节流用）。
    public var shortUserCount: Int
    /// 生成长期摘要时，会话里已有多少条用户消息（节流用）。
    public var longUserCount: Int
    public var shortUpdatedAt: Int64?
    public var longUpdatedAt: Int64?
    /// 0.15 主题总结（v19）：`[{"timeLabel":"…","summary":"…"}]` 的 JSON。
    ///
    /// 存 JSON 而不是三段文本：时间段要与正文分开（注入排版、检索加权都要用）。
    /// 坏 JSON 由 `SessionMemoryV2.decodeTopics` 安静降级成空数组。
    public var topicsJson: String?
    /// 生成主题总结时，会话里已有多少条用户消息（15–40 轮的节流用）。
    public var topicUserCount: Int
    public var topicUpdatedAt: Int64?
    public var updatedAt: Int64

    public init(
        sessionId: String,
        personaId: String,
        shortSummary: String? = nil,
        longSummary: String? = nil,
        shortUserCount: Int = 0,
        longUserCount: Int = 0,
        shortUpdatedAt: Int64? = nil,
        longUpdatedAt: Int64? = nil,
        topicsJson: String? = nil,
        topicUserCount: Int = 0,
        topicUpdatedAt: Int64? = nil,
        updatedAt: Int64 = 0
    ) {
        self.sessionId = sessionId
        self.personaId = personaId
        self.shortSummary = Self.clean(shortSummary, limit: SessionMemoryPolicy.shortLimit)
        self.longSummary = Self.clean(longSummary, limit: SessionMemoryPolicy.longLimit)
        self.shortUserCount = max(shortUserCount, 0)
        self.longUserCount = max(longUserCount, 0)
        self.shortUpdatedAt = shortUpdatedAt
        self.longUpdatedAt = longUpdatedAt
        self.topicsJson = topicsJson?.trimmingCharacters(in: .whitespacesAndNewlines)
            .isEmpty == true ? nil : topicsJson
        self.topicUserCount = max(topicUserCount, 0)
        self.topicUpdatedAt = topicUpdatedAt
        self.updatedAt = updatedAt
    }

    /// 解码后的主题总结（空 = 还没总结过 / 坏数据）。
    public var topics: [MemoryContract.TopicSegment] {
        SessionMemoryV2.decodeTopics(topicsJson)
    }

    static func clean(_ raw: String?, limit: Int) -> String? {
        let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(limit))
    }
}
