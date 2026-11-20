package com.yap.app.domain

/**
 * 工具轮数（0.15）：默认 5 轮、模型可以在参数里指定、程序硬上限 15。
 *
 * 为什么需要单独一层：0.14 的轮数写死在 `ChatRepository`（3 轮）。0.15 允许模型按任务
 * 复杂度要更多轮（「查一下 → 再确认 → 顺手记一笔 → 说话」），但**必须有硬顶**，
 * 否则一个死循环能把一次回复变成几十次 16K 输出。
 *
 * 规则本身在共享契约 [ExperienceContract.clampToolRounds]（Android / iOS / agentd 同一口径）；
 * 这个文件只负责把「模型写在工具参数里的 rounds」安全地解析出来：
 *
 * - 任何工具调用都可以带一个可选的 `rounds` 参数（不是新工具，不增加工具数量）；
 * - 值可以是数字、数字字符串、或带小数/空白的脏值；认不出就当没指定（用默认值）；
 * - 一轮里多次指定时取**最大值**（模型先说要 3 轮、后来发现要更多，不该被前一个值卡住）；
 * - 最终一律过 `clampToolRounds`（1..15）。
 */
object ToolRounds {

    /** 工具参数名（协议字段，两端一致）。 */
    const val ARGUMENT = "rounds"

    /** 默认值（模型没指定）：5 轮。 */
    val DEFAULT: Int = ExperienceContract.DEFAULT_TOOL_ROUNDS

    /** 硬上限：15 轮。 */
    val MAX: Int = ExperienceContract.MAX_TOOL_ROUNDS

    /** 下限：1 轮。 */
    val MIN: Int = ExperienceContract.MIN_TOOL_ROUNDS

    /**
     * 解析模型写在参数里的轮数：认 `12`、`"12"`、`" 12 "`、`"12.0"`；
     * 空/坏值/非数字返回 null（= 用默认值）。
     */
    fun parse(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toIntOrNull()?.let { return it }
        // 模型偶尔写 12.0 / 12 轮 / rounds=12。
        Regex("(-?\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    /** 夹取一个「可能为空」的指定值；null → 默认值。 */
    fun resolve(requested: Int?): Int = ExperienceContract.clampToolRounds(requested)

    /**
     * 一轮里看到新的指定值时，更新这一轮的轮数上限：取两者中较大的（但都先夹到 1..15）。
     *
     * 为什么不取「最后一次指定的值」：工具调用是模型边做边写的，先要 8 轮、后来
     * 某个调用忘了带参数，不该把预算缩回 5 轮——那会让它做到一半被切断。
     */
    fun merge(current: Int, requested: Int?): Int {
        val safeCurrent = resolve(current)
        val next = requested ?: return safeCurrent
        return maxOf(safeCurrent, resolve(next))
    }

    /** 方便调用方从参数表里直接取：`ToolRounds.requested(arguments)`。 */
    fun requested(argument: String?): Int? = parse(argument)

    /**
     * 从一次工具调用里取 rounds：**数字与字符串都认**。
     *
     * 模型原生 tool_calls 里通常写数字（`"rounds": 8`），文本代码块里可能写字符串；
     * 只读字符串会漏掉最常见的那种，于是「模型指定了轮数但没生效」。
     */
    fun requested(invocation: ToolInvocation): Int? =
        invocation.optionalIntArgument(ARGUMENT) ?: parse(invocation.argument(ARGUMENT))
}
