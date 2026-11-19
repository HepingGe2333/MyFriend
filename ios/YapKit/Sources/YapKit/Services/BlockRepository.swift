import Foundation

// 来源：`spec/experience/README.md` §2「拉黑（AI 主动）」。
//
// 这个仓储只做三件事：
// 1. 读「此刻是不是在拉黑期」（到期即视为解除，顺手把两列清掉）；
// 2. 记住一条**未确认**的提议（10 分钟有效），并判定两轮确认；
// 3. 确认生效时把 `blockedUntil` / `blockReason` 写进人格。
//
// 提议存在 `personas.blockProposalJson`：Android 的 18.json 就是这么排的
//（两端 schema 逐字一致），而且「提议」天然属于某一条人格 ——
// 不另建表，也不放 UserDefaults（那样两端行为会不一样）。

public final class BlockRepository {

    private let stores: DatabaseStores
    private let now: () -> Int64
    private let lock = NSLock()

    public init(
        stores: DatabaseStores,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.stores = stores
        self.now = now
    }

    // MARK: - 读

    /// 生效中的拉黑状态；不在拉黑期返回 nil。
    ///
    /// **到期自动解除**（规范 §2）就是在这里发生的：`blockedUntil <= now` 时把两列清空，
    /// 于是下一次读、下一次提示词、下一次界面刷新看到的都是「没被拉黑」。
    /// 不通知、不补看 —— 解除这件事本身不需要他知道。
    public func state(personaId: String, now: Int64? = nil) -> BlockState? {
        let at = now ?? self.now()
        guard let persona = personaRow(personaId) else { return nil }
        guard let until = persona.blockedUntil else { return nil }
        if until <= at {
            clearExpiredBlock(persona: persona)
            return nil
        }
        return BlockPolicy.effective(until: until, reason: persona.blockReason, now: at)
    }

    public func isBlocked(personaId: String, now: Int64? = nil) -> Bool {
        state(personaId: personaId, now: now) != nil
    }

    /// 人格行上**原样存着**的提议（不判过期）：`confirm` 需要区分
    /// 「从没提议过」与「提议过期了」（两句话对模型的意义完全不同）。
    func storedProposal(personaId: String) -> BlockProposal? {
        guard let persona = personaRow(personaId),
              let raw = persona.blockProposalJson,
              let data = raw.data(using: .utf8),
              let proposal = try? JSONDecoder().decode(BlockProposal.self, from: data),
              proposal.personaId == personaId else {
            return nil
        }
        return proposal
    }

    /// 这条人格当前挂着的、还没过期的提议（没有就 nil）。
    public func pendingProposal(personaId: String, now: Int64? = nil) -> BlockProposal? {
        let at = now ?? self.now()
        guard let proposal = storedProposal(personaId: personaId) else { return nil }
        guard BlockPolicy.isProposalFresh(proposal, now: at) else {
            // 过期（或不属于这条人格）的提议顺手清掉：留着只会让下一轮误判。
            clearProposal(personaId: personaId, now: at)
            return nil
        }
        return proposal
    }

    // MARK: - 写

    /// 记下一条「想拉黑」的提议。同一条人格已有未过期提议时**沿用**它
    /// （模型同一轮里重复提议不该刷新 10 分钟窗口，否则它可以靠反复提议把确认无限推迟）。
    @discardableResult
    public func propose(
        personaId: String,
        hours: Int,
        reason: String?,
        userMessageId: String?,
        now: Int64? = nil
    ) -> BlockProposal {
        let at = now ?? self.now()
        if let existing = pendingProposal(personaId: personaId, now: at) {
            return existing
        }
        let proposal = BlockProposal(
            personaId: personaId,
            hours: hours,
            reason: reason ?? "",
            userMessageId: userMessageId ?? "",
            proposedAt: at
        )
        writeProposal(proposal, now: at)
        return proposal
    }

    /// 确认（可能是第二次调用，也可能是又一轮的第一次调用）。
    ///
    /// 返回 `.accepted` 时已经写库；其余两种由调用方决定怎么跟模型解释。
    /// - Parameter hours: 只有「这次确认时显式给了时长」才用它；否则沿用提议里的时长
    ///  （与 Android 的 `BlockPolicy.plan` 一致：确认不会把 10 分钟窗口或时长悄悄改掉）。
    /// - Parameter userMessageId: 这一轮那条用户消息的 id；与提议时的不同才算「之后的一轮」。
    public func confirm(
        personaId: String,
        hours: Int? = nil,
        reason: String? = nil,
        userMessageId: String?,
        now: Int64? = nil
    ) -> BlockPolicy.Confirmation {
        let at = now ?? self.now()
        let stored = storedProposal(personaId: personaId)
        // 过期与「从没提议过」要分开：过期的那条先清掉，并如实告诉模型「重新提议」。
        if let stored, !BlockPolicy.isProposalFresh(stored, now: at) {
            clearProposal(personaId: personaId, now: at)
            return .expiredProposal
        }
        let proposal = stored
        let verdict = BlockPolicy.confirmation(
            proposal: proposal, userMessageId: userMessageId, now: at
        )
        guard verdict == .accepted, let proposal else { return verdict }
        guard let persona = personaRow(personaId) else { return .needsProposal }
        let reasonText = (reason ?? proposal.reason).trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedHours = hours ?? proposal.hours
        let deadline = BlockPolicy.expiry(hours: resolvedHours, now: at)
        // 定向 UPDATE：只动拉黑三列，不把并发写过的其它列（心情、活动）回滚成旧快照。
        let changed = (try? stores.database.executeUpdate(
            "UPDATE personas SET blockedUntil = ?, blockReason = ?, blockProposalJson = NULL "
                + "WHERE id = ?",
            [.int(deadline),
             reasonText.isEmpty ? .null : .text(String(reasonText.prefix(200))),
             .text(personaId)]
        )) ?? 0
        _ = persona
        return changed > 0 ? .accepted : .needsProposal
    }

    /// 直接结束拉黑（到期 / 测试 / 调试用）。产品上没有「手动解除」入口（规范 §2）。
    public func clear(personaId: String, now: Int64? = nil) {
        _ = now
        _ = try? stores.database.executeUpdate(
            "UPDATE personas SET blockedUntil = NULL, blockReason = NULL WHERE id = ?",
            [.text(personaId)]
        )
    }

    public func clearProposal(personaId: String, now: Int64? = nil) {
        _ = now
        _ = try? stores.database.executeUpdate(
            "UPDATE personas SET blockProposalJson = NULL WHERE id = ?",
            [.text(personaId)]
        )
    }

    // MARK: - 内部

    private func personaRow(_ personaId: String) -> PersonaEntity? {
        lock.lock()
        defer { lock.unlock() }
        return (try? stores.personas.byId(personaId)) ?? nil
    }

    private func writeProposal(_ proposal: BlockProposal, now: Int64) {
        guard let data = try? JSONEncoder().encode(proposal),
              let text = String(data: data, encoding: .utf8) else { return }
        lock.lock()
        defer { lock.unlock() }
        _ = try? stores.database.executeUpdate(
            "UPDATE personas SET blockProposalJson = ? WHERE id = ?",
            [.text(text), .text(proposal.personaId)]
        )
        _ = now
    }

    private func clearExpiredBlock(persona: PersonaEntity) {
        _ = try? stores.database.executeUpdate(
            "UPDATE personas SET blockedUntil = NULL, blockReason = NULL WHERE id = ?",
            [.text(persona.id)]
        )
    }
}
