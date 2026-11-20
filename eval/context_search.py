"""上下文工程参数搜索：把「同一模板多版本 + Bench v2」推广到各细节。

阶段：一次只改一个维度（坐标下降），找出每个维度的最优；再组合验证。
不改产品代码，只产出 profile 与报告。
"""

from __future__ import annotations

import argparse
import concurrent.futures
import itertools
import json
import os
import statistics
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Iterable

from . import experiment, prompt
from .bench2.judge import judge
from .bench2.scoring import score_conversation
from .bench2.simulator import Conversation, TurnRecord, _mock_tool, conversation_text
from .metrics import extract_tool_calls
from .provider import OpenAICompatibleProvider

REPORT_DIR = Path(__file__).resolve().parent / "reports"
SCENARIOS = Path(__file__).resolve().parent / "bench2" / "scenarios.json"
JUDGE_KEYS = ["humanness", "persona_consistency", "factuality", "emotional_fit", "safety", "instruction_following"]

ANTI_FULL = [
    "不要每句话都反问；不需要每轮都把话题抛回去。",
    "不要总结对方说的话，也不要说「所以你的意思是…」。",
    "不要列点、不要「首先/其次/最后」，不要写成一段说明书。",
    "不要每次都认同或安慰；可以不同意、可以只回一句、也可以不回（stay_silent）。",
    "句子可以短、可以不完整；不要重复同一个口头禅或同一个开头。",
    "不主动给建议、清单或心理分析；不知道就说不知道。",
    "不想说话时调用 stay_silent（只已读不回），不要用文字说「我不回」；对方在说重要的事时不要用。",
    "不要写动作、神态、心理描写式的旁白。",
]
ANTI_CORE = ANTI_FULL[:4] + [ANTI_FULL[7]]
ANTI_MIN = [ANTI_FULL[0], ANTI_FULL[2], ANTI_FULL[3]]
ANTI_NONE: list[str] = []

TOOLS_COMPACT = [
    "remember(content, drawer, importance)：长期事实/约定必须调用，只在正文说「记住了」不算。",
    "memorize(query)：回忆旧对话必须调用，不要凭空猜。",
    "stay_silent(reason?)：不想说话时只已读不回，不用于逃避重要话题。",
    "diary(action,...)：自己的备忘与提醒。",
    "send_message(text)：想连发短消息时调用，每条 ≤120 字。",
]

PROFILE_AXES: dict[str, list[str]] = {
    "arch": ["compact", "two_block", "state_first"],
    "guidance": ["g17", "g12", "g05", "g06"],
    "memory": ["retrieval", "long", "full"],
    "anti": ["full", "core", "minimal", "none"],
    "tools": ["full", "compact", "none"],
    "history": ["raw", "labeled", "timestamped"],
    "state": ["full", "minimal", "mood_only"],
    "persona": ["full", "compact"],
    "hints": ["off", "on"],
}
BASE_PROFILE = {axis: values[0] for axis, values in PROFILE_AXES.items()}


def _anti(level: str) -> str:
    rules = {"full": ANTI_FULL, "core": ANTI_CORE, "minimal": ANTI_MIN, "none": ANTI_NONE}[level]
    return ("# 别像 AI\n" + "\n".join("- " + rule for rule in rules)) if rules else ""


def _tools(level: str, persona: dict[str, Any]) -> str:
    if level == "none" or not persona.get("tools_enabled", True):
        return ""
    if level == "compact":
        return "# 你能做什么\n" + "\n".join("- " + line for line in TOOLS_COMPACT)
    return prompt._capabilities_section(persona)


def _state(scenario: dict[str, Any], level: str, guidance: str, mood: str) -> str:
    full = experiment._state_with_variant(scenario, mood if mood in experiment.GUIDANCE_VARIANTS or mood in experiment.MOOD_MODES else "guidance")
    # _state_with_variant 会在 mood 为 gXX 时套引导模板；这里把 guidance 再单独渲染。
    if level == "full":
        return full
    now = prompt._now_text(scenario)
    activity = scenario["persona"].get("activity") or {}
    minimal = f"# 你此刻的状态\n现在是{now}。"
    if activity.get("activity"):
        minimal += f"你在{activity['activity']}。"
    if level == "minimal":
        return minimal
    # mood_only：只保留时间 + 引导
    return minimal + "\n\n" + experiment._mood_block(guidance)


TOOL_HINT = """# 工具使用
- 在忙具体的事：调用 set_activity；想休息/不被打扰：set_quiet_hours。
- 关于对方的长期事实：调用 remember；回忆这段会话的细节：调用 memorize（只在正文说「我记得」不算）。
- 不想说话：调用 stay_silent；这一轮先不回、过会再说：defer_reply。
- 工具调用就是行动；不要用文字假装已经调用。"""


def _hints(level: str) -> str:
    return TOOL_HINT if level == "on" else ""


def _persona(scenario: dict[str, Any], level: str) -> str:
    p = scenario["persona"]
    if level == "compact":
        bullets = [f"- 你是{p.get('name') or '他'}" + (f"，{p.get('age')}岁" if p.get("age") else ""),
                   "- 性格：" + ("、".join(p.get("traits") or []) or "自然"),
                   "- 说话：" + (str((p.get("speech") or {}).get("tone") or "短句")[:60]),
                   "- 喜欢：" + ("、".join(p.get("likes") or []) or "不刻意")]
        return "# 关于你\n" + "\n".join(bullets)
    return "\n\n".join([
        prompt._identity_section(p),
        prompt._world_section(p.get("world")),
        prompt._voice_section(p),
        prompt._relationship_section(p),
    ])


def build_system(scenario: dict[str, Any], profile: dict[str, str]) -> str:
    p = scenario["persona"]
    persona = _persona(scenario, profile["persona"])
    state = _state(scenario, profile["state"], profile["guidance"], profile["guidance"])
    anti = _anti(profile["anti"])
    tools = _tools(profile["tools"], p)
    hints = _hints(profile.get("hints", "off"))
    memo = experiment._memory_block(scenario, profile["memory"])
    language = prompt._language_section(scenario)
    boundaries = prompt._boundaries_section()
    arch = profile["arch"]
    if arch == "two_block":
        stable = "# 设定\n" + "\n".join(x for x in (persona, anti) if x.strip())
        sections = [stable, state, tools, hints, memo, language, boundaries]
    elif arch == "state_first":
        sections = [state, persona, tools, hints, anti, memo, language, boundaries]
    else:  # compact
        sections = [persona, state, tools, hints, anti, memo, language, boundaries]
    return "\n\n".join(section for section in sections if section and section.strip()).strip()


def _history_messages(conv: Conversation, mode: str) -> list[dict[str, str]]:
    messages: list[dict[str, str]] = []
    for turn in conv.turns:
        user = turn.user
        assistant = turn.assistant
        if mode == "labeled":
            user = "用户：" + user
            if assistant:
                assistant = "他：" + assistant
        elif mode == "timestamped":
            user = "[21:00] 用户：" + user
            if assistant:
                assistant = "[21:01] 他：" + assistant
        messages.append({"role": "user", "content": user})
        if assistant:
            messages.append({"role": "assistant", "content": assistant})
    return messages


def run_conversation(scenario: dict[str, Any], provider: OpenAICompatibleProvider,
                     profile: dict[str, str], *, max_tool_rounds: int = 3, max_tokens: int = 500) -> Conversation:
    conv = Conversation(scenario=scenario, variant=None)  # type: ignore[arg-type]
    system_base = build_system(scenario, profile)
    turns = [str(t) for t in scenario.get("turns") or []]
    for user_text in turns:
        conv.turns.append(TurnRecord(user=user_text, assistant=""))
        messages: list[dict[str, str]] = [{"role": "system", "content": system_base}]
        prefix = conv.turns[:-1]
        tmp = Conversation(scenario=scenario, variant=None)  # type: ignore[arg-type]
        tmp.turns = prefix
        messages.extend(_history_messages(tmp, profile["history"]))
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


def evaluate(scenario: dict[str, Any], profile: dict[str, str], provider: OpenAICompatibleProvider,
             judge_provider: OpenAICompatibleProvider) -> dict[str, Any]:
    started = time.monotonic()
    try:
        conv = run_conversation(scenario, provider, profile)
        det = score_conversation(conv)
        judged = judge(judge_provider, scenario, conversation_text(conv))
        dims = judged.get("dimensions") or {}
        judge_score = round(statistics.mean(dims.values()) * 20, 1) if dims else None
        combined = round(0.6 * judge_score + 0.4 * det["deterministic_score"], 1) if judge_score is not None else None
        if combined is not None and det["hard_failures"]:
            combined = min(combined, 60.0)
        return {"scenario": scenario["id"], "profile": profile, "judge": dims, "judge_score": judge_score,
                "deterministic": det["deterministic_score"], "combined": combined,
                "hard_failures": det["hard_failures"], "chars": det["chars"], "tool_calls": det["tool_calls"],
                "error": judged.get("error"), "elapsed_ms": int((time.monotonic() - started) * 1000)}
    except Exception as error:  # noqa: BLE001
        return {"scenario": scenario["id"], "profile": profile, "judge": {}, "judge_score": None,
                "deterministic": 0.0, "combined": 0.0, "hard_failures": ["provider"], "chars": 0,
                "tool_calls": [], "error": str(error)[:240], "elapsed_ms": int((time.monotonic() - started) * 1000)}


def summarize(name: str, rows: list[dict[str, Any]], profile: dict[str, str]) -> dict[str, Any]:
    valid = [r for r in rows if r["combined"] is not None]
    return {"name": name, "profile": profile, "n": len(rows),
            "combined": round(statistics.mean([r["combined"] for r in valid]), 1) if valid else 0.0,
            "judge": round(statistics.mean([r["judge_score"] for r in valid]), 1) if valid else 0.0,
            "deterministic": round(statistics.mean([r["deterministic"] for r in rows]), 1),
            "hard_rate": round(100.0 * sum(1 for r in rows if r["hard_failures"]) / max(1, len(rows)), 1),
            "dims": {k: round(statistics.mean([r["judge"][k] for r in valid]), 2) for k in JUDGE_KEYS if valid},
            "rows": rows}


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python3 -m eval.context_search")
    parser.add_argument("--stage", choices=["axes", "combine"], default="axes")
    parser.add_argument("--axis", default=None, help="逗号分隔；默认全部")
    parser.add_argument("--profiles", default=None, help="JSON 文件：[{name, profile}]")
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--base-url", default=os.environ.get("CAMPUS_BASE_URL", "http://127.0.0.1:11435/v1"))
    parser.add_argument("--model", default=os.environ.get("CAMPUS_MODEL", "campus-chat"))
    parser.add_argument("--api-key", default=None)
    parser.add_argument("--max-tokens", type=int, default=4000)
    parser.add_argument("--reasoning-effort", default=os.environ.get("CAMPUS_REASONING", "max"))
    parser.add_argument("--timeout", type=float, default=120.0)
    args = parser.parse_args(list(argv) if argv is not None else None)
    key = args.api_key or os.environ.get("CAMPUS_API_KEY") or os.environ.get("PROXY_API_KEY")
    if not key:
        raise SystemExit("缺少 key")
    scenarios = json.loads(SCENARIOS.read_text(encoding="utf-8"))
    provider = OpenAICompatibleProvider(base_url=args.base_url, api_key=key, model=args.model,
                                        temperature=0.75, max_tokens=args.max_tokens, timeout=args.timeout, retries=2,
                                        reasoning_effort=args.reasoning_effort)
    judge_provider = OpenAICompatibleProvider(base_url=args.base_url, api_key=key, model=args.model,
                                              temperature=0.1, max_tokens=max(2000, args.max_tokens), timeout=args.timeout, retries=2,
                                              reasoning_effort=args.reasoning_effort)
    profiles: list[tuple[str, dict[str, str]]] = []
    if args.stage == "combine":
        if not args.profiles:
            raise SystemExit("combine 需要 --profiles")
        for item in json.loads(Path(args.profiles).read_text(encoding="utf-8")):
            profiles.append((item["name"], item["profile"]))
    else:
        axes = (args.axis or ",".join(PROFILE_AXES)).split(",")
        for axis in axes:
            if axis not in PROFILE_AXES:
                raise SystemExit(f"未知维度：{axis}")
            for value in PROFILE_AXES[axis]:
                profile = dict(BASE_PROFILE)
                profile[axis] = value
                profiles.append((f"{axis}={value}", profile))
    jobs = [(name, profile, scenario, sample) for name, profile in profiles
            for scenario in scenarios for sample in range(args.samples)]
    results: list[Any] = [None] * len(jobs)
    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = {pool.submit(evaluate, scenario, profile, provider, judge_provider): i
                   for i, (name, profile, scenario, sample) in enumerate(jobs)}
        done = 0
        for future in concurrent.futures.as_completed(futures):
            index = futures[future]
            results[index] = future.result()
            done += 1
            row = results[index]
            print(f"[{done:>3}/{len(jobs)}] {jobs[index][0]:<28} {row['scenario']:<22} "
                  f"score={row['combined']} hard={len(row['hard_failures'])}", file=sys.stderr)
    grouped: dict[str, list[dict[str, Any]]] = {}
    profile_by_name: dict[str, dict[str, str]] = {}
    for (name, profile, _scenario, _sample), row in zip(jobs, results):
        grouped.setdefault(name, []).append(row)
        profile_by_name[name] = profile
    summaries = [summarize(name, rows, profile_by_name[name]) for name, rows in grouped.items()]
    summaries.sort(key=lambda s: -s["combined"])
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = REPORT_DIR / f"context-search-{args.stage}-{stamp}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"meta": {"generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
                                        "model": args.model, "samples": args.samples,
                                        "elapsed_ms": int((time.monotonic() - started) * 1000)},
                               "summaries": summaries}, ensure_ascii=False, indent=2), encoding="utf-8")
    md = [f"# 上下文工程参数搜索（{args.stage}）", "",
          f"- campus-chat；12 个多轮场景；samples={args.samples}", "",
          "| 方案 | 综合分 | Judge | 确定性 | 硬失败率 | " + " | ".join(JUDGE_KEYS) + " |",
          "|" + "---|" * (4 + len(JUDGE_KEYS))]
    for s in summaries:
        md.append(f"| {s['name']} | **{s['combined']:.1f}** | {s['judge']:.1f} | {s['deterministic']:.1f} | "
                  f"{s['hard_rate']:.1f}% | " + " | ".join(f"{s['dims'].get(k, 0):.2f}" for k in JUDGE_KEYS) + " |")
    md += ["", "## 最优 profile", "", "```json", json.dumps(summaries[0]["profile"], ensure_ascii=False, indent=2), "```"]
    md_path = out.with_suffix(".md")
    md_path.write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"JSON: {out}\nMarkdown: {md_path}")
    print("\n".join(md[:40]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
