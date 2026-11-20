import Foundation

// ---------------------------------------------------------------------------
// 自动情绪判定（`spec/mood/README.md` §2，schema v17）。
//
// 背景：以前心情只能靠模型在聊天里顺手调一次 `set_mood`（而且只有一个词）。真机上的问题
// 很具体：他聊十轮都不会自己更新心情，语气也不变；偶尔写了「烦」也不知道有多烦。
//
// 这一版改成**每 4 条用户消息（或 24 小时）自动判一次**：
// 1. 输入 = 上一轮的心情 + 最近 8 条对话（一条一行的小摘要）；
// 2. 输出 = JSON `{label,valence,arousal,dominance,intensity,reason}`；
// 3. 校验（本文件，纯函数）：范围夹取、单次变化幅度 ≤2、没有明显变化就不写；
// 4. 调用失败 / 没配 API → **按时间衰减**（每小时向 0/2/3 靠一步），不报错、不打扰用户。
//
// 为什么判定逻辑要写成纯函数：模型输出是外部数据（不可信），「什么算合法」「什么叫有变化」
// 写在一个不碰网络、不碰数据库的类型里，测试才能穷举坏输入（越界、字符串数字、缺字段、
// 从雀跃直接跳到崩溃）。
// ---------------------------------------------------------------------------

/// 一次通过校验的情绪判定。
public struct MoodJudgement: Equatable, Sendable {
    public var label: String
    /// 1..5
    public var intensity: Int
    /// -2..2
    public var valence: Int
    /// 0..5
    public var arousal: Int
    /// 0..5
    public var dominance: Int
    /// 一句话理由（只给模型自己看，不直接展示给用户）。
    public var reason: String

    public init(
        label: String,
        intensity: Int = 3,
        valence: Int = 0,
        arousal: Int = 2,
        dominance: Int = 3,
        reason: String = ""
    ) {
        self.label = label
        self.intensity = intensity
        self.valence = valence
        self.arousal = arousal
        self.dominance = dominance
        self.reason = reason
    }

    /// 三维部分（已经夹到合法范围内）。
    public var dimensions: MoodDimensions {
        MoodDimensions(valence: valence, arousal: arousal, dominance: dominance)
    }
}

/// 情绪判定的触发节奏、提示词、解析与校验。
public enum MoodJudgementRules {

    // MARK: - 触发节奏（规范 §2）

    /// 每多少条用户消息判一次。
    ///
    /// 0.15 起是 **1**：每一条用户消息之后都重新判一次（`docs/design/context-memory-v2.md`
    /// 「心情：每条用户消息后重新判定一次」）。心情不再是一个每小时才动一次的旧值。
    public static let triggerUserMessages = 1
    /// 或者每隔多久（毫秒）判一次。
    public static let triggerIntervalMillis: Int64 = 24 * 60 * 60 * 1000
    /// 判定时带最近多少条对话（一条一行的小摘要）。
    public static let recentMessageLimit = 8
    /// 0.14 及以前「单次变化幅度 ≤2」的常量。
    ///
    /// 0.15 起**不再使用**（只做物理范围夹取）：真机反馈是「同一晚从雀跃掉到崩溃」被压成
    /// 一次只动 2 格，模型明说了、提示词里却是温吞的中间值，读起来像没听懂。
    /// 保留常量供迁移期对照与文档引用，避免旧测试/旧数据引用处编译不过。
    public static let legacyMaxDelta = 2
    /// 判定那一轮用的参数：低温度（要稳定 JSON），输出很短。
    public static let temperature = 0.2
    public static let maxOutputTokens = 300
    /// 单条对话摘要的字数上限。
    public static let dialogueLineMaxChars = 60
    /// reason 的字数上限。
    public static let reasonMaxChars = 80

    // MARK: - 提示词

    /// 判定用的 system + user 提示词。
    ///
    /// 输入只要两样：上一轮的心情（结构化）+ 最近几条对话的摘要。
    /// 刻意**不**带整段人设：判定的是「他现在什么心情」，不是「他是什么人」。
    public static func messages(
        previous: MoodState,
        recentDialogue: String,
        earlierSummary: String?
    ) -> [CompletionMessage] {
        let system = """
        你是「他」的直觉。看完「他」最近和对方的几句对话，**先凭感觉给结论**，再想这一轮该怎么说话。
        不要逐项推理、不要分析对话结构：你的第一反应就是答案。
        结论写成：一个词（label）+ 三个维度——愉快度（valence，-2 很不悦 … 2 很愉快）、
        唤醒度（arousal，0 很没劲/困 … 5 很激动）、掌控感（dominance，0 被压住 … 5 掌控），
        以及强度（intensity，1 很淡 … 5 很浓）。行为指导只写一句（guidance 可以省略，系统只用结论）。
        只输出 JSON 对象，不要解释、不要 markdown：
        {"label":"不超过4个字的词","valence":-2到2的整数,"arousal":0到5的整数,"dominance":0到5的整数,"intensity":1到5的整数,"reason":"一句话，<=30字"}
        规则：
        - label 用日常的说法（累、平静、烦、开心、想念、有点委屈……），不要用心理学名词；
        - 如果你读完真的觉得心情变了，就照实写——**不要为了「变化平滑」把强烈的感受压成中间值**；
        - 没有变化也要如实输出（系统会自己判断要不要写）；
        - 不要把对方的情绪当成他的情绪；不要评价关系，不要给建议。
        """
        let current = previous.isEmpty()
            ? MoodState(label: MoodLexicon.BASELINE, intensity: 2,
                        dimensions: MoodDimensions.baseline)
            : previous
        let dimensions = current.resolvedDimensions
        var user = "## 他上一轮的心情\n"
        user += "\(current.label)（强度 \(current.intensity)/5），愉快度 \(dimensions.valence) / "
        user += "唤醒度 \(dimensions.arousal)/5 / 掌控感 \(dimensions.dominance)/5。\n"
        let summary = (earlierSummary ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !summary.isEmpty {
            user += "\n## 更早之前聊过什么（摘要）\n\(String(summary.prefix(600)))\n"
        }
        let dialogue = recentDialogue.trimmingCharacters(in: .whitespacesAndNewlines)
        user += "\n## 最近几句\n" + (dialogue.isEmpty ? "（最近没聊什么）" : dialogue)
        user += "\n\n现在他的心情是什么？只输出 JSON。"
        return [.system(system), .user(user)]
    }

    /// 把最近几条消息渲染成「一条一行」的小摘要（最多 `limit` 条、每条 `dialogueLineMaxChars` 字）。
    public static func renderDialogue(_ messages: [MessageEntity], limit: Int = recentMessageLimit) -> String {
        let visible = messages
            .filter { $0.role == .user || $0.role == .assistant }
            .filter { !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .suffix(Swift.max(limit, 1))
        return visible.map { message in
            let who = message.role == .user ? "对方" : "他"
            let content = message.content
                .replacingOccurrences(of: "\n", with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return "- \(who)：\(String(content.prefix(dialogueLineMaxChars)))"
        }
        .joined(separator: "\n")
    }

    // MARK: - 解析与校验

    /// 解析模型输出；不是合法 JSON / 没有 label 时返回 nil（调用方当作「这次没判成」）。
    ///
    /// 容错点（模型经常这么写）：代码块、前后解释、数字写成字符串、label 带引号、
    /// 字段名大小写不同、维度缺一个（缺的用上一轮的值）。
    public static func parse(_ raw: String, previous: MoodState) -> MoodJudgement? {
        guard let object = PromptGuidanceJSON.object(from: raw) else { return nil }
        let labelRaw = firstString(object, ["label", "mood", "emotion", "name"])
        // 没给 label、也没给任何维度 → 这份输出没有信息量。
        let hasDimension = object["valence"] != nil || object["arousal"] != nil
            || object["dominance"] != nil
        guard labelRaw != nil || hasDimension else { return nil }
        // 只给了维度、没给词：沿用上一轮的词（不能因为缺字段就把「烦」写成「平静」）。
        let label = labelRaw.map { MoodLexicon.normalize($0) }
            ?? (previous.isEmpty() ? MoodLexicon.BASELINE : previous.label)

        let base = previous.resolvedDimensions
        let valence = clamp(number(object["valence"]) ?? base.valence,
                            to: MoodDimensions.valenceRange)
        let arousal = clamp(number(object["arousal"]) ?? base.arousal,
                            to: MoodDimensions.arousalRange)
        let dominance = clamp(number(object["dominance"]) ?? base.dominance,
                              to: MoodDimensions.dominanceRange)
        let intensity = clamp(number(object["intensity"]) ?? Swift.max(previous.intensity, 1),
                              to: 1...5)
        let reason = String(
            (firstString(object, ["reason", "why", "because"]) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(reasonMaxChars)
        )
        return MoodJudgement(
            label: label,
            intensity: intensity,
            valence: valence,
            arousal: arousal,
            dominance: dominance,
            reason: reason
        )
    }

    /// 把判定合并到「上一轮的心情」上：**只做物理范围夹取，不再限制单次幅度**（0.15）。
    ///
    /// 与 `parse` 的分工：`parse` 已经把模型给的越界值夹到合法范围（-2..2 / 0..5 / 1..5），
    /// 这里再夹一次是因为 `MoodJudgement` 可以被调用方直接构造（服务端、测试、旧缓存）。
    /// 幅度不再限制之后，「他一句话从雀跃到崩溃」会如实写进库与提示词——那才是直觉。
    public static func merged(_ judgement: MoodJudgement, previous: MoodState) -> MoodState {
        let dimensions = MoodDimensions(
            valence: clamp(judgement.valence, to: MoodDimensions.valenceRange),
            arousal: clamp(judgement.arousal, to: MoodDimensions.arousalRange),
            dominance: clamp(judgement.dominance, to: MoodDimensions.dominanceRange)
        )
        let intensity = clamp(judgement.intensity, to: 1...5)
        let label = judgement.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? previous.label
            : judgement.label
        return MoodState(
            label: label,
            intensity: intensity,
            reason: judgement.reason,
            updatedAt: 0,
            dimensions: dimensions
        )
    }

    /// 要不要写库：没有任何明显变化就不写（规范 §2）。
    ///
    /// 「明显」的判据：label/intensity 变了，或者任一维度动了 ≥1。
    /// 这样模型每轮都输出同一份心情时不会反复写库、也不会把 `moodUpdatedAt` 一直顶新，
    /// 于是「按时间衰减」才有机会生效。
    public static func shouldWrite(_ judgement: MoodJudgement, previous: MoodState) -> Bool {
        let next = merged(judgement, previous: previous)
        if MoodLexicon.normalize(next.label) != MoodLexicon.normalize(previous.label) { return true }
        if next.intensity != previous.intensity { return true }
        return next.resolvedDimensions.hasVisibleChange(from: previous.resolvedDimensions)
    }

    /// 失败/无配置时的衰减结果（规范 §2）：按小时向 0/2/3 靠一步，不报错。
    ///
    /// 直接复用 `MoodDrift.decay`：它已经处理「强度每小时 -1」「负面词先归平静」
    /// 「6 小时散干净」，这一版又让它把三维也一起往基准挪。
    public static func decayedOnFailure(_ previous: MoodState, now: Int64) -> MoodState {
        MoodDrift.decay(previous, now: now)
    }

    // MARK: - 小工具

    static func number(_ value: Any?) -> Int? {
        if let number = value as? NSNumber {
            // JSON 的 true/false 也是 NSNumber：心情判定里没有布尔字段，直接忽略。
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return nil }
            return Int(number.doubleValue.rounded())
        }
        if let text = value as? String {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if let value = Int(trimmed) { return value }
            if let value = Double(trimmed) { return Int(value.rounded()) }
        }
        return nil
    }

    static func clamp(_ value: Int, to range: ClosedRange<Int>) -> Int {
        min(Swift.max(value, range.lowerBound), range.upperBound)
    }

    static func firstString(_ object: [String: Any], _ keys: [String]) -> String? {
        for key in keys {
            if let value = object[key] as? String, !value.trimmingCharacters(in: .whitespaces).isEmpty {
                return value
            }
        }
        // 字段名大小写不同（Label / VALENCE）：再扫一遍，忽略大小写。
        for (key, value) in object {
            guard keys.contains(where: { $0.lowercased() == key.lowercased() }) else { continue }
            if let text = value as? String, !text.trimmingCharacters(in: .whitespaces).isEmpty {
                return text
            }
        }
        return nil
    }
}
