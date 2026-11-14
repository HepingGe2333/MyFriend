import PhotosUI
import SwiftUI

/// 相册选图 → 压缩 → 沙盒。
///
/// iOS 16 的 `PhotosPicker` 就是 `PHPickerViewController` 的 SwiftUI 外壳：
/// 它跑在系统进程里，**不需要相册权限**，也不会把整个相册的缩略图放进我们的内存。
/// 选完只把用户挑的那几张以 `Data` 交回来，我们在这里压缩、落盘，再交给界面/数据库。
///
/// 为什么压缩和写盘都在 `Task.detached` 里：解码一张 4000px 的图 + JPEG 编码是几十毫秒的
/// 活，放主线程会让「选完图 → 出现预览」这一下明显卡住。
enum ImageImport {

    enum Outcome {
        case success(MessageAttachment)
        case failure(String)
    }

    /// 单张：聊天图（长边 ≤ 1600、≤ 800KB）或头像（长边 ≤ 512、≤ 400KB）。
    static func importOne(_ item: PhotosPickerItem, kind: MediaKind) async -> Outcome {
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

    private static func message(for error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return L("图片没能存到本机")
    }
}
