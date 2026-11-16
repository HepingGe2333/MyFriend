import Foundation
import XCTest
@testable import YapKit

/// 设置：UserDefaults 键名（snake_case，与 Android 字段同名）、进出备份的边界。
///
/// 1.9 把记忆开关、回复节奏、额度与「每天主动几次」整批删掉了（§一）：
/// 这里同时守着「它们不会再被写回来」（见 `testBackupSettingsHasNoRemovedKeys`）。
final class SettingsStoreTests: XCTestCase {

    private var suiteName: String!
    private var defaults: UserDefaults!
    private var store: SettingsStore!

    override func setUpWithError() throws {
        suiteName = "myfriend.tests.settings.\(UUID().uuidString)"
        defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        store = SettingsStore(defaults: defaults)
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suiteName)
    }

    func testEmptyDefaultsMatchAndroidDefaults() {
        let settings = store.load()
        XCTAssertEqual(settings, AppSettings())
        XCTAssertEqual(settings.themeMode, .system)
        XCTAssertEqual(settings.accentColor, 0xFFC76F4E) // 4291260238，与 Android 同一个色值
        XCTAssertEqual(settings.fontScale, 1)
        XCTAssertEqual(settings.bubbleStyle, .soft)
        XCTAssertNil(settings.heartbeatLastFiredAt)
    }

    func testSaveAndLoadRoundTripsEveryField() {
        let settings = AppSettings(
            themeMode: .dark,
            accentColor: 0xFF4F7F92,
            fontScale: 1.15,
            bubbleStyle: .compact,
            animationsEnabled: false,
            chatBackgroundColor: 0xFF191A18,
            chatBackgroundImageUri: "file:///var/mobile/bg.png",
            activePersonaId: "persona-1",
            hapticsEnabled: false,
            agentLoopEnabled: false,
            backgroundTipDismissed: true,
            heartbeatLastResetAt: 1_700_000_000_000,
            heartbeatLastFiredAt: 1_700_000_100_000
        )
        store.save(settings)
        XCTAssertEqual(store.load(), settings)
    }

    func testDefaultsAreStoredUnderSnakeCaseKeys() throws {
        store.save(AppSettings(
            accentColor: 0xFF4F7F92,
            activePersonaId: "persona-1",
            agentLoopEnabled: false
        ))

        // 键名就是跨端的「共同叫法」：改键名 = 用户升级后丢设置。
        XCTAssertEqual(defaults.string(forKey: "active_persona_id"), "persona-1")
        XCTAssertEqual((defaults.object(forKey: "accent_color") as? NSNumber)?.int64Value, 0xFF4F7F92)
        XCTAssertEqual(defaults.bool(forKey: "agent_loop_enabled"), false)

        for key in SettingsStore.Key.allCases {
            XCTAssertTrue(key.rawValue.contains("_"), "键名应是 snake_case：\(key.rawValue)")
        }
    }

    func testResetClearsKeysSoNewFieldsFallBackToCodeDefaults() {
        store.save(AppSettings(themeMode: .dark, agentLoopEnabled: false))
        store.reset()
        XCTAssertEqual(store.load(), AppSettings())
        XCTAssertNil(defaults.object(forKey: SettingsStore.Key.themeMode.rawValue))
    }

    /// 「我的头像」是设备上的沙盒路径：存 UserDefaults、不进备份、也不被备份覆盖。
    func testMyAvatarUriRoundTripsAndStaysOutOfBackup() {
        let settings = AppSettings(myAvatarUri: "file:///var/mobile/avatar.jpg")
        store.save(settings)
        XCTAssertEqual(store.load().myAvatarUri, "file:///var/mobile/avatar.jpg")
        XCTAssertEqual(defaults.string(forKey: "my_avatar_uri"), "file:///var/mobile/avatar.jpg")

        // 备份里没有这个字段：导出的 JSON 里不该出现 avatar（图片本来就是设备上的东西）。
        let backup = settings.toBackupSettings()
        let encoded = (try? JSONEncoder().encode(backup)).map { String(data: $0, encoding: .utf8) } ?? nil
        XCTAssertFalse(encoded?.contains("avatar") ?? true, "备份里不该有头像路径：\(encoded ?? "nil")")

        // 从备份接回设置时，本机头像保持原样（与聊天背景图同样的边界）。
        let restored = AppSettings(myAvatarUri: "file:///var/mobile/kept.jpg").applying(backup)
        XCTAssertEqual(restored.myAvatarUri, "file:///var/mobile/kept.jpg")

        // 清空数据（reset）会把键删掉，下次读回代码默认值 nil。
        store.reset()
        XCTAssertNil(store.load().myAvatarUri)
    }

    func testSettingsFreshInstallDoesNotWriteAnything() {
        // 只读一次不该产生任何键：以后加字段时，没动过设置的用户能吃到新默认值。
        _ = store.load()
        for key in SettingsStore.Key.allCases {
            XCTAssertNil(defaults.object(forKey: key.rawValue), "不该写 \(key.rawValue)")
        }
    }

    // MARK: - 备份边界

    func testToBackupSettingsDropsDeviceOnlyFields() {
        let settings = AppSettings(
            chatBackgroundImageUri: "file:///var/mobile/bg.png",
            backgroundTipDismissed: true,
            heartbeatLastResetAt: 1_700_000_000_000,
            heartbeatLastFiredAt: 1_700_000_100_000
        )
        let backup = settings.toBackupSettings()
        // 设备路径、纯本地引导状态、心跳内部状态都不进备份（与 Android 同一取舍）。
        XCTAssertNil(backup.chatBackgroundColor)
        XCTAssertNil(backup.activePersonaId)
        XCTAssertEqual(backup.themeMode, "SYSTEM")
        XCTAssertTrue(backup.agentLoopEnabled)
    }

    /// 1.9 的守卫：被删掉的那批设置键**不许**再出现在备份段里（§八、§九「消失的设置」）。
    func testBackupSettingsHasNoRemovedKeys() throws {
        let backup = AppSettings().toBackupSettings()
        let json = try BackupCodec.encode(BackupBundle(settings: backup))
        for removed in ["replyPace", "memoryEnabled", "autoMemoryEnabled",
                        "memoryConsolidationEnabled", "webSearchScoringEnabled",
                        "proactiveDailyLimit", "monthlyBudgetMicroUsd", "overBudgetChatOnly",
                        "inputPriceUsdPerMillion", "outputPriceUsdPerMillion"] {
            XCTAssertFalse(json.contains("\"\(removed)\""), "备份里不该再有 \(removed)")
        }
    }

    func testApplyingBackupClampsRangesLikeAndroid() {
        let current = AppSettings(chatBackgroundImageUri: "file:///local.png", backgroundTipDismissed: true)
        let backup = BackupSettings(
            themeMode: "未来才有的主题",
            fontScale: 5.0,
            bubbleStyle: "未来才有的气泡",
            activePersonaId: "persona-9"
        )

        let applied = current.applying(backup)
        XCTAssertEqual(applied.themeMode, .system)         // 不认识 → 默认档
        XCTAssertEqual(applied.bubbleStyle, .soft)
        XCTAssertEqual(applied.fontScale, 1.3, accuracy: 0.000_001) // 夹在 0.85–1.3
        XCTAssertEqual(applied.activePersonaId, "persona-9")
        // 本机字段保持原样，不被一份备份抹掉。
        XCTAssertEqual(applied.chatBackgroundImageUri, "file:///local.png")
        XCTAssertTrue(applied.backgroundTipDismissed)
    }

    func testBackupSettingsRoundTripThroughCodecKeepsPortableFields() throws {
        let settings = AppSettings(
            themeMode: .light,
            accentColor: 0xFFC76F4E,
            fontScale: 0.9,
            bubbleStyle: .outlined,
            animationsEnabled: false,
            chatBackgroundColor: 0xFF191A18,
            activePersonaId: "persona-1",
            hapticsEnabled: false,
            agentLoopEnabled: false
        )
        let bundle = BackupBundle(settings: settings.toBackupSettings())
        let json = try BackupCodec.encode(bundle)
        let decoded = try XCTUnwrap(BackupCodec.decode(json).bundle)
        let restored = AppSettings().applying(try XCTUnwrap(decoded.settings))

        XCTAssertEqual(restored.themeMode, .light)
        XCTAssertEqual(restored.fontScale, 0.9, accuracy: 0.000_001)
        XCTAssertEqual(restored.bubbleStyle, .outlined)
        XCTAssertEqual(restored.activePersonaId, "persona-1")
        XCTAssertEqual(restored.chatBackgroundColor, 0xFF191A18)
        XCTAssertFalse(restored.agentLoopEnabled)
    }
}
