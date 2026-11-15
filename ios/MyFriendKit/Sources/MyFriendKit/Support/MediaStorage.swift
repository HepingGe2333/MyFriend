import CoreGraphics
import Foundation
import ImageIO

#if canImport(UIKit)
import UIKit
#endif

// ---------------------------------------------------------------------------
// 图片落盘、压缩、缩略图解码与缓存。
//
// 来源：Android `util/MediaStorage.kt`。做的事情一模一样：
//
//   相册选图 → 按显示尺寸解码（不当整图常驻）→ 长边限制 + JPEG 质量 → 写进沙盒
//   → `messages.attachmentsJson` / `personas.avatarUri` 只存 `file://` 路径。
//
// 与 Android 的差别（iOS 侧更严一点，规格要求）：
// - 聊天图长边 ≤ 1600px、JPEG 质量 0.85、体积上限 ~800KB（Android 是 1568 / 88 / 4MB）；
// - 头像长边 ≤ 512px、JPEG 质量 0.82、体积上限 ~400KB；
// - 任何一次解码都走 `CGImageSourceCreateThumbnailAtIndex`（按最大像素尺寸解码），
//   解码结果进 `NSCache`，上限 = min(物理内存 × 10%, 48MB)，收到内存警告就清空。
//
// 为什么把「参数」与「IO」分开：`ImageEncodingPolicy` 全是纯函数，可以在 macOS 上
// `swift test` 直接断言尺寸与压缩档位；真解码/写盘那一层才碰 ImageIO。
// ---------------------------------------------------------------------------

// MARK: - 纯参数

/// 附件种类：聊天气泡里的大图，还是头像（显示尺寸小得多，没必要存 1600px）。
public enum MediaKind: String, CaseIterable, Sendable {
    case chatImage
    case avatar
}

/// 压缩与尺寸策略。全部是纯函数/常量，单测直接调。
public enum ImageEncodingPolicy {

    /// 聊天图长边上限。
    public static let chatMaxLongEdge = 1600
    /// 聊天图 JPEG 质量。
    public static let chatJPEGQuality = 0.85
    /// 聊天图体积上限（约 800KB）。
    public static let chatMaxBytes = 800 * 1024

    /// 头像长边上限。
    public static let avatarMaxLongEdge = 512
    /// 头像 JPEG 质量。
    public static let avatarJPEGQuality = 0.82
    /// 头像体积上限（约 400KB）。
    public static let avatarMaxBytes = 400 * 1024

    /// 选图体积的硬上限：比这个还大就直接告诉用户「换一张小的」。
    public static let maxInputBytes = 20 * 1024 * 1024
    /// 质量阶梯的下限：再降画质就没法看了，宁可让体积略超。
    public static let minimumQuality = 0.55
    /// 每次重试降多少质量。
    public static let qualityStep = 0.10

    public static func longEdge(for kind: MediaKind) -> Int {
        switch kind {
        case .chatImage: return chatMaxLongEdge
        case .avatar: return avatarMaxLongEdge
        }
    }

    public static func jpegQuality(for kind: MediaKind) -> Double {
        switch kind {
        case .chatImage: return chatJPEGQuality
        case .avatar: return avatarJPEGQuality
        }
    }

    public static func byteLimit(for kind: MediaKind) -> Int {
        switch kind {
        case .chatImage: return chatMaxBytes
        case .avatar: return avatarMaxBytes
        }
    }

    /// 等比缩放到「长边 ≤ longEdge」；本来就小就原样返回（**不放大**）。
    /// 任何一边不是正数就原样退回（调用方会把它当坏图处理）。
    public static func fittedSize(width: Int, height: Int, longEdge: Int) -> (width: Int, height: Int) {
        guard width > 0, height > 0, longEdge > 0 else { return (width, height) }
        let longest = max(width, height)
        guard longest > longEdge else { return (width, height) }
        let scale = Double(longEdge) / Double(longest)
        let fittedWidth = max(1, Int((Double(width) * scale).rounded()))
        let fittedHeight = max(1, Int((Double(height) * scale).rounded()))
        return (fittedWidth, fittedHeight)
    }

    /// 压缩重试阶梯：0.85 → 0.75 → 0.65 → 0.55（到下限为止）。
    /// 传进来的质量本来就低于下限时，就只用它自己（不凭空「变好」）。
    public static func qualityLadder(from quality: Double) -> [Double] {
        let clamped = min(max(quality, 0), 1)
        var result: [Double] = []
        var current = clamped
        while current >= minimumQuality - 1e-9 {
            result.append((current * 100).rounded() / 100)
            current -= qualityStep
        }
        return result.isEmpty ? [clamped] : result
    }

    /// 选图是不是超过了「连读都不该读进内存」的硬上限。
    public static func isTooLargeInput(_ bytes: Int) -> Bool {
        bytes > maxInputBytes
    }

    /// 写出来的文件是不是还超过这一类附件的体积上限。
    public static func exceedsByteLimit(_ bytes: Int, kind: MediaKind) -> Bool {
        bytes > byteLimit(for: kind)
    }
}

/// 内存预算：缩略图缓存能占多少。
///
/// 为什么按比例而不是写死 48MB：小内存设备（3GB）上 48MB 会明显挤压聊天本身；
/// 大内存设备（8GB）上又太抠。`min(内存 × 10%, 48MB)` 是两层保险。
public enum MemoryBudget {

    /// 缓存最多占物理内存的十分之一。
    public static let cacheFraction = 0.10
    /// 缓存上限的绝对值。
    public static let cacheCapBytes = 48 * 1024 * 1024

    public static func thumbnailCacheLimitBytes(physicalMemory: UInt64) -> Int {
        let tenPercent = Double(physicalMemory) * cacheFraction
        let capped = min(tenPercent, Double(cacheCapBytes))
        return max(1, Int(capped))
    }

    /// 一张解码后的位图占多少（4 字节/像素，RGBA）。
    public static func imageCostBytes(width: Int, height: Int) -> Int {
        max(1, width) * max(1, height) * 4
    }
}

// MARK: - 缩略图解码

/// 按「要显示多大」解码，而不是先把整图读进内存再缩放。
public enum ImageThumbnailDecoder {

    /// 从文件解一张长边 ≤ `maxPixelSize` 的图（EXIF 方向会被摆正）。
    ///
    /// 关键在 `kCGImageSourceCreateThumbnailFromImageAlways` + `ThumbnailMaxPixelSize`：
    /// ImageIO 在解码阶段就直接出缩略图，不会先解一张 1200 万像素的原图。
    public static func thumbnail(url: URL, maxPixelSize: Int) -> CGImage? {
        guard maxPixelSize > 0 else { return nil }
        // 只用文件路径：不把整张图读成 Data（那等于把原图常驻一次）。
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        return thumbnail(source: source, maxPixelSize: maxPixelSize)
    }

    public static func thumbnail(data: Data, maxPixelSize: Int) -> CGImage? {
        guard maxPixelSize > 0, !data.isEmpty else { return nil }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return thumbnail(source: source, maxPixelSize: maxPixelSize)
    }

    private static func thumbnail(source: CGImageSource, maxPixelSize: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// 原图像素尺寸（只看元数据，不解码像素）。
    public static func pixelSize(url: URL) -> (width: Int, height: Int)? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        else { return nil }
        let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? 0
        let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? 0
        guard width > 0, height > 0 else { return nil }
        return (width, height)
    }
}

// MARK: - 缩略图缓存

/// 解码后的缩略图缓存。
///
/// `NSCache` 自己会在系统吃紧时回收，但我们仍然显式监听内存警告并清空：
/// 聊天页一屏十几张图，系统回收是滞后的，用户切回来那一下的卡顿是能感觉到的。
public final class ImageThumbnailCache: NSObject {

    public static let shared = ImageThumbnailCache()

    private final class Box {
        let image: CGImage
        init(_ image: CGImage) { self.image = image }
    }

    private let cache = NSCache<NSString, Box>()

    /// 收到内存警告时的回调（App 层在 Debug 里接一条日志，Release 里不接）。
    public var onMemoryWarning: (() -> Void)?

    public init(totalCostLimit: Int = MemoryBudget.thumbnailCacheLimitBytes(
        physicalMemory: ProcessInfo.processInfo.physicalMemory
    )) {
        super.init()
        cache.totalCostLimit = totalCostLimit
        #if canImport(UIKit)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleMemoryWarning),
            name: UIApplication.didReceiveMemoryWarningNotification,
            object: nil
        )
        #endif
    }

    public var totalCostLimit: Int {
        get { cache.totalCostLimit }
        set { cache.totalCostLimit = newValue }
    }

    public var countLimit: Int {
        get { cache.countLimit }
        set { cache.countLimit = newValue }
    }

    public func image(forKey key: String) -> CGImage? {
        cache.object(forKey: key as NSString)?.image
    }

    public func store(_ image: CGImage, forKey key: String) {
        let cost = MemoryBudget.imageCostBytes(width: image.width, height: image.height)
        cache.setObject(Box(image), forKey: key as NSString, cost: cost)
    }

    public func removeAll() {
        cache.removeAllObjects()
    }

    #if canImport(UIKit)
    @objc private func handleMemoryWarning() {
        removeAll()
        onMemoryWarning?()
    }
    #endif
}

// MARK: - 路径解析

/// `attachmentsJson` 里的 `uri` → 当前沙盒里的真实文件。
///
/// 为什么不能「拿到路径就直接开文件」：iOS 的 App 数据容器路径里带一段 UUID
/// （`.../Application/<UUID>/Library/Application Support/attachments/...`）。
/// 重装、从备份迁移、某些更新之后这段 UUID 会变，数据库里的绝对路径全部指向
/// 一个已经不存在的目录 —— 图片就会集体变成「图片已不在本机」。
///
/// 所以这里的规则是：
/// 1. `file://` 且文件还在 → 直接用；
/// 2. `file://` 但文件不在 → 用**同名文件**在当前附件目录里找回来（容器换了、图还在）；
/// 3. 没有 scheme 的相对路径 → 按当前附件目录解（防御手工数据）；
/// 4. `http(s)://` / `content://` 之类 → 不是本机文件，返回 nil。
public enum AttachmentLocation {

    /// 目录归属判定（与 Android `MediaOwnership.isWithin` 等价）。
    ///
    /// 三条规则：① 标准化后必须**是目录的子路径**（不是「同前缀」——`attachments2/` 不算）；
    /// ② 解开符号链接之后再比一次（防止附件目录里放一个指向沙盒外的软链）；
    /// ③ 目录自身不算「目录里的文件」。
    public static func isWithin(_ directory: URL, _ candidate: URL) -> Bool {
        let directoryPath = directory.standardizedFileURL.path
        let path = candidate.standardizedFileURL.path
        guard path != directoryPath else { return false }
        let prefix = directoryPath.hasSuffix("/") ? directoryPath : directoryPath + "/"
        guard path.hasPrefix(prefix) else { return false }

        // canonical：软链解开后再比一次（`resolvingSymlinksInPath` 对不存在的路径原样返回）。
        let canonicalDirectory = directory.resolvingSymlinksInPath().standardizedFileURL.path
        let canonicalPath = candidate.resolvingSymlinksInPath().standardizedFileURL.path
        guard canonicalPath != canonicalDirectory else { return false }
        let canonicalPrefix = canonicalDirectory.hasSuffix("/")
            ? canonicalDirectory
            : canonicalDirectory + "/"
        return canonicalPath.hasPrefix(canonicalPrefix)
    }

    /// 原始字符串里有没有路径穿越（按 `/` 与 `\\` 切片，先做百分号解码）。
    ///
    /// 0.11.1 安全审计 P2-3/P2-4：`../../myfriend.db` 以前会被当成「附件目录下的文件」，
    /// 于是 `exists()` 说它还在、`delete()` 真的把它删了。这条写在解析入口，一处收口。
    public static func hasPathTraversal(_ raw: String) -> Bool {
        let decoded = raw.removingPercentEncoding ?? raw
        let separators = CharacterSet(charactersIn: "/\\")
        return decoded.components(separatedBy: separators).contains("..")
    }

    public static func resolve(
        _ raw: String,
        directory: URL,
        fileExists: (String) -> Bool = { FileManager.default.fileExists(atPath: $0) }
    ) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        // 路径穿越一律拒绝：这是「数据即代码」的入口，`..` 不该被解释成语义。
        guard !hasPathTraversal(trimmed) else { return nil }

        guard let url = URL(string: trimmed) else { return nil }
        if let scheme = url.scheme?.lowercased(), scheme != "file" {
            return nil
        }

        if url.isFileURL {
            // 绝对路径：只有**落在当前附件目录里**才直接用（容器没变的情形）。
            // 旧容器路径（UUID 变了）或沙盒外路径都走下面的「同名文件找回来」分支；
            // 沙盒外的路径即便存在，也会因为同名文件不在附件目录里而返回 nil。
            if !hasPathTraversal(url.path), fileExists(url.path), isWithin(directory, url) {
                return url
            }
            let name = url.lastPathComponent
            guard !name.isEmpty, name != ".", name != ".." else { return nil }
            let candidate = directory.appendingPathComponent(name).standardizedFileURL
            guard isWithin(directory, candidate) else { return nil }
            return fileExists(candidate.path) ? candidate : nil
        }

        // 没有 scheme：当作相对当前附件目录的路径（防御手工数据）。
        guard !url.path.isEmpty, !url.path.hasPrefix("/") else { return nil }
        let candidate = directory.appendingPathComponent(url.path).standardizedFileURL
        guard isWithin(directory, candidate) else { return nil }
        return fileExists(candidate.path) ? candidate : nil
    }
}

// MARK: - 备份里的头像路径（与 Android `BackupAvatarPath` 等价）

/// 备份 JSON 里的 `avatarUri` → 能不能落库、落成什么。
///
/// Android 侧（0.10.2 安全审计 P2-2）先拒绝含 `..` 的整条路径、只取最后一段、
/// 再过 `isSafeName` 与 `MediaOwnership.isWithin`。iOS 以前只判断「文件存不存在」，
/// 于是 `../../myfriend.db` 这种值会被原样写进 `personas.avatarUri`。
public enum BackupAvatarPath {

    /// 名字最长多少（比任何我们自己写的 `avatar-<uuid>.jpg` 都长得多，只用来挡垃圾）。
    public static let maxNameBytes = 128

    /// 只取裸文件名；不安全就返回 nil。
    ///
    /// 拒绝：空串、`..`（路径穿越）、反斜杠、冒号（Windows 盘符/URL scheme）、
    /// `.`/`..`、超长、以及任何带路径分隔符的「名字」。
    public static func fileName(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        let decoded = trimmed.removingPercentEncoding ?? trimmed
        guard !self.hasPathTraversal(decoded) else { return nil }
        if decoded.contains("\\") { return nil }

        // 有 scheme（file:// 等）或绝对路径：取最后一段；裸文件名原样用。
        let name: String
        if decoded.contains("/") {
            name = (decoded as NSString).lastPathComponent
        } else if let url = URL(string: decoded), url.isFileURL {
            name = url.lastPathComponent
        } else {
            name = decoded
        }
        guard !name.isEmpty, name != ".", name != "..", !name.contains("/") else { return nil }
        guard !name.contains(":") else { return nil }
        guard name.utf8.count <= maxNameBytes else { return nil }
        return name
    }

    /// 导出用：只留「我们自己的头像文件名」，其它一律 nil。
    ///
    /// 0.11.1：iOS 导出本来就把绝对路径置空（`exportedAvatarUri`）。这里再补一条：
    /// 只有 `avatar-` 开头的裸文件名才算我们的资源，避免把 `/etc/passwd` 之类导出成 `"passwd"`。
    public static func portable(_ raw: String?) -> String? {
        guard let name = fileName(raw) else { return nil }
        let prefix = MediaKind.avatar.rawValue + "-"
        return name.hasPrefix(prefix) ? name : nil
    }

    /// 导入用：解析成当前附件目录里的绝对 URL 字符串；不存在/不安全就 nil。
    public static func resolveImported(
        _ raw: String?,
        directory: URL,
        fileExists: (String) -> Bool
    ) -> String? {
        guard let name = fileName(raw) else { return nil }
        let candidate = directory.appendingPathComponent(name).standardizedFileURL
        guard AttachmentLocation.isWithin(directory, candidate) else { return nil }
        guard fileExists(candidate.path) else { return nil }
        return candidate.absoluteString
    }

    private static func hasPathTraversal(_ text: String) -> Bool {
        AttachmentLocation.hasPathTraversal(text)
    }
}

// MARK: - 备份里的媒体（v3：图片二进制跟着备份走）

/// 备份 JSON 里 `media[].kind` 的三个取值。
///
/// 为什么是 String 而不是 Swift 枚举：Android 侧同样是字符串常量，而「不认识的 kind」
/// 必须能被导入侧**跳过并计数**，而不是把整条记录当成坏 JSON 丢掉 —— 枚举会强迫
/// 解码器在类型层面就失败。
public enum BackupMediaKind: String, CaseIterable, Sendable {
    case personaAvatar = "PERSONA_AVATAR"
    case userAvatar = "USER_AVATAR"
    case chatImage = "CHAT_IMAGE"

    /// 落盘文件名前缀：头像 `avatar-`、聊天图 `chatImage-`（与 `MediaKind.rawValue` 同源，
    /// `MediaStorage.deleteAllChatImages` 就是按这个前缀清图的）。
    public var fileNamePrefix: String {
        switch self {
        case .personaAvatar, .userAvatar: return MediaKind.avatar.rawValue + "-"
        case .chatImage: return MediaKind.chatImage.rawValue + "-"
        }
    }
}

/// v3 备份里媒体的硬上限（两端同一组数字，改了就是格式变更）。
public enum BackupMediaLimits {

    /// 一份备份里全部媒体的**原始**字节上限：128 MiB。
    public static let maxTotalBytes = 128 * 1024 * 1024
    /// 单个文件的**原始**字节上限：8 MiB。
    public static let maxFileBytes = 8 * 1024 * 1024
    /// `media[].fileName` 最长多少字节（只用来挡垃圾，比我们自己写的名字宽松得多）。
    public static let maxFileNameBytes = 200

    /// 只支持这三种图片格式。别的一律跳过并计数：解码器不认识它们，带过去也是坏图。
    public static let supportedMimes: Set<String> = ["image/jpeg", "image/png", "image/webp"]

    /// 单张 base64 文本的长度上限（含 padding）：再长就不可能落在单张预算里。
    public static func maxBase64Chars(perFileBytes: Int = maxFileBytes) -> Int {
        ((perFileBytes + 2) / 3) * 4
    }
}

/// 备份媒体的「纯」部分：base64、文件名校验、MIME ⇄ 扩展名、写入受管目录。
///
/// 全部是静态函数、不持有全局状态：单测可以拿临时目录直接喂进来，不碰 `MediaStorage.shared`。
public enum BackupMediaTransfer {

    // MARK: MIME

    public static func isSupported(mime: String) -> Bool {
        BackupMediaLimits.supportedMimes.contains(
            mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        )
    }

    /// MIME → 落盘扩展名（只认支持的那三种）。
    public static func fileExtension(forMime mime: String) -> String? {
        switch mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "image/jpeg": return "jpg"
        case "image/png": return "png"
        case "image/webp": return "webp"
        default: return nil
        }
    }

    /// 扩展名 → MIME。猜不到返回 nil（调用方跳过这张图）。
    public static func mime(forFileExtension fileExtension: String) -> String? {
        switch fileExtension.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "webp": return "image/webp"
        default: return nil
        }
    }

    /// 按文件名后缀猜 MIME；没有后缀 / 不认识的后缀返回 nil。
    public static func mime(forFileName fileName: String) -> String? {
        let name = fileName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let dot = name.lastIndex(of: "."), dot < name.index(before: name.endIndex) else { return nil }
        return mime(forFileExtension: String(name[name.index(after: dot)...]))
    }

    // MARK: 文件名

    /// 备份里的 `fileName` 是不是一个**裸的、安全的**文件名。
    ///
    /// 校验的是「整条值」而不是它的最后一段：`../../myfriend.db` 的最后一段看起来无害，
    /// 但整条值绝不能当文件名用。空白、路径分隔符、`..`、超长一律拒绝。
    public static func isSafeFileName(_ raw: String?) -> Bool {
        guard let name = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else {
            return false
        }
        guard name.utf8.count <= BackupMediaLimits.maxFileNameBytes else { return false }
        if name.contains("/") || name.contains("\\") { return false }
        if name.contains("..") { return false }
        return true
    }

    /// 导出用：把本机 `file:` 路径收成裸文件名（聊天图片用这个；头像用 `BackupAvatarPath.portable`）。
    ///
    /// 规则与 Android `BackupAvatarPath.portable` 一致：只认本地 `file:`、只取最后一段、
    /// 必须是安全名字。**不要求** `chatImage-` 前缀：老数据里可能有别的命名，
    /// 而能不能读字节由「文件真的在附件目录里」决定，不是由名字决定。
    public static func portableFileName(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        guard trimmed.lowercased().hasPrefix("file:") else { return nil }
        guard let name = BackupAvatarPath.fileName(trimmed) else { return nil }
        return isSafeFileName(name) ? name : nil
    }

    /// 导入时的落盘文件名：`avatar-<uuid>.<ext>` / `chatImage-<uuid>.<ext>`。
    ///
    /// 永远不复用备份里的 `fileName`：备份是外部输入，不能让它决定往哪个路径写字节。
    public static func newFileName(kind: BackupMediaKind, mime: String, uuid: UUID = UUID()) -> String? {
        guard let fileExtension = fileExtension(forMime: mime) else { return nil }
        return "\(kind.fileNamePrefix)\(uuid.uuidString).\(fileExtension)"
    }

    // MARK: base64

    /// 标准 RFC 4648 base64、带 padding、不换行（`Data.base64EncodedString()` 正是这个）。
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
    }

    /// 解码。与 Android `BackupBase64.decode` 同语义：容忍手写排版留下的空白，
    /// 但非法字符、错误的 padding 一律返回 nil（不抛、不崩）。
    public static func decode(_ text: String) -> Data? {
        if text.isEmpty { return Data() }
        var cleaned = ""
        cleaned.reserveCapacity(text.utf8.count)
        for character in text {
            switch character {
            case " ", "\n", "\r", "\t": continue
            default: cleaned.append(character)
            }
        }
        guard cleaned.utf8.count % 4 == 0 else { return nil }
        return Data(base64Encoded: cleaned, options: [])
    }

    // MARK: 写入

    /// 把字节写进受管目录，返回新文件的 URL；任何一步不合规都返回 nil（不抛）。
    ///
    /// 写之前必须过目录归属守卫（`AttachmentLocation.isWithin`）：备份数据只能决定
    /// **文件名**，不能决定路径。
    @discardableResult
    public static func write(
        _ data: Data,
        as fileName: String,
        in directory: URL,
        fileManager: FileManager = .default
    ) -> URL? {
        guard isSafeFileName(fileName) else { return nil }
        let standardizedDirectory = directory.standardizedFileURL
        let target = standardizedDirectory.appendingPathComponent(fileName, isDirectory: false).standardizedFileURL
        guard AttachmentLocation.isWithin(standardizedDirectory, target) else { return nil }
        guard !fileManager.fileExists(atPath: target.path) else { return nil }
        do {
            try fileManager.createDirectory(at: standardizedDirectory, withIntermediateDirectories: true)
            try data.write(to: target, options: .atomic)
            return target
        } catch {
            return nil
        }
    }
}

/// 导出时的一张候选图片：已经被解析到真实的、属于 App 的本地文件。
///
/// `fileName` 是**源文件名**（裸名），它同时会写进 `personas.avatarUri` /
/// `messages.attachmentsJson`，也是导入时「引用 ↔ media」的关联键。
public struct BackupMediaCandidate: Equatable, Sendable {
    public var kind: BackupMediaKind
    public var personaId: String?
    public var fileName: String
    public var fileURL: URL
    public var mime: String

    public init(kind: BackupMediaKind, personaId: String?, fileName: String, fileURL: URL, mime: String) {
        self.kind = kind
        self.personaId = personaId
        self.fileName = fileName
        self.fileURL = fileURL
        self.mime = mime
    }
}

/// 导出时的媒体打包：**只读**文件系统，不写任何东西（单测可直接喂临时目录）。
///
/// 三条规则（与 Android `BackupMediaPacker` 一致）：
/// - 同一张文件（同一路径 + 同一 kind）只装一次；
/// - 单张 > 单张预算：跳过并计入「超限」；
/// - 原始字节累计会超总量：**停止收集**，剩下的（含当前这张）全部计入「超限」——
///   不写半张图，也不写一份自己都导不回来的备份。
public enum BackupMediaPacker {

    public struct Result: Equatable, Sendable {
        public var media: [BackupMedia]
        /// 因为上限没有随备份带走的张数（导出后要如实告诉用户）。
        public var skippedByLimit: Int
        /// 这次真的装进 `media` 的聊天图片源文件名：导出侧重写 `attachmentsJson` 只认它们。
        public var chatImageFileNames: Set<String>

        public init(media: [BackupMedia] = [], skippedByLimit: Int = 0, chatImageFileNames: Set<String> = []) {
            self.media = media
            self.skippedByLimit = skippedByLimit
            self.chatImageFileNames = chatImageFileNames
        }
    }

    public static func pack(
        candidates: [BackupMediaCandidate],
        totalBudget: Int = BackupMediaLimits.maxTotalBytes,
        perFileBudget: Int = BackupMediaLimits.maxFileBytes,
        fileManager: FileManager = .default
    ) -> Result {
        // 去重：同一张文件（规范化路径 + kind）只装一次 —— 人格头像与预设头像
        // 完全可能指向同一个文件。
        var seen = Set<String>()
        var unique: [BackupMediaCandidate] = []
        for candidate in candidates {
            let canonical = candidate.fileURL.resolvingSymlinksInPath().standardizedFileURL.path
            let key = "\(candidate.kind.rawValue)|\(canonical)"
            if seen.insert(key).inserted { unique.append(candidate) }
        }
        guard !unique.isEmpty else { return Result() }

        var media: [BackupMedia] = []
        var chatImageFileNames = Set<String>()
        var total = 0
        var skipped = 0

        for (position, candidate) in unique.enumerated() {
            guard fileManager.fileExists(atPath: candidate.fileURL.path) else { continue }
            guard BackupMediaTransfer.isSafeFileName(candidate.fileName) else { continue }
            guard BackupMediaTransfer.isSupported(mime: candidate.mime) else { continue }
            let size = (try? candidate.fileURL.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
            guard size > 0 else { continue }
            if size > perFileBudget {
                skipped += 1
                continue
            }
            if total + size > totalBudget {
                // 停止收集：剩下的都没有随备份带走（数量按候选清单算，与 Android 同一口径）。
                skipped += unique.count - position
                break
            }
            guard let data = try? Data(contentsOf: candidate.fileURL), !data.isEmpty else { continue }
            total += data.count
            media.append(
                BackupMedia(
                    id: UUID().uuidString,
                    kind: candidate.kind.rawValue,
                    personaId: candidate.personaId,
                    fileName: candidate.fileName,
                    mime: candidate.mime,
                    bytes: data.count,
                    data: BackupMediaTransfer.encode(data)
                )
            )
            if candidate.kind == .chatImage { chatImageFileNames.insert(candidate.fileName) }
        }
        return Result(media: media, skippedByLimit: skipped, chatImageFileNames: chatImageFileNames)
    }
}

/// 导入时落盘的一张图片：新文件 + 它在备份里的源文件名 + 新的 `file://` 地址。
public struct RestoredBackupMedia: Equatable, Sendable {
    public var kind: BackupMediaKind
    public var fileName: String
    public var fileURL: URL
    public var uri: String
    public var mime: String

    public init(kind: BackupMediaKind, fileName: String, fileURL: URL, uri: String, mime: String) {
        self.kind = kind
        self.fileName = fileName
        self.fileURL = fileURL
        self.uri = uri
        self.mime = mime
    }
}

/// 导入媒体阶段的结果：关联表 + 两种计数 + 这一次真的写下去的文件（失败时用来回滚）。
public struct BackupMediaRestoreResult: Equatable, Sendable {
    public var avatarsByFileName: [String: RestoredBackupMedia] = [:]
    /// 按 `personaId` 再记一份：备份里的人格 `avatarUri` 缺失时用它兜底
    /// （与 Android `BackupMediaRestoreResult.avatarsByPersonaId` 同一个用途）。
    public var avatarsByPersonaId: [String: RestoredBackupMedia] = [:]
    public var chatImagesByFileName: [String: RestoredBackupMedia] = [:]
    public var userAvatar: RestoredBackupMedia?
    /// 坏 base64 / 不支持的 mime / 文件名不安全 / 字节数对不上 / 写入失败 / 重复条目。
    public var invalidCount: Int = 0
    /// 声明的字节数、解出来的字节数、或累计总量超出上限。
    public var overLimitCount: Int = 0
    public var writtenFiles: [URL] = []

    public init() {}
}

/// 导入时的媒体落盘：把 `media[].data` 解码、写进本机附件目录、返回关联表。
///
/// 安全边界（与 Android `BackupMediaRestorer` 一致）：
/// - 备份里的 `fileName` **只做关联键**，不参与拼路径；落盘名一律是新 UUID；
/// - 目标路径必须过 `AttachmentLocation.isWithin` 目录归属守卫；
/// - 坏数据只跳过并计数，绝不抛异常（导入必须成功，哪怕每一张图都是坏的）。
public enum BackupMediaRestorer {

    public static func restore(
        entries: [BackupMedia],
        directory: URL,
        totalBudget: Int = BackupMediaLimits.maxTotalBytes,
        perFileBudget: Int = BackupMediaLimits.maxFileBytes,
        fileManager: FileManager = .default,
        newUUID: () -> UUID = { UUID() }
    ) -> BackupMediaRestoreResult {
        var result = BackupMediaRestoreResult()
        guard !entries.isEmpty else { return result }

        var writtenKeys = Set<String>()
        var total = 0

        for entry in entries {
            let kindRaw = entry.kind.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let kind = BackupMediaKind(rawValue: kindRaw) else {
                result.invalidCount += 1
                continue
            }
            let fileName = entry.fileName.trimmingCharacters(in: .whitespacesAndNewlines)
            guard BackupMediaTransfer.isSafeFileName(fileName) else {
                result.invalidCount += 1
                continue
            }
            let mime = entry.mime.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard BackupMediaTransfer.isSupported(mime: mime),
                  BackupMediaTransfer.fileExtension(forMime: mime) != nil else {
                result.invalidCount += 1
                continue
            }
            // 重复的 (kind, fileName)：只认第一条，其余跳过（同名不同内容是坏数据）。
            let key = "\(kind.rawValue)|\(fileName)"
            guard !writtenKeys.contains(key) else {
                result.invalidCount += 1
                continue
            }
            // 声明的字节数先过一道：明显超限的连 base64 都不用解。
            if entry.bytes < 0 || entry.bytes > perFileBudget
                || entry.data.utf8.count > BackupMediaLimits.maxBase64Chars(perFileBytes: perFileBudget) {
                result.overLimitCount += 1
                continue
            }
            guard let data = BackupMediaTransfer.decode(entry.data),
                  !data.isEmpty,
                  data.count == entry.bytes else {
                result.invalidCount += 1
                continue
            }
            if data.count > perFileBudget || total + data.count > totalBudget {
                result.overLimitCount += 1
                continue
            }
            guard let newName = BackupMediaTransfer.newFileName(kind: kind, mime: mime, uuid: newUUID()),
                  let target = BackupMediaTransfer.write(data, as: newName, in: directory, fileManager: fileManager)
            else {
                result.invalidCount += 1
                continue
            }
            total += data.count
            writtenKeys.insert(key)
            result.writtenFiles.append(target)
            let restored = RestoredBackupMedia(
                kind: kind, fileName: fileName, fileURL: target,
                uri: target.absoluteString, mime: mime
            )
            switch kind {
            case .personaAvatar:
                result.avatarsByFileName[fileName] = restored
                let personaId = entry.personaId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if !personaId.isEmpty { result.avatarsByPersonaId[personaId] = restored }
            case .userAvatar: result.userAvatar = restored
            case .chatImage: result.chatImagesByFileName[fileName] = restored
            }
        }
        return result
    }

    /// `messages.attachmentsJson` 在 v3 里的两条转换。
    ///
    /// 导出：把每条的 `uri` 从设备路径换成**裸文件名**（换机后路径必然失效；裸名用来在
    /// `media` 里找到同一张图）。文件不存在 / 名字不安全 / 格式不支持的附件直接丢掉。
    public static func portableAttachments(
        _ raw: String?,
        allowedNames: Set<String>
    ) -> String? {
        let attachments = AttachmentsCodec.decode(raw)
        guard !attachments.isEmpty, !allowedNames.isEmpty else { return nil }
        var portable: [MessageAttachment] = []
        for attachment in attachments {
            guard let name = BackupMediaTransfer.portableFileName(attachment.uri),
                  allowedNames.contains(name) else { continue }
            // mime 可能是历史遗留的怪值：按文件名再认一次，认不出就不带（导入端也解不了）。
            guard BackupMediaTransfer.isSupported(mime: attachment.mime)
                || BackupMediaTransfer.mime(forFileName: name) != nil else { continue }
            portable.append(
                MessageAttachment(uri: name, mime: attachment.mime,
                                  width: attachment.width, height: attachment.height)
            )
        }
        return AttachmentsCodec.encode(portable)
    }

    /// 导入：按 `media` 里的 CHAT_IMAGE 重建本机路径；找不到的附件丢掉（v1/v2 的老行为）。
    ///
    /// - Returns: 新的 `attachmentsJson`；null 表示这条消息没有（可用的）附件了。
    public static func restoredAttachments(
        _ raw: String?,
        chatImagesByFileName: [String: RestoredBackupMedia]
    ) -> String? {
        let attachments = AttachmentsCodec.decode(raw)
        guard !attachments.isEmpty else { return nil }
        var rebuilt: [MessageAttachment] = []
        for attachment in attachments {
            // v3 的附件 uri 是裸文件名；v1/v2 里的设备路径一律不认（换机后本来也指不到文件）。
            let bare = attachment.uri.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !bare.isEmpty, !bare.contains("/"), !bare.contains("\\"), !bare.contains("..") else {
                continue
            }
            guard let media = chatImagesByFileName[bare] else { continue }
            rebuilt.append(
                MessageAttachment(uri: media.uri, mime: media.mime,
                                  width: attachment.width, height: attachment.height)
            )
        }
        return AttachmentsCodec.encode(rebuilt)
    }
}

// MARK: - 沙盒存储

/// 附件文件本体。
///
/// 目录约定：`Application Support/attachments/`（与数据库同级的 App 私有目录）。
/// 文件名带随机后缀：同一张图被选两次不会互相覆盖，删一条消息也不会误删另一条的图。
public final class MediaStorage {

    public enum StorageError: LocalizedError, Equatable {
        case emptyImage
        case tooLarge
        case unreadable
        case writeFailed

        public var errorDescription: String? {
            switch self {
            case .emptyImage: return String(localized: "这张图片是空的")
            case .tooLarge: return String(localized: "图片超过 20 MB，请换一张小一点的")
            case .unreadable: return String(localized: "读不出这张图片，换一张试试")
            case .writeFailed: return String(localized: "图片没能存到本机")
            }
        }
    }

    /// 附件目录。第一次访问时创建。
    public let directory: URL

    private let fileManager: FileManager

    public init(directory: URL, fileManager: FileManager = .default) {
        self.directory = directory
        self.fileManager = fileManager
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        applyBackupExclusion()
    }

    /// 把附件目录从 iCloud/iTunes 整机备份里排除。
    ///
    /// 为什么要有这个方法而不是在 `init` 里写一遍就算了：`deleteAll()` 会把目录删掉重建，
    /// 新建出来的目录没有这个标记（0.10.2 审计 P1-8），于是「清空过一次数据之后」，
    /// 几百 MB 的聊天图与头像开始进整机备份 —— 而用户完全看不出来。
    /// 所以 `init` 与 `deleteAll` 都走这里。
    func applyBackupExclusion() {
        // 图片二进制**不进备份**（与 Android 的约定一致）：
        // 应用自己的备份 JSON 本来就不带图；这里再把附件目录从 iCloud 整机备份里排除，
        // 免得「换机恢复」时把几百 MB 的聊天图一起搬过去（那些路径到了新机器也指不到东西）。
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var target = directory
        try? target.setResourceValues(values)
    }

    /// App 里的默认实例：`Application Support/attachments`。
    public static let shared: MediaStorage = {
        let fallback = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-attachments", isDirectory: true)
        let base = (try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? fallback
        return MediaStorage(directory: base.appendingPathComponent("attachments", isDirectory: true))
    }()

    // MARK: 写

    /// 把一张相册图片（原始 Data）压好、落盘，返回可直接写进 `attachmentsJson` 的附件。
    public func saveImage(data: Data, kind: MediaKind) throws -> MessageAttachment {
        guard !data.isEmpty else { throw StorageError.emptyImage }
        guard !ImageEncodingPolicy.isTooLargeInput(data.count) else { throw StorageError.tooLarge }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            throw StorageError.unreadable
        }
        return try saveImage(source: source, kind: kind)
    }

    /// 同 `saveImage(data:)`，但直接从文件读：相册选图拿到的是临时 URL 时走这条。
    public func saveImage(at url: URL, kind: MediaKind) throws -> MessageAttachment {
        let attributes = try? fileManager.attributesOfItem(atPath: url.path)
        let size = (attributes?[.size] as? NSNumber)?.intValue ?? 0
        guard size > 0 else { throw StorageError.emptyImage }
        guard !ImageEncodingPolicy.isTooLargeInput(size) else { throw StorageError.tooLarge }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
            throw StorageError.unreadable
        }
        return try saveImage(source: source, kind: kind)
    }

    private func saveImage(source: CGImageSource, kind: MediaKind) throws -> MessageAttachment {
        let limit = ImageEncodingPolicy.longEdge(for: kind)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: limit
        ]
        guard let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw StorageError.unreadable
        }

        let target = directory.appendingPathComponent(
            "\(kind.rawValue)-\(UUID().uuidString).jpg", isDirectory: false
        )
        var lastData: Data?
        for quality in ImageEncodingPolicy.qualityLadder(from: ImageEncodingPolicy.jpegQuality(for: kind)) {
            guard let encoded = encodeJPEG(decoded, quality: quality) else { continue }
            lastData = encoded
            if !ImageEncodingPolicy.exceedsByteLimit(encoded.count, kind: kind) {
                break
            }
        }
        guard let data = lastData else { throw StorageError.unreadable }
        do {
            try data.write(to: target, options: .atomic)
        } catch {
            throw StorageError.writeFailed
        }
        return MessageAttachment(
            uri: target.absoluteString,
            mime: MessageAttachment.defaultMime,
            width: decoded.width,
            height: decoded.height
        )
    }

    private func encodeJPEG(_ image: CGImage, quality: Double) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            data as CFMutableData, "public.jpeg" as CFString, 1, nil
        ) else { return nil }
        let properties: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: quality
        ]
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }

    // MARK: 读

    /// `uri` → 文件 URL。只认本机文件；容器 UUID 变了会用同名文件在当前目录找回来
    /// （见 `AttachmentLocation`）。外部相册地址一律当文件不见了。
    public func fileURL(for uriString: String) -> URL? {
        AttachmentLocation.resolve(uriString, directory: directory) { path in
            fileManager.fileExists(atPath: path)
        }
    }

    public func fileExists(_ attachment: MessageAttachment) -> Bool {
        guard let url = fileURL(for: attachment.uri) else { return false }
        return fileManager.fileExists(atPath: url.path)
    }

    /// 转成 OpenAI 兼容接口能直接用的 data URL；文件不在了返回 nil（界面显示「图片已不在本机」）。
    ///
    /// 只在「最近两条带图消息」上调用（见 `AttachmentsCodec.maxImageMessagesInContext`）：
    /// base64 会让请求体膨胀 4/3，历史里的图不该全带上。
    public func dataURL(for attachment: MessageAttachment) -> String? {
        guard attachment.isImage, let url = fileURL(for: attachment.uri) else { return nil }
        guard let data = try? Data(contentsOf: url), !data.isEmpty else { return nil }
        return "data:\(attachment.mime);base64,\(data.base64EncodedString())"
    }

    /// 按显示尺寸解码缩略图（带缓存）。文件不存在时返回 nil。
    public func thumbnail(for attachment: MessageAttachment, maxPixelSize: Int) -> CGImage? {
        guard let url = fileURL(for: attachment.uri) else { return nil }
        let key = "\(attachment.uri)#\(maxPixelSize)"
        if let cached = ImageThumbnailCache.shared.image(forKey: key) { return cached }
        guard fileManager.fileExists(atPath: url.path) else { return nil }
        guard let image = ImageThumbnailDecoder.thumbnail(url: url, maxPixelSize: maxPixelSize) else {
            return nil
        }
        ImageThumbnailCache.shared.store(image, forKey: key)
        return image
    }

    // MARK: 删

    public func delete(_ attachment: MessageAttachment) {
        // 0.11.1 安全审计 P2-3：只删**自己目录里**的文件。以前这里对 `fileURL(for:)` 的结果
        // 直接 `removeItem`，而解析又会接受 `../../myfriend.db` —— 删一条消息就能删到沙盒里的
        // 任意文件。`deleteFileIfOwned` 与头像走的是同一个守卫（与 Android `MediaOwnership` 等价）。
        deleteFileIfOwned(attachment.uri)
    }

    public func delete(_ attachments: [MessageAttachment]) {
        for attachment in attachments { delete(attachment) }
    }

    /// 这条 `uri` 现在还能不能指向一个真实文件（备份导入时判断头像是否失效）。
    ///
    /// 与 `resolve` 同一套规则：容器路径变了但同名文件还在附件目录里，也算「还在」。
    public func exists(uriString: String?) -> Bool {
        guard let uriString, !uriString.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }
        guard let url = AttachmentLocation.resolve(uriString, directory: directory) else { return false }
        return fileManager.fileExists(atPath: url.path)
    }

    /// 只删「自己目录里」的文件。
    ///
    /// 为什么要有这条：`avatarUri` 可能来自备份/旧版本（指向别的目录），
    /// 换头像时顺手 `removeItem` 一个不属于我们的路径是危险的。
    public func deleteFileIfOwned(_ uriString: String?) {
        guard let uriString, let url = fileURL(for: uriString) else { return }
        // 目录归属（含符号链接）判定收在 `AttachmentLocation.isWithin`，与 Android 的
        // `MediaOwnership.isWithin` 语义一致：子目录才算、同前缀不算、软链解开后再比。
        guard AttachmentLocation.isWithin(directory, url) else { return }
        try? fileManager.removeItem(at: url)
    }

    /// 删掉一条消息里的所有附件（删消息、清空上下文、清空数据时用）。
    public func deleteAttachments(in json: String?) {
        delete(AttachmentsCodec.decode(json))
    }

    /// 删掉全部附件（聊天图 + 头像）。重建目录后**立刻**恢复「不进备份」标记（P1-8）。
    public func deleteAll() {
        try? fileManager.removeItem(at: directory)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        applyBackupExclusion()
    }

    /// 只删聊天图，人格头像留着（「清空全部聊天与记忆」用：人还在，头像不该消失）。
    ///
    /// 文件名的前缀就是 `MediaKind.rawValue`（`chatImage-<uuid>.jpg` / `avatar-<uuid>.jpg`），
    /// 所以这里按前缀删，而不是遍历数据库里的引用 —— 顺便把历史遗留的孤儿图也清掉。
    ///
    /// - Returns: 删掉了几个文件。
    @discardableResult
    public func deleteAllChatImages() -> Int {
        guard let items = try? fileManager.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: nil
        ) else { return 0 }
        var removed = 0
        for url in items where url.lastPathComponent.hasPrefix(MediaKind.chatImage.rawValue + "-") {
            if (try? fileManager.removeItem(at: url)) != nil { removed += 1 }
        }
        return removed
    }

    /// 附件一共占了多少字节（设置页/报告里给个数字用）。
    public func totalBytesOnDisk() -> Int {
        guard let items = try? fileManager.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.fileSizeKey]
        ) else { return 0 }
        return items.reduce(0) { partial, url in
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return partial + size
        }
    }
}
