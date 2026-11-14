import Foundation

// 来源：app/src/main/java/com/myfriend/app/data/repo/BackupRepository.kt
//
// 「把他带走」与「从备份里接他回来」。
//
// ## 这不是数据库同步
//
// 导出的一份 JSON 里装的是他：人格（人设卡、作息、心情）、记忆（含分量与漂移后的正文）、
// 你们的每一句对话、他给自己定的主动节奏、以及能带走的设置。
// 换手机、重装、甚至只是想留个底，都用同一份文件。
//
// ## 三条不变量（与 Android 一致）
//
// 1. **密钥永不进备份**。导出只走 `BackupApiConfig` 白名单；编完再用
//    `BackupCodec.containsSecretField` 自检一遍，宁可导出失败也不写进一个明文密钥。
// 2. **导入是一个事务**。全部写库工作在 `inTransaction { }` 里，任何一步失败整体回滚，
//    绝不留下「一半的他」——半个备份比没有备份更糟，用户根本看不出少了什么。
// 3. **API 配置从不删**。Key 在这台设备的 Keychain 里，备份里没有；
//    所以「替换」也只覆盖配置里的地址与模型，把 Key 留着。真丢了就让用户重新填一次。
//
// ## 1.9 的备份（v2）
//
// - 人格带上他的「细分字段」（性格 / 喜欢 / 习惯 / 过去 / 设定 / 主动程度 / 记性 / 抽记忆间隔 /
//   会不会睡觉 / 唤醒阈值 / 睡前免打扰）——换端之后他还是同一个人；
// - **sessions 每人一条**：备份里同一人格出现多段时，导入只保留最新那段
//   （与数据库的「一人一段」唯一索引同一条规则，否则导入会撞唯一索引）；
// - `messages.readAt` 跟着走：已读与否不该因为换手机而变；
// - `settings` 只剩外观与「常驻思考」；旧的记忆开关 / 节奏 / 额度 / 主动次数不再写、也不再读。
//
// ## 与 Android 的差别
//
// - Android 的 `PersonaRevision`（人设版本历史）iOS 还没有对应表：备份结构里本来也没有它，
//   所以这里无需处理，也不假装删过；
// - 导入后要重建「倒排索引 + 向量索引」：Android 只重建 memory_terms，iOS 顺手把向量也补上
//   （因为向量表同样可能为空，而检索两条路都依赖它）；
// - 设置写 `SettingsStore`：它不抛异常，所以「写设置失败只警告」在这一版等价于
//   「activePersonaId 指向不存在的人格时给一条中文警告并交还默认人格」。

public enum ImportMode: String, CaseIterable, Sendable {
    /// 按 id 去重合并：同一条用备份里的更新，备份里有而本机没有的追加进来。
    case merge
    /// 先清空这台手机上的他，再照备份原样接回来（API 配置与密钥不动）。
    case replace
}

/// 接回来之后给用户看的一句话：接回了多少、丢了多少、有什么要嘱咐的。
public struct ImportSummary: Equatable, Sendable {
    public var personas: Int
    public var memories: Int
    public var sessions: Int
    public var messages: Int
    public var rules: Int
    public var skipped: Int
    public var warnings: [String]
    public var mode: ImportMode
    public var presets: Int

    public init(
        personas: Int = 0,
        memories: Int = 0,
        sessions: Int = 0,
        messages: Int = 0,
        rules: Int = 0,
        skipped: Int = 0,
        warnings: [String] = [],
        mode: ImportMode = .merge,
        presets: Int = 0
    ) {
        self.personas = personas
        self.memories = memories
        self.sessions = sessions
        self.messages = messages
        self.rules = rules
        self.skipped = skipped
        self.warnings = warnings
        self.mode = mode
        self.presets = presets
    }

    /// 「把他接回来了：3 个人格 · 128 条记忆 · 42 段对话 · 1580 句话」。
    public func describe() -> String {
        var text = mode == .replace
            ? String(localized: "照备份重新接回了他")
            : String(localized: "把他接回来了")
        // 每个计数单独成句再由 `·` 拼起来：中英的单复数各自有 `%d …` / `%d ….one` 两条资源，
        // 一整句塞四个 `%d` 就没法让英文只把「1」说对。
        text += String(localized: "：") + PluralText.list([
            PluralText.format("%d 个人格", personas),
            PluralText.format("%d 条记忆", memories),
            PluralText.format("%d 段对话", sessions),
            PluralText.format("%d 句话", messages)
        ])
        if rules > 0 {
            text += " · " + PluralText.format("%d 条主动节奏", rules)
        }
        if presets > 0 {
            text += " · " + PluralText.format("%d 个预设", presets)
        }
        if skipped > 0 {
            text += "；" + PluralText.format("有 %d 条没接回来", skipped)
        }
        if !warnings.isEmpty {
            let separator = String(localized: "；")
            text += separator + warnings.joined(separator: separator)
        }
        return text
    }
}

/// 备份读不懂 / 写不进去时抛出的一句话；界面上直接显示 `message`。
public struct BackupImportError: Error, LocalizedError, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var errorDescription: String? { message }
    public var description: String { message }
}

public final class BackupService {

    /// 每个人格最多带多少条「整理过记忆」的留痕。
    public static let exportConsolidationsPerPersona = 20
    /// 每个人格最多带多少条还没过期的念头。
    public static let exportIntentsPerPersona = 30

    private let stores: DatabaseStores
    private let settings: SettingsProviding
    private let vectorIndex: MemoryVectorIndex
    private let appVersion: String
    private let now: () -> Int64
    /// 备份里的头像路径还能不能落到一个真实文件上（测试可注入临时目录）。
    private let avatarFileExists: (String) -> Bool

    public init(
        stores: DatabaseStores,
        settings: SettingsProviding,
        vectorIndex: MemoryVectorIndex? = nil,
        appVersion: String = "",
        avatarFileExists: @escaping (String) -> Bool = { MediaStorage.shared.exists(uriString: $0) },
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.stores = stores
        self.settings = settings
        self.vectorIndex = vectorIndex ?? MemoryVectorIndex(stores: stores, now: now)
        self.appVersion = appVersion
        self.avatarFileExists = avatarFileExists
        self.now = now
    }

    /// 备份文件建议名字：`myfriend-backup-20261114-0140.json`。
    public func suggestedFileName(now: Int64? = nil) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyyMMdd-HHmm"
        let stamp = formatter.string(from: Date(timeIntervalSince1970: Double(now ?? self.now()) / 1000.0))
        return "myfriend-backup-\(stamp).json"
    }

    /// 把「他」收集成一份可以带走的 JSON 文本。
    public func export() throws -> String {
        let exportedAt = now()
        let personas = (try? stores.personas.all(orderBy: "updatedAt DESC")) ?? []
        let sessions = ((try? stores.sessions.all(orderBy: "updatedAt DESC")) ?? [])
        // 每条消息按会话取（MessageStore 没有「取全部」的便利方法，也不该为备份加一张全表扫）。
        let messages = sessions.flatMap { (try? stores.messages.bySession($0.id)) ?? [] }
        let memories = personas.flatMap { (try? stores.memories.fetch(
            where: "personaId = ?",
            [.text($0.id)],
            orderBy: "pinned DESC, importance DESC, updatedAt DESC"
        )) ?? [] }
        let consolidations = personas.flatMap { persona in
            Array(((try? stores.memoryConsolidations.byPersona(persona.id)) ?? [])
                .prefix(Self.exportConsolidationsPerPersona))
        }
        let intents = personas.flatMap { persona in
            Array(((try? stores.agentIntents.byPersona(persona.id)) ?? [])
                .prefix(Self.exportIntentsPerPersona))
        }

        let bundle = BackupBundle(
            format: backupFormatName,
            formatVersion: backupFormatVersion,
            appVersion: appVersion,
            exportedAt: exportedAt,
            apiKeysOmitted: true,
            personas: personas.map(Self.toBackupPersona),
            memories: memories.map(Self.toBackupMemory),
            sessions: sessions.map(Self.toBackupSession),
            messages: messages.map(Self.toBackupMessage),
            presets: ((try? stores.presets.all(orderBy: "updatedAt DESC")) ?? []).map(Self.toBackupPreset),
            proactiveRules: ((try? stores.proactiveRules.all(orderBy: "nextTriggerAt ASC")) ?? [])
                .map(Self.toBackupRule),
            agentIntents: intents.map(Self.toBackupIntent),
            memoryConsolidations: consolidations.map(Self.toBackupConsolidation),
            apiConfigs: ((try? stores.apiConfigs.all(orderBy: "updatedAt DESC")) ?? [])
                .map(Self.toBackupApiConfig),
            settings: settings.load().toBackupSettings()
        )

        let encoded = try BackupCodec.encode(bundle)
        // 最后一道闸：真出现了密钥字段就中止导出，不写文件。
        if BackupCodec.containsSecretField(encoded) {
            throw BackupImportError(String(localized: "备份里出现了密钥字段，已中止导出"))
        }
        return encoded
    }

    /// 读进来的备份先解码看一眼，不写库。
    ///
    /// 界面上先让用户确认「几个人格、多少条记忆、多少句话」，再决定合并还是替换。
    public func preview(fileText: String) -> BackupDecodeResult {
        BackupCodec.decode(fileText)
    }

    /// 预览用的一行摘要（读不懂时返回 nil，原因走 `preview`）。
    public func previewOf(fileText: String) -> BackupPreview? {
        preview(fileText: fileText).bundle.map(BackupPreview.from)
    }

    /// 从 JSON 文本把他接回来。读不懂就抛 `BackupImportError`，库里什么都不改。
    public func importBackup(text: String, mode: ImportMode) throws -> ImportSummary {
        switch BackupCodec.decode(text) {
        case .failure(let reason):
            throw BackupImportError(reason)
        case .success(let bundle, let warnings):
            var summary = try importBundle(bundle, mode: mode)
            summary.warnings = warnings + summary.warnings
            return summary
        }
    }

    /// `import(text:mode:)` 的别名：`import` 是 Swift 关键字，用反引号调用起来别扭，
    /// 但保留同一个入口名字，方便和 Android 的 `importJson` 对照。
    public func `import`(text: String, mode: ImportMode) throws -> ImportSummary {
        try importBackup(text: text, mode: mode)
    }

    /// 把已经解码好的备份写进库。
    ///
    /// 单个事务：清空（仅替换模式）、人格、记忆、对话、消息、规则、念头、整理记录一起提交，
    /// 失败整体回滚。设置走本机设置，只能在事务成功之后单独写——所以它若失败，
    /// 只提示「设置没接回来」，绝不把已经写好的他回滚掉。
    public func importBundle(_ bundle: BackupBundle, mode: ImportMode) throws -> ImportSummary {
        var warnings: [String] = []
        var skipped = 0

        // 备份里带着 `avatarUri`，但图片二进制从来不进备份（换台手机路径必然失效）。
        // 指向不存在文件的头像直接清空：留着它，界面会画一个永远加载不出来的头像，
        // 用户也看不出「这是备份带来的坏路径」——把它算进 warnings 才诚实。
        var staleAvatars = 0
        let personas = bundle.personas.map(Self.toEntity).map { persona -> PersonaEntity in
            let uri = persona.avatarUri?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !uri.isEmpty, !avatarFileExists(uri) else { return persona }
            var fixed = persona
            fixed.avatarUri = nil
            staleAvatars += 1
            return fixed
        }
        if staleAvatars > 0 {
            warnings.append(PluralText.format("有 %d 个人格的头像文件找不到，头像已经清空", staleAvatars))
        }
        let memories = bundle.memories.map(Self.toEntity)
        // 一人一段（§八）：备份里同一人格有多段时只留最新那条，其余连同其消息一起不进库。
        // 这不是「优化」：`chat_sessions.personaId` 上有唯一索引，不合并就会直接写失败。
        let (keptSessions, droppedSessions) = Self.oneSessionPerPersona(bundle.sessions)
        if droppedSessions > 0 {
            warnings.append(PluralText.format("备份里有 %d 段多余的对话：一人只留最新的一段，其余没有接回来", droppedSessions))
        }

        // 合并模式下还要处理「同一个人在本机已经有另一段对话」：唯一索引不允许两段，
        // 直接删掉本机那一段等于悄悄销毁用户最近的聊天，而丢掉备份那一段等于「只接回来一半」。
        // 所以两段落到同一行上：会话行用本机的 id，备份那段的消息改挂到这个 id 上（sessionIdRemap），
        // 于是两边说过的话都还在同一段对话里。替换模式不需要：那边会先全清。
        var sessions: [ChatSessionEntity] = []
        var mergedSessions = 0
        var localSessionsByPersona: [String: ChatSessionEntity] = [:]
        if mode == .merge {
            for session in ((try? stores.sessions.all()) ?? []) {
                localSessionsByPersona[session.personaId] = session
            }
        }
        var sessionIdRemap: [String: String] = [:]
        for backupSession in keptSessions {
            var entity = Self.toEntity(backupSession)
            if let local = localSessionsByPersona[entity.personaId], local.id != entity.id {
                mergedSessions += 1
                sessionIdRemap[entity.id] = local.id
                entity.id = local.id
            }
            sessions.append(entity)
        }
        if mergedSessions > 0 {
            warnings.append(PluralText.format(
                "有 %d 段对话和本机已有的对话属于同一个人：已经并进本机那段里，接回来以后还是同一段",
                mergedSessions
            ))
        }
        let presets = bundle.presets.map(Self.toEntity)
        let rules = bundle.proactiveRules.map(Self.toEntity)
        let intents = bundle.agentIntents.map(Self.toEntity)
        let consolidations = bundle.memoryConsolidations.map(Self.toEntity)

        // 消息得有个能落脚的会话：合并模式下可以落进本机已有的同一段对话，
        // 替换模式下就只能落在备份自带的对话里。
        var knownSessionIds = Set(sessions.map(\.id))
        if mode == .merge {
            knownSessionIds.formUnion(((try? stores.sessions.all()) ?? []).map(\.id))
        }
        let messages = bundle.messages.map { message -> MessageEntity in
            var entity = Self.toEntity(message)
            // 备份那段已经并进本机那段：消息跟着换 id，否则会因为没有落脚点被丢掉。
            if let target = sessionIdRemap[entity.sessionId] { entity.sessionId = target }
            return entity
        }.filter { message in
            let ok = knownSessionIds.contains(message.sessionId)
            if !ok { skipped += 1 }
            return ok
        }

        let messagesWithAttachments = bundle.messages.filter {
            !($0.attachmentsJson ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }.count
        // 合并模式下，本机同 id 的消息会保留自己的附件引用（文件就在磁盘上），
        // 这部分不该被算进「图片没跟过来」——那是跨设备的说法。
        let locallyBackedAttachments = Self.countLocallyBackedAttachments(
            messages, stores: stores, merge: mode == .merge
        )
        let unbackedAttachments = max(messagesWithAttachments - locallyBackedAttachments, 0)
        if unbackedAttachments > 0 {
            warnings.append(PluralText.format("有 %d 句话原本带着图片，图片没有跟着备份走，现在只剩文字", unbackedAttachments))
        }

        try stores.database.inTransaction {
            if mode == .replace {
                // 顺序：先清子表（消息、索引、回声、版本），再清父表。
                try stores.messages.deleteAll()
                try stores.sessions.deleteAll()
                try stores.memoryTerms.deleteAll()
                try stores.memoryVersions.deleteAll()
                MemoryEchoStore(database: stores.database).deleteAll()
                try stores.memories.deleteAll()
                try stores.memoryConsolidations.deleteAll()
                try stores.agentIntents.deleteAll()
                try stores.proactiveRules.deleteAll()
                try stores.presets.deleteAll()
                try stores.personas.deleteAll()
            }

            try stores.personas.upsert(personas)
            try stores.presets.upsert(presets)
            try stores.memories.upsert(memories)
            // 记忆的倒排索引要跟着一起建：否则接回来以后按关键词想不起事，只剩 LIKE 兜底。
            for memory in memories {
                try? stores.memoryTerms.deleteTerms(forMemory: memory.id)
                let terms = ChineseTerms.terms(memory.content).map {
                    MemoryTermEntity(memoryId: memory.id, term: $0.term, weight: $0.weight)
                }
                if !terms.isEmpty { try? stores.memoryTerms.upsert(terms) }
            }
            // 会话：合并模式下与本机同一段对话**不能整行替换**（0.10.2 审计 P1-9）：
            // 备份里的预览 / 未读是导出那一刻的快照，比本机旧的话会把首页预览倒退、
            // 把已读又算一遍、把摘要与抽记忆计数冲回去。这里按「谁更新用谁 / 缺了就补」逐字段合并。
            for session in sessions {
                if mode == .merge,
                   let local = (try? stores.sessions.byId(session.id)) ?? nil {
                    try stores.sessions.upsert(Self.mergeSessionIntoLocal(session, local: local))
                } else {
                    try stores.sessions.upsert(session)
                }
            }
            // 消息：合并模式下同 id 的消息保留本机的 `attachmentsJson`。
            // 备份只带元数据、不带图片二进制，整行 REPLACE 会把它写成 NULL →
            // 本机所有带图消息的图片引用被抹掉，文件却还在磁盘上变成孤儿（同样见 P1-9）。
            for message in messages {
                if mode == .merge,
                   let local = (try? stores.messages.byId(message.id)) ?? nil {
                    try stores.messages.upsert(Self.mergeMessageIntoLocal(message, local: local))
                } else {
                    try stores.messages.upsert(message)
                }
            }
            for rule in rules { try stores.proactiveRules.upsert(rule) }
            for intent in intents { try stores.agentIntents.upsert(intent) }
            for consolidation in consolidations { try stores.memoryConsolidations.upsert(consolidation) }

            // API 配置：只覆盖非敏感字段；已存在的配置把本机 Key 留着，新配置 Key 留空等用户填。
            // 配置本身从不删除。
            //
            // 安全边界（0.10.2 安全审计 P1-0）：`baseUrl` / `allowInsecureHttp` /
            // `webToolsBaseUrl` 是「这台设备对这个服务商的信任决定」，**不从备份迁移**。
            // 否则只要攻击者知道某条配置的 id（任何一份导出文件里都有），拼一份备份把地址
            // 改成自己的主机，用户「合并导入」之后本机 Key 与全部聊天就发到攻击者那里了。
            for imported in bundle.apiConfigs {
                if let existing = (try? stores.apiConfigs.byId(imported.id)) ?? nil {
                    let merge = Self.mergeInto(imported, existing: existing)
                    try stores.apiConfigs.upsert(merge.entity)
                    if let foreign = merge.foreignBaseUrl {
                        warnings.append(
                            String(format: String(localized: "配置「%@」在备份里的地址与本机不同，已保留本机地址（不会跟着备份改）"), merge.entity.name)
                        )
                        // 只进 warnings（界面会逐条显示）：Kit 层没有日志设施，
                        // 而这条差异本来就该让用户看到，而不是躺在 debug.log 里。
                        _ = foreign
                    }
                    if merge.keptLocalInsecureHttp {
                        warnings.append(
                            String(format: String(localized: "配置「%@」的「允许不安全 HTTP」保持本机设置，不改从备份"), merge.entity.name)
                        )
                    }
                } else {
                    let created = Self.toNewEntity(imported, now: now())
                    try stores.apiConfigs.upsert(created)
                    if imported.allowInsecureHttp {
                        warnings.append(
                            String(format: String(localized: "配置「%@」在备份里开着「允许不安全 HTTP」，接回来一律关掉；需要的话请自己再打开"), created.name)
                        )
                    }
                }
            }
        }

        // 事务外做索引补建：它们不参与「他有没有被完整接回来」这件事，
        // 失败也只是「这次检索慢一点 / 少一路信号」。
        vectorIndex.resetEnsured()
        _ = vectorIndex.pruneOrphans()
        vectorIndex.ensureIndex()

        let configsMissingKey = bundle.apiConfigs.filter { config in
            if let existing = (try? stores.apiConfigs.byId(config.id)) ?? nil {
                return (existing.apiKeyCipher ?? "").isEmpty
            }
            return true
        }.count
        if configsMissingKey > 0 {
            warnings.append(PluralText.format(
                "有 %d 个 API 配置还缺 Key（备份里从来不装密钥），去「API 配置」里补一次就好",
                configsMissingKey
            ))
        }

        if let backupSettings = bundle.settings {
            restoreSettings(backupSettings, warnings: &warnings)
        } else {
            warnings.append(String(localized: "这份备份里没有设置，设置页保持你现在的样子"))
        }

        return ImportSummary(
            personas: personas.count,
            memories: memories.count,
            sessions: sessions.count,
            messages: messages.count,
            rules: rules.count,
            skipped: skipped,
            warnings: warnings,
            mode: mode,
            presets: presets.count
        )
    }

    /// 把设置逐项写回本机。
    ///
    /// 只写备份里真的带来的那几项；聊天背景图片（设备路径）与本地引导状态一律不碰。
    private func restoreSettings(_ backup: BackupSettings, warnings: inout [String]) {
        var restored = settings.load().applying(backup)
        // 「正在聊的人」必须真的存在：备份里选中的那个人格可能没在这一次接回来的内容里。
        if let active = restored.activePersonaId, ((try? stores.personas.byId(active)) ?? nil) == nil {
            warnings.append(String(localized: "备份里选中的那个人格不在这份备份里，已把「正在聊的人」交还给默认人格"))
            restored.activePersonaId = nil
        }
        settings.save(restored)
    }

    /// 一人一段：按 `COALESCE(lastMessageAt, updatedAt)` 取每人格最新的那一段。
    ///
    /// 排序里带上 createdAt 与 id，是为了「同一毫秒的两段」也有确定的赢家——
    /// 导入两次必须得到同一个结果，否则「同一份备份接回来两次」会出现不同的对话。
    static func oneSessionPerPersona(_ sessions: [BackupSession]) -> (kept: [BackupSession], dropped: Int) {
        let ordered = sessions.sorted { lhs, rhs in
            let left = lhs.lastMessageAt ?? lhs.updatedAt
            let right = rhs.lastMessageAt ?? rhs.updatedAt
            if left != right { return left > right }
            if lhs.createdAt != rhs.createdAt { return lhs.createdAt > rhs.createdAt }
            return lhs.id > rhs.id
        }
        var seen = Set<String>()
        var kept: [BackupSession] = []
        var dropped = 0
        for session in ordered {
            let personaId = session.personaId.trimmingCharacters(in: .whitespacesAndNewlines)
            if personaId.isEmpty || seen.contains(personaId) {
                dropped += 1
                continue
            }
            seen.insert(personaId)
            kept.append(session)
        }
        // 保留顺序按「导入前的原顺序」稳定输出：文件里怎么写，库里就怎么进（便于对账）。
        let keptIds = Set(kept.map(\.id))
        return (sessions.filter { keptIds.contains($0.id) }, dropped)
    }

    /// 合并导入时「同 id 的会话」怎么落库：**只补缺失字段 / 按时间取新**，绝不整行替换。
    ///
    /// 本机是「当前状态」的唯一事实来源：预览 / 未读 / 标题 / 置顶 / 摘要进度都可能是
    /// 导出之后才更新的。备份只有「导出那一刻」。
    static func mergeSessionIntoLocal(_ imported: ChatSessionEntity, local: ChatSessionEntity) -> ChatSessionEntity {
        var merged = local
        // 绑定信息：本机没填才用备份的（备份那台机器可能绑过配置）。
        if (merged.apiConfigId ?? "").isEmpty, let importedConfig = imported.apiConfigId, !importedConfig.isEmpty {
            merged.apiConfigId = importedConfig
        }
        if merged.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !imported.model.isEmpty {
            merged.model = imported.model
        }
        if merged.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !imported.title.isEmpty {
            merged.title = imported.title
        }
        // 预览 / 最后一条时间：只接受**更新**的那一份（备份比本机旧时不能倒退）。
        let localAt = local.lastMessageAt ?? 0
        let importedAt = imported.lastMessageAt ?? 0
        if importedAt > localAt {
            merged.lastMessageAt = imported.lastMessageAt
            merged.lastMessagePreview = imported.lastMessagePreview ?? merged.lastMessagePreview
        } else if merged.lastMessagePreview == nil {
            merged.lastMessagePreview = imported.lastMessagePreview
        }
        // 未读与置顶是本机这台设备的阅读状态，不从备份覆盖。
        // 摘要：谁的覆盖更远用谁（等价于「只有更新的那次压缩生效」）。
        let localUpTo = local.historySummarizedUpTo ?? -1
        let importedUpTo = imported.historySummarizedUpTo ?? -1
        if importedUpTo > localUpTo,
           let summary = imported.historySummary?.trimmingCharacters(in: .whitespacesAndNewlines),
           !summary.isEmpty {
            merged.historySummary = imported.historySummary
            merged.historySummarizedUpTo = imported.historySummarizedUpTo
            merged.historySummaryAt = imported.historySummaryAt
        }
        // 抽记忆计数：取更大的那个（只往前推进，不往回退）。
        merged.lastMemoryExtractedCount = max(local.lastMemoryExtractedCount, imported.lastMemoryExtractedCount)
        merged.lastPersonaVersion = max(local.lastPersonaVersion, imported.lastPersonaVersion)
        // 会话级覆盖：本机没有才补。
        if merged.memoryRetentionOverride == nil { merged.memoryRetentionOverride = imported.memoryRetentionOverride }
        if merged.extractionEveryOverride == nil { merged.extractionEveryOverride = imported.extractionEveryOverride }
        // 6 个 sync 列只在库里且 iOS 不做同步：保留本机值（`ChatSessionEntity` 的默认/现值）。
        merged.updatedAt = max(local.updatedAt, imported.updatedAt)
        return merged
    }

    /// 合并导入时「同 id 的消息」怎么落库：本机的附件引用与更新过的正文不能被备份抹掉。
    static func mergeMessageIntoLocal(_ imported: MessageEntity, local: MessageEntity) -> MessageEntity {
        let importedAttachments = (imported.attachmentsJson ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let localAttachments = (local.attachmentsJson ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        var merged = local
        // 附件：本机有引用就保住（文件就在磁盘上；文件不在了界面按占位降级）。
        // 只有本机没有、备份里有的时候才补上（跨设备接回来的那条消息本来就只剩元数据）。
        if localAttachments.isEmpty, !importedAttachments.isEmpty {
            merged.attachmentsJson = imported.attachmentsJson
        }
        // 正文 / 状态：只接受备份里**更新**的那一份（本机编辑过、已读、失败重试过都更可信）。
        if imported.updatedAt > local.updatedAt {
            merged.content = imported.content
            merged.status = imported.status
            merged.errorMessage = imported.errorMessage
            merged.model = imported.model
            merged.edited = imported.edited
            merged.toolTraceJson = imported.toolTraceJson ?? merged.toolTraceJson
            merged.updatedAt = imported.updatedAt
            merged.readAt = local.readAt ?? imported.readAt
        }
        // `attachmentsJson` 永远以「本机的引用」为准（上面已经处理过补齐的情况）。
        return merged
    }

    /// 合并模式下有多少条导入消息「本机已经有带附件引用的同 id 消息」（这些图不会丢）。
    static func countLocallyBackedAttachments(
        _ messages: [MessageEntity],
        stores: DatabaseStores,
        merge: Bool
    ) -> Int {
        guard merge else { return 0 }
        var count = 0
        for message in messages {
            guard !(message.attachmentsJson ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                continue
            }
            if let local = (try? stores.messages.byId(message.id)) ?? nil,
               !(local.attachmentsJson ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                count += 1
            }
        }
        return count
    }

    // MARK: - 实体 ⇄ 备份结构
    //
    // 这些映射只在这里写一遍：任何实体新增字段却忘了带进备份，往返测试会当场变红。
    // 刻意手写而不是靠 Codable 自动合成：备份的字段名是**跨端契约**，不能跟着属性名漂。

    static func toBackupPersona(_ persona: PersonaEntity) -> BackupPersona {
        BackupPersona(
            id: persona.id, name: persona.name, description: persona.description,
            avatarUri: persona.avatarUri, avatarSeed: persona.avatarSeed, prompt: persona.prompt,
            category: persona.category, version: persona.version,
            defaultApiConfigId: persona.defaultApiConfigId, modelOverride: persona.modelOverride,
            isDefault: persona.isDefault, createdAt: persona.createdAt, updatedAt: persona.updatedAt,
            personaCardJson: persona.personaCardJson,
            quietSleepMinutes: persona.quietSleepMinutes, quietWakeMinutes: persona.quietWakeMinutes,
            mood: persona.mood, moodIntensity: persona.moodIntensity, moodReason: persona.moodReason,
            moodUpdatedAt: persona.moodUpdatedAt, moodNudgedAt: persona.moodNudgedAt,
            traits: persona.traits, likes: persona.likes, behaviors: persona.behaviors,
            backstory: persona.backstory, quirks: persona.quirks,
            proactiveLevel: persona.proactiveLevel, memoryRetention: persona.memoryRetention,
            extractionEvery: persona.extractionEvery, sleepEnabled: persona.sleepEnabled,
            wakeThreshold: persona.wakeThreshold, dndOnSleep: persona.dndOnSleep
        )
    }

    static func toEntity(_ persona: BackupPersona) -> PersonaEntity {
        PersonaEntity(
            id: persona.id, name: persona.name, description: persona.description,
            avatarUri: persona.avatarUri, avatarSeed: persona.avatarSeed, prompt: persona.prompt,
            category: persona.category, version: persona.version,
            defaultApiConfigId: persona.defaultApiConfigId, modelOverride: persona.modelOverride,
            isDefault: persona.isDefault, createdAt: persona.createdAt, updatedAt: persona.updatedAt,
            personaCardJson: persona.personaCardJson,
            quietSleepMinutes: persona.quietSleepMinutes, quietWakeMinutes: persona.quietWakeMinutes,
            mood: persona.mood, moodIntensity: persona.moodIntensity, moodReason: persona.moodReason,
            moodUpdatedAt: persona.moodUpdatedAt, moodNudgedAt: persona.moodNudgedAt,
            traits: persona.traits, likes: persona.likes, behaviors: persona.behaviors,
            backstory: persona.backstory, quirks: persona.quirks,
            proactiveLevel: persona.proactiveLevel, memoryRetention: persona.memoryRetention,
            extractionEvery: persona.extractionEvery, sleepEnabled: persona.sleepEnabled,
            wakeThreshold: persona.wakeThreshold, dndOnSleep: persona.dndOnSleep
        )
    }

    static func toBackupMemory(_ memory: MemoryEntity) -> BackupMemory {
        BackupMemory(
            id: memory.id, personaId: memory.personaId, content: memory.content,
            category: memory.category.stored, importance: memory.importance, pinned: memory.pinned,
            sourceSessionId: memory.sourceSessionId, createdAt: memory.createdAt,
            updatedAt: memory.updatedAt, lastUsedAt: memory.lastUsedAt, useCount: memory.useCount,
            drawer: memory.drawer, sourceQuote: memory.sourceQuote, status: memory.status,
            strength: memory.strength, archivedAt: memory.archivedAt,
            consolidatedAt: memory.consolidatedAt, tags: memory.tags, happenedAt: memory.happenedAt,
            originalContent: memory.originalContent, driftCount: memory.driftCount
        )
    }

    static func toEntity(_ memory: BackupMemory) -> MemoryEntity {
        MemoryEntity(
            id: memory.id, personaId: memory.personaId, content: memory.content,
            category: MemoryCategory(stored: memory.category),
            importance: min(max(memory.importance, 0), 100), pinned: memory.pinned,
            sourceSessionId: memory.sourceSessionId, createdAt: memory.createdAt,
            updatedAt: memory.updatedAt, lastUsedAt: memory.lastUsedAt, useCount: memory.useCount,
            drawer: memory.drawer, sourceQuote: memory.sourceQuote,
            status: memory.status.isEmpty ? "ACTIVE" : memory.status,
            strength: min(max(memory.strength, 0.0), 1.0), archivedAt: memory.archivedAt,
            consolidatedAt: memory.consolidatedAt, tags: memory.tags, happenedAt: memory.happenedAt,
            originalContent: memory.originalContent, driftCount: memory.driftCount
        )
    }

    static func toBackupSession(_ session: ChatSessionEntity) -> BackupSession {
        BackupSession(
            id: session.id, personaId: session.personaId, apiConfigId: session.apiConfigId,
            model: session.model, title: session.title,
            lastMessagePreview: session.lastMessagePreview, lastMessageAt: session.lastMessageAt,
            unreadCount: session.unreadCount, pinned: session.pinned,
            createdAt: session.createdAt, updatedAt: session.updatedAt,
            lastPersonaVersion: session.lastPersonaVersion,
            // 本机的 6 个 sync 列（syncState/…/lastSyncAt）**不写进备份**：
            // Android 的 BackupSession 没有它们（0.10.2 安全审计 P1-5）。
            lastMemoryExtractedCount: session.lastMemoryExtractedCount,
            summary: session.historySummary,
            summarizedUpTo: session.historySummarizedUpTo,
            summaryAt: session.historySummaryAt,
            memoryRetentionOverride: session.memoryRetentionOverride,
            extractionEveryOverride: session.extractionEveryOverride
        )
    }

    static func toEntity(_ session: BackupSession) -> ChatSessionEntity {
        // 摘要只认「有正文也有压到哪一条」的完整组合，避免只导了一半的旧数据把提示词写歪。
        let summaryText = (session.summary ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let validSummary = !summaryText.isEmpty && (session.summarizedUpTo ?? 0) > 0
        return ChatSessionEntity(
            id: session.id, personaId: session.personaId, apiConfigId: session.apiConfigId,
            model: session.model, title: session.title,
            lastMessagePreview: session.lastMessagePreview, lastMessageAt: session.lastMessageAt,
            unreadCount: session.unreadCount, pinned: session.pinned,
            createdAt: session.createdAt, updatedAt: session.updatedAt,
            lastPersonaVersion: session.lastPersonaVersion,
            // 备份里没有 sync 字段：接回来的会话一律按「还没同步过」处理（iOS 本来也没做同步）。
            syncState: .stateless,
            syncedPersonaVersion: nil,
            syncedMemoriesHash: nil,
            serverSessionId: nil,
            lastSyncError: nil,
            lastSyncAt: nil,
            lastMemoryExtractedCount: session.lastMemoryExtractedCount,
            historySummary: validSummary ? session.summary : nil,
            historySummarizedUpTo: session.summarizedUpTo.flatMap { $0 > 0 ? $0 : nil },
            historySummaryAt: session.summaryAt,
            memoryRetentionOverride: session.memoryRetentionOverride,
            extractionEveryOverride: session.extractionEveryOverride
        )
    }

    static func toBackupMessage(_ message: MessageEntity) -> BackupMessage {
        BackupMessage(
            id: message.id, sessionId: message.sessionId, personaId: message.personaId,
            role: message.role.stored, content: message.content,
            createdAt: message.createdAt, updatedAt: message.updatedAt,
            status: message.status.stored, errorMessage: message.errorMessage,
            model: message.model, edited: message.edited,
            attachmentsJson: message.attachmentsJson, toolTraceJson: message.toolTraceJson,
            readAt: message.readAt
        )
    }

    /// 消息接回来：`toolTraceJson` 保留（他当时做过什么），`attachmentsJson` 丢掉。
    ///
    /// 图片本体不在备份里，那个 uri 在这台手机上指向空气；留着反而会让气泡显示一个破图。
    static func toEntity(_ message: BackupMessage) -> MessageEntity {
        MessageEntity(
            id: message.id, sessionId: message.sessionId, personaId: message.personaId,
            role: MessageRole(stored: message.role), content: message.content,
            createdAt: message.createdAt, updatedAt: message.updatedAt,
            status: MessageStatus(stored: message.status), errorMessage: message.errorMessage,
            model: message.model, edited: message.edited,
            attachmentsJson: nil, toolTraceJson: message.toolTraceJson,
            readAt: message.readAt
        )
    }

    static func toBackupPreset(_ preset: PersonaPresetEntity) -> BackupPreset {
        BackupPreset(
            id: preset.id, name: preset.name, description: preset.description,
            prompt: preset.prompt, category: preset.category, avatarUri: preset.avatarUri,
            avatarSeed: preset.avatarSeed, createdAt: preset.createdAt, updatedAt: preset.updatedAt
        )
    }

    static func toEntity(_ preset: BackupPreset) -> PersonaPresetEntity {
        PersonaPresetEntity(
            id: preset.id, name: preset.name, description: preset.description,
            prompt: preset.prompt, category: preset.category, avatarUri: preset.avatarUri,
            avatarSeed: preset.avatarSeed, createdAt: preset.createdAt, updatedAt: preset.updatedAt
        )
    }

    static func toBackupRule(_ rule: ProactiveRuleEntity) -> BackupProactiveRule {
        BackupProactiveRule(
            id: rule.id, personaId: rule.personaId, sessionId: rule.sessionId,
            kind: rule.kind.stored, timeOfDayMinutes: rule.timeOfDayMinutes,
            intervalMinutes: rule.intervalMinutes, hint: rule.hint, note: rule.note,
            enabled: rule.enabled, pending: rule.pending, source: rule.source.stored,
            nextTriggerAt: rule.nextTriggerAt, lastTriggeredAt: rule.lastTriggeredAt,
            lastError: rule.lastError, createdAt: rule.createdAt, updatedAt: rule.updatedAt
        )
    }

    static func toEntity(_ rule: BackupProactiveRule) -> ProactiveRuleEntity {
        ProactiveRuleEntity(
            id: rule.id, personaId: rule.personaId, sessionId: rule.sessionId,
            kind: ProactiveKind(stored: rule.kind), timeOfDayMinutes: rule.timeOfDayMinutes,
            intervalMinutes: rule.intervalMinutes, hint: rule.hint, note: rule.note,
            enabled: rule.enabled, pending: rule.pending,
            source: ProactiveSource(stored: rule.source),
            nextTriggerAt: rule.nextTriggerAt, lastTriggeredAt: rule.lastTriggeredAt,
            lastError: rule.lastError, createdAt: rule.createdAt, updatedAt: rule.updatedAt
        )
    }

    static func toBackupIntent(_ intent: AgentIntentEntity) -> BackupAgentIntent {
        BackupAgentIntent(
            id: intent.id, personaId: intent.personaId, sessionId: intent.sessionId,
            kind: intent.kind.stored, earliestAt: intent.earliestAt, latestAt: intent.latestAt,
            note: intent.note, hint: intent.hint, status: intent.status.stored,
            ruleId: intent.ruleId, createdAt: intent.createdAt, sentAt: intent.sentAt,
            lastError: intent.lastError
        )
    }

    static func toEntity(_ intent: BackupAgentIntent) -> AgentIntentEntity {
        AgentIntentEntity(
            id: intent.id, personaId: intent.personaId, sessionId: intent.sessionId,
            kind: AgentIntentKind(stored: intent.kind), earliestAt: intent.earliestAt,
            latestAt: intent.latestAt, note: intent.note, hint: intent.hint,
            status: AgentIntentStatus(stored: intent.status), ruleId: intent.ruleId,
            createdAt: intent.createdAt, sentAt: intent.sentAt, lastError: intent.lastError
        )
    }

    static func toBackupConsolidation(_ run: MemoryConsolidationEntity) -> BackupMemoryConsolidation {
        BackupMemoryConsolidation(
            id: run.id, personaId: run.personaId, at: run.at, kept: run.kept,
            merged: run.merged, archived: run.archived, forgotten: run.forgotten,
            summary: run.summary, modelName: run.modelName
        )
    }

    static func toEntity(_ run: BackupMemoryConsolidation) -> MemoryConsolidationEntity {
        MemoryConsolidationEntity(
            id: run.id, personaId: run.personaId, at: run.at, kept: run.kept,
            merged: run.merged, archived: run.archived, forgotten: run.forgotten,
            summary: run.summary, modelName: run.modelName
        )
    }

    /// API 配置 → 备份：白名单拷贝（密钥不进备份的第一道闸）。
    static func toBackupApiConfig(_ config: ApiConfigEntity) -> BackupApiConfig {
        BackupApiConfig(
            id: config.id, name: config.name, provider: config.provider.stored,
            baseUrl: config.baseUrl, model: config.model,
            allowInsecureHttp: config.allowInsecureHttp,
            temperature: config.temperature, topP: config.topP,
            maxOutputTokens: config.maxOutputTokens,
            frequencyPenalty: config.frequencyPenalty,
            presencePenalty: config.presencePenalty,
            webToolsEnabled: config.webToolsEnabled, webToolsBaseUrl: config.webToolsBaseUrl,
            createdAt: config.createdAt, updatedAt: config.updatedAt
        )
    }

    /// `mergeInto` 的结果：合并后的实体 + 需要告诉用户/日志的差异。
    struct ApiConfigMerge: Equatable {
        var entity: ApiConfigEntity
        /// 备份里给了一个**与本机不同**的地址（真实地址，不覆盖本机，只用于告警/日志）。
        var foreignBaseUrl: String?
        /// 备份想把「允许不安全 HTTP」改成本机不同的值（已拒绝）。
        var keptLocalInsecureHttp: Bool

        /// 便捷取用：绝大多数调用方只关心实体。
        var merged: ApiConfigEntity { entity }
    }

    /// 把备份里的配置盖回一条**已存在**的配置：只动非敏感/非信任字段。
    ///
    /// 密钥、自定义 Headers、创建时间都留在本机——备份里从来没有它们，
    /// 要是这里顺手把整个实体换掉，用户在本机存的 Key 就被一份「没有 Key 的备份」抹掉了。
    ///
    /// **地址不迁移**（0.10.2 安全审计 P1-0）：`baseUrl` / `webToolsBaseUrl` 与 `allowInsecureHttp`
    /// 都是「这台设备信任谁」的决定。备份改了它们而保留本机 Key，就等于把用户真实的 Key
    /// 与全部聊天送到一个陌生主机（或降级成明文）。所以：地址与本机不同就保留本机值并告警；
    /// 明文开关永远不因备份被打开（本机自己开着就继续开着）。
    static func mergeInto(_ imported: BackupApiConfig, existing: ApiConfigEntity) -> ApiConfigMerge {
        var merged = existing
        merged.name = imported.name.isEmpty ? existing.name : imported.name
        merged.provider = ProviderType(stored: imported.provider)
        merged.model = imported.model.isEmpty ? existing.model : imported.model
        merged.temperature = imported.temperature ?? existing.temperature
        merged.topP = imported.topP ?? existing.topP
        merged.maxOutputTokens = imported.maxOutputTokens ?? existing.maxOutputTokens
        merged.frequencyPenalty = imported.frequencyPenalty ?? existing.frequencyPenalty
        merged.presencePenalty = imported.presencePenalty ?? existing.presencePenalty
        merged.webToolsEnabled = imported.webToolsEnabled

        // 地址：同源才接受（尾部斜杠/`/v1` 写法差异不算不同）；不同就保留本机值。
        let importedBase = NetworkGuard.normalizeBaseUrl(imported.baseUrl)
        let localBase = NetworkGuard.normalizeBaseUrl(existing.baseUrl)
        var foreignBaseUrl: String?
        if !importedBase.isEmpty, importedBase != localBase {
            foreignBaseUrl = importedBase
        } else if !importedBase.isEmpty {
            merged.baseUrl = importedBase
        }
        let importedToolsBase = NetworkGuard.normalizeBaseUrl(imported.webToolsBaseUrl)
        if !importedToolsBase.isEmpty, importedToolsBase == NetworkGuard.normalizeBaseUrl(existing.webToolsBaseUrl) {
            merged.webToolsBaseUrl = importedToolsBase
        }

        // 明文开关：只在「本机本来就开着」时保留 true；备份说 true 本机说 false → 拒绝。
        let keptLocalInsecureHttp = imported.allowInsecureHttp != existing.allowInsecureHttp
        merged.allowInsecureHttp = existing.allowInsecureHttp

        if imported.updatedAt > 0 { merged.updatedAt = imported.updatedAt }
        return ApiConfigMerge(
            entity: merged,
            foreignBaseUrl: foreignBaseUrl,
            keptLocalInsecureHttp: keptLocalInsecureHttp
        )
    }

    /// 把备份里的配置建成一条新配置：密钥留空，等用户在这台手机上重新填一次。
    ///
    /// `customHeadersJson` 给 `{}`：备份里没有它（可能夹带 Authorization），与其猜，不如让用户显式补。
    /// `allowInsecureHttp` 一律给 false：新设备上没有「用户当场同意过明文」这件事，
    /// 备份不该替用户做这个决定（0.10.2 安全审计 P1-0）。
    static func toNewEntity(_ imported: BackupApiConfig, now: Int64) -> ApiConfigEntity {
        ApiConfigEntity(
            id: imported.id,
            name: imported.name.isEmpty ? String(localized: "接回来的配置") : imported.name,
            provider: ProviderType(stored: imported.provider),
            baseUrl: imported.baseUrl,
            apiKeyCipher: nil,
            model: imported.model,
            customHeadersJson: "{}",
            allowInsecureHttp: false,
            temperature: imported.temperature,
            topP: imported.topP,
            maxOutputTokens: imported.maxOutputTokens,
            frequencyPenalty: imported.frequencyPenalty,
            presencePenalty: imported.presencePenalty,
            createdAt: imported.createdAt > 0 ? imported.createdAt : now,
            updatedAt: imported.updatedAt > 0 ? imported.updatedAt : now,
            webToolsEnabled: imported.webToolsEnabled,
            webToolsBaseUrl: imported.webToolsBaseUrl,
            webToolsApiKeyCipher: nil
        )
    }
}
