import Foundation

// 来源：`spec/experience/README.md` §2「拉黑（AI 主动）」。
//
// 拉黑是**他自己做的决定**（模型调 `block_user`），但生效必须两轮确认：
// 第一次调用只是「提议」，要等**之后的一轮**（中间至少有一条用户消息）再确认一次才生效。
// 这条规则的全部判定都在这里 —— 纯函数、可注入时钟，因此两轮确认 / 过期 / 到期解除
// 都能被单测逐条钉住，而不是散落在工具执行器与聊天链路里各写一遍。
//
// 为什么「两轮」不是「同一轮调两次」：同一轮里模型可以连调两次工具，如果那样也算数，
// 「两轮确认」就退化成一次调用。要求「中间至少有一条用户消息」才真正保证
// 他想清楚了、而且对方确实又说了一句（而不是他对着空气生闷气）。

/// 一次「想拉黑」的提议（还没生效）。
///
/// 0.14.1（`spec/experience/README.md` §9）：字段与**服务端 DTO 逐字一致**。
/// 提议落库的 JSON 形状就是这一组键（没有 `sessionId` / `createdAt` 这类本地别名）：
///
/// ```json
/// {"proposalId":"…","personaId":"…","hours":6,"reason":"…","userMessageId":"…",
///  "proposedAt":1700000000000,"expiresAt":1700000600000}
/// ```
///
/// 「确认必须来自之后的一轮」不再靠会话里数消息，而是靠 `userMessageId`：
/// 提议时的那条用户消息 id 与确认时的不同，才算下一轮（同一轮连调两次 id 相同）。
public struct BlockProposal: Equatable, Sendable, Codable {
    /// 这次提议的 id（服务端/客户端都按它去重与排查）。
    public var proposalId: String
    public var personaId: String
    /// 到期小时后写进 `personas.blockedUntil`。
    public var hours: Int
    public var reason: String
    /// 提议时那条用户消息的 id；没有（例如主动轮）时为空串。
    public var userMessageId: String
    /// 提议的时刻（毫秒时间戳）。
    public var proposedAt: Int64
    /// 提议过期时刻（毫秒时间戳）：`proposedAt + 10 分钟`。
    public var expiresAt: Int64

    public init(
        proposalId: String = UUID().uuidString,
        personaId: String,
        hours: Int,
        reason: String,
        userMessageId: String = "",
        proposedAt: Int64,
        expiresAt: Int64? = nil
    ) {
        self.proposalId = proposalId
        self.personaId = personaId
        self.hours = BlockPolicy.clampHours(hours)
        self.reason = String(reason.trimmingCharacters(in: .whitespacesAndNewlines).prefix(120))
        self.userMessageId = userMessageId.trimmingCharacters(in: .whitespacesAndNewlines)
        self.proposedAt = proposedAt
        self.expiresAt = expiresAt ?? (proposedAt + BlockPolicy.proposalTTLMillis)
    }
}

/// 拉黑的生效状态（从 `personas.blockedUntil` 读出来）。
public struct BlockState: Equatable, Sendable {
    /// 到期时间（毫秒时间戳）。
    public var until: Int64
    public var reason: String?

    public init(until: Int64, reason: String? = nil) {
        self.until = until
        self.reason = reason
    }
}

public enum BlockPolicy {

    /// 拉黑时长：1–24 小时（规范 §2）。
    public static let minHours = 1
    public static let maxHours = 24
    /// 模型没写 `hours` 时的缺省值：半天。落在合法区间里，且不像 1 小时那样「赌气」。
    public static let defaultHours = 12

    /// 未确认的提议 10 分钟后过期（规范 §2）。
    public static let proposalTTLMillis: Int64 = 10 * 60_000

    public static func clampHours(_ value: Int) -> Int {
        min(max(value, minHours), maxHours)
    }

    /// 从任意「模型给的」小时数夹取：认数字、数字字符串，认不出走缺省。
    public static func hours(from raw: Any?) -> Int {
        if let number = raw as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return defaultHours }
            return clampHours(number.intValue)
        }
        if let text = raw as? String, let value = Int(text.trimmingCharacters(in: .whitespaces)) {
            return clampHours(value)
        }
        return defaultHours
    }

    /// 到期时刻：`now + hours × 1 小时`。
    public static func expiry(hours: Int, now: Int64) -> Int64 {
        now + Int64(clampHours(hours)) * 3_600_000
    }

    /// 此刻是不是在拉黑期。`until` 为空 / 已经过去都算「没被拉黑」。
    public static func isBlocked(until: Int64?, now: Int64) -> Bool {
        guard let until, until > 0 else { return false }
        return until > now
    }

    /// 生效状态；不在拉黑期返回 nil。
    public static func effective(until: Int64?, reason: String?, now: Int64) -> BlockState? {
        guard isBlocked(until: until, now: now), let until else { return nil }
        let trimmed = reason?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return BlockState(until: until, reason: trimmed.isEmpty ? nil : trimmed)
    }

    /// 提议是否还能被确认（`expiresAt` 之前有效；服务端下发的这个字段就是权威窗口）。
    public static func isProposalFresh(_ proposal: BlockProposal, now: Int64) -> Bool {
        now >= proposal.proposedAt && now < proposal.expiresAt
    }

    /// 确认的结论：能不能生效、不能生效时是哪一种原因。
    public enum Confirmation: Equatable, Sendable {
        /// 生效，把 `until` 写进人格。
        case accepted
        /// 同一轮里急着确认：还差一条用户消息（提议继续有效，等下一轮）。
        case waitingForNextRound
        /// 没有提议：要先不带 confirm 提议一次。
        case needsProposal
        /// 提议已经过期（10 分钟）：要重新提议一次。
        case expiredProposal
    }

    /// 两轮确认的判定。
    ///
    /// - Parameters:
    ///   - proposal: 这条人格当前挂着的提议（没有就是 nil）。
    ///   - userMessageId: 这次确认所在的那一轮的用户消息 id（没有就 nil）。
    ///   - now: 现在。
    ///
    /// 「之后的一轮」= `userMessageId` 与提议时那条不同。同一轮里连调两次工具，
    /// 当前用户消息 id 没变，因此照样只是「还差一轮」——这正是规范 §9 的判定。
    public static func confirmation(
        proposal: BlockProposal?,
        userMessageId: String?,
        now: Int64
    ) -> Confirmation {
        guard let proposal else { return .needsProposal }
        guard isProposalFresh(proposal, now: now) else { return .expiredProposal }
        let current = userMessageId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !current.isEmpty, current != proposal.userMessageId else {
            return .waitingForNextRound
        }
        return .accepted
    }

    // MARK: - 提示词与界面

    /// 给模型看的一句「现在还在拉黑期」。
    ///
    /// 为什么模型也需要知道：他可能会想主动说话、想在心理活动里纠结；
    /// 不告诉他，他会以为对方「一直没理我」，然后在下一轮里无缘无故地委屈。
    public static func promptNote(_ state: BlockState, now: Int64) -> String {
        let minutesLeft = max(0, (state.until - now) / 60_000)
        let hours = max(1, Int((Double(minutesLeft) / 60.0).rounded(.up)))
        let reason = state.reason.map { "（你当时想的是：\($0)）" } ?? ""
        return "你现在不想理对方\(reason)，大约还有 \(hours) 小时。"
            + "他发来的消息你都收得到，但这段时间不回也没关系；想提前缓过来也可以照常回。"
    }

    /// 界面上那条用户消息要不要显示「被拉黑」的标记（红色感叹号 + 提示）。
    ///
    /// 只认消息自己的 `blocked` 列：解除拉黑之后，那几条消息**仍然**带着这个标记
    ///（那是「当时他把我拉黑了」这件事的痕迹，模型永远看不到它们，用户看得见）。
    public static func showsBlockedIndicator(role: MessageRole, blocked: Bool) -> Bool {
        role == .user && blocked
    }
}
