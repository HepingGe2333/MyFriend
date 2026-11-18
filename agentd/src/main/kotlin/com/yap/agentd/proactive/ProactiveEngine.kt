package com.yap.agentd.proactive

import com.yap.app.domain.Heartbeat
import java.time.Clock
import kotlin.random.Random

/**
 * 主动会话的判定引擎：一拍只回答一个问题 ——「**现在**要不要主动找他」。
 *
 * 规则全部沿用 `spec/agent-core` 的语义（`:agentcore` 里已经有唯一实现，这里只是把它们按
 * 服务端的顺序串起来）：
 *
 * | 顺序 | 规则 | 依据 |
 * | --- | --- | --- |
 * | 1 | 主动消息被关掉 → 不开口 | 人格/全局开关 |
 * | 2 | 睡眠窗口内 + 免打扰 → 不开口，等醒来 | `SleepPolicy` / `spec/agent-core/cases/sleep-decision.json` |
 * | 3 | 睡眠窗口内（没被叫醒）→ 不开口，等醒来 | 同上 |
 * | 4 | **正在忙**（活动 `BUSY` 且没到 `until`）→ 不开口，等忙完 | `spec/persona-guidance/README.md` §4 |
 * | 5 | 还没有互动起点 → 只记起点，不掷 | 客户端 `HeartbeatController` 的同一条规则 |
 * | 6 | 刚聊完 10 分钟内 → 根本不掷（不是概率低） | `Heartbeat.COOL_DOWN_MINUTES` |
 * | 7 | 今天主动次数用完 → 不开口 | `ProactiveLevel` + 心情修正（`InProcessAgentCore.proactiveCap`） |
 * | 8 | 算「这一拍想开口」的概率并掷骰子 | `Heartbeat.probability` × `ProactiveLevel.heartbeatWeight` × 活动权重 → `perBeatProbability` |
 * | 9 | 掷中 → 产出要落库的 [PendingProactive] | 正文由注入的 [ProactiveTextSource] 写 |
 *
 * 三个刻意的设计（与客户端一致）：
 * - **概率时钟的真源是「最近一次互动」**：用户刚说话 / 他刚说话 / 上次主动，取最新的那个。
 *   所以「被回复后重置」不需要额外分支 —— 回复本身就是一次互动，冷却期从那一刻重新开始；
 * - **先门槛后掷骰子**：睡着、说够了、刚聊完都不掷，既省 CPU，也不会让「他」显得不知分寸；
 * - **掷中才产出 pending**：没掷中不碰存储层，一拍的成本就是几次毫秒运算。
 *
 * 纯函数：时间来自注入的 [Clock]（或显式 [evaluate] 的 `now`），随机数来自注入的 `roll`，
 * 不读库、不联网、不看客户端连接 —— 这也正是「客户端没有后台进程也能主动」的前提。
 */
class ProactiveEngine(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val policy: ProactivePolicy = ProactivePolicy(),
    private val texts: ProactiveTextSource = ProactiveTextSource.DEFAULT,
    private val roll: () -> Double = { Random.nextDouble() }
) {

    /**
     * 判定一拍。
     *
     * @param now 要判定的时刻；默认取 [clock] 的当前时刻。显式传入是为了让「睡点正好 23:30」
     *            这种边界在测试里可钉、也让 `tickOnce(now)` 幂等
     */
    fun evaluate(
        persona: PersonaState,
        level: LevelPolicy = LevelPolicy(),
        now: Long = clock.millis()
    ): ProactiveDecision {
        val zone = clock.zone
        val tick = policy.tickIntervalMs

        // 1. 开关
        if (!level.enabled || !persona.enabled) {
            return decision(persona, ProactiveReason.DISABLED, now + tick)
        }

        // 2/3. 睡眠与免打扰。服务端收不到新消息，所以「有没有人被叫醒」只能从最后互动推
        // （30 分钟内有互动 = 1 条；够不够阈值仍由 SleepPolicy 决定）。
        val sleep = SleepState.derive(persona, now, zone)
        if (sleep.dnd) {
            return decision(persona, ProactiveReason.DND, sleep.nextAwakeAt)
        }
        if (sleep.asleep && !sleep.woken) {
            return decision(persona, ProactiveReason.ASLEEP, sleep.nextAwakeAt)
        }

        val cap = level.dailyCap(persona.mood)
        val sent = persona.proactiveCountToday.coerceAtLeast(0)

        // 4. 占用时间（`set_activity`）：他说了「接下来这段时间在忙」，这一拍就不开口。
        //    过期由 ActivityState.availabilityAt(now) 归成 FREE —— 不需要定时任务清理，
        //    所以「21:30 结束」的活动在 21:31 之后自动恢复正常节奏（迟到的 tick 也不会被旧数据卡住）。
        val activityAvailability = persona.activity?.availabilityAt(now) ?: ActivityAvailability.FREE
        if (activityAvailability == ActivityAvailability.BUSY) {
            val until = persona.activity?.until ?: 0L
            return decision(
                persona,
                ProactiveReason.BUSY,
                maxOf(until, now + tick),
                dailyCap = cap,
                sentToday = sent
            )
        }

        // 5. 概率时钟：最近一次互动 / 上次主动里最新的那个。
        val resetAt = Heartbeat.latestMark(persona.lastInteractionAt, persona.lastProactiveAt)
        if (resetAt <= 0L) {
            return decision(persona, ProactiveReason.NO_BASELINE, now + tick, dailyCap = cap, sentToday = sent)
        }

        // 6. 刚聊完先安静一会儿：这段时间压根不掷骰子。
        val minutes = Heartbeat.minutesBetween(resetAt, now)
        if (minutes < Heartbeat.COOL_DOWN_MINUTES) {
            val cooldownEndsAt = resetAt + (Heartbeat.COOL_DOWN_MINUTES * 60_000.0).toLong()
            return decision(
                persona,
                ProactiveReason.RECENT_INTERACTION,
                maxOf(cooldownEndsAt, now + tick),
                dailyCap = cap,
                sentToday = sent
            )
        }

        // 7. 今天说够了（上限含心情修正；0 表示今天不主动）。
        if (sent >= cap) {
            return decision(
                persona,
                ProactiveReason.DAILY_CAP,
                SleepState.nextLocalMidnight(now, zone),
                dailyCap = cap,
                sentToday = sent
            )
        }

        // 8. 概率：每分钟概率 × 主动程度权重 × 活动权重 → 本拍概率。
        val perBeat = beatProbability(minutes, persona, level, now)
        val rollValue = sanitizedRoll()
        if (!Heartbeat.shouldFire(perBeat, rollValue)) {
            // 在忙（LIMITED）时的「没掷中」要能被看见：概率被降到 35% 是原因之一，
            // 与纯运气的 not_this_tick 分开，客户端/日志才知道他为什么安静。
            val reason = if (activityAvailability == ActivityAvailability.LIMITED) {
                ProactiveReason.LIMITED
            } else {
                ProactiveReason.NOT_THIS_TICK
            }
            return decision(
                persona,
                reason,
                now + tick,
                probability = perBeat,
                roll = rollValue,
                dailyCap = cap,
                sentToday = sent
            )
        }

        // 9. 掷中：写正文、产出 pending。正文为空（LLM 失败/无配置）时这一拍不开口，
        //    **不回落到固定文案**：主动消息的真实感全部来自内容，发模板话比不发更伤人设。
        val text = texts.compose(persona, now).trim()
        if (text.isEmpty()) {
            return decision(
                persona,
                ProactiveReason.GENERATION_FAILED,
                now + tick,
                probability = perBeat,
                roll = rollValue,
                dailyCap = cap,
                sentToday = sent
            )
        }
        return decision(
            persona,
            ProactiveReason.FIRED,
            now,
            pending = PendingProactive(text = text, personaId = persona.personaId, createdAt = now),
            probability = perBeat,
            roll = rollValue,
            dailyCap = cap,
            sentToday = sent
        )
    }

    /**
     * 这一刻「想开口」的**本拍**概率：`Heartbeat.probability`（含心情放大与深夜折扣）
     * × `ProactiveLevel.heartbeatWeight` → `perBeatProbability` 换算成这一拍。
     *
     * 不做门槛判断（睡眠、冷却、每日上限都不看）：它只回答「如果现在掷骰子，中的概率多大」，
     * 给日志/诊断/契约测试用。唯一例外是**活动权重**（`LIMITED` → ×0.35、`BUSY` → 0）：
     * 那不是门槛，而是概率本身的一部分，所以必须和真正掷骰子时用的是同一个数。
     * 概率时钟仍是 [Heartbeat.latestMark]（最近一次互动或上次主动）。
     */
    fun impulseProbability(
        persona: PersonaState,
        level: LevelPolicy = LevelPolicy(),
        now: Long = clock.millis()
    ): Double {
        val resetAt = Heartbeat.latestMark(persona.lastInteractionAt, persona.lastProactiveAt)
        if (resetAt <= 0L) return 0.0
        return beatProbability(Heartbeat.minutesBetween(resetAt, now), persona, level, now)
    }

    /**
     * 概率的三步换算：分钟概率 → 主动程度权重 → **活动权重** → 本拍概率。
     *
     * gate 与 [impulseProbability] 共用这一份实现，所以「诊断用的概率」和「真正掷骰子的概率」
     * 不会漂。活动权重就一条规则（`README.md` §4）：
     * `LIMITED` → ×[ActivityState.LIMITED_PROACTIVE_FACTOR]；`BUSY` → 0（调用方已经提前返回，
     * 这里兜底）；`FREE`/过期/没设 → 1。
     */
    private fun beatProbability(
        minutes: Double,
        persona: PersonaState,
        level: LevelPolicy,
        now: Long
    ): Double {
        val perMinute = Heartbeat.probability(
            minutesSinceReset = minutes,
            mood = persona.mood.core(),
            now = now,
            hourOfDay = SleepState.hourOfDay(now, clock.zone)
        )
        val weighted = (perMinute * level.heartbeatWeight() * activityWeight(persona.activity, now))
            .coerceIn(0.0, Heartbeat.MAX_PROBABILITY)
        return Heartbeat.perBeatProbability(weighted, policy.tickIntervalMinutes)
    }

    /**
     * 活动带来的降权系数。
     *
     * `BUSY` 在 [evaluate] 的第 4 步就返回了；这里给 0 只是让 [impulseProbability]
     * 也不会对「正在忙」的人报一个假的想开口概率。
     */
    private fun activityWeight(activity: ActivityState?, now: Long): Double =
        when (activity?.availabilityAt(now)) {
            null, ActivityAvailability.FREE -> 1.0
            ActivityAvailability.LIMITED -> ActivityState.LIMITED_PROACTIVE_FACTOR
            ActivityAvailability.BUSY -> 0.0
        }

    /** 把注入的随机数收进 `[0,1)`：坏值（NaN/负数/1.0）按 0 处理（一定会掷中），不会静默变成「永不触发」。 */
    private fun sanitizedRoll(): Double {
        val value = roll()
        return when {
            !value.isFinite() -> 0.0
            value < 0.0 -> 0.0
            value >= 1.0 -> 0.999999999
            else -> value
        }
    }

    private fun decision(
        persona: PersonaState,
        reason: ProactiveReason,
        scheduledAt: Long,
        pending: PendingProactive? = null,
        probability: Double = 0.0,
        roll: Double = -1.0,
        dailyCap: Int = 0,
        sentToday: Int = 0
    ): ProactiveDecision = ProactiveDecision(
        shouldFire = reason == ProactiveReason.FIRED && pending != null,
        reason = reason,
        targetPersonaId = persona.personaId,
        scheduledAt = scheduledAt,
        pending = pending,
        probability = probability,
        roll = roll,
        dailyCap = dailyCap,
        sentToday = sentToday
    )
}
