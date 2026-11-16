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

    // MARK: 编辑器/生成/渲染的**建议**上限（0.12：存储与解析不再截断）
    //
    // 与 `WorldPreset` 同一原则：这些数字只用于「界面计数/超限提示」「本地兜底生成时
    // 别产出太多条」「提示词里最多引用几条」。用户手写的内容原样保存，不再被切掉——
    // 否则两端按各自的常数截断，`sourceHash` 就会对同一份人格算出不同的值。

    /// 本地兜底总结最多生成几条常用语（总结会多给，存太多会变成「模板库」）。
    public static let maxPhrases = 12
    /// **提示词里最多引用几条**（§1.1 第 4 条：常用语是参考不是模板；§6.3 用的是前 6 条）。
    public static let promptPhraseLimit = 6
    /// 编辑器里习惯/禁忌的建议条数（只用于提示，不参与存储）。
    public static let maxHabits = 6
    public static let maxAvoid = 5
    /// 编辑器里 tone 的建议字数（只用于提示；渲染提示词时另按 120 字截断，与 Android 一致）。
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
        // 存储/解析/哈希保留原文：只做去空白与去重，不按字数/条数截断。
        self.tone = tone.trimmingCharacters(in: .whitespacesAndNewlines)
        self.phrases = PersonaTags.normalize(
            phrases.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        )
        self.habits = PersonaTags.normalize(habits)
        self.avoid = PersonaTags.normalize(avoid)
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
            tone: PromptGuidanceJSON.string(object, "tone") ?? "",
            phrases: PromptGuidanceJSON.stringList(object, "phrases"),
            habits: PromptGuidanceJSON.stringList(object, "habits"),
            avoid: PromptGuidanceJSON.stringList(object, "avoid"),
            source: SpeechStyleSource(stored: PromptGuidanceJSON.string(object, "source"))
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

    /// 「# 你怎么说话」那一段（文案来自规范脚手架 `spec/persona-guidance/scaffold.json`）。
    ///
    /// 空风格返回空串（整节不出现）。
    /// 脚手架固定了四条：两条语气规则、tone、常用语、以及「不旁白」——
    /// 它们是「怎么说话」里最强的成分，即使用户没填 tone 也要在。
    /// habits / avoid 不在脚手架里，按同一种 `- ` 形状追加，空就不出现。
    public func renderForPrompt() -> String {
        guard !isEmpty else { return "" }
        var lines: [String] = [PersonaPromptScaffold.voiceSection]
        for rule in PersonaPromptScaffold.voiceRules {
            lines.append("- " + rule)
        }
        if !tone.isEmpty {
            lines.append("- " + PersonaPromptScaffold.fill(
                PersonaPromptScaffold.voiceTone, ["tone": tone]
            ))
        }
        for habit in habits {
            lines.append("- " + habit)
        }
        let spoken = Array(phrases.prefix(Self.promptPhraseLimit))
        if !spoken.isEmpty {
            let quoted = spoken.map { "「\($0)」" }.joined(separator: "、")
            lines.append("- " + PersonaPromptScaffold.fill(
                PersonaPromptScaffold.voicePhrases, ["phrases": quoted]
            ))
        }
        if !avoid.isEmpty {
            lines.append("- 不要出现：" + avoid.joined(separator: "、") + "。")
        }
        lines.append("- " + PersonaPromptScaffold.voiceNoNarration)
        return lines.joined(separator: "\n")
    }
}
