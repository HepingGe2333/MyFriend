import PhotosUI
import SwiftUI

/// 相册选图 → 压缩 → 沙盒。
///
/// iOS 16 的 `PhotosPicker` 就是 `PHPickerViewController` 的 SwiftUI 外壳：
/// 它跑在系统进程里，**不需要相册权限**，也不会把整个相册的缩略图放进我们的内存。
/// 选完只把用户挑的那几张交回来，我们在这里压缩、落盘，再交给界面/数据库。
///
/// 0.11.0 审计 P2-14：优先要**文件 URL**（`loadTransferable(type: URL.self)`），
/// 先用 `FileManager` 看大小再决定要不要碰像素 —— 一张 100MB 的全景/RAW 不该为了
/// 「判断它太大」被完整读进内存（原来走 `Data.self`，20MB 的闸来得太晚）。
///
/// 为什么压缩和写盘都在 `Task.detached` 里：解码一张 4000px 的图 + JPEG 编码是几十毫秒的
/// 活，放主线程会让「选完图 → 出现预览」这一下明显卡住。
enum ImageImport {

    enum Outcome {
        case success(MessageAttachment)
        case failure(String)
    }

    /// 单张：聊天图（长边 ≤ 1600、≤ 800KB）或头像（长边 ≤ 512、≤ 400KB）。
    ///
    /// 先试文件 URL（不读像素）；极少数资产拿不到 URL 时才退回 `Data` 路径。
    static func importOne(_ item: PhotosPickerItem, kind: MediaKind) async -> Outcome {
        if let url = try? await item.loadTransferable(type: URL.self), url.isFileURL {
            do {
                let attachment = try await save(fileURL: url, kind: kind)
                return .success(attachment)
            } catch {
                return .failure(message(for: error))
            }
        }
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            return .failure(L("读不出这张图片，换一张试试"))
        }
        do {
            let attachment = try await save(data: data, kind: kind)
            return .success(attachment)
        } catch {
            return .failure(message(for: error))
        }
    }

    /// 多张：一张失败不影响其它张；失败的会合成一句中文提醒给界面。
    static func importMany(
        _ items: [PhotosPickerItem],
        kind: MediaKind,
        limit: Int
    ) async -> (attachments: [MessageAttachment], failure: String?) {
        var attachments: [MessageAttachment] = []
        var failure: String?
        for item in items.prefix(max(limit, 0)) {
            switch await importOne(item, kind: kind) {
            case .success(let attachment):
                attachments.append(attachment)
            case .failure(let text):
                failure = text
            }
        }
        return (attachments, failure)
    }

    private static func save(data: Data, kind: MediaKind) async throws -> MessageAttachment {
        let storage = MediaStorage.shared
        return try await Task.detached(priority: .userInitiated) {
            try storage.saveImage(data: data, kind: kind)
        }.value
    }

    /// 文件路径：大小判断与缩略图解码都在 `MediaStorage.saveImage(at:)` 里
    /// （先用 `attributesOfItem` 看大小，再用 `CGImageSource` 按目标尺寸解码）。
    private static func save(fileURL: URL, kind: MediaKind) async throws -> MessageAttachment {
        let storage = MediaStorage.shared
        return try await Task.detached(priority: .userInitiated) {
            try storage.saveImage(at: fileURL, kind: kind)
        }.value
    }

    private static func message(for error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return L("图片没能存到本机")
    }
}
