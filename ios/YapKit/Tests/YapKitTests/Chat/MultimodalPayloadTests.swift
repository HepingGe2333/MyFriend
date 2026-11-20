import Foundation
import XCTest
@testable import YapKit

/// 0.13 多模态报文：音频 `input_audio`、视频关键帧 `image_url`、上下文里的媒体说明、
/// 以及「历史不重复发送 base64」。
final class MultimodalPayloadTests: XCTestCase {

    // MARK: - 报文形状

    func testAudioMessageEncodesInputAudioPart() throws {
        let audio = WireAudioPart(base64: "AAAA", format: "m4a")
        let message = WireMessage(role: "user", content: "听听这段", audio: [audio])
        let data = try OpenAICompatibleClient.encodeRequest(
            model: "qwen-omni", messages: [message], params: InferenceParams(), stream: false
        )
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let messages = try XCTUnwrap(root["messages"] as? [[String: Any]])
        let parts = try XCTUnwrap(messages.first?["content"] as? [[String: Any]])
        XCTAssertEqual(parts.count, 2)
        XCTAssertEqual(parts[0]["type"] as? String, "text")
        XCTAssertEqual(parts[0]["text"] as? String, "听听这段")
        XCTAssertEqual(parts[1]["type"] as? String, "input_audio")
        let payload = try XCTUnwrap(parts[1]["input_audio"] as? [String: Any])
        XCTAssertEqual(payload["data"] as? String, "AAAA")
        XCTAssertEqual(payload["format"] as? String, "m4a")
    }

    func testVideoFramesEncodeAsImageURLsWithTextNote() throws {
        let frames = [
            "data:image/jpeg;base64,AAAA",
            "data:image/jpeg;base64,BBBB"
        ]
        let parts = MultimodalPayload.contentParts(
            text: "视频：2 帧，时长 4.0 秒", images: frames, audio: []
        )
        XCTAssertEqual(parts.count, 3)
        XCTAssertEqual(parts[0]["type"] as? String, "text")
        XCTAssertEqual(parts[1]["type"] as? String, "image_url")
        let url = try XCTUnwrap((parts[1]["image_url"] as? [String: Any])?["url"] as? String)
        XCTAssertEqual(url, frames[0])
        XCTAssertEqual(parts[2]["type"] as? String, "image_url")
    }

    func testPlainMessageStaysStringContent() throws {
        let data = try OpenAICompatibleClient.encodeRequest(
            model: "m", messages: [WireMessage(role: "user", content: "在吗")],
            params: InferenceParams(), stream: false
        )
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let messages = try XCTUnwrap(root["messages"] as? [[String: Any]])
        XCTAssertEqual(messages.first?["content"] as? String, "在吗")
    }

    func testAudioFormatMapping() {
        XCTAssertEqual(MultimodalPayload.audioFormat(forMime: "audio/m4a"), "m4a")
        XCTAssertEqual(MultimodalPayload.audioFormat(forMime: "audio/x-m4a"), "m4a")
        XCTAssertEqual(MultimodalPayload.audioFormat(forMime: "audio/mpeg"), "mpeg")
        XCTAssertNil(MultimodalPayload.audioFormat(forMime: "audio/ogg"))
    }

    // MARK: - 上下文（历史）

    private func message(
        id: String,
        role: MessageRole,
        content: String,
        attachments: [MessageAttachment] = [],
        at: Int64
    ) -> MessageEntity {
        MessageEntity(
            id: id, sessionId: "s1", personaId: "p1", role: role, content: content,
            createdAt: at, updatedAt: at, attachmentsJson: AttachmentsCodec.encode(attachments)
        )
    }

    func testHistoryMentionsMediaTypeAndIdButOnlyCurrentTurnGetsAudio() throws {
        let oldAudio = MessageAttachment.audio(uri: "file:///audio/audio-old.m4a", durationMs: 9_000)
        let currentAudio = MessageAttachment.audio(uri: "file:///audio/audio-new.m4a", durationMs: 12_000)
        let messages = [
            message(id: "m1", role: .user, content: "", attachments: [oldAudio], at: 1),
            message(id: "m2", role: .assistant, content: "我听到了", at: 2),
            message(id: "m3", role: .user, content: "", attachments: [currentAudio], at: 3)
        ]
        let parts = MediaWireParts(audio: [WireAudioPart(base64: "Q1VBTA==", format: "m4a")])
        let history = PromptBuilder.history(
            messages: messages,
            summarizedUpTo: nil,
            imageResolver: nil,
            mediaParts: ["m3": parts]
        )
        XCTAssertEqual(history.count, 3)

        // 旧语音：只有一行说明（id + 类型），**没有** input_audio。
        XCTAssertTrue(history[0].audio.isEmpty, "历史里的语音不该重复发送 base64")
        XCTAssertTrue(history[0].content.contains("语音"))
        XCTAssertTrue(history[0].content.contains("audio-old"))
        // 当轮语音：真的带上了音频片段，说明里也仍然有 id。
        XCTAssertEqual(history[2].audio, [WireAudioPart(base64: "Q1VBTA==", format: "m4a")])
        XCTAssertTrue(history[2].content.contains("audio-new"))
    }

    func testHistoryAddsVideoFrameNoteAndType() throws {
        let video = MessageAttachment.video(uri: "file:///video/video-1.mp4", durationMs: 12_000)
        let messages = [message(id: "m1", role: .user, content: "", attachments: [video], at: 1)]
        let frames = ["data:image/jpeg;base64,AAAA"]
        let parts = MediaWireParts(images: frames, note: "视频：1 帧，时长 12.0 秒")
        let history = PromptBuilder.history(
            messages: messages, summarizedUpTo: nil, imageResolver: nil, mediaParts: ["m1": parts]
        )
        let wire = try XCTUnwrap(history.first)
        XCTAssertEqual(wire.images, frames)
        XCTAssertTrue(wire.content.contains("视频"))
        XCTAssertTrue(wire.content.contains("视频：1 帧，时长 12.0 秒"))
        XCTAssertTrue(wire.content.contains("video-1"))
    }

    func testHistoryWithoutMediaBehavesAsBefore() {
        let messages = [
            message(id: "m1", role: .user, content: "在吗", at: 1),
            message(id: "m2", role: .assistant, content: "在", at: 2)
        ]
        let history = PromptBuilder.history(messages: messages, summarizedUpTo: nil)
        // 0.15：每条带 `[HH:mm] 说话人：` 前缀（这里只关心「媒体字段没变」）。
        XCTAssertEqual(history.count, 2)
        XCTAssertTrue(history[0].content.hasSuffix("用户：在吗"), history[0].content)
        XCTAssertTrue(history[1].content.hasSuffix("他：在"), history[1].content)
        XCTAssertTrue(history.allSatisfy { $0.audio.isEmpty && $0.images.isEmpty })
    }

    // MARK: - 视频关键帧策略

    func testFramePolicyCapsFramesAndKeepsPixelBudget() {
        XCTAssertEqual(VideoFramePolicy.frameCount(durationSeconds: 2), 1)
        XCTAssertEqual(VideoFramePolicy.frameCount(durationSeconds: 12), 6)
        XCTAssertEqual(VideoFramePolicy.frameCount(durationSeconds: 120), 8)
        XCTAssertEqual(VideoFramePolicy.frameCount(durationSeconds: nil), 8)

        let times = VideoFramePolicy.frameTimes(durationSeconds: 10, count: 4)
        XCTAssertEqual(times.count, 4)
        XCTAssertTrue(times.allSatisfy { $0 >= 0 && $0 <= 10 })
        XCTAssertEqual(times, times.sorted())
        XCTAssertLessThan(times.last ?? 0, 10)

        // 体积预算：超了就丢最后几帧，绝不越过 8 MB。
        let frames = (1...8).map { Data(repeating: 0, count: $0) }
        let budgeted = VideoFramePolicy.budgeted(frames, budget: 10)
        XCTAssertEqual(budgeted.map(\.count), [1, 2, 3, 4])
        XCTAssertEqual(VideoFramePolicy.note(frameCount: 6, durationSeconds: 12), "视频：6 帧，时长 12.0 秒")
    }

    // MARK: - 不支持的模型：明确报错

    func testUnsupportedModelErrorIsExplicitAndKeepsStatus() {
        let original = ApiException(statusCode: 400, message: "请求被服务拒绝（400）", detail: "unsupported content")
        let mapped = MediaModality.map(error: original, hadAudio: true, hadVideo: false)
        XCTAssertEqual((mapped as? ApiException)?.message, "这个模型听不了语音")
        XCTAssertEqual((mapped as? ApiException)?.statusCode, 400)
        XCTAssertEqual((mapped as? ApiException)?.detail, "unsupported content")

        let both = MediaModality.map(error: original, hadAudio: true, hadVideo: true)
        XCTAssertEqual((both as? ApiException)?.message, "这个模型听不了语音，也看不了视频")
        let video = MediaModality.map(
            error: ApiException(statusCode: 415, message: "不支持的媒体类型"), hadAudio: false, hadVideo: true
        )
        XCTAssertEqual((video as? ApiException)?.message, "这个模型看不了视频")

        // 与模态无关的错误原样保留：Key 错了就是 Key 错了。
        let unauthorized = ApiException(statusCode: 401, message: "API Key 无效或未授权（401）")
        XCTAssertEqual(
            (MediaModality.map(error: unauthorized, hadAudio: true, hadVideo: true) as? ApiException)?.message,
            "API Key 无效或未授权（401）"
        )
        let throttled = ApiException(statusCode: 429, message: "请求过于频繁或额度不足（429）")
        XCTAssertEqual(
            (MediaModality.map(error: throttled, hadAudio: true, hadVideo: false) as? ApiException)?.message,
            "请求过于频繁或额度不足（429）"
        )
        // 这一轮没带音视频：不翻译。
        XCTAssertEqual(
            (MediaModality.map(error: original, hadAudio: false, hadVideo: false) as? ApiException)?.message,
            "请求被服务拒绝（400）"
        )
    }

    func testToolCatalogExposesListenAndWatch() {
        XCTAssertNotNil(ToolCatalog.byName(ToolCatalog.listen))
        XCTAssertNotNil(ToolCatalog.byName(ToolCatalog.watch))
        let prompt = ToolCatalog.capabilityPrompt()
        XCTAssertTrue(prompt.contains("listen("))
        XCTAssertTrue(prompt.contains("watch("))
        XCTAssertTrue(prompt.contains("audioId"))
        XCTAssertTrue(prompt.contains("videoId"))
    }
}
