import Foundation

// ---------------------------------------------------------------------------
// 「这个模型听不了语音 / 看不了视频」（0.13）。
//
// 背景：语音/视频是**多模态输入**，不是所有 OpenAI 兼容网关都支持。常见表现是
// 一个 4xx（`400 unsupported content type` / `415 unsupported media type` /
// `422 invalid content`），如果照原样把「请求被服务拒绝（400）」交给用户，
// 他根本不知道原因，还以为是自己发的语音坏了。
//
// 三条规则：
// 1. **明确报错**：这一轮确实带了音频/视频时，4xx 统一翻成
//    「这个模型听不了语音」/「这个模型看不了视频」；
// 2. **绝不伪造转写**：不悄悄改成「我听到了…」，也不把音频丢掉重发一次 ——
//    模型没听到就是没听到；
// 3. **别把无关错误吞掉**：401/403/404/413/429 这些与「模态不支持」无关的状态码
//    原样交给原来的错误映射（Key 错了、模型不存在、太长、限流）。
// ---------------------------------------------------------------------------

public enum MediaModality {

    /// 这些状态码才可能是「模型不支持这种输入」。
    public static func mayBeUnsupportedModality(status: Int?) -> Bool {
        guard let status else { return false }
        guard (400...499).contains(status) else { return false }
        switch status {
        case 401, 403, 404, 408, 413, 429: return false
        default: return true
        }
    }

    /// 明确的那句话；这一轮没带音视频时返回 nil（不需要改文案）。
    public static func unsupportedMessage(hadAudio: Bool, hadVideo: Bool) -> String? {
        switch (hadAudio, hadVideo) {
        case (false, false):
            return nil
        case (true, false):
            return String(localized: "这个模型听不了语音")
        case (false, true):
            return String(localized: "这个模型看不了视频")
        case (true, true):
            return String(localized: "这个模型听不了语音，也看不了视频")
        }
    }

    /// 把 provider 的 4xx 翻成一句用户能懂的话；不需要翻就原样返回。
    ///
    /// 原始状态码与响应体留在 `ApiException.detail` 里（排查用），不展示给用户。
    public static func map(error: Error, hadAudio: Bool, hadVideo: Bool) -> Error {
        guard let message = unsupportedMessage(hadAudio: hadAudio, hadVideo: hadVideo) else {
            return error
        }
        guard let api = error as? ApiException,
              mayBeUnsupportedModality(status: api.statusCode) else {
            return error
        }
        return ApiException(statusCode: api.statusCode, message: message, detail: api.detail)
    }
}
