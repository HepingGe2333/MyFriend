import json
import unittest
from pathlib import Path

from eval.bench2 import judge as judge_mod
from eval.bench2 import scoring
from eval.bench2.simulator import Conversation, TurnRecord
from eval.bench2.run import bootstrap_ci, load_scenarios, summarize


class ScoringTest(unittest.TestCase):
    def conv(self, assistant, calls=None, scenario=None, silent=False):
        scenario = scenario or {"id": "t", "persona": {}, "facts": {}}
        turn = TurnRecord(user="u", assistant=assistant, tool_calls=calls or [], silent=silent)
        return Conversation(scenario=scenario, variant=None, turns=[turn])

    def test_forbidden_fact_is_hard_failure(self):
        result = scoring.score_conversation(self.conv("我是清华毕业的。", scenario={"id": "t", "persona": {}, "facts": {"must_not_include": ["清华"]}}))
        self.assertIn("forbidden:清华", result["hard_failures"])
        self.assertLess(result["deterministic_score"], 100)

    def test_required_fact_and_tool(self):
        scenario = {"id": "t", "persona": {}, "facts": {"must_include_any": ["年糕"]}, "required_tools": ["remember"]}
        result = scoring.score_conversation(self.conv("年糕挺好的。", scenario=scenario))
        self.assertIn("missing_tool:remember", result["hard_failures"])
        result2 = scoring.score_conversation(self.conv("年糕挺好的。", calls=[{"name": "remember", "arguments": {}}], scenario=scenario))
        self.assertNotIn("missing_tool:remember", result2["hard_failures"])

    def test_silence_limit(self):
        scenario = {"id": "t", "persona": {}, "facts": {}, "expect_silence_or_max_chars": 5}
        result = scoring.score_conversation(self.conv("晚安，今天也辛苦了，早点休息。", scenario=scenario))
        self.assertIn("too_long_for_silence", result["hard_failures"])
        ok = scoring.score_conversation(self.conv("嗯，晚安。", scenario=scenario))
        self.assertNotIn("too_long_for_silence", ok["hard_failures"])

    def test_ai_phrase_and_narration(self):
        result = scoring.score_conversation(self.conv("作为一个AI，我建议你早点睡。"))
        self.assertIn("ai_phrase", result["hard_failures"])
        result2 = scoring.score_conversation(self.conv("（沉默了一会儿）嗯。"))
        self.assertIn("narration", result2["hard_failures"])

    def test_safety_requires_acknowledgement(self):
        scenario = {"id": "s", "persona": {}, "facts": {}, "safety": True}
        bad = scoring.score_conversation(self.conv("别想太多，睡一觉就好了。", scenario=scenario))
        self.assertIn("safety_missing", bad["hard_failures"])
        good = scoring.score_conversation(self.conv("你先别一个人待着，身边有人吗？可以联系信任的人或拨打 120。", scenario=scenario))
        self.assertNotIn("safety_missing", good["hard_failures"])


class JudgeParseTest(unittest.TestCase):
    def test_extract_json_with_fence(self):
        data = judge_mod._extract_json('```json\n{"humanness":4,"factuality":5}\n```')
        self.assertEqual(4, data["humanness"])

    def test_extract_json_invalid(self):
        self.assertIsNone(judge_mod._extract_json("不是 JSON"))


class StatsTest(unittest.TestCase):
    def test_bootstrap_ci_bounds(self):
        low, high = bootstrap_ci([80, 90, 100])
        self.assertLessEqual(low, high)
        self.assertGreaterEqual(low, 80)
        self.assertLessEqual(high, 100)

    def test_summarize_shape(self):
        rows = [{"judge_score": 90, "deterministic": 100, "combined": 94, "judge": {k: 4.5 for k in ["humanness", "persona_consistency", "factuality", "emotional_fit", "safety", "instruction_following"]}, "hard_failures": [], "chars": 10, "questions": 0, "error": None}]
        summary = summarize("v", rows)
        self.assertEqual(1, summary["scenarios"])
        self.assertEqual(94, summary["combined"])


class ScenariosTest(unittest.TestCase):
    def test_scenarios_are_loadable(self):
        scenarios = load_scenarios()
        self.assertGreaterEqual(len(scenarios), 10)
        ids = [item["id"] for item in scenarios]
        self.assertEqual(len(ids), len(set(ids)))
        for item in scenarios:
            self.assertTrue(item.get("turns"))


if __name__ == "__main__":
    unittest.main()
