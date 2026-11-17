import Foundation

// ---------------------------------------------------------------------------
// 云端模式下的人格进化（0.13 第二阶段：**服务端**评估 + 待审 + 接受/拒绝）。
//
// 第一阶段这一层是「客户端自己算完、接受时整份人格推上去」。第二阶段把评估与合并
// 都搬到服务端（`EvolutionRoutes`）：人格的权威副本、模型调用、四字段合并与
// `version + 1` 全在服务端，客户端只做三件事：
//
// 1. **触发**：一轮服务端回复落库后按同一套节流（20 条用户消息 / 24 小时）问一次
//    `POST …/evolution/evaluate`；结果（提议）进本地 `persona_pending_patches` 缓存；
// 2. **同步待审**：`GET …/evolution/pending` 覆盖本地缓存 —— 换设备/重装以后
//    「他最近的变化」还在（服务端是权威，它也顺手清掉 7 天过期的）；
// 3. **人审**：接受 = `POST …/{patchId}/accept`，**服务端成功之后**才用回执写本地
//    四字段 + `version`，然后删本地待审项；拒绝 = `POST …/reject` + 删本地待审项。
//
// 失败语义（与第一阶段同一条规矩）：**待审项保留**，把中文原因抛给界面。
// 「补丁已过期/不存在」（404/410）例外：它已经没意义了，本地那条也删掉。
//
// 与本地模式**共用**的东西：游标（`PersonaEvolutionCursor`）、单飞闸、本地缓存表。
// 所以切模式不会重复评估，也不会各存一份节奏。
// ---------------------------------------------------------------------------

public final class CloudPersonaEvolution {

    /// 接受一条待确认变化之后的结果。
    public struct Approval: Equatable {
        /// 写回之后的人格（服务端回执 + 本地缓存）。
        public var persona: PersonaEntity
        /// 实际生效的条数（0 = 服务端去重后没有真正变化，版本没有升，条目照样丢掉）。
        public var applied: Int
        /// 服务端回执里的版本号（0 = 回执没带，用本地值）。
        public var serverVersion: Int

        public init(persona: PersonaEntity, applied: Int, serverVersion: Int = 0) {
            self.persona = persona
            self.applied = applied
            self.serverVersion = serverVersion
        }
    }

    private let client: ServerAPIClient
    private let evolution: PersonaEvolutionService
    private let stores: DatabaseStores
    private let serverPersonaId: (String) -> String
    private let now: () -> Int64
    private let log: (String) -> Void

    /// - Parameters:
    ///   - client: 当前连接（地址/令牌变了装配层会重建这个类型）。
    ///   - evolution: 本地进化服务：只借用它的**节流游标与单飞闸**（提示词与校验留在本地模式）。
    ///   - serverPersonaId: 本机人格 id → 服务端人格 id（首次同步时服务端另发过 id 的情况）。
    public init(
        client: ServerAPIClient,
        evolution: PersonaEvolutionService,
        stores: DatabaseStores,
        serverPersonaId: @escaping (String) -> String = { $0 },
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.client = client
        self.evolution = evolution
        self.stores = stores
        self.serverPersonaId = serverPersonaId
        self.now = now
        self.log = log
    }

    // MARK: - 触发与评估（服务端回复落库之后）

    /// 到了节奏就请服务端评估一次；没到、或者同一个人格正在评估，就安静返回 nil。
    ///
    /// 与本地模式同一套游标与单飞闸：两条触发路径（切模式前后的本地/云端）不会重复评估。
    @discardableResult
    public func evaluateIfNeeded(personaId: String) async -> ServerEvolutionEvaluation? {
        guard evolution.shouldEvaluate(personaId: personaId, now: now()) else { return nil }
        // 单飞：与本地模式共用同一道闸（`PersonaEvolutionService` 里的 inFlight）。
        guard evolution.beginEvaluation(personaId: personaId) else {
            log("[evolution] 已经在评估了，这次不重复（人格 \(personaId)）")
            return nil
        }
        defer { evolution.endEvaluation(personaId: personaId) }
        return await evaluate(personaId: personaId)
    }

    /// 不看节奏，请服务端评估一次（测试与「人格页手动刷新」用）。
    ///
    /// 失败只写日志并返回 nil：这是后台动作，不该打断聊天。**但要推进游标**
    /// （与本地模式一致）：服务端这次没配模型，不代表下一条用户消息要再白跑一次。
    @discardableResult
    public func evaluate(personaId: String) async -> ServerEvolutionEvaluation? {
        let remoteId = serverPersonaId(personaId)
        do {
            let evaluation = try await client.evaluateEvolution(personaId: remoteId)
            if let patch = evaluation.patch {
                try? cache(patch, personaId: personaId)
            }
            log("[evolution] 服务端评估：proposed=\(evaluation.proposed)（\(evaluation.summary)）")
            evolution.markEvaluated(personaId: personaId, at: now())
            return evaluation
        } catch {
            evolution.markEvaluated(personaId: personaId, at: now())
            log("[evolution] 服务端评估失败：\(ServerEvolutionFailure.classify(error).message)")
            return nil
        }
    }

    /// 拉一次服务端的待审列表，用它**覆盖**本地缓存。
    ///
    /// - Returns: 服务端此刻的待审条数（失败时返回 nil）。
    @discardableResult
    public func refreshPending(personaId: String) async -> Int? {
        let remoteId = serverPersonaId(personaId)
        do {
            let page = try await client.pendingEvolutions(personaId: remoteId)
            try reconcilePending(page.patches, personaId: personaId)
            return page.patches.count
        } catch {
            log("[evolution] 拉待审列表失败：\(ServerEvolutionFailure.classify(error).message)")
            return nil
        }
    }

    // MARK: - 接受 / 拒绝

    /// 接受：先请服务端合并，成功后再写本地缓存（顺序不能反，理由见文件头）。
    ///
    /// - Throws: 服务端拒绝（`ServerAPIError`）或回执不完整时**待审项原样保留**；
    ///   「补丁已过期/不存在」时待审项会被删掉，再把错误抛给界面。
    @discardableResult
    public func approve(patchId: String) async throws -> Approval {
        guard let patch = (try? stores.personaPendingPatches.byId(patchId)) ?? nil else {
            throw PersonaEvolutionError.patchNotFound
        }
        let personaId = patch.personaId
        let remoteId = serverPersonaId(personaId)
        do {
            // 待审 id 直接沿用：服务端发下来的补丁 id 我们原样存着。
            // （本地模式攒下的待审项服务端并不认识，发上去会 404 —— 上层据此把这条清掉。）
            let acceptance = try await client.acceptEvolution(
                personaId: remoteId, patchId: patch.id
            )
            return try commit(acceptance, patch: patch, personaId: personaId)
        } catch {
            let failure = ServerEvolutionFailure.classify(error)
            if failure.shouldDropPending {
                try? stores.personaPendingPatches.remove(id: patchId)
                log("[evolution] 这条待审已经过期/不在了，本地也清掉（\(failure.message)）")
            } else {
                log("[evolution] 接受失败，待审项保留：\(failure.message)")
            }
            throw error
        }
    }

    /// 拒绝：请服务端丢掉补丁，成功后删本地待审项。人格一个字都不动。
    public func reject(patchId: String) async throws {
        guard let patch = (try? stores.personaPendingPatches.byId(patchId)) ?? nil else { return }
        let remoteId = serverPersonaId(patch.personaId)
        do {
            _ = try await client.rejectEvolution(personaId: remoteId, patchId: patch.id)
        } catch {
            let failure = ServerEvolutionFailure.classify(error)
            // 补丁已经不在了：本地那条删掉即可（用户想要的结果已经成立）。
            guard failure.shouldDropPending else {
                log("[evolution] 拒绝失败，待审项保留：\(failure.message)")
                throw error
            }
            log("[evolution] 服务端说这条补丁不在了，本地照删（\(failure.message)）")
        }
        do {
            try stores.personaPendingPatches.remove(id: patchId)
        } catch {
            log("[evolution] 待审项没删掉：\(error.localizedDescription)")
        }
    }

    // MARK: - 本地缓存

    /// 把服务端的补丁写进本地待审表（同 id 覆盖，同 `patchJson` 去重）。
    func cache(_ patch: ServerEvolutionPatch, personaId: String) throws {
        let entity = PersonaPendingPatchEntity(
            id: patch.id,
            personaId: personaId,
            sessionId: patch.sessionId,
            patchJson: patch.resolvedPatchJson,
            summary: patch.summary,
            reason: patch.reason,
            createdAt: patch.createdAt > 0 ? patch.createdAt : now()
        )
        try stores.personaPendingPatches.insertIfNew(entity)
    }

    /// `/pending` 是权威：本地多出来的删掉，缺的补上，内容变了的覆盖。
    func reconcilePending(_ patches: [ServerEvolutionPatch], personaId: String) throws {
        let local = (try? stores.personaPendingPatches.byPersona(personaId)) ?? []
        let remoteIds = Set(patches.map(\.id))
        for stale in local where !remoteIds.contains(stale.id) {
            try? stores.personaPendingPatches.remove(id: stale.id)
        }
        for patch in patches {
            let entity = PersonaPendingPatchEntity(
                id: patch.id,
                personaId: personaId,
                sessionId: patch.sessionId,
                patchJson: patch.resolvedPatchJson,
                summary: patch.summary,
                reason: patch.reason,
                createdAt: patch.createdAt > 0 ? patch.createdAt : now()
            )
            try? stores.personaPendingPatches.upsert(entity)
        }
    }

    /// 服务端接受之后写本地：四字段 + version 以**回执**为准，其它字段保留本地值。
    private func commit(
        _ acceptance: ServerEvolutionAcceptance,
        patch: PersonaPendingPatchEntity,
        personaId: String
    ) throws -> Approval {
        let existing = ((try? stores.personas.byId(personaId)) ?? nil)
        guard var local = existing else {
            // 本机没有这个人格（比如它刚被删掉）：服务端已经改了，本地没什么可写的。
            try? stores.personaPendingPatches.remove(id: patch.id)
            throw PersonaEvolutionError.personaNotFound
        }
        if let object = acceptance.persona {
            // 服务端回执是权威：用它整份覆盖可写字段（`ServerWriteService` 的映射与写路径共用）。
            if let mapped = try? ServerWriteService.persona(from: object, existing: local) {
                local = mapped
            }
        }
        // 四字段与版本无论如何都按回执对齐：回执可能不含 extras（不同实现回显不完全），
        // 但「用户刚接受的变化」与「第几版」不能因为回显不全就丢掉。
        if let updated = (try? stores.personas.byId(personaId)) ?? nil {
            local = updated
        }
        let changes = PersonaEvolution.decodePatch(patch.patchJson)
        if acceptance.changed, !changes.isEmpty {
            var merged = local
            let (appliedPersona, _) = PersonaEvolution.apply(changes, to: local, now: now())
            merged.traits = appliedPersona.traits
            merged.likes = appliedPersona.likes
            merged.behaviors = appliedPersona.behaviors
            merged.quirks = appliedPersona.quirks
            local = merged
        }
        // 版本取较大值：本机「第几版」的语义是「用户接受过几次进化」，
        // 接受一次必须前进，不能因为回执没带 version（或回显旧值）就退回去。
        if acceptance.version > 0 {
            local.version = Swift.max(local.version, acceptance.version)
        }
        local.updatedAt = Swift.max(local.updatedAt, now())
        do {
            try stores.personas.upsert(local)
        } catch {
            throw ServerWriteError.cacheUpdateFailed(error.localizedDescription)
        }
        do {
            try stores.personaPendingPatches.remove(id: patch.id)
        } catch {
            // 人格与服务端都已经生效，删待审项失败不该让整次接受报错
            // （大不了再点一次，届时服务端会回 `changed=false` 并把补丁删掉）。
            log("[evolution] 待审项没删掉：\(error.localizedDescription)（人格 \(personaId)）")
        }
        return Approval(
            persona: local,
            applied: acceptance.changed ? acceptance.applied.count : 0,
            serverVersion: acceptance.version
        )
    }
}
