import Foundation

// ---------------------------------------------------------------------------
// 花多少钱：纯计算，不碰数据库、不碰网络。
//
// 来源：Android `domain/ApiCost.kt`。移植时逐行对照，连常量与文案都不改，
// 因为「同一批 tokens 在两端算出不同的钱」是最难解释的一类 bug。
//
// 1.9 起这一层**只用来给 `api_usage.costMicroUsd` 记一个估值**：
// 额度、超支拦截、自定义单价全部删除（§五），界面只显示 token，不显示钱。
//
// 单位约定：**micro-USD（百万分之一美元）**，整数存、整数算，避免浮点误差越攒越大。
// 定价表里的数字也是 micro-USD / 百万 tokens（$0.27/百万 tokens = 270_000）。
// ---------------------------------------------------------------------------

/// 一次模型调用读了多少、写了多少。
public struct TokenUsage: Equatable, Sendable {
    public var promptTokens: Int
    public var completionTokens: Int

    public init(promptTokens: Int = 0, completionTokens: Int = 0) {
        self.promptTokens = promptTokens
        self.completionTokens = completionTokens
    }

    public static let zero = TokenUsage()

    /// 「有没有真的报了量」：两处都是 0 时，记账那边就当没拿到 usage。
    public var isMeaningful: Bool { promptTokens > 0 || completionTokens > 0 }
}

/// 某类模型的单价，单位：micro-USD / 百万 tokens。
public struct ModelPrice: Equatable, Sendable {
    public var inputMicroUsdPerMillion: Int64
    public var outputMicroUsdPerMillion: Int64

    public init(inputMicroUsdPerMillion: Int64, outputMicroUsdPerMillion: Int64) {
        self.inputMicroUsdPerMillion = inputMicroUsdPerMillion
        self.outputMicroUsdPerMillion = outputMicroUsdPerMillion
    }
}

/// 一次调用是干什么的。
///
/// 这些不是给用户看的账单科目，而是「他自己在做哪件事」：
/// 聊天是他陪着你，整理记忆是他收拾自己的记性，联网打分是他判断值不值得记。
public enum UsageKind {
    public static let chat = "CHAT"
    public static let consolidation = "CONSOLIDATION"
    public static let scoring = "SCORING"
    public static let agent = "AGENT"
    public static let recall = "RECALL"
    public static let extraction = "EXTRACTION"
    public static let reconsolidation = "RECONSOLIDATION"
    /// 把太长的会话压成一段摘要的过程。
    public static let history = "HISTORY"
    /// 把中文人设/世界翻成当前界面语言（`spec/persona-translation` §6）。
    public static let translation = "TRANSLATION"
    public static let unknown = "UNKNOWN"

    /// 给「用量」页用的展示顺序：先聊天，再他背着你做的事。
    public static let displayOrder: [String] = [
        chat, consolidation, scoring, agent, recall, extraction, reconsolidation, history,
        translation, unknown
    ]

    /// 宽松归一：大写、去空白；不认识的一律归到 UNKNOWN，绝不抛异常。
    public static func normalize(_ raw: String?) -> String {
        let key = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return displayOrder.contains(key) ? key : unknown
    }

    /// 展示标签（跟随界面语言）。刻意写成「他在做什么」而不是「计费科目」。
    public static func label(_ kind: String?) -> String {
        switch normalize(kind) {
        case chat: return String(localized: "聊天")
        case consolidation: return String(localized: "整理记忆")
        case scoring: return String(localized: "判断值不值得记")
        case agent: return String(localized: "自己待着时想事情")
        case recall: return String(localized: "回忆你说过的事")
        case extraction: return String(localized: "从聊天里记东西")
        case reconsolidation: return String(localized: "想起旧事")
        case history: return String(localized: "整理这段对话")
        case translation: return String(localized: "把他的设定翻成你在用的语言")
        default: return String(localized: "其他")
        }
    }

    /// 聊天是用户主动发起的，任何时候都不能拦。
    public static func isChat(_ kind: String?) -> Bool { normalize(kind) == chat }
}

public enum ApiCost {

    /// 1 美元 = 1_000_000 micro-USD。
    public static let MICRO_PER_DOLLAR: Int64 = 1_000_000

    private static let MICRO_PER_MILLION_TOKENS: Int64 = 1_000_000

    /// 不认识的模型用这个保守值：$1.5 / $6 每百万 tokens。
    ///
    /// 宁可算贵一点让人早点发现「这个月花得有点多」，也别算成 0 让人以为不要钱。
    public static let UNKNOWN_PRICE = ModelPrice(
        inputMicroUsdPerMillion: 1_500_000,
        outputMicroUsdPerMillion: 6_000_000
    )

    /// 定价表：模型名前缀 → 单价。
    ///
    /// 按前缀长度从长到短匹配，所以 "gpt-4o-mini" 不会被 "gpt-4o" 抢先；
    /// 带服务商前缀的 id（如 "openai/gpt-4o-mini"）会在第二圈用 contains 兜住。
    /// 价格是公开标价的近似值，只为「心里有数」，不是账单。
    public static let priceTable: [(prefix: String, price: ModelPrice)] = [
        // DeepSeek 系：便宜、也最可能被这个 App 的用户使用。
        ("deepseek-reasoner", ModelPrice(inputMicroUsdPerMillion: 550_000, outputMicroUsdPerMillion: 2_190_000)),
        ("deepseek-chat", ModelPrice(inputMicroUsdPerMillion: 270_000, outputMicroUsdPerMillion: 1_100_000)),
        ("deepseek", ModelPrice(inputMicroUsdPerMillion: 270_000, outputMicroUsdPerMillion: 1_100_000)),
        // Claude
        ("claude-3-5-haiku", ModelPrice(inputMicroUsdPerMillion: 800_000, outputMicroUsdPerMillion: 4_000_000)),
        ("claude-3-5-sonnet", ModelPrice(inputMicroUsdPerMillion: 3_000_000, outputMicroUsdPerMillion: 15_000_000)),
        ("claude-3-opus", ModelPrice(inputMicroUsdPerMillion: 15_000_000, outputMicroUsdPerMillion: 75_000_000)),
        ("claude-3-haiku", ModelPrice(inputMicroUsdPerMillion: 250_000, outputMicroUsdPerMillion: 1_250_000)),
        ("claude", ModelPrice(inputMicroUsdPerMillion: 3_000_000, outputMicroUsdPerMillion: 15_000_000)),
        // OpenAI
        ("gpt-4.1-mini", ModelPrice(inputMicroUsdPerMillion: 400_000, outputMicroUsdPerMillion: 1_600_000)),
        ("gpt-4.1", ModelPrice(inputMicroUsdPerMillion: 2_000_000, outputMicroUsdPerMillion: 8_000_000)),
        ("gpt-4o-mini", ModelPrice(inputMicroUsdPerMillion: 150_000, outputMicroUsdPerMillion: 600_000)),
        ("gpt-4o", ModelPrice(inputMicroUsdPerMillion: 2_500_000, outputMicroUsdPerMillion: 10_000_000)),
        ("gpt-4-turbo", ModelPrice(inputMicroUsdPerMillion: 10_000_000, outputMicroUsdPerMillion: 30_000_000)),
        ("gpt-4", ModelPrice(inputMicroUsdPerMillion: 30_000_000, outputMicroUsdPerMillion: 60_000_000)),
        ("gpt-3.5", ModelPrice(inputMicroUsdPerMillion: 500_000, outputMicroUsdPerMillion: 1_500_000)),
        ("o1-mini", ModelPrice(inputMicroUsdPerMillion: 1_100_000, outputMicroUsdPerMillion: 4_400_000)),
        ("o3-mini", ModelPrice(inputMicroUsdPerMillion: 1_100_000, outputMicroUsdPerMillion: 4_400_000)),
        ("o1", ModelPrice(inputMicroUsdPerMillion: 15_000_000, outputMicroUsdPerMillion: 60_000_000)),
        // Gemini
        ("gemini-2.0-flash", ModelPrice(inputMicroUsdPerMillion: 100_000, outputMicroUsdPerMillion: 400_000)),
        ("gemini-1.5-pro", ModelPrice(inputMicroUsdPerMillion: 1_250_000, outputMicroUsdPerMillion: 5_000_000)),
        ("gemini-1.5-flash", ModelPrice(inputMicroUsdPerMillion: 75_000, outputMicroUsdPerMillion: 300_000)),
        ("gemini", ModelPrice(inputMicroUsdPerMillion: 500_000, outputMicroUsdPerMillion: 1_500_000)),
        // 国产系
        ("qwen", ModelPrice(inputMicroUsdPerMillion: 400_000, outputMicroUsdPerMillion: 1_200_000)),
        ("kimi", ModelPrice(inputMicroUsdPerMillion: 1_700_000, outputMicroUsdPerMillion: 1_700_000)),
        ("moonshot", ModelPrice(inputMicroUsdPerMillion: 1_700_000, outputMicroUsdPerMillion: 1_700_000)),
        ("glm", ModelPrice(inputMicroUsdPerMillion: 600_000, outputMicroUsdPerMillion: 600_000)),
        ("doubao", ModelPrice(inputMicroUsdPerMillion: 110_000, outputMicroUsdPerMillion: 280_000))
    ]

    /// 已按前缀长度降序排好，匹配时先长后短。
    /// 排序在静态初始化时做一次：定价表是常量，不该每次查价都排一遍。
    static let matchOrder: [(prefix: String, price: ModelPrice)] =
        priceTable.sorted { $0.prefix.count > $1.prefix.count }

    /// 把模型名洗成小写、去掉首尾空白；空名返回 nil。
    static func normalizeModel(_ modelName: String?) -> String? {
        guard let name = modelName?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
              !name.isEmpty else { return nil }
        return name
    }

    /// 查内置定价表。先按前缀匹配（长前缀优先），再按包含匹配（兜住 "vendor/model" 这种写法）。
    public static func basePriceFor(_ modelName: String?) -> ModelPrice {
        guard let name = normalizeModel(modelName) else { return UNKNOWN_PRICE }
        if let hit = matchOrder.first(where: { name.hasPrefix($0.prefix) }) { return hit.price }
        if let hit = matchOrder.first(where: { name.contains($0.prefix) }) { return hit.price }
        return UNKNOWN_PRICE
    }

    /// 最终单价。1.9 起没有「用户自定义单价」这件事了（额度整段删除），
    /// 内置表是唯一来源；留着这个函数是为了记账那一处仍然只有一个人口。
    public static func priceFor(_ modelName: String?) -> ModelPrice {
        basePriceFor(modelName)
    }

    /// tokens 数乘以单价，四舍五入到 micro-USD。
    static func costOfTokens(tokens: Int64, pricePerMillion: Int64) -> Int64 {
        if tokens <= 0 || pricePerMillion <= 0 { return 0 }
        return (tokens * pricePerMillion + MICRO_PER_MILLION_TOKENS / 2) / MICRO_PER_MILLION_TOKENS
    }

    /// 估算一次调用的成本（micro-USD）。坏输入不抛异常：负数当成 0，nil 模型名走保守默认值。
    public static func estimateMicroUsd(price: ModelPrice, usage: TokenUsage) -> Int64 {
        let prompt = Int64(max(usage.promptTokens, 0))
        let completion = Int64(max(usage.completionTokens, 0))
        return costOfTokens(tokens: prompt, pricePerMillion: price.inputMicroUsdPerMillion)
            + costOfTokens(tokens: completion, pricePerMillion: price.outputMicroUsdPerMillion)
    }

    public static func estimateCostMicroUsd(modelName: String?, usage: TokenUsage) -> Int64 {
        estimateMicroUsd(price: priceFor(modelName), usage: usage)
    }

    // MARK: - usage 解析

    /// 从模型返回的原始 JSON 文本里抠 usage。
    ///
    /// 三种协议一次认全：
    /// - OpenAI 兼容：`usage.prompt_tokens / usage.completion_tokens`
    /// - Gemini：`usageMetadata.promptTokenCount / candidatesTokenCount`
    /// - Claude：`usage.input_tokens / usage.output_tokens`
    /// 认不出就当 0，不抛异常（记账失败也不能把聊天搞挂）。
    public static func parseUsage(rawBody: String?) -> TokenUsage {
        guard let raw = rawBody, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return .zero
        }
        return parseUsage(root: root)
    }

    /// 已经解析成字典的入口（流式分片里每个 chunk 都走这条，省一次 `JSONSerialization`）。
    public static func parseUsage(root: [String: Any]) -> TokenUsage {
        if let usage = root["usage"] as? [String: Any] {
            let prompt = intOf(usage, "prompt_tokens") ?? intOf(usage, "input_tokens")
            let completion = intOf(usage, "completion_tokens") ?? intOf(usage, "output_tokens")
            if prompt != nil || completion != nil {
                return TokenUsage(
                    promptTokens: max(prompt ?? 0, 0),
                    completionTokens: max(completion ?? 0, 0)
                )
            }
        }
        if let meta = root["usageMetadata"] as? [String: Any] {
            let prompt = intOf(meta, "promptTokenCount")
            let completion = intOf(meta, "candidatesTokenCount")
            if prompt != nil || completion != nil {
                return TokenUsage(
                    promptTokens: max(prompt ?? 0, 0),
                    completionTokens: max(completion ?? 0, 0)
                )
            }
        }
        return .zero
    }

    /// 宽松取整数：`3`、`3.0`、"3" 都认（有的网关把 token 数写成字符串）。
    static func intOf(_ object: [String: Any], _ key: String) -> Int? {
        guard let value = object[key], !(value is NSNull) else { return nil }
        if let number = value as? NSNumber { return number.intValue }
        if let text = value as? String {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if let intValue = Int(trimmed) { return intValue }
            if let doubleValue = Double(trimmed) { return Int(doubleValue) }
        }
        return nil
    }

    // MARK: - 估算 token（服务端不报 usage 时兜底）

    /// 粗略估算 token：中文约 1 字 ≈ 0.7 token，英文约 4 字符 ≈ 1 token。
    ///
    /// 只在服务端不给 usage 时兜底用——宁可估得粗，也不要让「他陪你聊天」的花费记成 0。
    public static func estimateTokens(_ text: String) -> Int {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return 0 }
        var cjk = 0
        var other = 0
        for scalar in text.unicodeScalars {
            let code = scalar.value
            if (0x4E00...0x9FFF).contains(code) || (0x3400...0x4DBF).contains(code) {
                cjk += 1
            } else {
                other += 1
            }
        }
        return max(Int(Double(cjk) * 0.7 + Double(other) / 4.0), 1)
    }

    // MARK: - 月份边界

    /// 本月起点（本地时区的自然月 1 号 00:00）。预算按自然月算，跨月那一刻额度自动回来。
    public static func monthStart(_ now: Int64, timeZone: TimeZone = .current) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let date = Date(timeIntervalSince1970: Double(now) / 1000.0)
        let components = calendar.dateComponents([.year, .month], from: date)
        guard let first = calendar.date(from: DateComponents(
            timeZone: timeZone, year: components.year, month: components.month, day: 1
        )) else { return now }
        return Int64((first.timeIntervalSince1970 * 1000).rounded())
    }

    /// 下个月起点：跨年也算得对（12 月 → 次年 1 月）。
    public static func nextMonthStart(_ now: Int64, timeZone: TimeZone = .current) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let start = Date(timeIntervalSince1970: Double(monthStart(now, timeZone: timeZone)) / 1000.0)
        guard let next = calendar.date(byAdding: .month, value: 1, to: start) else { return now }
        return Int64((next.timeIntervalSince1970 * 1000).rounded())
    }

    // MARK: - 金额展示

    /// 金额的中文展示，如 "$0.42"；不到一分钱就说不到一分钱。
    public static func formatUsd(_ microUsd: Int64) -> String {
        if microUsd <= 0 { return "$0.00" }
        if microUsd < 10_000 { return "<$0.01" }
        let cents = (microUsd + 5_000) / 10_000
        let whole = cents / 100
        let fraction = String(format: "%02d", Int(cents % 100))
        return "$\(whole).\(fraction)"
    }

}
