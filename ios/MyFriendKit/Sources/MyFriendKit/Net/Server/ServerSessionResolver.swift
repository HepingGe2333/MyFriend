import Foundation

// ---------------------------------------------------------------------------
// **服务端会话 id ⇄ 本地会话 id** 的换算（M3/M4 里最容易出错的一处）。
//
// 为什么会有两个 id：
// - 本地模式先建了会话（`AppEnvironment.ensureEveryPersonaHasSession`，id 是本机 UUID）；
// - 服务端模式下服务端也按「一人格一会话」建了它自己的会话（id 是服务端生成的）。
// 两边都是「一个人一段会话」，但 id 不同。而 `chat_sessions.personaId` 上有**唯一索引**，
// 所以绝不能把服务端那条当成「另一条会话」直接插进本地：`INSERT OR REPLACE` 会因为
// 唯一冲突把本机那条**删掉**（连同它下面消息的归属），界面上就是「会话突然空了」。
//
// 取舍：**本地 id 保持权威**，服务端 id 记在 `chat_sessions.serverSessionId`。
//   - 缓存写入一律用本地 id（消息、预览、未读都挂在用户看到的那个会话上）；
//   - 发往服务端的请求一律用 `serverSessionId`；
//   - 本地还没有这段会话时，直接用服务端 id 建一条（两边从此一致，不需要再换算）。
//
// 这个换算是纯数据操作，没有网络与时钟依赖（时间由参数传），所以可以单测。
// ---------------------------------------------------------------------------

public final class ServerSessionResolver {

    private let stores: DatabaseStores

    public init(stores: DatabaseStores) {
        self.stores = stores
    }

    /// 服务端会话 → 本地会话 id（必要时把本机那条同人格会话「认领」成这个服务端会话）。
    ///
    /// 三步，顺序有讲究：
    /// 1. 本地已经有同 id 的会话（之前同步过 / 本地就是服务端 id）→ 直接用；
    /// 2. 本地有 `serverSessionId` 匹配的会话（用户在本机建的那条已经被认领过）→ 用它；
    /// 3. 本地这个人格有一条会话（本地模式建的）→ 把服务端 id 记到它头上；
    /// 4. 都没有 → 用服务端 id 新建一条。
    @discardableResult
    public func localSessionId(forServer session: ServerSession, at timestamp: Int64) throws -> String {
        if let existing = (try? stores.sessions.byId(session.id)) ?? nil {
            return try mergeServerFields(session, into: existing, at: timestamp)
        }
        if let claimed = (try? stores.sessions.first(
            where: "serverSessionId = ?", [.text(session.id)]
        )) ?? nil {
            return try mergeServerFields(session, into: claimed, at: timestamp)
        }
        if let local = (try? stores.sessions.session(forPersona: session.personaId)) ?? nil,
           local.serverSessionId == nil || local.serverSessionId == session.id {
            return try mergeServerFields(session, into: local, at: timestamp)
        }
        // 本地连这一段会话都没有（新装、或者人格是服务端新建的）：用服务端 id 建一条。
        let record = ServerSyncMapping.session(from: session, existing: nil, now: timestamp)
        try stores.sessions.upsert(record)
        return record.id
    }

    /// 只做查找：这个服务端会话在本机对应哪一条（找不到返回 nil，**不建**）。
    ///
    /// 事件流用这个：一条消息的事件不该顺手在本地造出一个空会话（那会让首页多出一行
    /// 「没说过话的会话」）。找不到就跳过这条事件的消息缓存，等下一次 `/v1/state` 补齐。
    public func existingLocalSessionId(serverSessionId: String) -> String? {
        if ((try? stores.sessions.byId(serverSessionId)) ?? nil) != nil { return serverSessionId }
        let claimed = (try? stores.sessions.first(
            where: "serverSessionId = ?", [.text(serverSessionId)]
        )) ?? nil
        return claimed?.id
    }

    /// 本地会话 id → 发往服务端时该用的 id。
    public func remoteSessionId(forLocal localSessionId: String) -> String {
        let session = (try? stores.sessions.byId(localSessionId)) ?? nil
        guard let serverId = session?.serverSessionId, !serverId.isEmpty else { return localSessionId }
        return serverId
    }

    // MARK: 内部

    /// 把服务端那几列（标题/未读/最后时间/服务端 id）并进本地那一行，返回本地 id。
    ///
    /// 只动服务端**权威**的那几列：`apiConfigId` / 摘要进度 / `pinned` / 本地标题等
    /// 属于「这台设备怎么用它」，同步不该覆盖（与 `ServerSyncMapping.session` 同一口径）。
    private func mergeServerFields(
        _ session: ServerSession,
        into existing: ChatSessionEntity,
        at timestamp: Int64
    ) throws -> String {
        var record = existing
        record.serverSessionId = session.id
        if !session.title.isEmpty { record.title = session.title }
        record.unreadCount = max(session.unread, 0)
        if let lastMessageAt = session.lastMessageAt {
            record.lastMessageAt = max(record.lastMessageAt ?? 0, lastMessageAt)
        }
        record.updatedAt = max(record.updatedAt, session.updatedAt > 0 ? session.updatedAt : timestamp)
        if record.createdAt == 0 { record.createdAt = session.createdAt }
        try stores.sessions.upsert(record)
        return record.id
    }
}
