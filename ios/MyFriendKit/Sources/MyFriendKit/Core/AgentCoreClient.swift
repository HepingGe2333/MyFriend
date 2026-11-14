import Foundation

// ---------------------------------------------------------------------------
// agent 决策层：本机实现 ⇄ 远程 `agentd` 服务（0.11.0）。
//
// 这一层要解决的是「同一个判断走哪条路」：
//
// - `NativeAgentCoreClient`：直接委托 `SleepGate` / `DeferReply` / `ProactiveLevel` /
//   `ToolArgClamp`。这四个纯函数是**唯一真源**，这里不复制任何判定逻辑，只做参数整形
//   （与 Android 的 `InProcessAgentCore` 一一对应）。
// - `RemoteAgentCoreClient`：调 `agentd` 的四个 `/v1/core/*` 端点。失败要能被看见
//   （HTTP 状态、错误体、坏 JSON、超时都翻成 `AgentCoreClientError`）。
// - `FallbackAgentCoreClient`：先试远程，失败回退本机并记一条日志。
//   装成它之后，「服务没起」不会让聊天失败 —— 只是慢一个超时。
//
// ## 字段名为什么长这样
//
// 契约在仓库根的 `spec/agent-core/cases/*.json`（两端共读），线上字段名的唯一出处是
// Android 的 `agentcore/.../AgentCoreProtocol.kt`。这里的数据类**逐字**对齐
// `agentd` 的请求/响应体：`sleepEnabled` / `dndOnSleep` / `sleepMinutes` / `wakeMinutes` /
// `nowMinutes` / `userMessagesInWindow` / `wakeThreshold` → `wake` / `moodRelaxed` /
// `reason` / `marksRead`；`minutes` / `reason` / `now` → `earliestAt` / `latestAt` /
// `minutes` / `reason`；`level` / `moodIntensity` → `dailyCap`；`tool` / `args` → `args`。
// `AgentCoreClientTests` 里有一条 golden 断言：字段名改了会红。
//
// ## 怎么切到远程
//
// 选择顺序（`AgentCoreClientFactory`）：`MYFRIEND_AGENT_CORE` 环境变量 →
// Info.plist 的 `MFAgenCoreMode` → 默认 `.native`。**没有用户可见开关**。
//
// ```
// # 本机（默认）：零网络请求
// MYFRIEND_AGENT_CORE=native
// # 远程：先试 agentd，失败回退本机（推荐联调方式）
// MYFRIEND_AGENT_CORE=remote MYFRIEND_AGENT_CORE_URL=http://127.0.0.1:8788
// # 远程严格模式：远程失败直接报错（只用于验证 agentd 真的被用上了）
// MYFRIEND_AGENT_CORE=remote-strict
// ```
//
// 模拟器里用 `SIMCTL_CHILD_MYFRIEND_AGENT_CORE=remote` 前缀传进去（见交付报告）。
// ---------------------------------------------------------------------------

// MARK: - 协议

/// agent 决策层的统一入口。
///
/// 四个方法都是异步、可抛错的：远程实现需要一次带超时的 HTTP 请求，而「请求失败」必须
/// 能被上层看见（由 `FallbackAgentCoreClient` 决定回退，而不是在客户端里静默吞掉）。
/// 本机实现虽然不会失败，签名也保持一致 —— 调用点不该因为「换了一条路」改形状。
public protocol AgentCoreClient: Sendable {
    /// 睡眠 / 免打扰 / 唤醒阈值判定（§六）。返回的 `wake` 就是「这一轮要不要回」。
    func sleepDecision(_ input: SleepInput) async throws -> SleepDecision

    /// `defer_reply` 的时间计划：缺省 45 分钟、夹 5–720、窗口 +30 分钟、理由截 80 字。
    func deferPlan(minutes: Int?, reason: String?, now: Int64) async throws -> DeferPlan

    /// 每日主动上限的**基础值**（= `ProactiveLevel.dailyCap`，1–5）。
    ///
    /// 心情修正不在这里：只有强度、没有心情标签时无法判断 valence / energy，
    /// agentd 对「只有 moodIntensity」的处理也是只按主动程度换算；调用点拿到基础值后
    /// 再用本地 `MoodBehavior.effectiveDailyCap` 叠心情（与 0.10.x 逐字一致）。
    func proactiveCap(level: Int, moodIntensity: Int?) async throws -> Int

    /// 工具参数夹取：只夹已知数值键（`importance` / `count` / `minutes`），其余键原样保留，
    /// 缺省值补在结果里。不认识的工具原样返回（拒绝未知工具是服务端 HTTP 层的事）。
    func clampToolArgs(tool: String, args: [String: JSONValue]) async throws -> [String: JSONValue]

    /// 这个实现背后是哪条路（0.11.1 审计 P2-1）。
    ///
    /// 调用点需要区分「严格远程」与「带回退的远程 / 本机」：前者失败必须被看见，
    /// 后者失败要静默回退。默认 `.native`（本机实现不会失败）。
    var agentCoreMode: AgentCoreMode { get }
}

public extension AgentCoreClient {
    var agentCoreMode: AgentCoreMode { .native }
}

/// `clampToolArgs` / agentd `tool-args` 认识的工具名。
public enum AgentCoreTool {
    public static let remember = "remember"
    public static let webSearch = "web_search"
    public static let deferReply = "defer_reply"
}

// MARK: - 数据类（字段名 = agentd 的 JSON 字段名）

/// 睡眠判定的入参。字段名与 `/v1/core/sleep-decision` 的请求体逐字一致。
public struct SleepInput: Equatable, Sendable, Encodable {
    public var sleepEnabled: Bool
    public var dndOnSleep: Bool
    public var sleepMinutes: Int?
    public var wakeMinutes: Int?
    public var nowMinutes: Int
    public var userMessagesInWindow: Int
    public var wakeThreshold: Int

    public init(
        sleepEnabled: Bool,
        dndOnSleep: Bool,
        sleepMinutes: Int? = nil,
        wakeMinutes: Int? = nil,
        nowMinutes: Int,
        userMessagesInWindow: Int,
        wakeThreshold: Int
    ) {
        self.sleepEnabled = sleepEnabled
        self.dndOnSleep = dndOnSleep
        self.sleepMinutes = sleepMinutes
        self.wakeMinutes = wakeMinutes
        self.nowMinutes = nowMinutes
        self.userMessagesInWindow = userMessagesInWindow
        self.wakeThreshold = wakeThreshold
    }

    private enum CodingKeys: String, CodingKey {
        case sleepEnabled
        case dndOnSleep
        case sleepMinutes
        case wakeMinutes
        case nowMinutes
        case userMessagesInWindow
        case wakeThreshold
    }

    /// 显式写出可空字段（`null` 而不是省略）：与 Android `AgentCoreCodec.encodeSleepInput`
    /// 的报文逐字一致，抓包比对时两边长得一样。
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(sleepEnabled, forKey: .sleepEnabled)
        try container.encode(dndOnSleep, forKey: .dndOnSleep)
        try container.encode(sleepMinutes, forKey: .sleepMinutes)
        try container.encode(wakeMinutes, forKey: .wakeMinutes)
        try container.encode(nowMinutes, forKey: .nowMinutes)
        try container.encode(userMessagesInWindow, forKey: .userMessagesInWindow)
        try container.encode(wakeThreshold, forKey: .wakeThreshold)
    }
}

/// 睡眠判定的结果。字段名与 `/v1/core/sleep-decision` 的响应体逐字一致。
///
/// `marksRead` 是 0.11.0 加的兼容字段：少了它，远程模式会在「免打扰」时悄悄把已读补回去。
/// 老版本 agentd（0.10.x）不回这个字段，解码时按 `true` 处理（与 Android
/// `AgentCoreCodec.decodeSleepDecision` 的兼容规则一致）。
public struct SleepDecision: Equatable, Sendable {
    public var wake: Bool
    public var moodRelaxed: Bool
    public var reason: String
    public var marksRead: Bool

    public init(wake: Bool, moodRelaxed: Bool, reason: String, marksRead: Bool = true) {
        self.wake = wake
        self.moodRelaxed = moodRelaxed
        self.reason = reason
        self.marksRead = marksRead
    }

    /// 此刻在他的睡眠窗口里（= 不会回，或只是被叫醒）。
    ///
    /// 0.11.1 审计 P2-10：与 Kotlin 契约对齐。`agentcore` 的
    /// `SleepDecision.asleep` 是 `!wake || moodRelaxed`（被叫醒时人仍在睡眠窗口里），
    /// 这里原来写的 `!wake` 会在「被叫醒」时返回 false —— 与共享契约不一致。
    public var asleep: Bool { !wake || moodRelaxed }

    /// 睡眠中开着免打扰（= 连已读都不留）。
    public var dnd: Bool { !marksRead }

    /// 翻成聊天链路用的判定。
    ///
    /// 映射关系（与 `SleepGate.decide` 的返回值一一对应）：
    /// - `wake = true, moodRelaxed = true` → `.wokenUp`
    /// - `wake = true, moodRelaxed = false` → `.reply`
    /// - `wake = false, marksRead = false` → `.sleepingDoNotDisturb`
    /// - `wake = false, marksRead = true` → `.sleepingWaiting`
    public var replyDecision: SleepReplyDecision {
        if wake { return moodRelaxed ? .wokenUp : .reply }
        return marksRead ? .sleepingWaiting : .sleepingDoNotDisturb
    }

    init(json: [String: JSONValue], marksReadFallback: Bool = false) throws {
        wake = try AgentCoreJSON.requiredBool(json, "wake")
        moodRelaxed = try AgentCoreJSON.bool(json, "moodRelaxed", fallback: false)
        reason = try AgentCoreJSON.text(json, "reason", fallback: "")
        // 0.11.1 审计 P2-9：老 agentd（0.10.x）不返回 marksRead。缺省值不能写死 true ——
        // 「睡着 + 免打扰」时会因此悄悄把已读补回去（与本机 `SleepGate` 的 `marksRead = !dnd` 相反）。
        // 远程客户端按输入推断一个更准的缺省值（`dndOnSleep && !wake` → 不标已读），
        // 单独解码时缺省 false（宁可不补已读，也不要在免打扰里补）。
        marksRead = try AgentCoreJSON.bool(json, "marksRead", fallback: marksReadFallback)
    }
}

// MARK: - 模式与超时

/// 决策层的两种走法。
public enum AgentCoreMode: Equatable, Sendable, CustomStringConvertible {
    /// 本机 Swift 实现：零网络请求（默认）。
    case native
    /// 远程 `agentd`。`preferredFallback = true` 时远程失败回退本机；
    /// `false` 是「严格远程」，只用于验证 agentd 真的被用上了（失败会冒到调用点）。
    case remote(preferredFallback: Bool)

    public var description: String {
        switch self {
        case .native: return "native"
        case .remote(let preferredFallback): return preferredFallback ? "remote" : "remote-strict"
        }
    }

    /// 这个模式下会不会发网络请求。
    public var usesRemote: Bool {
        if case .remote = self { return true }
        return false
    }

    /// 严格远程（没有本机兜底）：失败要冒到调用点，而不是被静默回退。
    public var isStrictRemote: Bool {
        if case .remote(let preferredFallback) = self { return !preferredFallback }
        return false
    }
}

/// 远程调用的超时。
///
/// `connect` 是 URLSession 的「等第一个字节」超时（建连 + 服务端开始回话），
/// `overall` 是整个请求的硬上限（URLSession 的资源超时 + 一层 Task 赛跑兜底）。
/// `URLSession` 没有独立的「建连超时」配置项，所以这里不假装有：`connect` 就是
/// `timeoutIntervalForRequest`，`overall` 是 `timeoutIntervalForResource`。
public struct AgentCoreTimeouts: Equatable, Sendable {
    public var connect: TimeInterval
    public var overall: TimeInterval

    public init(connect: TimeInterval = 3, overall: TimeInterval = 8) {
        self.connect = connect
        self.overall = overall
    }

    public static let `default` = AgentCoreTimeouts()
}

/// 端点常量：与 `agentd` 的 `AgentCoreProtocol` 一致。
public enum AgentCoreEndpoint {
    public static let health = "/health"
    public static let sleepDecision = "/v1/core/sleep-decision"
    public static let deferPlan = "/v1/core/defer-plan"
    public static let proactiveCap = "/v1/core/proactive-cap"
    public static let toolArgs = "/v1/core/tool-args"
}

/// 决策层自己的错误类型。
///
/// 为什么不用 `ApiException`：那一类面向「和模型服务对话」的用户错误（带 HTTP 状态码与
/// 中文提示），而这里失败时用户什么也不该看见 —— 回退本机、写一行日志就够了。
/// 用独立类型能让日志一眼分清「网络断了」还是「协议对不上」。
public enum AgentCoreClientError: Error, Equatable, Sendable, CustomStringConvertible {
    /// 地址拼不出来（baseURL 写坏了）。
    case invalidURL(String)
    /// 回包不是 HTTP 响应。
    case notHTTP
    /// 非 2xx：`message` 取自 agentd 的统一错误体（`{"error":{"message","code"}}`）。
    case http(status: Int, message: String)
    /// 回包不是合法 JSON 对象，或字段类型不对。
    case badJSON(String)
    /// 回包缺少契约要求的字段。
    case missingField(String)
    /// 超时。
    case timedOut(TimeInterval)
    /// 传输层失败（DNS / 连接被拒 / 断网……）。
    case transport(String)
    /// 响应体超过上限（默认 1 MB）：恶意/坏掉的 agentd 不能用一条响应把内存打高。
    case responseTooLarge(limit: Int)

    public var description: String {
        switch self {
        case .invalidURL(let raw):
            return "agentd 地址不合法：\(raw)"
        case .notHTTP:
            return "agentd 没有返回 HTTP 响应"
        case .http(let status, let message):
            return "agentd 返回 HTTP \(status)：\(message)"
        case .badJSON(let detail):
            return "agentd 回包不是合法的 JSON：\(detail)"
        case .missingField(let key):
            return "agentd 回包缺少字段 \(key)"
        case .timedOut(let seconds):
            return String(format: "agentd 超时（%.1f 秒）", seconds)
        case .transport(let detail):
            return "连接 agentd 失败：\(detail)"
        case .responseTooLarge(let limit):
            return "agentd 响应体超过上限（\(limit) 字节）"
        }
    }

    /// 任意错误的可读描述（只进日志/诊断，不进用户界面）。
    public static func describe(_ error: Error) -> String {
        if let core = error as? AgentCoreClientError { return core.description }
        return String(describing: error)
    }
}

extension AgentCoreClientError: LocalizedError {
    /// 用户可见的那句话（只在 `remote-strict` 联调模式会冒到界面：严格模式的失败要能被看见，
    /// 否则它和普通回退模式没有区别）。普通回退模式不会显示它 —— 错误被 `decide` 换成了本机结果。
    public var errorDescription: String? {
        String(localized: "决策服务不可用（严格远程模式），请检查 agentd 是否在运行")
    }
}

// MARK: - 本机实现

/// 本机实现：直接委托 Domain 里的四个纯函数，**一行判定逻辑都不复制**。
public struct NativeAgentCoreClient: AgentCoreClient {

    public init() {}

    // MARK: 睡眠

    public func sleepDecision(_ input: SleepInput) async throws -> SleepDecision {
        sleepDecisionNow(input)
    }

    /// 同步版本（不抛错）：给「调用点自己已经是同步的、或兜底时不能再抛」的地方用。
    ///
    /// 取舍：协议是异步的（远程要走网络），但本机纯计算只有微秒级。给一个同步入口
    /// 而不是让同步调用点被迫改成 async，是为了不让「心跳 / 工具执行」这种
    /// 本来与网络无关的链路凭空多一层 await。
    public func sleepDecisionNow(_ input: SleepInput) -> SleepDecision {
        let evaluation = SleepGate.evaluate(
            sleepEnabled: input.sleepEnabled,
            sleepMinutes: input.sleepMinutes,
            wakeMinutes: input.wakeMinutes,
            minuteOfDay: input.nowMinutes,
            dndOnSleep: input.dndOnSleep,
            recentUserMessages: input.userMessagesInWindow,
            wakeThreshold: input.wakeThreshold
        )
        return SleepDecision(
            wake: evaluation.shouldReply,
            moodRelaxed: evaluation.woken,
            reason: Self.reason(input: input, evaluation: evaluation),
            marksRead: evaluation.marksRead
        )
    }

    /// 「为什么没回」这句中文与 Android `InProcessAgentCore.sleepDecision` 逐字一致：
    /// 远程与进程内不该给出两种说法。
    static func reason(input: SleepInput, evaluation: SleepGate.Evaluation) -> String {
        if !evaluation.asleep { return "醒着，会正常回复" }
        if evaluation.dnd { return "免打扰中，不会醒" }
        if evaluation.woken { return "被你叫醒了，缓了缓" }
        if let clock = wakeClockText(input) { return "睡着了 · \(clock) 醒" }
        return "睡着了"
    }

    static func wakeClockText(_ input: SleepInput) -> String? {
        guard input.sleepEnabled else { return nil }
        let window = SleepSchedule.window(sleepMinutes: input.sleepMinutes, wakeMinutes: input.wakeMinutes)
        return ServiceTime.clockText(window.wake)
    }

    // MARK: defer_reply

    public func deferPlan(minutes: Int?, reason: String?, now: Int64) async throws -> DeferPlan {
        deferPlanNow(minutes: minutes, reason: reason, now: now)
    }

    public func deferPlanNow(minutes: Int?, reason: String?, now: Int64) -> DeferPlan {
        DeferReply.plan(minutes: minutes, reason: reason, now: now)
    }

    // MARK: 主动上限

    public func proactiveCap(level: Int, moodIntensity: Int?) async throws -> Int {
        proactiveCapNow(level: level, moodIntensity: moodIntensity)
    }

    /// 只按主动程度换算（= `ProactiveLevel.dailyCap`）。
    ///
    /// 心情修正**不在这里**：`moodIntensity` 单独存在时没有 valence / energy，挪不了上限；
    /// 调用点拿本地 `MoodTuning` 再叠 `MoodBehavior.effectiveDailyCap`（与 0.10.x 一致）。
    public func proactiveCapNow(level: Int, moodIntensity: Int?) -> Int {
        _ = moodIntensity
        return ProactiveLevel.dailyCap(level)
    }

    // MARK: 工具参数

    public func clampToolArgs(tool: String, args: [String: JSONValue]) async throws -> [String: JSONValue] {
        clampToolArgsNow(tool: tool, args: args)
    }

    /// 只夹已知数值键，缺省值补在结果里，其余键原样保留。
    ///
    /// 不认识的工具**原样返回**（与 Android `InProcessAgentCore.clampToolArgs` 一致）：
    /// 拒绝未知工具是服务端 HTTP 层的事，客户端不替服务端定义工具表。
    public func clampToolArgsNow(tool: String, args: [String: JSONValue]) -> [String: JSONValue] {
        var out = args
        switch tool {
        case AgentCoreTool.remember:
            out["importance"] = .int(Int64(ToolArgClamp.clampImportance(
                AgentCoreJSON.int(args["importance"]) ?? ToolArgClamp.importanceDefault
            )))
        case AgentCoreTool.webSearch:
            out["count"] = .int(Int64(ToolArgClamp.clampCount(
                AgentCoreJSON.int(args["count"]) ?? ToolArgClamp.countDefault
            )))
        case AgentCoreTool.deferReply:
            out["minutes"] = .int(Int64(ToolArgClamp.clampDeferMinutes(
                AgentCoreJSON.int(args["minutes"]) ?? ToolArgClamp.minutesDefault
            )))
        default:
            break
        }
        return out
    }
}

// MARK: - 远程实现

/// `URLSession` 不是在所有 SDK 上都标了 `Sendable`，用一个不可变的盒子隔一层，
/// 让 `RemoteAgentCoreClient` 可以老实声明成 `Sendable` 的值类型。
private final class AgentCoreSessionBox: @unchecked Sendable {
    let session: URLSession
    init(_ session: URLSession) { self.session = session }
}

/// 跨 `TaskGroup` 回传的 HTTP 结果（`URLResponse` 在部分 SDK 上没有 `Sendable` 标注）。
private struct AgentCoreHTTPResult: @unchecked Sendable {
    var data: Data
    var response: URLResponse
}

/// 远程实现：`URLSession` 调 `agentd` 的四个端点。
///
/// 超时、HTTP 状态、错误体、坏 JSON、字段缺失全部翻成 `AgentCoreClientError`；
/// 取消（用户点了停止）保持 `CancellationError`，不当成「服务挂了」。
public struct RemoteAgentCoreClient: AgentCoreClient {

    public let baseURL: URL
    public let timeouts: AgentCoreTimeouts

    /// 这个实现就是「严格远程」：失败原样抛给调用点，绝不自己回退。
    public var agentCoreMode: AgentCoreMode { .remote(preferredFallback: false) }

    /// 响应体上限：1 MB（与聊天侧 `readAll` 的上限同一个数量级）。
    ///
    /// 0.11.1 安全审计 P2-8 / iOS 审计 P2-11：agentd 是客户端信任的地址，但在协议里
    /// 「对方能回多少」不该没有边界 —— 一个坏掉的/被替换的服务端用一条高带宽响应就能把
    /// 决策线程的内存打高（决策被聊天链路同步调用，OOM 会直接杀 App）。
    public static let maxResponseBytes = 1 * 1024 * 1024

    private let sessionBox: AgentCoreSessionBox
    private let log: @Sendable (String) -> Void

    /// - Parameters:
    ///   - baseURL: 例如 `http://127.0.0.1:8788`（不带 `/v1`）。
    ///   - timeouts: 连接 3 秒 / 整体 8 秒（默认）。
    ///   - session: 单测注入自定义 `URLProtocol` 用；nil = 按 `timeouts` 新建一个会话。
    ///   - log: 远程**成功**时记一行（失败由 `FallbackAgentCoreClient` 记）。
    ///     App 在 Debug 构建里接到 `Documents/debug.log`，联调时能直接看到「真的走了远程」。
    public init(
        baseURL: URL,
        timeouts: AgentCoreTimeouts = .default,
        session: URLSession? = nil,
        log: @escaping @Sendable (String) -> Void = { _ in }
    ) {
        self.baseURL = baseURL
        self.timeouts = timeouts
        self.sessionBox = AgentCoreSessionBox(session ?? Self.makeSession(timeouts: timeouts))
        self.log = log
    }

    public static func makeSession(timeouts: AgentCoreTimeouts) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        // 建连 / 等首个字节：3 秒。整体硬上限另有 `timeoutIntervalForResource` + Task 赛跑。
        configuration.timeoutIntervalForRequest = max(timeouts.connect, 0.1)
        configuration.timeoutIntervalForResource = max(timeouts.overall, 0.1)
        configuration.waitsForConnectivity = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpAdditionalHeaders = ["Accept": "application/json"]
        return URLSession(configuration: configuration)
    }

    // MARK: 四个端点

    public func sleepDecision(_ input: SleepInput) async throws -> SleepDecision {
        let body = try JSONEncoder().encode(input)
        let json = try await post(AgentCoreEndpoint.sleepDecision, body: body)
        do {
            // 0.11.1 审计 P2-9：0.10.x 的 agentd 没有 `marksRead`。缺省值按**输入**推断：
            // 「这一轮是免打扰（dndOnSleep 且没被叫醒）」时不要补已读，否则远程模式会在
            // 免打扰里把已读补回去（与本机 `SleepGate` 的 `marksRead = !dnd` 语义相反）。
            let wakeFlag = try AgentCoreJSON.requiredBool(json, "wake")
            let fallback = !(input.dndOnSleep && !wakeFlag)
            return try SleepDecision(json: json, marksReadFallback: fallback)
        } catch let error as AgentCoreClientError {
            throw error
        } catch {
            throw AgentCoreClientError.badJSON("睡眠判定响应字段不对：\(error)")
        }
    }

    public func deferPlan(minutes: Int?, reason: String?, now: Int64) async throws -> DeferPlan {
        let request = DeferPlanRequest(minutes: minutes, reason: reason, now: now)
        let json = try await post(AgentCoreEndpoint.deferPlan, body: try JSONEncoder().encode(request))
        do {
            return DeferPlan(
                minutes: try AgentCoreJSON.requiredInt(json, "minutes"),
                reason: try AgentCoreJSON.text(json, "reason", fallback: ""),
                earliestAt: try AgentCoreJSON.requiredInt64(json, "earliestAt"),
                latestAt: try AgentCoreJSON.requiredInt64(json, "latestAt")
            )
        } catch let error as AgentCoreClientError {
            throw error
        } catch {
            throw AgentCoreClientError.badJSON("defer 计划响应字段不对：\(error)")
        }
    }

    public func proactiveCap(level: Int, moodIntensity: Int?) async throws -> Int {
        let request = ProactiveCapRequest(level: level, moodIntensity: moodIntensity)
        let json = try await post(AgentCoreEndpoint.proactiveCap, body: try JSONEncoder().encode(request))
        do {
            return try AgentCoreJSON.requiredInt(json, "dailyCap")
        } catch let error as AgentCoreClientError {
            throw error
        } catch {
            throw AgentCoreClientError.badJSON("主动上限响应字段不对：\(error)")
        }
    }

    public func clampToolArgs(tool: String, args: [String: JSONValue]) async throws -> [String: JSONValue] {
        let request = ToolArgsRequest(tool: tool, args: args)
        let json = try await post(AgentCoreEndpoint.toolArgs, body: try JSONEncoder().encode(request))
        guard let value = json["args"], !value.isNull else {
            throw AgentCoreClientError.missingField("args")
        }
        guard case .object(let clamped) = value else {
            throw AgentCoreClientError.badJSON("工具参数响应的 args 必须是 JSON 对象")
        }
        return clamped
    }

    // MARK: 请求

    private func post(_ path: String, body: Data) async throws -> [String: JSONValue] {
        guard let url = Self.url(baseURL: baseURL, path: path) else {
            throw AgentCoreClientError.invalidURL("\(baseURL.absoluteString) + \(path)")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = body
        // 0.11.1 审计 P1-2：`URLRequest.timeoutInterval` 会**覆盖**会话级的
        // `timeoutIntervalForRequest`。这里原来设成 `overall`（8 秒），把会话里的
        // `connect`（3 秒）彻底作废了 —— agentd 不可达时每次决策都要等满 8 秒。
        // 现在请求级 = connect（= 等首个字节/空闲超时），整体硬上限由会话的
        // `timeoutIntervalForResource` 与 `send()` 里的 Task 赛跑（overall）兜住。
        request.timeoutInterval = max(timeouts.connect, 0.1)
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await send(request)
        guard let http = response as? HTTPURLResponse else {
            throw AgentCoreClientError.notHTTP
        }
        guard (200...299).contains(http.statusCode) else {
            throw AgentCoreClientError.http(status: http.statusCode, message: Self.errorMessage(from: data))
        }
        guard data.count <= Self.maxResponseBytes else {
            // `send()` 已经在读的时候就截住了；这里是第二道（自定义 session 也可能绕过）。
            throw AgentCoreClientError.responseTooLarge(limit: Self.maxResponseBytes)
        }
        log("agentcore: \(path) 远程成功（HTTP \(http.statusCode)）")
        guard !data.isEmpty else {
            throw AgentCoreClientError.badJSON("响应体为空")
        }
        do {
            return try JSONDecoder().decode([String: JSONValue].self, from: data)
        } catch {
            let preview = String(decoding: data.prefix(200), as: UTF8.self)
            throw AgentCoreClientError.badJSON("\(error)；原文：\(preview)")
        }
    }

    /// 发请求 + 整体超时兜底。
    ///
    /// 三条边界：① 请求级 `timeoutInterval = connect`（见 `post`）负责「等不到首个字节」；
    /// ② 会话资源超时 = overall；③ 再压一层 `Task.sleep` 赛跑兜住「连接卡在半路」。
    /// 取消（用户点了停止）原样抛 `CancellationError`。
    private func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
        let session = sessionBox.session
        let overall = timeouts.overall
        let started = Date()
        do {
            let result = try await withThrowingTaskGroup(of: AgentCoreHTTPResult.self) { group in
                group.addTask {
                    try await Self.readResponse(session: session, request: request)
                }
                group.addTask {
                    try await Task.sleep(nanoseconds: UInt64(max(overall, 0.001) * 1_000_000_000))
                    throw AgentCoreClientError.timedOut(overall)
                }
                guard let first = try await group.next() else {
                    throw AgentCoreClientError.transport("没有拿到任何响应")
                }
                group.cancelAll()
                return first
            }
            return (result.data, result.response)
        } catch let error as AgentCoreClientError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch let urlError as URLError {
            throw Self.mapURLError(
                urlError, timeouts: timeouts, elapsed: Date().timeIntervalSince(started)
            )
        } catch {
            throw AgentCoreClientError.transport(String(describing: error))
        }
    }

    /// 边读边数：超过 `maxResponseBytes` 立刻中止，不把整份响应读进内存。
    ///
    /// 0.11.1：`session.data(for:)` 是「先整份读进内存再判断」，上限等于没有。
    /// 换成 `bytes(for:)` 之后，恶意/坏掉的服务端最多让我们多读一个字节。
    fileprivate static func readResponse(
        session: URLSession,
        request: URLRequest
    ) async throws -> AgentCoreHTTPResult {
        let (bytes, response) = try await session.bytes(for: request)
        if let http = response as? HTTPURLResponse,
           http.expectedContentLength > Int64(maxResponseBytes) {
            throw AgentCoreClientError.responseTooLarge(limit: maxResponseBytes)
        }
        var data = Data()
        data.reserveCapacity(16 * 1024)
        for try await byte in bytes {
            guard data.count < maxResponseBytes else {
                throw AgentCoreClientError.responseTooLarge(limit: maxResponseBytes)
            }
            data.append(byte)
        }
        return AgentCoreHTTPResult(data: data, response: response)
    }

    static func mapURLError(
        _ error: URLError,
        timeouts: AgentCoreTimeouts,
        elapsed: TimeInterval? = nil
    ) -> Error {
        switch error.code {
        case .cancelled:
            // 上层取消（用户点了停止）：这不是「服务不可用」。
            return CancellationError()
        case .timedOut:
            // 0.11.1 审计 P1-2：请求级（空闲/等首个字节）= connect，会话资源级 = overall。
            // 用实际耗时区分两者，日志里才能看出到底是「3 秒连不上」还是「8 秒没回完」。
            if let elapsed, timeouts.overall > 0, elapsed >= timeouts.overall * 0.9 {
                return AgentCoreClientError.timedOut(timeouts.overall)
            }
            return AgentCoreClientError.timedOut(timeouts.connect)
        case .cannotFindHost, .dnsLookupFailed:
            return AgentCoreClientError.transport("解析不到 agentd 地址")
        case .cannotConnectToHost, .networkConnectionLost, .notConnectedToInternet:
            return AgentCoreClientError.transport("连不上 agentd（服务没起或网络不通）")
        default:
            // 系统文案（`localizedDescription`）是给开发者看的，不进用户界面；日志里够用。
            return AgentCoreClientError.transport("URLError code=\(error.code.rawValue)")
        }
    }

    static func errorMessage(from data: Data) -> String {
        if let object = try? JSONDecoder().decode([String: JSONValue].self, from: data),
           case .object(let error)? = object["error"] {
            let message: String? = if case .string(let text)? = error["message"] { text } else { nil }
            let code: String? = if case .string(let text)? = error["code"] { text } else { nil }
            switch (message, code) {
            case (let message?, let code?): return "\(message)（\(code)）"
            case (let message?, nil): return message
            case (nil, let code?): return code
            case (nil, nil): break
            }
        }
        let text = String(decoding: data.prefix(300), as: UTF8.self)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? "（没有错误体）" : text
    }

    static func url(baseURL: URL, path: String) -> URL? {
        var base = baseURL.absoluteString
        while base.hasSuffix("/") { base.removeLast() }
        guard !path.isEmpty else { return URL(string: base) }
        return URL(string: base + path)
    }
}

// MARK: - 远程请求体（字段名 = agentd 的契约）

/// `/v1/core/defer-plan` 请求体。字段名与 `agentd` 的 `AgentCoreCodec.encodeDeferPlanRequest` 一致。
struct DeferPlanRequest: Encodable {
    var minutes: Int?
    var reason: String?
    var now: Int64

    private enum CodingKeys: String, CodingKey {
        case minutes
        case reason
        case now
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        // 可空字段显式写 null（与 Android 的报文一致）。
        try container.encode(minutes, forKey: .minutes)
        try container.encode(reason, forKey: .reason)
        try container.encode(now, forKey: .now)
    }
}

/// `/v1/core/proactive-cap` 请求体。
///
/// 只带 `level` + `moodIntensity`：`moodLabel` 不在 `AgentCoreClient` 协议里
/// （心情标签留在本机，由调用点叠 `MoodBehavior.effectiveDailyCap`），
/// 而 agentd 对「只有强度、没有标签」的处理就是按主动程度换算 —— 与 `proactiveCap` 的意思一致。
struct ProactiveCapRequest: Encodable {
    var level: Int
    var moodIntensity: Int?

    private enum CodingKeys: String, CodingKey {
        case level
        case moodIntensity
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(level, forKey: .level)
        try container.encode(moodIntensity, forKey: .moodIntensity)
    }
}

/// `/v1/core/tool-args` 请求体。
struct ToolArgsRequest: Encodable {
    var tool: String
    var args: [String: JSONValue]
}

// MARK: - 回退实现

/// 先试远程、失败回退本机，并记一条日志。
///
/// 这是「服务不可用时聊天不受影响」的那一层，也是**唯一**决定要不要回退的地方：
/// 远程客户端只负责把失败如实抛出来，不自己吞。
public struct FallbackAgentCoreClient: AgentCoreClient {

    public let mode: AgentCoreMode
    public var agentCoreMode: AgentCoreMode { mode }

    private let remote: any AgentCoreClient
    private let native: any AgentCoreClient
    private let log: @Sendable (String) -> Void
    private let breaker: AgentCoreCircuitBreaker?

    public init(
        mode: AgentCoreMode = .remote(preferredFallback: true),
        remote: any AgentCoreClient,
        native: any AgentCoreClient = NativeAgentCoreClient(),
        log: @escaping @Sendable (String) -> Void = { _ in },
        breaker: AgentCoreCircuitBreaker? = AgentCoreCircuitBreaker()
    ) {
        self.mode = mode
        self.remote = remote
        self.native = native
        self.log = log
        self.breaker = breaker
    }

    /// `.remote(preferredFallback: true)` 才回退；`.native` 本来就只走本机。
    private var fallsBack: Bool {
        switch mode {
        case .native: return true
        case .remote(let preferredFallback): return preferredFallback
        }
    }

    // MARK: 四个方法

    public func sleepDecision(_ input: SleepInput) async throws -> SleepDecision {
        try await withFallback("sleep-decision") {
            try await remote.sleepDecision(input)
        } recover: {
            try await native.sleepDecision(input)
        }
    }

    public func deferPlan(minutes: Int?, reason: String?, now: Int64) async throws -> DeferPlan {
        try await withFallback("defer-plan") {
            try await remote.deferPlan(minutes: minutes, reason: reason, now: now)
        } recover: {
            try await native.deferPlan(minutes: minutes, reason: reason, now: now)
        }
    }

    public func proactiveCap(level: Int, moodIntensity: Int?) async throws -> Int {
        try await withFallback("proactive-cap") {
            try await remote.proactiveCap(level: level, moodIntensity: moodIntensity)
        } recover: {
            try await native.proactiveCap(level: level, moodIntensity: moodIntensity)
        }
    }

    public func clampToolArgs(tool: String, args: [String: JSONValue]) async throws -> [String: JSONValue] {
        try await withFallback("tool-args") {
            try await remote.clampToolArgs(tool: tool, args: args)
        } recover: {
            try await native.clampToolArgs(tool: tool, args: args)
        }
    }

    // MARK: 内部

    private func withFallback<T>(
        _ endpoint: String,
        _ attempt: () async throws -> T,
        recover: () async throws -> T
    ) async throws -> T {
        guard mode.usesRemote else { return try await recover() }
        // 0.11.1 审计 P1-2：连续失败后先短路一段时间。没有它的话，agentd 不可达时
        // 一次回复里串行经过的几次决策会各等满一个超时（用户看到模型迟迟不开口）。
        if let breaker, breaker.isOpen {
            log("agentcore: \(endpoint) 熔断中（远程最近连续失败），直接走本机")
            return try await recover()
        }
        do {
            let value = try await attempt()
            breaker?.recordSuccess()
            return value
        } catch is CancellationError {
            // 用户点了停止：不算「服务挂了」，也不要回退（回退会让停止变得迟钝）。
            throw CancellationError()
        } catch {
            let opened = breaker?.recordFailure() ?? false
            guard fallsBack else { throw error }
            log("agentcore: \(endpoint) 远程失败（\(Self.describe(error))），回退本机")
            if opened {
                log("agentcore: 远程连续失败，接下来 \(Int(breaker?.cooldown ?? 0)) 秒直接走本机")
            }
            return try await recover()
        }
    }

    static func describe(_ error: Error) -> String {
        AgentCoreClientError.describe(error)
    }
}

// MARK: - 选择机制

/// 装配入口：按「环境变量 → Info.plist → 默认本机」的顺序决定走哪条路。
///
/// 为什么不做成用户可见的设置项：这是**联调/部署**开关，不是产品功能；
/// 用户界面里多一个「用远程内核」只会让人困惑。环境变量给 DEBUG/CI 用，
/// Info.plist 给打包时固定配置用，默认永远是本机、零网络。
public enum AgentCoreClientFactory {

    /// `MYFRIEND_AGENT_CORE`：`native` / `remote` / `remote-strict`。
    public static let modeEnvironmentKey = "MYFRIEND_AGENT_CORE"
    /// `MYFRIEND_AGENT_CORE_URL`：agentd 的 base URL（默认 `http://127.0.0.1:8788`）。
    public static let baseURLEnvironmentKey = "MYFRIEND_AGENT_CORE_URL"
    /// Info.plist 里的可选键（值同上；给「打包时固定」用）。
    public static let modeInfoPlistKey = "MFAgenCoreMode"
    /// Info.plist 里的可选 base URL 键。
    public static let baseURLInfoPlistKey = "MFAgenCoreBaseURL"

    public static let defaultBaseURL = URL(string: "http://127.0.0.1:8788")!

    /// 解析模式：环境变量优先于 Info.plist，解析不了就退回 `.native`。
    public static func resolveMode(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        info: [String: Any]? = Bundle.main.infoDictionary
    ) -> AgentCoreMode {
        if let raw = environment[modeEnvironmentKey], let mode = mode(from: raw) {
            return mode
        }
        if let raw = info?[modeInfoPlistKey] as? String, let mode = mode(from: raw) {
            return mode
        }
        return .native
    }

    /// 解析 base URL：环境变量 → Info.plist → `http://127.0.0.1:8788`。
    public static func resolveBaseURL(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        info: [String: Any]? = Bundle.main.infoDictionary
    ) -> URL {
        if let raw = environment[baseURLEnvironmentKey], let url = validURL(raw) {
            return url
        }
        if let raw = info?[baseURLInfoPlistKey] as? String, let url = validURL(raw) {
            return url
        }
        return defaultBaseURL
    }

    /// 字符串 → 模式。`remote` 带本机回退，`remote-strict` 不带（联调时用来确认真的走了远程）。
    public static func mode(from raw: String) -> AgentCoreMode? {
        switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "native", "local", "in-process", "inprocess":
            return .native
        case "remote":
            return .remote(preferredFallback: true)
        case "remote-strict", "remote-only", "strict-remote":
            return .remote(preferredFallback: false)
        default:
            return nil
        }
    }

    /// 按模式造客户端。
    ///
    /// - `.native` → `NativeAgentCoreClient`（零网络）；
    /// - `.remote(preferredFallback: true)` → `FallbackAgentCoreClient`（远程失败回退本机）；
    /// - `.remote(preferredFallback: false)` → `RemoteAgentCoreClient`（失败冒到调用点）。
    public static func make(
        mode: AgentCoreMode? = nil,
        environment: [String: String] = ProcessInfo.processInfo.environment,
        info: [String: Any]? = Bundle.main.infoDictionary,
        baseURL: URL? = nil,
        timeouts: AgentCoreTimeouts = .default,
        session: URLSession? = nil,
        log: @escaping @Sendable (String) -> Void = { _ in }
    ) -> any AgentCoreClient {
        let resolvedMode = mode ?? resolveMode(environment: environment, info: info)
        switch resolvedMode {
        case .native:
            return NativeAgentCoreClient()
        case .remote(let preferredFallback):
            let remote = RemoteAgentCoreClient(
                baseURL: baseURL ?? resolveBaseURL(environment: environment, info: info),
                timeouts: timeouts,
                session: session,
                log: log
            )
            guard preferredFallback else { return remote }
            return FallbackAgentCoreClient(mode: resolvedMode, remote: remote, log: log)
        }
    }

    private static func validURL(_ raw: String) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              url.host?.isEmpty == false else {
            return nil
        }
        return url
    }
}

// MARK: - JSON 小工具

/// `JSONValue` → JSON 的编码（请求体与测试都要用）。
///
/// `JSONValue` 原本只为备份解码而生（只有 `Decodable`）。这里补上 `Encodable`，
/// 让「工具参数原样往回发」不需要再写一份 `Any` 转换。
extension JSONValue: Encodable {
    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let flag): try container.encode(flag)
        case .int(let number): try container.encode(number)
        case .double(let number): try container.encode(number)
        case .string(let text): try container.encode(text)
        case .array(let items): try container.encode(items)
        case .object(let fields): try container.encode(fields)
        }
    }
}

/// 决策层用到的几个 JSON 取值 / 转换。
internal enum AgentCoreJSON {

    // MARK: 读数（区分「缺字段」与「类型不对」）

    static func requiredBool(_ json: [String: JSONValue], _ key: String) throws -> Bool {
        guard let value = json[key], !value.isNull else {
            throw AgentCoreClientError.missingField(key)
        }
        guard case .bool(let flag) = value else {
            throw AgentCoreClientError.badJSON("字段 \(key) 必须是 true/false")
        }
        return flag
    }

    static func bool(_ json: [String: JSONValue], _ key: String, fallback: Bool) throws -> Bool {
        guard let value = json[key], !value.isNull else { return fallback }
        guard case .bool(let flag) = value else {
            throw AgentCoreClientError.badJSON("字段 \(key) 必须是 true/false")
        }
        return flag
    }

    static func text(_ json: [String: JSONValue], _ key: String, fallback: String) throws -> String {
        guard let value = json[key], !value.isNull else { return fallback }
        guard case .string(let text) = value else {
            throw AgentCoreClientError.badJSON("字段 \(key) 必须是字符串")
        }
        return text
    }

    static func requiredInt(_ json: [String: JSONValue], _ key: String) throws -> Int {
        guard let raw = json[key], !raw.isNull else {
            throw AgentCoreClientError.missingField(key)
        }
        guard let number = int(raw) else {
            throw AgentCoreClientError.badJSON("字段 \(key) 必须是整数")
        }
        return number
    }

    static func requiredInt64(_ json: [String: JSONValue], _ key: String) throws -> Int64 {
        guard let raw = json[key], !raw.isNull else {
            throw AgentCoreClientError.missingField(key)
        }
        switch raw {
        case .int(let number): return number
        // 有的服务端会把 64 位整数写成数字字符串（agentd 的 `JsonValue.Num` 就是按文本存的），
        // 这里宽容一点：读得出来就算数，读不出来才是协议错。
        case .string(let text):
            if let number = Int64(text.trimmingCharacters(in: .whitespacesAndNewlines)) { return number }
        case .double(let number):
            if number.isFinite, number.rounded() == number, let value = Int64(exactly: number) { return value }
        default: break
        }
        throw AgentCoreClientError.badJSON("字段 \(key) 必须是整数")
    }

    // MARK: 参数表里的整数（与 Android `InProcessAgentCore.intArgument` 同语义）

    /// 只认 JSON 数值语义（整数 / 整数值的 Double / 数字字符串）。
    /// `null`、布尔、对象一律当「没给」，由调用方回落到缺省值。
    static func int(_ value: JSONValue?) -> Int? {
        switch value {
        case .int(let number):
            return Int(exactly: number)
        case .double(let number):
            guard number.isFinite, number.rounded() == number,
                  number >= Double(Int.min), number <= Double(Int.max) else { return nil }
            return Int(number)
        case .string(let text):
            return Int(text.trimmingCharacters(in: .whitespacesAndNewlines))
        default:
            return nil
        }
    }

    // MARK: 任意值 → JSONValue（工具参数从 ToolInvocation 搬过来）

    static func object(fromArgumentsJson raw: String) -> [String: JSONValue] {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let text = trimmed.isEmpty ? "{}" : trimmed
        guard let data = text.data(using: .utf8),
              let any = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return [:]
        }
        return any.mapValues(value(from:))
    }

    static func value(from raw: Any) -> JSONValue {
        switch raw {
        case is NSNull:
            return .null
        case let number as NSNumber:
            // JSON 的 true/false 在 Darwin 上也是 NSNumber（CFBoolean），先认出来。
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                return .bool(number.boolValue)
            }
            let double = number.doubleValue
            if double.isFinite, double.rounded() == double, let int = Int64(exactly: double) {
                return .int(int)
            }
            return .double(double)
        case let text as String:
            return .string(text)
        case let items as [Any]:
            return .array(items.map(value(from:)))
        case let fields as [String: Any]:
            return .object(fields.mapValues(value(from:)))
        default:
            return .null
        }
    }
}
