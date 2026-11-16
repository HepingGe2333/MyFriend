package com.yap.app.domain

/**
 * 用户点「停止」时，那一轮回复该不该落库（审计 P2-8）。
 *
 * 以前的条件是 `shownText.isNotBlank() || traces.isNotEmpty()`：多轮工具调用期间
 * `shownText` 会被清空，于是 `traces` 非空、正文为空 → 落一条 `content=""`、
 * `status=STOPPED` 的助手消息。界面上它既没有正文（`content.isNotBlank()` 才渲染）
 * 也没有工具痕迹（只在 `status == COMPLETE` 时渲染），就是一个空气泡；
 * 模型上下文也会因为 `content.isBlank()` 把它排除，所以纯粹是视觉垃圾。
 *
 * 现在的三分：
 *
 * - [Outcome.DISCARD]：没有正文也没有工具痕迹（这一轮什么都没发生）→ 不写；
 * - [Outcome.STATUS_ONLY]：只有工具痕迹、没有正文 → 写一条**状态占位**（界面按资源显示
 *   「已停止」，正文留空，不污染模型上下文）；
 * - [Outcome.SAVE_TEXT]：有正文 → 老行为（存半截话）。
 *
 * 注意「正文为空」只把纯空白算空：模型可能先输出几个空格再被停掉。
 */
object InterruptedReply {

    enum class Outcome {
        /** 什么都没有：不落库。 */
        DISCARD,

        /** 只有工具调用：落一条正文为空的状态消息（界面显示「已停止」）。 */
        STATUS_ONLY,

        /** 有可见正文：照常存半截回复。 */
        SAVE_TEXT
    }

    /** 决定这次中断要不要落库、以什么形式落库。 */
    fun decide(visibleText: String, hasToolTraces: Boolean): Outcome = when {
        visibleText.isNotBlank() -> Outcome.SAVE_TEXT
        hasToolTraces -> Outcome.STATUS_ONLY
        else -> Outcome.DISCARD
    }

    /** 界面要不要为这条消息渲染「已停止」占位（正文为空 + 状态 STOPPED）。 */
    fun showsStatusPlaceholder(content: String?, isStopped: Boolean): Boolean =
        isStopped && content.isNullOrBlank()
}
