import Foundation
import XCTest
@testable import YapKit

/// 0.13 音视频附件模型（`spec/audio-video/README.md`）：
/// `kind` / `durationMs` 的编解码、mime 白名单、体积/时长上限、以及旧 JSON 的向后兼容。
final class AudioVideoAttachmentsTests: XCTestCase {

    // MARK: - 编解码

    func testOldImageJSONStillDecodesWithoutKindOrDuration() throws {
        let raw = #"[{"uri":"file:///a.jpg","mime":"image/jpeg","width":1600,"height":1200}]"#
        let items = AttachmentsCodec.decode(raw)
        XCTAssertEqual(items.count, 1)
        let attachment = try XCTUnwrap(items.first)
        XCTAssertEqual(attachment.kind, nil)
        XCTAssertEqual(attachment.durationMs, nil)
        XCTAssertTrue(attachment.isImage)
        XCTAssertFalse(attachment.isAudio)
        XCTAssertFalse(attachment.isVideo)
        // 旧数据重新编码：仍然只写老字段（不凭空多出 kind/durationMs），
        // 再解一遍与原值相等（老版本能读懂新版本写出来的东西）。
        let reencoded = try XCTUnwrap(AttachmentsCodec.encode(items))
        XCTAssertFalse(reencoded.contains("kind"))
        XCTAssertFalse(reencoded.contains("durationMs"))
        XCTAssertEqual(AttachmentsCodec.decode(reencoded), items)
    }

    func testAudioAndVideoRoundTripKeepsKindAndDuration() throws {
        let audio = MessageAttachment.audio(uri: "file:///audio/x.m4a", durationMs: 12_300)
        let video = MessageAttachment.video(
            uri: "file:///video/y.mp4", durationMs: 65_000, width: 1280, height: 720
        )
        let encoded = try XCTUnwrap(AttachmentsCodec.encode([audio, video]))
        XCTAssertTrue(encoded.contains(#""kind":"AUDIO""#))
        XCTAssertTrue(encoded.contains(#""durationMs":12300"#))
        XCTAssertTrue(encoded.contains(#""kind":"VIDEO""#))

        let decoded = AttachmentsCodec.decode(encoded)
        XCTAssertEqual(decoded, [audio, video])
        XCTAssertTrue(decoded[0].isAudio)
        XCTAssertTrue(decoded[1].isVideo)
        XCTAssertFalse(decoded[0].isImage)
        XCTAssertEqual(decoded[1].durationSeconds, 65)
    }

    func testUnknownFieldsAreIgnoredAndBadDurationInvalidatesWholeList() {
        // 未知字段（未来版本/Android 写的）：忽略，不炸。
        let extra = #"[{"uri":"file:///a.m4a","mime":"audio/m4a","kind":"AUDIO","durationMs":900,"waveform":[1,2]}]"#
        XCTAssertEqual(AttachmentsCodec.decode(extra).count, 1)
        // 坏类型：与 Android 的整段 runCatching 同语义 —— 整段当没有。
        let bad = #"[{"uri":"file:///a.m4a","durationMs":"很久"}]"#
        XCTAssertTrue(AttachmentsCodec.decode(bad).isEmpty)
    }

    func testMimeWhitelistMatchesSpec() {
        for mime in ["image/jpeg", "image/png", "image/webp", "image/gif",
                     "audio/m4a", "audio/mp4", "audio/mpeg",
                     "video/mp4", "video/quicktime"] {
            XCTAssertTrue(AttachmentsCodec.isAllowedMime(mime), "\(mime) 应当被允许")
        }
        for mime in ["audio/ogg", "video/webm", "application/pdf", "image/heic"] {
            XCTAssertFalse(AttachmentsCodec.isAllowedMime(mime), "\(mime) 不在规格里")
        }
        XCTAssertEqual(AttachmentsCodec.kind(forMime: "audio/m4a"), "AUDIO")
        XCTAssertEqual(AttachmentsCodec.kind(forMime: "video/quicktime"), "VIDEO")
        XCTAssertEqual(AttachmentsCodec.kind(forMime: "image/webp"), "IMAGE")
    }

    /// 只写了 mime、没写 kind 的老数据/外部数据也要能认出语音与视频。
    func testKindFallsBackToMime() {
        XCTAssertTrue(MessageAttachment(uri: "x", mime: "audio/mpeg").isAudio)
        XCTAssertTrue(MessageAttachment(uri: "x", mime: "video/quicktime").isVideo)
        // 显式 kind 优先：mime 被服务端补成默认 image/jpeg 时，AUDIO 仍然是语音。
        let audio = MessageAttachment(uri: "x", mime: "image/jpeg", kind: "AUDIO", durationMs: 5_000)
        XCTAssertTrue(audio.isAudio)
        XCTAssertFalse(audio.isImage)
        // 表情（kind=STICKER + image/gif）不能被当成图片之外的别的东西。
        let sticker = MessageAttachment(uri: "x", mime: "image/gif", kind: "STICKER")
        XCTAssertTrue(sticker.isSticker)
        XCTAssertTrue(sticker.isImage)
        XCTAssertFalse(sticker.isAudio)
    }

    // MARK: - 上限

    func testLimitsRejectOverlongAndOversizedMedia() {
        XCTAssertNil(AudioVideoLimits.rejection(
            durationMs: 60_000, bytes: 1_000_000, kind: .audio
        ))
        XCTAssertEqual(
            AudioVideoLimits.rejection(durationMs: 6 * 60_000, bytes: 100, kind: .audio),
            "语音最长 5 分钟"
        )
        XCTAssertEqual(
            AudioVideoLimits.rejection(durationMs: 3 * 60_000, bytes: 100, kind: .video),
            "视频最长 2 分钟"
        )
        XCTAssertEqual(
            AudioVideoLimits.rejection(durationMs: 1_000, bytes: AudioVideoLimits.audioMaxBytes + 1, kind: .audio),
            "语音超过 8 MB"
        )
        XCTAssertEqual(
            AudioVideoLimits.rejection(durationMs: 1_000, bytes: AudioVideoLimits.videoMaxBytes + 1, kind: .video),
            "视频超过 64 MB"
        )
        // 录音到 5 分钟自动停；4:59 还继续。
        XCTAssertFalse(AudioVideoLimits.shouldAutoStop(elapsedMs: 5 * 60_000 - 1))
        XCTAssertTrue(AudioVideoLimits.shouldAutoStop(elapsedMs: 5 * 60_000))
    }

    func testDurationTextFormatting() {
        XCTAssertEqual(AudioVideoLimits.durationText(nil), "--:--")
        XCTAssertEqual(AudioVideoLimits.durationText(7_000), "0:07")
        XCTAssertEqual(AudioVideoLimits.durationText(83_000), "1:23")
        XCTAssertEqual(AudioVideoLimits.durationText(300_000), "5:00")
    }

    /// 「历史里不重复发 base64」靠的是：媒体 id 与类型在上下文里以一行文字出现，
    /// 二进制只给当轮那一条消息。
    func testMediaContextLineCarriesIdTypeAndToolHint() throws {
        let audio = MessageAttachment.audio(uri: "file:///audio/audio-abc.m4a", durationMs: 12_000)
        XCTAssertEqual(audio.mediaRefId, "audio-abc")
        let line = audio.mediaContextLine
        XCTAssertTrue(line.contains("语音"))
        XCTAssertTrue(line.contains("audio-abc"))
        XCTAssertTrue(line.contains("listen"))

        let serverVideo = MessageAttachment.video(uri: "/v1/media/vid-1", durationMs: 8_000)
        XCTAssertEqual(serverVideo.mediaRefId, "vid-1")
        XCTAssertTrue(serverVideo.mediaContextLine.contains("watch"))
        // 图片没有这一行（图片走 image_url，不需要让模型去「听/看」）。
        XCTAssertTrue(MessageAttachment(uri: "file:///a.jpg").mediaContextLine.isEmpty)
    }
}
