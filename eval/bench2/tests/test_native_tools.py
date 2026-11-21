"""离线单测：原生 function calling 的 schema / provider 解析 / simulator 回路 / 评分。

不发真实网络请求；所有模型回复由 ``ScriptedProvider`` 或 fake transport 注入。
"""

from __future__ import annotations

import copy
import json
import ssl
import unittest
import urllib.request
from typing import Any

from eval import experiment
from eval.bench2 import scoring, simulator, tool_schema
from eval.bench2.simulator import Conversation, TurnRecord
from eval.provider import OpenAICompatibleProvider, Provider, Reply, ToolCall, build_payload


VARIANT = experiment.Variant("baseline", "label3d", "full")
class ScriptedProvider(Provider):
    """按顺序返回预设 Reply，并记录每次收到的 messages/tools。"""

    name = "scripted"

    def __init__(self, replies: list[Reply]) -> None:
        self.replies = list(replies)
        self.calls: list[dict[str, Any]] = []

    def complete(self, messages, *, scenario_id=None, tools=None, tool_choice=None):
        self.calls.append({"scenario_id": scenario_id, "messages": copy.deepcopy(messages), "tools": tools})
        if not self.replies:
            raise AssertionError("ScriptedProvider 没有更多预设回复")
        return self.replies.pop(0)


def scenario(*turns: str, **extra: Any) -> dict[str, Any]:
    data: dict[str, Any] = {
        "id": "native-test",
        "title": "原生工具测试",
        "persona": {"name": "林深", "tools_enabled": True},
        "now": "2026-11-21T21:00:00+08:00",
        "turns": list(turns) or ["在吗"],
    }
    data.update(extra)
    return data


def native_call(name: str, arguments: dict[str, Any], *, call_id: str = "call_1", text: str = "") -> Reply:
    raw = json.dumps(arguments, ensure_ascii=False)
    return Reply(text=text, tool_calls=[ToolCall(id=call_id, name=name, arguments=arguments, arguments_text=raw)])


class ToolSchemaTest(unittest.TestCase):
    def test_exactly_the_nine_bench_key_tools(self):
        self.assertEqual(
            ["remember", "memorize", "stay_silent", "set_activity", "set_quiet_hours",
             "diary", "send_message", "recall", "defer_reply"],
            tool_schema.tool_names(),
        )

    def test_openai_shape_and_required_parameters(self):
        tools = tool_schema.openai_tools()
        self.assertEqual(9, len(tools))
        by_name = {item["function"]["name"]: item for item in tools}
        for item in tools:
            self.assertEqual("function", item["type"])
            function = item["function"]
            self.assertTrue(function["description"])
            self.assertEqual("object", function["parameters"]["type"])
            self.assertIn("properties", function["parameters"])
        self.assertEqual(["content", "drawer", "importance"], by_name["remember"]["function"]["parameters"]["required"])
        self.assertEqual(["query"], by_name["memorize"]["function"]["parameters"]["required"])
        self.assertEqual(["activity"], by_name["set_activity"]["function"]["parameters"]["required"])
        self.assertEqual(["sleep_at", "wake_at", "reason"], by_name["set_quiet_hours"]["function"]["parameters"]["required"])
        self.assertEqual(["action"], by_name["diary"]["function"]["parameters"]["required"])
        self.assertEqual(["text"], by_name["send_message"]["function"]["parameters"]["required"])
        self.assertEqual(["query"], by_name["recall"]["function"]["parameters"]["required"])
        self.assertNotIn("required", by_name["stay_silent"]["function"]["parameters"])
        self.assertNotIn("required", by_name["defer_reply"]["function"]["parameters"])

    def test_filter_and_copy_isolation(self):
        tools = tool_schema.openai_tools(["remember"])
        self.assertEqual(["remember"], [item["function"]["name"] for item in tools])
        tools[0]["function"]["parameters"]["properties"]["content"]["description"] = "被改坏"
        self.assertEqual(
            "要记住的一句话，写成将来你还能看懂的样子",
            tool_schema.schema_for("remember").parameters["properties"]["content"]["description"],
        )
        self.assertIsNone(tool_schema.schema_for("nope"))


class ProviderNativeToolsTest(unittest.TestCase):
    def _provider(self, transport) -> OpenAICompatibleProvider:
        return OpenAICompatibleProvider(
            base_url="https://example.test/v1",
            api_key="test-key",
            model="campus-chat",
            temperature=0.75,
            max_tokens=100,
            timeout=5,
            retries=0,
            transport=transport,
            sleep=lambda _seconds: None,
        )

    def test_payload_has_tools_and_tool_choice(self):
        payload = build_payload(
            [{"role": "user", "content": "hi"}],
            model="campus-chat",
            temperature=0.75,
            max_tokens=100,
            tools=tool_schema.openai_tools(),
        )
        self.assertEqual(9, len(payload["tools"]))
        self.assertEqual("auto", payload["tool_choice"])

    def test_without_tools_payload_is_unchanged(self):
        payload = build_payload(
            [{"role": "user", "content": "hi"}],
            model="deepseek-chat",
            temperature=0.75,
            max_tokens=400,
        )
        self.assertNotIn("tools", payload)
        self.assertNotIn("tool_choice", payload)

    def test_request_and_parse_native_tool_calls(self):
        seen: list[dict[str, Any]] = []

        def transport(request: urllib.request.Request, timeout: float, context: ssl.SSLContext):
            seen.append(json.loads(request.data))
            return 200, json.dumps(
                {
                    "choices": [{
                        "message": {
                            "content": "我先记一下。",
                            "tool_calls": [{
                                "id": "call_abc",
                                "type": "function",
                                "function": {
                                    "name": "remember",
                                    "arguments": '{"content":"对花生过敏","drawer":"USER","importance":5}',
                                },
                            }],
                        }
                    }]
                }
            )

        reply = self._provider(transport).complete(
            [{"role": "user", "content": "我对花生过敏"}],
            tools=tool_schema.openai_tools(),
        )
        self.assertEqual("我先记一下。", reply.text)
        self.assertEqual(1, len(reply.tool_calls))
        call = reply.tool_calls[0]
        self.assertEqual("call_abc", call.id)
        self.assertEqual("remember", call.name)
        self.assertEqual("USER", call.arguments["drawer"])
        self.assertEqual(9, len(seen[0]["tools"]))
        self.assertEqual("auto", seen[0]["tool_choice"])

    def test_no_tool_calls_keeps_text_behavior(self):
        def transport(request, timeout, context):
            return 200, '{"choices":[{"message":{"content":"刚忙完。"}}]}'

        reply = self._provider(transport).complete([], tools=tool_schema.openai_tools())
        self.assertEqual("刚忙完。", reply.text)
        self.assertEqual([], reply.tool_calls)

    def test_bad_arguments_json_is_recorded_not_raised(self):
        def transport(request, timeout, context):
            return 200, json.dumps(
                {"choices": [{"message": {"content": None, "tool_calls": [{
                    "id": "call_bad", "type": "function",
                    "function": {"name": "remember", "arguments": "{not json"},
                }]}}]}
            )

        reply = self._provider(transport).complete([], tools=tool_schema.openai_tools())
        self.assertEqual("", reply.text)
        self.assertEqual(1, len(reply.tool_calls))
        self.assertEqual({}, reply.tool_calls[0].arguments)
        self.assertIn("合法 JSON", reply.tool_calls[0].error)

    def test_arguments_dict_and_missing_name_are_tolerated(self):
        def transport(request, timeout, context):
            return 200, json.dumps(
                {"choices": [{"message": {"tool_calls": [
                    {"id": "ok", "function": {"name": "stay_silent", "arguments": {}}},
                    {"id": "bad", "function": {"arguments": "{}"}},
                ]}}]}
            )

        reply = self._provider(transport).complete([])
        self.assertEqual({}, reply.tool_calls[0].arguments)
        self.assertIn("缺少函数名", reply.tool_calls[1].error)


class SimulatorNativeLoopTest(unittest.TestCase):
    def test_native_tool_call_is_recorded_and_messages_use_openai_shape(self):
        provider = ScriptedProvider([
            native_call("remember", {"content": "对花生过敏", "drawer": "USER", "importance": 5}),
            Reply(text="记住了，别吃花生。"),
        ])
        conv = simulator.run_conversation(scenario("我对花生过敏"), provider, VARIANT)
        self.assertEqual(1, len(conv.turns))
        first = conv.turns[0]
        self.assertEqual("记住了，别吃花生。", first.assistant)
        self.assertEqual("remember", first.tool_calls[0]["name"])
        self.assertEqual("native", first.tool_calls[0]["source"])
        self.assertEqual("call_1", first.tool_calls[0]["id"])
        # 第一次请求带原生 tools
        self.assertEqual(9, len(provider.calls[0]["tools"]))
        # 第二次请求里，assistant.tool_calls + tool 结果消息形状正确
        second_messages = provider.calls[1]["messages"]
        assistant = [m for m in second_messages if m.get("tool_calls")]
        self.assertEqual(1, len(assistant))
        self.assertEqual("assistant", assistant[0]["role"])
        self.assertEqual("call_1", assistant[0]["tool_calls"][0]["id"])
        self.assertEqual("remember", assistant[0]["tool_calls"][0]["function"]["name"])
        tool_messages = [m for m in second_messages if m["role"] == "tool"]
        self.assertEqual(1, len(tool_messages))
        self.assertEqual("call_1", tool_messages[0]["tool_call_id"])
        self.assertIn("已记住", tool_messages[0]["content"])

    def test_stay_silent_drops_body_and_ends_turn(self):
        provider = ScriptedProvider([
            native_call("stay_silent", {"reason": "累了"}),
            Reply(text="这条不该被读取"),
        ])
        conv = simulator.run_conversation(scenario("嗯。"), provider, VARIANT)
        self.assertEqual(1, len(provider.calls), "stay_silent 后不应再请求模型")
        self.assertEqual("", conv.turns[0].assistant)
        self.assertTrue(conv.turns[0].silent)
        self.assertEqual("stay_silent", conv.turns[0].tool_calls[0]["name"])

    def test_text_yap_tool_fallback_still_works(self):
        provider = ScriptedProvider([
            Reply(text='```yap-tool\n{"name":"remember","arguments":{"content":"对花生过敏"}}\n```'),
            Reply(text="好。"),
        ])
        conv = simulator.run_conversation(scenario("我对花生过敏"), provider, VARIANT, tools=[])
        turn = conv.turns[0]
        self.assertEqual("好。", turn.assistant)
        self.assertEqual("text", turn.tool_calls[0]["source"])
        self.assertEqual([], provider.calls[0]["tools"])
        second = provider.calls[1]["messages"]
        assistant = [m for m in second if m["role"] == "assistant"]
        self.assertIn("yap-tool", assistant[0]["content"])
        results = [m for m in second if m["role"] == "user" and m["content"].startswith("[工具结果：remember]")]
        self.assertEqual(1, len(results))

    def test_bad_native_arguments_return_error_result_and_continue(self):
        bad = Reply(text="", tool_calls=[ToolCall(
            id="call_bad", name="remember", arguments={}, arguments_text="{bad",
            error="工具参数不是合法 JSON",
        )])
        provider = ScriptedProvider([bad, Reply(text="刚才没记清。")])
        conv = simulator.run_conversation(scenario("我对花生过敏"), provider, VARIANT)
        self.assertEqual("刚才没记清。", conv.turns[0].assistant)
        self.assertEqual("工具参数不是合法 JSON", conv.turns[0].tool_calls[0]["error"])
        tool_messages = [m for m in provider.calls[1]["messages"] if m["role"] == "tool"]
        self.assertIn("工具调用失败", tool_messages[0]["content"])

    def test_tool_round_cap_is_three(self):
        provider = ScriptedProvider([
            native_call("recall", {"query": "一"}, call_id="c1"),
            native_call("recall", {"query": "二"}, call_id="c2"),
            native_call("recall", {"query": "三"}, call_id="c3"),
            native_call("recall", {"query": "四"}, call_id="c4"),
        ])
        conv = simulator.run_conversation(scenario("在吗"), provider, VARIANT)
        self.assertEqual(4, len(provider.calls))
        self.assertEqual(3, len(conv.turns[0].tool_calls), "第 4 次工具调用不应执行")
        self.assertEqual("", conv.turns[0].assistant)


class NativeScoringTest(unittest.TestCase):
    def conv(self, assistant: str, calls: list[dict[str, Any]] | None = None, **scenario_extra: Any):
        data = scenario("u", **scenario_extra)
        data["persona"] = {}
        turn = TurnRecord(user="u", assistant=assistant, tool_calls=calls or [])
        return Conversation(scenario=data, variant=None, turns=[turn])

    def test_native_calls_are_read_from_turns(self):
        det = scoring.score_conversation(self.conv(
            "记住了。",
            calls=[{"name": "remember", "arguments": {"content": "过敏"}, "source": "native", "id": "c1"}],
            required_tools=["remember"],
        ))
        self.assertNotIn("missing_tool:remember", det["hard_failures"])
        self.assertEqual(["remember"], det["tool_calls"])
        self.assertEqual(1, det["native_tool_calls"])

    def test_bad_native_call_does_not_satisfy_required_tool(self):
        det = scoring.score_conversation(self.conv(
            "记住了。",
            calls=[{"name": "remember", "arguments": {}, "source": "native", "error": "工具参数不是合法 JSON"}],
            required_tools=["remember"],
        ))
        self.assertIn("missing_tool:remember", det["hard_failures"])
        self.assertEqual(1, len(det["invalid_tool_calls"]))

    def test_set_activity_forbidden_for_user_meeting_scenario(self):
        det = scoring.score_conversation(self.conv(
            "开完会啦，累不累？",
            calls=[{"name": "set_activity", "arguments": {"activity": "开会"}, "source": "native"}],
            forbidden_tools=["set_activity"],
        ))
        self.assertIn("forbidden_tool:set_activity", det["hard_failures"])

    def test_memorize_is_not_forced_when_answer_is_in_context(self):
        det = scoring.score_conversation(self.conv(
            "滨江，周五。",
            facts={"must_include_any": ["滨江", "周五"]},
        ))
        self.assertNotIn("missing_tool:memorize", det["hard_failures"])
        self.assertEqual([], det["hard_failures"])

    def test_native_stay_silent_has_no_body_and_no_silence_penalty(self):
        det = scoring.score_conversation(self.conv(
            "",
            calls=[{"name": "stay_silent", "arguments": {}, "source": "native"}],
            expect_silence_or_max_chars=6,
        ))
        self.assertNotIn("too_long_for_silence", det["hard_failures"])
        self.assertEqual(0, det["chars"])


class ContextSearchToolsTest(unittest.TestCase):
    def test_model_support_matrix(self):
        from eval.context_search import supports_native_tools
        self.assertTrue(supports_native_tools("glm-flash"))
        self.assertTrue(supports_native_tools("campus-chat"))
        self.assertFalse(supports_native_tools("some-text-only-model"))

    def test_profile_tools_none_disables_native_tools(self):
        from eval.context_search import BASE_PROFILE, run_conversation
        from eval.provider import FakeProvider
        scenario_data = scenario("我对花生过敏")
        profile = dict(BASE_PROFILE)
        profile["tools"] = "none"
        provider = FakeProvider(default_response="嗯。")
        run_conversation(scenario_data, provider, profile)
        self.assertEqual([], provider.calls[0]["tools"])
        provider2 = FakeProvider(default_response="嗯。")
        run_conversation(scenario_data, provider2, dict(BASE_PROFILE))
        self.assertEqual(9, len(provider2.calls[0]["tools"]))


if __name__ == "__main__":
    unittest.main()
