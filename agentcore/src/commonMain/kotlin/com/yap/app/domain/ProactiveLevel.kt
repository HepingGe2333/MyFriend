package com.yap.app.domain

/**
 * 主动程度：设置页里那一格「每天最多主动几次」被拿掉之后，这件事交回给人格。
 *
 * 1–5 各有一个人话解释，同时换算成两个真东西：
 * - 每日主动上限：1–5 次（心情还会在 [MoodBehavior] 里挪一格）；
 * - 心跳权重：乘在「想不想找你」的概率上。1 的人半年不主动也正常，5 的人会明显地黏。
 *
 * 纯函数，边界收在这里，免得 UI、心跳、AgentLoop 各写一份 1..5 的夹取逻辑。
 */
object ProactiveLevel {

    const val MIN = 1
    const val MAX = 5
    const val DEFAULT = 3

    fun normalize(level: Int): Int = level.coerceIn(MIN, MAX)

    /** 每天最多主动开口几次。 */
    fun dailyCap(level: Int): Int = normalize(level)

    /**
     * 心跳概率权重：0.7 / 0.85 / 1.0 / 1.15 / 1.3。
     *
     * 不做到 0 也不做到 3 倍：再冷淡的人也会有忽然想起你的时候，
     * 再黏的人也不该变成推送机器。这两个边界由 [Heartbeat] 自己再夹一次。
     */
    fun heartbeatWeight(level: Int): Double = when (normalize(level)) {
        1 -> 0.70
        2 -> 0.85
        3 -> 1.0
        4 -> 1.15
        else -> 1.30
    }

    /**
     * 编辑页滑杆下面那句人话，和 iOS 必须一字不差。
     *
     * 这句是跨端 spec 的一部分（见 `spec/AgentCoreSpecTest`），也是 `domain/` 里唯一的一份：
     * 界面显示走 `ui/screens/persona/ProactiveLevelText.kt` 的资源版本，那边中文与此逐字一致、英文在 values-en。
     */
    fun description(level: Int): String = when (normalize(level)) {
        1 -> "很少主动。你不找他，他基本不会来打扰。"
        2 -> "偶尔想起你。忙起来就顾不上了。"
        3 -> "会主动，但有分寸。大多数日子会来找你说一句话。"
        4 -> "比较黏人。隔一阵子就想知道你在做什么。"
        else -> "很黏你。一天里会好几次想找你说话。"
    }
}
