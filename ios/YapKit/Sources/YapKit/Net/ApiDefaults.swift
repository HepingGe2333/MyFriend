import Foundation

// ---------------------------------------------------------------------------
// 模型 API 的**固定默认参数**（`spec/api-defaults/README.md`，0.13）。
//
// 为什么要有这一层：
// - 默认值是一个**产品决定**（0.75 在「像人」与「稳定」之间、单次最多 16K 输出、
//   上下文上限 1M），它不该散落在聊天、后台任务、服务层各写一遍；
// - 「配置里显式填了就以配置为准」这条规则只能有一处实现，否则某条链路会悄悄
//   把用户的 0.3 覆盖回 0.75；
// - 单测要能直接断言「发出去的报文里 temperature=0.75、max_tokens=16384」，
//   所以默认值必须是公开常量，而不是藏在某个 `??` 的右侧。
//
// 边界（刻意）：
// - **只填默认值，不改显式值**：`temperature` / `topP` / `maxOutputTokens` /
//   惩罚项在配置里为 nil 才落默认；配了就用配置的（哪怕是 0 或 1.2）；
// - 上下文 1M 只是**上限**：历史仍由「最近 N 条 + 滚动摘要」控制（`PromptBuilder`），
//   1M 参与预算换算（见 `historyCharBudget`），不是「一次把 1M 全发出去」；
// - 频率/存在惩罚默认 0（显式开启才发）。
// ---------------------------------------------------------------------------

public enum ApiDefaults {

    /// 采样温度：0.75 在「像人」与「稳定」之间。
    public static let temperature: Double = 0.75
    /// `top_p`：不额外截断。
    public static let topP: Double = 1.0
    /// 单次最大输出（工具回路每轮同样）。
    public static let maxOutputTokens: Int = 16_384
    /// 上下文窗口上限：1,000,000 tokens（只作上限，见文件头）。
    public static let contextWindowTokens: Int = 1_000_000
    /// 频率惩罚 / 存在惩罚：不启用。
    public static let frequencyPenalty: Double = 0
    public static let presencePenalty: Double = 0

    /// token → 字符的粗略折算（预算换算用）。
    ///
    /// 1 token ≈ 2 个字符是「中英混排」下的保守估计：宁可少发一点历史，
    /// 也不要在 1M 上限之外把请求体顶爆。它只用于**裁剪**，不用于计费
    /// （计费看服务端回的 usage，见 `ApiCost`）。
    public static let charsPerToken: Double = 2.0

    /// 配置 → 推理参数：显式值优先，缺的补默认值。
    ///
    /// 这是「客户端与服务端各自实现同一默认值」里的客户端那一份：
    /// 聊天、工具回路、后台任务（经 `CompletionClientAdapter`）都走它。
    public static func inferenceParams(
        temperature: Double?,
        topP: Double?,
        maxOutputTokens: Int?,
        frequencyPenalty: Double?,
        presencePenalty: Double?
    ) -> InferenceParams {
        InferenceParams(
            temperature: temperature ?? Self.temperature,
            topP: topP ?? Self.topP,
            maxOutputTokens: maxOutputTokens ?? Self.maxOutputTokens,
            frequencyPenalty: frequencyPenalty ?? Self.frequencyPenalty,
            presencePenalty: presencePenalty ?? Self.presencePenalty
        )
    }

    /// 有效上下文窗口：显式配置优先，但不超过 1M 上限（1M 是「最多」，不是「至少」）。
    ///
    /// iOS 的 API 配置里目前没有「上下文窗口」输入项（Android 也没有），
    /// 所以生产路径传 nil → 1M；这个入口留给以后加配置项，也留给单测钉住上限语义。
    public static func contextTokenLimit(configured: Int? = nil) -> Int {
        guard let configured, configured > 0 else { return contextWindowTokens }
        return min(configured, contextWindowTokens)
    }

    /// 上下文预算换算成「历史正文字数」预算。
    ///
    /// 先把输出额度从窗口里扣掉（`max_tokens` 也占窗口），再按 `charsPerToken` 折算，
    /// 最少给 0（配置了一个比输出还小的窗口时，预算就是 0 —— 调用方会退回默认值）。
    public static func historyCharBudget(
        contextTokens: Int = contextWindowTokens,
        maxOutputTokens: Int = maxOutputTokens
    ) -> Int {
        let window = max(contextTokens - maxOutputTokens, 0)
        return Int((Double(window) * charsPerToken).rounded(.down))
    }
}

public extension ApiConfigEntity {
    /// 这个配置实际会发出去的推理参数（显式值优先，缺的用 `ApiDefaults`）。
    ///
    /// 与旧的 `inferenceParams()` 的差别：旧的把 nil 原样传下去（= 让服务端用它的默认值），
    /// 0.13 起客户端自己定默认值，所以这里永远给出完整的五个参数。
    func effectiveInferenceParams() -> InferenceParams {
        ApiDefaults.inferenceParams(
            temperature: temperature,
            topP: topP,
            maxOutputTokens: maxOutputTokens,
            frequencyPenalty: frequencyPenalty,
            presencePenalty: presencePenalty
        )
    }
}
