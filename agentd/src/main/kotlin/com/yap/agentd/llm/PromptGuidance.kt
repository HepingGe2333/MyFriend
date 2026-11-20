package com.yap.agentd.llm

import com.yap.agentd.store.Mood

/**
 * 本轮「引导」段（0.15 上下文 profile，`spec/context-profile/README.md`）。
 *
 * 评测结论（`eval/reports/bench2-guidance-summary-*.md`）：同一段模板只改四个参数
 * （stance / move1 / move2 / avoid），排名靠前的是 **g12（有点防备）** 与
 * **g17（只聊此刻）**。产品里按心情二选一，不引入「心情判定」之外的变量：
 *
 * - `valence < 0` 或 `arousal < 2`（心情偏负 / 低唤醒）→ [G12]：有点防备，不轻易示弱；
 * - 其余情况（含**没有心情记录**）→ [G17]：只聊此刻，不翻旧账。
 *
 * 位置固定在「心情之后、话风之前」，所以整段排在当前状态块的最后一行；标题 `# 引导`
 * 是独立段标题，方便两端逐字对照（`:agentcore` 暂无这条常量，先由服务端持有）。
 */
object PromptGuidance {

    /** 段标题（profile 里固定）。 */
    const val TITLE = "# 引导"

    /**
     * 一个引导变体：只有四个参数，模板一个字不改（模板见 [text]）。
     *
     * [avoid] 会拼进「不要{avoid}。」，所以取值是动词短语（`掏心掏肺` / `反问`）。
     */
    data class Variant(
        val id: String,
        val stance: String,
        val move1: String,
        val move2: String,
        val avoid: String
    ) {
        /** 渲染成提示词段：标题 + 「这一轮：…」+ 两条动作 + 一条禁止。 */
        val text: String
            get() = buildString {
                appendLine(TITLE)
                appendLine("这一轮：$stance")
                appendLine("- $move1")
                appendLine("- $move2")
                append("- 不要$avoid。")
            }
    }

    /** 心情偏负 / 低唤醒：有点防备，不轻易示弱（评测里 judge 最高的一档）。 */
    val G12: Variant = Variant(
        id = "g12",
        stance = "有点防备，不轻易示弱",
        move1 = "用短句挡一下",
        move2 = "被问急了才多露半句",
        avoid = "掏心掏肺"
    )

    /** 默认档：只聊此刻，不翻旧账（`spec/context-profile/README.md` 的四个动作：只聊此刻/不翻旧账/不延伸/不反问）。 */
    val G17: Variant = Variant(
        id = "g17",
        stance = "只聊此刻，不翻旧账",
        move1 = "回应现在这句话",
        move2 = "不延伸过去",
        avoid = "反问"
    )

    /**
     * 按心情选引导。**不改心情判定**：这里只读已经判好的三维数值。
     *
     * @param mood `null` = 没有心情记录（老数据/判定失败且无历史）→ [G17]
     */
    fun select(mood: Mood?): Variant =
        if (mood != null && (mood.valence < 0 || mood.arousal < AROUSAL_LOW)) G12 else G17

    /** 情绪偏负 / 低唤醒的判定：`arousal < 2`（0..5 的量表，2 及以下算「提不起劲」）。 */
    const val AROUSAL_LOW: Int = 2

    /** 直接拿渲染好的段（空心情也有 g17，所以**永远非空**）。 */
    fun section(mood: Mood?): String = select(mood).text
}
