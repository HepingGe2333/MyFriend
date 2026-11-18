import Foundation

// ---------------------------------------------------------------------------
// 删掉一个人（连同他留下的一切）。见 0.10.2 安全审计 P1-6。
//
// 为什么要单独一个类型：这个动作在首页（长按删除）与人格列表（卡片删除）各有一个入口，
// 两处各写一份迟早少删一张表。0.10.1 就漏了三处：
// - `memory_echoes`（「只被提过一次」的回声里存着用户原话）；
// - `agent_ticks`（他的思考留痕，含 actionJson 与 messageId）；
// - `memory_versions`（记忆的历史版本里存着正文与 beforeContent —— 「忘掉就是真删」的承诺）；
// 0.13 起再加上 `diary_notes`（他写给自己的便条）与 `persona_pending_patches`
//（待确认的人格变化）：同样没有外键，同样必须显式删。
// 另外删掉默认人格之后，库里可能一个默认人格都没有（`defaultPersona()` 返回 nil），
// 「正在聊的人」还指着一个已经不存在的 id。
//
// Android 的对应实现见 `PersonaRepository.deletePersona`（顺序表在两边文档里同一份）。
// ---------------------------------------------------------------------------

/// 人格行没删掉（库里已经没有了，或写失败）。界面只需要一句「没能删除」。
public enum PersonaDeletionError: Error, LocalizedError {
    case failed

    public var errorDescription: String? { String(localized: "没能删掉这个人，请重试") }
}

/// 删人格的级联动作（顺序有讲究：先索引/子表，再父表）。
public enum PersonaDeletion {

    /// 删完之后要交给装配层的结果（设置项的修正不能在这里做：Kit 不碰 AppSettings）。
    public struct Outcome: Equatable, Sendable {
        /// 被删掉的那个人是不是「默认人格」。
        public var wasDefault: Bool
        /// 剩下的默认人格 id（删的若不是默认人格，就是原来那个；一个都不剩时为 nil）。
        public var nextDefaultPersonaId: String?
        /// `activePersonaId` 是否指向被删的人（调用方要把设置改成 nil 或新的默认）。
        public var activePersonaWasDeleted: Bool
        /// 人格行是不是真的删掉了（false = 库里本来就没有，或写失败）。
        public var personaDeleted: Bool = false
        /// 删掉了几条回声 / 几条思考留痕 / 几条记忆版本（诊断与测试用）。
        public var echoes: Int
        public var ticks: Int
        public var memoryVersions: Int
    }

    /// 级联删除。**不抛**（与 `MemoryRepository.delete` 一致：单张表失败不该把半个删除留在库里）；
    /// 但会把每一处的失败吞进 `warnings`，调用方可以记日志。
    ///
    /// - Parameters:
    ///   - personaId: 要删的人格 id。
    ///   - stores: 数据库仓储。
    ///   - memoryRepository: 记忆仓储（删记忆要连带倒排索引与向量索引）。
    ///   - activePersonaId: 当前设置里「正在聊的人」（用来判断要不要重置）。
    @discardableResult
    public static func delete(
        personaId: String,
        stores: DatabaseStores,
        memoryRepository: MemoryRepository,
        activePersonaId: String?
    ) -> Outcome {
        var outcome = Outcome(
            wasDefault: false, nextDefaultPersonaId: nil, activePersonaWasDeleted: false,
            personaDeleted: false, echoes: 0, ticks: 0, memoryVersions: 0
        )
        let persona = (try? stores.personas.byId(personaId)) ?? nil
        outcome.wasDefault = persona?.isDefault ?? false
        outcome.activePersonaWasDeleted = activePersonaId == personaId

        // 1) 记忆：先删每条记忆的版本与索引，再删记忆本身（顺序与 `MemoryRepository.delete` 一致）。
        let memories = (try? stores.memories.byPersona(personaId)) ?? []
        for memory in memories {
            let versions = (try? stores.memoryVersions.count(where: "memoryId = ?", [.text(memory.id)])) ?? 0
            outcome.memoryVersions += versions
            _ = try? stores.memoryVersions.delete(where: "memoryId = ?", [.text(memory.id)])
            memoryRepository.delete(memory)
        }

        // 1.5) 0.13 的两张新表：日记板与待确认的人格变化。
        // 它们没有 persona 外键，留着就会变成「看不见但一直占着」的孤儿行
        //（日记内容还是他写给自己的话，删人之后不应该留在库里）。
        _ = try? stores.diaryNotes.delete(where: "personaId = ?", [.text(personaId)])
        _ = try? stores.personaPendingPatches.delete(where: "personaId = ?", [.text(personaId)])

        // 2) 回声与思考留痕（这两张表没有 persona 外键，必须显式删）。
        let echoStore = MemoryEchoStore(database: stores.database)
        outcome.echoes = echoStore.byPersona(personaId).count
        echoStore.deleteAll(personaId: personaId)
        let ticks = AgentTickStore(database: stores.database)
        outcome.ticks = ticks.recent(personaId: personaId, limit: 10_000).count
        ticks.deleteAll(personaId: personaId)

        // 3) 一个事务把剩下的表清干净（消息 → 会话 → 整理记录 → 规则 → 念头 → 人格）。
        var deletedInTransaction = false
        try? stores.database.inTransaction {
            try? stores.messages.delete(where: "personaId = ?", [.text(personaId)])
            try? stores.sessions.delete(where: "personaId = ?", [.text(personaId)])
            try? stores.memoryConsolidations.delete(where: "personaId = ?", [.text(personaId)])
            try? stores.proactiveRules.delete(where: "personaId = ?", [.text(personaId)])
            try? stores.agentIntents.delete(where: "personaId = ?", [.text(personaId)])
            deletedInTransaction = ((try? stores.personas.delete(id: personaId)) != nil)
        }
        outcome.personaDeleted = deletedInTransaction && ((try? stores.personas.byId(personaId)) ?? nil) == nil

        // 4) 默认人格的转移：删的是默认人格时，把 `isDefault` 交给「最近更新过的另一个人」。
        if outcome.wasDefault {
            let remaining = (try? stores.personas.all(orderBy: "updatedAt DESC")) ?? []
            if var successor = remaining.first {
                successor.isDefault = true
                _ = try? stores.personas.upsert(successor)
                outcome.nextDefaultPersonaId = successor.id
            }
        } else {
            outcome.nextDefaultPersonaId = ((try? stores.personas.defaultPersona()) ?? nil)?.id
        }
        return outcome
    }
}
