import Foundation
import XCTest
@testable import YapKit

/// 服务端设置 ⇄ 本机设置（0.12.1：用户头像 `userAvatarUri`，兼容旧键 `myAvatarUri`）。
///
/// 这一层只有两条规则，但两条都容易写反：
/// 1. 推：只有**已经是服务端媒体**的头像才写（本地 `file://` 推过去是坏图）；
/// 2. 拉：只认服务端媒体路径，缺键时保持本机值（旧服务端不认识新键也不能把头像清掉）。
final class ServerSettingsMappingTests: XCTestCase {

    private func localSettings(avatar: String? = nil) -> AppSettings {
        var settings = AppSettings()
        settings.themeMode = .system
        settings.accentColor = 999
        settings.fontScale = 1
        settings.myAvatarUri = avatar
        return settings
    }

    // MARK: - 拉

    func testApplyingRestoresAvatarFromNewKey() {
        let merged = ServerSettingsMapping.applying(
            [
                ServerSettingsMapping.avatarKey: .string("/v1/media/avatar-1"),
                "themeMode": .string("DARK"),
                "accentColor": .int(0xFF112233)
            ],
            to: localSettings()
        )
        XCTAssertEqual(merged.myAvatarUri, "/v1/media/avatar-1")
        XCTAssertEqual(merged.themeMode, .dark)
        XCTAssertEqual(merged.accentColor, 0xFF112233)
    }

    /// 服务端正在加 `userAvatarUri`：只认识旧键 `myAvatarUri` 的版本也要能还原头像。
    func testApplyingRestoresAvatarFromLegacyKeyWhenNewKeyMissing() {
        let merged = ServerSettingsMapping.applying(
            [ServerSettingsMapping.legacyAvatarKey: .string("/v1/media/avatar-legacy")],
            to: localSettings()
        )
        XCTAssertEqual(merged.myAvatarUri, "/v1/media/avatar-legacy")
    }

    /// 两个键都没有 = 这个服务端版本不带头像，本机头像必须原样保留（不能清空）。
    func testApplyingWithoutAvatarKeysKeepsLocalAvatar() {
        let merged = ServerSettingsMapping.applying(
            ["themeMode": .string("DARK")],
            to: localSettings(avatar: "file:///tmp/me.jpg")
        )
        XCTAssertEqual(merged.myAvatarUri, "file:///tmp/me.jpg")
    }

    func testApplyingExplicitNullClearsAvatar() {
        let merged = ServerSettingsMapping.applying(
            [ServerSettingsMapping.avatarKey: .null],
            to: localSettings(avatar: "/v1/media/old")
        )
        XCTAssertNil(merged.myAvatarUri)
    }

    /// 服务端误存了本机路径（或别的形状）：忽略，不要把界面变成坏引用。
    func testApplyingIgnoresNonServerAvatarShape() {
        let merged = ServerSettingsMapping.applying(
            [
                ServerSettingsMapping.avatarKey: .string("file:///tmp/other-device.jpg"),
                ServerSettingsMapping.legacyAvatarKey: .string("/v1/media/good")
            ],
            to: localSettings(avatar: "/v1/media/mine")
        )
        XCTAssertEqual(merged.myAvatarUri, "/v1/media/good", "另一个键给了有效路径就用它")
    }

    func testApplyingIgnoresWrongTypesAndClampsFontScale() {
        let merged = ServerSettingsMapping.applying(
            [
                "themeMode": .int(3),
                "fontScale": .double(9.9),
                "animationsEnabled": .string("yes"),
                "chatBackgroundColor": .null
            ],
            to: localSettings()
        )
        XCTAssertEqual(merged.themeMode, .system, "类型不对就忽略这个键")
        XCTAssertEqual(merged.fontScale, 1.3, "字号被夹到允许区间")
        XCTAssertTrue(merged.animationsEnabled)
        XCTAssertNil(merged.chatBackgroundColor)
    }

    // MARK: - 推

    func testBodyOnlyCarriesServerMediaAvatarUnderBothKeys() {
        let body = ServerSettingsMapping.body(localSettings(avatar: "/v1/media/avatar-1"))
        XCTAssertEqual(body[ServerSettingsMapping.avatarKey]?.stringValue, "/v1/media/avatar-1")
        XCTAssertEqual(body[ServerSettingsMapping.legacyAvatarKey]?.stringValue, "/v1/media/avatar-1")
        XCTAssertEqual(body["themeMode"]?.stringValue, "SYSTEM")
        XCTAssertEqual(body["accentColor"]?.intValue, 999)
    }

    func testBodyOmitsLocalFileAvatar() {
        let body = ServerSettingsMapping.body(localSettings(avatar: "file:///tmp/me.jpg"))
        XCTAssertNil(body[ServerSettingsMapping.avatarKey])
        XCTAssertNil(body[ServerSettingsMapping.legacyAvatarKey])
    }

    func testBodyClearingAvatarWritesNulls() {
        let body = ServerSettingsMapping.body(
            localSettings(avatar: "file:///tmp/me.jpg"),
            clearingAvatar: true
        )
        XCTAssertEqual(body[ServerSettingsMapping.avatarKey], .null)
        XCTAssertEqual(body[ServerSettingsMapping.legacyAvatarKey], .null)
    }

    func testHasServerAvatar() {
        XCTAssertTrue(ServerSettingsMapping.hasServerAvatar([
            ServerSettingsMapping.avatarKey: .string("/v1/media/a")
        ]))
        XCTAssertTrue(ServerSettingsMapping.hasServerAvatar([
            ServerSettingsMapping.legacyAvatarKey: .string("/v1/media/b")
        ]))
        XCTAssertFalse(ServerSettingsMapping.hasServerAvatar([:]))
        XCTAssertFalse(ServerSettingsMapping.hasServerAvatar([
            ServerSettingsMapping.avatarKey: .string("file:///tmp/a.jpg")
        ]))
        XCTAssertFalse(ServerSettingsMapping.hasServerAvatar([
            ServerSettingsMapping.avatarKey: .null
        ]))
    }
}
