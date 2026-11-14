// 来源：app/src/main/java/com/myfriend/app/domain/Heartbeat.kt
//
// 他的心跳：冲动什么时候来。
//
// 这不是「定时任务」，也不是「推送概率」。人在刚聊完、刚做完一件事的时候，心思是收回来的：
// 那会儿他并不是最想开口，反而是最安静的一段；随后这份安静慢慢散掉，一个人待着的空落落
// 才会一点点攒起来，攒到某个时候他忽然很想找你说句话。所以这条曲线是**先降后升**的：
//
//     p(t) = FLOOR + (INITIAL - FLOOR) * e^(-t/τ) + (CEILING - FLOOR) * ramp(t)
//
// - t 是「距离上一次互动/上一次冲动过了多少分钟」；
// - 前 COOL_DOWN_MINUTES 分钟只有指数衰减项：从 INITIAL 一路落到谷底（他在收尾，不想连轴说话）；
// - 之后 RAMP_MINUTES 内线性爬升：一个人待越久，越可能想起你；
// - RAMP_MINUTES 之后停在上限 CEILING：再久也就是「很想」，不会再涨到「一定会」。
//
// **p(t) 是「这一分钟里他突然想起你」的概率**，不是「这一拍」的概率。
// 心跳每 30 秒掷一次骰子，所以真正用的是 perBeatProbability 把分钟概率换算成本拍概率
// （`1 − (1 − p)^Δ`）——否则 p(0)=0.35 每 30 秒掷一次，等于一分钟内六成会触发，
// 「先降后升」的收尾期在采样层面根本不存在。另外 COOL_DOWN_MINUTES 之内干脆不掷：
// 刚聊完就该安静一会儿，这是他自己也认的。
//
// 情绪会放大它：心情浓（强度 ≥ 3）且不是中性时整体乘 1.15~1.55 —— 高兴和难过都算，
// 因为这两种情绪都会让人更想找个人说说话；平静时不放大。深夜（0~5 点）整体减半：他也要睡。
//
// 这个文件是纯算法：不碰数据库 / 网络，时间与随机数全部由参数传进来，
// 所以曲线形状可以一条一条断言，而不是靠观测。

import Foundation

/// 心跳的状态：概率时钟上一次被重置的时刻，以及上一次真的冲动了一回的时刻。
public struct HeartbeatState: Equatable {
    /// 概率时钟上一次被重置的时刻。
    ///
    /// 「重置」只发生在两种情况下：一次互动（他说了话 / 你说了话），或者一次真的冲动触发。
    /// 值为 0 表示还没有记录（比如刚装上、还没跟他说过话）。
    public var lastResetAt: Int64

    /// 上一次他真的因为这股冲动想了一回的时刻；nil = 还没发生过。
    public var lastFiredAt: Int64?

    public init(lastResetAt: Int64 = 0, lastFiredAt: Int64? = nil) {
        self.lastResetAt = lastResetAt
        self.lastFiredAt = lastFiredAt
    }
}

/// 心跳曲线的全部常量与换算。
public enum Heartbeat {

    // MARK: 曲线数值

    /// 刚从一次互动里回来时的概率。
    ///
    /// 不是最高：他刚说完话，人是收回来的。0.35 意味着大约每三拍里有一拍会「想起」，
    /// 但接下来十分钟会迅速降下去，所以不会连着说话。
    public static let INITIAL = 0.35

    /// 谷底：刚互动完十分钟前后，他基本不想开口（约 0.115）。
    public static let FLOOR = 0.10

    /// 上限：一个人待了三个小时以后，很想，但也不会「一定」找你。
    public static let CEILING = 0.85

    /// 收尾期：10 分钟。
    ///
    /// 人刚聊完的那股热乎劲大约十分钟就散了；太短会显得他马上又贴上来，太长会显得他冷淡。
    public static let COOL_DOWN_MINUTES = 10.0

    /// 爬升期：10 → 180 分钟。
    ///
    /// 三小时是「一个人待着开始有点空」的自然尺度：一小时太急，半天又太钝。
    public static let RAMP_MINUTES = 180.0

    /// 收尾期的指数时间常数（分钟）。
    ///
    /// 取 3.5 让「刚聊完」的那点余温在前十分钟里掉掉九成以上，
    /// 曲线因此有一个清晰的谷，而不是一条糊成一片的斜坡。
    static let COOL_TAU_MINUTES = 3.5

    /// 深夜的折扣：他也要睡。
    public static let NIGHT_FACTOR = 0.5

    /// 算作「深夜」的小时：0 点到 5 点。
    public static let NIGHT_HOURS = 0...5

    /// 心情要多浓才开始放大冲动：3 / 5。
    public static let MOOD_MIN_INTENSITY = 3

    /// 情绪放大的下限与上限：淡淡的浓是 1.15，满脑子都是最多 1.55。
    public static let MOOD_BOOST_MIN = 1.15
    public static let MOOD_BOOST_MAX = 1.6

    /// 每档浓强度多放大 0.2：强度 3 → 1.15，4 → 1.35，5 → 1.55。
    static let MOOD_BOOST_STEP = 0.2

    /// 概率的夹取范围：再低也留一点念想，再高也不到「一定」。
    public static let MIN_PROBABILITY = 0.02
    public static let MAX_PROBABILITY = 0.9

    // MARK: 概率

    /// 此刻「这股冲动来了」的概率。
    ///
    /// - Parameters:
    ///   - minutesSinceReset: 距离上一次互动/上一次冲动多少分钟（负数、NaN 一律当作 0）
    ///   - mood: 他此刻的心情（强度、极性一起决定放大多少）
    ///   - now: 现在（毫秒）；只为以后可能的「一天里的起伏」留着，目前不参与计算
    ///   - hourOfDay: 现在几点（0..23）；0~5 点整体减半
    /// - Returns: 夹在 [MIN_PROBABILITY, MAX_PROBABILITY] 之间的概率，绝不会是 NaN 或负数
    public static func probability(
        minutesSinceReset: Double,
        mood: MoodState = MoodState(),
        now: Int64 = 0,
        hourOfDay: Int = 12
    ) -> Double {
        let t = sanitizeMinutes(minutesSinceReset)
        let base = FLOOR
            + (INITIAL - FLOOR) * exp(-t / COOL_TAU_MINUTES)
            + (CEILING - FLOOR) * rampProgress(t)
        let withMood = base * moodBoost(mood)
        let withNight = isNightHour(hourOfDay) ? withMood * NIGHT_FACTOR : withMood
        // 双重保险：任何一步算歪（NaN/Inf）都回落到谷底，而不是把 NaN 丢给随机数比较。
        if !withNight.isFinite { return FLOOR }
        return min(Swift.max(withNight, MIN_PROBABILITY), MAX_PROBABILITY)
    }

    /// 心情带来的放大倍数。
    ///
    /// - 平静、空、认不出的词：1.0（不猜他的心情，也就不改他的节奏）；
    /// - 强度不到 3：1.0（淡淡的情绪不值得让他更主动）；
    /// - 强度 ≥ 3 且极性和劲头都不为 0：1.15 ~ 1.55（高兴、难过、想念都算「心里不静」）。
    ///
    /// 为什么正面情绪也放大：一个人很开心的时候同样憋不住想找人分享；
    /// 这不是「负面情绪才需要安慰」的补偿逻辑。
    public static func moodBoost(_ mood: MoodState) -> Double {
        if mood.isEmpty() { return 1.0 }
        let intensity = min(Swift.max(mood.intensity, 1), 5)
        if intensity < MOOD_MIN_INTENSITY { return 1.0 }
        let word = MoodLexicon.classify(mood.label)
        if word.valence == 0 && word.energy == 0 { return 1.0 }
        let boost = MOOD_BOOST_MIN + MOOD_BOOST_STEP * Double(intensity - MOOD_MIN_INTENSITY)
        return min(Swift.max(boost, MOOD_BOOST_MIN), MOOD_BOOST_MAX)
    }

    /// 是不是深夜（0 点到 5 点）。乱传的小时数当作白天，宁可他多想起你一次。
    public static func isNightHour(_ hourOfDay: Int) -> Bool {
        NIGHT_HOURS.contains(hourOfDay)
    }

    /// 把「每分钟的概率」换算成「这一拍的概率」。
    ///
    /// 心跳是离散采样的：30 秒拍一次、两分钟拍一次（低电量）都算同一件事的近似。
    /// 用 `1 − (1 − p)^Δ` 而不是 `p × Δ`：多次独立机会的并集才是「这一分钟里发生」的概率，
    /// 并且 Δ 越大本拍概率越高、但一分钟总概率始终等于 p（不会因为拍得频就更容易触发）。
    ///
    /// - Parameters:
    ///   - perMinute: 每分钟的概率（`probability` 的输出）
    ///   - intervalMinutes: 这一拍覆盖多少分钟；坏值退回 0.5 分钟（默认节拍）
    public static func perBeatProbability(perMinute: Double, intervalMinutes: Double) -> Double {
        if !perMinute.isFinite || perMinute <= 0.0 { return 0.0 }
        let minutes = (intervalMinutes.isFinite && intervalMinutes > 0.0) ? intervalMinutes : 0.5
        let perMinuteClamped = min(Swift.max(perMinute, 0.0), MAX_PROBABILITY)
        let probability = 1.0 - pow(1.0 - perMinuteClamped, minutes)
        if !probability.isFinite { return perMinuteClamped }
        return min(Swift.max(probability, 0.0), 1.0)
    }

    /// 这一拍覆盖多少分钟（30 秒 → 0.5）。
    public static let BEAT_MINUTES: Double = 0.5

    /// 冲动的「这一拍要不要动」：掷出的随机数落在概率之内才算。
    ///
    /// 随机数一律由调用方传进来（`roll`），算法自己不碰 Random：这样曲线与判定都能复现。
    public static func shouldFire(probability: Double, roll: Double) -> Bool {
        if !probability.isFinite || probability <= 0.0 { return false }
        // roll 必须是 [0,1) 里的一个数：0.0 一定落在任何正概率之内，1.0 永远落不进去。
        if !roll.isFinite || roll < 0.0 || roll >= 1.0 { return false }
        return roll < probability
    }

    // MARK: 时钟

    /// 一次互动（或一次真的冲动）之后，概率时钟回到起点。
    ///
    /// 只动 `lastResetAt`；`fired` 为 true 时顺手记下这次是真的想了。
    /// 时钟回拨（now <= 0 或早于原来的重置点）时保持原值，宁可不动，也别把曲线甩到一个荒唐的位置。
    public static func nextReset(_ state: HeartbeatState, now: Int64, fired: Bool = false) -> HeartbeatState {
        if now <= 0 { return state }
        let resetAt = now > state.lastResetAt ? now : state.lastResetAt
        return HeartbeatState(lastResetAt: resetAt, lastFiredAt: fired ? now : state.lastFiredAt)
    }

    /// 最近一次「有动静」的时刻：几个时间戳里最大的那个，全都没有就是 0。
    ///
    /// 调用方用它把「用户刚说话」「他刚说话」「上次冲动」和持久化的重置点合成一个真源，
    /// 这样不必去改聊天仓库，也不会漏掉某一种互动。
    public static func latestMark(_ marks: Int64?...) -> Int64 {
        var latest: Int64 = 0
        for mark in marks {
            latest = Swift.max(latest, Swift.max(mark ?? 0, 0))
        }
        return latest
    }

    /// 距离上次重置过了多少分钟；负数（时钟回拨）、没记录一律当作 0。
    public static func minutesBetween(lastResetAt: Int64, now: Int64) -> Double {
        if lastResetAt <= 0 || now <= 0 { return 0.0 }
        let delta = now - lastResetAt
        if delta <= 0 { return 0.0 }
        return Double(delta) / 60_000.0
    }

    // MARK: 内部

    /// 0 → 谷底之后的那段直线；到 RAMP_MINUTES 为止，之后停在 1。
    static func rampProgress(_ t: Double) -> Double {
        if t <= COOL_DOWN_MINUTES { return 0.0 }
        let span = RAMP_MINUTES - COOL_DOWN_MINUTES
        if span <= 0.0 { return 1.0 }
        return min(Swift.max((t - COOL_DOWN_MINUTES) / span, 0.0), 1.0)
    }

    /// 把坏输入收进一个能算的区间：NaN/负 → 0，无穷大 → 一个足够大的有限值。
    static func sanitizeMinutes(_ value: Double) -> Double {
        if value.isNaN { return 0.0 }
        if value < 0.0 { return 0.0 }
        if value > RAMP_MINUTES * 100 { return RAMP_MINUTES * 100 }
        return value
    }
}
