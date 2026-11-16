import CoreGraphics
import Foundation
import ImageIO
import XCTest
@testable import YapKit

/// `attachmentsJson` 的编解码：字段名与 Android 逐字一致、默认值不写、坏数据不崩。
///
/// 这组用例直接对应 Android 的 `AttachmentsCodecTest.kt`：
/// 同一批输入在两端必须得到同一个结果，否则同一条消息换机之后「图片就没了」。
final class AttachmentsCodecTests: XCTestCase {

    func testRoundTripsSingleImage() {
        let items = [
            MessageAttachment(
                uri: "file:///var/mobile/Containers/attachments/chat-a.jpg",
                mime: "image/jpeg",
                width: 1600,
                height: 1200
            )
        ]

        let encoded = try? XCTUnwrap(AttachmentsCodec.encode(items))
        XCTAssertTrue(encoded?.contains("chat-a.jpg") == true)
        XCTAssertTrue(encoded?.contains("1600") == true)
        XCTAssertEqual(AttachmentsCodec.decode(encoded), items)
    }

    func testEncodesEmptyListAsNil() {
        XCTAssertNil(AttachmentsCodec.encode([]))
        XCTAssertTrue(AttachmentsCodec.decode(nil).isEmpty)
        XCTAssertTrue(AttachmentsCodec.decode("").isEmpty)
        XCTAssertTrue(AttachmentsCodec.decode("   ").isEmpty)
        XCTAssertFalse(AttachmentsCodec.hasImages(nil))
    }

    /// Android 的 `encodeDefaults = false`：默认 MIME 与 0 宽高不写进 JSON。
    /// （iOS 实际会写真实宽高；这里用默认值验证「默认值省略」这条规则本身。）
    func testEncodeOmitsDefaultsLikeAndroid() {
        let encoded = AttachmentsCodec.encode([MessageAttachment(uri: "file:///tmp/a.jpg")])
        let text = encoded ?? ""
        XCTAssertFalse(text.contains("\"mime\""), "默认 image/jpeg 不该写进 JSON：\(text)")
        XCTAssertFalse(text.contains("\"width\""), "0 宽不该写进 JSON：\(text)")
        XCTAssertFalse(text.contains("\"height\""), "0 高不该写进 JSON：\(text)")

        let decoded = AttachmentsCodec.decode(text)
        XCTAssertEqual(decoded.first?.mime, "image/jpeg")
        XCTAssertEqual(decoded.first?.width, 0)
        XCTAssertEqual(decoded.first?.height, 0)
        XCTAssertTrue(decoded.first?.isImage == true)
    }

    /// 坏数据一律当没有：不抛异常、不崩、不「只留一半」。
    func testDropsBrokenPayloadsInsteadOfCrashing() {
        XCTAssertTrue(AttachmentsCodec.decode("不是 JSON").isEmpty)
        // 缺 uri：Kotlin 里 uri 是必填的，整段作废。
        XCTAssertTrue(AttachmentsCodec.decode(#"[{"mime":"image/png"}]"#).isEmpty)
        // uri 是空白：解得出但没意义，过滤掉。
        XCTAssertTrue(AttachmentsCodec.decode(#"[{"uri":"  "}]"#).isEmpty)
        // 顶层不是数组。
        XCTAssertTrue(AttachmentsCodec.decode(#"{"uri":"file:///a.jpg"}"#).isEmpty)
        // 数组里混了一个非对象：与 Kotlin 一样整段作废。
        XCTAssertTrue(AttachmentsCodec.decode(#"[{"uri":"file:///a.jpg"},1]"#).isEmpty)
        // 宽高类型不对。
        XCTAssertTrue(AttachmentsCodec.decode(#"[{"uri":"file:///a.jpg","width":"big"}]"#).isEmpty)
        // 宽的布尔值也算坏数据（JSON 里 true 不是数字）。
        XCTAssertTrue(AttachmentsCodec.decode(#"[{"uri":"file:///a.jpg","width":true}]"#).isEmpty)
    }

    func testIgnoresUnknownFieldsAndDefaultsMime() {
        let decoded = AttachmentsCodec.decode(
            #"[{"uri":"file:///tmp/b.png","extra":"ignored","mime":null}]"#
        )
        XCTAssertEqual(decoded.count, 1)
        XCTAssertEqual(decoded.first?.mime, "image/jpeg")
        XCTAssertTrue(decoded.first?.isImage == true)
    }

    /// 一条坏的、一条好的：Android 整段丢，iOS 也整段丢（不做「部分成功」）。
    func testOneBadItemDropsWholePayload() {
        let raw = #"[{"uri":"file:///a.jpg"},{"mime":"image/png"}]"#
        XCTAssertTrue(AttachmentsCodec.decode(raw).isEmpty)
    }

    func testKeepsValidAndFiltersBlankUri() {
        let raw = #"[{"uri":"file:///a.jpg","mime":"image/png"},{"uri":""}]"#
        let decoded = AttachmentsCodec.decode(raw)
        XCTAssertEqual(decoded.count, 1)
        XCTAssertEqual(decoded.first?.mime, "image/png")
    }

    /// 与 Android 的 `MessageAttachment` 完全同一个形状：字段名一个不差。
    func testJsonShapeMatchesAndroid() throws {
        let encoded = try XCTUnwrap(AttachmentsCodec.encode([
            MessageAttachment(uri: "file:///a.jpg", mime: "image/png", width: 10, height: 20)
        ]))
        let object = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [[String: Any]]
        )
        let first = try XCTUnwrap(object.first)
        XCTAssertEqual(Set(first.keys), ["uri", "mime", "width", "height"])
        XCTAssertEqual(first["uri"] as? String, "file:///a.jpg")
        XCTAssertEqual(first["mime"] as? String, "image/png")
        XCTAssertEqual(first["width"] as? Int, 10)
        XCTAssertEqual(first["height"] as? Int, 20)
    }

    /// 消息里的附件上限（Android `MAX_IMAGES_PER_MESSAGE` / `MAX_IMAGE_MESSAGES_IN_CONTEXT`）。
    func testContextLimits() {
        XCTAssertEqual(AttachmentsCodec.maxImagesPerMessage, 4)
        XCTAssertEqual(AttachmentsCodec.maxImageMessagesInContext, 2)
    }
}
