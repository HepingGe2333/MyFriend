import Foundation
import XCTest
@testable import MyFriendKit

/// 服务端配置与安全存储（M3）：令牌进 Keychain、`load` 的优先级、脱敏、https 规则。
final class ServerCredentialStoreTests: XCTestCase {

    private var keychain: InMemoryKeychain!
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var store: ServerCredentialStore!

    override func setUpWithError() throws {
        try super.setUpWithError()
        keychain = InMemoryKeychain()
        suiteName = "server-credential-tests-\(UUID().uuidString)"
        defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        store = ServerCredentialStore(keychain: keychain, defaults: defaults)
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suiteName)
        keychain = nil
        defaults = nil
        store = nil
        try super.tearDownWithError()
    }

    // MARK: - Keychain

    func testTokenRoundTripsThroughKeychainAndTrims() throws {
        XCTAssertNil(store.token())
        XCTAssertFalse(store.hasToken)

        try store.setToken("  secret-token \n")

        XCTAssertEqual(store.token(), "secret-token")
        XCTAssertTrue(store.hasToken)
        // 真的写进了 Keychain（不是内存里的某个字段）
        XCTAssertEqual(
            keychain.get(service: ServerCredentialStore.service, account: ServerCredentialStore.tokenAccount),
            "secret-token"
        )
    }

    func testEmptyTokenClearsKeychainEntry() throws {
        try store.setToken("secret")
        XCTAssertEqual(keychain.storedCount, 1)

        try store.setToken("   ")

        XCTAssertNil(store.token())
        XCTAssertEqual(keychain.storedCount, 0, "空字符串等于清除，不留一条空 Key")
    }

    func testMaskedTokenOnlyShowsLastFourCharacters() throws {
        XCTAssertNil(store.maskedToken())
        try store.setToken("prod_super_secret_token_abcd")
        let masked = try XCTUnwrap(store.maskedToken())

        XCTAssertTrue(masked.hasSuffix("abcd"))
        XCTAssertFalse(masked.contains("prod"))
        XCTAssertFalse(masked.contains("secret"))
        XCTAssertEqual(masked.count, 12, "•••••••• + 末尾 4 位")
    }

    func testSummaryNeverContainsRawToken() throws {
        try store.setToken("prod_super_secret_token_abcd")
        let summary = store.summary(baseURL: URL(string: "https://chat.example.com"))

        XCTAssertEqual(summary, "https://chat.example.com · 已保存令牌")
        XCTAssertFalse(summary.contains("secret"))
    }

    // MARK: - 开关与地址

    func testModeFlagsAreNilUntilUserTouchesThem() {
        XCTAssertNil(store.modeEnabled())
        XCTAssertNil(store.storedBaseURLText())

        store.setModeEnabled(false)
        XCTAssertEqual(store.modeEnabled(), false)
        store.setModeEnabled(nil)
        XCTAssertNil(store.modeEnabled(), "取消设置 = 回到「从没动过」")
    }

    func testBaseURLTextIsTrimmedAndCleared() {
        store.setBaseURLText("  https://chat.example.com \n")
        XCTAssertEqual(store.storedBaseURLText(), "https://chat.example.com")
        store.setBaseURLText("  ")
        XCTAssertNil(store.storedBaseURLText())
    }

    // MARK: - load 的优先级

    /// 设置页填的地址/令牌**优先于**环境变量（用户看不到环境变量，只会觉得设置没生效）。
    func testLoadPrefersDeviceSettingsOverEnvironment() throws {
        try store.setToken("device-token")
        store.setBaseURLText("https://device.example.com")
        store.setModeEnabled(true)

        let config = try ServerConfig.load(
            environment: [
                ServerConfig.EnvironmentKey.url: "https://env.example.com",
                ServerConfig.EnvironmentKey.token: "env-token",
                ServerConfig.EnvironmentKey.enabled: "false"
            ],
            infoDictionary: nil,
            buildAllowsInsecureHTTP: true,
            credentials: store
        )

        XCTAssertEqual(config.baseURL?.absoluteString, "https://device.example.com")
        XCTAssertEqual(config.token, "device-token")
        XCTAssertTrue(config.enabled)
        XCTAssertTrue(config.isUsable)
    }

    /// 设置页没填地址 → 回落到环境变量（调试/CI 的用法不被破坏）。
    func testLoadFallsBackToEnvironmentWhenSettingsAreEmpty() throws {
        let config = try ServerConfig.load(
            environment: [
                ServerConfig.EnvironmentKey.url: "https://env.example.com",
                ServerConfig.EnvironmentKey.token: "env-token"
            ],
            infoDictionary: nil,
            buildAllowsInsecureHTTP: true,
            credentials: store
        )

        XCTAssertEqual(config.baseURL?.absoluteString, "https://env.example.com")
        XCTAssertEqual(config.token, "env-token")
    }

    /// 用户在设置页关掉服务端模式：即便环境变量配了地址也不可用（开关是用户的明确意愿）。
    func testUserDisabledSwitchWinsOverEnvironment() throws {
        store.setModeEnabled(false)
        store.setBaseURLText("https://device.example.com")

        let config = try ServerConfig.load(
            environment: [ServerConfig.EnvironmentKey.url: "https://env.example.com"],
            infoDictionary: nil,
            buildAllowsInsecureHTTP: true,
            credentials: store
        )

        XCTAssertFalse(config.enabled)
        XCTAssertFalse(config.isUsable, "关掉开关就不该发请求")
    }

    func testNoAddressAtAllMeansDisabledButKeepsToken() throws {
        try store.setToken("device-token")
        let config = try ServerConfig.load(
            environment: [:], infoDictionary: nil, buildAllowsInsecureHTTP: true, credentials: store
        )
        XCTAssertNil(config.baseURL)
        XCTAssertFalse(config.enabled)
        XCTAssertEqual(config.token, "device-token")
        XCTAssertFalse(config.isUsable)
    }

    // MARK: - https 规则（与既有 ATS 测试同一口径，这里从「设置页来源」再验一次）

    func testStoredHTTPAddressIsRejectedInReleaseBuild() throws {
        store.setBaseURLText("http://192.168.1.10:8787")
        XCTAssertThrowsError(
            try ServerConfig.load(
                environment: [:], infoDictionary: nil, buildAllowsInsecureHTTP: false, credentials: store
            )
        ) { error in
            XCTAssertEqual(error as? ServerConfigError, .insecureHTTPNotAllowedInRelease)
        }
    }

    func testStoredHTTPAddressIsAllowedInDebugForLocalNetworkOnly() throws {
        store.setBaseURLText("http://127.0.0.1:8787")
        let local = try ServerConfig.load(
            environment: [:], infoDictionary: nil, buildAllowsInsecureHTTP: true, credentials: store
        )
        XCTAssertEqual(local.baseURL?.absoluteString, "http://127.0.0.1:8787")

        store.setBaseURLText("http://chat.example.com")
        XCTAssertThrowsError(
            try ServerConfig.load(
                environment: [:], infoDictionary: nil, buildAllowsInsecureHTTP: true, credentials: store
            )
        ) { error in
            XCTAssertEqual(
                error as? ServerConfigError, .insecureHTTPRequiresLocalNetwork("chat.example.com")
            )
        }
    }

    /// `loadForApp` 是装配层该用的入口：默认真的读设备设置。
    func testLoadForAppReadsDeviceSettings() throws {
        try store.setToken("device-token")
        store.setBaseURLText("https://device.example.com")
        let config = try ServerConfig.loadForApp(
            credentials: store, environment: [:], infoDictionary: nil, buildAllowsInsecureHTTP: true
        )
        XCTAssertEqual(config.baseURL?.absoluteString, "https://device.example.com")
        XCTAssertTrue(config.hasToken)
    }

    /// 脱敏展示：配置对象的打印里永远没有令牌原文。
    func testConfigDescriptionRedactsToken() {
        let config = ServerConfig(
            baseURL: URL(string: "https://chat.example.com"), token: "device-token"
        )
        XCTAssertFalse(config.description.contains("device-token"))
        XCTAssertTrue(config.description.contains("<redacted>"))
    }
}

// MARK: - 写路径

/// 新建会话 / 标已读 / 上报活动 / 注册推送 / 测试连接的线上契约。
final class ServerWritePathTests: XCTestCase {

    override func setUp() {
        super.setUp()
        ServerURLProtocolStub.reset()
    }

    override func tearDown() {
        ServerURLProtocolStub.reset()
        super.tearDown()
    }

    private func jsonBody(_ index: Int = 0) throws -> [String: Any] {
        let data = ServerURLProtocolStub.recordedBodies[index]
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testCreateSessionPostsPersonaAndDecodesExistingSession() async throws {
        ServerURLProtocolStub.enqueue(.json("""
        {
          "created": false,
          "session": {
            "id": "s1", "personaId": "p1", "title": "夜里散步", "createdAt": 1, "updatedAt": 2,
            "lastMessageAt": 3, "lastBlockIndex": 0, "unread": 2, "messageCount": 5
          }
        }
        """))
        let client = ServerTestSupport.client()

        let result = try await client.createSession(personaId: "p1", title: "夜里散步")

        XCTAssertFalse(result.created, "已存在时会话是复用的（幂等）")
        XCTAssertEqual(result.session.id, "s1")
        XCTAssertEqual(result.session.unread, 2)
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/sessions")
        XCTAssertEqual(try jsonBody()["personaId"] as? String, "p1")
        XCTAssertEqual(try jsonBody()["title"] as? String, "夜里散步")
    }

    func testCreateSessionWithoutTitleOmitsField() async throws {
        ServerURLProtocolStub.enqueue(.json("""
        { "created": true, "session": { "id": "s1", "personaId": "p1", "title": "", "createdAt": 1,
          "updatedAt": 2, "lastMessageAt": null, "lastBlockIndex": -1, "unread": 0, "messageCount": 0 } }
        """))
        _ = try await ServerTestSupport.client().createSession(personaId: "p1")
        XCTAssertNil(try jsonBody()["title"], "没填标题就不该发明一个")
    }

    func testCreateSessionRejectsEmptyPersonaIdWithoutSending() async throws {
        do {
            _ = try await ServerTestSupport.client().createSession(personaId: "  ")
            XCTFail("应当拒绝")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.invalidArgument)
        }
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty)
    }

    func testMarkReadPostsUpToAndDecodesUnread() async throws {
        ServerURLProtocolStub.enqueue(.json("""
        { "sessionId": "s1", "unread": 0, "readAt": 1234, "marked": 2 }
        """))
        let result = try await ServerTestSupport.client().markRead(sessionId: "s1", upTo: 1_700)

        XCTAssertEqual(result.unread, 0)
        XCTAssertEqual(result.marked, 2)
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.first?.url?.path, "/v1/sessions/s1/read")
        XCTAssertEqual(try jsonBody()["upTo"] as? Int64, 1_700)
    }

    func testMarkReadWithoutUpToOmitsField() async throws {
        ServerURLProtocolStub.enqueue(.json(#"{"sessionId":"s1","unread":0,"readAt":1}"#))
        _ = try await ServerTestSupport.client().markRead(sessionId: "s1")
        XCTAssertNil(try jsonBody()["upTo"])
    }

    func testReportActivityPostsGuidanceFields() async throws {
        ServerURLProtocolStub.enqueue(.json("""
        { "activity": { "kind": "WORK", "activity": "在事务所赶图", "until": 1800003600000,
          "availability": "BUSY" } }
        """))
        let result = try await ServerTestSupport.client().reportActivity(
            personaId: "p1", activity: "在事务所赶图", kind: "WORK", minutes: 60, availability: "BUSY"
        )

        XCTAssertEqual(result.kind, "WORK")
        XCTAssertEqual(result.until, 1_800_003_600_000)
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.first?.url?.path, "/v1/personas/p1/activity")
        let body = try jsonBody()
        XCTAssertEqual(body["activity"] as? String, "在事务所赶图")
        XCTAssertEqual(body["kind"] as? String, "WORK")
        XCTAssertEqual(body["minutes"] as? Int, 60)
        XCTAssertEqual(body["availability"] as? String, "BUSY")
    }

    func testReportActivityRejectsEmptyText() async throws {
        do {
            _ = try await ServerTestSupport.client().reportActivity(personaId: "p1", activity: "   ")
            XCTFail("应当拒绝")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.invalidArgument)
        }
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty)
    }

    func testRegisterPushTokenPostsPlatformAndBundle() async throws {
        ServerURLProtocolStub.enqueue(.json("""
        { "registered": true, "created": true, "platform": "ios", "bundleId": "com.myfriend.app.ios" }
        """))
        let result = try await ServerTestSupport.client().registerPushToken(
            token: "apns-token", bundleId: "com.myfriend.app.ios"
        )

        XCTAssertTrue(result.registered)
        XCTAssertTrue(result.created)
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.first?.url?.path, "/v1/push/register")
        let body = try jsonBody()
        XCTAssertEqual(body["platform"] as? String, "ios")
        XCTAssertEqual(body["token"] as? String, "apns-token")
        XCTAssertEqual(body["bundleId"] as? String, "com.myfriend.app.ios")
    }

    /// 「测试连接」打的是**根级** `/health`（不是 `/v1/health`），并且沿用同一个令牌。
    func testConnectionHitsRootHealthAndDecodesVersion() async throws {
        ServerURLProtocolStub.enqueue(.json(#"{"ok":true,"service":"myfriend-agentd","version":"0.12.0"}"#))
        let client = ServerTestSupport.client(baseURL: URL(string: "https://chat.example.com/v1")!)

        let health = try await client.testConnection()

        XCTAssertTrue(health.ok)
        XCTAssertEqual(health.version, "0.12.0")
        XCTAssertEqual(health.describe, "连接正常（服务端 0.12.0）")
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.url?.path, "/health")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer secret-token")
    }

    /// 服务端的错误体要原样翻成中文，并且带状态码（补发队列靠它区分 4xx）。
    func testWritePathMapsServerErrorEnvelope() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"code":"not_found","message":"没有这个会话"}}"#, status: 404
        ))
        do {
            _ = try await ServerTestSupport.client().markRead(sessionId: "s1")
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, "not_found")
            XCTAssertEqual(error.status, 404)
            XCTAssertEqual(error.message, "没有这个会话")
            XCTAssertEqual(error.statusCode, 404)
        }
    }

    /// 令牌不对时给的是「服务端要求有效的访问 Token」这类可直接展示的文案。
    func testUnauthorizedIsTranslated() async throws {
        ServerURLProtocolStub.enqueue(.json("{}", status: 401))
        do {
            _ = try await ServerTestSupport.client().createSession(personaId: "p1")
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.status, 401)
            XCTAssertTrue(error.message.contains("Token"))
        }
    }
}
