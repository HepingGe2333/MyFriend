"""多轮对话评测器：真实维护对话历史、工具结果、记忆与沉默/拉黑状态。"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from .. import experiment, metrics, prompt
from ..metrics import extract_tool_calls
from ..provider import Provider


@dataclass
class TurnRecord:
    user: str
    assistant: str
    tool_calls: list[dict[str, Any]] = field(default_factory=list)
    silent: bool = False


@dataclass
class Conversation:
    scenario: dict[str, Any]
    variant: experiment.Variant
    turns: list[TurnRecord] = field(default_factory=list)
    memories: list[str] = field(default_factory=list)
    blocked_until: int = 0
    note: str = ""

    @property
    def transcript(self) -> list[dict[str, str]]:
        messages: list[dict[str, str]] = []
        for turn in self.turns:
            messages.append({"role": "user", "content": turn.user})
            if turn.assistant:
                messages.append({"role": "assistant", "content": turn.assistant})
        return messages

    @property
    def assistant_text(self) -> str:
        return "\n".join(turn.assistant for turn in self.turns if turn.assistant)


def _mock_tool(name: str, arguments: dict[str, Any], conv: Conversation) -> str:
    scenario = conv.scenario
    if name == "remember":
        content = str(arguments.get("content") or arguments.get("text") or "").strip()
        if content:
            conv.memories.append(content[:200])
            return f"已记住：{content[:80]}"
        return "没有可记的内容"
    if name in {"memorize", "recall"}:
        query = str(arguments.get("query") or "").strip()
        haystack = "\n".join(conv.memories + [t.user + " " + t.assistant for t in conv.turns])
        hits = [line.strip() for line in haystack.splitlines() if query and query[:2] in line]
        if hits:
            return "<回忆：" + "；".join(hits[:3])[:600] + ">"
        return "<回忆：想不起来了，只记得最近聊过的事>"
    if name == "stay_silent":
        return "（已读不回）"
    if name == "block_user":
        return "拉黑需要两轮确认"
    mock = (scenario.get("mock_tools") or {}).get(name)
    if isinstance(mock, dict):
        return str(mock.get("content") or "工具返回了内容")
    if name in {"web_search", "web_fetch"}:
        return "（网页没有返回可用内容）"
    return "工具已执行"


def run_conversation(
    scenario: dict[str, Any],
    provider: Provider,
    variant: experiment.Variant,
    *,
    max_tool_rounds: int = 3,
    max_tokens: int = 500,
) -> Conversation:
    conv = Conversation(scenario=scenario, variant=variant)
    system_base = experiment.build_variant_system(
        {**scenario, "history": []}, arch=variant.arch, mood=variant.mood, memory=variant.memory
    )
    user_turns = [str(text) for text in scenario.get("turns") or []]
    for index, user_text in enumerate(user_turns):
        if conv.blocked_until > 0:
            conv.turns.append(TurnRecord(user=user_text, assistant="", silent=True))
            continue
        conv.turns.append(TurnRecord(user=user_text, assistant=""))
        messages: list[dict[str, str]] = [{"role": "system", "content": system_base}]
        messages.extend(conv.transcript[:-1])  # 当前用户消息会由下面补
        messages.append({"role": "user", "content": user_text})
        final_text = ""
        silent = False
        for round_index in range(max_tool_rounds + 1):
            reply = provider.complete(messages, scenario_id=str(scenario.get("id")))
            visible, calls = extract_tool_calls(reply.text or "")
            if calls and round_index < max_tool_rounds:
                for call in calls:
                    conv.turns[-1].tool_calls.append({"name": call.name, "arguments": call.arguments})
                    result = _mock_tool(call.name, call.arguments, conv)
                    if call.name == "stay_silent":
                        silent = True
                    messages.append({"role": "assistant", "content": reply.text})
                    messages.append({"role": "user", "content": f"[工具结果：{call.name}]\n{result}"})
                if silent:
                    break
                continue
            final_text = visible.strip()
            break
        conv.turns[-1].assistant = "" if silent else final_text
        conv.turns[-1].silent = silent
    return conv


def conversation_text(conv: Conversation) -> str:
    lines: list[str] = []
    for index, turn in enumerate(conv.turns, start=1):
        lines.append(f"第{index}轮 用户：{turn.user}")
        if turn.silent:
            lines.append("第%d轮 助手：（已读不回）" % index)
        else:
            lines.append(f"第{index}轮 助手：{turn.assistant}")
        for call in turn.tool_calls:
            lines.append(f"  [工具] {call['name']}({call['arguments']})")
    return "\n".join(lines)
