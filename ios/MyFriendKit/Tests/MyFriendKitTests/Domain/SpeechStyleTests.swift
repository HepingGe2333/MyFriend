import Foundation
import XCTest
@testable import MyFriendKit

/// 话风：宽容解析、提示词渲染（常用语最多 6 条）、本地兜底（§3.2）。
final class SpeechStyleTests: XCTestCase {

    private func specCase() throws -> [String: Any] {
        var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fm = FileManager.default
        for _ in 0..<16 {
            let candidate = url.appendingPathComponent("spec/persona-guidance/cases/speech-style-linshen.json")
            if fm.fileExists(atPath: candidate.path) {
                guard let data = try? Data(contentsOf: candidate),
                      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    throw XCTSkip("speech-style-linshen.json 读不出来")
                }
                return object
            }
            let parent = url.deletingLastPathComponent()
            if parent.path == url.path { break }
            url = parent
        }
        throw XCTSkip("找不到 spec/persona-guidance/cases/speech-style-linshen.json")
    }

    // MARK: - 解析

    func testDecodesSpecExpectation() throws {
        let root = try specCase()
        let expect = try XCTUnwrap(root["expect"] as? [String: Any])
        let data = try JSONSerialization.data(withJSONObject: expect)
        let text = try XCTUnwrap(String(data: data, encoding: .utf8))
        let style = try XCTUnwrap(SpeechStyle.decode(text))
        XCTAssertEqual(style.tone, expect["tone"] as? String)
        XCTAssertFalse(style.phrases.isEmpty)
        XCTAssertFalse(style.habits.isEmpty)
        XCTAssertFalse(style.avoid.isEmpty)
        // 推荐样本的 phrases 本来就不多（存储不设上限，这里只是样本自身的性质）。
        XCTAssertLessThanOrEqual(style.phrases.count, SpeechStyle.maxPhrases)
    }

    func testDecodeToleratesCodeFenceAndChatter() {
        let raw = """
        分析如下：
        ```json
        {"tone":"简短直接","phrases":["在忙，晚点说","嗯"],"habits":["句子短"],"avoid":["长篇大论"]}
        ```
        希望对你有帮助。
        """
        let style = SpeechStyle.parse(raw)
        XCTAssertEqual(style?.tone, "简短直接")
        XCTAssertEqual(style?.phrases, ["在忙，晚点说", "嗯"])
    }

    func testDecodeToleratesMissingAndWrongTypedFields() {
        XCTAssertNil(SpeechStyle.decode(nil))
        XCTAssertNil(SpeechStyle.decode("坏数据"))
        XCTAssertNil(SpeechStyle.decode("{}"), "全空等于没学过话风")
        let style = SpeechStyle.decode(#"{"tone":123,"phrases":"在忙,嗯","habits":5}"#)
        XCTAssertEqual(style?.tone, "123")
        XCTAssertEqual(style?.phrases, ["在忙", "嗯"], "字符串也能按分隔符拆")
        XCTAssertEqual(style?.habits, [])
    }

    /// 0.12：存储不按建议上限截断 —— 去重保序，但一条都不丢。
    func testPhrasesAreDeduplicatedButNotTruncated() {
        let style = SpeechStyle(phrases: ["嗯", "嗯", "行"] + (0..<20).map { "第\($0)句" })
        XCTAssertEqual(style.phrases.first, "嗯")
        XCTAssertEqual(style.phrases[1], "行")
        XCTAssertEqual(style.phrases.count, 22, "22 条（去重后）全部保留")
        XCTAssertEqual(Set(style.phrases).count, style.phrases.count)
        // 落库 JSON 也带着全部 22 条（以前这里只剩 12 条）。
        let decoded = SpeechStyle.decode(style.jsonString())
        XCTAssertEqual(decoded?.phrases, style.phrases)
    }

    /// 0.12：tone / habits / avoid 也不再按 80 / 6 / 5 截断（否则两端的 sourceHash 会漂）。
    func testLongToneAndListsAreNotTruncated() {
        let tone = String(repeating: "冷", count: SpeechStyle.toneMax + 40)
        let habits = (0..<(SpeechStyle.maxHabits + 4)).map { "习惯\($0)" }
        let avoid = (0..<(SpeechStyle.maxAvoid + 4)).map { "禁忌\($0)" }
        let style = SpeechStyle(tone: tone, habits: habits, avoid: avoid)
        XCTAssertEqual(style.tone, tone)
        XCTAssertEqual(style.habits, habits)
        XCTAssertEqual(style.avoid, avoid)
        let decoded = SpeechStyle.decode(style.jsonString())
        XCTAssertEqual(decoded?.tone, tone)
        XCTAssertEqual(decoded?.habits, habits)
        XCTAssertEqual(decoded?.avoid, avoid)
        // 渲染提示词仍然按 tone 的建议长度截断前 120 字（与 Android render 一致）。
        XCTAssertTrue(style.renderForPrompt().contains(String(repeating: "冷", count: 120)))
    }

    // MARK: - 渲染

    func testRenderForPromptQuotesAtMostSixPhrases() {
        let phrases = ["一", "二", "三", "四", "五", "六", "七", "八"].map { "\($0)句话" }
        let style = SpeechStyle(
            tone: "简短直接，偏冷淡务实",
            phrases: phrases,
            habits: ["句子很短"],
            avoid: ["长篇大论"]
        )
        let text = style.renderForPrompt()
        XCTAssertTrue(text.hasPrefix("# 你怎么说话"))
        XCTAssertTrue(text.contains("简短直接"))
        XCTAssertTrue(text.contains("句子很短"))
        XCTAssertTrue(text.contains("长篇大论"))
        for phrase in phrases.prefix(SpeechStyle.promptPhraseLimit) {
            XCTAssertTrue(text.contains("「\(phrase)」"), "前 6 条要在：\(phrase)")
        }
        for phrase in phrases.dropFirst(SpeechStyle.promptPhraseLimit) {
            XCTAssertFalse(text.contains("「\(phrase)」"), "第 7 条以后不该出现：\(phrase)")
        }
        // 常用语是参考不是模板（§1.1 第 4 条）。
        XCTAssertTrue(text.contains("最多带 1 处"))
        // 不写动作描写、不写括号旁白。
        XCTAssertTrue(text.contains("不要写动作描写"))
    }

    func testEmptyStyleRendersNothing() {
        XCTAssertEqual(SpeechStyle().renderForPrompt(), "")
        XCTAssertNil(SpeechStyle().jsonString())
    }

    func testJsonRoundTripKeepsSource() throws {
        let style = SpeechStyle(
            tone: "简短", phrases: ["嗯"], habits: ["少用感叹号"], avoid: ["长篇大论"],
            source: .heuristic
        )
        let json = try XCTUnwrap(style.jsonString())
        let decoded = try XCTUnwrap(SpeechStyle.decode(json))
        XCTAssertEqual(decoded, style)
        XCTAssertTrue(decoded.source.isHeuristic, "界面要能显示「本地粗略总结」")
    }

    // MARK: - 本地兜底（§3.2）

    func testHeuristicKeepsRepeatedShortLinesAsPhrases() {
        let transcript = """
        林深：在忙，晚点说
        林深：这个方案又被打回来了，烦
        林深：你先睡吧，不用等我
        林深：嗯
        林深：在忙，晚点说
        林深：嗯
        林深：别想太多
        林深：在忙，晚点说
        """
        let style = SpeechStyleSummarizer.heuristic(transcript)
        XCTAssertEqual(style.source, .heuristic)
        XCTAssertEqual(style.tone, SpeechStyleSummarizer.heuristicTone)
        // 频次 ≥2 的句子进 phrases；只出现一次的不进（不编造）。
        XCTAssertTrue(style.phrases.contains("在忙，晚点说"))
        XCTAssertFalse(style.phrases.contains("别想太多"), "只出现一次的句子不进 phrases（不编造）")
        XCTAssertFalse(style.phrases.contains("嗯"), "单字太短（§3.2 只取 2–20 字的短句）")
        XCTAssertEqual(style.avoid, SpeechStyleSummarizer.heuristicAvoid)
        XCTAssertEqual(style.habits.count, 2, "按平均句长与感叹号比例给 2 条")
        XCTAssertLessThanOrEqual(style.phrases.count, SpeechStyleSummarizer.heuristicPhraseLimit)
        // 昵称前缀被去掉。
        XCTAssertFalse(style.phrases.contains { $0.contains("林深") })
    }

    func testHeuristicHandlesEmptyAndNoisyInput() {
        // 空输入：没有可取的短句，但还是给出合法的兜底骨架（tone/habits/avoid），
        // 界面据此显示「本地粗略总结」而不是一片空白。
        let empty = SpeechStyleSummarizer.heuristic("")
        XCTAssertTrue(empty.phrases.isEmpty)
        XCTAssertEqual(empty.tone, SpeechStyleSummarizer.heuristicTone)
        XCTAssertEqual(empty.source, .heuristic)
        // 全是长句：没有 2–20 字的短句，phrases 为空但仍是合法的兜底结果。
        let long = String(repeating: "这是一句很长的聊天内容，超过二十个字了。", count: 3)
        let style = SpeechStyleSummarizer.heuristic(long)
        XCTAssertTrue(style.phrases.isEmpty)
        XCTAssertEqual(style.tone, SpeechStyleSummarizer.heuristicTone)
        XCTAssertEqual(style.source, .heuristic)
    }

    func testHeuristicToneHabitsFollowPunctuation() {
        let shouty = "别这样！\n快点！\n真的！\n好的！"
        let style = SpeechStyleSummarizer.heuristic(shouty)
        XCTAssertTrue(style.habits.contains("常用感叹号"))
    }
}
