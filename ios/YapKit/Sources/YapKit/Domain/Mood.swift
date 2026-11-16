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

    public init(label: String = "", intensity: Int = 2, reason: String = "", updatedAt: Int64 = 0) {
        self.label = label
        self.intensity = intensity
        self.reason = reason
        self.updatedAt = updatedAt
    }

    public func isEmpty() -> Bool { label.trimmed.isEmpty }

    /// 给界面用的一句：「雀跃（4/5）· 因为刚聊到想去的地方」
    public func display() -> String {
        if isEmpty() { return MoodLexicon.displayLabel(MoodLexicon.BASELINE) }
        var text = "\(MoodLexicon.displayLabel(label))（\(intensity)/5）"
        if !reason.trimmed.isEmpty { text += " · \(reason)" }
        return text
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
        if state.isEmpty() {
            return MoodState(label: MoodLexicon.BASELINE, intensity: state.intensity,
                             reason: state.reason, updatedAt: state.updatedAt)
        }
        let age = Swift.max(now - state.updatedAt, 0)
        if age >= FADE_AFTER_MS {
            return MoodState(label: MoodLexicon.BASELINE, intensity: 1, reason: "", updatedAt: state.updatedAt)
        }
        let hours = Int(age / 3_600_000)
        if hours <= 0 { return state }
        let faded = Swift.max(state.intensity - hours * DECAY_PER_HOUR, 1)
        if faded == state.intensity { return state }
        return MoodState(
            label: (faded <= 1 && MoodLexicon.isNegative(state.label)) ? MoodLexicon.BASELINE : state.label,
            intensity: faded,
            reason: faded <= 1 ? "" : state.reason,
            updatedAt: state.updatedAt
        )
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

    /// 常驻在系统提示里的一句。
    public static func line(_ state: MoodState) -> String {
        if state.isEmpty() {
            return "\n\n## 你现在的心情\n\(MoodLexicon.BASELINE)。没有特别的起伏。"
        }
        let word = MoodLexicon.classify(state.label)
        let intensityText: String
        switch state.intensity {
        case 1: intensityText = "很淡"
        case 2: intensityText = "淡淡的"
        case 3: intensityText = "明显"
        case 4: intensityText = "很浓"
        default: intensityText = "满脑子都是"
        }
        var text = "\n\n## 你现在的心情\n"
        // 第一行就是规范脚手架里的 `state_mood`（`scaffold.json`）：
        // 心情的**名字与强度**是两端约定，后面那半句分寸提示才是这里自己的内容。
        text += PersonaPromptScaffold.fill(
            PersonaPromptScaffold.stateMood,
            ["mood": state.label, "intensity": String(state.intensity)]
        )
        text += "\(intensityText)。"
        if !state.reason.trimmed.isEmpty { text += "原因：\(state.reason)。" }
        if word.valence <= -2 {
            text += "这种心情会让你说话慢一点、少一点，但不要卖惨，也别反复解释情绪。"
        } else if word.energy <= -2 {
            text += "这种心情会让你回得更短、更懒，不要硬撑热情。"
        } else if word.valence >= 2 {
            text += "这种心情会让你话多一点、语气轻一点，但别油。"
        } else {
            text += "语气自然就好，不必特意表现。"
        }
        return text
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
