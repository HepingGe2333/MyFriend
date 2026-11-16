import Foundation
import XCTest
@testable import YapKit

/// M5 服务端写路径：人格 / 记忆 / 预设 / 设置 / 备份。
///
/// 每条都钉三件事：
/// 1. 打对了哪个接口、body 长什么样；
/// 2. 成功后**本地缓存**（SQLite）确实跟着变了；
/// 3. 服务端失败时本地**一个字都不动**（不回退静默本地写）。
final class ServerWriteServiceTests: XCTestCase {

    private var temp: TempDatabase!
    private var defaults: UserDefaults!
    private var settings: SettingsStore!
    private var mediaDirectory: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerURLProtocolStub.reset()
        temp = try TempDatabase()
        try temp.stores.personas.upsert(PersonaEntity(
            id: "p1", name: "林深",
            description: "慢热的人",
            worldJson: #"{"worldName":"临港新城","notes":"完整原文"}"#
        ))
        defaults = UserDefaults(suiteName: "mf-write-\(UUID().uuidString)")!
        settings = SettingsStore(defaults: defaults)
        mediaDirectory = temp.directory.appendingPathComponent("attachments", isDirectory: true)
        try FileManager.default.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        ServerURLProtocolStub.reset()
        defaults?.removePersistentDomain(forName: defaults.description)
        temp?.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func client() -> ServerAPIClient {
        ServerAPIClient(
            config: ServerConfig(baseURL: ServerTestSupport.defaultBaseURL, token: "secret-token"),
            session: ServerURLProtocolStub.makeSession()
        )
    }

    private func service(withBackup: Bool = false) -> ServerWriteService {
        let backup: BackupService? = withBackup
            ? BackupService(
                stores: temp.stores,
                settings: settings,
                appVersion: "test",
                avatarFileExists: { _ in false },
                mediaDirectory: mediaDirectory
              )
            : nil
        return ServerWriteService(
            client: client(),
            stores: temp.stores,
            memoryRepository: MemoryRepository(stores: temp.stores),
            backup: backup,
            now: { 1_800_000_000_000 }
        )
    }

    private func requestBodyJSON() throws -> [String: Any] {
        let data = try XCTUnwrap(ServerURLProtocolStub.recordedBodies.last)
        return try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
    }

    private func personaJSON(
        id: String = "p-server",
        name: String = "林深",
        worldJson: String = #"{"worldName":"临港新城","notes":"完整原文"}"#
    ) -> String {
        #"{"persona":{"id":"\#(id)","name":"\#(name)","description":"慢热的人","isDefault":true,"createdAt":111,"updatedAt":222,"activityJson":null,"worldJson":\#(jsonString(worldJson)),"traits":["嘴硬心软"],"likes":[],"behaviors":[],"prompt":"你叫林深。"}}"#
    }

    private func jsonString(_ value: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: [value])
        let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
        return String(text.dropFirst().dropLast())
    }

    // MARK: - 人格

    func testCreatePersonaPostsWithoutIDAndWritesServerIDToCache() async throws {
        ServerURLProtocolStub.enqueue(.json(personaJSON(id: "p-server")))
        let draft = PersonaEntity(id: UUID().uuidString, name: "林深", description: "慢热的人")
        let saved = try await service().savePersona(draft, isNew: true)

        XCTAssertEqual(saved.id, "p-server")
        XCTAssertEqual(saved.updatedAt, 222)
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/personas")
        let body = try requestBodyJSON()
        XCTAssertNil(body["id"], "新建不能带 id（服务端自己生成）")
        XCTAssertEqual(body["name"] as? String, "林深")
        XCTAssertNotNil(body["worldJson"], "世界设定的完整原文要随人格上移")
        // 本地缓存：服务端 id 那条真的写进去了。
        XCTAssertNotNil((try? temp.stores.personas.byId("p-server")) ?? nil)
        XCTAssertTrue(((try? temp.stores.personas.byId(draft.id)) ?? nil) == nil)
    }

    func testUpdatePersonaPostsIDAndKeepsServerTruth() async throws {
        ServerURLProtocolStub.enqueue(.json(personaJSON(id: "p1", name: "林深（改）")))
        var persona = try XCTUnwrap((try? temp.stores.personas.byId("p1")) ?? nil)
        persona.name = "林深（改）"
        let saved = try await service().savePersona(persona, isNew: false)
        XCTAssertEqual(saved.name, "林深（改）")
        let body = try requestBodyJSON()
        XCTAssertEqual(body["id"] as? String, "p1")
        XCTAssertEqual(
            (try? temp.stores.personas.byId("p1"))??.name, "林深（改）",
            "成功后本地缓存跟着更新"
        )
    }

    func testUpdatePersonaFailureDoesNotTouchLocalCache() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"message":"名字不能为空","code":"missing_field"}}"#, status: 400
        ))
        var persona = try XCTUnwrap((try? temp.stores.personas.byId("p1")) ?? nil)
        persona.name = "新名字"
        do {
            _ = try await service().savePersona(persona, isNew: false)
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.message, "名字不能为空")
        }
        XCTAssertEqual(
            (try? temp.stores.personas.byId("p1"))??.name, "林深",
            "服务端失败时本地不能改"
        )
    }

    func testDeletePersonaCascadesLocalCacheAfterServer() async throws {
        try temp.stores.memories.upsert(MemoryEntity(
            id: "m1", personaId: "p1", content: "他怕冷"
        ))
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: "s1", personaId: "p1", title: "对话", createdAt: 1, updatedAt: 2
        ))
        ServerURLProtocolStub.enqueue(.json(#"{"deleted":true,"id":"p1"}"#))
        let outcome = try await service().deletePersona(id: "p1", activePersonaId: "p1")
        XCTAssertTrue(outcome.personaDeleted)
        XCTAssertTrue(((try? temp.stores.personas.byId("p1")) ?? nil) == nil)
        XCTAssertEqual(try temp.stores.memories.count(personaId: "p1"), 0)
        XCTAssertTrue(((try? temp.stores.sessions.byId("s1")) ?? nil) == nil)
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.url?.path, "/v1/personas/p1")
    }

    func testDeletePersonaFailureKeepsLocalCache() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"message":"没有这个人格","code":"not_found"}}"#, status: 404
        ))
        do {
            _ = try await service().deletePersona(id: "p1", activePersonaId: nil)
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.status, 404)
        }
        XCTAssertNotNil((try? temp.stores.personas.byId("p1")) ?? nil, "服务端没删成功，本地必须还在")
    }

    // MARK: - 记忆

    func testCreateMemoryPostsScaledImportanceAndTagsThenCaches() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"memory":{"id":"mem-server","personaId":"p1","content":"他喜欢夜里散步","importance":80,"tags":["散步","夜"],"createdAt":10,"updatedAt":20}}"#
        ))
        let saved = try await service().addMemory(
            personaId: "p1", content: "他喜欢夜里散步", category: .fact, importance: 4, tags: ["散步", "夜"]
        )
        XCTAssertEqual(saved.id, "mem-server")
        XCTAssertEqual(saved.importance, 4)
        XCTAssertEqual(MemoryTags.decode(saved.tags), ["散步", "夜"])
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(request.url?.path, "/v1/personas/p1/memories")
        let body = try requestBodyJSON()
        XCTAssertNil(body["id"])
        XCTAssertEqual(body["importance"] as? Int, 80, "本地 1–5 要换算成服务端 0–100")
        XCTAssertEqual(body["tags"] as? [String], ["散步", "夜"])
        XCTAssertEqual(
            (try? temp.stores.memories.byId("mem-server"))??.content, "他喜欢夜里散步",
            "本地缓存写入服务端 id 那条"
        )
    }

    func testUpdateMemoryKeepsLocalOnlyFields() async throws {
        try temp.stores.memories.upsert(MemoryEntity(
            id: "m1", personaId: "p1", content: "旧内容", category: .event,
            importance: 2, pinned: true, drawer: "EVENT", status: "DORMANT", strength: 0.4
        ))
        ServerURLProtocolStub.enqueue(.json(
            #"{"memory":{"id":"m1","personaId":"p1","content":"新内容","importance":60,"tags":[],"createdAt":1,"updatedAt":2}}"#
        ))
        var memory = try XCTUnwrap((try? temp.stores.memories.byId("m1")) ?? nil)
        memory.content = "新内容"
        let saved = try await service().saveMemory(memory, isNew: false)
        XCTAssertEqual(saved.content, "新内容")
        XCTAssertEqual(saved.importance, 3)
        XCTAssertTrue(saved.pinned, "pinned 是本地列，服务端模型没有它，不能被覆盖掉")
        XCTAssertEqual(saved.status, "DORMANT")
        XCTAssertEqual(try requestBodyJSON()["id"] as? String, "m1")
    }

    func testDeleteMemoryRemovesCacheAndFailureKeepsIt() async throws {
        try temp.stores.memories.upsert(MemoryEntity(id: "m1", personaId: "p1", content: "忘掉我"))
        ServerURLProtocolStub.enqueue(.json(#"{"deleted":true,"id":"m1"}"#))
        try await service().deleteMemory(id: "m1")
        XCTAssertTrue(((try? temp.stores.memories.byId("m1")) ?? nil) == nil)

        try temp.stores.memories.upsert(MemoryEntity(id: "m2", personaId: "p1", content: "还在"))
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"message":"没有这条记忆","code":"not_found"}}"#, status: 404
        ))
        do {
            try await service().deleteMemory(id: "m2")
            XCTFail("应当抛错")
        } catch {
            XCTAssertNotNil((try? temp.stores.memories.byId("m2")) ?? nil)
        }
    }

    // MARK: - 预设

    func testPresetSaveAndDelete() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"preset":{"id":"pre-server","name":"夜班记者","description":"","prompt":"","category":"","avatarSeed":7,"worldJson":null,"speechStyleJson":null,"createdAt":1,"updatedAt":2}}"#
        ))
        let draft = PersonaPresetEntity(id: UUID().uuidString, name: "夜班记者", avatarSeed: 7)
        let saved = try await service().savePreset(draft, isNew: true)
        XCTAssertEqual(saved.id, "pre-server")
        XCTAssertEqual(try requestBodyJSON()["avatarSeed"] as? Int, 7)
        XCTAssertNotNil((try? temp.stores.presets.byId("pre-server")) ?? nil)

        ServerURLProtocolStub.enqueue(.json(#"{"deleted":true,"id":"pre-server"}"#))
        try await service().deletePreset(id: "pre-server")
        XCTAssertTrue(((try? temp.stores.presets.byId("pre-server")) ?? nil) == nil)
    }

    // MARK: - 设置

    func testSettingsPushOnlyWhitelistedFields() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"settings":{"themeMode":"DARK","fontScale":1.1,"activePersonaId":"p1"}}"#
        ))
        var appSettings = AppSettings()
        appSettings.themeMode = .dark
        appSettings.fontScale = 1.1
        appSettings.activePersonaId = "p1"
        let merged = try await service().saveSettings(appSettings)
        XCTAssertEqual(merged["themeMode"]?.stringValue, "DARK")
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(request.url?.path, "/v1/settings")
        let body = try requestBodyJSON()
        XCTAssertEqual(body["themeMode"] as? String, "DARK")
        XCTAssertEqual(body["activePersonaId"] as? String, "p1")
        XCTAssertNil(body["apiKey"], "设置白名单里永远没有密钥")
        XCTAssertNil(body["myAvatarUri"], "设备路径不上移")
        XCTAssertNil(body["chatBackgroundImageUri"])
    }

    func testSettingsFailureSurfacesServerMessage() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"message":"activePersonaId 指向不存在的人格","code":"invalid_argument"}}"#,
            status: 400
        ))
        do {
            _ = try await service().saveSettings(AppSettings())
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.message, "activePersonaId 指向不存在的人格")
        }
    }

    // MARK: - 读回缓存（服务端权威）

    func testRefreshMemoriesKeepsLocalOnlyColumns() async throws {
        try temp.stores.memories.upsert(MemoryEntity(
            id: "m1", personaId: "p1", content: "本地旧内容", category: .event,
            importance: 2, pinned: true, drawer: "EVENT", sourceQuote: "你上次说的",
            status: "DORMANT", strength: 0.3
        ))
        ServerURLProtocolStub.enqueue(.json(
            #"{"personaId":"p1","memories":[{"id":"m1","personaId":"p1","content":"服务端内容","importance":100,"tags":["冷"],"createdAt":1,"updatedAt":9}]}"#
        ))
        let count = try await service().refreshMemories(personaId: "p1")
        XCTAssertEqual(count, 1)
        let memory = try XCTUnwrap((try? temp.stores.memories.byId("m1")) ?? nil)
        XCTAssertEqual(memory.content, "服务端内容")
        XCTAssertEqual(memory.importance, 5)
        XCTAssertEqual(MemoryTags.decode(memory.tags), ["冷"])
        XCTAssertTrue(memory.pinned, "pinned 是本地列")
        XCTAssertEqual(memory.status, "DORMANT")
        XCTAssertEqual(memory.sourceQuote, "你上次说的")
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(request.url?.path, "/v1/personas/p1/memories")
    }

    func testRefreshPresetsUpsertsServerList() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"presets":[{"id":"pre1","name":"夜班记者","description":"","prompt":"","category":"","avatarSeed":3,"worldJson":null,"speechStyleJson":null,"createdAt":1,"updatedAt":2}]}"#
        ))
        let count = try await service().refreshPresets()
        XCTAssertEqual(count, 1)
        XCTAssertEqual((try? temp.stores.presets.byId("pre1"))??.name, "夜班记者")
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(request.url?.path, "/v1/presets")
    }

    // MARK: - 备份

    func testExportBackupReadsServerObject() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"version":5,"exportedAt":123,"personas":[{"id":"p1"}],"settings":{"themeMode":"DARK"}}"#
        ))
        let text = try await service().exportBackup()
        XCTAssertTrue(text.contains("\"version\""))
        let object = JSONValueParser.object(from: text)
        XCTAssertEqual(object?["personas"]?.arrayValue?.count, 1)
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/v1/backup")
    }

    func testImportBackupPostsModeAndDataThenUpdatesLocalCache() async throws {
        // 先用本地导出造一份合法备份（与真实导入同一条编解码路径）。
        let backup = BackupService(
            stores: temp.stores,
            settings: settings,
            appVersion: "test",
            avatarFileExists: { _ in false },
            mediaDirectory: mediaDirectory
        )
        let text = try backup.export()
        ServerURLProtocolStub.enqueue(.json(
            #"{"mode":"merge","imported":{"personas":1,"sessions":1,"messages":0,"memories":1,"presets":0,"settings":true}}"#
        ))
        let summary = try await service(withBackup: true).importBackup(text: text, mode: .merge)
        XCTAssertEqual(summary.personas, 1)
        XCTAssertEqual(summary.memories, 0)

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/backup")
        let body = try requestBodyJSON()
        XCTAssertEqual(body["mode"] as? String, "merge")
        XCTAssertNotNil(body["data"] as? [String: Any], "data 是备份对象本身，不是字符串")
        XCTAssertEqual(
            (try? temp.stores.personas.byId("p1"))??.name, "林深",
            "导入后本地缓存里有这个人"
        )
    }

    func testImportBackupFailureDoesNotTouchLocalCache() async throws {
        let text = try BackupService(
            stores: temp.stores, settings: settings, appVersion: "test",
            avatarFileExists: { _ in false }, mediaDirectory: mediaDirectory
        ).export()
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"message":"备份版本不支持","code":"unsupported_backup_version"}}"#, status: 409
        ))
        do {
            _ = try await service(withBackup: true).importBackup(text: text, mode: .replace)
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.status, 409)
        }
        XCTAssertNotNil((try? temp.stores.personas.byId("p1")) ?? nil, "服务端拒绝导入时本地不能清")
    }
}
