import Foundation
import XCTest
@testable import YapKit

/// bootstrap 记下的「本机 id → 服务端 id」映射，在**日常写路径**里也要生效。
///
/// 场景：本机用了很久（人格/预设/记忆都是本机 UUID 或老 id）→ 第一次连上一台服务端，
/// bootstrap 把数据推上去时服务端另发了 id（`POST /v1/personas` 带未知 id 会 404，
/// 客户端回落成「不带 id 新建」）。之后用户改人设/改记忆/换主题，如果还按本机 id 发请求，
/// 服务端会 404 / 400 —— 表现就是「同步过一次以后，什么都存不上了」。
final class ServerWriteIdMappingTests: XCTestCase {

    private var temp: TempDatabase!
    private var settings: SettingsStore!
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerURLProtocolStub.reset()
        temp = try TempDatabase()
        suiteName = "mf-idmap-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        settings = SettingsStore(defaults: defaults)
    }

    override func tearDownWithError() throws {
        ServerURLProtocolStub.reset()
        if !suiteName.isEmpty { defaults?.removePersistentDomain(forName: suiteName) }
        temp?.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func service() -> ServerWriteService {
        ServerWriteService(
            client: ServerAPIClient(
                config: ServerConfig(baseURL: ServerTestSupport.defaultBaseURL, token: "secret-token"),
                session: ServerURLProtocolStub.makeSession()
            ),
            stores: temp.stores,
            memoryRepository: MemoryRepository(stores: temp.stores),
            now: { 1_800_000_000_000 }
        )
    }

    private func bodyJSON() throws -> [String: Any] {
        let data = try XCTUnwrap(ServerURLProtocolStub.recordedBodies.last)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    /// 假装 bootstrap 已经跑过：本机 id → 服务端 id 的映射落在 `server_sync_state` 里。
    private func seedMapping(local: String, server: String, key: (String) -> String) throws {
        let state = ServerSyncStateStore(database: temp.stores.database)
        try state.set(server, for: key(local), at: 1)
    }

    // MARK: - 人格

    func testUpdatePersonaUsesServerIdFromMappingAndKeepsLocalId() async throws {
        try temp.stores.personas.upsert(PersonaEntity(id: "local-persona", name: "林深"))
        try seedMapping(
            local: "local-persona", server: "p-server",
            key: ServerSyncStateStore.Key.personaIdMap
        )
        ServerURLProtocolStub.enqueue(.json(#"""
        {"persona":{"id":"p-server","name":"林深（改）","description":"慢热","isDefault":false,
          "createdAt":1,"updatedAt":2,"prompt":"","category":"","activityJson":null,
          "worldJson":null,"traits":[],"likes":[],"behaviors":[]}}
        """#))

        var persona = try XCTUnwrap((try? temp.stores.personas.byId("local-persona")) ?? nil)
        persona.name = "林深（改）"
        let saved = try await service().savePersona(persona, isNew: false)

        XCTAssertEqual(try bodyJSON()["id"] as? String, "p-server", "更新要用服务端认得的 id")
        XCTAssertEqual(saved.id, "local-persona", "本机 id 保持权威（否则本地多出一行人格）")
        XCTAssertEqual(saved.name, "林深（改）")
        XCTAssertNotNil((try? temp.stores.personas.byId("local-persona")) ?? nil)
        XCTAssertTrue(((try? temp.stores.personas.byId("p-server")) ?? nil) == nil)
    }

    func testUpdatePersonaWithoutMappingStillUsesLocalId() async throws {
        try temp.stores.personas.upsert(PersonaEntity(id: "p1", name: "林深"))
        ServerURLProtocolStub.enqueue(.json(#"""
        {"persona":{"id":"p1","name":"林深（改）","description":"","isDefault":false,
          "createdAt":1,"updatedAt":2,"prompt":"","category":"","activityJson":null,
          "worldJson":null,"traits":[],"likes":[],"behaviors":[]}}
        """#))
        var persona = try XCTUnwrap((try? temp.stores.personas.byId("p1")) ?? nil)
        persona.name = "林深（改）"
        _ = try await service().savePersona(persona, isNew: false)
        XCTAssertEqual(try bodyJSON()["id"] as? String, "p1")
    }

    // MARK: - 记忆

    func testSaveMemoryUsesMappedPersonaPathAndServerMemoryId() async throws {
        try temp.stores.memories.upsert(MemoryEntity(
            id: "local-memory", personaId: "local-persona", content: "旧内容"
        ))
        try seedMapping(
            local: "local-persona", server: "p-server",
            key: ServerSyncStateStore.Key.personaIdMap
        )
        try seedMapping(
            local: "local-memory", server: "mem-server",
            key: ServerSyncStateStore.Key.memoryIdMap
        )
        ServerURLProtocolStub.enqueue(.json(#"""
        {"memory":{"id":"mem-server","personaId":"p-server","content":"新内容",
          "importance":80,"tags":[],"createdAt":1,"updatedAt":2}}
        """#))

        var memory = try XCTUnwrap((try? temp.stores.memories.byId("local-memory")) ?? nil)
        memory.content = "新内容"
        let saved = try await service().saveMemory(memory, isNew: false)

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(request.url?.path, "/v1/personas/p-server/memories")
        XCTAssertEqual(try bodyJSON()["id"] as? String, "mem-server")
        XCTAssertEqual(saved.id, "local-memory")
        XCTAssertEqual(saved.personaId, "local-persona")
        XCTAssertEqual(
            (try? temp.stores.memories.byId("local-memory"))??.content, "新内容",
            "本地缓存按本机 id 更新"
        )
    }

    // MARK: - 设置

    func testSettingsPushMapsActivePersonaIdToServerId() async throws {
        try temp.stores.personas.upsert(PersonaEntity(id: "local-persona", name: "林深"))
        try seedMapping(
            local: "local-persona", server: "p-server",
            key: ServerSyncStateStore.Key.personaIdMap
        )
        ServerURLProtocolStub.enqueue(.json(#"{"settings":{"activePersonaId":"p-server"}}"#))

        var appSettings = AppSettings()
        appSettings.activePersonaId = "local-persona"
        _ = try await service().saveSettings(appSettings)

        XCTAssertEqual(
            try bodyJSON()["activePersonaId"] as? String, "p-server",
            "本机人格 id 推到服务端要换成它那边的 id（否则整条 400）"
        )
    }

    func testSettingsPushKeepsUnknownActivePersonaId() async throws {
        ServerURLProtocolStub.enqueue(.json(#"{"settings":{}}"#))
        var appSettings = AppSettings()
        appSettings.activePersonaId = "local-persona"
        _ = try await service().saveSettings(appSettings)
        XCTAssertEqual(try bodyJSON()["activePersonaId"] as? String, "local-persona")
    }

    func testClearUserAvatarWritesNullsAndMapsActivePersona() async throws {
        try temp.stores.personas.upsert(PersonaEntity(id: "local-persona", name: "林深"))
        try seedMapping(
            local: "local-persona", server: "p-server",
            key: ServerSyncStateStore.Key.personaIdMap
        )
        ServerURLProtocolStub.enqueue(.json(#"{"settings":{}}"#))

        var appSettings = AppSettings()
        appSettings.activePersonaId = "local-persona"
        appSettings.myAvatarUri = "/v1/media/avatar-1"
        _ = try await service().clearUserAvatar(from: appSettings)

        let body = try bodyJSON()
        XCTAssertEqual(body["activePersonaId"] as? String, "p-server")
        XCTAssertTrue(body[ServerSettingsMapping.avatarKey] is NSNull)
        XCTAssertTrue(body[ServerSettingsMapping.legacyAvatarKey] is NSNull)
    }
}

