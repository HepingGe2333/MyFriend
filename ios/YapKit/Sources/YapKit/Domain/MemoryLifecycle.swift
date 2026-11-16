// 来源：app/src/main/java/com/yap/app/domain/MemoryLifecycle.kt
//
// 记忆的生命周期：时间让它变淡，被想起让它回血，太淡的沉下去，太旧又太淡的忘掉。
//
// 全部是纯函数：不碰数据库、不读时钟（`now` 由调用方传入），
// 所有阈值都是常量，坏数据（负数时间、NaN 分量、越界重要度）一律夹取而不抛异常。
//
// 为什么是「一天一小步」而不是一次算到底：
// 整理每天跑一次，一天最多掉 4~7 个百分点，用户几乎察觉不到哪条记忆是哪天消失的；
// 就算错过几次（手机没开机），单次整理也最多补算 `MAX_DECAY_DAYS` 天，
// 免得一次开机把半年的记忆一次性抹平。

import Foundation

/// 一条记忆在「抽屉」里的层位。
///
/// - `active`：还在用，正常进上下文；
/// - `dormant`：沉下去了 —— 不再优先被想起，但关键词命中时还能捞回来；
/// - `archived`：已归档 —— 永远不进上下文，数据还留着，将来「回忆」工具仍可能翻出来。
public enum MemoryTier: String, CaseIterable {
    case active = "ACTIVE"
    case dormant = "DORMANT"
    case archived = "ARCHIVED"

    /// 从数据库里的字符串解析；空 / 未知一律当作 ACTIVE。
    /// 宁可多留一条，也不要因为一个脏字符串把记忆误判成归档。
    public static func parse(_ raw: String?) -> MemoryTier {
        let key = (raw ?? "").trimmed.uppercased()
        return MemoryTier(rawValue: key) ?? .active
    }
}

/// 生命周期的纯视图：只带衰减/归档需要的字段，不依赖数据库实体，便于单测。
///
/// 时间戳都是毫秒；`0` 表示「没有这个时间」，任何入口都不会因为 0 或负数抛异常。
public struct MemoryLifecycleState {
    public var id: String
    public var tier: MemoryTier
    /// 用户亲手钉住的：永远不会被自动沉底/归档/遗忘。
    public var pinned: Bool
    /// 1..5，越界会被夹取。
    public var importance: Int
    /// 被提起过几次（聊天里被想起、被加固）。
    public var useCount: Int
    /// 上次被用（进上下文或被主动提起）的时间。
    public var lastUsedAt: Int64
    public var createdAt: Int64
    /// 当前分量 0..1，整理时会写回。
    public var strength: Double
    /// 上次被每日整理的时间；衰减按「距上次整理多久」算步长。
    public var lastConsolidatedAt: Int64
    /// 记忆抽屉：关于对方 / 关于他自己 / 共同经历。
    /// 三个抽屉的衰减速度不一样（见 `DRAWER_DECAY_*`）。
    /// 默认按 EVENT：旧数据没有抽屉时行为与以前完全一致，不会被这次改动「变短命」。
    public var drawer: MemoryDrawer

    public init(
        id: String,
        tier: MemoryTier = .active,
        pinned: Bool = false,
        importance: Int = 3,
        useCount: Int = 0,
        lastUsedAt: Int64 = 0,
        createdAt: Int64 = 0,
        strength: Double = 1.0,
        lastConsolidatedAt: Int64 = 0,
        drawer: MemoryDrawer = .event
    ) {
        self.id = id
        self.tier = tier
        self.pinned = pinned
        self.importance = importance
        self.useCount = useCount
        self.lastUsedAt = lastUsedAt
        self.createdAt = createdAt
        self.strength = strength
        self.lastConsolidatedAt = lastConsolidatedAt
        self.drawer = drawer
    }
}

/// 一次维护对这条记忆的处置。上层按类型真的去改库 / 删库。
public enum LifecycleAction: Equatable {
    /// 留在原层，只把新分量写回去。
    case keep(id: String)
    /// 往下一层：ACTIVE → DORMANT → ARCHIVED。
    case demote(id: String, to: MemoryTier, strength: Double)
    /// 又被人想起来了，回上一层（DORMANT → ACTIVE）。
    case promote(id: String, strength: Double)
    /// 彻底忘掉：上层要真的删库。
    case forget(id: String, reason: String)

    /// 被处置的那条记忆的 id，上层据此改库。
    public var id: String {
        switch self {
        case .keep(let id), .demote(let id, _, _), .promote(let id, _), .forget(let id, _):
            return id
        }
    }
}

/// 一次日常维护的结果：该怎么处置 + 应该写回的分量。
public struct LifecycleUpdate: Equatable {
    public var action: LifecycleAction
    public var strength: Double

    public init(action: LifecycleAction, strength: Double) {
        self.action = action
        self.strength = strength
    }
}

/// 记忆生命周期的全部常量与判定。
public enum MemoryLifecycle {

    public static let DAY_MILLIS: Int64 = 86_400_000

    // MARK: 阈值

    /// 低于这条线：先沉下去（不再优先被想起，但关键词还能捞回来）。
    public static let DORMANT_THRESHOLD = 0.45

    /// 再低于这条线：移出上下文（归档）。
    public static let ARCHIVE_THRESHOLD = 0.20

    /// 分量低到这条线以下、并且半年没人提起过，就当彻底忘了。
    public static let FORGET_THRESHOLD = 0.08

    /// 「很久没人提起」的天数。半年是个人的记忆里「真的想不起来」的尺度：
    /// 再短会误删半年前的约定，再长会让抽屉里塞满永远不会再用的碎屑。
    public static let FORGET_AGE_DAYS = 180.0

    /// 被钉住的记忆的分量下限。
    /// 用户亲手钉住 = 「这条对我很重要」，任何自动衰减都不能把它抹到阈值以下。
    public static let PINNED_FLOOR = 0.55

    /// 整理器说「留着」的加固量：模型判断它重要，多给一点分量，抵消这次衰减。
    public static let KEEP_BONUS = 0.08

    /// 被聊天召回时回血的量（比 keep 小：想起来一次不该顶一次整理）。
    public static let RECALL_BONUS = 0.05

    // MARK: 衰减参数

    /// 每天的基础衰减量：重要度 3 的记忆大约 20 天从满值掉到归档线。
    public static let DECAY_PER_DAY = 0.045

    /// 被用过之后多少天内算「新鲜」，这段时间是回血而不是继续掉。
    public static let RECENT_USE_DAYS = 7.0

    /// 一次整理最多补算多少天，避免长时间不开机后一次掉光。
    public static let MAX_DECAY_DAYS = 14.0

    // MARK: 抽屉差异
    //
    // 同样是「三个月没提起」，三个抽屉的答案不该一样：
    //
    // - USER（关于对方的事实与偏好）：名字、忌口、过敏、家人、职业 —— 半年不提也不会变假。
    //   这是三个抽屉里最不能淡的：淡掉不是「想不起来」，是会在现实里记错、说错、伤到人。
    //   所以衰减最慢（0.55，大约是 EVENT 的一半速度）。
    // - SELF（他自己是什么样的人）：性格、习惯、在意的事是慢变量，但会被当下心情染色，
    //   隔久了确实会「不像当时那个我」。居中（0.8）。
    // - EVENT（琐事与经历）：具体哪天聊了什么，本来就该淡；抽屉要腾出来装新的经历。
    //   保持 1.0（不加速）：这次调整只放慢该放慢的，不让任何既有记忆比以前消失得更快。
    //
    // 这三个数是「他记性的一部分」，不是设置项：用户看不到、也调不了。

    /// 关于对方的事实与偏好：衰减最慢。
    public static let DRAWER_DECAY_USER = 0.55

    /// 他自己是什么样的人：居中。
    public static let DRAWER_DECAY_SELF = 0.8

    /// 共同经历与经历的琐事：最快（= 基准速度）。
    public static let DRAWER_DECAY_EVENT = 1.0

    /// 单次回血上限，免得一次聊天把旧记忆直接拉满。
    public static let MAX_HEAL_PER_RUN = 0.15

    /// 新鲜度的半衰期：45 天没被提起，新鲜度掉到约 0.37。
    public static let FRESHNESS_HALF_LIFE_DAYS = 45.0

    /// 沉底的记忆分量回到「阈值 + 这个余量」以上，才算真的被想起来，回到 ACTIVE。
    public static let RECOVER_MARGIN = 0.10

    /// 衰减分量的权重：当前 strength 占大头，其次是重要度，再是新鲜度和被用次数。
    static let WEIGHT_STRENGTH = 0.40
    static let WEIGHT_IMPORTANCE = 0.30
    static let WEIGHT_FRESHNESS = 0.20
    static let WEIGHT_USAGE = 0.10

    /// 被用多少次算「用得很多」（占比打满）。
    static let USAGE_FULL_HITS = 5.0

    // MARK: 打分

    /// 这条记忆此刻还剩多少分量（0..1）。
    ///
    /// 组合：当前 strength、重要度、新鲜度（距上次被用多久）、被用次数；
    /// 钉住的一律不低于 `PINNED_FLOOR`；已归档的一律 0（它本来就不参与上下文）。
    public static func effectiveStrength(_ memory: MemoryLifecycleState, now: Int64) -> Double {
        if memory.tier == .archived { return 0.0 }
        let strength = safeUnit(memory.strength)
        let importance = importanceFactor(memory.importance)
        let freshness = freshnessOf(memory, now: now)
        let usage = usageFactor(memory.useCount)

        var score = WEIGHT_STRENGTH * strength
            + WEIGHT_IMPORTANCE * importance
            + WEIGHT_FRESHNESS * freshness
            + WEIGHT_USAGE * usage
        if memory.pinned { score = Swift.max(score, PINNED_FLOOR) }
        return min(Swift.max(score, 0.0), 1.0)
    }

    /// 记性（`memoryRetention` 1–5）换算成衰减系数：记性越好掉得越慢。
    ///
    /// 这五个数与 Kotlin `MemoryLifecycle.retentionFactor` 一致
    /// （`spec/agent-core/cases/memory-decay.json` 钉住）：1 → ×1.6、2 → ×1.3、
    /// 3 → ×1.0、4 → ×0.75、5 → ×0.5。越界值先夹进 1..5。
    public static func retentionFactor(_ retention: Int) -> Double {
        switch min(Swift.max(retention, 1), 5) {
        case 1: return 1.6
        case 2: return 1.3
        case 3: return 1.0
        case 4: return 0.75
        default: return 0.5
        }
    }

    /// 每天一次的衰减：没被用过的一天要掉一点，最近被用过的会回血。
    ///
    /// 步长按「距上次整理（没有就按创建）多少天」算，至少算一天，最多 `MAX_DECAY_DAYS` 天；
    /// 重要度越高掉得越慢（重要的事本来就该更抗时间）。
    /// - Parameter retentionFactor: 记性系数（人格的 `memoryRetention`，1 → 忘得快，5 → 什么都记得）。
    ///   只缩放「每天掉多少」，不改判定阈值：调大调小都不会让某件具体的事突然消失，
    ///   只会改变它淡下去的速度（§三）。
    public static func decayStep(_ memory: MemoryLifecycleState, now: Int64,
                                 retentionFactor: Double = 1.0) -> Double {
        // 归档了就不再衰减，它的去留只由 nextStatus 的「很旧 + 很低」判断。
        if memory.tier == .archived { return 0.0 }

        let current = safeUnit(memory.strength)
        let anchor = decayAnchor(memory)
        let elapsed = anchor <= 0 ? 1.0 : Double(now - anchor) / Double(DAY_MILLIS)
        // 整理一天一次；即使同一秒内被调度两次，也只按一天算，避免重复扣。
        let steps = elapsed < 1.0 ? 1.0 : min(elapsed, MAX_DECAY_DAYS)

        let factor = retentionFactor.isFinite && retentionFactor > 0 ? retentionFactor : 1.0
        let decay = DECAY_PER_DAY * decayRate(memory.importance)
            * drawerDecayRate(memory.drawer) * steps * factor
        let heal = healAmount(memory, now: now)
        let next = min(Swift.max(current - decay + heal, 0.0), 1.0)
        return memory.pinned ? Swift.max(next, PINNED_FLOOR) : next
    }

    /// 算完分量之后，这条记忆该往哪走。
    ///
    /// 规则（一天最多走一层，避免「一夜之间全没了」）：
    /// - 钉住的：永远 `keep`；
    /// - ACTIVE 且分量 ≤ `DORMANT_THRESHOLD`：沉到 DORMANT；
    /// - DORMANT 且分量 ≤ `ARCHIVE_THRESHOLD`：归档，移出上下文；
    /// - DORMANT 且分量回到 `DORMANT_THRESHOLD` + `RECOVER_MARGIN` 以上：回升成 ACTIVE；
    /// - 任何层只要「分量 ≤ `FORGET_THRESHOLD` 且 ≥ `FORGET_AGE_DAYS` 天没被提起」：彻底忘掉。
    ///
    /// 注意「很旧 + 很低」的遗忘判断放在沉底/归档之后：先移出上下文，确认半年都没人再想起，
    /// 才真的删数据 —— 删了就回不来了，宁可多留一段。
    public static func nextStatus(_ memory: MemoryLifecycleState, strength: Double, now: Int64) -> LifecycleAction {
        let value = safeUnit(strength)
        if memory.pinned { return .keep(id: memory.id) }

        switch memory.tier {
        case .active:
            if value <= DORMANT_THRESHOLD {
                return .demote(id: memory.id, to: .dormant, strength: value)
            }
            return .keep(id: memory.id)

        case .dormant:
            if shouldForget(memory, strength: value, now: now) {
                return .forget(
                    id: memory.id,
                    reason: "分量只剩 \(formatPercent(value))，又已经 \(ageInDays(memory, now: now)) 天没被提起"
                )
            }
            if value <= ARCHIVE_THRESHOLD {
                return .demote(id: memory.id, to: .archived, strength: value)
            }
            if value >= DORMANT_THRESHOLD + RECOVER_MARGIN {
                return .promote(id: memory.id, strength: value)
            }
            return .keep(id: memory.id)

        case .archived:
            // 归档的记忆不再往死里删：只有「真的很旧 + 真的很低」才忘掉，其余保持不动。
            if shouldForget(memory, strength: value, now: now) {
                return .forget(
                    id: memory.id,
                    reason: "归档已超过 \(Int(FORGET_AGE_DAYS)) 天，分量只剩 \(formatPercent(value))"
                )
            }
            return .keep(id: memory.id)
        }
    }

    /// 整理器只调这一个入口：先算衰减，再决定处置方式。
    public static func dailyUpdate(_ memory: MemoryLifecycleState, now: Int64,
                                   retentionFactor: Double = 1.0) -> LifecycleUpdate {
        let strength = decayStep(memory, now: now, retentionFactor: retentionFactor)
        return LifecycleUpdate(action: nextStatus(memory, strength: strength, now: now), strength: strength)
    }

    /// 整理器说「留着」时写回的分量：不低于当前值，再加一点 `KEEP_BONUS`。
    public static func keepStrength(_ memory: MemoryLifecycleState, now: Int64) -> Double {
        boost(memory, now: now, amount: KEEP_BONUS)
    }

    /// 聊天/回忆里被想起时写回的分量：回血一点点，不要让一次对话顶掉一次整理。
    public static func recallStrength(_ memory: MemoryLifecycleState, now: Int64) -> Double {
        boost(memory, now: now, amount: RECALL_BONUS)
    }

    /// 是不是「很旧 + 很低」，可以彻底忘掉。
    /// 单独暴露出来，方便单测直接把阈值钉死，也方便以后接设置项。
    public static func shouldForget(_ memory: MemoryLifecycleState, strength: Double, now: Int64) -> Bool {
        !memory.pinned
            && safeUnit(strength) <= FORGET_THRESHOLD
            && ageInDays(memory, now: now) >= FORGET_AGE_DAYS
    }

    /// 距离上次被用（没有就按创建）多少天；没有时间戳时按「很久」处理。
    public static func ageInDays(_ memory: MemoryLifecycleState, now: Int64) -> Double {
        let anchor = touchAnchor(memory)
        if anchor <= 0 { return .greatestFiniteMagnitude }
        return Double(Swift.max(now - anchor, 0)) / Double(DAY_MILLIS)
    }

    // MARK: 内部

    static func boost(_ memory: MemoryLifecycleState, now: Int64, amount: Double) -> Double {
        let current = safeUnit(memory.strength)
        let base = Swift.max(current, effectiveStrength(memory, now: now))
        let next = min(Swift.max(base + amount, 0.0), 1.0)
        return memory.pinned ? Swift.max(next, PINNED_FLOOR) : next
    }

    /// 衰减锚点：上次整理时间 → 上次被用时间 → 创建时间。
    static func decayAnchor(_ memory: MemoryLifecycleState) -> Int64 {
        for anchor in [memory.lastConsolidatedAt, memory.lastUsedAt, memory.createdAt] where anchor > 0 {
            return anchor
        }
        return 0
    }

    /// 「多久没被提起」的锚点：上次被用 → 创建。
    static func touchAnchor(_ memory: MemoryLifecycleState) -> Int64 {
        memory.lastUsedAt > 0 ? memory.lastUsedAt : memory.createdAt
    }

    /// 新鲜度 0..1：以 `FRESHNESS_HALF_LIFE_DAYS` 为半衰期。
    static func freshnessOf(_ memory: MemoryLifecycleState, now: Int64) -> Double {
        let anchor = touchAnchor(memory)
        if anchor <= 0 { return 0.0 }
        let days = Double(Swift.max(now - anchor, 0)) / Double(DAY_MILLIS)
        return min(Swift.max(exp(-days / FRESHNESS_HALF_LIFE_DAYS), 0.0), 1.0)
    }

    /// 重要度因子：1 → 0，5 → 1。
    static func importanceFactor(_ importance: Int) -> Double {
        // 越界值先夹取，别让模型给的 9 把分数顶爆
        let value = min(Swift.max(importance, 1), 5)
        return Double(value - 1) / 4.0
    }

    /// 被用次数因子 0..1。
    static func usageFactor(_ useCount: Int) -> Double {
        min(1.0, Double(Swift.max(useCount, 0)) / USAGE_FULL_HITS)
    }

    /// 重要度越高，衰减越慢：
    /// 1 分的近况一个月就淡，5 分的身份/约定要拖很久才可能沉底。
    static func decayRate(_ importance: Int) -> Double {
        switch min(Swift.max(importance, 1), 5) {
        case 1: return 1.5
        case 2: return 1.2
        case 3: return 1.0
        case 4: return 0.8
        default: return 0.65
        }
    }

    /// 抽屉的衰减系数：关于对方的事实最抗时间，共同经历最快（理由见常量处）。
    static func drawerDecayRate(_ drawer: MemoryDrawer) -> Double {
        switch drawer {
        case .user: return DRAWER_DECAY_USER
        case .selfDrawer: return DRAWER_DECAY_SELF
        case .event: return DRAWER_DECAY_EVENT
        }
    }

    /// 最近 `RECENT_USE_DAYS` 天内被用过 → 回血；用得越多回得越多（有上限）。
    static func healAmount(_ memory: MemoryLifecycleState, now: Int64) -> Double {
        if memory.lastUsedAt <= 0 { return 0.0 }
        let days = Double(Swift.max(now - memory.lastUsedAt, 0)) / Double(DAY_MILLIS)
        if days > RECENT_USE_DAYS { return 0.0 }
        let freshness = 1.0 - days / RECENT_USE_DAYS
        let usage = 0.5 + 0.5 * usageFactor(memory.useCount)
        return MAX_HEAL_PER_RUN * freshness * usage
    }

    /// 把任意坏值（NaN / 负数 / 超过 1）收进 0..1。
    static func safeUnit(_ value: Double) -> Double {
        if value.isNaN || value.isInfinite { return value > 0 ? 1.0 : 0.0 }
        return min(Swift.max(value, 0.0), 1.0)
    }

    static func formatPercent(_ value: Double) -> String {
        "\(Int(safeUnit(value) * 100))%"
    }
}
