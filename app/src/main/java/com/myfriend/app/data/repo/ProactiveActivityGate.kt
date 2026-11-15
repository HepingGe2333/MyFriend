package com.myfriend.app.data.repo

import com.myfriend.app.domain.ActivityAvailability
import com.myfriend.app.domain.PersonaActivity
import com.myfriend.app.domain.PersonaActivityCodec

/**
 * 定时主动（`proactive_rules`）的活动状态门禁（0.12 遗留缺陷 3）。
 *
 * 背景：`HeartbeatController` 在「常驻循环」那条路上已经检查了 `activityJson` 的
 * `availability`，但 `ProactiveRepository.fire`（Worker 的定时规则路径）只检查了作息
 * （`personaCardRepository.isAsleep`）。结果：人格说「正在开会（BUSY）」，定时规则仍然会
 * 到点让他开口；`LIMITED` 也完全不降概率。
 *
 * 规则只有一份（与 `HeartbeatController` 对齐）：
 * - **FREE / 没有活动 / 活动过期** → 正常触发（过期活动由 [PersonaActivity.availabilityAt] 归为 FREE）；
 * - **BUSY** → 本次跳过，顺延到活动结束时刻（`until`，那时自然变成 FREE）；
 * - **LIMITED** → 按 [PersonaActivity.LIMITED_PROACTIVE_FACTOR] 降概率；没掷中则跳过，
 *   并在「活动结束」与「一个较短重试间隔」取较早者再看一眼（不把当天剩下的机会一次睡掉）。
 *
 * 为什么做成纯函数：Worker 的那条路径要真的跑模型/数据库，纯 JVM 单测很难构造整条依赖链；
 * 把判定抽出来以后，`roll` 可以注入，BUSY/LIMITED/过期三种边界都能逐条钉住。
 */
internal data class ProactiveActivityGateResult(
    val block: ProactiveActivityBlock = ProactiveActivityBlock.NONE,
    /** 被挡住时建议的下一次触发时刻（毫秒）；[ProactiveActivityBlock.NONE] 时无意义。 */
    val resumeAt: Long = 0L
) {
    val shouldProceed: Boolean get() = block == ProactiveActivityBlock.NONE
}

internal enum class ProactiveActivityBlock {
    /** 不挡：FREE、没设活动、活动过期或 LIMITED 掷中了。 */
    NONE,
    /** BUSY：本次跳过，顺延到活动结束。 */
    BUSY,
    /** LIMITED：本次按降权后的概率没掷中，稍后再看。 */
    LIMITED
}

internal object ProactiveActivityGate {

    /** LIMITED 没掷中时的重试间隔：比他「忙完」更早时也保留一次机会。 */
    const val LIMITED_RETRY_DELAY_MS: Long = 30 * 60_000L

    /** 活动状态检查的纯函数入口。坏 JSON / 空值一律视为「没有活动」（不挡主动消息）。 */
    fun evaluate(activityJson: String?, now: Long, roll: Double): ProactiveActivityGateResult {
        val activity = PersonaActivityCodec.decode(activityJson) ?: return ProactiveActivityGateResult()
        return when (activity.availabilityAt(now)) {
            // 过期活动已经由 availabilityAt 归成 FREE：一个结束了的忙碌不该继续影响主动消息。
            ActivityAvailability.FREE -> ProactiveActivityGateResult()

            ActivityAvailability.BUSY -> ProactiveActivityGateResult(
                block = ProactiveActivityBlock.BUSY,
                resumeAt = activity.until
            )

            ActivityAvailability.LIMITED -> {
                // 注入的随机源可能被测试写成 NaN/越界：坏值一律按「不掷中」处理（保守，不打扰）。
                val safeRoll = if (roll.isFinite() && roll in 0.0..<1.0) roll else 1.0
                if (safeRoll < PersonaActivity.LIMITED_PROACTIVE_FACTOR) {
                    ProactiveActivityGateResult()
                } else {
                    ProactiveActivityGateResult(
                        block = ProactiveActivityBlock.LIMITED,
                        // 最晚到活动结束；活动还很久就 30 分钟后再看一次（LIMITED 仍有概率被触发）。
                        resumeAt = minOf(activity.until, now + LIMITED_RETRY_DELAY_MS)
                    )
                }
            }
        }
    }
}
