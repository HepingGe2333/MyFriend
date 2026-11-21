import Foundation

// ---------------------------------------------------------------------------
// 0.15 危机判定「两段式」（第一段仍是纯词表 `SafetyGuard.isCrisis`）。
//
// 与 Android `domain/CrisisClassifier.kt` 同一套语义（0.15 两端一致，连 system 提示词
// 都逐字对齐）。为什么需要第二段：
// - 纯词表**会漏**：「我把药都倒出来了」「这是最后一次跟你说话」不一定命中「安眠药」；
// - 也会**误判**：「我笑死了」「想死你了」这类日常夸张（词表有负向断言，但挡不全）。
// 词表零成本、零延迟，适合当第一段漏斗：不命中就直接返回「不是危机」，一次网络都不发；
// 命中之后才值得付一次低温度 completion 的代价，问模型「这是真实危险吗」。
//
// 三条硬规则（全部按「安全优先」取舍）：
// 1. **失败即危机**：请求失败 / 超时 / 坏 JSON / 模型说 risk=true 都算危机；
// 2. **只有 risk=false 才降级**：模型明确说「不是真实危机」才不进入危机兜底，
//    不确定（缺字段、类型不对）按危机处理；
// 3. **只判最近一条用户消息，一条消息只判一次**：结论按消息 id 缓存（有上限）。
//
// 超时 5 秒（`CrisisJudgement` 同源）：超时退回第一段结论（词表命中，所以仍然是危机）。
//
// 为什么放在 Domain：这是「输入文本 → 危机/不危机」的纯决策，网络出口是可注入的
// `CrisisJudgeProvider`（生产由 ChatService 用当前 API 配置接上，测试给假实现），
// 所以「true / false / 超时 / 坏 JSON / HTTP 失败 / 缓存」都能逐条跑完。
// ---------------------------------------------------------------------------

/// 第二段判定要发出去的那一次模型请求。
public struct CrisisJudgeRequest: Equatable, Sendable {
    /// system 指令（`CrisisClassifier.systemPrompt`）。
    public var system: String
    /// 只放**最近一条用户消息**的原文（截到 `CrisisJudgement.maxInputChars`，不改写、不摘要）。
    public var userText: String

    public init(system: String = CrisisJudgement.systemPrompt, userText: String) {
        self.system = system
        self.userText = userText
    }
}

/// 第二段判定的模型调用（可注入假实现）。
///
/// 返回模型吐出的**原始文本**（应当是一段 JSON）；抛错 / 返回空 = 这次判定失败。
/// 生产实现由 `ChatService` 用当轮解析出来的 API 配置构造（温度 0、输出上限 200），
/// 测试里直接给一个闭包即可。
public typealias CrisisJudgeProvider = (CrisisJudgeRequest) async throws -> String

/// 判定走到哪一段（与 Android `CrisisStage` 同名同义，只用于日志/测试/诊断）。
public enum CrisisStage: String, Equatable, Sendable {
    /// 第一段词表没命中：不是危机轮，没有发请求。
    case none = "NONE"
    /// 第一段命中：词表结论（没有 provider / 超时 / 无法判定时也停在这里）。
    case lexicon = "LEXICON"
    /// 第二段模型给了明确结论（risk=true / false）。
    case model = "MODEL"
    /// 第二段不可用（失败 / 超时 / 坏 JSON / 缺字段）：按危机兜底。
    case failsafe = "FAILSAFE"
}

/// 一次判定的结论。
///
/// `isCrisis` 是**唯一的决策位**：true 进危机兜底（不许沉默、补安全提示）；
/// false 完全不改变正常对话。其余字段只给日志 / 调试看，不展示给用户。
public struct CrisisVerdict: Equatable, Sendable {
    public var isCrisis: Bool
    public var stage: CrisisStage
    public var reason: String

    public init(isCrisis: Bool, stage: CrisisStage, reason: String = "") {
        self.isCrisis = isCrisis
        self.stage = stage
        self.reason = reason
    }

    /// 第一段词表没命中。
    public static let notCrisis = CrisisVerdict(isCrisis: false, stage: .none)
    /// 第一段命中、第二段没得用的结论（安全优先）。
    public static let lexiconCrisis = CrisisVerdict(isCrisis: true, stage: .lexicon)
    /// 第二段失败时的兜底结论。
    public static let failsafe = CrisisVerdict(isCrisis: true, stage: .failsafe)
}

/// 第二段判定失败的原因（只在日志/测试里用；对外语义永远是「按危机处理」）。
public enum CrisisClassifierError: Error, Equatable, CustomStringConvertible {
    case timeout
    case emptyResponse
    case invalidJSON

    public var description: String {
        switch self {
        case .timeout: return "timeout"
        case .emptyResponse: return "empty response"
        case .invalidJSON: return "invalid JSON"
        }
    }
}

/// 危机两段式判定器：词表漏斗 + 一次低温度模型判定 + 按消息 id 的结论缓存。
///
/// 线程安全：缓存用锁保护，`classify` 可以从多个聊天生成任务并发调用。
public final class CrisisClassifier {

    /// 第二段判定的超时：5 秒（与 Android `CrisisJudgement.TIMEOUT_MS` 一致）。
    public static let defaultTimeout: TimeInterval = CrisisJudgement.timeout

    /// 第二段判定的 system 指令（逐字对齐 Android `CrisisJudgement.SYSTEM`）。
    public static let systemPrompt = CrisisJudgement.systemPrompt

    /// 缓存上限：只服务「最近这条消息」的去重，不需要记住整段会话。
    public static let maxCachedVerdicts = CrisisJudgement.maxCached

    private let provider: CrisisJudgeProvider?
    private let timeout: TimeInterval
    private let log: ((String) -> Void)?

    private let lock = NSLock()
    /// messageId → 结论。
    private var verdicts: [String: CrisisVerdict] = [:]
    /// 插入顺序（FIFO 淘汰；上限很小，数组比再引一张表简单）。
    private var insertionOrder: [String] = []

    /// - Parameters:
    ///   - provider: 默认模型调用；`nil` = 只跑第一段词表（老行为，测试也用它）。
    ///     单次调用可以用 `classify(...)` 的 `judge` 覆盖（优先级：provider > judge）。
    ///   - timeout: 第二段超时（秒），默认 5；`<= 0` 视为不等待（直接按失败处理，测试用）。
    ///   - log: 排查日志（App 层写 debug.log），`nil` = 不记。
    public init(
        provider: CrisisJudgeProvider? = nil,
        timeout: TimeInterval = CrisisClassifier.defaultTimeout,
        log: ((String) -> Void)? = nil
    ) {
        self.provider = provider
        self.timeout = timeout
        self.log = log
    }

    // MARK: - 判定

    /// 判断这一条用户消息是不是危机（`classify` 的布尔快捷方式）。
    public func isCrisis(
        userText: String,
        messageId: String? = nil,
        judge: CrisisJudgeProvider? = nil
    ) async -> Bool {
        await classify(userText: userText, messageId: messageId, judge: judge).isCrisis
    }

    /// 完整判定（带 stage/reason，便于日志与测试对照 Android）。
    ///
    /// - Parameters:
    ///   - userText: **最近一条**用户消息原文（空白 = 不是危机，不请求）。
    ///   - messageId: 这条消息的 id；非空时结论会缓存（同一条消息重试不再请求）。
    ///   - judge: 单次覆盖的模型调用（ChatService 用它接当前 API 配置）。
    ///     优先级：`init(provider:)` > `judge`；两者都 `nil` 时只跑词表（= 0.15 之前的行为）。
    public func classify(
        userText: String,
        messageId: String? = nil,
        judge: CrisisJudgeProvider? = nil
    ) async -> CrisisVerdict {
        let text = userText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return .notCrisis }

        // 第一段：纯词表。不命中 = 不是危机，一次网络都不发。
        guard SafetyGuard.isCrisis(text) else { return .notCrisis }

        // 结论缓存：同一条消息被重试时不再问第二次。
        let key = (messageId ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !key.isEmpty, let cached = cachedVerdict(for: key) { return cached }

        // 显式注入的 provider 优先（测试 / 联调），其次才是调用点传进来的「当前 API 配置」。
        // 两者都没有：保留第一段的结论（安全优先：词表说危机就是危机）。
        guard let activeJudge = provider ?? judge else { return .lexiconCrisis }

        let request = CrisisJudgeRequest(
            userText: CrisisJudgement.userPrompt(text)
        )
        let verdict: CrisisVerdict
        do {
            let raw = try await withTimeout { try await activeJudge(request) }
            verdict = try Self.parse(raw)
            log?("[crisis] 第二段判定：risk=\(verdict.isCrisis)（\(verdict.reason)）")
        } catch is CancellationError {
            // 这一轮被取消（用户点了停止）：不缓存，下次重算；本轮仍按词表兜底。
            log?("[crisis] 第二段判定被取消，按词表结论处理")
            return .lexiconCrisis
        } catch {
            // 超时 / HTTP 失败 / 坏 JSON / 空响应：安全优先，按危机处理。
            log?("[crisis] 第二段判定失败（\(error)），按词表结论处理")
            return .failsafe
        }

        if !key.isEmpty { store(verdict, for: key) }
        return verdict
    }

    // MARK: - 响应解析

    /// 解析模型原文（与 Android `CrisisJudgement.parse` 同一条规则）：
    /// - 容忍代码块 / 前后解释文字 / `"risk":"true"` 这种字符串写法；
    /// - 读不出 `risk` 字段、或值不是 true/false 字符串 → `.failsafe`（**不是 false**）。
    public static func parse(_ raw: String) throws -> CrisisVerdict {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw CrisisClassifierError.emptyResponse }
        guard let json = extractObject(trimmed) else {
            throw CrisisClassifierError.invalidJSON
        }
        guard let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              let rawRisk = object["risk"] else {
            throw CrisisClassifierError.invalidJSON
        }
        guard let risk = booleanValue(rawRisk) else {
            throw CrisisClassifierError.invalidJSON
        }
        let reason = ((object["reason"] as? String) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return CrisisVerdict(
            isCrisis: risk,
            stage: .model,
            reason: String(reason.prefix(CrisisJudgement.maxReasonChars))
        )
    }

    /// 宽松读布尔：真正的 JSON 布尔，或字符串 `"true"` / `"false"`（大小写不敏感）。
    ///
    /// 数字 0/1、`"不确定"`、`null` 都不算：安全判定的失败方向只能是「多说一句安全提示」。
    static func booleanValue(_ raw: Any) -> Bool? {
        if let number = raw as? NSNumber {
            // `as? Bool` 会把 JSON 的 0/1 也当成 false/true（NSNumber 桥接），
            // 这里必须确认它真的是 CFBoolean。
            guard CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
            return number.boolValue
        }
        if let text = raw as? String {
            switch text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "true": return true
            case "false": return false
            default: return nil
            }
        }
        return nil
    }

    /// 从一段可能带 markdown / 前后缀的文本里取出 JSON 对象原文。
    ///
    /// 先试整段（最常见：模型很听话）；不行再从第一个 `{` 到最后一个 `}` 取一次
    /// （模型爱在前后加「好的」「分析如下」）。取不到就返回 nil。
    static func extractObject(_ text: String) -> String? {
        if text.hasPrefix("{"), text.hasSuffix("}") { return text }
        guard let start = text.firstIndex(of: "{"),
              let end = text.lastIndex(of: "}"),
              start < end else { return nil }
        return String(text[start...end])
    }

    // MARK: - 超时

    /// 跑一次 provider，超过 `timeout` 就抛 `.timeout`。
    ///
    /// 用 `withThrowingTaskGroup`：组里超时任务抛错后 `defer { cancelAll() }` 会取消真正的
    /// 请求（URLSession 的 async 接口对取消是配合的），不会留下一个还在跑的请求。
    private func withTimeout(
        _ operation: @escaping () async throws -> String
    ) async throws -> String {
        let seconds = max(timeout, 0)
        return try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                if seconds > 0 {
                    try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                }
                throw CrisisClassifierError.timeout
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else {
                throw CrisisClassifierError.emptyResponse
            }
            return first
        }
    }

    // MARK: - 缓存

    /// 这条消息的缓存结论；没有就 `nil`。
    public func cachedVerdict(for messageId: String) -> CrisisVerdict? {
        lock.lock()
        defer { lock.unlock() }
        return verdicts[messageId]
    }

    private func store(_ verdict: CrisisVerdict, for messageId: String) {
        lock.lock()
        defer { lock.unlock() }
        if verdicts[messageId] == nil {
            insertionOrder.append(messageId)
        }
        verdicts[messageId] = verdict
        while insertionOrder.count > Self.maxCachedVerdicts {
            let oldest = insertionOrder.removeFirst()
            verdicts.removeValue(forKey: oldest)
        }
    }

    /// 清空缓存（数据重置 / 测试用）。
    public func clearCache() {
        lock.lock()
        defer { lock.unlock() }
        verdicts.removeAll()
        insertionOrder.removeAll()
    }
}

/// 第二段判定的提示词 / 时间预算 / 解析常量（逐字对齐 Android `CrisisJudgement`）。
public enum CrisisJudgement {

    /// 整个请求的时间预算：超了就退回词表结论，不等它。
    public static let timeout: TimeInterval = 5

    /// 判定不需要发挥：温度 0，输出很短。
    public static let temperature: Double = 0
    /// 只要一个 JSON 对象，给 200 token 足够（reason 限 40 字）。
    public static let maxOutputTokens = 200

    /// 送进来的用户原话最多截到这么长：够判断语气，又不把整段聊天发出去。
    public static let maxInputChars = 2_000
    /// 理由最多保留多少字（只进日志/调试，不进界面）。
    public static let maxReasonChars = 120
    /// 缓存上限（与 Android `CrisisClassifier.MAX_CACHED` 一致）。
    public static let maxCached = 64

    /// 判定器的 system 提示词（Android 同一份文案）。
    ///
    /// 「拿不准时输出 true」是刻意的：这一段存在的意义就是安全兜底，
    /// 把不确定判成 false 等于把第一段白做的功夫丢掉。
    public static let systemPrompt = [
        "你是危机识别的安全判定器。输入是用户刚发给朋友的一句话（只有这一句，不要替他补上下文）。",
        "",
        "判断他是否处在**真实**的自伤 / 自杀 / 服药过量危机里：",
        "- true：明确说想死、想结束自己的生命、正在自伤、已经吞了药、交代后事、告别；",
        "  或者用隐喻但意图清楚（「我把药都倒出来了」「这是最后一次跟你说话」）。",
        "- false：夸张抱怨（累死了、困死了、笑死、社死）、想念（想死你了）、",
        "  转述或虚构（小说里的人物、别人说的话）、单纯的情绪低落但没有危险意图。",
        "",
        "只输出一个 JSON 对象，不要解释、不要 markdown、不要代码块：",
        #"{"risk":true,"reason":"不超过 40 字的理由"}"#,
        "risk 只能是 true 或 false。拿不准时输出 true。"
    ].joined(separator: "\n")

    /// 发给模型的 user 内容：只有这一句原话（截短），不加任何本地状态。
    public static func userPrompt(_ userMessage: String) -> String {
        String(userMessage.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxInputChars))
    }
}
