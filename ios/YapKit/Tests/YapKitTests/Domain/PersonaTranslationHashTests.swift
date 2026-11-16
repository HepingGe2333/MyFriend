import CryptoKit
import Foundation
import XCTest
@testable import YapKit

/// `sourceHash` 的**算法契约**（`spec/persona-translation/README.md` §4）。
///
/// `sourceHash` 是「这份译文是按哪一版源字段翻的」的指纹：任一被翻译字段变化 → 哈希对不上 →
/// 缓存作废、下次重译。它**必须是两端同一个算法**，否则一端翻译、另一端读缓存会永远判定失效
/// （每次打开会话都重翻一遍，白花钱）。规范 §4 定死的算法是：
///
/// - 对象形状 `{"persona":{…},"world":{…} 或 null,"speech":{…} 或 null}`，
///   键集合与缓存 JSON（`PersonaTranslation.jsonString()`）的三个子对象**逐键一致**；
/// - 键按字典序排序、无多余空白（`json_encode` 等价的紧凑形式）；
/// - UTF-8 字节做 SHA-256 → 十六进制小写 → 前缀 `sha256:`；
/// - `phrases` 不参与（§3：它不被翻译）；世界/话风「没有」与「全空」都写 `null`。
///
/// 这里钉的是**字节级期望值**：`pythonReference*` 两组固定输入的 64 位 hex 是**独立算出**的，
/// 不是拿被测实现生成的。复算方式（临时脚本，不依赖本仓库）：
///
/// ```python
/// import json, hashlib
/// obj = {"persona": {...}, "world": None or {...}, "speech": None or {...}}
/// text = json.dumps(obj, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
/// print("sha256:" + hashlib.sha256(text.encode('utf-8')).hexdigest())
/// ```
///
/// 只要 iOS 这边的规范化 JSON 与 Python `json.dumps(..., sort_keys=True, separators=(',', ':'))`
/// 有一个字节不同（多一个空格、键序不同、`\u` 转义、`null` 写成缺键），这两条就会红——
/// 那正是「跨端不一致」的样子。
final class PersonaTranslationHashTests: XCTestCase {

    // MARK: - 固定输入

    /// 固定输入 1：**只有人设字段**，没有世界、没有话风（`world` / `speech` 都是 `null`）。
    ///
    /// 字段值刻意用中文（校验非 ASCII 不会被 `\uXXXX` 转义）且不带引号/反斜杠/控制字符
    /// （那类字符的转义两套实现本来就不该有分歧，不在这条契约的验证范围里）。
    private func personaOnlySource() -> PersonaTranslatableSource {
        PersonaTranslatableSource(
            name: "林深",
            description: "嘴硬心软的建筑师。",
            traits: ["嘴硬心软"],
            likes: ["夜里散步"],
            behaviors: ["先给事实"],
            backstory: "父亲早年去世。",
            quirks: "不太用感叹号。",
            systemPrompt: "你叫林深。"
        )
    }

    /// 固定输入 2：人设 + 世界（含 schedule / relationships / intimacy / obligations / notes）
    /// + 话风（含 `phrases`——它不进哈希）。
    private func fullSource() -> PersonaTranslatableSource {
        PersonaTranslatableSource(
            name: "林深",
            description: "嘴硬心软的建筑师。",
            traits: ["嘴硬心软", "话少"],
            likes: ["夜里散步"],
            behaviors: ["先给事实"],
            backstory: "父亲早年去世。",
            quirks: "不太用感叹号。",
            systemPrompt: "你叫林深。",
            world: WorldPreset(
                worldName: "临港新城·云筑建筑事务所",
                era: "当代，中国沿海新一线城市，2026 年",
                description: "事务所竞争激烈，甲方频繁改图。",
                schedule: [
                    WorldScheduleItem(day: "工作日", start: "09:00", end: "18:30",
                                      activity: "在事务所画图"),
                    WorldScheduleItem(day: "周三", start: "20:00", end: "20:30",
                                      activity: "接母亲电话")
                ],
                relationships: [
                    WorldRelationship(name: "母亲", relation: "家人", note: "每周三晚打电话", intimacy: 4),
                    WorldRelationship(name: "老周", relation: "合伙人", note: "催图直接")
                ],
                obligations: ["按节点交图", "每周三接母亲电话"],
                notes: "事务所里没人叫他全名，都叫「深哥」。"
            ),
            speech: SpeechStyle(
                tone: "简短直接，偏冷淡",
                phrases: ["在忙，晚点说", "你先睡吧"],
                habits: ["句子很短", "少用感叹号"],
                avoid: ["长篇大论", "书面化表达"]
            )
        )
    }

    // MARK: - Python 独立算出的期望值

    /// 固定输入 1 的期望哈希（Python `json.dumps(sort_keys=True, separators=(',', ':'))` +
    /// `sha256(utf-8)` 算出；规范化文本为
    /// `{"persona":{"backstory":"父亲早年去世。","behaviors":["先给事实"],"description":"嘴硬心软的建筑师。","likes":["夜里散步"],"name":"林深","quirks":"不太用感叹号。","systemPrompt":"你叫林深。","traits":["嘴硬心软"]},"speech":null,"world":null}`）。
    private static let pythonReferencePersonaOnlyHash =
        "sha256:78c34486dcb191bc32164dda387cf08273f26746af8f95d92a86d4c3553599f8"

    /// 固定输入 2 的期望哈希（同一段 Python 脚本算出；world 的 7 个键与 speech 的 3 个键都在，
    /// `relationships[0]` 多一个 `intimacy:4`，`phrases` 不出现）。
    private static let pythonReferenceFullSourceHash =
        "sha256:2225c61f8f1fe41a2b0bc2033407a70d23da118e77da9b4ba4b026256b3cf904"

    // MARK: - 小工具

    private func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// 测试侧独立重建「哈希输入」：从缓存 JSON 里摘掉 `lang` / `translatedAt` / `sourceHash`
    /// 三个元数据键，剩下的就是三个子对象（§4 规定的规范化对象）。
    private func canonicalInputText(for translation: PersonaTranslation) throws -> String {
        let json = try XCTUnwrap(translation.jsonString())
        var root = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try XCTUnwrap(json.data(using: .utf8))) as? [String: Any]
        )
        root.removeValue(forKey: "lang")
        root.removeValue(forKey: "translatedAt")
        root.removeValue(forKey: "sourceHash")
        let data = try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
        return try XCTUnwrap(String(data: data, encoding: .utf8))
    }

    private func translation(of source: PersonaTranslatableSource) -> PersonaTranslation {
        PersonaTranslation(
            lang: "en",
            translatedAt: 1,
            sourceHash: PersonaTranslation.sourceHash(for: source),
            persona: PersonaTranslation.PersonaFields(
                name: source.name,
                description: source.description,
                traits: source.traits,
                likes: source.likes,
                behaviors: source.behaviors,
                backstory: source.backstory,
                quirks: source.quirks,
                systemPrompt: source.systemPrompt
            ),
            world: source.world,
            speech: source.speech
        )
    }

    // MARK: - 字节级期望值

    /// 只有人设：`world` / `speech` 都写 `null`（不省键），哈希与 Python 独立实现一致。
    func testPersonaOnlyHashMatchesPythonReference() {
        let hash = PersonaTranslation.sourceHash(for: personaOnlySource())
        XCTAssertEqual(hash, Self.pythonReferencePersonaOnlyHash,
                       "规范化 JSON 与 Python json.dumps(sort_keys, separators=(',',':')) 不一致")
    }

    /// 人设 + 世界 + 话风：完整形状（含 schedule/relationships/intimacy/notes），
    /// 哈希与 Python 独立实现一致。
    func testFullSourceHashMatchesPythonReference() {
        let hash = PersonaTranslation.sourceHash(for: fullSource())
        XCTAssertEqual(hash, Self.pythonReferenceFullSourceHash,
                       "完整形状（含 intimacy、notes、schedule）的规范化 JSON 与 Python 不一致")
    }

    /// 哈希输入必须与缓存 JSON 的三个子对象**逐键同形**（§4：两者共用一份构造）。
    ///
    /// 这条不钉具体 hex，而是钉「`jsonString()` 去掉三个元数据键之后，SHA-256 就等于 sourceHash」：
    /// 以后谁在 `jsonString()` 里加了一个键却没加进哈希输入（或反过来），这条立刻红。
    func testHashInputIsExactlyTheCacheJsonMinusMetadata() throws {
        for source in [personaOnlySource(), fullSource()] {
            let translation = translation(of: source)
            let text = try canonicalInputText(for: translation)
            let expected = "sha256:" + sha256Hex(Data(text.utf8))
            XCTAssertEqual(PersonaTranslation.sourceHash(for: source), expected,
                           "哈希输入与缓存 JSON 子对象不同形：\(text)")
        }
    }

    // MARK: - 确定性与格式

    func testSameSourceAlwaysHashesToTheSameSpecFormattedValue() {
        let first = PersonaTranslation.sourceHash(for: fullSource())
        let second = PersonaTranslation.sourceHash(for: fullSource())
        XCTAssertEqual(first, second, "同一份源必须得到同一个哈希（否则缓存永远命中不了）")
        XCTAssertTrue(first.hasPrefix("sha256:"))
        // `^sha256:[0-9a-f]{64}$`：小写十六进制、正好 64 位。
        XCTAssertNotNil(first.range(of: "^sha256:[0-9a-f]{64}$", options: .regularExpression),
                        "哈希格式不是规范要的 sha256:<64 位小写 hex>：\(first)")
        XCTAssertEqual(first.count, "sha256:".count + 64)
    }

    // MARK: - 任一字段变化 → 哈希变化

    /// 世界/话风里**任意一个**字段改了，哈希都必须变（否则改了设定还用旧译文）。
    func testAnyWorldOrSpeechFieldChangeChangesHash() {
        let base = fullSource()
        let hash = PersonaTranslation.sourceHash(for: base)
        XCTAssertNotNil(base.world)
        XCTAssertNotNil(base.speech)

        var worldNotesChanged = base
        worldNotesChanged.world?.notes = "换了一句背景。"
        XCTAssertNotEqual(hash, PersonaTranslation.sourceHash(for: worldNotesChanged), "world.notes 变化未失效")

        var scheduleChanged = base
        scheduleChanged.world?.schedule[0].activity = "在事务所开会"
        XCTAssertNotEqual(hash, PersonaTranslation.sourceHash(for: scheduleChanged), "schedule[0].activity 变化未失效")

        var relationshipChanged = base
        relationshipChanged.world?.relationships[1].note = "催图很客气"
        XCTAssertNotEqual(hash, PersonaTranslation.sourceHash(for: relationshipChanged),
                          "relationships[1].note 变化未失效")

        var intimacyChanged = base
        intimacyChanged.world?.relationships[0].intimacy = 5
        XCTAssertNotEqual(hash, PersonaTranslation.sourceHash(for: intimacyChanged),
                          "relationships[0].intimacy 变化未失效")

        var obligationsChanged = base
        obligationsChanged.world?.obligations = ["按节点交图"]
        XCTAssertNotEqual(hash, PersonaTranslation.sourceHash(for: obligationsChanged), "obligations 变化未失效")

        var toneChanged = base
        toneChanged.speech?.tone = "简短直接"
        XCTAssertNotEqual(hash, PersonaTranslation.sourceHash(for: toneChanged), "speech.tone 变化未失效")

        var avoidChanged = base
        avoidChanged.speech?.avoid = ["长篇大论"]
        XCTAssertNotEqual(hash, PersonaTranslation.sourceHash(for: avoidChanged), "speech.avoid 变化未失效")

        // 人设字段同理（回归：旧实现也是「任一字段变必变」，不能被重构改坏）。
        var nameChanged = base
        nameChanged.name = "林岸"
        XCTAssertNotEqual(hash, PersonaTranslation.sourceHash(for: nameChanged), "persona.name 变化未失效")
    }

    /// `phrases` 不参与翻译、也就不参与哈希（§3）：只改常用语不该让整份译文失效。
    func testPhrasesNeverAffectHash() {
        let base = fullSource()
        var changed = base
        changed.speech?.phrases = ["知道了", "再说吧"]
        XCTAssertEqual(PersonaTranslation.sourceHash(for: base), PersonaTranslation.sourceHash(for: changed),
                       "phrases 改了不该重翻")

        var noPhrases = base
        noPhrases.speech?.phrases = []
        XCTAssertEqual(PersonaTranslation.sourceHash(for: base), PersonaTranslation.sourceHash(for: noPhrases))
    }

    // MARK: - 「没有世界」与「空世界」等价

    /// 全空的 `WorldPreset`（七字段全空）与 `nil` 语义相同，哈希也必须相同：都写 `null`。
    ///
    /// 现实场景：`WorldPreset.decode` 对全空 JSON 返回 `nil`，但手工构造/编辑器清空后
    /// 可能拿到一个"存在但全空"的对象——两种情况的缓存必须能互相命中。
    func testEmptyWorldHashesLikeNoWorld() {
        let source = personaOnlySource()
        var emptyWorld = source
        emptyWorld.world = WorldPreset()
        XCTAssertNotNil(emptyWorld.world)
        XCTAssertTrue(emptyWorld.world!.isEmpty)
        XCTAssertEqual(PersonaTranslation.sourceHash(for: source),
                       PersonaTranslation.sourceHash(for: emptyWorld),
                       "空世界必须与 nil 同哈希（都写 JSON null）")
        // 与 Python 的「只有人设」期望值也是同一个：证明确实写的是 null 而不是空对象。
        XCTAssertEqual(PersonaTranslation.sourceHash(for: emptyWorld),
                       Self.pythonReferencePersonaOnlyHash)
    }

    /// 空话风同理：`speech` 存在但 `isEmpty` → 写 `null`。
    func testEmptySpeechHashesLikeNoSpeech() {
        let source = personaOnlySource()
        var emptySpeech = source
        emptySpeech.speech = SpeechStyle()
        XCTAssertNotNil(emptySpeech.speech)
        XCTAssertTrue(emptySpeech.speech!.isEmpty)
        XCTAssertEqual(PersonaTranslation.sourceHash(for: source),
                       PersonaTranslation.sourceHash(for: emptySpeech))
        XCTAssertEqual(PersonaTranslation.sourceHash(for: emptySpeech),
                       Self.pythonReferencePersonaOnlyHash)
    }

    // MARK: - 不再截断（0.12）

    /// 超过旧 iOS 上限（tone 80 / habits 6 / avoid 5 / notes 1200）的源字段必须**完整**进哈希。
    ///
    /// 以前 Tone 截到 80 字、habits 截到 6 条、notes 截到 1200 字，于是「第 81 个字开始不同」
    /// 的两个人格会算出同一个 `sourceHash`（缓存互相冒充）；Android 不按这些长度截，
    /// 两端就对同一份人格各算一个值。这条用「前缀相同、尾部不同」的两份源夹住它。
    func testFieldsBeyondOldLimitsAreFullyHashed() throws {
        let baseTone = String(repeating: "冷", count: SpeechStyle.toneMax + 20)
        var a = PersonaTranslatableSource(name: "林深")
        a.speech = SpeechStyle(tone: baseTone + "甲")
        var b = PersonaTranslatableSource(name: "林深")
        b.speech = SpeechStyle(tone: baseTone + "乙")
        XCTAssertNotEqual(
            PersonaTranslation.sourceHash(for: a),
            PersonaTranslation.sourceHash(for: b),
            "tone 第 81 个字之后不同，哈希必须不同（以前会相同）"
        )

        let longNotes = String(repeating: "事", count: WorldPreset.maxNotesChars + 10)
        var c = PersonaTranslatableSource(name: "林深")
        c.world = WorldPreset(worldName: "临港新城", notes: longNotes + "甲")
        var d = PersonaTranslatableSource(name: "林深")
        d.world = WorldPreset(worldName: "临港新城", notes: longNotes + "乙")
        XCTAssertNotEqual(
            PersonaTranslation.sourceHash(for: c),
            PersonaTranslation.sourceHash(for: d),
            "notes 第 1201 个字之后不同，哈希必须不同（以前会相同）"
        )

        // 落库/解析一圈之后仍然是同一份哈希（JSON 不能把长字段吃掉）。
        let cached = PersonaTranslation(
            lang: "en", translatedAt: 1,
            sourceHash: PersonaTranslation.sourceHash(for: c),
            persona: PersonaTranslation.PersonaFields(name: "Lin"),
            world: c.world
        )
        let decoded = try XCTUnwrap(PersonaTranslation.decode(cached.jsonString()))
        XCTAssertEqual(decoded.world?.notes, longNotes + "甲", "翻译缓存里的 world 也不能截断")
        XCTAssertEqual(decoded.sourceHash, cached.sourceHash)
    }

    // MARK: - 与缓存命中的联动

    /// 哈希是缓存命中的唯一源判据：源字段不变 → 命中（零请求）；任一字段变 → 失效。
    func testCacheStaysValidUntilAnyTranslatedFieldChanges() {
        let source = fullSource()
        let cached = translation(of: source)
        XCTAssertTrue(cached.isValid(for: source, lang: "en"), "源没变必须命中")
        XCTAssertTrue(cached.isValid(for: source, lang: "en-US"), "语言短码归一化后仍算同一种语言")

        var notesChanged = source
        notesChanged.world?.notes = "换了一句背景。"
        XCTAssertFalse(cached.isValid(for: notesChanged, lang: "en"), "世界设定变了必须失效")

        var phrasesChanged = source
        phrasesChanged.speech?.phrases = ["知道了"]
        XCTAssertTrue(cached.isValid(for: phrasesChanged, lang: "en"), "只改常用语不该失效")

        XCTAssertFalse(cached.isValid(for: source, lang: "zh"), "语言不一致必须失效")
    }
}
