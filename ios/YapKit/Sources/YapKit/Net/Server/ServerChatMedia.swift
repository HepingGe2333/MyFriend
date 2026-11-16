import Foundation

// ---------------------------------------------------------------------------
// 服务端模式的聊天图片（M5）：先压缩，再上传，再发送。
//
// 顺序是有讲究的：
// 1. **压缩**：选图路径（`ImageImport` → `MediaStorage.saveImage`）已经把图压成
//    JPEG（长边 ≤1600、≤800KB）并落盘；这里优先直接复用那个**压缩产物**，不再重编码
//    （对已经是 JPEG 且尺寸/体积都合格的图，重编码只会变大、变糊 —— 与备份导入
//    `BackupMediaRestorer` 的 `keepOriginal` 同一条判据）。只有文件不是 JPEG / 超限时
//    才真的走 `ImageCompressor`。
// 2. **上传**：`POST /v1/media`（原始字节）拿回 `/v1/media/<id>`。
// 3. **发送**：`attachments` 只发 mediaId；消息落库时 `attachmentsJson` 里存
//    `/v1/media/<id>`（服务端下发的也是这个形状，界面据此走 `ServerMediaCache` 渲染）。
//
// 为什么上传失败要 `rejected` 而不是排队：一条「带图的待发消息」如果图片还没上传，
// 补发时只能发文字或失败；两种都不是用户想要的。所以上传必须在**入队之前**完成，
// 失败就把草稿与图片还给界面（`ChatService.SendOutcome.rejected` 的既有语义）。
// ---------------------------------------------------------------------------

/// 一张待上传图片的准备结果（压缩后的字节 + 尺寸）。
public struct ServerMediaPreparation: Equatable, Sendable {
    public var bytes: Data
    public var mime: String
    public var width: Int
    public var height: Int

    public init(bytes: Data, mime: String = "image/jpeg", width: Int = 0, height: Int = 0) {
        self.bytes = bytes
        self.mime = mime
        self.width = width
        self.height = height
    }
}

/// 「把本地附件变成可上传字节」的抽象：生产用 `LocalChatMediaPreparer`；
/// 测试注入假实现，断言「准备（压缩）→ 上传 → 发送」的顺序。
public protocol ServerChatMediaPreparing {
    func prepare(attachment: MessageAttachment, kind: MediaKind) -> ServerMediaPreparation?
}

/// 从本机附件目录读压缩产物（必要时重压）。
public struct LocalChatMediaPreparer: ServerChatMediaPreparing {

    private let storage: MediaStorage

    public init(storage: MediaStorage = .shared) {
        self.storage = storage
    }

    public func prepare(attachment: MessageAttachment, kind: MediaKind) -> ServerMediaPreparation? {
        // 已经是服务端媒体（例如从服务端同步回来的消息再被转发）：不用再上传。
        if ServerMediaReference.mediaId(from: attachment.uri) != nil {
            return ServerMediaPreparation(bytes: Data(), mime: attachment.mime)
        }
        guard let url = storage.fileURL(for: attachment.uri) else { return nil }
        guard let raw = try? Data(contentsOf: url), !raw.isEmpty else { return nil }
        guard !ImageEncodingPolicy.isTooLargeInput(raw.count) else { return nil }

        let maxEdge = ImageEncodingPolicy.longEdge(for: kind)
        let byteLimit = ImageEncodingPolicy.byteLimit(for: kind)
        let pixelSize = ImageThumbnailDecoder.pixelSize(data: raw)
        let fitsLongEdge = pixelSize.map { Swift.max($0.width, $0.height) <= maxEdge } ?? false
        let alreadyCompressed = ImageThumbnailDecoder.isJPEG(data: raw)
            && fitsLongEdge
            && raw.count <= byteLimit
        let payload: Data
        if alreadyCompressed {
            payload = raw
        } else if let compressed = ImageCompressor.compressedJPEG(data: raw, kind: kind) {
            payload = compressed
        } else {
            return nil
        }
        let finalSize = ImageThumbnailDecoder.pixelSize(data: payload)
            ?? pixelSize
        return ServerMediaPreparation(
            bytes: payload,
            mime: "image/jpeg",
            width: finalSize?.width ?? attachment.width,
            height: finalSize?.height ?? attachment.height
        )
    }
}
