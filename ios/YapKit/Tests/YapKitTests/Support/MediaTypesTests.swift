import Foundation
import XCTest
@testable import YapKit

/// 0.15：媒体类型（`IMAGE` / `STICKER` / `AUDIO` / `VIDEO` / `AVATAR`）与附件的
/// `kind` + `mime` 校验。
///
/// 钉住四件事：
/// 1. 五种类型的名字与各自接受的 mime 只有一张表（附件、备份、服务端共用）；
/// 2. 附件显式 `kind` 必须是四种之一（`AVATAR` 只出现在备份 `media[]` 里）；
/// 3. `mime` 必须与 kind 同族，未知 kind / 不匹配 → **拒绝**（整段按坏数据处理，不猜成图片）；
/// 4. 0.13 及更早的「没写 kind」仍然能读（按 mime 推断），且重新编码不多出字段。
final class MediaTypesTests: XCTestCase {

    // MARK: - 类型表

    func testParseIsCaseInsensitiveAndTrims() {
        XCTAssertEqual(MediaType.parse(" image "), .image)
        XCTAssertEqual(MediaType.parse("Sticker"), .sticker)
        XCTAssertEqual(MediaType.parse("AUDIO"), .audio)
        XCTAssertEqual(MediaType.parse("video"), .video)
        XCTAssertEqual(MediaType.parse("AVATAR"), .avatar)
        XCTAssertNil(MediaType.parse(""))
        XCTAssertNil(MediaType.parse("   "))
        XCTAssertNil(MediaType.parse(nil))
        XCTAssertNil(MediaType.parse("HOLOGRAM"))
    }

    func testBackupKindsMapToCanonicalTypes() {
        XCTAssertEqual(MediaType.fromBackupKind("PERSONA_AVATAR"), .avatar)
        XCTAssertEqual(MediaType.fromBackupKind("USER_AVATAR"), .avatar)
        XCTAssertEqual(MediaType.fromBackupKind("CHAT_IMAGE"), .image)
        XCTAssertEqual(MediaType.fromBackupKind("STICKER"), .sticker)
        XCTAssertEqual(MediaType.fromBackupKind("AUDIO"), .audio)
        XCTAssertEqual(MediaType.fromBackupKind("VIDEO"), .video)
        XCTAssertNil(MediaType.fromBackupKind("avatar"))
        XCTAssertNil(MediaType.fromBackupKind("GIF"))
    }

    func testMimeInference() {
        XCTAssertEqual(MediaType.inferred(fromMime: "image/jpeg"), .image)
        XCTAssertEqual(MediaType.inferred(fromMime: "image/png"), .image)
        XCTAssertEqual(MediaType.inferred(fromMime: "image/webp"), .image)
        // GIF 在这个产品里只有表情。
        XCTAssertEqual(MediaType.inferred(fromMime: "image/gif"), .sticker)
        XCTAssertEqual(MediaType.inferred(fromMime: "audio/m4a"), .audio)
        XCTAssertEqual(MediaType.inferred(fromMime: "audio/mpeg"), .audio)
        XCTAssertEqual(MediaType.inferred(fromMime: "video/mp4"), .video)
        XCTAssertEqual(MediaType.inferred(fromMime: "video/quicktime"), .video)
        XCTAssertNil(MediaType.inferred(fromMime: "image/heic"))
        XCTAssertNil(MediaType.inferred(fromMime: "application/pdf"))
        XCTAssertNil(MediaType.inferred(fromMime: ""))
    }

    func testAvatarIsNotAnAttachmentType() {
        XCTAssertFalse(MediaType.avatar.isAttachmentType)
        for type in MediaType.attachmentTypes {
            XCTAssertTrue(type.isAttachmentType, "\(type) 应当是附件类型")
        }
        XCTAssertEqual(
            Set(MediaType.attachmentTypes.map(\.rawValue)),
            ["IMAGE", "STICKER", "AUDIO", "VIDEO"]
        )
    }

    // MARK: - 附件的 kind + mime

    func testExplicitKindAndMatchingMimeIsValid() {
        let cases: [(String, String, MediaType)] = [
            ("IMAGE", "image/jpeg", .image),
            ("IMAGE", "image/png", .image),
            ("IMAGE", "image/webp", .image),
            ("STICKER", "image/gif", .sticker),
            ("AUDIO", "audio/m4a", .audio),
            ("AUDIO", "audio/mpeg", .audio),
            ("VIDEO", "video/mp4", .video),
            ("VIDEO", "video/quicktime", .video)
        ]
        for (kind, mime, expected) in cases {
            let check = MessageAttachment(uri: "x", mime: mime, kind: kind).kindCheck
            XCTAssertEqual(check, .explicit(expected), "\(kind) / \(mime)")
        }
    }

    func testUnknownKindIsRejectedNotGuessedAsImage() {
        let attachment = MessageAttachment(uri: "x", mime: "image/jpeg", kind: "HOLOGRAM")
        guard case .invalid(let reason) = attachment.kindCheck else {
            return XCTFail("未知 kind 必须拒绝，实际：\(attachment.kindCheck)")
        }
        XCTAssertTrue(reason.contains("HOLOGRAM"), reason)
        XCTAssertNil(attachment.resolvedMediaType)
    }

    func testAvatarKindInAttachmentIsRejected() {
        let attachment = MessageAttachment(uri: "x", mime: "image/jpeg", kind: "AVATAR")
        guard case .invalid(let reason) = attachment.kindCheck else {
            return XCTFail("附件里不许出现 AVATAR")
        }
        XCTAssertTrue(reason.contains("AVATAR"), reason)
    }

    func testMimeMustMatchKind() {
        let cases = [
            MessageAttachment(uri: "x", mime: "image/jpeg", kind: "AUDIO"),
            MessageAttachment(uri: "x", mime: "image/gif", kind: "IMAGE"),
            MessageAttachment(uri: "x", mime: "audio/m4a", kind: "VIDEO"),
            MessageAttachment(uri: "x", mime: "", kind: "IMAGE"),
            MessageAttachment(uri: "x", mime: "  ", kind: "STICKER")
        ]
        for attachment in cases {
            guard case .invalid = attachment.kindCheck else {
                return XCTFail("kind/mime 不匹配必须拒绝：\(attachment.kind ?? "-") / \(attachment.mime)")
            }
        }
    }

    func testLegacyAttachmentWithoutKindIsAcceptedByMime() {
        let image = MessageAttachment(uri: "x", mime: "image/jpeg")
        XCTAssertNil(image.kind, "解码旧数据时不能凭空补上 kind")
        XCTAssertEqual(image.kindCheck, .legacy(.image))
        XCTAssertEqual(image.resolvedMediaType, .image)
        XCTAssertTrue(image.isImage)
        XCTAssertFalse(image.isAudio)

        let sticker = MessageAttachment(uri: "x", mime: "image/gif")
        XCTAssertEqual(sticker.kindCheck, .legacy(.sticker))
        XCTAssertTrue(sticker.isSticker)

        let unknown = MessageAttachment(uri: "x", mime: "application/octet-stream")
        guard case .invalid = unknown.kindCheck else {
            return XCTFail("认不出的 mime 必须拒绝")
        }
    }

    // MARK: - 编解码：未知类型拒绝、旧数据保持原样

    func testCodecRejectsUnknownKindForWholeSegment() {
        let raw = #"""
        [{"uri":"file:///a.jpg","mime":"image/jpeg"},{"uri":"file:///b.bin","mime":"image/jpeg","kind":"HOLOGRAM"}]
        """#
        // 与 Android 的整段 `runCatching` 同语义：一条坏数据 → 整段作废。
        XCTAssertTrue(AttachmentsCodec.decode(raw).isEmpty)
        let report = AttachmentsCodec.decodeReport(raw)
        XCTAssertEqual(report.rejected, 1)
        XCTAssertNotNil(report.rejectionReason)
    }

    func testCodecRejectsMimeMismatch() {
        let raw = #"[{"uri":"file:///voice.m4a","mime":"image/jpeg","kind":"AUDIO"}]"#
        XCTAssertTrue(AttachmentsCodec.decode(raw).isEmpty)
    }

    func testCodecNormalizesKindCase() throws {
        let raw = #"[{"uri":"file:///voice.m4a","mime":"audio/m4a","kind":"audio","durationMs":4200}]"#
        let decoded = try XCTUnwrap(AttachmentsCodec.decode(raw).first)
        XCTAssertEqual(decoded.kind, "AUDIO")
        XCTAssertTrue(decoded.isAudio)
        XCTAssertEqual(decoded.durationMs, 4200)
    }

    func testNewWritesAlwaysCarryExplicitKind() throws {
        // 0.15：普通图片新写入一律显式 `IMAGE`（不再留 null）。
        XCTAssertEqual(MessageAttachment.image(uri: "file:///a.jpg").kind, "IMAGE")
        XCTAssertEqual(MessageAttachment.audio(uri: "file:///a.m4a", durationMs: 1_000).kind, "AUDIO")
        XCTAssertEqual(MessageAttachment.video(uri: "file:///a.mp4", durationMs: 1_000).kind, "VIDEO")
        let encoded = try XCTUnwrap(AttachmentsCodec.encode([MessageAttachment.image(uri: "file:///a.jpg")]))
        XCTAssertTrue(encoded.contains(#""kind":"IMAGE""#), encoded)
    }

    /// 服务端下载落地后的附件：kind 与 mime 必须匹配（否则下一次 decode 会整段丢掉）。
    func testServerLocalAttachmentFixesMimeForKind() {
        let store = ServerMediaLocalStore(
            attachmentsDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("mf-media-types-\(UUID().uuidString)", isDirectory: true),
            stickersDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("mf-media-types-st-\(UUID().uuidString)", isDirectory: true),
            audioDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("mf-media-types-a-\(UUID().uuidString)", isDirectory: true),
            videoDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("mf-media-types-v-\(UUID().uuidString)", isDirectory: true)
        )
        // 服务端只给了 mediaId，mime 被补成默认 `image/jpeg`，但附件显式说这是语音。
        let attachment = MessageAttachment(uri: "x", mime: "image/jpeg", kind: "AUDIO")
        let local = store.localAttachment(
            for: attachment,
            mediaId: "m-1",
            kind: .audio,
            fileURL: URL(fileURLWithPath: "/tmp/audio-m-1-abc.m4a")
        )
        XCTAssertEqual(local.mime, "audio/m4a")
        XCTAssertTrue(local.isKindValid, "\(local.kind ?? "-") / \(local.mime)")
    }
}
