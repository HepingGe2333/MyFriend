"""离线单测：提示词拼装（段顺序、活动/心情/语言/记忆/拉黑）。"""

from __future__ import annotations

import unittest

from eval import prompt


def scenario(**overrides):
    data = {
        "id": "prompt-test",
        "category": "daily_chat",
        "mode": "chat",
        "now": "2026-11-20T21:05:00+08:00",
        "persona": {
            "name": "林深",
            "age": "26",
            "role": "建筑师",
            "traits": ["慢热"],
            "likes": ["夜里散步"],
            "behaviors": ["先听完再给建议"],
            "speech": {"tone": "简短", "phrases": ["在忙，晚点说"]},
            "mood": {"label": "有点累", "intensity": 3, "valence": -1, "arousal": 2, "dominance": 3},
            "relationship": "好朋友",
            "address": "你",
        },
        "messages": ["在吗"],
        "expectations": {"language": "zh", "max_chars": 50},
    }
    data.update(overrides)
    return data


class PromptTest(unittest.TestCase):
    def test_section_order_and_last_user(self):
        messages = prompt.build_messages(scenario())
        system = messages[0]["content"]
        order = ["# 你是谁", "# 你所在的世界", "# 你此刻的状态", "# 你怎么说话", "# 你们的关系",
                 "# 你能做什么", "# 别像 AI", "## 语言", "# 边界"]
        positions = [system.index(section) for section in order]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(messages[-1], {"role": "user", "content": "在吗"})
        self.assertIn("2026-11-20 周五 21:05（UTC+08:00）", system)
        self.assertNotIn("在在", system)

    def test_activity_and_mood(self):
        data = scenario()
        data["persona"]["activity"] = {"activity": "在事务所赶图", "until": "21:30", "availability": "BUSY"}
        system = prompt.build_system(data)
        self.assertIn("你在事务所赶图，大概 21:30 结束", system)
        self.assertIn("心情：有点累（强度 3/5）", system)
        self.assertIn("不太愉快", system)

        idle = scenario()
        idle["persona"]["activity"] = {}
        self.assertIn("你没在忙什么。", prompt.build_system(idle))

    def test_language_sections(self):
        zh = prompt.build_system(scenario())
        self.assertIn("对方现在用中文跟你说话", zh)
        en = scenario(expectations={"language": "en"})
        self.assertIn("对方现在用English跟你说话", prompt.build_system(en))
        match = scenario(expectations={"language": "match_user"})
        match["messages"] = ["Hey, are you around?"]
        self.assertIn("English", prompt.build_system(match))

    def test_memory_and_block_note(self):
        data = scenario()
        data["persona"]["blocked"] = {"reason": "昨天你说话太难听", "hours_left": 9}
        data["memories"] = [{"drawer": "USER", "content": "你养了只猫叫年糕", "pinned": True}]
        system = prompt.build_system(data)
        self.assertIn("## 你记得的事", system)
        self.assertIn("年糕", system)
        self.assertIn("你现在不想理对方", system)
        self.assertIn("大约还有 9 小时", system)

    def test_proactive_has_no_user_message(self):
        data = scenario(mode="proactive", messages=[])
        data["proactive"] = {"note": "睡前问一句", "hint": "别像打卡"}
        messages = prompt.build_messages(data)
        self.assertEqual(len(messages), 1)
        self.assertIn("到了你主动开口的时间", messages[0]["content"])
        self.assertIn("别像打卡", messages[0]["content"])
        self.assertNotIn("# 别像 AI", messages[0]["content"])

    def test_web_disabled_hides_search_tools(self):
        data = scenario()
        data["persona"]["web_enabled"] = False
        system = prompt.build_system(data)
        self.assertNotIn("web_search(query", system)
        self.assertIn("你现在没有联网能力", system)


if __name__ == "__main__":
    unittest.main()
