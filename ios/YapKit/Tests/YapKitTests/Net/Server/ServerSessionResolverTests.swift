import Foundation
import XCTest
@testable import YapKit

/// 本地会话 id 与 服务端会话 id 的换算（`ServerSessionResolver`）。
///
/// 这一层的存在理由只有一条：**两边都是「一人格一会话」，但 id 不同**，
/// 而 `chat_sessions.personaId` 上有唯一索引——直接插服务端那条会把本机那条（连同历史）冲掉。
final class ServerSessionResolverTests: XCTestCase {

    private var temp: TempDatabase!
    private var resolver: ServerSessionResolver!

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        resolver = ServerSessionResolver(stores: temp.stores)
        try temp.stores.personas.upsert(PersonaEntity(id: "p1", name: "林深"))
    }

    override func tearDownWithError() throws {
        temp?.cleanUp()
        temp = nil
        resolver = nil
        try super.tearDownWithError()
    }

    private func serverSession(
        id: String = "server-s1",
        personaId: String = "p1",
        title: String = "夜里散步",
        unread: Int = 2
    ) -> ServerSession {
        ServerSession(
            id: id,
            personaId: personaId,
            title: title,
            createdAt: 100,
            updatedAt: 200,
            lastMessageAt: 300,
            lastBlockIndex: 0,
            unread: unread,
            messageCount: 1
        )
    }

    // MARK: - 本地已有同人格会话

    /// 本地模式先建了会话（id 是 UUID）：换算后 **本地那条还在**，
    /// 只是多了一个 `serverSessionId`，而且不会多出第二行（唯一索引不冲突）。
    func testClaimsLocalSessionInsteadOfCreatingSecondRow() throws {
        var local = ChatSessionEntity(id: "local-1", personaId: "p1", title: "旧标题")
        local.pinned = true
        try temp.stores.sessions.upsert(local)

        let localId = try resolver.localSessionId(forServer: serverSession(), at: 500)

        XCTAssertEqual(localId, "local-1", "缓存仍按本地 id 挂")
        XCTAssertEqual(try temp.stores.sessions.count(), 1, "不能多出第二行（唯一索引）")
        let claimed = try XCTUnwrap(temp.stores.sessions.byId("local-1"))
        XCTAssertEqual(claimed.serverSessionId, "server-s1")
        XCTAssertEqual(claimed.title, "夜里散步")
        XCTAssertEqual(claimed.unreadCount, 2)
        XCTAssertTrue(claimed.pinned, "本机字段不能被同步冲掉")
        XCTAssertNil(try temp.stores.sessions.byId("server-s1"), "不能凭空插一条服务端 id 的会话")
    }

    /// 已经认领过（`serverSessionId` 已写好）：再来一次仍然幂等。
    func testClaimIsIdempotent() throws {
        try temp.stores.sessions.upsert(ChatSessionEntity(id: "local-1", personaId: "p1"))
        _ = try resolver.localSessionId(forServer: serverSession(), at: 500)
        _ = try resolver.localSessionId(forServer: serverSession(), at: 600)
        XCTAssertEqual(try temp.stores.sessions.count(), 1)
        XCTAssertEqual(try temp.stores.sessions.byId("local-1")?.serverSessionId, "server-s1")
    }

    // MARK: - 本地没有会话

    /// 本地连会话都没有（新装/人格在服务端新建）：直接用服务端 id 建一条，两边从此一致。
    func testCreatesSessionWithServerIdWhenNoLocalRow() throws {
        let localId = try resolver.localSessionId(forServer: serverSession(), at: 500)
        XCTAssertEqual(localId, "server-s1")
        let created = try XCTUnwrap(temp.stores.sessions.byId("server-s1"))
        XCTAssertEqual(created.personaId, "p1")
        XCTAssertEqual(created.serverSessionId, "server-s1")
        XCTAssertEqual(resolver.remoteSessionId(forLocal: "server-s1"), "server-s1")
    }

    /// 本地就是服务端 id（之前同步过）：直接命中，不再做任何修改。
    func testExistingSameIdSessionIsUsedAsIs() throws {
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: "server-s1", personaId: "p1", title: "本机标题", createdAt: 100
        ))
        XCTAssertEqual(try resolver.localSessionId(forServer: serverSession(), at: 500), "server-s1")
        XCTAssertEqual(try temp.stores.sessions.count(), 1)
    }

    // MARK: - 映射查询

    func testRemoteSessionIdFallsBackToLocalId() throws {
        try temp.stores.sessions.upsert(ChatSessionEntity(id: "local-1", personaId: "p1"))
        // 还没认领：请求服务端只能用本地 id（与本地模式行为一致，服务端会 404 → 走建会话流程）
        XCTAssertEqual(resolver.remoteSessionId(forLocal: "local-1"), "local-1")

        _ = try resolver.localSessionId(forServer: serverSession(), at: 500)
        XCTAssertEqual(resolver.remoteSessionId(forLocal: "local-1"), "server-s1")
    }

    func testExistingLocalSessionIdLooksUpBothDirections() throws {
        XCTAssertNil(resolver.existingLocalSessionId(serverSessionId: "server-s1"))

        try temp.stores.sessions.upsert(ChatSessionEntity(id: "local-1", personaId: "p1"))
        _ = try resolver.localSessionId(forServer: serverSession(), at: 500)

        XCTAssertEqual(resolver.existingLocalSessionId(serverSessionId: "server-s1"), "local-1")
        // 本地就是服务端 id 的情况也要认得
        XCTAssertEqual(resolver.existingLocalSessionId(serverSessionId: "local-1"), "local-1")
        XCTAssertNil(resolver.existingLocalSessionId(serverSessionId: "other"))
    }
}
