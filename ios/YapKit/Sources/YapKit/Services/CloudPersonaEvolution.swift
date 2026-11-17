import Foundation

// ---------------------------------------------------------------------------
// 云端模式下的人格进化（0.13：`docs/design/persona-evolution.md` §2 的云端接线）。
//
// 本地模式那条链路是「`ChatService` 落库 → `ChatBackgroundService` 触发 → 写本地待审 →
// 接受写本地人格」。云端模式（服务端权威）少了中间那一环：聊天在 `ServerChatGateway`，
// 人格的权威副本在服务端，所以本类型补两件事：
//
// 1. **触发与评估**：一轮服务端回复落库后按**同一套节流**（20 条用户消息 / 24 小时）
//    评估一次；输入是本地缓存的人格与最近对话，结果照旧写本地 `persona_pending_patches`
//    （提议不是事实，等服务端确认才生效）；
// 2. **接受/拒绝**：接受 = 四字段合并成一次 `POST /v1/personas`（整份人格推上去，
//    其它字段原样保留），服务端成功之后才写本地缓存并 `version+1`，然后删掉待审项；
//    失败**保留**待审项，把错误抛给界面。拒绝只删本地待审项。
//
// 为什么评估不放到服务端：评估要读本机离线的最近对话、要用用户自己的模型配置、
// 还要让人点头——这三件事都在客户端。服务端只接收「用户已经同意的那份人格」。
//
// 与本地模式**共用**的东西（所以模式切换不会重复评估、也不会各存一份节奏）：
// - `PersonaEvolutionService`：提示词、校验、游标（`PersonaEvolutionCursor`）、单飞闸；
// - `PersonaEvolution.approve/apply` 的字段白名单与 `version+1` 语义。
// ---------------------------------------------------------------------------

public final class CloudPersonaEvolution {

    /// 接受一条待确认变化之后的结果。
    public struct Approval: Equatable {
        /// 写回之后的人格（云端确认 + 本地缓存）。
        public var persona: PersonaEntity
        /// 实际生效的条数（0 = 这条已经不成立了，条目照样丢掉）。
        public var applied: Int

        public init(persona: PersonaEntity, applied: Int) {
            self.persona = persona
            self.applied = applied
        }
    }

    private let evolution: PersonaEvolutionService
    private let writes: ServerWriteService
    private let stores: DatabaseStores
    private let now: () -> Int64
    private let log: (String) -> Void

    public init(
        evolution: PersonaEvolutionService,
        writes: ServerWriteService,
        stores: DatabaseStores,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.evolution = evolution
        self.writes = writes
        self.stores = stores
        self.now = now
        self.log = log
    }

    // MARK: - 触发与评估（服务端回复落库之后）

    /// 到了节奏就评估一次；没到、或者同一个人格正在评估，就安静返回 nil。
    ///
    /// 不阻塞调用方：生产入口在 `AppEnvironment` 里起 `Task`，这里只保证
    /// 「该不该评估 / 有没有人在评估」这两问在**开 Task 之前**就有答案。
    @discardableResult
    public func evaluateIfNeeded(
        personaId: String,
        sessionId: String?,
        config: CompletionConfig,
        model: String
    ) async -> PersonaEvolutionService.Outcome? {
        guard evolution.shouldEvaluate(personaId: personaId, now: now()) else { return nil }
        // 单飞：与本地模式共用同一道闸（`PersonaEvolutionService` 里的 inFlight）。
        guard evolution.beginEvaluation(personaId: personaId) else {
            log("[evolution] 已经在评估了，这次不重复（人格 \(personaId)）")
            return nil
        }
        defer { evolution.endEvaluation(personaId: personaId) }

        let result = await evolution.evaluate(
            personaId: personaId,
            sessionId: sessionId,
            config: config,
            model: model
        )
        switch result {
        case .success(let outcome):
            log("[evolution] \(outcome.message)（云端模式，人格 \(personaId)）")
            return outcome
        case .failure(let error):
            log("[evolution] 评估失败：\(error.localizedDescription)")
            return nil
        }
    }

    // MARK: - 接受 / 拒绝

    /// 接受一条待确认变化：先推服务端，成功后再写本地缓存与 `version+1`。
    ///
    /// 顺序不能反：服务端是权威。先写本地再推，一旦请求失败，这台手机就会以为
    /// 改过了，而另一端与服务端都还不知道（下次同步会莫名其妙回滚）。
    ///
    /// - Throws: 服务端拒绝（`ServerAPIError`）或回执不完整（`ServerWriteError`）时
    ///   **待审项原样保留**，界面据此提示「这次没接受成」。
    @discardableResult
    public func approve(patchId: String) async throws -> Approval {
        let plan = try evolution.planApproval(patchId: patchId, at: now())
        guard plan.applied > 0 else {
            // 这条已经不成立了（人格里本来就是这样）：不算失败，条目照样丢掉。
            try evolution.commit(plan)
            return Approval(persona: plan.persona, applied: 0)
        }
        let saved = try await writes.savePersona(plan.updated, isNew: false)
        let merged = Self.promote(saved, with: plan.updated)
        do {
            try stores.personas.upsert(merged)
        } catch {
            // 服务端已经改了：如实告诉用户「本地没跟上」（下次同步会补回来），
            // 但待审项不删——用户还能再点一次（两边都已是他接受后的样子，重复点不会改坏）。
            throw ServerWriteError.cacheUpdateFailed(error.localizedDescription)
        }
        do {
            try stores.personaPendingPatches.remove(id: plan.patch.id)
        } catch {
            // 人格与服务端都已经生效，删待审项失败不该让整次接受报错（大不了再点一次，
            // 届时 `applied == 0` 会静默把条目删掉）。
            log("[evolution] 待审项没删掉：\(error.localizedDescription)（人格 \(plan.patch.personaId)）")
        }
        return Approval(persona: merged, applied: plan.applied)
    }

    /// 拒绝：只删本地待审项，人格与服务端一个字都不动。
    public func reject(patchId: String) throws {
        try evolution.reject(patchId: patchId)
    }

    // MARK: - 本地缓存合并

    /// 服务端回执 + 「刚被接受的那份人格」→ 要写进本地缓存的那一行。
    ///
    /// 两个刻意的覆盖：
    /// - **四个字段**以 `accepted` 为准：回执可能不含 extras（服务端把它们放在 extras 里，
    ///   不同实现回显不完全），我们不能因此把刚接受的变化从本地缓存里抹掉；
    /// - **version** 取较大值：本机「第几版」的语义是「用户接受过几次进化」，接受一次必须 +1，
    ///   不能因为服务端回执没带 version（或回显旧值）就退回去。
    ///
    /// 其它字段（名字、简介、手写提示词、世界设定、头像……）全部保留服务端回执与服务端 id。
    static func promote(_ saved: PersonaEntity, with accepted: PersonaEntity) -> PersonaEntity {
        var merged = saved
        merged.traits = accepted.traits
        merged.likes = accepted.likes
        merged.behaviors = accepted.behaviors
        merged.quirks = accepted.quirks
        merged.version = max(saved.version, accepted.version)
        merged.updatedAt = max(saved.updatedAt, accepted.updatedAt)
        return merged
    }
}
