import Foundation

// ---------------------------------------------------------------------------
// 多模态报文的形状（0.13 语音 / 视频）。
//
// OpenAI 兼容协议里一条消息的 `content` 可以是字符串，也可以是 parts 数组：
//
//   [{"type":"text","text":"…"},
//    {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}},
//    {"type":"input_audio","input_audio":{"data":"<base64>","format":"m4a"}}]
//
// 抽成纯函数（不碰网络）是为了两件事：
// 1. 单测能直接断言「音频 part 的字段名与格式」——写错一个键，模型只会退回成
//    「读不懂这条消息」，报错很难查；
// 2. `listen` / `watch` 工具结果、当轮附件、历史图片三条路径**共用同一份拼装器**，
//    不会出现「聊天里的语音能听、工具里再听的语音形状不一样」。
//
// 视频没有 `input_video` 这种 part：各家的形状都不统一，规格选择「抽关键帧 + 一句
// 时长帧数说明」以多张 `image_url` 发送（见 `VideoFramePolicy`）。
// ---------------------------------------------------------------------------

public enum MultimodalPayload {

    /// 音频 part 的 `type` 值。
    public static let inputAudioType = "input_audio"

    /// 图片 part（data URL 或 http(s) 链接）。
    public static func imagePart(_ url: String) -> [String: Any] {
        ["type": "image_url", "image_url": ["url": url]]
    }

    /// 文字 part。
    public static func textPart(_ text: String) -> [String: Any] {
        ["type": "text", "text": text]
    }

    /// 音频 part：`{"type":"input_audio","input_audio":{"data":"…","format":"m4a"}}`。
    public static func audioPart(_ audio: WireAudioPart) -> [String: Any] {
        [
            "type": inputAudioType,
            "input_audio": ["data": audio.base64, "format": audio.format]
        ]
    }

    /// 拼一条多模态消息的 `content` 数组：文字 → 图片（含视频帧）→ 音频。
    ///
    /// 空文字不写 text part（模型不需要一句空话）；如果三者都为空，返回一个空数组，
    /// 调用方应当退回字符串形式（`WireMessage.isMultimodal` 已经保证了这一点）。
    public static func contentParts(
        text: String,
        images: [String],
        audio: [WireAudioPart]
    ) -> [[String: Any]] {
        var parts: [[String: Any]] = []
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { parts.append(textPart(text)) }
        for url in images { parts.append(imagePart(url)) }
        for part in audio { parts.append(audioPart(part)) }
        return parts
    }

    /// 把一段媒体片段拼成一条消息（附件与工具结果都走这里）。
    public static func wireMessage(
        role: String,
        content: String,
        parts: MediaWireParts?
    ) -> WireMessage {
        guard let parts, !parts.isEmpty else {
            return WireMessage(role: role, content: content)
        }
        var text = content
        let note = parts.note.trimmingCharacters(in: .whitespacesAndNewlines)
        if !note.isEmpty {
            text = text.isEmpty ? note : text + "\n" + note
        }
        return WireMessage(role: role, content: text, images: parts.images, audio: parts.audio)
    }

    /// 这个 mime 在 provider 那边的 `format` 名（认不出返回 nil）。
    public static func audioFormat(forMime mime: String) -> String? {
        WireAudioPart.format(forMime: mime)
    }
}
