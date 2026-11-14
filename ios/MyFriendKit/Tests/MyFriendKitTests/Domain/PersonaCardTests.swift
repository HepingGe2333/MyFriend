import Foundation
import XCTest
@testable import MyFriendKit

/// 人设卡的纯逻辑：编解码、清洗上限、稳定策略、按预算渲染、旧提示词解析、模型补丁。
///
/// 这些用例对着 Android 的 `PersonaCard.kt` 逐条钉：字段名、上限、裁剪优先级、兜底文案
/// 任何一端漂了都应该在这里先红。坏输入（坏 JSON / 类型不符 / 空串 / 超长）是重点，
/// 因为人设卡是「模型可以自己在对话里改」的结构，防御性比功能更重要。
final class PersonaCardTests: XCTestCase {

    // MARK: - 编解码

    func testEncodeDecodeRoundTrip() {
        let card = PersonaCard(
            identity: PersonaIdentity(name: "林岸", age: "二十多岁", role: "开旧书店的人", relation: "认识很多年的朋友"),
            voice: PersonaVoice(
                pace: "慢，偶尔停下来", sentence: "短句，一次说一两句",
                catchphrases: ["嗯", "那就这样"], address: "小岸", quirks: "少用感叹号"
            ),
            traits: [PersonaTrait(name: "嘴硬心软", strength: 0.8), PersonaTrait(name: "慢热")],
            values: ["真诚", "不敷衍"],
            boundaries: ["不假装记得没说过的事"],
            life: PersonaLife(schedule: "凌晨两点睡，中午起", doing: "整理旧书", caresAbout: "你最近睡得好不好"),
            bond: "他记得你怕冷。"
        )
        XCTAssertEqual(PersonaCardCodec.decode(PersonaCardCodec.encode(card)), card)
    }

    func testEmptyCardEncodesToEmptyObject() {
        XCTAssertEqual(PersonaCardCodec.encode(PersonaCard()), "{}")
    }

    func testDefaultFieldsAreNotWritten() {
        // encodeDefaults=false：空字符串 / 空数组 / strength 0.5 都不写出。
        let card = PersonaCard(
            identity: PersonaIdentity(name: "林岸"),
            traits: [PersonaTrait(name: "嘴硬心软", strength: 0.5)]
        )
        XCTAssertEqual(
            PersonaCardCodec.encode(card),
            #"{"identity":{"name":"林岸"},"traits":[{"name":"嘴硬心软"}]}"#
        )
        // 非默认 strength 要写出。
        let strong = PersonaCard(traits: [PersonaTrait(name: "嘴硬心软", strength: 0.3)])
        XCTAssertTrue(PersonaCardCodec.encode(strong).contains("\"strength\""))
    }

    func testEncodeEscapesQuotesAndNewlines() {
        let encoded = PersonaCardCodec.encode(PersonaCard(bond: "他说「好」。\n然后走了。"))
        XCTAssertTrue(encoded.contains(#"\n"#), encoded)
        // 解码会做「折成一行」的清洗，换行变空格（与 Android 的 cleaned 一致）。
        XCTAssertEqual(PersonaCardCodec.decode(encoded).bond, "他说「好」。 然后走了。")
    }

    func testDecodeToleratesBadInput() {
        XCTAssertTrue(PersonaCardCodec.decode(nil).isEmpty)
        XCTAssertTrue(PersonaCardCodec.decode("").isEmpty)
        XCTAssertTrue(PersonaCardCodec.decode("   ").isEmpty)
        XCTAssertTrue(PersonaCardCodec.decode("不是 JSON").isEmpty)
        XCTAssertTrue(PersonaCardCodec.decode("[1,2,3]").isEmpty)
        // 类型不符：Android 是整个 decode 抛异常 -> 空卡，这里保持一致。
        XCTAssertTrue(PersonaCardCodec.decode(#"{"identity":"林岸"}"#).isEmpty)
        XCTAssertTrue(PersonaCardCodec.decode(#"{"identity":{"name":1}}"#).isEmpty)
        XCTAssertTrue(PersonaCardCodec.decode(#"{"traits":"嘴硬"}"#).isEmpty)
        // 未知字段忽略；null 等价于「字段没出现」。
        XCTAssertEqual(PersonaCardCodec.decode(#"{"foo":1,"bond":"嗯","voice":null}"#).bond, "嗯")
    }

    func testDecodeSanitizesLimits() {
        let longName = String(repeating: "长", count: 50)
        let longRole = String(repeating: "职", count: 100)
        let longSentence = String(repeating: "句", count: 300)
        let raw = """
        {
          "identity": {"name": "\(longName)", "role": "\(longRole)", "age": "\(longName)"},
          "voice": {"sentence": "\(longSentence)", "catchphrases": ["  ", "嗯"]},
          "traits": [{"name": "  "}, {"name": "倔", "strength": 3}, {"name": "软", "strength": -1}, {"name": "\(longName)"}],
          "values": ["一", "二", "三", "四", "五", "六", "七", "八", "九"],
          "bond": "\(String(repeating: "啊", count: 2500))"
        }
        """
        let card = PersonaCardCodec.decode(raw)
        XCTAssertEqual(card.identity.name.count, PersonaCardLimits.nameMax)
        XCTAssertEqual(card.identity.age.count, PersonaCardLimits.nameMax)
        XCTAssertEqual(card.identity.role.count, PersonaCardLimits.roleMax)
        XCTAssertEqual(card.voice.sentence.count, PersonaCardLimits.fieldMax)
        // 空项被丢掉，strength 夹到 0...1。
        XCTAssertEqual(card.voice.catchphrases, ["嗯"])
        XCTAssertEqual(card.traits.count, 3)
        XCTAssertEqual(card.traits.map(\.name), ["倔", "软", String(longName.prefix(PersonaCardLimits.nameMax - 1)) + "…"])
        XCTAssertEqual(card.traits[0].strength, 1, accuracy: 0.0001)
        XCTAssertEqual(card.traits[1].strength, 0, accuracy: 0.0001)
        XCTAssertEqual(card.values.count, PersonaCardLimits.maxListItems)
        XCTAssertEqual(card.bond.count, PersonaCardLimits.bondMax)
        XCTAssertFalse(card.isEmpty)
    }

    func testDecodeTruncatesLongListItems() {
        let raw = #"{"values":["\#(String(repeating: "值", count: 80))"]}"#
        XCTAssertEqual(PersonaCardCodec.decode(raw).values.first?.count, PersonaCardLimits.listItemMax)
    }

    func testIsEmpty() {
        XCTAssertTrue(PersonaCard().isEmpty)
        XCTAssertTrue(PersonaCard(voice: PersonaVoice(catchphrases: [" ", ""])).isEmpty)
        XCTAssertFalse(PersonaCard(traits: [PersonaTrait(name: "倔")]).isEmpty)
        XCTAssertFalse(PersonaCard(bond: "嗯").isEmpty)
    }

    // MARK: - 稳定策略

    func testStableStrategyFallbackForEmptyCard() {
        XCTAssertEqual(PersonaCardPrompt.stableStrategy(PersonaCard()), "用自然、简短、像真人发消息的方式说话。")
        // 只有关系、没有名字也没有身份时同样兜底（Android: name = name.ifBlank { role }）。
        let card = PersonaCard(identity: PersonaIdentity(relation: "朋友"))
        XCTAssertEqual(PersonaCardPrompt.stableStrategy(card), "用自然、简短、像真人发消息的方式说话。")
    }

    func testStableStrategyIncludesNameRoleRelationAndVoice() {
        let card = PersonaCard(
            identity: PersonaIdentity(name: "林岸", role: "开旧书店的人", relation: "朋友"),
            voice: PersonaVoice(pace: "语速偏慢", address: "小岸"),
            traits: [PersonaTrait(name: "嘴硬心软")]
        )
        let strategy = PersonaCardPrompt.stableStrategy(card)
        XCTAssertTrue(strategy.hasPrefix("你是林岸"), strategy)
        XCTAssertTrue(strategy.contains("开旧书店的人"), strategy)
        XCTAssertTrue(strategy.contains("对方的"), strategy)
        XCTAssertTrue(strategy.contains("称呼对方「小岸」"), strategy)
        // 旧提示词里「语速偏慢」要剥掉主语再拼，不能出现「说话你说话」。
        XCTAssertTrue(strategy.contains("说话偏慢"), strategy)
        XCTAssertFalse(strategy.contains("说话语速"), strategy)
        XCTAssertTrue(strategy.contains("嘴硬心软"), strategy)
        XCTAssertTrue(strategy.hasSuffix("先回应再展开，别解释自己是 AI。"), strategy)
        XCTAssertLessThanOrEqual(strategy.count, 80)
    }

    func testStableStrategyStripsMainSubjectWordPrefix() {
        let card = PersonaCard(identity: PersonaIdentity(name: "林岸"), voice: PersonaVoice(pace: "你说话从容、克制"))
        let strategy = PersonaCardPrompt.stableStrategy(card)
        XCTAssertTrue(strategy.contains("说话从容、克制"), strategy)
        XCTAssertFalse(strategy.contains("说话你说话"), strategy)
    }

    func testStableStrategyNeverExceedsCap() {
        let card = PersonaCard(
            identity: PersonaIdentity(
                name: String(repeating: "林", count: 40),
                role: String(repeating: "职", count: 80),
                relation: String(repeating: "关", count: 40)
            ),
            voice: PersonaVoice(pace: String(repeating: "慢", count: 200),
                               address: String(repeating: "称", count: 40)),
            traits: [PersonaTrait(name: String(repeating: "倔", count: 40))]
        )
        let strategy = PersonaCardPrompt.stableStrategy(card)
        XCTAssertLessThanOrEqual(strategy.count, 80)
        XCTAssertTrue(strategy.hasPrefix("你是"))
    }

    // MARK: - 按预算渲染

    private func bigCard() -> PersonaCard {
        PersonaCard(
            identity: PersonaIdentity(name: "林岸", age: "二十多岁", role: "开旧书店的人", relation: "认识很多年的朋友"),
            voice: PersonaVoice(
                pace: "慢，偶尔停下来", sentence: "短句，一次说一两句",
                catchphrases: ["嗯", "那就这样"], address: "小岸", quirks: "少用感叹号，爱用省略号"
            ),
            traits: [
                PersonaTrait(name: "嘴硬心软", strength: 0.8), PersonaTrait(name: "慢热", strength: 0.6),
                PersonaTrait(name: "话少", strength: 0.7), PersonaTrait(name: "怕冷", strength: 0.4)
            ],
            values: ["真诚", "不敷衍", "把话说清楚"],
            boundaries: ["不假装记得没说过的事", "不评判你的选择", "不拿别人的事开玩笑"],
            life: PersonaLife(
                schedule: String(repeating: "五点起床，", count: 20),
                doing: String(repeating: "整理旧书，", count: 20),
                caresAbout: String(repeating: "你最近睡得好不好，", count: 20)
            ),
            bond: String(repeating: "他记得你说过的话，也记得你怕冷。", count: 30)
        )
    }

    func testRenderContextKeepsTwoRequiredSections() {
        let card = bigCard()
        let lean = PersonaCardPrompt.renderContext(card, budget: .lean)
        XCTAssertLessThanOrEqual(lean.count, PromptBudget.lean.maxChars)
        XCTAssertTrue(lean.contains("### 你是谁"), lean)
        XCTAssertTrue(lean.contains("### 说话方式"), lean)
        // 身份与声音各至少留一行（第一行受保护）。
        XCTAssertTrue(lean.contains("- 名字：林岸"), lean)
        XCTAssertTrue(lean.contains("- 语速：慢，偶尔停下来"), lean)
        // 生活细节最先掉。
        XCTAssertFalse(lean.contains("### 生活"), lean)
        XCTAssertFalse(lean.contains("### 你们之间"), lean)
    }

    func testRenderContextGrowsWithBudget() {
        let card = bigCard()
        let lean = PersonaCardPrompt.renderContext(card, budget: .lean)
        let normal = PersonaCardPrompt.renderContext(card, budget: .normal)
        let rich = PersonaCardPrompt.renderContext(card, budget: .rich)
        XCTAssertLessThanOrEqual(lean.count, normal.count)
        XCTAssertLessThanOrEqual(normal.count, rich.count)
        XCTAssertTrue(rich.contains("### 生活"), rich)
        XCTAssertTrue(rich.contains("### 你们之间"), rich)
        XCTAssertTrue(rich.contains("- 作息："), rich)
        // 段落标题顺序固定。
        let identityIndex = rich.range(of: "### 你是谁")
        let voiceIndex = rich.range(of: "### 说话方式")
        XCTAssertNotNil(identityIndex)
        XCTAssertNotNil(voiceIndex)
        if let identityIndex, let voiceIndex {
            XCTAssertLessThan(identityIndex.lowerBound, voiceIndex.lowerBound)
        }
    }

    func testRenderContextEmptyCardUsesPlaceholders() {
        let rendered = PersonaCardPrompt.renderContext(PersonaCard())
        XCTAssertTrue(rendered.contains("### 你是谁\n（还没有设定）"), rendered)
        XCTAssertTrue(rendered.contains("### 说话方式\n（还没有设定）"), rendered)
        XCTAssertFalse(rendered.contains("### 性格"))
    }

    func testRenderContextExtremeInputStaysWithinBudget() {
        // 极端输入（超长自由文本）也只输出预算内的内容。
        let card = PersonaCard(bond: String(repeating: "啊", count: 900))
        let lean = PersonaCardPrompt.renderContext(card, budget: .lean)
        XCTAssertLessThanOrEqual(lean.count, PromptBudget.lean.maxChars)
        XCTAssertTrue(lean.contains("### 你是谁"))
        XCTAssertTrue(lean.contains("### 说话方式"))
    }

    // MARK: - 旧提示词解析

    func testFromLegacyPromptParsesIdentityVoiceAndBoundaries() {
        let prompt = """
        你是顾言，一个很安静的朋友。
        不要假装记得没说过的事。
        说话声音轻，语速慢。
        """
        let card = PersonaCardPrompt.fromLegacyPrompt(prompt)
        XCTAssertEqual(card.identity.name, "顾言")
        XCTAssertEqual(card.identity.role, "一个很安静的朋友")
        XCTAssertEqual(card.identity.relation, "朋友")
        XCTAssertEqual(card.boundaries, ["不要假装记得没说过的事。"])
        XCTAssertEqual(card.voice.pace, "说话声音轻，语速慢。")
        // bond 是自由文本段：整段原文都保留下来，只是被折成一行（cleaned 会把换行变空格）。
        XCTAssertEqual(card.bond, "你是顾言，一个很安静的朋友。 不要假装记得没说过的事。 说话声音轻，语速慢。")
        XCTAssertFalse(card.isEmpty)
    }

    func testFromLegacyPromptKeepsRawAndDefaultTraitWhenNothingRecognized() {
        let prompt = "今天海面很平静。"
        let card = PersonaCardPrompt.fromLegacyPrompt(prompt)
        XCTAssertEqual(card.bond, prompt)
        XCTAssertEqual(card.traits, [PersonaTrait(name: "保持原文里的自我设定", strength: 0.6)])
        XCTAssertFalse(card.isEmpty)
    }

    func testFromLegacyPromptEmptyPromptIsEmptyCard() {
        XCTAssertTrue(PersonaCardPrompt.fromLegacyPrompt("").isEmpty)
        XCTAssertTrue(PersonaCardPrompt.fromLegacyPrompt("  \n ").isEmpty)
    }

    func testFromLegacyPromptStripsBulletsAndHeadings() {
        let prompt = """
        # 表达方式
        - 用短句，偶尔停顿。
        ## 边界
        1. 不要替对方做决定。
        """
        let card = PersonaCardPrompt.fromLegacyPrompt(prompt)
        // 位于「表达方式」小节下的每一行都算声音描述。
        XCTAssertEqual(card.voice.pace, "用短句，偶尔停顿。")
        XCTAssertEqual(card.boundaries, ["不要替对方做决定。"])
    }

    func testFromLegacyPromptTruncatesLongBond() {
        let prompt = String(repeating: "啊", count: 2500)
        let card = PersonaCardPrompt.fromLegacyPrompt(prompt)
        XCTAssertEqual(card.bond.count, PersonaCardLimits.bondMax)
    }

    func testFromLegacyPromptParsesNameFromNiJiao() {
        let card = PersonaCardPrompt.fromLegacyPrompt("你叫阿岸，住在海边。")
        XCTAssertEqual(card.identity.name, "阿岸")
        XCTAssertEqual(card.identity.role, "住在海边")
    }

    // MARK: - 模型补丁

    func testPatchApplyOverwritesOnlyMentionedFields() {
        let base = PersonaCard(
            identity: PersonaIdentity(name: "林岸", role: "开旧书店的人"),
            values: ["真诚"],
            bond: "他记得你怕冷。"
        )
        let patch = """
        {"identity":{"name":"阿岸"},"traits":[{"name":"嘴硬","strength":0.8}],
         "values":[],"bond":"他记得你怕冷，也记得你怕吵。"}
        """
        let after = PersonaCardPatch.apply(base, patchJson: patch)
        XCTAssertEqual(after.identity.name, "阿岸")
        XCTAssertEqual(after.identity.role, "开旧书店的人")
        XCTAssertEqual(after.traits, [PersonaTrait(name: "嘴硬", strength: 0.8)])
        // 列表字段是「替换」语义，空数组就是清空。
        XCTAssertEqual(after.values, [])
        XCTAssertEqual(after.bond, "他记得你怕冷，也记得你怕吵。")
    }

    func testPatchApplyIgnoresBadInput() {
        let base = PersonaCard(identity: PersonaIdentity(name: "林岸"), bond: "嗯")
        XCTAssertEqual(PersonaCardPatch.apply(base, patchJson: "不是 JSON"), base)
        XCTAssertEqual(PersonaCardPatch.apply(base, patchJson: "[]"), base)
        XCTAssertEqual(PersonaCardPatch.apply(base, patchJson: "{}"), base)
        // 类型不符：文本字段忽略，数组里非字符串元素被丢掉。
        let after = PersonaCardPatch.apply(
            base,
            patchJson: #"{"identity":"阿岸","voice":{"pace":1},"values":[1,"真诚"],"bond":2}"#
        )
        XCTAssertEqual(after.identity.name, "林岸")
        XCTAssertEqual(after.voice.pace, "")
        XCTAssertEqual(after.values, ["真诚"])
        XCTAssertEqual(after.bond, "嗯")
    }

    func testPatchApplySanitizesStrength() {
        let base = PersonaCard(identity: PersonaIdentity(name: "林岸"))
        let after = PersonaCardPatch.apply(base, patchJson: #"{"traits":[{"name":"倔","strength":5}]}"#)
        XCTAssertEqual(after.traits.first?.strength, 1)
    }

    func testSummarize() {
        XCTAssertEqual(
            PersonaCardPatch.summarize(#"{"identity":{"name":"阿岸"},"voice":{"address":"小岸"}}"#),
            "身份·名字、说话习惯·称呼"
        )
        XCTAssertEqual(
            PersonaCardPatch.summarize(#"{"traits":[{"name":"嘴硬"},{"name":"心软"}]}"#),
            "性格·嘴硬、心软"
        )
        XCTAssertEqual(PersonaCardPatch.summarize(#"{"values":["真诚"]}"#), "在意的事")
        XCTAssertEqual(PersonaCardPatch.summarize(#"{"life":{"doing":"整理旧书"}}"#), "生活")
        XCTAssertEqual(PersonaCardPatch.summarize("坏 JSON"), "调整了人设")
        XCTAssertEqual(PersonaCardPatch.summarize("{}"), "调整了人设")
        // 一行摘要最多 60 字（超了截断并补省略号）。
        let longName = String(repeating: "超", count: 30)
        let names = (0..<6).map { _ in #"{"name":"\#(longName)"}"# }.joined(separator: ",")
        let summary = PersonaCardPatch.summarize(#"{"traits":[\#(names)]}"#)
        XCTAssertEqual(summary.count, 60)
        XCTAssertTrue(summary.hasPrefix("性格·"))
        XCTAssertTrue(summary.hasSuffix("…"))
    }
}
