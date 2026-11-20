import XCTest
@testable import YapKit

/// 「# 引导」段（0.15 profile，`spec/context-profile/README.md`）。
///
/// 三条契约：
/// 1. **选择规则**：心情偏负（valence < 0）或低唤醒（arousal < 2）→ g12；
///    其余 → g17；心情缺失 → g17。心情判定本身不在这里改。
/// 2. **文案逐字**：四个变量来自同一套模板（`eval/experiment.py` 的 `GUIDANCE_VARIANTS`），
///    不许各端各写一套。
/// 3. **位置**：`# 引导` 在心情之后、话风之前（由 `PromptSection.stateFirstOrder` 保证）。
final class PromptGuidanceTests: XCTestCase {

    private func mood(_ valence: Int, _ arousal: Int, _ dominance: Int = 3) -> MoodState {
        MoodState(
            label: "有点烦", intensity: 3,
            dimensions: MoodDimensions(valence: valence, arousal: arousal, dominance: dominance)
        )
    }

    // MARK: - 选择规则

    func testNegativeValencePicksDefensive() {
        XCTAssertEqual(PromptGuidance.pick(for: mood(-1, 4)), .defensive)
        XCTAssertEqual(PromptGuidance.pick(for: mood(-2, 5)), .defensive)
        // 很不悦但低唤醒也一样：不悦就够格了。
        XCTAssertEqual(PromptGuidance.pick(for: mood(-2, 0)), .defensive)
    }

    func testLowArousalPicksDefensive() {
        // 唤醒度 ≤ 2（没劲、累、困）→ 防备；哪怕心情是正的。
        XCTAssertEqual(PromptGuidance.pick(for: mood(0, 2)), .presentOnly)
        XCTAssertEqual(PromptGuidance.pick(for: mood(1, 1)), .defensive)
        XCTAssertEqual(PromptGuidance.pick(for: mood(2, 0)), .defensive)
    }

    func testPositiveValenceWithEnergyPicksPresentOnly() {
        XCTAssertEqual(PromptGuidance.pick(for: mood(0, 3)), .presentOnly)
        XCTAssertEqual(PromptGuidance.pick(for: mood(1, 3)), .presentOnly)
        XCTAssertEqual(PromptGuidance.pick(for: mood(2, 5)), .presentOnly)
    }

    /// 门槛常量就是规则本身：改门槛只需要动一处。
    ///
    /// 当前门槛（2）正好压在基准心情的唤醒度上，所以「平静」按字面规则走 g12；
    /// `spec/context-profile/README.md` 的表格说「平静用 g17」，与 §注入规则的
    /// 门槛写法冲突——这条测试把**当前实现**钉住，等两端确认后改一行即可（见 `PromptGuidance` 注释）。
    func testLowArousalThresholdIsTheOnlyKnob() {
        XCTAssertEqual(PromptGuidance.lowArousalThreshold, 2)
        XCTAssertEqual(PromptGuidance.pick(for: mood(0, PromptGuidance.lowArousalThreshold)), .presentOnly)
        XCTAssertEqual(PromptGuidance.pick(for: mood(0, PromptGuidance.lowArousalThreshold + 1)), .presentOnly)
    }

    func testMissingMoodFallsBackToPresentOnly() {
        XCTAssertEqual(PromptGuidance.pick(for: nil), .presentOnly)
        XCTAssertEqual(PromptGuidance.pick(for: MoodState()), .presentOnly)
        XCTAssertEqual(PromptGuidance.pick(for: MoodState(label: "   ")), .presentOnly)
    }

    /// 老库只有 label / intensity：用词表推出来的三维照样能选（与心情段同源）。
    func testLegacyMoodWithoutDimensionsStillChooses() {
        // 「低落」在词表里是负向：推出来的三维 valence < 0。
        let legacy = MoodState(label: "低落", intensity: 3)
        XCTAssertEqual(PromptGuidance.pick(for: legacy), .defensive)
    }

    /// 心情判定不被改动：选择只读状态，不写回、不重新判。
    func testSelectionDoesNotMutateMood() {
        var state = mood(-1, 1)
        let before = state
        _ = PromptGuidance.pick(for: state)
        _ = PromptGuidance.section(for: state)
        XCTAssertEqual(state, before)
        state.label = "平静"
        XCTAssertEqual(state.label, "平静")
    }

    // MARK: - 文案

    func testDefensiveSectionMatchesApprovedWording() {
        let text = PromptGuidance.section(.defensive)
        XCTAssertEqual(text, """
        # 引导
        这一轮：有点防备，不轻易示弱
        - 用短句挡一下
        - 被问急了才多露半句
        - 不要掏心掏肺。
        """)
    }

    func testPresentOnlySectionMatchesApprovedWording() {
        let text = PromptGuidance.section(.presentOnly)
        XCTAssertEqual(text, """
        # 引导
        这一轮：只聊此刻，不翻旧账
        - 回应现在这句话
        - 不延伸过去
        - 不要反问。
        """)
    }

    /// 心情缺失时注入的就是 g17 那一段（不是空串）。
    func testSectionForMissingMoodIsPresentOnly() {
        XCTAssertEqual(PromptGuidance.section(for: nil), PromptGuidance.section(.presentOnly))
        XCTAssertTrue(PromptGuidance.section(for: nil).contains("只聊此刻"))
    }

    func testSectionFollowsMood() {
        XCTAssertEqual(PromptGuidance.section(for: mood(-2, 3)), PromptGuidance.section(.defensive))
        XCTAssertEqual(PromptGuidance.section(for: mood(2, 4)), PromptGuidance.section(.presentOnly))
    }

    // MARK: - 中英文同步

    /// 英文人格也必须有引导段（否则英文人格少一整段行为指令）。
    func testEnglishSectionsAreEnglishAndMirrorChinese() {
        let defensive = PromptGuidance.section(.defensive, language: .en)
        XCTAssertTrue(defensive.hasPrefix("# Guidance"))
        XCTAssertFalse(defensive.contains("这一轮"))
        XCTAssertFalse(defensive.contains("# 引导"))
        XCTAssertEqual(defensive.filter { $0 == "\n" }.count, 4, "标题 + 三行，形状与中文一致")

        let presentOnly = PromptGuidance.section(.presentOnly, language: .en)
        XCTAssertTrue(presentOnly.hasPrefix("# Guidance"))
        XCTAssertTrue(presentOnly.contains("right now"), presentOnly) // 「只聊此刻」的英文化
        XCTAssertNotEqual(defensive, presentOnly, "两个变体的英文不能一样")
    }

    func testEnglishAndChineseShareTheSameTemplateShape() {
        for guidance in PromptGuidance.allCases {
            let zh = PromptGuidance.section(guidance, language: .zh)
                .split(separator: "\n").map(String.init)
            let en = PromptGuidance.section(guidance, language: .en)
                .split(separator: "\n").map(String.init)
            XCTAssertEqual(zh.count, 5, "标题 + 这一轮 + 三条：\(zh)")
            XCTAssertEqual(en.count, 5, "标题 + This turn + 三条：\(en)")
            XCTAssertTrue(zh[1].hasPrefix("这一轮："))
            XCTAssertTrue(en[1].hasPrefix("This turn: "))
            for index in 2..<5 {
                XCTAssertTrue(zh[index].hasPrefix("- "), zh[index])
                XCTAssertTrue(en[index].hasPrefix("- "), en[index])
            }
        }
    }

    // MARK: - 位置（心情之后、话风之前）

    func testGuidanceSitsAfterMoodAndBeforeVoiceInSystemPrompt() throws {
        let system = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: "# 你是谁\n你是林深。",
            personaName: "林深",
            speechStyleBlock: "\n\n# 你怎么说话\n- 句子短。",
            moodBlock: MoodPrompt.line(mood(-1, 1)),
            guidanceBlock: PromptGuidance.section(.defensive)
        ))
        let mood = try XCTUnwrap(system.range(of: MoodPrompt.sectionTitle))
        let guidance = try XCTUnwrap(system.range(of: PromptGuidance.sectionTitle))
        let voice = try XCTUnwrap(system.range(of: PersonaPromptScaffold.voiceSection))
        XCTAssertLessThan(mood.lowerBound, guidance.lowerBound, "引导在心情之后")
        XCTAssertLessThan(guidance.lowerBound, voice.lowerBound, "引导在话风之前")
    }

    /// 引导段属于「状态组」：比人设（`# 你是谁`）更早，紧跟在状态之后。
    func testGuidanceBelongsToStateGroup() {
        let order = PromptSection.stateFirstOrder
        let state = try? XCTUnwrap(order.firstIndex(of: .state))
        let guidance = try? XCTUnwrap(order.firstIndex(of: .guidance))
        let persona = try? XCTUnwrap(order.firstIndex(of: .persona))
        guard let state, let guidance, let persona else { return XCTFail("段枚举缺项") }
        XCTAssertEqual(guidance, state + 1)
        XCTAssertLessThan(guidance, persona)
    }
}
