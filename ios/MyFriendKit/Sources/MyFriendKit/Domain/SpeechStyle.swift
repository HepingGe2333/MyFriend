import Foundation

// ---------------------------------------------------------------------------
// 话风（Speech Style）：他怎么说话。
//
// 规范：`spec/persona-guidance/README.md` §3。
//
// 为什么要有这一段：只写「你叫林深」的提示词，模型会稳定地滑向「温柔、体贴、什么都接」的
// 助手腔。真正让一个人可辨认的是他的**句子长度、标点、口头禅、和明确不说什么**。
// 这四样就是 tone / phrases / habits / avoid。
//
// 隐私（§3.3）：这里只装**总结后的风格数据**。原始聊天记录永远不落库、不进日志、不进备份——
// 那个字符串只在 `SpeechStyleSummarizer` 里活一次请求的内存，函数返回就没人再引用它。
// ---------------------------------------------------------------------------

/// 这段风格是怎么来的：模型总结 / 本地粗略推断 / 用户手写。
///
/// 界面要能区分「本地粗略总结，可手动修改」（§3.2 要求的那句提示），所以它跟着 JSON 一起存。
public enum SpeechStyleSource: String, CaseIterable, Sendable {
    case model = "model"
    case heuristic = "heuristic"
    case manual = "manual"

    public init(stored raw: String?) {
        switch (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "heuristic": self = .heuristic
        case "manual": self = .manual
        default: self = .model
        }
    }

    public var isHeuristic: Bool { self == .heuristic }
}

public struct SpeechStyle: Equatable, Sendable {

    // MARK: 上限

    /// 落库时最多留几条常用语（总结会多给，存太多会变成「模板库」）。
    public static let maxPhrases = 12
    /// **提示词里最多引用几条**（§1.1 第 4 条：常用语是参考不是模板；§6.3 用的是前 6 条）。
    public static let promptPhraseLimit = 6
    public static let maxHabits = 6
    public static let maxAvoid = 5
    public static let toneMax = 80

    public var tone: String
    public var phrases: [String]
    public var habits: [String]
    public var avoid: [String]
    /// `model` / `heuristic` / `manual`（缺字段时按 model，备份里也保留它）。
    public var source: SpeechStyleSource

    public init(
        tone: String = "",
        phrases: [String] = [],
        habits: [String] = [],
        avoid: [String] = [],
        source: SpeechStyleSource = .model
    ) {
        let trimmedTone = tone.trimmingCharacters(in: .whitespacesAndNewlines)
        self.tone = trimmedTone.count > Self.toneMax ? String(trimmedTone.prefix(Self.toneMax)) : trimmedTone
        self.phrases = PersonaTags.normalize(phrases.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) },
                                             limit: Self.maxPhrases)
        self.habits = PersonaTags.normalize(habits, limit: Self.maxHabits)
        self.avoid = PersonaTags.normalize(avoid, limit: Self.maxAvoid)
        self.source = source
    }

    public var isEmpty: Bool {
        tone.isEmpty && phrases.isEmpty && habits.isEmpty && avoid.isEmpty
    }

    // MARK: - 解析

    /// 库里的 JSON；空 / 坏 JSON 返回 nil。
    public static func decode(_ raw: String?) -> SpeechStyle? {
        guard let object = PromptGuidanceJSON.object(from: raw) else { return nil }
        let style = from(object: object)
        return style.isEmpty ? nil : style
    }

    /// 模型输出（可能带代码块 / 前后解释）。
    public static func parse(_ text: String?) -> SpeechStyle? {
        decode(text)
    }

    public static func from(object: [String: Any]) -> SpeechStyle {
        SpeechStyle(
            tone: PromptGuidanceJSON.string(object, "tone", limit: toneMax) ?? "",
            phrases: PromptGuidanceJSON.stringList(object, "phrases", limit: maxPhrases, itemLimit: 40),
            habits: PromptGuidanceJSON.stringList(object, "habits", limit: maxHabits, itemLimit: 80),
            avoid: PromptGuidanceJSON.stringList(object, "avoid", limit: maxAvoid, itemLimit: 80),
            source: SpeechStyleSource(stored: PromptGuidanceJSON.string(object, "source", limit: 20))
        )
    }

    public func jsonString() -> String? {
        guard !isEmpty else { return nil }
        let object: [String: Any] = [
            "tone": tone,
            "phrases": phrases,
            "habits": habits,
            "avoid": avoid,
            "source": source.rawValue
        ]
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    // MARK: - 渲染

    /// 规范 §6.3 的「# 你怎么说话」那一段。空风格返回空串。
    ///
    /// 三条固定要求（句子短 / 情绪压着说 / 不写动作描写）来自 §6.3 的实测组合：
    /// 它们是「怎么说话」里最强的成分，即使用户没填 tone 也要在。
    public func renderForPrompt() -> String {
        guard !isEmpty else { return "" }
        var lines: [String] = ["# 你怎么说话"]
        lines.append("- 句子短（1-2 句），少感叹号，少书面词。")
        if !tone.isEmpty {
            lines.append("- 你的说话风格：\(tone)。")
        }
        for habit in habits {
            lines.append("- \(habit)")
        }
        let spoken = Array(phrases.prefix(Self.promptPhraseLimit))
        if !spoken.isEmpty {
            let quoted = spoken.map { "「\($0)」" }.joined(separator: "、")
            lines.append("- 你平时会说的话（自然使用，最多带 1 处，不要每句都套）：\(quoted)")
        }
        if !avoid.isEmpty {
            lines.append("- 不要出现：" + avoid.joined(separator: "、") + "。")
        }
        lines.append("- 不要写动作描写、不要括号旁白。")
        return lines.joined(separator: "\n")
    }
}
