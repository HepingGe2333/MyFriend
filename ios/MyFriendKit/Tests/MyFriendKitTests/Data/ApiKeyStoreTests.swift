import Foundation
import XCTest
@testable import MyFriendKit

/// API Key：Keychain 的语义（service / account 隔离、空值即清除）。
///
/// 这里用 `InMemoryKeychain` 而不是真 Keychain：`swift test` 是无签名的命令行进程，
/// 真 Keychain 在不同机器上可能需要用户授权，测试不该被弹窗卡住。
/// `ApiKeyStore` 与 `SystemKeychain` 的契约由 `KeychainBackend` 协议固定，
/// 测试覆盖的是「上层怎么用」，底层换成系统实现不影响这些断言。
final class ApiKeyStoreTests: XCTestCase {

    private var backend: InMemoryKeychain!
    private var store: ApiKeyStore!

    override func setUp() {
        super.setUp()
        backend = InMemoryKeychain()
        store = ApiKeyStore(backend: backend)
    }

    /// service 名是 App 的身份：改了它，升级后所有人「Key 都丢了」。
    func testServiceNameMatchesAppBundle() {
        XCTAssertEqual(ApiKeyStore.service, "com.myfriend.app.ios")
        XCTAssertEqual(ApiKeyStore.Account.apiKey(configId: "a-1"), "api-key.a-1")
        XCTAssertEqual(ApiKeyStore.Account.webToolsAPIKey(configId: "a-1"), "web-tools-api-key.a-1")
    }

    func testSetGetAndReplaceAPIKey() throws {
        XCTAssertNil(store.apiKey(for: "a-1"))

        try store.setAPIKey("sk-first", for: "a-1")
        XCTAssertEqual(store.apiKey(for: "a-1"), "sk-first")

        try store.setAPIKey("sk-second", for: "a-1")
        XCTAssertEqual(store.apiKey(for: "a-1"), "sk-second")
        XCTAssertEqual(backend.storedCount, 1, "同一条 Key 不该留下两条记录")
    }

    func testKeysAreIsolatedPerConfig() throws {
        try store.setAPIKey("sk-a", for: "a-1")
        try store.setAPIKey("sk-b", for: "a-2")
        XCTAssertEqual(store.apiKey(for: "a-1"), "sk-a")
        XCTAssertEqual(store.apiKey(for: "a-2"), "sk-b")

        try store.deleteKeys(for: "a-1")
        XCTAssertNil(store.apiKey(for: "a-1"))
        XCTAssertEqual(store.apiKey(for: "a-2"), "sk-b")
    }

    func testEmptyKeyClearsInsteadOfStoringBlank() throws {
        try store.setAPIKey("sk-a", for: "a-1")
        try store.setAPIKey("", for: "a-1")
        XCTAssertNil(store.apiKey(for: "a-1"))

        try store.setAPIKey("   ", for: "a-1")
        XCTAssertNil(store.apiKey(for: "a-1"))
        try store.setAPIKey(nil, for: "a-1")
        XCTAssertNil(store.apiKey(for: "a-1"))
        XCTAssertEqual(backend.storedCount, 0)
    }

    func testKeysAreTrimmedBeforeStoring() throws {
        try store.setAPIKey("  sk-padded \n", for: "a-1")
        XCTAssertEqual(store.apiKey(for: "a-1"), "sk-padded")
    }

    func testWebToolsKeyIsSeparateFromMainKey() throws {
        // 联网工具 Key 留空时复用主 Key（Domain/Net 层决定）；存储上必须是两条独立记录。
        try store.setAPIKey("sk-main", for: "a-1")
        try store.setWebToolsAPIKey("sk-web", for: "a-1")
        XCTAssertEqual(store.apiKey(for: "a-1"), "sk-main")
        XCTAssertEqual(store.webToolsAPIKey(for: "a-1"), "sk-web")

        try store.setWebToolsAPIKey(nil, for: "a-1")
        XCTAssertNil(store.webToolsAPIKey(for: "a-1"))
        XCTAssertEqual(store.apiKey(for: "a-1"), "sk-main")
    }

    func testDeleteKeysRemovesBothWithoutTouchingOtherConfigs() throws {
        try store.setAPIKey("sk-a", for: "a-1")
        try store.setWebToolsAPIKey("sk-a-web", for: "a-1")
        try store.setAPIKey("sk-b", for: "a-2")
        XCTAssertEqual(backend.storedCount, 3)

        try store.deleteKeys(for: "a-1")
        XCTAssertNil(store.apiKey(for: "a-1"))
        XCTAssertNil(store.webToolsAPIKey(for: "a-1"))
        XCTAssertEqual(store.apiKey(for: "a-2"), "sk-b")
        XCTAssertEqual(backend.storedCount, 1)
    }

    func testCustomServiceIsUsedByEveryOperation() throws {
        let custom = ApiKeyStore(backend: backend, service: "com.example.other")
        try custom.setAPIKey("sk-other", for: "a-1")
        XCTAssertEqual(custom.apiKey(for: "a-1"), "sk-other")
        // service 不同的同一个 account 读不到对方的值。
        XCTAssertNil(store.apiKey(for: "a-1"))
        XCTAssertEqual(backend.storedCount, 1)
    }

    // MARK: - 「已配置」以读出非空 Key 为准（0.10.2 安全审计 P2-6）

    func testIsConfiguredOnlyWhenKeychainReturnsNonEmptyKey() throws {
        XCTAssertFalse(store.isConfigured(for: "a-1"))
        try store.setAPIKey("sk-live", for: "a-1")
        XCTAssertTrue(store.isConfigured(for: "a-1"))

        // Keychain 条目被系统清掉 / 读不到：立刻变成「未配置」。
        try backend.delete(service: ApiKeyStore.service,
                           account: ApiKeyStore.Account.apiKey(configId: "a-1"))
        XCTAssertFalse(store.isConfigured(for: "a-1"))

        // 只剩下空白：同样不算已配置（不会「假装已保存」）。
        try backend.set("  \n ", service: ApiKeyStore.service,
                        account: ApiKeyStore.Account.apiKey(configId: "a-1"))
        XCTAssertFalse(store.isConfigured(for: "a-1"))
        // 但底层值确实还在：说明这条判定不是「看有没有记录」，而是「看值能不能用」。
        XCTAssertEqual(backend.storedCount, 1)
    }

    /// 配置了但没有 Key 时 `resolvedChatConfig` 仍然会把 `apiKey` 交给上层（nil），
    /// 界面用 `isConfigured` 决定显示「未填写」还是「已保存」。
    func testIsConfiguredIsPerConfig() throws {
        try store.setAPIKey("sk-a", for: "a-1")
        XCTAssertTrue(store.isConfigured(for: "a-1"))
        XCTAssertFalse(store.isConfigured(for: "a-2"))
    }
}
