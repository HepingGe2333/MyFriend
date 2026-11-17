package com.yap.app.domain

/**
 * 「已读不回」（`stay_silent`，`spec/mood/README.md` §4）。
 *
 * 与 `defer_reply` 的分工：
 * - `defer_reply` = 「过一会儿再回」：**会留下一个念头**，到点他会自己想起来；
 * - `stay_silent` = 「这一轮不回」：**什么都不留**，只把对方的消息标成已读。
 *
 * 为什么需要连续限制：沉默是一种很重的表达。连着两轮不回，用户看到的是「他不想理我」；
 * 真实的人也会在第三轮给一句「嗯」或者「晚点说」。所以规范钉死「连续不超过 2 轮」——
 * 第 3 次要么强制回一句（模型读到拒绝结果后自己接着说），要么这次调用被忽略。
 *
 * 这里是纯函数：计数怎么涨、什么时候拒绝、拒绝时怎么跟模型说话，都在这一处，
 * 数据层只负责把计数存进设置（[com.yap.app.data.prefs.SettingsRepository]）。
 */
object StaySilent {

    /** 连续「已读不回」的上限（规范 §4：不要连续使用超过 2 轮）。 */
    const val MAX_CONSECUTIVE: Int = 2

    /** 这一轮还允许沉默吗：已经连续沉默 [MAX_CONSECUTIVE] 轮就不允许。 */
    fun allowed(previousStreak: Int): Boolean = previousStreak.coerceAtLeast(0) < MAX_CONSECUTIVE

    /** 沉默成功之后的新计数（封顶，不会越涨越大）。 */
    fun nextStreak(previousStreak: Int): Int =
        (previousStreak.coerceAtLeast(0) + 1).coerceAtMost(MAX_CONSECUTIVE)

    /**
     * 允许沉默时回灌给模型的话。
     *
     * 必须明确写「不要再输出任何文字」：模型很容易在调用工具之后补一句「那我先忙了」，
     * 那句话会变成一条 assistant 消息，于是「已读不回」就失效了。
     */
    fun allowedModelText(reason: String): String = buildString {
        append("好，这一轮不回。不要再输出任何文字，也不要解释；对方只会看到「已读」，")
        append("这本身就已经是你的回答。")
        if (reason.isNotBlank()) append("（你不想说话的原因：${reason.take(40)}，只给自己看。）")
    }

    /** 不允许沉默时回灌给模型的话：明确要求这一轮说一句，而不是把调用当失败反复重试。 */
    fun deniedModelText(): String =
        "你已经连续两轮没有回了。这一轮不能再沉默：用你自己的语气回一句，哪怕很短（「嗯」「在忙，晚点说」）。" +
            "不要解释你为什么之前没回，也不要提这次调用。对方在等一个回应。"

    /** 落进工具摘要的一句话（回灌给模型看，不直接展示）。 */
    fun summary(reason: String): String =
        if (reason.isBlank()) "这一轮不回" else "这一轮不回：${reason.take(20)}"
}
