import Foundation
import XCTest
@testable import YapKit

/// 服务端备份 → 本地 v6 形状的适配（`spec/experience/README.md` §11，0.14.1）。
///
/// 原样导入服务端 JSON 会出现静默错位：头像没了、80 分重要度显示成 80 星、
/// 未读丢失、`client_extras` 里的客户端独有段被当未知键丢掉。
final class ServerBackupAdapterTests: XCTestCase {

    private let serverBackup = """
    {
      "format":"myfriend.backup","formatVersion":6,"appVersion":"0.14.1",
      "exportedAt":100,"apiKeysOmitted":true,
      "personas":[{"id":"p1","name":"林深","avatarUrl":"/v1/media/av1",
        "createdAt":1,"updatedAt":2,"prompt":"你叫林深"}],
      "memories":[{"id":"m1","personaId":"p1","content":"喜欢夜里散步","importance":80,
        "tags":["散步"],"createdAt":3,"updatedAt":4}],
      "sessions":[{"id":"s1","personaId":"p1","title":"和深","lastMessageAt":5,
        "unread":2,"updatedAt":6,"createdAt":5}],
      "messages":[{"id":"msg1","sessionId":"s1","personaId":"p1","role":"USER","content":"看",
        "createdAt":5,"updatedAt":5,"attachments":["/v1/media/av1"],"source":"CHAT"}],
      "media":[],"stickers":[],"diaryNotes":[],"presets":[
        {"id":"pre1","name":"模板","prompt":"提示词"}
      ],
      "proactiveRules":[{"id":"r-top","personaId":"p1","kind":"DAILY","enabled":true}],
      "client_extras":{
        "proactiveRules":[{"id":"r-extra","personaId":"p1","kind":"DAILY"}],
        "memoryConsolidations":[{"id":"c-1","personaId":"p1","at":1,"kept":1}],
        "futureClientSection":[{"x":1}]
      },
      "extensions":{"serverOnly":{"tenant":"t-1"}},
      "memoryConsolidations":[{"id":"c-top","personaId":"p1","at":2,"kept":2}],
      "apiConfigs":[]
    }
    """

    private func localObject() throws -> [String: JSONValue] {
        let text = try XCTUnwrap(ServerBackupAdapter.toLocalBackupJSON(serverBackup))
        return try XCTUnwrap(JSONValueParser.object(from: text))
    }

    func testPersonaAvatarMovesToAvatarUri() throws {
        let local = try localObject()
        let persona = try XCTUnwrap(local["personas"]?.arrayValue?.first?.objectValue)
        XCTAssertEqual(persona["avatarUri"]?.stringValue, "/v1/media/av1")
        XCTAssertEqual(persona["id"]?.stringValue, "p1")
        XCTAssertEqual(persona["prompt"]?.stringValue, "你叫林深")
    }

    func testMemoryImportanceAndTagsBecomeLocalShapes() throws {
        let local = try localObject()
        let memory = try XCTUnwrap(local["memories"]?.arrayValue?.first?.objectValue)
        XCTAssertEqual(memory["importance"]?.intValue, 4, "80 → 4")
        XCTAssertEqual(memory["tags"]?.stringValue, #"["散步"]"#, "数组 → JSON 文本")
    }

    func testSessionUnreadAndMessageAttachmentsBecomeLocalShapes() throws {
        let local = try localObject()
        let session = try XCTUnwrap(local["sessions"]?.arrayValue?.first?.objectValue)
        XCTAssertEqual(session["unreadCount"]?.intValue, 2)
        let message = try XCTUnwrap(local["messages"]?.arrayValue?.first?.objectValue)
        let json = try XCTUnwrap(message["attachmentsJson"]?.stringValue)
        let attachments = AttachmentsCodec.decode(json)
        XCTAssertEqual(attachments.map(\.uri), ["/v1/media/av1"])
    }

    func testClientExtrasAreHoistedAndExtensionsDropped() throws {
        let local = try localObject()
        // `client_extras` 里的段被提到顶层；顶层已有值时以顶层为准。
        XCTAssertEqual(local["proactiveRules"]?.arrayValue?.first?.objectValue?["id"]?.stringValue, "r-top")
        XCTAssertEqual(local["memoryConsolidations"]?.arrayValue?.first?.objectValue?["id"]?.stringValue, "c-top")
        XCTAssertNotNil(local["futureClientSection"], "将来新增的客户端段也要透传")
        XCTAssertNil(local["extensions"], "服务端专有段客户端忽略")
        XCTAssertNil(local["client_extras"], "内部容器不留在本地备份里")
    }

    func testAlreadyLocalValuesAreNotShrunkAgain() throws {
        let raw = """
        {"format":"myfriend.backup","formatVersion":6,
         "memories":[{"id":"m1","personaId":"p1","content":"x","importance":4}]}
        """
        let text = try XCTUnwrap(ServerBackupAdapter.toLocalBackupJSON(raw))
        let local = try XCTUnwrap(JSONValueParser.object(from: text))
        XCTAssertEqual(
            local["memories"]?.arrayValue?.first?.objectValue?["importance"]?.intValue, 4
        )
    }

    func testBrokenInputReturnsNilInsteadOfThrowing() {
        XCTAssertNil(ServerBackupAdapter.toLocalBackupJSON("not json"))
        XCTAssertNil(ServerBackupAdapter.toLocalBackupJSON("[]"))
    }
}
