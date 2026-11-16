import Foundation
import XCTest
@testable import MyFriendKit

/// 翻译缓存的**跨端 JSON 形状契约**（iOS ↔ Android，`spec/persona-translation/README.md` §4）。
///
/// 两端各自实现一份 `PersonaTranslation`（Swift / Kotlin），同一份缓存 JSON 会在
/// 备份恢复、服务端权威模式、跨端迁移里被另一边读到。所以这里不测哈希算法，
/// 只钉住**同一段缓存 JSON 在两端读写出同一组键**：
///
/// 1. 两端测试各自内嵌**完全相同的一段示例 JSON**（`specSampleJSON`）——规范 §4 的示例用了
///    `[...]` 占位，不是合法 JSON，不能直接解析；所以约定好一段等价的可解析样本；
/// 2. 断言根对象 / `persona` / `world` / `speech` 的键集合完全一致；
/// 3. 断言没有 `schemaVersion`、没有 `phrases`（§3：`phrases` 不进缓存），
///    没有话风/世界时仍然写 `"world": null` / `"speech": null`（不省键）；
/// 4. 额外读一遍规范 README §4，确认我们断言里的键名都真的写在规范示例里。
///
/// Android 侧的同名契约在
/// `app/src/test/java/com/myfriend/app/domain/PersonaTranslationJsonContractTest.kt`，
/// 两边 `SPEC_SAMPLE` 逐字相同、期望键相同。任何一端多一个键，那一端就红。
final class PersonaTranslationJsonContractTests: XCTestCase {

    /// 跨端契约样本：与 Android 测试里的 `SPEC_SAMPLE` **逐字相同**。
    ///
    /// 规范 §4 的示例用 `[...]` 表示数组占位，不能当 JSON 解析；这里给一份可解析的等价样本，
    /// 键名/结构与规范示例一致（额外带上了 §3 要求翻译的 `systemPrompt`）。
    private static let specSampleJSON = """
{
  "lang": "en",
  "translatedAt": 1794500000000,
  "sourceHash": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
  "persona": {
    "name": "Lin Shen",
    "description": "An architect who runs a small team.",
    "traits": ["reserved", "soft-hearted"],
    "likes": ["night walks"],
    "behaviors": ["gives the facts first"],
    "backstory": "Grew up in the south.",
    "quirks": "Rarely uses exclamation marks.",
    "systemPrompt": "You are Lin Shen."
  },
  "world": {
    "worldName": "Lingang New City",
    "era": "2026, coastal China",
    "description": "A rainy city where the studio is always busy.",
    "schedule": [],
    "relationships": [],
    "obligations": [],
    "notes": "Nobody calls him by his full name."
  },
  "speech": {
    "tone": "short and direct",
    "habits": ["very short sentences"],
    "avoid": ["long speeches"]
  }
}
"""

    private let rootKeys: Set<String> = ["lang", "translatedAt", "sourceHash", "persona", "world", "speech"]
    private let personaKeys: Set<String> = [
        "name", "description", "traits", "likes", "behaviors", "backstory", "quirks", "systemPrompt"
    ]
    private let worldKeys: Set<String> = [
        "worldName", "era", "description", "schedule", "relationships", "obligations", "notes"
    ]
    private let speechKeys: Set<String> = ["tone", "habits", "avoid"]

    // MARK: - 找规范

    private func readmeURL(file: String = #filePath) -> URL? {
        var url = URL(fileURLWithPath: file).deletingLastPathComponent()
        let fm = FileManager.default
        for _ in 0..<16 {
            let candidate = url.appendingPathComponent("spec/persona-translation/README.md")
            if fm.fileExists(atPath: candidate.path) { return candidate }
            let parent = url.deletingLastPathComponent()
            if parent.path == url.path { break }
            url = parent
        }
        return nil
    }

    private func object(_ raw: String) throws -> [String: Any] {
        let data = try XCTUnwrap(raw.data(using: .utf8))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func keySet(_ object: [String: Any], _ key: String, file: StaticString = #filePath,
                        line: UInt = #line) -> Set<String> {
        guard let nested = object[key] as? [String: Any] else {
            XCTFail("缺少对象键：\(key)", file: file, line: line)
            return []
        }
        return Set(nested.keys)
    }

    // MARK: - 契约

    func testSameSampleJSONRoundTripsToSameShapeAsAndroid() throws {
        let decoded = try XCTUnwrap(PersonaTranslation.decode(Self.specSampleJSON),
                                    "约定的跨端示例必须能被 iOS 缓存解码")
        let encoded = try XCTUnwrap(decoded.jsonString())
        let root = try object(encoded)

        XCTAssertEqual(Set(root.keys), rootKeys, "根对象键必须与 Android 完全一致")
        XCTAssertEqual(keySet(root, "persona"), personaKeys, "persona 键必须与 Android 完全一致")
        XCTAssertEqual(keySet(root, "world"), worldKeys, "world 键必须与 Android 完全一致")
        XCTAssertEqual(keySet(root, "speech"), speechKeys, "speech 只含 tone/habits/avoid")
        XCTAssertFalse(encoded.contains("\"schemaVersion\""), "iOS 不能多出 Android 没有的键")
        XCTAssertFalse(encoded.contains("\"phrases\""), "phrases 永不进缓存（§3）")

        // 往返稳定：同一段缓存 JSON 解 → 编 → 解，内容不变。
        XCTAssertEqual(PersonaTranslation.decode(encoded), decoded)
    }

    func testSampleKeysAreAllPresentInSpecSection4() throws {
        let url = try XCTUnwrap(readmeURL(), "找不到 spec/persona-translation/README.md")
        let text = try String(contentsOf: url, encoding: .utf8)
        let section = text.components(separatedBy: "## 4. 输出与缓存").dropFirst().first ?? ""
        guard let jsonStart = section.range(of: "```json"),
              let jsonEnd = section.range(of: "```", range: jsonStart.upperBound..<section.endIndex) else {
            return XCTFail("spec/persona-translation/README.md §4 里找不到 json 示例")
        }
        let block = String(section[jsonStart.upperBound..<jsonEnd.lowerBound])
        // §4 的示例漏写了 §3 要求的 systemPrompt（实现补上）；其余键必须在示例里。
        let keys = rootKeys.union(personaKeys).union(worldKeys).union(speechKeys)
            .subtracting(["systemPrompt"])
        for key in keys {
            XCTAssertTrue(block.contains("\"\(key)\""), "规范 §4 的缓存示例里没有键「\(key)」")
        }
    }

    func testWorldWithOnlyNameStillWritesEveryWorldKey() throws {
        let translation = PersonaTranslation(
            lang: "en", translatedAt: 1, sourceHash: "sha256:abc",
            persona: .init(), world: WorldPreset(worldName: "Lingang"), speech: nil
        )
        let root = try object(try XCTUnwrap(translation.jsonString()))
        let world = try XCTUnwrap(root["world"] as? [String: Any])
        XCTAssertEqual(Set(world.keys), worldKeys, "空字符串字段也要写，键集合固定")
        XCTAssertEqual(world["notes"] as? String, "")
    }

    func testMissingWorldAndSpeechAreWrittenAsNullNotOmitted() throws {
        let translation = PersonaTranslation(
            lang: "en", translatedAt: 1, sourceHash: "sha256:abc",
            persona: .init(), world: nil, speech: nil
        )
        let json = try XCTUnwrap(translation.jsonString())
        let root = try object(json)
        XCTAssertEqual(Set(root.keys), rootKeys)
        XCTAssertTrue(root["world"] is NSNull, "world 缺省写 null")
        XCTAssertTrue(root["speech"] is NSNull, "speech 缺省写 null")
        XCTAssertFalse(json.contains("schemaVersion"))
    }

    /// 请求与缓存的边界：话风只发/存 tone/habits/avoid，`phrases` 永不出现（§3）。
    func testSpeechPayloadAndCacheNeverCarryPhrases() throws {
        let style = SpeechStyle(
            tone: "简短直接",
            phrases: ["在忙，晚点说"],
            habits: ["句子很短"],
            avoid: ["长篇大论"]
        )
        let source = PersonaTranslatableSource(name: "林深", speech: style)
        let payload = PersonaTranslator.requestPayload(source: source)
        XCTAssertTrue(payload.contains("\"speech\""))
        XCTAssertFalse(payload.contains("在忙，晚点说"))
        let speechObject = try XCTUnwrap(try object(payload)["speech"] as? [String: Any])
        XCTAssertEqual(Set(speechObject.keys), speechKeys)

        let translation = PersonaTranslation(
            lang: "en", translatedAt: 1, sourceHash: "sha256:abc",
            persona: .init(),
            speech: SpeechStyle(tone: "short", habits: ["very short"], avoid: ["long speeches"])
        )
        let cache = try XCTUnwrap(translation.jsonString())
        XCTAssertFalse(cache.contains("在忙，晚点说"))
        XCTAssertEqual(keySet(try object(cache), "speech"), speechKeys)
    }
}
