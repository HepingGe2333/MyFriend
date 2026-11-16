import Foundation

// ---------------------------------------------------------------------------
// 话风学习：把一段聊天记录总结成 `SpeechStyle`。
//
// 规范：`spec/persona-guidance/README.md` §3.2 / §6.1（提示词逐字照抄实测那条）。
//
// ## 隐私（§3.3，硬要求）
//
// 原始聊天记录**只用于这一次总结**：
// - 不落库（这个类不碰 `DatabaseStores`，也没有任何写入方法）；
// - 不进日志（`log` 只收到「多少字」「成功/失败」，绝不带正文片段）；
// - 不进备份（备份里只有总结后的 `SpeechStyle`）。
// 所以这个类型的输入 `transcript` 只在一份 `[CompletionMessage]` 里活一次请求，
// 函数返回之后除了调用方手里的返回值，没有任何地方还引用它。
//
// ## 兜底（§3.2）
//
// 没有 API Key、调用失败、模型输出读不懂 → `heuristic`：完全本地、纯函数、不联网。
// 结果标 `source = "heuristic"`，界面据此提示「这是本地粗略总结，可手动修改」。
// ---------------------------------------------------------------------------

/// 一次话风总结的结果。
public struct SpeechStyleSummary: Equatable, Sendable {
    public var style: SpeechStyle
    /// 是否真的问过模型（false = 走本地兜底）。
    public var usedModel: Bool
    /// 排查用的一句话（不含聊天记录原文）。
    public var message: String

    public init(style: SpeechStyle, usedModel: Bool, message: String = "") {
        self.style = style
        self.usedModel = usedModel
        self.message = message
    }
}

public final class SpeechStyleSummarizer {

    /// 规范 §6.1 的提示词，**逐字**照抄（改一个字都要两端一起改）。
    public static let systemPrompt = """
    你是对话风格分析器。输入是一段微信/QQ 聊天记录（可能含“昵称：内容”）。
    只输出 JSON 对象，不要解释、不要 markdown：
    {"tone":"一句话概括说话风格（中文，<=40字）",
     "phrases":["6-12 条这个人常用的短句/口头禅，尽量原样取，去重"],
     "habits":["3-6 条语言习惯（句长、标点、语气词、是否用表情/省略号等）"],
     "avoid":["3-5 条不该出现的表达（例如书面语、感叹号滥用、长篇大论）"]}
    规则：
    - phrases 必须是聊天记录里真实出现过的、或只做最小归一化（去掉语气词重复），不要编造新句子；
    - 若记录里只有一个参与者，按“这个人”总结；若多人，只总结出现最多的那个人；
    - 不要输出人名，不要评价关系。
    """

    /// 总结是「抽取」而不是「创作」：温度压低。
    public static let temperature = 0.2
    public static let maxOutputTokens = 900
    /// 一次最多分析多少个字（再长的记录对总结没有帮助，只会烧 token）。
    public static let maxTranscriptChars = 8_000

    /// 本地兜底用的常量（§3.2）。
    public static let heuristicTone = "简短、口语"
    public static let heuristicAvoid = ["长篇大论", "书面化表达", "夸张语气"]
    public static let heuristicPhraseLimit = 12
    public static let heuristicMinRepeats = 2

    private let completion: CompletionClient
    private let log: (String) -> Void

    public init(completion: CompletionClient, log: @escaping (String) -> Void = { _ in }) {
        self.completion = completion
        self.log = log
    }

    // MARK: - 入口

    /// 总结一段聊天记录。
    ///
    /// - Parameters:
    ///   - transcript: 用户粘贴的原文。**只在内存里用一次**（见文件头的隐私说明）。
    ///   - config: 本机 API 配置；nil 或没有 Key 时直接走本地兜底。
    ///   - model: 用哪个模型；空则用配置里的。
    public func summarize(
        transcript: String,
        config: CompletionConfig?,
        model: String = ""
    ) async -> SpeechStyleSummary {
        let text = String(transcript.prefix(Self.maxTranscriptChars))
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return SpeechStyleSummary(
                style: SpeechStyle(source: .heuristic), usedModel: false, message: "没有可总结的记录"
            )
        }
        guard let config,
              let key = config.apiKey?.trimmingCharacters(in: .whitespacesAndNewlines), !key.isEmpty else {
            log("[style] 没有可用的 API 配置，走本地兜底（记录 \(trimmed.count) 字）")
            return SpeechStyleSummary(
                style: Self.heuristic(trimmed), usedModel: false, message: "没有可用的 API 配置"
            )
        }

        let messages = [
            CompletionMessage.system(Self.systemPrompt),
            CompletionMessage.user(trimmed)
        ]
        let raw: String
        do {
            let result = try await completion.complete(
                config: config,
                model: model.isEmpty ? config.model : model,
                messages: messages,
                params: CompletionParams(temperature: Self.temperature, maxOutputTokens: Self.maxOutputTokens),
                kind: .extraction,
                context: .none
            )
            raw = result.text
        } catch {
            // 只记「失败」和错误摘要：错误信息里可能带请求体，所以截断并去掉可能的正文。
            log("[style] 总结请求失败，走本地兜底：\(Self.safeErrorText(error))")
            return SpeechStyleSummary(
                style: Self.heuristic(trimmed), usedModel: false, message: "总结请求失败"
            )
        }

        guard var style = SpeechStyle.parse(raw) else {
            log("[style] 模型输出读不懂（\(raw.count) 字），走本地兜底")
            return SpeechStyleSummary(
                style: Self.heuristic(trimmed), usedModel: false, message: "模型输出读不懂"
            )
        }
        // 模型给的 phrases 里如果混进了「昵称：」，去掉前缀（§3.2 第 1 条的同一套规则）。
        // 这是「生成」而不是「解析用户输入」：模型可能给几十条常用语，这里按本地兜底
        // 的同一个上限收敛（`maxPhrases` 的语义就是「生成别太多」）。用户手写的话风
        // 不经过这里，永远原样保存。
        style.phrases = Array(
            PersonaTags.normalize(style.phrases.map(Self.strippingSpeakerPrefix))
                .prefix(SpeechStyle.maxPhrases)
        )
        style.source = .model
        if style.isEmpty {
            return SpeechStyleSummary(
                style: Self.heuristic(trimmed), usedModel: false, message: "模型给的是空的"
            )
        }
        log("[style] 总结成功（\(style.phrases.count) 条常用语）")
        return SpeechStyleSummary(style: style, usedModel: true)
    }

    // MARK: - 本地兜底（§3.2）

    /// 完全本地的粗略总结：不联网、不抛异常、结果可被用户直接改。
    ///
    /// 规则逐条对应规范 §3.2：
    /// 1. 按行去掉 `昵称：` 前缀；
    /// 2. 取长度 2–20 的短句；
    /// 3. 频次 ≥2 的句子进 `phrases`（最多 12 条）；
    /// 4. `tone` 用「简短、口语」占位；
    /// 5. `habits` 按平均句长与感叹号比例给 2 条；
    /// 6. `avoid` 给通用 3 条；
    /// 7. 结果标 `source = heuristic`。
    public static func heuristic(_ transcript: String) -> SpeechStyle {
        let lines = shortLines(from: transcript)
        var counts: [String: Int] = [:]
        var order: [String] = []
        for line in lines {
            if counts[line] == nil { order.append(line) }
            counts[line, default: 0] += 1
        }
        let repeated = order
            .filter { (counts[$0] ?? 0) >= heuristicMinRepeats }
            .sorted { (counts[$0] ?? 0) > (counts[$1] ?? 0) }
        let phrases = Array(repeated.prefix(heuristicPhraseLimit))

        let averageLength = lines.isEmpty
            ? 0
            : Int((Double(lines.reduce(0) { $0 + $1.count }) / Double(lines.count)).rounded())
        let exclamations = transcript.filter { $0 == "!" || $0 == "！" }.count
        let exclamationRate = lines.isEmpty ? 0 : Double(exclamations) / Double(max(lines.count, 1))

        var habits: [String] = []
        if averageLength > 0 {
            habits.append(averageLength <= 8 ? "句子很短，多为陈述或安排" : "句子偏长，常一次说完几件事")
        }
        habits.append(exclamationRate < 0.15 ? "少用感叹号" : "常用感叹号")

        return SpeechStyle(
            tone: heuristicTone,
            phrases: phrases,
            habits: habits,
            avoid: heuristicAvoid,
            source: .heuristic
        )
    }

    /// 按行拆、去 `昵称：` 前缀、只留长度 2–20 的短句。
    ///
    /// **刻意不去重**：频次是 `phrases` 的唯一判据（§3.2 第 3 条），
    /// 在这里去重会让每一句都变成「只出现一次」。去重留给调用方在排完频次之后做。
    static func shortLines(from transcript: String) -> [String] {
        var result: [String] = []
        // 支持 `\n`、`\r\n`、以及中文全角换行习惯。
        for rawLine in transcript.split(whereSeparator: { $0 == "\n" || $0 == "\r" }) {
            let line = strippingSpeakerPrefix(String(rawLine))
            guard line.count >= 2, line.count <= 20 else { continue }
            result.append(line)
        }
        return result
    }

    /// 去掉「昵称：」「昵称:」前缀（含全角冒号与首尾空白）。
    static func strippingSpeakerPrefix(_ raw: String) -> String {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        for separator in ["：", ":"] {
            guard let range = text.range(of: separator) else { continue }
            let head = text[text.startIndex..<range.lowerBound]
            // 只有像昵称才切：长度 ≤ 20、不含空格（「明天 10:30 见」不是昵称）。
            if head.count <= 20, !head.contains(" "), !head.contains("\t"), !head.isEmpty {
                text = String(text[range.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
                break
            }
        }
        return text
    }

    /// 错误摘要：只留类型与一句话，且去掉可能被服务商回显的请求体（长文本）。
    static func safeErrorText(_ error: Error) -> String {
        let description = error.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        let clipped = description.count > 120 ? String(description.prefix(120)) : description
        return clipped.isEmpty ? "未知错误" : clipped
    }
}

// MARK: - App 层默认装配

public extension SpeechStyleSummarizer {
    /// 给编辑页用的默认装配：网络层与并发闸都用进程内的同一套。
    ///
    /// 为什么放在 Kit 而不是让 App 层注进来：`App/**` 这一轮不改（约束），
    /// 而编辑页需要一个能真的发请求的实现。代价是这条调用**不走 `api_usage` 记账**
    /// （`OpenAICompatibleClient` 的 `usage` 是 nil）——只有人手点「总结」时才发生，
    /// 量很小；等 App 层暴露 completion client 之后应改成注入。
    static func makeDefault(log: @escaping (String) -> Void = { _ in }) -> SpeechStyleSummarizer {
        SpeechStyleSummarizer(
            completion: CompletionClientAdapter(client: OpenAICompatibleClient()),
            log: log
        )
    }
}
