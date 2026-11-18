package com.yap.app.domain

/**
 * 应用自己发起 completion 请求时使用的默认参数（`spec/api-defaults/README.md`）。
 *
 * 为什么要有这么一个「唯一真源」：温度、上下文窗口、单次最大输出散落在网络层、仓库层、
 * 提示词装配层各自写一个数字时，改了其中一处就会出现「聊天用 0.75、工具回路用 1.0」
 * 这种只在长期相处里才慢慢变味的问题。这里只放常量与纯计算，不碰网络与数据库。
 *
 * 三条边界：
 * 1. **配置显式值优先**：用户在 API 配置里填了温度/最大输出，就用他的（见 `NetworkModels.inferenceParams`）；
 * 2. **1M 只是上限**：上下文窗口是模型能力上限，真正发出去的体积仍受「最近 N 条 + 摘要」约束
 *    （见 [requestCharBudget] 与 `ChatRepository` 的历史裁剪）；
 * 3. 频率/存在惩罚默认 0：不启用，别让模型被悄悄改了说话习惯。
 */
object ApiDefaults {

    /** 0.75：在「像人」与「稳定」之间；再高开始跑题，再低开始像模板。 */
    const val TEMPERATURE: Double = 0.75

    /** 不额外截断采样分布。 */
    const val TOP_P: Double = 1.0

    /** 单次最大输出：工具回路每一轮同样用这个上限。 */
    const val MAX_OUTPUT_TOKENS: Int = 16_384

    /** 上下文窗口上限（tokens）。只作为上限，不是「每次都要发满」。 */
    const val CONTEXT_WINDOW_TOKENS: Int = 1_000_000

    /** 频率/存在惩罚：0 = 不启用。 */
    const val FREQUENCY_PENALTY: Double = 0.0
    const val PRESENCE_PENALTY: Double = 0.0

    /**
     * tokens → 字符的保守折算：中英混排按 1 token ≈ 2 字符估。
     *
     * 只用于「提醒自己别越过窗口」这一层粗算（真正的 token 数由服务端算）。
     * 估小一点更安全：低估字符预算只会让历史裁得更早，不会撞上窗口。
     */
    const val CHARS_PER_TOKEN: Int = 2

    /**
     * 上下文窗口折算出的**输入**字符容量：`(窗口 - 单次最大输出) × 每 token 字符数`。
     *
     * 减掉输出是必须的：窗口是「输入 + 输出」的总和，把 1M 全给输入会让最后一段回复被截断。
     */
    fun inputCharBudget(
        contextWindowTokens: Int = CONTEXT_WINDOW_TOKENS,
        maxOutputTokens: Int = MAX_OUTPUT_TOKENS
    ): Int {
        val window = contextWindowTokens.coerceAtLeast(0)
        val output = maxOutputTokens.coerceIn(0, window)
        val tokens = window - output
        val chars = tokens.toLong() * CHARS_PER_TOKEN
        return chars.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 一次请求实际拼进消息的字符预算：**上限是 1M 窗口折算值**，再取更保守的产品收紧值。
     *
     * @param practicalChars 产品侧为了成本/延迟/首字时间额外收的口径（聊天目前约 2.8 万字）；
     *        它只会让预算更小，永远不会越过 [inputCharBudget] 给出的窗口上限。
     */
    fun requestCharBudget(
        practicalChars: Int,
        contextWindowTokens: Int = CONTEXT_WINDOW_TOKENS,
        maxOutputTokens: Int = MAX_OUTPUT_TOKENS
    ): Int = minOf(practicalChars.coerceAtLeast(0), inputCharBudget(contextWindowTokens, maxOutputTokens))
}
