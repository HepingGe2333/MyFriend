package com.yap.app.domain

/**
 * 设置页「测试生成」的纯逻辑（0.15）。
 *
 * 用当前人格/当前 API 配置发一条测试消息，**不写进任何会话**，只显示：
 * 耗时、字数、有没有调用工具、模型的回答（截断预览）。
 *
 * 为什么要在设置页做这件事：用户改了提示词/温度/模型之后，唯一的验证方式原本是
 * 回到聊天页发一句真正的消息——那条消息会进历史、会触发记忆抽取、会算进上下文。
 * 「测试生成」只借用配置，不留痕迹。
 *
 * 这个对象只负责**度量与判定**（纯函数），网络调用在
 * `data/repo/GenerationTestRepository`；界面文案走 `R.string.settings_generation_*`。
 */
object GenerationProbe {

    /** 测试请求的输入（由界面/仓库填）。 */
    data class Request(
        /** 发给模型的测试消息（用户可见输入框里的内容；空时用 [DEFAULT_PROBE_MESSAGE]）。 */
        val message: String = DEFAULT_PROBE_MESSAGE,
        /** 温度用配置里的值；测试时通常压到 0.6，别让一次测试跑飞。 */
        val temperature: Double = 0.6,
        /** 测试输出上限：只要看「他会不会说话」，不需要 16K。 */
        val maxOutputTokens: Int = 400
    )

    /** 一次测试的结果。 */
    data class Result(
        /** 开始到收到完整回答的毫秒数。 */
        val elapsedMs: Long,
        /** 模型回答的字符数（去掉工具块后的可见正文）。 */
        val charCount: Int,
        /** 这一轮里模型有没有调用工具（工具块里的调用会被剥掉，只留计数）。 */
        val toolCalls: Int,
        /** 剥掉工具块之后的回答正文。 */
        val text: String,
        /** 出错的稳定文案（给 UI 映射资源）；成功为 null。 */
        val errorToken: String? = null
    ) {
        val ok: Boolean get() = errorToken == null && charCount > 0

        /** 回答的预览（最多 [PREVIEW_CHARS] 字）。 */
        val preview: String
            get() = if (text.length <= PREVIEW_CHARS) text else text.take(PREVIEW_CHARS).trimEnd() + "…"

        /** 耗时显示：`1.8 秒`。 */
        val elapsedText: String
            get() = String.format(java.util.Locale.ROOT, "%.1f 秒", elapsedMs.coerceAtLeast(0L) / 1000.0)
    }

    /** 默认测试消息：像用户随口说一句，不夹带指令。 */
    const val DEFAULT_PROBE_MESSAGE = "在吗？随便说句话给我听听。"

    /** 回答预览最多多少字（界面上不刷屏）。 */
    const val PREVIEW_CHARS = 160

    /** 出错令牌（UI 映射到具体资源，不直接把异常甩给用户）。 */
    const val TOKEN_EMPTY = "empty"
    const val TOKEN_FAILED = "failed"
    const val TOKEN_TOOL_ONLY = "tool_only"

    /**
     * 从模型的原始输出里量出结果：剥掉工具块、数工具调用、算字数。
     *
     * @param raw 模型返回的原始文本（可能含 ```yap-tool 工具块）
     * @param elapsedMs 耗时（毫秒）
     */
    fun measure(raw: String, elapsedMs: Long): Result {
        val text = raw.trim()
        if (text.isEmpty()) {
            return Result(
                elapsedMs = elapsedMs.coerceAtLeast(0L),
                charCount = 0,
                toolCalls = 0,
                text = "",
                errorToken = TOKEN_EMPTY
            )
        }
        val (body, calls) = ToolProtocol.extract(text)
        val visible = body.trim().ifBlank {
            // 只有工具调用、没有正文：仍然算「成功跑通」，但提示「这一轮只做了动作」。
            if (calls.isNotEmpty()) "" else text
        }
        val count = visible.length
        return Result(
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            charCount = count,
            toolCalls = calls.size,
            text = visible,
            errorToken = if (count == 0 && calls.isNotEmpty()) TOKEN_TOOL_ONLY else null
        )
    }

    /**
     * 请求前的静态校验：没有配置/没有人格时不必发请求。
     *
     * @param hasPersona 当前有没有人格
     * @param hasConfig 当前人格有没有可用的 API 配置
     */
    fun preflight(hasPersona: Boolean, hasConfig: Boolean): String? = when {
        !hasPersona -> TOKEN_FAILED
        !hasConfig -> TOKEN_FAILED
        else -> null
    }
}
