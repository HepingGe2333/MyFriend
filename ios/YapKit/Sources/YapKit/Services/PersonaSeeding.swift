import Foundation

// ---------------------------------------------------------------------------
// 播种（0.11.0 审计 P2-13）。
//
// 「删除全部本地数据」之后原来的行为是：库里一个人格都没有 → 首页空着，让用户点「+」
// 自己建；重启 App 之后三个种子人格又出现。同一台设备上出现了两种「出厂状态」。
//
// 现在清空动作自己重新播种（`DataReset.clearAll` 的 `reseed`），时机与内容都跟
// 首次启动完全一致：同一套人格、同一套固定的 id、每人一段会话。
//
// 为什么把播种从 App 层搬进 Kit：`swift test` 只跑 Kit（不在模拟器里），
// 「清空后 personas 数量 = 3，且是当前语言的种子」这件事必须能直接断言。
// ---------------------------------------------------------------------------

public enum PersonaSeeding {

    /// 播种结果。
    public struct Outcome: Equatable, Sendable {
        /// 落了哪几个人格（按种子顺序）。
        public var personaIds: [String]
        /// 新建了几段会话（已有会话的人格不会重复建）。
        public var sessions: Int
        /// 库里本来已经有人：什么都没做。
        public var skipped: Bool

        public init(personaIds: [String] = [], sessions: Int = 0, skipped: Bool = false) {
            self.personaIds = personaIds
            self.sessions = sessions
            self.skipped = skipped
        }
    }

    /// 只在「库里一个人格都没有」时播种（首次启动的入口）。
    @discardableResult
    public static func seedIfNeeded(
        stores: DatabaseStores,
        language: SeedLanguage,
        sessionTitle: String,
        apiConfigId: String? = nil,
        now: Int64
    ) throws -> Outcome {
        let existing = (try? stores.personas.count()) ?? 0
        guard existing == 0 else { return Outcome(skipped: true) }
        return try seed(
            stores: stores,
            language: language,
            sessionTitle: sessionTitle,
            apiConfigId: apiConfigId,
            now: now
        )
    }

    /// 无条件播种（「删除全部本地数据」之后调用：那时库里确实一个人都不剩）。
    ///
    /// 「一人一段」：`chat_sessions.personaId` 上有唯一索引，所以只给**还没有会话**的
    /// 人格建新会话；同一个人格已有会话时原样留着（清空之后不会有，但备份导入的
    /// 中间状态里可能有）。
    @discardableResult
    public static func seed(
        stores: DatabaseStores,
        language: SeedLanguage,
        sessionTitle: String,
        apiConfigId: String? = nil,
        now: Int64
    ) throws -> Outcome {
        let personas = SeedPersonas.build(now: now, language: language)
        var sessions = 0
        for persona in personas {
            try stores.personas.upsert(persona)
        }
        for persona in personas {
            if ((try? stores.sessions.session(forPersona: persona.id)) ?? nil) != nil { continue }
            let session = ChatSessionEntity(
                id: UUID().uuidString,
                personaId: persona.id,
                apiConfigId: apiConfigId,
                title: sessionTitle,
                createdAt: now,
                updatedAt: now
            )
            if (try? stores.sessions.upsert(session)) != nil {
                sessions += 1
            }
        }
        return Outcome(personaIds: personas.map(\.id), sessions: sessions)
    }
}
