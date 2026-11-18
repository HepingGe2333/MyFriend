import Foundation

// ---------------------------------------------------------------------------
// 表情文件本体（`Application Support/stickers/<id>.gif`）。
//
// 与 `MediaStorage`（聊天图）分开的原因：
// - 聊天图是**一条消息的附件**（删消息 → 可能删文件），表情是**用户自己的收藏**，
//   可以脱离任何一条消息存在；
// - 表情的消息附件直接引用这个目录里的文件（发送时不复制、不重新编码），
//   所以两套生命周期不能混在一起。
//
// `stickers.file` 存的是绝对路径（规格：「本机文件路径或 /v1/media/<id>」）。
// 但 iOS 的 App 容器路径里带一段 UUID，重装/迁移之后会变，所以解析规则与
// `AttachmentLocation` 同一条：绝对路径还能用就直接用；文件不在就用**同名文件**
// 在当前目录里找回来。服务端媒体（`/v1/media/<id>`）返回 nil，由渲染层走
// `ServerMediaCache`。
// ---------------------------------------------------------------------------

public final class StickerStorage {

    public enum StorageError: LocalizedError, Equatable {
        case emptyFile
        case writeFailed
        case notGIF

        public var errorDescription: String? {
            switch self {
            case .emptyFile: return String(localized: "这个表情是空的")
            case .writeFailed: return String(localized: "表情没能存到本机")
            case .notGIF: return String(localized: "表情必须是 GIF")
            }
        }
    }

    /// 表情目录。第一次访问时创建。
    public let directory: URL

    private let fileManager: FileManager
    private static let fileLock = NSRecursiveLock()

    public init(directory: URL, fileManager: FileManager = .default) {
        self.directory = directory
        self.fileManager = fileManager
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        applyBackupExclusion()
    }

    /// App 里的默认实例：`Application Support/stickers`。
    public static let shared: StickerStorage = {
        let fallback = FileManager.default.temporaryDirectory
            .appendingPathComponent("yap-stickers", isDirectory: true)
        let base = (try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? fallback
        return StickerStorage(directory: base.appendingPathComponent("stickers", isDirectory: true))
    }()

    /// 表情文件不进 iCloud/iTunes 整机备份（与聊天图同一条约定：换机后绝对路径也指不到）。
    func applyBackupExclusion() {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var target = directory
        try? target.setResourceValues(values)
    }

    /// 文件名：`<id>.gif`（id 由调用方生成，通常是 UUID；不信任外部传入的 id 里的路径分隔符）。
    public static func fileName(for id: String) -> String {
        let safe = sanitize(id)
        return safe + ".gif"
    }

    /// 去掉路径分隔符与 `..`：id 会进文件名，不能让它变成路径穿越。
    static func sanitize(_ raw: String) -> String {
        let forbidden = CharacterSet(charactersIn: "/\\:?*\"<>|\n\r\t")
        let cleaned = raw
            .components(separatedBy: forbidden)
            .joined(separator: "-")
            .replacingOccurrences(of: "..", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? UUID().uuidString : String(cleaned.prefix(120))
    }

    /// 写一个表情文件，返回落盘 URL。
    ///
    /// - Throws: 空字节 / 写盘失败。
    @discardableResult
    public func write(_ data: Data, id: String) throws -> URL {
        guard !data.isEmpty else { throw StorageError.emptyFile }
        let target = directory.appendingPathComponent(Self.fileName(for: id), isDirectory: false)
        Self.fileLock.lock()
        defer { Self.fileLock.unlock() }
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: target, options: .atomic)
            return target
        } catch {
            throw StorageError.writeFailed
        }
    }

    /// 把外部文件复制进表情目录（收藏用户从聊天里发来的表情时用）。
    @discardableResult
    public func copy(from source: URL, id: String) throws -> URL {
        guard fileManager.fileExists(atPath: source.path) else { throw StorageError.emptyFile }
        let target = directory.appendingPathComponent(Self.fileName(for: id), isDirectory: false)
        Self.fileLock.lock()
        defer { Self.fileLock.unlock() }
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            if source.standardizedFileURL.path != target.standardizedFileURL.path {
                if fileManager.fileExists(atPath: target.path) {
                    try? fileManager.removeItem(at: target)
                }
                try fileManager.copyItem(at: source, to: target)
            }
            return target
        } catch {
            throw StorageError.writeFailed
        }
    }

    /// `stickers.file` → 本机文件 URL；服务端媒体或文件不在了返回 nil。
    public func fileURL(for file: String) -> URL? {
        let trimmed = file.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        // 服务端媒体不是本机文件：交给 `ServerMediaCache`。
        guard ServerMediaReference.mediaId(from: trimmed) == nil else { return nil }
        guard !AttachmentLocation.hasPathTraversal(trimmed) else { return nil }

        if trimmed.lowercased().hasPrefix("file://") {
            guard let url = URL(string: trimmed), url.isFileURL else { return nil }
            return resolve(url)
        }
        if trimmed.hasPrefix("/") {
            return resolve(URL(fileURLWithPath: trimmed))
        }
        // 相对路径（防御手工数据）：只允许最后一段。
        let name = (trimmed as NSString).lastPathComponent
        guard !name.isEmpty, name != ".", name != ".." else { return nil }
        let candidate = directory.appendingPathComponent(name).standardizedFileURL
        guard AttachmentLocation.isWithin(directory, candidate) else { return nil }
        return fileManager.fileExists(atPath: candidate.path) ? candidate : nil
    }

    public func fileExists(_ sticker: StickerEntity) -> Bool {
        fileURL(for: sticker.file) != nil
    }

    public func data(for sticker: StickerEntity) -> Data? {
        guard let url = fileURL(for: sticker.file) else { return nil }
        return try? Data(contentsOf: url)
    }

    /// 绝对路径：容器没变就直接用；变了就用同名文件在当前目录里找回来。
    private func resolve(_ url: URL) -> URL? {
        let standardized = url.standardizedFileURL
        if fileManager.fileExists(atPath: standardized.path),
           AttachmentLocation.isWithin(directory, standardized) {
            return standardized
        }
        let name = standardized.lastPathComponent
        guard !name.isEmpty, name != ".", name != ".." else { return nil }
        let candidate = directory.appendingPathComponent(name).standardizedFileURL
        guard AttachmentLocation.isWithin(directory, candidate) else { return nil }
        return fileManager.fileExists(atPath: candidate.path) ? candidate : nil
    }

    /// 删一个文件（**只删自己目录里的**；服务端媒体不删）。返回真的删掉了没。
    @discardableResult
    public func delete(file: String) -> Bool {
        guard let url = fileURL(for: file) else { return false }
        guard AttachmentLocation.isWithin(directory, url) else { return false }
        Self.fileLock.lock()
        defer { Self.fileLock.unlock() }
        do {
            try fileManager.removeItem(at: url)
            return true
        } catch {
            return false
        }
    }

    /// 删掉目录里的**全部**表情文件并重建目录（「删除全部数据」用它）。
    ///
    /// 只动自己的目录：聊天附件在 `attachments/`，由 `MediaStorage` 管。
    @discardableResult
    public func deleteAll() -> Int {
        Self.fileLock.lock()
        defer { Self.fileLock.unlock() }
        let items = (try? fileManager.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: nil
        )) ?? []
        var removed = 0
        for url in items where AttachmentLocation.isWithin(directory, url) {
            if (try? fileManager.removeItem(at: url)) != nil { removed += 1 }
        }
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        applyBackupExclusion()
        return removed
    }

    /// 目录里现在有多少文件 / 共多少字节（诊断与设置页「清理」预留）。
    public func totalBytesOnDisk() -> Int {
        guard let items = try? fileManager.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.fileSizeKey]
        ) else { return 0 }
        return items.reduce(0) { sum, url in
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return sum + size
        }
    }
}
