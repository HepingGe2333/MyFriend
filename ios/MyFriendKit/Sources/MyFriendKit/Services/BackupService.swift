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
// ## 0.12 的备份（v3）：图片真的跟着走
//
// v2 只写路径字符串，换台手机以后那些引用全都指不到文件 —— 图片等于丢了。v3 在顶层加一个
// `media` 数组把二进制一起装进去（base64），并且把引用改成**裸文件名**：
//
// - 导出收集顺序固定：人格头像 → 「我的头像」→ 预设头像 → 聊天图片；同一张文件只装一次；
//   单张 > 8 MiB 跳过并计数；累计会超 128 MiB 就**停止收集**，剩下的全部计入「超限」；
// - 导入时媒体**先落盘**（新 UUID 文件名、必须过目录归属守卫），再在事务里只写数据库引用；
//   事务失败会把这次新写的文件删掉，不留孤儿；
// - 坏 base64 / 不支持的 mime / 不安全文件名 / 超限：跳过并计数，导入照样成功；
// - v1/v2 备份没有 `media`：行为与以前一字不差（丢掉路径、给一句「图片没有跟着备份走」）。
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

/// 导出结果：JSON 文本 + 需要如实告诉用户的提醒（目前只有「有 N 张图片超出上限没带走」）。
///
/// 为什么把提醒单独拿出来，而不是塞进 JSON 或写日志：图片超限是用户能理解、也只能由用户
/// 决定要不要处理的事（删掉几张图再导出），所以它必须上屏；而 `export()` 的返回值是文件内容，
/// 不能混进别的东西。
public struct BackupExportResult: Equatable, Sendable {
    public var text: String
    public var warnings: [String]

    public init(text: String, warnings: [String] = []) {
        self.text = text
        self.warnings = warnings
    }
}

public final class BackupService {

    /// 备份文件的大小上限（256 MB，与 Android 侧同一个数字语义）。
    ///
    /// v3 起图片二进制也进备份：128 MiB 原始字节 base64 之后约 171 MiB，再加上文字，
    /// 旧的 64 MB 闸会把一份自己刚导出的备份挡在门外。0.10.2 安全审计 P2-3 的两道闸
    /// （读之前看文件大小、解析之前看 UTF-8 字节数）不变，只是数字跟着格式一起抬到 256 MiB。
    public static let maxImportBytes = 256 * 1024 * 1024

    /// 读入/解析上限按可用内存收紧：整份 JSON 要按字符读入（UTF-16 占两倍），
    /// 256 MiB 的文本在 192 MB 堆上不可能完成。取 `min(256 MiB, 可用内存/8)`。
    public static func effectiveImportBytes(available: Int = BackupMediaLimits.availableMemoryBytes()) -> Int {
        max(8 * 1024 * 1024, min(maxImportBytes, available / 8))
    }

    /// 导出的体积上限：与导入同一个数字（0.11.1 审计 P1-4）。
    ///
    /// 以前导出没有上限、导入有闸：聊天量大时能导出一份「自己导不回来」的备份，
    /// 而用户到换机那天才发现 —— 唯一的迁移手段变成死文件。现在导出到上限就明确报错
    /// （`export()` 抛 `BackupImportError`，界面显示「没能把他带走」），绝不返回半份 JSON。
    public static var maxExportBytes: Int { effectiveImportBytes() }

    /// 一份备份里全部图片的**原始**字节上限：128 MiB（与 Android 同一组数字）。
    public static var maxMediaBytes: Int { BackupMediaLimits.effectiveTotalBytes() }
    /// 单张图片的**原始**字节上限：8 MiB。
    public static let maxMediaFileBytes = BackupMediaLimits.maxFileBytes

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
    /// 导入时把「裸文件名」拼回哪个附件目录（默认与 `MediaStorage.shared` 同一个）。
    private let mediaDirectory: URL
    /// 导出体积上限（单测注入一个小值，不必真的造 256 MB 数据）。
    private let exportLimitBytes: Int
    /// 图片总量 / 单张上限（单测注入小值，不必真的造 128 MiB 数据）。
    private let mediaByteLimit: Int
    private let mediaFileByteLimit: Int

    public init(
        stores: DatabaseStores,
        settings: SettingsProviding,
        vectorIndex: MemoryVectorIndex? = nil,
        appVersion: String = "",
        avatarFileExists: @escaping (String) -> Bool = { MediaStorage.shared.exists(uriString: $0) },
        mediaDirectory: URL = MediaStorage.shared.directory,
        exportLimitBytes: Int = BackupService.maxExportBytes,
        mediaByteLimit: Int = BackupService.maxMediaBytes,
        mediaFileByteLimit: Int = BackupService.maxMediaFileBytes,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.stores = stores
        self.settings = settings
        self.vectorIndex = vectorIndex ?? MemoryVectorIndex(stores: stores, now: now)
        self.appVersion = appVersion
        self.avatarFileExists = avatarFileExists
        self.mediaDirectory = mediaDirectory
        self.exportLimitBytes = exportLimitBytes
        self.mediaByteLimit = mediaByteLimit
        self.mediaFileByteLimit = mediaFileByteLimit
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
    ///
    /// 保留这个入口是为了既有调用点（与 `exportWithWarnings()` 的文本完全一致）；
    /// 界面要显示「有几张图超出上限」时用 `exportWithWarnings()`。
    public func export() throws -> String {
        try exportWithWarnings().text
    }

    /// 把「他」收集成一份可以带走的 JSON 文本 + 需要上屏的提醒（v3 的图片超限）。
    public func exportWithWarnings() throws -> BackupExportResult {
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
        let presets = ((try? stores.presets.all(orderBy: "updatedAt DESC")) ?? [])
        let currentSettings = settings.load()

        // v3：先把「本机真的还引用得到」的图片收集成候选（顺序固定：人格头像 → 我的头像 →
        // 预设头像 → 聊天图片），再按上限打包。只读磁盘，不写任何东西。
        let candidates = Self.mediaCandidates(
            personas: personas,
            presets: presets,
            messages: messages,
            myAvatarUri: currentSettings.myAvatarUri,
            directory: mediaDirectory,
            fileExists: avatarFileExists
        )
        let packed = BackupMediaPacker.pack(
            candidates: candidates,
            totalBudget: mediaByteLimit,
            perFileBudget: mediaFileByteLimit
        )
        // 只有真的装进 media 的头像才写进 `avatarUri`：写了名字却没有字节，导入端只会
        // 多一条「头像找不到」的警告，那是骗人的。
        let packedAvatars = Set(packed.media.filter { $0.kind == BackupMediaKind.personaAvatar.rawValue }
            .map(\.fileName))

        let bundle = BackupBundle(
            format: backupFormatName,
            formatVersion: backupFormatVersion,
            appVersion: appVersion,
            exportedAt: exportedAt,
            apiKeysOmitted: true,
            personas: personas.map { persona in
                let name = BackupAvatarPath.portable(persona.avatarUri)
                return Self.toBackupPersona(
                    persona,
                    avatarUri: name.flatMap { packedAvatars.contains($0) ? $0 : nil }
                )
            },
            memories: memories.map(Self.toBackupMemory),
            sessions: sessions.map(Self.toBackupSession),
            messages: messages.map { message in
                Self.toBackupMessage(
                    message,
                    attachmentsJson: BackupMediaRestorer.portableAttachments(
                        message.attachmentsJson, allowedNames: packed.chatImageFileNames
                    )
                )
            },
            media: packed.media,
            presets: presets.map { preset in
                let name = BackupAvatarPath.portable(preset.avatarUri)
                return Self.toBackupPreset(
                    preset,
                    avatarUri: name.flatMap { packedAvatars.contains($0) ? $0 : nil }
                )
            },
            proactiveRules: ((try? stores.proactiveRules.all(orderBy: "nextTriggerAt ASC")) ?? [])
                .map(Self.toBackupRule),
            agentIntents: intents.map(Self.toBackupIntent),
            memoryConsolidations: consolidations.map(Self.toBackupConsolidation),
            apiConfigs: ((try? stores.apiConfigs.all(orderBy: "updatedAt DESC")) ?? [])
                .map(Self.toBackupApiConfig),
            settings: currentSettings.toBackupSettings()
        )

        let encoded = try BackupCodec.encode(bundle)
        // 最后一道闸：真出现了密钥字段就中止导出，不写文件。
        if BackupCodec.containsSecretField(encoded) {
            throw BackupImportError(String(localized: "备份里出现了密钥字段，已中止导出"))
        }
        // 0.11.1 审计 P1-4：导出与导入的尺寸口径必须一致。超限时**抛错**（调用点不会拿到
        // 半份 JSON、更不会写出文件），而不是让用户拿到一份自己导不回来的备份。
        guard encoded.utf8.count <= exportLimitBytes else {
            throw BackupImportError(Self.tooLargeExportMessage(limitBytes: exportLimitBytes))
        }
        var warnings: [String] = []
        if packed.skippedByLimit > 0 {
            // 超限是唯一一种「导出成功但有东西没带走」的情形：必须上屏，不能只写日志。
            warnings.append(PluralText.format("有 %d 张图片没有随备份带走（超出上限）", packed.skippedByLimit))
        }
        return BackupExportResult(text: encoded, warnings: warnings)
    }

    /// 收集导出候选：只收本机当前还引用得到、且落在附件目录里的图片。
    ///
    /// 顺序就是 `media` 数组的顺序（人格头像 → 我的头像 → 预设头像 → 聊天图片）：
    /// 超限时先保住最要紧的那几张，用户看到的「哪几张没带走」才可预期。
    static func mediaCandidates(
        personas: [PersonaEntity],
        presets: [PersonaPresetEntity],
        messages: [MessageEntity],
        myAvatarUri: String?,
        directory: URL,
        fileExists: (String) -> Bool
    ) -> [BackupMediaCandidate] {
        var candidates: [BackupMediaCandidate] = []

        func append(_ kind: BackupMediaKind, _ personaId: String?, _ raw: String?, _ name: String?) {
            guard let raw, let name, let url = resolveMediaFile(raw, directory: directory, fileExists: fileExists)
            else { return }
            guard let mime = BackupMediaTransfer.mime(forFileName: name) else { return }
            candidates.append(
                BackupMediaCandidate(kind: kind, personaId: personaId, fileName: name, fileURL: url, mime: mime)
            )
        }

        // (1) 人格头像。
        for persona in personas {
            append(.personaAvatar, persona.id, persona.avatarUri, BackupAvatarPath.portable(persona.avatarUri))
        }
        // (2) 「我的头像」：它不在 BackupSettings 里（那是设备路径），只以 USER_AVATAR 走 media。
        append(.userAvatar, nil, myAvatarUri, BackupAvatarPath.portable(myAvatarUri))
        // (3) 预设头像。
        for preset in presets {
            append(.personaAvatar, nil, preset.avatarUri, BackupAvatarPath.portable(preset.avatarUri))
        }
        // (4) 聊天图片：`attachmentsJson` 里还能解析到本机文件的那些。
        for message in messages {
            for attachment in AttachmentsCodec.decode(message.attachmentsJson) {
                append(
                    .chatImage, message.personaId, attachment.uri,
                    BackupMediaTransfer.portableFileName(attachment.uri)
                )
            }
        }
        return candidates
    }

    /// 只保留「这次备份里真的会被引用到」的媒体条目（与 Android `BackupRepository` 同一条规则）。
    ///
    /// 为什么要过滤：媒体落盘在事务之前，没被任何头像/消息引用的条目写下去也只是孤儿文件
    /// —— 还会给一份恶意备份一个「白占 128 MiB 磁盘」的机会。规则的边界是：
    /// - `USER_AVATAR`：总是接（它就是「我的头像」，不需要别的引用）；
    /// - `PERSONA_AVATAR`：人格/预设的 `avatarUri` 就是它，或者 `personaId` 指到这份备份里的人格；
    /// - `CHAT_IMAGE`：某条消息的 `attachmentsJson[].uri` 就是它；
    /// - 不认识的 kind：不接（`BackupMediaRestorer` 那边也会把它算成坏数据）。
    static func referencedMedia(_ bundle: BackupBundle) -> [BackupMedia] {
        var referencedAvatarNames = Set<String>()
        for persona in bundle.personas {
            if let name = BackupAvatarPath.fileName(persona.avatarUri) { referencedAvatarNames.insert(name) }
        }
        for preset in bundle.presets {
            if let name = BackupAvatarPath.fileName(preset.avatarUri) { referencedAvatarNames.insert(name) }
        }
        let personaIds = Set(bundle.personas.map(\.id))
        var referencedChatNames = Set<String>()
        for message in bundle.messages {
            for attachment in AttachmentsCodec.decode(message.attachmentsJson) {
                let name = attachment.uri.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty, !name.contains("/"), !name.contains("\\"), !name.contains("..") {
                    referencedChatNames.insert(name)
                }
            }
        }
        return bundle.media.filter { media in
            switch BackupMediaKind(rawValue: media.kind.trimmingCharacters(in: .whitespacesAndNewlines)) {
            case .userAvatar:
                return true
            case .personaAvatar:
                let personaId = media.personaId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                return referencedAvatarNames.contains(media.fileName)
                    || (!personaId.isEmpty && personaIds.contains(personaId))
            case .chatImage:
                return referencedChatNames.contains(media.fileName)
            case nil:
                // 不认识的 kind：谁也不可能引用它，但仍然交给落盘器去「跳过并计数」——
                // 一份带着坏数据的备份，用户有权知道「有几张图片读不出来」。
                // （它连字节都不会被解码：落盘器第一步就按 kind 拒掉。）
                return true
            }
        }
    }

    /// 一个引用字符串 → 本机附件目录里的真实文件（容器 UUID 变了也能用同名文件找回来）。
    static func resolveMediaFile(
        _ raw: String,
        directory: URL,
        fileExists: (String) -> Bool
    ) -> URL? {
        AttachmentLocation.resolve(raw, directory: directory, fileExists: fileExists)
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

    /// 读一个备份文件的内容（导入前的第一道闸：先看大小，再分块读文本）。
    ///
    /// 为什么不让界面自己 `String(contentsOf:)`：那样整份文件已经进内存了，
    /// 「超限拒绝」只能发生在 OOM 之后（0.10.2 安全审计 P2-3）。
    public func readBackupText(at url: URL, fileManager: FileManager = .default) throws -> String {
        try Self.readBoundedText(at: url, fileManager: fileManager)
    }

    /// 有界读文件（静态版：`BackupImportLoader` 要在后台线程里直接调用它，不持有 service）。
    ///
    /// 0.11.1 审计 P2-4：原来用 `String(contentsOf:)` 整份读，文件属性拿不到大小时
    /// （网络卷 / 文件 Provider）等于没有第一道闸。现在按 64 KB 分块读，读满上限立刻中止；
    /// UTF-8 解码也只发生一次。
    public static func readBoundedText(
        at url: URL,
        fileManager: FileManager = .default,
        maxBytes: Int = BackupService.effectiveImportBytes()
    ) throws -> String {
        let size = (try? fileManager.attributesOfItem(atPath: url.path))?[.size] as? NSNumber
        if let bytes = size?.intValue, bytes > maxBytes {
            throw BackupImportError(Self.tooLargeMessage)
        }
        guard let handle = try? FileHandle(forReadingFrom: url) else {
            throw BackupImportError(String(localized: "这个文件读不出来，换一份之前带走的备份试试"))
        }
        defer { try? handle.close() }
        var data = Data()
        do {
            while true {
                guard let chunk = try handle.read(upToCount: 64 * 1024), !chunk.isEmpty else { break }
                data.append(chunk)
                // 第二道闸：文件属性拿不到大小（网络卷 / 特殊 provider）时按读到的字节数再判一次。
                guard data.count <= maxBytes else {
                    throw BackupImportError(Self.tooLargeMessage)
                }
            }
        } catch let error as BackupImportError {
            throw error
        } catch {
            throw BackupImportError(String(localized: "这个文件读不出来，换一份之前带走的备份试试"))
        }
        guard let text = String(data: data, encoding: .utf8) else {
            throw BackupImportError(String(localized: "这个文件读不出来，换一份之前带走的备份试试"))
        }
        return text
    }

    /// 超限时的本地化错误文案（两个入口共用一份）。
    public static var tooLargeMessage: String {
        String(format: String(localized: "备份文件超过 %@，请选一份更小的"), "256 MB")
    }

    /// 导出超限的文案：说清楚「没写文件」与「怎么办」。
    public static func tooLargeExportMessage(limitBytes: Int) -> String {
        let limit = limitBytes == BackupService.maxExportBytes
            ? "256 MB"
            : ByteCountFormatter.string(fromByteCount: Int64(limitBytes), countStyle: .file)
        return String(
            format: String(localized: "要带走的东西超过 %@（导出上限与导入一致），这次没有写出文件。可以先删掉一些聊天记录或不再需要的记忆再试。"),
            limit
        )
    }

    /// 从 JSON 文本把他接回来。读不懂就抛 `BackupImportError`，库里什么都不改。
    public func importBackup(text: String, mode: ImportMode) throws -> ImportSummary {
        // 解析之前再判一次：调用方可能绕过 `readBackupText`（备份来自剪贴板、测试、旧代码）。
        guard text.utf8.count <= Self.effectiveImportBytes() else {
            throw BackupImportError(Self.tooLargeMessage)
        }
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

        // v3：媒体**先落盘**（在事务之前）。
        //
        // 为什么在事务前：事务里只做数据库操作，写文件可能因为磁盘、权限、外来数据失败；
        // 把失败挡在事务外，库里就不会出现「引用了一张写不出来的图」的半成品。
        // 反过来「文件写了、落库失败」会在下面的 do/catch 里回滚删掉。
        let restoredMedia = BackupMediaRestorer.restore(
            entries: Self.referencedMedia(bundle),
            directory: mediaDirectory,
            totalBudget: mediaByteLimit,
            perFileBudget: mediaFileByteLimit
        )
        if restoredMedia.invalidCount > 0 {
            warnings.append(PluralText.format(
                "有 %d 张图片没接回来（文件损坏或格式不支持）", restoredMedia.invalidCount
            ))
        }
        if restoredMedia.overLimitCount > 0 {
            warnings.append(PluralText.format(
                "有 %d 张图片没有接回来（超出上限）", restoredMedia.overLimitCount
            ))
        }

        // 头像引用：v3 先按 `media` 里的 PERSONA_AVATAR（裸文件名 = 关联键）接成本机新路径；
        // 接不到的再走 v1/v2 的老路（同名文件已经在本机附件目录里就用它）；
        // 两条都不行的直接清空 —— 留着它，界面会画一个永远加载不出来的头像，
        // 用户也看不出「这是备份带来的坏路径」——把它算进 warnings 才诚实。
        // 0.11.1 安全审计 P2-4：头像路径也必须过归属守卫。以前只判断「这个路径存在吗」，
        // 于是备份里的 `../../myfriend.db`（存在）会被原样写成头像引用 —— 而它指向的是
        // App 容器里的其它文件。现在只收「裸文件名 + 落在 attachments/ 里 + 文件真的存在」，
        // 落库时统一写成当前容器的绝对路径（容器 UUID 变了也能对上）。
        var staleAvatars = 0
        let personas = bundle.personas.map(Self.toEntity).map { persona -> PersonaEntity in
            let raw = persona.avatarUri?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !raw.isEmpty,
               let name = BackupAvatarPath.fileName(raw),
               let restored = restoredMedia.avatarsByFileName[name] {
                var fixed = persona
                fixed.avatarUri = restored.uri
                return fixed
            }
            // 备份里没写 avatarUri、但 media 里带了这个人格的头像（personaId 能对上）：
            // 一样接回来（与 Android 的 avatarsByPersonaId 兜底同一条规则，
            // 所以这一条要在「avatarUri 为空就直接返回」之前）。
            if let restored = restoredMedia.avatarsByPersonaId[persona.id] {
                var fixed = persona
                fixed.avatarUri = restored.uri
                return fixed
            }
            guard !raw.isEmpty else { return persona }
            if let resolved = BackupAvatarPath.resolveImported(
                raw, directory: mediaDirectory, fileExists: avatarFileExists
            ) {
                guard resolved != persona.avatarUri else { return persona }
                var fixed = persona
                fixed.avatarUri = resolved
                return fixed
            }
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
        // 预设头像与人格头像同一套规则：v3 先认 media，再认本机同名文件，最后清空。
        //
        // 清空时**不单独再报一句**：人格头像的警告已经说清「有头像没接回来」，
        // 而预设的头像本来就是可有可无的（没有文件时界面按 avatarSeed 画色块）。
        let presets = bundle.presets.map(Self.toEntity).map { preset -> PersonaPresetEntity in
            let raw = preset.avatarUri?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !raw.isEmpty else { return preset }
            var fixed = preset
            if let name = BackupAvatarPath.fileName(raw),
               let restored = restoredMedia.avatarsByFileName[name] {
                fixed.avatarUri = restored.uri
                return fixed
            }
            if let resolved = BackupAvatarPath.resolveImported(
                raw, directory: mediaDirectory, fileExists: avatarFileExists
            ) {
                fixed.avatarUri = resolved == preset.avatarUri ? preset.avatarUri : resolved
                return fixed
            }
            // 指向不存在的文件 / 不安全的路径：清空（绝不让备份里的路径直接落库）。
            fixed.avatarUri = nil
            return fixed
        }
        let rules = bundle.proactiveRules.map(Self.toEntity)
        let intents = bundle.agentIntents.map(Self.toEntity)
        let consolidations = bundle.memoryConsolidations.map(Self.toEntity)

        // 消息得有个能落脚的会话：合并模式下可以落进本机已有的同一段对话，
        // 替换模式下就只能落在备份自带的对话里。
        var knownSessionIds = Set(sessions.map(\.id))
        if mode == .merge {
            knownSessionIds.formUnion(((try? stores.sessions.all()) ?? []).map(\.id))
        }
        // 「原本带着图片、这次没能接回来」的消息数：v1/v2 备份里没有 media，全部计入；
        // v3 备份里只有 media 缺失 / 坏掉的那几张会落到这里（老警告照旧，语义没变）。
        var lostAttachmentMessages = 0
        let messages = bundle.messages.map { message -> MessageEntity in
            let restoredJson = BackupMediaRestorer.restoredAttachments(
                message.attachmentsJson, chatImagesByFileName: restoredMedia.chatImagesByFileName
            )
            let hadAttachments = !(message.attachmentsJson ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            if hadAttachments, restoredJson == nil { lostAttachmentMessages += 1 }
            var entity = Self.toEntity(message, attachmentsJson: restoredJson)
            // 备份那段已经并进本机那段：消息跟着换 id，否则会因为没有落脚点被丢掉。
            if let target = sessionIdRemap[entity.sessionId] { entity.sessionId = target }
            return entity
        }.filter { message in
            let ok = knownSessionIds.contains(message.sessionId)
            if !ok { skipped += 1 }
            return ok
        }

        // 合并模式下，本机同 id 的消息会保留自己的附件引用（文件就在磁盘上），
        // 这部分不该被算进「图片没跟过来」——那是跨设备的说法。
        let locallyBackedAttachments = Self.countLocallyBackedAttachments(
            messages, stores: stores, merge: mode == .merge
        )
        let unbackedAttachments = max(lostAttachmentMessages - locallyBackedAttachments, 0)
        if unbackedAttachments > 0 {
            warnings.append(PluralText.format("有 %d 句话原本带着图片，图片没有跟着备份走，现在只剩文字", unbackedAttachments))
        }

        do {
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
                        if merge.keptLocalProvider {
                            warnings.append(
                                String(format: String(localized: "配置「%@」的搜索服务保持本机设置（不会跟着备份改）"), merge.entity.name)
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
        } catch {
            // 媒体文件是在事务之前写的：事务失败就把这次新写的文件删掉，
            // 别在用户目录里留下一批没人引用的图片（与 Android 的 writtenFiles 回滚同一件事）。
            // 只删「这次新写、且还在我们目录里」的文件，绝不碰旧文件。
            for url in restoredMedia.writtenFiles where AttachmentLocation.isWithin(mediaDirectory, url) {
                try? FileManager.default.removeItem(at: url)
            }
            throw error
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

        // 「我的头像」不在 BackupSettings 里（那是设备路径），只以 USER_AVATAR 走 media：
        // 设置恢复之后再单独写回；这次没接回来一张就**不动**本机已有的那张
        // （`applying` 本来也不碰它，这里只是把「备份带来的图」补上）。
        if let userAvatar = restoredMedia.userAvatar {
            var updated = settings.load()
            updated.myAvatarUri = userAvatar.uri
            settings.save(updated)
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

    /// 导出时该写进备份的 `avatarUri`：只写**裸文件名**（`avatar-<uuid>.jpg`），不写设备路径。
    ///
    /// 0.10.2 安全审计 P2-5：头像存的是 `file:///…/Application Support/attachments/avatar-…`，
    /// 原样写进备份等于把「对方设备的沙盒路径 + 文件名 UUID」带出去（低敏感，但属于设备路径），
    /// 而且换台手机它必然失效。
    /// v3 起图片二进制跟着备份走（见 `media`），所以这里写的是**关联键**：与 Android
    /// `BackupAvatarPath.portable` 同一条规则 —— 只认本机 `file:`、只取最后一段、
    /// 必须是安全名字（iOS 的头像还要求 `avatar-` 前缀，避免把 `/etc/passwd` 写成 `passwd`）。
    /// 非文件类 URI（历史遗留的 `content://`、将来的网络头像）不写：它们不是我们的文件。
    ///
    /// 调用点还要确认「这一张真的装进了 `media`」才写得进去（见 `exportWithWarnings`）。
    static func exportedAvatarUri(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        // 三种可接受的形态：本机 `file:` URL、绝对路径、以及「已经是裸文件名」（导入后再导出）。
        // 其它（`https://` / `content://`）都不是我们的文件：字节带不走，写个名字只会让
        // 导入端多一条「头像找不到」的警告。
        let isLocalFile = trimmed.lowercased().hasPrefix("file:") || trimmed.hasPrefix("/")
        let isBareName = !trimmed.contains("/") && !trimmed.contains("\\") && !trimmed.contains(":")
        guard isLocalFile || isBareName else { return nil }
        return BackupAvatarPath.portable(trimmed)
    }

    static func toBackupPersona(_ persona: PersonaEntity, avatarUri: String? = nil) -> BackupPersona {
        BackupPersona(
            id: persona.id, name: persona.name, description: persona.description,
            avatarUri: avatarUri, avatarSeed: persona.avatarSeed,
            prompt: persona.prompt,
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

    /// 导出消息：`attachmentsJson` 由调用方换算成「裸文件名 + 元数据」的版本。
    ///
    /// 0.11.1 安全审计 P2-12（v3 起仍然成立）：附件里存的是设备沙盒绝对路径（含容器 UUID），
    /// 绝不原样写进备份。默认 `nil` = 这条消息这次没有可带走的图片（文件不在 / 超限 / 格式不支持）。
    static func toBackupMessage(_ message: MessageEntity, attachmentsJson: String? = nil) -> BackupMessage {
        BackupMessage(
            id: message.id, sessionId: message.sessionId, personaId: message.personaId,
            role: message.role.stored, content: message.content,
            createdAt: message.createdAt, updatedAt: message.updatedAt,
            status: message.status.stored, errorMessage: message.errorMessage,
            model: message.model, edited: message.edited,
            attachmentsJson: attachmentsJson, toolTraceJson: message.toolTraceJson,
            readAt: message.readAt
        )
    }

    /// 消息接回来：`toolTraceJson` 保留（他当时做过什么）；`attachmentsJson` 只写
    /// **本机新路径**（v3 由 `BackupMediaRestorer.restoredAttachments` 重建）。
    ///
    /// v1/v2 备份里没有 `media`，重建结果一定是 nil：那时图片本体不在备份里，
    /// 老 uri 在这台手机上指向空气，留着反而会让气泡显示一个破图。
    static func toEntity(_ message: BackupMessage, attachmentsJson: String? = nil) -> MessageEntity {
        MessageEntity(
            id: message.id, sessionId: message.sessionId, personaId: message.personaId,
            role: MessageRole(stored: message.role), content: message.content,
            createdAt: message.createdAt, updatedAt: message.updatedAt,
            status: MessageStatus(stored: message.status), errorMessage: message.errorMessage,
            model: message.model, edited: message.edited,
            attachmentsJson: attachmentsJson, toolTraceJson: message.toolTraceJson,
            readAt: message.readAt
        )
    }

    static func toBackupPreset(_ preset: PersonaPresetEntity, avatarUri: String? = nil) -> BackupPreset {
        BackupPreset(
            id: preset.id, name: preset.name, description: preset.description,
            prompt: preset.prompt, category: preset.category,
            avatarUri: avatarUri,
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
            webToolsEnabled: config.webToolsEnabled,
            webToolsProvider: WebToolsProvider(stored: config.webToolsProvider).rawValue,
            webToolsBaseUrl: config.webToolsBaseUrl,
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
        /// 备份里的搜索服务与本机不同（已保留本机 provider，理由见 `mergeInto`）。
        var keptLocalProvider: Bool

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
        // 搜索服务也属于「这台设备把 Key 发给谁」的一部分：本机 `webToolsBaseUrl` 为空时，
        // provider 决定默认地址（DeepSeek 官方 vs Tavily/Brave/Serper/自建）。若照备份改 provider
        // 而不改地址，本机存的 DeepSeek Key 就会发到备份指定的那家服务上去 —— 等于把 Key 交给
        // 第三方。所以 provider 与本机不同时保留本机值并上报警告；新配置（本机没有）才采用备份值。
        let importedProvider = WebToolsProvider(stored: imported.webToolsProvider).rawValue
        let localProvider = WebToolsProvider(stored: existing.webToolsProvider).rawValue
        let keptLocalProvider = importedProvider != localProvider
        merged.webToolsProvider = localProvider

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
            keptLocalInsecureHttp: keptLocalInsecureHttp,
            keptLocalProvider: keptLocalProvider
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
            webToolsProvider: WebToolsProvider(stored: imported.webToolsProvider).rawValue,
            webToolsBaseUrl: imported.webToolsBaseUrl,
            webToolsApiKeyCipher: nil
        )
    }
}


// MARK: - 导入的准备阶段（后台线程）

/// 「读文件 + JSON 预解析」的结果（两份都是 `Sendable`，可以安全地跨线程交回主线程）。
public struct BackupImportPreparation: Equatable, Sendable {
    /// 原文（用户确认后要重新交给 `importBackup(text:mode:)`）。
    public let text: String
    /// 预解析结果（界面拿它显示「几个人格、多少条记忆」）。
    public let decoded: BackupDecodeResult

    public init(text: String, decoded: BackupDecodeResult) {
        self.text = text
        self.decoded = decoded
    }
}

/// 导入的准备阶段：**在后台线程**读文件并做 JSON 预解析（0.11.1 审计 P2-4 / 安全审计 P2-9）。
///
/// 256 MB 的 JSON 在主 runloop 上做 UTF-8 解码 + `JSONSerialization` 是秒级卡顿，
/// 内存峰值还要乘上几份副本。`importBundle` 早就挪进 `Task.detached` 了，但「读 + decode」
/// 这两步原来还在主线程 —— 这里把它们一起挪走，界面只 `await` 结果。
public enum BackupImportLoader {

    /// 读并预解析。错误（超限 / 读不出来）原样交回调用点显示。
    public static func load(at url: URL) async -> Result<BackupImportPreparation, Error> {
        await Task.detached(priority: .userInitiated) {
            do {
                let text = try BackupService.readBoundedText(at: url)
                return .success(
                    BackupImportPreparation(text: text, decoded: BackupCodec.decode(text))
                )
            } catch {
                return .failure(error)
            }
        }.value
    }
}
