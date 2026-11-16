import Foundation
import XCTest
@testable import YapKit

/// Keychain 写失败时的兜底边界（0.10.2 审计 P1-5 / 安全审计 P1-1）。
///
/// 0.10.1 的实现是「任何 `SecItemAdd` 失败 → 明文写进 `UserDefaults`」，Release 与真机同样生效。
/// 而 `UserDefaults` 落在 `Library/Preferences/*.plist`，**默认会进 iCloud/iTunes 整机备份**——
/// 「API Key 只在 Keychain、不进备份」这条对外承诺就是这样静默失效的。
///
/// 这里钉住：只有「DEBUG + 模拟器」才允许兜底；其它构建写失败必须抛错，且绝不能碰 UserDefaults。
final class KeychainFallbackTests: XCTestCase {

    private let service = "com.yap.tests"
    private let account = "api-key.config-1"
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUpWithError() throws {
        try super.setUpWithError()
        suiteName = "mf-keychain-fallback-\(UUID().uuidString)"
        defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        try super.tearDownWithError()
    }

    // MARK: - 假 Keychain

    private final class FlakyKeychain: KeychainBackend {
        var failWrites: Bool
        private var storage: [String: String] = [:]

        init(failWrites: Bool) {
            self.failWrites = failWrites
        }

        func set(_ value: String, service: String, account: String) throws {
            if failWrites {
                throw KeychainError.operationFailed(status: -34018, action: "写入")
            }
            storage["\(service)|\(account)"] = value
        }

        func get(service: String, account: String) -> String? {
            storage["\(service)|\(account)"]
        }

        func delete(service: String, account: String) throws {
            storage.removeValue(forKey: "\(service)|\(account)")
        }
    }

    private func fallbackKey() -> String {
        ResilientKeychainBackend.fallbackPrefix + service + "." + account
    }

    // MARK: - 真机 / Release：绝不写明文

    func testPlaintextFallbackIsDisabledOnDeviceBuilds() {
        // `swift test` 跑在 macOS 上，与真机走同一条编译分支：必须不允许兜底。
        XCTAssertFalse(KeychainFallbackPolicy.allowsPlaintextFallback,
                       "非「DEBUG + 模拟器」构建不允许明文兜底")
        XCTAssertTrue(KeychainFallbackPolicy.deniedReason.contains("模拟器"),
                      "日志要写清为什么不能兜底")
    }

    func testWriteFailureOnDeviceThrowsAndNeverTouchesUserDefaults() {
        let primary = FlakyKeychain(failWrites: true)
        let backend = ResilientKeychainBackend(
            primary: primary,
            fallbackDefaults: defaults,
            allowsFallback: false
        )
        XCTAssertFalse(backend.plaintextFallbackEnabled)

        XCTAssertThrowsError(try backend.set("sk-secret", service: service, account: account)) { error in
            guard case KeychainError.operationFailed(let status, _) = error else {
                return XCTFail("应当把 Keychain 的原始错误抛上去：\(error)")
            }
            XCTAssertEqual(status, -34018)
        }
        XCTAssertNil(defaults.string(forKey: fallbackKey()), "真机上失败也不能留明文")
        XCTAssertNil(backend.get(service: service, account: account),
                     "兜底关掉之后读也不该去 UserDefaults 里翻")
    }

    /// 即使传入 defaults，只要不允许兜底就不会写（防止「传了就会写」的误用）。
    func testDefaultsArgumentAloneDoesNotEnableFallback() {
        let backend = ResilientKeychainBackend(
            primary: FlakyKeychain(failWrites: true),
            fallbackDefaults: defaults,
            allowsFallback: false
        )
        XCTAssertThrowsError(try backend.set("sk-secret", service: service, account: account))
        XCTAssertTrue(defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(ResilientKeychainBackend.fallbackPrefix) }.isEmpty)
    }

    // MARK: - 模拟器调试构建：允许兜底，但一旦真 Keychain 能用就立刻清掉明文

    func testSimulatorFallbackRoundTripsAndIsRemovedOnceKeychainWorks() throws {
        let primary = FlakyKeychain(failWrites: true)
        let backend = ResilientKeychainBackend(
            primary: primary,
            fallbackDefaults: defaults,
            allowsFallback: true
        )
        XCTAssertTrue(backend.plaintextFallbackEnabled)

        try backend.set("sk-secret", service: service, account: account)
        XCTAssertEqual(defaults.string(forKey: fallbackKey()), "sk-secret")
        XCTAssertEqual(backend.get(service: service, account: account), "sk-secret")

        // Keychain 恢复可用：写入成功后，UserDefaults 里的明文必须被清掉。
        primary.failWrites = false
        try backend.set("sk-secret", service: service, account: account)
        XCTAssertEqual(primary.get(service: service, account: account), "sk-secret")
        XCTAssertNil(defaults.string(forKey: fallbackKey()), "真 Keychain 写成功就不能留明文副本")
    }

    func testDeleteRemovesFallbackEntry() throws {
        let backend = ResilientKeychainBackend(
            primary: FlakyKeychain(failWrites: true),
            fallbackDefaults: defaults,
            allowsFallback: true
        )
        try backend.set("sk-secret", service: service, account: account)
        XCTAssertNotNil(defaults.string(forKey: fallbackKey()))
        try backend.delete(service: service, account: account)
        XCTAssertNil(defaults.string(forKey: fallbackKey()))
    }

    func testLegacyFallbackEntriesAreClearedButOtherKeysSurvive() throws {
        defaults.set("old-plaintext", forKey: fallbackKey())
        defaults.set("someone-elses", forKey: "unrelated.key")
        let backend = ResilientKeychainBackend(
            primary: FlakyKeychain(failWrites: true),
            fallbackDefaults: defaults,
            allowsFallback: false
        )
        XCTAssertEqual(backend.clearLegacyFallbacks(), 1)
        XCTAssertNil(defaults.string(forKey: fallbackKey()))
        XCTAssertEqual(defaults.string(forKey: "unrelated.key"), "someone-elses")
    }

    /// `ApiKeyStore` 与真机路径的组合：写失败就抛，界面能提示「这个 Key 没保存成功」。
    func testApiKeyStorePropagatesFailureInsteadOfPretendingSuccess() {
        let store = ApiKeyStore(
            backend: ResilientKeychainBackend(
                primary: FlakyKeychain(failWrites: true),
                fallbackDefaults: defaults,
                allowsFallback: false
            ),
            service: service
        )
        XCTAssertThrowsError(try store.setAPIKey("sk-secret", for: "config-1"))
        XCTAssertNil(store.apiKey(for: "config-1"))
    }
}
