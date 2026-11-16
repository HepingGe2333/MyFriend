import Foundation
import XCTest
@testable import MyFriendKit

/// `sourceHash` 的**跨端共享用例**（`spec/persona-translation/README.md` §4.1）。
///
/// 用例住在仓库根的 `spec/persona-translation/cases/hash-cases.json`，Android 的
/// `PersonaTranslationHashCasesTest` 读的是**同一份文件**。`expectHash` 由
/// `scripts/hash-cases.py` 独立算出（不调用两端实现）——iOS 与 Android 只要有一端
/// 在「键集合 / 是否 trim / speech 全空判定 / 字典序」上偷懒，这里就会有一端红。
///
/// 喂进被测代码的是**和真实库列一样的东西**：人设字段是裸字符串，世界/话风是 JSON 文本
/// （用例里写成对象，测试再序列化成字符串），所以这条也顺带覆盖了「宽容解析 → 规范化」这段。
final class PersonaTranslationHashCasesTests: XCTestCase {

    /// 从 `#filePath` 往上找仓库根（同时有 `spec/persona-translation/cases/hash-cases.json` 与
    /// `settings.gradle.kts`），与 `SearchSpecTests` / `PersonaTranslationJsonContractTests` 同一套做法。
    private static let repoRoot: URL = {
        var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fm = FileManager.default
        while url.path != "/" {
            let cases = url.appendingPathComponent("spec/persona-translation/cases/hash-cases.json")
            if fm.fileExists(atPath: cases.path),
               fm.fileExists(atPath: url.appendingPathComponent("settings.gradle.kts").path) {
                return url
            }
            url = url.deletingLastPathComponent()
        }
        fatalError("找不到仓库根目录（从 #filePath=\(#filePath) 向上没有 spec/persona-translation/cases/hash-cases.json）")
    }()

    private func loadCases() throws -> [[String: Any]] {
        let file = Self.repoRoot.appendingPathComponent("spec/persona-translation/cases/hash-cases.json")
        let data = try Data(contentsOf: file)
        let document = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any],
            "用例文件顶层必须是对象：\(file.path)"
        )
        let cases = try XCTUnwrap(document["cases"] as? [[String: Any]], "\(file.path) 的 cases 必须是数组")
        XCTAssertFalse(cases.isEmpty, "共享哈希用例不能为空")
        return cases
    }

    /// 用例里的世界/话风对象 → 库里存的 JSON 文本（`nil` = 这一列是 NULL）。
    private func storedJSONText(_ value: Any?) -> String? {
        guard let value, !(value is NSNull), JSONSerialization.isValidJSONObject(value) else { return nil }
        guard let data = try? JSONSerialization.data(withJSONObject: value),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    private func persona(from entry: [String: Any], name: String) throws -> PersonaEntity {
        let input = try XCTUnwrap(entry["input"] as? [String: Any], "[\(name)] 缺 input")
        let personaJSON = try XCTUnwrap(input["persona"] as? [String: Any], "[\(name)] 缺 input.persona")
        return PersonaEntity(
            id: "hash-case",
            name: personaJSON["name"] as? String ?? "",
            description: personaJSON["description"] as? String ?? "",
            prompt: personaJSON["systemPrompt"] as? String ?? "",
            traits: personaJSON["traits"] as? [String] ?? [],
            likes: personaJSON["likes"] as? [String] ?? [],
            behaviors: personaJSON["behaviors"] as? [String] ?? [],
            backstory: personaJSON["backstory"] as? String,
            quirks: personaJSON["quirks"] as? String,
            worldJson: storedJSONText(input["world"]),
            speechStyleJson: storedJSONText(input["speech"])
        )
    }

    // MARK: - 共享用例

    func testSharedHashCasesMatchIndependentReference() throws {
        let cases = try loadCases()
        XCTAssertGreaterThanOrEqual(cases.count, 3, "共享用例不能少于 3 条")
        for entry in cases {
            let name = entry["name"] as? String ?? "<unnamed>"
            let expectHash = try XCTUnwrap(entry["expectHash"] as? String, "[\(name)] 缺 expectHash")
            let source = PersonaTranslatableSource(persona: try persona(from: entry, name: name))
            XCTAssertEqual(
                PersonaTranslation.sourceHash(for: source),
                expectHash,
                "[\(name)] sourceHash 与 scripts/hash-cases.py 独立算出的权威值不一致"
            )
        }
    }

    // MARK: - 实现层回归（独立于用例文件）

    /// 「只有 phrases 的话风」= 没有话风（§4.1 第 4 条）：改常用语不能经由 null ↔ 空对象翻转
    /// 改变哈希。这条钉的是为什么 `speech` 判空不看 `phrases`。
    func testPhrasesOnlySpeechHashesLikeNoSpeech() {
        var noSpeech = PersonaTranslatableSource(name: "林深", description: "嘴硬心软的建筑师。")
        noSpeech.speech = nil
        var phrasesOnly = noSpeech
        phrasesOnly.speech = SpeechStyle(tone: "", phrases: ["嗯，我在"], habits: [], avoid: [])
        XCTAssertEqual(
            PersonaTranslation.sourceHash(for: noSpeech),
            PersonaTranslation.sourceHash(for: phrasesOnly),
            "只有 phrases 的话风必须与没有话风同哈希"
        )
    }

    /// 人设字段是库里的原值：规范化阶段不 trim，首尾空白要进哈希（§4.1 第 6 条）。
    func testPersonaFieldsAreHashedWithoutTrimming() {
        let padded = PersonaTranslatableSource(name: " 林深 ")
        let plain = PersonaTranslatableSource(name: "林深")
        XCTAssertNotEqual(
            PersonaTranslation.sourceHash(for: padded),
            PersonaTranslation.sourceHash(for: plain),
            "首尾空白必须进哈希（规范化阶段不 trim）"
        )
    }
}
