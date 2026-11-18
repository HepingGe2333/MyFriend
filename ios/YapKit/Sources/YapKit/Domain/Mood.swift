// 来源：app/src/main/java/com/yap/app/domain/Mood.kt
//
// 他此刻的心情。
//
// 心情不是装饰：它常驻在提示词里影响语气，也会随时间自己淡下去；
// 他说过几句话之后，系统会轻轻问他一句「现在我的心情变得……」，让他自己更新。

import Foundation

/// 一个全局的「现在」。只出现在**可选参数的默认值**里（和 Kotlin 的
/// `System.currentTimeMillis()` 默认参数一一对应）；所有算法本体都要求把时间显式传进来，
/// 这样单测不需要跟时钟赛跑。
public enum DomainClock {
    public static var nowMillis: Int64 {
        Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
    }

    /// 现在几点（0..23），按系统时区。
    public static func hourOfDay(_ now: Int64 = DomainClock.nowMillis) -> Int {
        Calendar.current.component(.hour, from: Date(timeIntervalSince1970: Double(now) / 1000.0))
    }
}

/// 他此刻的心情。
public struct MoodState: Equatable {
    /// 一个词的标签，如「平静」「有点累」「雀跃」
    public var label: String
    /// 1..5，越大越浓
    public var intensity: Int
    /// 为什么会这样，一句话
    public var reason: String
    /// 上次真正变化的时间
    public var updatedAt: Int64
    /// schema v17 的多维心情；`nil` = **还没判定过**（老数据，或者只写过 label）。
    ///
    /// 为什么是可选而不是「给个默认值」：`nil` 与「判定结果是 0/2/3」在语义上是两件事——
    /// 前者要退回词表（`MoodLexicon`）推断，后者是模型真的看过对话后的结论。
    /// 混成一份会让「老库升级」和「他今天确实平静」变得无法区分。
    public var dimensions: MoodDimensions?

    public init(
        label: String = "",
        intensity: Int = 2,
        reason: String = "",
        updatedAt: Int64 = 0,
        dimensions: MoodDimensions? = nil
    ) {
        self.label = label
        self.intensity = intensity
        self.reason = reason
        self.updatedAt = updatedAt
        self.dimensions = dimensions
    }

    public func isEmpty() -> Bool { label.trimmed.isEmpty }

    /// 三维心情：有判定结果用判定结果，否则由词表推（老数据/模型只写了 label）。
    public var resolvedDimensions: MoodDimensions {
        dimensions ?? MoodDimensions.fromLegacy(label: label, intensity: intensity)
    }

    /// 给界面用的一句：「雀跃（4/5）· 因为刚聊到想去的地方」
    public func display() -> String {
        if isEmpty() { return MoodLexicon.displayLabel(MoodLexicon.BASELINE) }
        var text = "\(MoodLexicon.displayLabel(label))（\(intensity)/5）"
        if !reason.trimmed.isEmpty { text += " · \(reason)" }
        return text
    }
}

/// schema v17 的**多维心情**（`spec/mood/README.md` §1）。
///
/// 一个词说不清「累」和「烦」的区别：前者是没劲，后者是不悦但还有火气。
/// 所以除了 label / intensity，还存三个 0 附近有意义的维度：
/// - `valence` 愉快度：-2 很不悦 … 2 很愉快；
/// - `arousal` 唤醒度：0 低能量/困 … 5 激动；
/// - `dominance` 掌控感：0 被压住 … 5 掌控。
///
/// 全是纯值类型与纯函数：范围夹取、旧数据映射、单次变化幅度、按小时衰减都在这里，
/// 服务层只负责「调模型 → 校验 → 落库」，测试可以穷举边界。
public struct MoodDimensions: Equatable, Sendable {

    public static let valenceRange = -2...2
    public static let arousalRange = 0...5
    public static let dominanceRange = 0...5

    public var valence: Int
    public var arousal: Int
    public var dominance: Int

    public init(valence: Int = 0, arousal: Int = 2, dominance: Int = 3) {
        self.valence = Self.clampValence(valence)
        self.arousal = Self.clampArousal(arousal)
        self.dominance = Self.clampDominance(dominance)
    }

    /// 基准：平静（0 / 2 / 3）。衰减的终点、判定的起点都是它。
    public static let baseline = MoodDimensions()

    public var isBaseline: Bool { self == Self.baseline }

    public static func clampValence(_ value: Int) -> Int {
        min(Swift.max(value, valenceRange.lowerBound), valenceRange.upperBound)
    }

    public static func clampArousal(_ value: Int) -> Int {
        min(Swift.max(value, arousalRange.lowerBound), arousalRange.upperBound)
    }

    public static func clampDominance(_ value: Int) -> Int {
        min(Swift.max(value, dominanceRange.lowerBound), dominanceRange.upperBound)
    }

    /// 旧数据（只有 label + intensity）的映射（规范 §1）：
    /// valence 由 label 的正负给 ±1（累/烦 → -1，平静 → 0，开心 → 1），
    /// arousal 由 intensity 推，dominance 固定 3。
    ///
    /// 与 Android `MoodDimensions.fromLegacy` 同一套规则：
    /// 1. 基准词（平静）→ 0；
    /// 2. 词表里的词 → 极性**夹到 ±1**（词表里 `低落`/`雀跃` 本身是 ±2，但一个旧标签
    ///    不该被放大成很强烈的情绪）；
    /// 3. 词表之外的口语单字（「累」「烦」「高兴」）→ 只给方向（±1），不给强度。
    public static func fromLegacy(label: String, intensity: Int) -> MoodDimensions {
        let normalized = MoodLexicon.normalize(label)
        let valence: Int
        if normalized == MoodLexicon.BASELINE {
            valence = 0
        } else {
            let known = MoodLexicon.classify(normalized).valence
            if known != 0 {
                valence = min(Swift.max(known, -1), 1)
            } else if negativeHints.contains(where: { normalized.contains($0) }) {
                valence = -1
            } else if positiveHints.contains(where: { normalized.contains($0) }) {
                valence = 1
            } else {
                valence = 0
            }
        }
        return MoodDimensions(
            valence: valence,
            arousal: intensity,
            dominance: 3
        )
    }

    /// 负面口语词（只用于「词表里不认识」时的方向判断，不参与强度计算）。
    static let negativeHints = ["累", "烦", "困", "难受", "低落", "委屈", "不安", "愧疚", "难过", "丧"]
    /// 正面口语词。
    static let positiveHints = ["开心", "高兴", "快乐", "雀跃", "兴奋", "期待", "轻松", "放松", "温柔", "甜"]

    /// 单次变化幅度限制：每一天维度的变化不超过 `maxDelta`（规范 §2：≤2）。
    ///
    /// 为什么在**这里**夹而不是只信提示词：模型经常一次给出一个「从雀跃到崩溃」的跳变，
    /// 那更像剧情而不是心情；夹住之后，情绪只能一档一档走。
    public func limitedChange(to next: MoodDimensions, maxDelta: Int = 2) -> MoodDimensions {
        let limit = Swift.max(maxDelta, 0)
        func step(_ current: Int, _ target: Int) -> Int {
            let delta = target - current
            return current + min(Swift.max(delta, -limit), limit)
        }
        return MoodDimensions(
            valence: step(valence, next.valence),
            arousal: step(arousal, next.arousal),
            dominance: step(dominance, next.dominance)
        )
    }

    /// 向基准靠 `steps` 步（每个维度最多动 `steps` 格，规范 §2 的「每小时靠一步」）。
    public func decayed(toward target: MoodDimensions = .baseline, steps: Int = 1) -> MoodDimensions {
        limitedChange(to: target, maxDelta: Swift.max(steps, 0))
    }

    /// 两个维度之间是否「没有明显变化」：逐维相同才算。
    public func hasVisibleChange(from previous: MoodDimensions) -> Bool {
        valence != previous.valence
            || arousal != previous.arousal
            || dominance != previous.dominance
    }
}

/// 词表与温和的极性判断，只用来做展示和自动回落，不参与模型决策。
public enum MoodLexicon {

    public static let BASELINE = "平静"

    /// 极性：-2 很负面 … 2 很正面；energy：-2 很累 … 2 很有劲。
    public struct MoodWord: Equatable {
        public let label: String
        public let valence: Int
        public let energy: Int

        public init(_ label: String, _ valence: Int, _ energy: Int) {
            self.label = label
            self.valence = valence
            self.energy = energy
        }
    }

    static let KNOWN: [MoodWord] = [
        MoodWord("平静", 0, 0),
        MoodWord("放松", 1, -1),
        MoodWord("温柔", 1, 0),
        MoodWord("雀跃", 2, 2),
        MoodWord("开心", 2, 1),
        MoodWord("兴奋", 2, 2),
        MoodWord("期待", 1, 1),
        MoodWord("想念", 0, -1),
        MoodWord("有点累", -1, -2),
        MoodWord("疲惫", -1, -2),
        MoodWord("犯困", -1, -2),
        MoodWord("低落", -2, -1),
        MoodWord("难过", -2, -1),
        MoodWord("心烦", -2, 1),
        MoodWord("不安", -1, 1),
        MoodWord("有点委屈", -2, 0),
        MoodWord("愧疚", -1, 0)
    ]

    static let BY_LABEL: [String: MoodWord] = {
        var table: [String: MoodWord] = [:]
        for word in KNOWN {
            // 后写的覆盖先写的：与 Kotlin `associateBy` 一致。
            table[word.label] = word
        }
        return table
    }()

    /// 未知词按中性处理，不猜。
    public static func classify(_ label: String) -> MoodWord {
        let key = normalize(label)
        return BY_LABEL[key] ?? MoodWord(key, 0, 0)
    }

    /// 界面显示用的词：已知词表翻成当前界面语言，模型自己造的未知词原样显示。
    ///
    /// 词表本身保持中文（它同时是**存库的数据**与**提示词里的词**，不能改）；
    /// 只有「给人看」的这一处做映射。未知词（比如模型临时写的「有点想你」）不硬翻，
    /// 原样显示——那属于人格数据，不在文案范围里。
    public static func displayLabel(_ label: String) -> String {
        switch normalize(label) {
        case "平静": return String(localized: "平静")
        case "放松": return String(localized: "放松")
        case "温柔": return String(localized: "温柔")
        case "雀跃": return String(localized: "雀跃")
        case "开心": return String(localized: "开心")
        case "兴奋": return String(localized: "兴奋")
        case "期待": return String(localized: "期待")
        case "想念": return String(localized: "想念")
        case "有点累": return String(localized: "有点累")
        case "疲惫": return String(localized: "疲惫")
        case "犯困": return String(localized: "犯困")
        case "低落": return String(localized: "低落")
        case "难过": return String(localized: "难过")
        case "心烦": return String(localized: "心烦")
        case "不安": return String(localized: "不安")
        case "有点委屈": return String(localized: "有点委屈")
        case "愧疚": return String(localized: "愧疚")
        default: return label
        }
    }

    /// 去掉空白与标点，限制长度；空串回落到基准心情。
    public static func normalize(_ label: String) -> String {
        let trimmed = collapsedWhitespaceRemoved(label.trimmed)
        if trimmed.isEmpty { return BASELINE }
        return String(trimmed.prefix(12))
    }

    /// 是不是「累/困」这类需要他少说话的心情。
    public static func isLowEnergy(_ label: String) -> Bool {
        classify(label).energy <= -2
    }

    /// 是不是比较负面、需要被照顾的心情。
    public static func isNegative(_ label: String) -> Bool {
        classify(label).valence <= -2
    }

    /// 等价于 Kotlin 的 `replace(Regex("\\s+"), "")`：把空白整个删掉（不是压成一个空格）。
    static func collapsedWhitespaceRemoved(_ text: String) -> String {
        var out = ""
        out.reserveCapacity(text.count)
        for ch in text where !ch.isWhitespace { out.append(ch) }
        return out
    }
}

/// 心情自己会淡。
///
/// 人的情绪本来就会回落：强度每小时掉一点，几个小时后回到基准；
/// 只有真的被再次触发时才会重新变浓（由模型通过 set_mood 或周期性引导完成）。
public enum MoodDrift {

    /// 超过这个时间没有更新，就认为情绪已经散了。
    public static let FADE_AFTER_MS: Int64 = 6 * 60 * 60 * 1000

    /// 每小时掉的强度。
    static let DECAY_PER_HOUR = 1

    public static func decay(_ state: MoodState, now: Int64) -> MoodState {
        let dimensions = decayedDimensions(state, now: now)
        if state.isEmpty() {
            return MoodState(label: MoodLexicon.BASELINE, intensity: state.intensity,
                             reason: state.reason, updatedAt: state.updatedAt,
                             dimensions: dimensions)
        }
        let age = Swift.max(now - state.updatedAt, 0)
        if age >= FADE_AFTER_MS {
            return MoodState(label: MoodLexicon.BASELINE, intensity: 1, reason: "",
                             updatedAt: state.updatedAt,
                             // 情绪散了：三维也回到基准（0 / 2 / 3）。
                             dimensions: state.dimensions == nil ? nil : MoodDimensions.baseline)
        }
        let hours = Int(age / 3_600_000)
        if hours <= 0 { return state }
        let faded = Swift.max(state.intensity - hours * DECAY_PER_HOUR, 1)
        if faded == state.intensity && dimensions == state.dimensions { return state }
        return MoodState(
            label: (faded <= 1 && MoodLexicon.isNegative(state.label)) ? MoodLexicon.BASELINE : state.label,
            intensity: faded,
            reason: faded <= 1 ? "" : state.reason,
            updatedAt: state.updatedAt,
            dimensions: dimensions
        )
    }

    /// 三维心情按小时向基准靠一步（规范 §2：「失败/无配置时按时间衰减」）。
    ///
    /// 只对**判定过**的心情生效（`dimensions != nil`）：老数据没有这一层，
    /// 由 label + intensity 的衰减（上面那段）负责，两种口径不对彼此二次衰减。
    static func decayedDimensions(_ state: MoodState, now: Int64) -> MoodDimensions? {
        guard let dimensions = state.dimensions else { return nil }
        let age = Swift.max(now - state.updatedAt, 0)
        let hours = Swift.min(Int(age / 3_600_000), 12)
        if hours <= 0 { return dimensions }
        return dimensions.decayed(toward: .baseline, steps: hours)
    }

    /// 说过几句话之后该不该回头看一眼自己的心情。
    public static func shouldReflect(
        _ state: MoodState,
        spokenSinceUpdate: Int,
        now: Int64,
        threshold: Int = 3,
        lastTouchAt: Int64? = nil
    ) -> Bool {
        if spokenSinceUpdate >= threshold { return true }
        let touch = lastTouchAt ?? state.updatedAt
        let age = Swift.max(now - touch, 0)
        // 很久没动过心情、又聊了好一会儿，也该看看了。
        return age >= 4 * 3_600_000 && spokenSinceUpdate >= 1
    }
}

/// 心情怎么写进提示词。措辞在这里定死，别处不许再拼。
public enum MoodPrompt {

    /// 「# 你的心情」段标题（规范 `spec/mood/README.md` §3）。
    public static let sectionTitle = "# 你的心情"

    /// 常驻在系统提示里的一段（多维版本，规范 §3）。
    ///
    /// 形状（逐字照规范）：
    /// ```
    /// # 你的心情
    /// 心情：{label}（强度 {intensity}/5）。愉快度 {valence} / 唤醒度 {arousal}/5 / 掌控感 {dominance}/5。
    /// - 这会影响你怎么说：{按维度生成的 1-2 句行为提示}
    /// - 不要直接报数值，也不要说「我的情绪是…」；用说话方式体现。
    /// ```
    ///
    /// 第一句仍然由脚手架 `state_mood` 生成：它是两端约定的「名字 + 强度」。
    /// 数值是**给模型自己看的状态**，所以最后一条明确要求它别说出口。
    public static func line(_ state: MoodState) -> String {
        let dimensions = state.isEmpty()
            ? MoodDimensions.baseline
            : state.resolvedDimensions
        let label = state.isEmpty() ? MoodLexicon.BASELINE : state.label
        let intensity = state.isEmpty() ? 1 : min(Swift.max(state.intensity, 1), 5)

        var lines: [String] = [sectionTitle]
        lines.append(PersonaPromptScaffold.fill(
            PersonaPromptScaffold.stateMood,
            ["mood": label, "intensity": String(intensity)]
        ) + dimensionLine(dimensions))
        lines.append("- 这会影响你怎么说：" + behaviorHints(dimensions))
        if !state.reason.trimmed.isEmpty {
            // 与 Android `MoodDimensions.promptLines` 同一行文：`原因：…`（不展示给用户）。
            lines.append("原因：\(state.reason)")
        }
        lines.append("- 不要直接报数值，也不要说「我的情绪是…」；用说话方式体现。")
        return "\n\n" + lines.joined(separator: "\n")
    }

    /// 「愉快度 … / 唤醒度 … / 掌控感 …」那半句。
    public static func dimensionLine(_ dimensions: MoodDimensions) -> String {
        "愉快度 \(dimensions.valence) / 唤醒度 \(dimensions.arousal)/5 / 掌控感 \(dimensions.dominance)/5。"
    }

    /// 按维度生成的 1-2 句行为提示（规范 §3：**不报数值**，只说怎么说话）。
    ///
    /// 挑选顺序：先愉快度（最影响语气），再从唤醒度与掌控感里挑一个与基准差得最远的。
    /// 这样最多两句，不会把提示词写成一张参数表。
    public static func behaviorHints(_ dimensions: MoodDimensions) -> String {
        var hints: [String] = []
        if dimensions.valence <= -2 {
            hints.append("心里很不痛快，话更少、更短，但别卖惨，也别反复解释自己的情绪。")
        } else if dimensions.valence <= -1 {
            hints.append("有点不悦，语气淡一点，别硬装没事，也别主动找话题。")
        } else if dimensions.valence >= 2 {
            hints.append("心情很好，语气轻一点，话可以多一点，但别油、别夸张。")
        } else if dimensions.valence >= 1 {
            hints.append("心情不错，语气松一点，不用端着。")
        }

        let arousalGap = abs(dimensions.arousal - MoodDimensions.baseline.arousal)
        let dominanceGap = abs(dimensions.dominance - MoodDimensions.baseline.dominance)
        if arousalGap >= dominanceGap {
            if dimensions.arousal <= 1 {
                hints.append("没什么劲，回得更短、更慢，能只回一句就只回一句。")
            } else if dimensions.arousal >= 4 {
                hints.append("有点兴奋，话容易多，收着点，别一口气发一大段。")
            }
        } else {
            if dimensions.dominance <= 1 {
                hints.append("说不上什么主意，少替对方拿主意，多听，多用「嗯」「行」。")
            } else if dimensions.dominance >= 4 {
                hints.append("说话更笃定，别绕弯子，也别反复确认对方的意思。")
            }
        }
        if hints.isEmpty {
            hints.append("语气自然就好，不必特意表现心情。")
        }
        return hints.prefix(2).joined(separator: "")
    }

    /// 周期性引导词：说过几句话之后，让他回头看看心情变没变。
    ///
    /// 刻意写成第一人称的自言自语，不说「请更新情绪状态」这种机械指令。
    public static func reflectionNudge(_ state: MoodState, spokenSinceUpdate: Int) -> String {
        var text = "\n\n## 心里那句话\n"
        text += "说了 \(spokenSinceUpdate) 句话，停一下，问自己一句：现在我的心情变得……？"
        if state.isEmpty() {
            text += "如果和\(MoodLexicon.BASELINE)不一样了，调用 set_mood 记下来。"
        } else {
            text += "刚才还是「\(state.label)」，现在呢？真的变了就调用 set_mood 更新，没变就别动。"
        }
        text += "对方不需要知道你记了心情，语气里自然带出来就行。"
        return text
    }
}
