"""多轮对话评测器：真实维护对话历史、工具结果、记忆与沉默/拉黑状态。"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .. import experiment
from ..metrics import extract_tool_calls
from ..provider import Provider
from . import tool_schema

SILENT_TOOLS = {"stay_silent"}


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


def _call_error(call: Any) -> str:
    return str(getattr(call, "error", "") or "")


def _record_call(conv: Conversation, call: Any, *, source: str) -> dict[str, Any]:
    """把一次调用写进 ``conv.turns[-1].tool_calls``（评分只看这里）。

    原生调用额外保留 id/source：报告能区分「模型真的走了 tools[]」还是老式文本
    ```yap-tool```；坏参数保留 error，评分不会把它算成一次成功调用。
    """
    record: dict[str, Any] = {
        "name": str(getattr(call, "name", "") or ""),
        "arguments": dict(getattr(call, "arguments", {}) or {}),
        "source": source,
    }
    call_id = str(getattr(call, "id", "") or "")
    if call_id:
        record["id"] = call_id
    error = _call_error(call)
    if error:
        record["error"] = error
    conv.turns[-1].tool_calls.append(record)
    return record


def _execute_call(call: Any, conv: Conversation) -> str:
    """执行 mock 工具；坏参数不执行，回一条可读错误。"""
    error = _call_error(call)
    if error:
        return f"工具调用失败：{error}"
    return _mock_tool(str(getattr(call, "name", "") or ""), dict(getattr(call, "arguments", {}) or {}), conv)


def _native_assistant_message(reply: Any) -> dict[str, Any]:
    """按 OpenAI 形状回填 assistant 的 tool_calls 消息（content 可为 null）。"""
    calls: list[dict[str, Any]] = []
    for call in reply.tool_calls:
        arguments = str(getattr(call, "arguments_text", "") or "").strip()
        if not arguments:
            arguments = "{}"
        calls.append(
            {
                "id": str(getattr(call, "id", "") or ""),
                "type": "function",
                "function": {"name": str(getattr(call, "name", "") or ""), "arguments": arguments},
            }
        )
    return {"role": "assistant", "content": (reply.text or None), "tool_calls": calls}


def _tool_result_message(call: Any, result: str) -> dict[str, Any]:
    """按 OpenAI 形状回填 tool 结果；每个 tool_call_id 必须恰好一条。"""
    message: dict[str, Any] = {
        "role": "tool",
        "tool_call_id": str(getattr(call, "id", "") or ""),
        "content": result,
    }
    name = str(getattr(call, "name", "") or "")
    if name:
        message["name"] = name
    return message


def run_tool_loop(
    provider: Provider,
    messages: list[dict[str, Any]],
    conv: Conversation,
    *,
    tools: list[dict[str, Any]] | None = None,
    max_tool_rounds: int = 3,
) -> tuple[str, bool]:
    """跑一轮用户消息对应的工具循环，返回 ``(最终可见文本, 是否沉默)``。

    两条路都保留：

    - **原生**：``reply.tool_calls`` → 回填 assistant tool_calls + 每条 tool 结果（OpenAI 形状）；
    - **文本 fallback**：老模型仍可输出 ```yap-tool``` 代码块 → 回填 assistant 正文 +
      ``[工具结果：...]`` 用户消息（旧协议，不破坏既有行为）。

    ``stay_silent`` 一旦执行就结束本轮，且最终正文置空；其余工具继续循环，
    最多 ``max_tool_rounds`` 轮，之后强制取一次正文（防止模型无限调工具）。
    """
    final_text = ""
    silent = False
    scenario_id = str(conv.scenario.get("id"))
    for round_index in range(max_tool_rounds + 1):
        reply = provider.complete(messages, scenario_id=scenario_id, tools=tools)
        native_calls = list(getattr(reply, "tool_calls", None) or [])
        visible, text_calls = extract_tool_calls(reply.text or "")
        if (native_calls or text_calls) and round_index < max_tool_rounds:
            if native_calls:
                # 原生协议：content 与 tool_calls 可以同时存在，文本留作过程话，不落正文。
                messages.append(_native_assistant_message(reply))
                for call in native_calls:
                    _record_call(conv, call, source="native")
                    result = _execute_call(call, conv)
                    if str(getattr(call, "name", "") or "") in SILENT_TOOLS:
                        silent = True
                    messages.append(_tool_result_message(call, result))
            else:
                # 文本 fallback：保持旧 shape（assistant 正文 + 用户工具结果）。
                messages.append({"role": "assistant", "content": reply.text})
                for call in text_calls:
                    _record_call(conv, call, source="text")
                    result = _execute_call(call, conv)
                    if call.name in SILENT_TOOLS:
                        silent = True
                    messages.append({"role": "user", "content": f"[工具结果：{call.name}]\n{result}"})
            if silent:
                break
            continue
        final_text = visible.strip()
        break
    return final_text, silent


def run_conversation(
    scenario: dict[str, Any],
    provider: Provider,
    variant: experiment.Variant,
    *,
    max_tool_rounds: int = 3,
    max_tokens: int = 500,
    tools: list[dict[str, Any]] | None = None,
) -> Conversation:
    conv = Conversation(scenario=scenario, variant=variant)
    if tools is None:
        # Bench 默认与产品一致：把关键工具的 OpenAI schema 交给模型。
        # 传 [] 可显式退回纯文本 yap-tool 链路（离线对比/老模型）。
        tools = tool_schema.openai_tools()
    system_base = experiment.build_variant_system(
        {**scenario, "history": []}, arch=variant.arch, mood=variant.mood, memory=variant.memory
    )
    user_turns = [str(text) for text in scenario.get("turns") or []]
    for index, user_text in enumerate(user_turns):
        if conv.blocked_until > 0:
            conv.turns.append(TurnRecord(user=user_text, assistant="", silent=True))
            continue
        conv.turns.append(TurnRecord(user=user_text, assistant=""))
        messages: list[dict[str, Any]] = [{"role": "system", "content": system_base}]
        messages.extend(conv.transcript[:-1])  # 当前用户消息会由下面补
        messages.append({"role": "user", "content": user_text})
        final_text, silent = run_tool_loop(
            provider,
            messages,
            conv,
            tools=tools,
            max_tool_rounds=max_tool_rounds,
        )
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
