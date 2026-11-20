import CryptoKit
import Foundation

// ---------------------------------------------------------------------------
// 服务端媒体 → 本机持久目录（0.13 第二阶段）。
//
// 第一阶段的做法是「渲染时按需下载到 Caches，再带 Authorization 取」。那对「看一眼图片」
// 够用，但对 0.13 新增的语音/视频/表情有三处不够：
//
// 1. **Caches 会被系统清掉**：清掉之后一段语音就变成「加载失败」，而用户以为文件丢了；
// 2. **Caches 不进备份**：换机/重装后媒体全没了（v6 备份现在会带上图片/表情/音频/视频，
//    所以本机必须先有一份**持久**文件，备份才有东西可打包）；
// 3. **播放器要真实文件**：`AVPlayer` 拿无扩展名的缓存文件会 `cannotOpen`。
//
// 所以：**拉下来就落进各自的持久目录**，并把 `attachmentsJson` 改成指向本地文件
// （同时保留 `serverMediaId`，将来要重新上传/重拉都有依据）。渲染与播放因此完全走
// 本地文件这条路 —— 界面层一行都不用改（它本来就按 `file://` 渲染）。
//
// 目录与文件命名（与本地选图/录音产生的文件同一套前缀，清理逻辑不必区分「备份来的」）：
//
//   头像 / 聊天图 → `Application Support/attachments/`  `avatar-<id>-<hash>.jpg` / `chatImage-…`
//   表情         → `Application Support/stickers/`     `sticker-<id>-<hash>.gif`
//   语音         → `Application Support/audio/`        `audio-<id>-<hash>.m4a`
//   视频         → `Application Support/video/`        `video-<id>-<hash>.mp4`
//
// 文件名里带 **mediaId** 与**内容哈希**：id 让它天然幂等（同一个 id 已有文件就直接用），
// 哈希让「同一个 id、内容却变了」这种异常也能被看出来（换名字重写，不留半旧文件）。
// ---------------------------------------------------------------------------

/// 一份服务端媒体落在本机哪个目录、用什么前缀。
public enum ServerMediaLocalKind: String, CaseIterable, Sendable {
    case chatImage = "chatImage"
    case avatar = "avatar"
    case sticker = "sticker"
    case audio = "audio"
    case video = "video"

    /// 文件名前缀（与 `MediaKind.rawValue` / `AudioVideoLimits.Kind.fileNamePrefix` 同源）。
    public var fileNamePrefix: String { rawValue + "-" }

    /// 这条附件属于哪一类（按 `kind` 字段优先，其次 mime —— 与 `MessageAttachment` 的判定同一套）。
    public static func kind(for attachment: MessageAttachment) -> ServerMediaLocalKind {
        if attachment.isAudio { return .audio }
        if attachment.isVideo { return .video }
        if attachment.isSticker { return .sticker }
        return .chatImage
    }

    /// 服务端 `MediaKind`（`kind=` 查询参数）→ 本地类别。头像与聊天图都落在 attachments。
    public static func kind(forServerKind raw: String?) -> ServerMediaLocalKind? {
        switch (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased() {
        case "CHAT_IMAGE": return .chatImage
        case "PERSONA_AVATAR", "USER_AVATAR": return .avatar
        case "STICKER": return .sticker
        case "AUDIO": return .audio
        case "VIDEO": return .video
        default: return nil
        }
    }

    /// 兜底扩展名（mime 认不出来时用；服务端媒体一定有 mime，这条只是防御）。
    var fallbackExtension: String {
        switch self {
        case .chatImage, .avatar: return "jpg"
        case .sticker: return "gif"
        case .audio: return "m4a"
        case .video: return "mp4"
        }
    }
}

/// 从字节里认出「这是哪一类媒体」（0.13 第二阶段）。
///
/// 为什么需要它：服务端消息的附件**只带 mediaId**，不带 kind/mime（`/v1/state` 的
/// `attachments` 是字符串数组）。本机上传过的媒体靠 `ServerMediaMetaStore` 还记得；
/// **别的设备**上传的（Android 发的语音、另一台 iPhone 发的视频）本机一无所知 ——
/// 只按「没有 kind 就当图片」处理，就会把一段 .m4a 塞进 attachments 目录，
/// 气泡变成一张永远加载不出来的「坏图」。
///
/// 所以下载完之后按**文件头**再认一次。这不是万能嗅探：只认两端会传的那几种容器
/// （JPEG/PNG/WebP/GIF、ISO BMFF 的音频/视频、MP3）。认不出来就返回 nil，
/// 调用方继续用原来的推断（宁可当成图片，也不要瞎猜成语音）。
public struct ServerMediaSniffed: Equatable, Sendable {
    public var kind: ServerMediaLocalKind
    public var mime: String
    /// 写进 `attachmentsJson` 的 `kind` 字段（`IMAGE` / `STICKER` / `AUDIO` / `VIDEO`）。
    public var attachmentKind: String

    public init(kind: ServerMediaLocalKind, mime: String, attachmentKind: String) {
        self.kind = kind
        self.mime = mime
        self.attachmentKind = attachmentKind
    }
}

public enum ServerMediaSniffer {

    public static func sniff(_ data: Data) -> ServerMediaSniffed? {
        // 最少 8 字节才够看 JPEG/PNG/GIF/MP3 的头；ISO BMFF 的品牌还要再多 4 字节（下面单独判）。
        guard data.count >= 8 else { return nil }
        let bytes = [UInt8](data.prefix(16))
        // JPEG：FF D8 FF
        if bytes[0] == 0xFF, bytes[1] == 0xD8, bytes[2] == 0xFF {
            return ServerMediaSniffed(kind: .chatImage, mime: "image/jpeg",
                                      attachmentKind: MessageAttachment.imageKind)
        }
        // PNG：89 50 4E 47 0D 0A 1A 0A
        if bytes[0] == 0x89, bytes[1] == 0x50, bytes[2] == 0x4E, bytes[3] == 0x47 {
            return ServerMediaSniffed(kind: .chatImage, mime: "image/png",
                                      attachmentKind: MessageAttachment.imageKind)
        }
        // GIF：`GIF87a` / `GIF89a` —— 表情就是这个（必须落进 stickers 目录）。
        if bytes[0] == 0x47, bytes[1] == 0x49, bytes[2] == 0x46, bytes[3] == 0x38 {
            return ServerMediaSniffed(kind: .sticker, mime: "image/gif",
                                      attachmentKind: MessageAttachment.stickerKind)
        }
        // WebP：`RIFF` + 4 字节长度 + `WEBP`
        if bytes[0] == 0x52, bytes[1] == 0x49, bytes[2] == 0x46, bytes[3] == 0x46,
           bytes[8] == 0x57, bytes[9] == 0x45, bytes[10] == 0x42, bytes[11] == 0x50 {
            return ServerMediaSniffed(kind: .chatImage, mime: "image/webp",
                                      attachmentKind: MessageAttachment.imageKind)
        }
        // MP3：`ID3` 头，或者 0xFFEx/0xFFFx 帧同步。
        if bytes[0] == 0x49, bytes[1] == 0x44, bytes[2] == 0x33 {
            return ServerMediaSniffed(kind: .audio, mime: "audio/mpeg",
                                      attachmentKind: MessageAttachment.audioKind)
        }
        if bytes[0] == 0xFF, (bytes[1] & 0xE0) == 0xE0 {
            return ServerMediaSniffed(kind: .audio, mime: "audio/mpeg",
                                      attachmentKind: MessageAttachment.audioKind)
        }
        // ISO BMFF：`[4 字节 size][ftyp][brand…]`（mp4 / m4a / mov 都是这个容器）。
        if bytes.count >= 12,
           bytes[4] == 0x66, bytes[5] == 0x74, bytes[6] == 0x79, bytes[7] == 0x70 {
            let brand = String(bytes: bytes[8..<12], encoding: .ascii) ?? ""
            if brand.hasPrefix("M4A") || brand.hasPrefix("M4B") || brand == "mp4a" {
                return ServerMediaSniffed(kind: .audio, mime: "audio/m4a",
                                          attachmentKind: MessageAttachment.audioKind)
            }
            if brand == "qt  " {
                return ServerMediaSniffed(kind: .video, mime: "video/quicktime",
                                          attachmentKind: MessageAttachment.videoKind)
            }
            return ServerMediaSniffed(kind: .video, mime: "video/mp4",
                                      attachmentKind: MessageAttachment.videoKind)
        }
        return nil
    }
}

/// 服务端媒体在本机的落盘器：**只做 IO**，不联网（下载在 `ServerMediaSyncService` 里）。
///
/// 四个目录都可注入：单测用临时目录，绝不往 `MediaStorage.shared` 里写测试数据。
public final class ServerMediaLocalStore {

    /// 语音/视频/表情的硬上限（与 `AudioVideoLimits` / `StickerLimits` 同一组数字）。
    /// 超过就**不落盘**：一份 200 MB 的「视频」写进沙盒，用户只会觉得 App 在偷空间。
    public static let maxBytesPerKind: [ServerMediaLocalKind: Int] = [
        .video: AudioVideoLimits.videoMaxBytes,
        .audio: AudioVideoLimits.audioMaxBytes,
        .sticker: StickerLimits.maxDownloadBytes
    ]

    public let attachmentsDirectory: URL
    public let stickersDirectory: URL
    public let audioDirectory: URL
    public let videoDirectory: URL

    private let fileManager: FileManager
    private static let lock = NSRecursiveLock()

    public init(
        attachmentsDirectory: URL,
        stickersDirectory: URL,
        audioDirectory: URL,
        videoDirectory: URL,
        fileManager: FileManager = .default
    ) {
        self.attachmentsDirectory = attachmentsDirectory
        self.stickersDirectory = stickersDirectory
        self.audioDirectory = audioDirectory
        self.videoDirectory = videoDirectory
        self.fileManager = fileManager
        for directory in [attachmentsDirectory, stickersDirectory, audioDirectory, videoDirectory] {
            try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            Self.applyBackupExclusion(directory)
        }
    }

    /// App 里的默认实例：与本地媒体同一批目录（`Application Support/…`）。
    public static let shared: ServerMediaLocalStore = ServerMediaLocalStore(
        attachmentsDirectory: MediaStorage.shared.directory,
        stickersDirectory: StickerStorage.shared.directory,
        audioDirectory: AudioVideoStore.shared.audioDirectory,
        videoDirectory: AudioVideoStore.shared.videoDirectory
    )

    /// 这些目录都**不进** iCloud/iTunes 整机备份（与 `MediaStorage` 同一条约定）：
    /// 二进制由 App 自己的备份 v6 负责搬，整机备份再搬一份只会翻倍。
    private static func applyBackupExclusion(_ directory: URL) {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var target = directory
        try? target.setResourceValues(values)
    }

    public func directory(for kind: ServerMediaLocalKind) -> URL {
        switch kind {
        case .chatImage, .avatar: return attachmentsDirectory
        case .sticker: return stickersDirectory
        case .audio: return audioDirectory
        case .video: return videoDirectory
        }
    }

    /// 文件名：`<前缀><mediaId>-<内容哈希前 16 位>.<ext>`。
    ///
    /// mediaId 是外部输入（服务端生成，仍然按不可信处理）：只留字母数字与 `-`/`_`，
    /// 截断到 64 个字符 —— 它决定文件名，绝不能让它带路径分隔符。
    public static func fileName(
        mediaId: String,
        kind: ServerMediaLocalKind,
        mime: String,
        data: Data
    ) -> String {
        let safeId = sanitized(mediaId)
        let hash = shortHash(data)
        let ext = AudioVideoLimits.fileExtension(forMime: mime)
            ?? BackupMediaTransfer.fileExtension(forMime: mime)
            ?? kind.fallbackExtension
        return "\(kind.fileNamePrefix)\(safeId)-\(hash).\(ext)"
    }

    /// 这个 mediaId 在本机是不是已经有文件了（幂等的判据）。
    ///
    /// 只按 id 匹配前缀、不比对哈希：服务端的 media id 是**内容寻址**的（`sha256` 去重），
    /// 同一个 id 的字节不会变。已经有一份就直接用，不再下载。
    public func existingFile(mediaId: String, kind: ServerMediaLocalKind) -> URL? {
        let safeId = Self.sanitized(mediaId)
        guard !safeId.isEmpty else { return nil }
        let directory = self.directory(for: kind)
        let prefix = "\(kind.fileNamePrefix)\(safeId)-"
        Self.lock.lock()
        defer { Self.lock.unlock() }
        guard let items = try? fileManager.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.isRegularFileKey]
        ) else { return nil }
        guard let match = items.first(where: { url in
            url.lastPathComponent.hasPrefix(prefix)
                && (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true
        }) else { return nil }
        // 用逻辑目录拼回路径（临时目录在 macOS 上是 `/var` → `/private/var` 的软链，
        // 枚举出来的路径与写进去的字符串不一样，会让 `attachmentsJson` 的 uri 来回抖）。
        return directory.standardizedFileURL
            .appendingPathComponent(match.lastPathComponent, isDirectory: false)
            .standardizedFileURL
    }

    /// 落盘一份媒体；已有同 id 文件时直接返回它（**不重写**）。
    ///
    /// - Throws: `ServerMediaLocalError`（空文件 / 超限 / 写不进去）。
    @discardableResult
    public func store(
        data: Data,
        mediaId: String,
        kind: ServerMediaLocalKind,
        mime: String
    ) throws -> URL {
        let safeId = Self.sanitized(mediaId)
        guard !safeId.isEmpty else { throw ServerMediaLocalError.invalidMediaId }
        guard !data.isEmpty else { throw ServerMediaLocalError.empty }
        if let limit = Self.maxBytesPerKind[kind], data.count > limit {
            throw ServerMediaLocalError.tooLarge(limit)
        }
        Self.lock.lock()
        defer { Self.lock.unlock() }
        if let existing = existingFile(mediaId: safeId, kind: kind) { return existing }
        let directory = self.directory(for: kind).standardizedFileURL
        let name = Self.fileName(mediaId: safeId, kind: kind, mime: mime, data: data)
        let target = directory.appendingPathComponent(name, isDirectory: false).standardizedFileURL
        // 归属守卫：数据只能决定文件名，不能决定路径。
        guard AttachmentLocation.isWithin(directory, target) else {
            throw ServerMediaLocalError.writeFailed
        }
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: target, options: .atomic)
        } catch {
            throw ServerMediaLocalError.writeFailed
        }
        return target
    }

    /// 一次「落盘 + 校验」的结果。
    public struct StoredFile: Equatable, Sendable {
        public var url: URL
        /// 落盘字节的 sha256（64 位小写 hex）。
        public var sha256: String
        /// true = 本机已有同 id 文件，没有重新写。
        public var reused: Bool

        public init(url: URL, sha256: String, reused: Bool) {
            self.url = url
            self.sha256 = sha256
            self.reused = reused
        }
    }

    /// 落盘并校验服务端声明的 sha256（0.15）。
    ///
    /// 流程（与「不一致就删掉、记失败、不落库」这条要求对应）：
    /// 1. 先写进**临时文件**（`.<name>.tmp-<uuid>`，与目标同目录，保证 rename 原子）；
    /// 2. 从临时文件**流式**算出 sha256；
    /// 3. 与服务端声明的哈希比对：不一致 → 删临时文件、抛 `hashMismatch`，
    ///    目标路径一个字节都不写（调用方据此只计数、不改库）；
    /// 4. 一致 → 原子改名成目标文件名，返回哈希。
    ///
    /// `expectedSha256` 为空 = 服务端没给哈希（老服务端 / 老数据）：照常落盘，不做校验。
    @discardableResult
    public func storeVerified(
        data: Data,
        mediaId: String,
        kind: ServerMediaLocalKind,
        mime: String,
        expectedSha256: String? = nil
    ) throws -> StoredFile {
        let safeId = Self.sanitized(mediaId)
        guard !safeId.isEmpty else { throw ServerMediaLocalError.invalidMediaId }
        guard !data.isEmpty else { throw ServerMediaLocalError.empty }
        if let limit = Self.maxBytesPerKind[kind], data.count > limit {
            throw ServerMediaLocalError.tooLarge(limit)
        }
        let expected = ServerMediaHash.normalized(expectedSha256)
        Self.lock.lock()
        defer { Self.lock.unlock() }

        // 幂等：同一个 id 已有文件且（有哈希时）哈希一致 → 直接复用，不重写。
        if let existing = existingFile(mediaId: safeId, kind: kind),
           let existingHash = sha256Hex(ofFileAt: existing) {
            if let expected, existingHash != expected {
                // 本机这份和「服务端声明的内容」对不上：删掉重下（绝不把坏字节当缓存用）。
                try? fileManager.removeItem(at: existing)
            } else {
                return StoredFile(url: existing, sha256: existingHash, reused: true)
            }
        }

        let directory = self.directory(for: kind).standardizedFileURL
        let name = Self.fileName(mediaId: safeId, kind: kind, mime: mime, data: data)
        let target = directory.appendingPathComponent(name, isDirectory: false).standardizedFileURL
        guard AttachmentLocation.isWithin(directory, target) else {
            throw ServerMediaLocalError.writeFailed
        }
        let temp = directory.appendingPathComponent(
            ".\(name).tmp-\(UUID().uuidString)", isDirectory: false
        ).standardizedFileURL
        guard AttachmentLocation.isWithin(directory, temp) else {
            throw ServerMediaLocalError.writeFailed
        }
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: temp, options: .atomic)
        } catch {
            try? fileManager.removeItem(at: temp)
            throw ServerMediaLocalError.writeFailed
        }
        guard let actual = sha256Hex(ofFileAt: temp) else {
            try? fileManager.removeItem(at: temp)
            throw ServerMediaLocalError.writeFailed
        }
        if let expected, actual != expected {
            // 校验失败：临时文件立刻删掉，**目标路径不落任何字节**。
            try? fileManager.removeItem(at: temp)
            throw ServerMediaLocalError.hashMismatch(expected: expected, actual: actual)
        }
        do {
            if fileManager.fileExists(atPath: target.path) {
                try? fileManager.removeItem(at: target)
            }
            try fileManager.moveItem(at: temp, to: target)
        } catch {
            try? fileManager.removeItem(at: temp)
            throw ServerMediaLocalError.writeFailed
        }
        return StoredFile(url: target, sha256: actual, reused: false)
    }

    /// 一个本机文件内容的 sha256（分块读，视频也不会一次进内存）；文件不在/读不出 → nil。
    public func sha256Hex(ofFileAt url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            guard let chunk = try? handle.read(upToCount: 256 * 1024), !chunk.isEmpty else { break }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// 删掉一个本机媒体文件（只删四个受管目录里的普通文件；别的一律不碰）。
    ///
    /// 用途只有一个：本机文件与服务端声明的 sha256 对不上时清掉它，下次重新下载。
    /// 目录归属守卫在这里同样成立 —— 传进来一条任意路径也不会误删。
    public func deleteStoredFile(at url: URL) {
        let standardized = url.standardizedFileURL
        let directories = [
            attachmentsDirectory, stickersDirectory, audioDirectory, videoDirectory
        ].map { $0.standardizedFileURL }
        guard directories.contains(where: { AttachmentLocation.isWithin($0, standardized) }) else {
            return
        }
        Self.lock.lock()
        defer { Self.lock.unlock() }
        try? fileManager.removeItem(at: standardized)
    }

    /// 把一条服务端媒体引用换成"落盘之后的附件"：`uri` 指向本地文件，`serverMediaId` 保留。
    ///
    /// 图片的宽高、语音/视频的时长与 `kind` 全部沿用传进来的元信息（服务端不下发这些，
    /// 它们来自上传时记下的 `ServerMediaMetaStore`，或者上一次同步写库的那一份）。
    public func localAttachment(
        for attachment: MessageAttachment,
        mediaId: String,
        kind: ServerMediaLocalKind,
        fileURL: URL
    ) -> MessageAttachment {
        var local = attachment
        local.uri = fileURL.absoluteString
        local.serverMediaId = mediaId
        if local.mime.isEmpty || local.mime == MessageAttachment.defaultMime,
           let guessed = BackupMediaTransfer.mime(forFileName: fileURL.lastPathComponent) {
            local.mime = guessed
        }
        // 0.15：kind 与 mime 必须匹配。老数据里「kind=AUDIO + mime 默认 image/jpeg」在
        // 落盘之后要按 kind 补成规范 mime，否则下一次 `AttachmentsCodec.decode` 会把
        // 这条附件当坏数据整段丢掉（比显示成坏图更难查）。
        if let type = local.mediaType, !type.accepts(mime: local.mime) {
            switch type {
            case .sticker: local.mime = "image/gif"
            case .audio: local.mime = AudioVideoLimits.audioMime
            case .video: local.mime = AudioVideoLimits.videoMime
            case .image: local.mime = MessageAttachment.defaultMime
            case .avatar: break
            }
        }
        return local
    }

    /// mediaId → 安全文件名字符集。
    static func sanitized(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        let allowed = CharacterSet(charactersIn:
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")
        var scalars: [Unicode.Scalar] = []
        for scalar in trimmed.unicodeScalars {
            scalars.append(allowed.contains(scalar) ? scalar : "-")
            if scalars.count >= 64 { break }
        }
        let text = String(String.UnicodeScalarView(scalars))
        return text.isEmpty ? "" : text
    }

    static func shortHash(_ data: Data) -> String {
        String(ImageContentHash.sha256Hex(data).prefix(16))
    }
}

public enum ServerMediaLocalError: Error, LocalizedError, Equatable {
    case invalidMediaId
    case empty
    case tooLarge(Int)
    case writeFailed
    /// 下载回来的字节与服务端声明的 sha256 不一致（0.15）。
    ///
    /// 出现它就说明「这份字节不是服务端那份」：临时文件已经删掉、库里也不该改，
    /// 下次同步会重新下载。不重试到成功为止（避免坏代理把同步卡死）。
    case hashMismatch(expected: String, actual: String)

    public var errorDescription: String? {
        switch self {
        case .invalidMediaId: return String(localized: "这个媒体 id 不合法")
        case .empty: return String(localized: "服务端返回的媒体是空的")
        case .tooLarge(let limit):
            return String(format: String(localized: "这个媒体超过上限（%d MB），没有存到本机"), limit / (1024 * 1024))
        case .writeFailed: return String(localized: "媒体没能存到本机")
        case .hashMismatch:
            return String(localized: "下载回来的媒体与校验值不一致，已丢弃")
        }
    }
}


/// 本机媒体文件的 sha256 台账（0.15）。
///
/// 存在 `server_sync_state`（与 `ServerMediaMetaStore` 同一个理由：这是**附属于媒体的状态**，
/// 不是一张业务记录表；跨端 schema 不需要为它多一版）。
///
/// 用途只有两件：
/// 1. 下载落地后记下「这份文件的 sha256 就是服务端声明的那个」；
/// 2. 下次「已有文件就复用」之前复核 —— 文件被改坏/截断时能发现并重下。
/// 没有记录（老数据）时跳过复核，行为与 0.14 一样。
public final class ServerMediaLocalHashStore {

    private let state: ServerSyncStateStore
    private let now: () -> Int64

    public init(state: ServerSyncStateStore, now: @escaping () -> Int64 = { DomainClock.nowMillis }) {
        self.state = state
        self.now = now
    }

    public convenience init(
        database: SQLiteDatabase,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.init(state: ServerSyncStateStore(database: database), now: now)
    }

    /// 记下的哈希（不存在 / 格式坏 → nil）。
    public func recorded(mediaId: String) -> String? {
        let id = mediaId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return nil }
        return ServerMediaHash.normalized(
            (try? state.value(ServerSyncStateStore.Key.mediaLocalHash(id))) ?? nil
        )
    }

    /// 记一行（哈希不合法就忽略：宁可没有记录，也不要记一个永远对不上的值）。
    @discardableResult
    public func record(mediaId: String, sha256: String) -> Bool {
        let id = mediaId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty, let hash = ServerMediaHash.normalized(sha256) else { return false }
        return (try? state.set(hash, for: ServerSyncStateStore.Key.mediaLocalHash(id), at: now())) != nil
    }

    public func clear(mediaId: String) {
        let id = mediaId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        try? state.set(nil, for: ServerSyncStateStore.Key.mediaLocalHash(id), at: now())
    }
}
