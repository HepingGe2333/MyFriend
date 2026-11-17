import Foundation

// ---------------------------------------------------------------------------
// 云端 → 本地：**全量下载 + 条数校验**（0.13「切回本地」的前置条件）。
//
// 规则（规范 `spec/sync-consent/README.md` §4）：
// 从云端切回本地时，必须先做一次全量下载（人格 / 会话 / 消息 / 媒体引用 / 设置 /
// 记忆 / 预设），写进本地库并**校验条数**，成功才关闭服务端模式；失败保持云端模式并提示。
//
// 为什么不能直接关掉开关：
// - 服务端模式下本机只是缓存（`/v1/state` 只给最新块），上次同步之后那边可能又聊了很多；
// - 关掉之后不会再拉，缺的那段就永久缺了 —— 用户看到的是「聊天记录少了」。
//
// 实现落在 `ServerBootstrapService.downloadAll()`（见那个文件）：
// 它复用 bootstrap 的 PULL 半边（拉人格/会话/每会话全部块/预设/记忆/设置），
// 但**不推任何东西**（切回本地时服务端是权威，不该用本机缓存覆盖它）。
// 这里只放结果类型与校验口径，便于单测与报告。
// ---------------------------------------------------------------------------

/// 一次全量下载的条数校验结果。
///
/// 口径（规范：「校验本地消息条数 == 服务端条数（允许本地独有合并）」）：
/// - 服务端给的 `messageCount` 必须 <= 本地这个会话的条数；
/// - 服务端**真的发下来**的每条消息 id 都必须在本地库里（防止「块翻了一半」）；
/// - 服务端消息里的媒体引用必须在本地消息的 `attachmentsJson` 里是同一个 id；
/// - 本地独有的消息**不算失败**（合并语义：本机以前聊的、服务端还没有的那些）。
public struct ServerRestoreVerification: Equatable, Sendable {
    /// 校验过的会话数。
    public var sessionsChecked: Int
    /// 服务端声称的条数之和。
    public var messagesOnServer: Int
    /// 本地实际条数之和。
    public var messagesOnLocal: Int
    /// 服务端有、本地没有的条数（必须为 0）。
    public var missingMessages: Int
    /// 本地独有（合并保留）的条数。
    public var extraLocalMessages: Int
    /// 服务端消息里的媒体引用总数。
    public var mediaRefs: Int
    /// 本地缺掉的媒体引用数（必须为 0）。
    public var missingMediaRefs: Int
    /// 人话失败原因（为空 = 通过）。
    public var failures: [String]

    public init(
        sessionsChecked: Int = 0,
        messagesOnServer: Int = 0,
        messagesOnLocal: Int = 0,
        missingMessages: Int = 0,
        extraLocalMessages: Int = 0,
        mediaRefs: Int = 0,
        missingMediaRefs: Int = 0,
        failures: [String] = []
    ) {
        self.sessionsChecked = sessionsChecked
        self.messagesOnServer = messagesOnServer
        self.messagesOnLocal = messagesOnLocal
        self.missingMessages = missingMessages
        self.extraLocalMessages = extraLocalMessages
        self.mediaRefs = mediaRefs
        self.missingMediaRefs = missingMediaRefs
        self.failures = failures
    }

    /// 通过 = 没有缺消息、没有缺媒体引用、没有结构性失败。
    public var passed: Bool {
        failures.isEmpty && missingMessages == 0 && missingMediaRefs == 0
    }

    /// 给用户/日志的一句话（中文；设置页直接用）。
    public var describe: String {
        guard passed else {
            let reason = failures.isEmpty
                ? "少 \(missingMessages) 条消息、\(missingMediaRefs) 处媒体引用"
                : failures.prefix(3).joined(separator: "；")
            return "全量下载没通过校验：\(reason)"
        }
        var text = "取回 \(sessionsChecked) 段会话、\(messagesOnLocal) 条消息"
        if mediaRefs > 0 { text += "、\(mediaRefs) 处媒体引用" }
        if extraLocalMessages > 0 { text += "（本机独有的 \(extraLocalMessages) 条已保留）" }
        return text
    }
}

/// 一次全量下载的结果。
public struct ServerRestoreReport: Equatable, Sendable {
    /// 拉回来的人格 / 会话 / 消息 / 预设 / 记忆条数（对账用）。
    public var personas: Int
    public var sessions: Int
    public var messages: Int
    public var presets: Int
    public var memories: Int
    /// 设置有没有拉回来。
    public var settingsPulled: Bool
    public var verification: ServerRestoreVerification
    public var at: Int64

    public init(
        personas: Int = 0,
        sessions: Int = 0,
        messages: Int = 0,
        presets: Int = 0,
        memories: Int = 0,
        settingsPulled: Bool = false,
        verification: ServerRestoreVerification = ServerRestoreVerification(),
        at: Int64 = 0
    ) {
        self.personas = personas
        self.sessions = sessions
        self.messages = messages
        self.presets = presets
        self.memories = memories
        self.settingsPulled = settingsPulled
        self.verification = verification
        self.at = at
    }

    public var describe: String {
        "人格 \(personas)、会话 \(sessions)、消息 \(messages)、预设 \(presets)"
            + "、记忆 \(memories)\(settingsPulled ? "、设置" : "")；"
            + verification.describe
    }
}

public enum ServerRestoreOutcome: Equatable, Sendable {
    /// 服务端模式没开 / 正在跑别的同步。
    case skipped(reason: String)
    case completed(ServerRestoreReport)
    /// 下载失败或校验没通过：**不要关闭服务端模式**。
    case failed(message: String, verification: ServerRestoreVerification?)
    /// 用户取消：**不要关闭服务端模式**。
    case cancelled
}
