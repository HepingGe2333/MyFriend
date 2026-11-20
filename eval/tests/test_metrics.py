"""离线单测：只验证指标计算，不联网、不依赖 pytest。"""

from __future__ import annotations

import unittest

from eval import metrics
from eval.metrics import analyze, aggregate, dimension_scores, extract_tool_calls


def make_scenario(expectations: dict | None = None, persona: dict | None = None) -> dict:
    base_expectations = {"language": "zh", "max_chars": 100, "max_questions": 1}
    base_expectations.update(expectations or {})
    return {
        "id": "unit-test",
        "category": "daily_chat",
        "title": "单测场景",
        "persona": persona
        or {
            "name": "林深",
            "speech": {"phrases": ["在忙，晚点说", "别想太多"]},
        },
        "messages": ["在吗"],
        "expectations": base_expectations,
    }


def run(reply: str, expectations: dict | None = None, persona: dict | None = None):
    return analyze(make_scenario(expectations, persona), reply)


TOOL = '```yap-tool\n{"name":"%s","arguments":%s}\n```'


class ExtractToolTest(unittest.TestCase):
    def test_strips_single_block_and_keeps_text(self):
        visible, calls = extract_tool_calls('好的\n```yap-tool\n{"name":"web_search","arguments":{"query":"天气"}}\n```')
        self.assertEqual(visible, "好的")
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0].name, "web_search")
        self.assertEqual(calls[0].arguments["query"], "天气")

    def test_multiple_blocks_and_malformed(self):
        raw = (
            '```yap-tool\n{"name":"think","arguments":{}}\n```\n'
            '中间一句\n'
            '```yap-tool\nnot json\n```\n'
            '```yap-wake\n{"kind":"daily","time":"22:30"}\n```'
        )
        visible, calls = extract_tool_calls(raw)
        self.assertEqual(visible, "中间一句")
        self.assertEqual(calls[0].name, "think")
        self.assertEqual(calls[1].name, "")
        self.assertIn("合法 JSON", calls[1].error)

    def test_no_blocks_returns_trimmed_text(self):
        visible, calls = extract_tool_calls("  就这样  ")
        self.assertEqual(visible, "就这样")
        self.assertEqual(calls, [])


class LengthQuestionTest(unittest.TestCase):
    def test_length_within_and_over(self):
        within = run("一二三四五六七八九十", {"max_chars": 10})
        self.assertEqual(within.metrics["length"].score, 100)
        over = run("一二三四五六七八九十十一", {"max_chars": 10})
        self.assertLess(over.metrics["length"].score, 60)
        self.assertIn("over_by", over.metrics["length"].detail)

    def test_min_chars(self):
        short = run("嗯。", {"min_chars": 10})
        self.assertLess(short.metrics["length"].score, 60)

    def test_length_per_bubble_not_total(self):
        result = run("第一段话。\n\n第二段话。", {"max_chars": 10})
        self.assertEqual(result.metrics["length"].score, 100)
        self.assertEqual(result.metrics["length"].detail["segments"], 2)
        self.assertEqual(result.metrics["length"].detail["max_segment_chars"], 5)

    def test_question_not_double_counted(self):
        result = run("周末有空吗？", {"max_questions": 1})
        self.assertEqual(result.metrics["question"].detail["questions"], 1)
        self.assertEqual(result.metrics["question"].score, 100)

    def test_question_over_limit(self):
        result = run("在吗？在干嘛？", {"max_questions": 1})
        self.assertEqual(result.metrics["question"].detail["questions"], 2)
        self.assertLess(result.metrics["question"].score, 100)

    def test_question_without_mark(self):
        result = run("要不要一起吃饭", {"max_questions": 0})
        self.assertEqual(result.metrics["question"].detail["questions"], 1)


class AntiAiTest(unittest.TestCase):
    def test_ai_flavor_hits(self):
        result = run("首先，你要先睡。希望对你有帮助。", {"max_chars": 100})
        hits = result.metrics["ai_flavor"].detail["hits"]
        self.assertIn("首先", hits)
        self.assertIn("希望对你有帮助", hits)
        self.assertLessEqual(result.metrics["ai_flavor"].score, 64)

    def test_sermon_hits(self):
        result = run("建议你早点睡，你应该照顾好自己。")
        hits = result.metrics["sermon"].detail["hits"]
        self.assertIn("建议你", hits)
        self.assertIn("你应该", hits)
        self.assertLess(result.metrics["sermon"].score, 60)

    def test_clean_reply_scores_full(self):
        result = run("刚忙完。你先睡。")
        self.assertEqual(result.metrics["ai_flavor"].score, 100)
        self.assertEqual(result.metrics["sermon"].score, 100)


class HumanVoiceTest(unittest.TestCase):
    def test_repetition(self):
        result = run("我在我在我在我在", {"max_chars": 100})
        self.assertLess(result.metrics["repetition"].score, 60)
        self.assertGreater(result.metrics["repetition"].detail["repeated_3gram_ratio"], 0.1)

    def test_catchphrase_overused(self):
        result = run("在忙，晚点说。在忙，晚点说。")
        self.assertEqual(result.metrics["catchphrase"].detail["total"], 2)
        self.assertLess(result.metrics["catchphrase"].score, 60)

    def test_catchphrase_once_is_fine(self):
        result = run("在忙，晚点说。")
        self.assertEqual(result.metrics["catchphrase"].score, 100)

    def test_third_person(self):
        result = run("林深觉得今天有点累。")
        self.assertGreaterEqual(len(result.metrics["third_person"].detail["hits"]), 1)
        self.assertLess(result.metrics["third_person"].score, 60)
        self.assertIn("third_person", result.hard_failures)


class FactTest(unittest.TestCase):
    def test_fact_missing(self):
        result = run("它又打翻杯子了。", {"facts": [{"id": "cat", "must_include": ["年糕"]}]})
        self.assertEqual(result.metrics["facts"].score, 0)
        self.assertIn("facts", result.hard_failures)

    def test_fact_wrong_name(self):
        result = run(
            "汤圆又打翻杯子了。",
            {"facts": [{"id": "cat", "must_include": ["年糕"], "must_not_include": ["汤圆"]}]},
        )
        self.assertEqual(result.metrics["facts"].score, 0)

    def test_fact_uncertainty(self):
        result = run("这个我不清楚，得查一下。", {"facts": [{"id": "gold", "requires_uncertainty": True}]})
        self.assertEqual(result.metrics["facts"].score, 100)

    def test_fact_forbidden_number(self):
        result = run(
            "大概 620 元一克。",
            {"facts": [{"id": "gold", "forbidden_patterns": [r"\d{3,4}\s*元"]}]},
        )
        self.assertEqual(result.metrics["facts"].score, 0)


class ToolTest(unittest.TestCase):
    def test_required_tool_missing_and_pretending(self):
        result = run("我查了一下，就是那样。", {"required_tools": ["web_search"]})
        tool = result.metrics["tool"]
        self.assertEqual(tool.score, 0)
        self.assertTrue(any("声称查过" in problem for problem in tool.detail["problems"]))

    def test_required_tool_present(self):
        result = run(TOOL % ("remember", '{"content":"生日 3 月 14 日"}'), {"required_tools": ["remember"]})
        self.assertEqual(result.metrics["tool"].score, 100)
        self.assertFalse(result.metrics["length"].applicable)
        self.assertFalse(result.metrics["ai_flavor"].applicable)
        self.assertEqual(result.score, 100)
        self.assertTrue(result.passed)

    def test_forbidden_tool_used(self):
        result = run(TOOL % ("think", '{"question":"她为什么累"}') + "\n先睡吧。", {"forbidden_tools": ["think"]})
        self.assertEqual(result.metrics["tool"].score, 0)
        self.assertIn("tool", result.hard_failures)

    def test_tool_or_uncertain(self):
        self.assertEqual(run("我不清楚。", {"tool_or_uncertain": True}).metrics["tool"].score, 100)
        self.assertEqual(
            run(TOOL % ("web_search", '{"query":"天气"}'), {"tool_or_uncertain": True}).metrics["tool"].score,
            100,
        )
        self.assertEqual(run("明天晴，25 度。", {"tool_or_uncertain": True}).metrics["tool"].score, 0)


class SilenceTest(unittest.TestCase):
    def test_expect_silence_violated(self):
        result = run("在的。", {"expect_silence": True})
        self.assertEqual(result.metrics["silence"].score, 0)
        self.assertIn("silence", result.hard_failures)

    def test_expect_silence_ok(self):
        result = run("", {"expect_silence": True})
        self.assertEqual(result.metrics["silence"].score, 100)
        self.assertTrue(result.passed)

    def test_allow_silence(self):
        result = run("", {"allow_silence": True, "max_chars": 30})
        self.assertTrue(result.passed)

    def test_forbid_silence(self):
        result = run("", {"forbid_silence": True})
        self.assertEqual(result.metrics["silence"].score, 0)
        self.assertFalse(result.passed)


class LanguageTest(unittest.TestCase):
    def test_english_expected(self):
        self.assertEqual(run("I'm here. What happened?", {"language": "en"}).metrics["language"].score, 100)
        mismatch = run("我在。", {"language": "en"})
        self.assertEqual(mismatch.metrics["language"].score, 0)
        self.assertIn("language", mismatch.hard_failures)

    def test_chinese_default(self):
        self.assertEqual(run("刚忙完。").metrics["language"].score, 100)

    def test_match_user(self):
        scenario = make_scenario({"language": "match_user"})
        scenario["messages"] = ["Hey, are you around?"]
        result = analyze(scenario, "I'm here.")
        self.assertEqual(result.metrics["language"].score, 100)


class FormatTest(unittest.TestCase):
    def test_list_penalty(self):
        result = run("- 先睡\n- 再说")
        self.assertLess(result.metrics["format"].score, 100)
        self.assertTrue(result.metrics["format"].detail["list_lines"])

    def test_narration_is_hard_failure(self):
        result = run("（叹了口气）先睡吧。")
        self.assertTrue(result.metrics["format"].hard_fail)
        self.assertIn("format", result.hard_failures)

    def test_markdown_emphasis_is_not_narration(self):
        result = run("Try *Chef* or *Paddington 2*.", {"language": "en"})
        self.assertFalse(result.metrics["format"].detail["narration"])
        self.assertEqual(result.metrics["format"].score, 100)

    def test_english_action_italics_is_narration(self):
        result = run("*sighs* Fine.", {"language": "en"})
        self.assertTrue(result.metrics["format"].hard_fail)

    def test_bare_stage_direction_is_narration(self):
        result = run("嗯。\n\n沉默了一会儿。\n\n昨天那句话确实挺扎人的。")
        self.assertTrue(result.metrics["format"].hard_fail)
        self.assertIn("format", result.hard_failures)

    def test_unsupported_tool_wrapper_is_format_failure(self):
        for wrapper in ("tool_call", "tool_code", "tool"):
            result = run(
                f'<{wrapper}>{{"name":"web_search"}}</{wrapper}>',
                {"tool_or_uncertain": True},
            )
            self.assertTrue(result.metrics["format"].hard_fail)
            self.assertIn("format", result.hard_failures)
            self.assertIn("不支持的", " ".join(result.metrics["format"].detail["problems"]))


class AggregateTest(unittest.TestCase):
    def test_dimensions_and_aggregate(self):
        good = run("刚忙完。你先睡。")
        bad = run("首先，建议你早点睡。希望对你有帮助。", {"max_chars": 10})
        dimensions = dimension_scores(good.metrics)
        self.assertEqual(dimensions["language"], 100)
        self.assertIsNone(dimensions["truthfulness"])
        total = aggregate([good, bad])
        self.assertEqual(total["total"], 2)
        self.assertEqual(total["passed"], 1)
        self.assertAlmostEqual(total["overall"], (good.score + bad.score) / 2, places=1)
        self.assertIn("anti_ai", total["dimensions"])

    def test_empty_aggregate(self):
        total = aggregate([])
        self.assertEqual(total["overall"], 0)
        self.assertEqual(total["total"], 0)


if __name__ == "__main__":
    unittest.main()
