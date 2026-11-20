import Foundation

// ---------------------------------------------------------------------------
// 用户头像上传（0.12.1，`USER_AVATAR`）。
//
// 链路：设置里选图 → 现有 `ImageImport` 落一张**已压缩**的本地头像（长边 ≤512 /
// JPEG 0.82 / ≤400KB）→ 这里再检查一遍（必要时重压）→ `POST /v1/media?kind=USER_AVATAR`
// → 把 `/v1/media/<id>` 写进设置（服务端设置白名单 `myAvatarUri`）。
//
// 三个刻意的取舍：
// 1. **复用压缩产物**：选图路径写出来的本来就是合格 JPEG，重编码只会更糊、更大
//    （与聊天图 `LocalChatMediaPreparer` 同一条判据）；只有不是 JPEG / 超限时才重压。
// 2. **上传成功以 `POST /v1/media` 的 2xx + `url` 为准**：设置接口那边即使老服务端
//    不支持 `myAvatarUri` 也不会报错（静默忽略），不能拿它当「传上去了」的证据。
// 3. **已经有服务端引用就不重复上传**：从服务端拉回来的头像是 `/v1/media/<id>`，
//    再上传等于白花一次流量。
//
// 本地模式完全不经过这里（设置页只在 `serverModeActive` 时调用）。
// ---------------------------------------------------------------------------

/// 一次用户头像上传的结果。
public struct ServerUserAvatarUploadResult: Equatable, Sendable {
    /// 服务端媒体 id。
    public var mediaId: String
    /// 写进设置的相对路径（`/v1/media/<id>`）。
    public var uri: String
    /// 真正上传的字节数（已经是服务端媒体、没有重新上传时为 0）。
    public var bytes: Int
    public var mime: String

    public init(
        mediaId: String,
        uri: String,
        bytes: Int,
        mime: String = "image/jpeg"
    ) {
        self.mediaId = mediaId
        self.uri = uri
        self.bytes = bytes
        self.mime = mime
    }
}

public enum ServerUserAvatarError: Error, LocalizedError, Equatable {
    /// 本地文件不存在 / 读不出来（已被清理、或路径不是我们写的）。
    case localFileMissing
    /// 压缩失败（不是图片，或解码不了）。
    case compressFailed
    /// 压缩后仍然是空字节。
    case emptyPayload

    public var errorDescription: String? {
        switch self {
        case .localFileMissing:
            return "这张头像已经不在这台设备上了，请重新选一张"
        case .compressFailed:
            return "这张图片读不出来，换一张试试"
        case .emptyPayload:
            return "这张图片是空的，换一张试试"
        }
    }
}

/// 用户头像上传器：准备（压缩）→ 上传 → 返回可写进设置的 `/v1/media/<id>`。
public final class ServerUserAvatarUploader {

    private let uploader: any ServerMediaUploading
    private let preparer: any ServerChatMediaPreparing

    /// - Parameters:
    ///   - uploader: 生产是 `ServerAPIClient`；测试注入假实现断言「先压缩再上传」。
    ///   - preparer: 默认复用选图路径的压缩产物。
    public init(
        uploader: any ServerMediaUploading,
        preparer: (any ServerChatMediaPreparing)? = nil
    ) {
        self.uploader = uploader
        self.preparer = preparer ?? LocalChatMediaPreparer()
    }

    /// 上传一张「我的头像」。
    ///
    /// - Parameter localURI: 本机附件引用（`ImageImport` 产出的 `file://…` 或裸文件名）。
    ///   已经是 `/v1/media/<id>` 时直接返回（不重复上传）。
    /// - Throws: `ServerUserAvatarError`（本地准备失败）或 `ServerAPIError`（上传失败）。
    public func upload(localURI: String) async throws -> ServerUserAvatarUploadResult {
        // 已经是服务端媒体：只做形状归一化，不再上传。
        if let mediaId = ServerMediaReference.mediaId(from: localURI) {
            return ServerUserAvatarUploadResult(
                mediaId: mediaId,
                uri: ServerMediaReference.uri(id: mediaId),
                bytes: 0
            )
        }
        let attachment = MessageAttachment.image(uri: localURI)
        guard let prepared = preparer.prepare(attachment: attachment, kind: .avatar) else {
            // 准备器返回 nil 有两种原因（文件不在 / 解码不了）；调用方看到的是同一句话，
            // 因为对用户来说「这张图用不了」就够了。
            throw ServerUserAvatarError.localFileMissing
        }
        guard !prepared.bytes.isEmpty else {
            throw ServerUserAvatarError.emptyPayload
        }
        let media = try await uploader.uploadMedia(
            bytes: prepared.bytes,
            mime: prepared.mime,
            kind: ServerMediaKind.userAvatar.rawValue,
            personaId: nil
        )
        return ServerUserAvatarUploadResult(
            mediaId: media.id,
            uri: ServerMediaReference.uri(id: media.id),
            bytes: prepared.bytes.count,
            mime: prepared.mime
        )
    }
}
