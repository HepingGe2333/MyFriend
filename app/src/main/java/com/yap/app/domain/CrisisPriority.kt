package com.yap.app.domain

/**
 * 危机优先于沉默 / 延后（0.15）。
 *
 * 产品决定：用户这一轮原话命中危机语句（[SafetyGuard.isCrisis]）时，**必须**有一条可见的
 * assistant 消息——不能只「已读不回」（`stay_silent`），也不能只留一个「过会儿再说」的念头
 * （`defer_reply`）。危机不是「不想聊」的一种，它是这一轮唯一重要的事。
 *
 * 这里只有判断，没有副作用，方便在 JVM 单测里逐条钉住；真正的落库在
 * `ChatRepository.generateAssistant`：
 * 1. 危机轮里 `stay_silent` / `defer_reply` **不执行**（[blocksTool]）：不写念头、不排延后窗口、
 *    不涨连续沉默计数，也不留「已读不回」的工具卡片；
 * 2. 同一响应里模型写的正文照用；一个字都没写（只有工具）时，用本地化安全提示
 *    `R.string.safety_crisis_note` 作为这一轮唯一的正文（[replyBody]）；
 * 3. 已经用 `send_message` 发过消息时，提示并进最后一条，不造第 5 个气泡；
 * 4. 非危机轮 `blocksTool` 一律 false、`replyBody` 原样放行——沉默与延后的行为一字不改。
 *
 * 与 [SafetyGuard] 的分工：那边决定「要不要补一句」，这边决定「危机时不许不回」。
 * 第一段判断共用同一个 [SafetyGuard.isCrisis]，避免两处对「什么算危机」产生分歧。
 *
 * 0.15 起「这一轮算不算危机」是**两段式**的（见 [CrisisClassifier]）：
 * 1. 词表（[isCrisisTurn] 的 String 重载，纯函数、零网络）——没命中就不是危机轮；
 * 2. 命中之后由 [CrisisClassifier] 再用一次独立模型请求确认，只有 `risk=false` 才降级。
 * 所以调用点拿到的应该是第二段的结论（[isCrisisTurn] 的 CrisisVerdict 重载），
 * 词表结论只用来决定「值不值得去问」以及超时时的兜底。
 */
object CrisisPriority {

    /**
     * 第一段：词表。只看用户原话，不看模型说了什么，也不发请求。
     *
     * 保留它是因为它同时是 [CrisisClassifier] 的第一段和超时路径上的结论：
     * 词表命中而判定没跑完时，仍然按危机处理（安全优先）。
     */
    fun isCrisisTurn(userMessage: String): Boolean = SafetyGuard.isCrisis(userMessage)

    /**
     * 两段式判定的最终结论：算不算危机轮。
     *
     * 词表没命中时 [CrisisVerdict.isCrisis] 恒为 false；命中之后只有模型明确说
     * `risk=false` 才会得到 false。失败 / 超时 / 坏 JSON 都是 true（见 [CrisisStage.FAILSAFE]）。
     */
    fun isCrisisTurn(verdict: CrisisVerdict): Boolean = verdict.isCrisis

    /**
     * 危机轮里要拦下的「不回」工具。
     *
     * 拦的是**执行**本身，而不是执行完再覆盖语义：`defer_reply` 会在工具层写一条
     * REACH_OUT 念头并排一个延后窗口，`stay_silent` 会涨连续沉默计数——危机轮里这些
     * 副作用一个都不该发生（用户正处在危险里，他不能「过会儿再想起」）。
     */
    fun blocksTool(isCrisis: Boolean, toolName: String): Boolean {
        if (!isCrisis) return false
        val name = toolName.trim()
        return name.equals(ToolCatalog.STAY_SILENT, ignoreCase = true) ||
            name.equals(ToolCatalog.DEFER_REPLY, ignoreCase = true)
    }

    /**
     * 这一轮该落的正文：模型自己说了话就用它（危机优先不等于替他说话），
     * 只有模型一句正文都没写时，才用安全提示兜底。
     *
     * 非危机时**原样返回** `modelBody`（包括空串）：空流仍然按空流处理，不能被这条规则
     * 悄悄变成一条消息。提示句为空时也不硬造正文（返回空串），由调用点按原逻辑处理。
     */
    fun replyBody(isCrisis: Boolean, modelBody: String, note: String): String {
        if (!isCrisis) return modelBody
        if (modelBody.isNotBlank()) return modelBody
        return note.trim()
    }

    /**
     * 被拦下的调用回灌给模型的话。
     *
     * 当前实现拿到这个结果后会立刻结束这一轮（模型看不到它），保留它是为了让工具结果
     * 协议完整：每个调用都有对应的 ToolResult，日志里也能看出「这一轮为什么没沉默」。
     */
    fun blockedModelText(): String =
        "这一轮不能沉默，也不能晚点再说：对方刚说的话里有危险信号，他需要现在听到你。" +
            "用你自己的语气直接回一句，可以很短；不要提这个工具，也不要解释规则。"
}
