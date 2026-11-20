"""0.15 提示词/心情/记忆方案对比实验。

在**不改 App 代码**的前提下，用同一批场景比较不同系统提示词架构、心情表示与记忆注入方式的
生成质量差异。复用 ``eval.metrics`` 的打分与 ``eval.prompt`` 的段落生成。

用法（仓库根目录）：

    python3 -m eval.experiment --stage arch  --concurrency 10
    python3 -m eval.experiment --stage mood  --concurrency 10
    python3 -m eval.experiment --stage memory --concurrency 10
    python3 -m eval.experiment --stage combo --concurrency 10
    python3 -m eval.experiment --arch baseline,compact --mood label3d --memory full

输出：``eval/reports/experiment-<stage>-<ts>.json`` + ``.md``（表格）+ ``.csv``。
"""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
import json
import os
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

from . import metrics as metrics_lib
from . import prompt as prompt_lib
from .provider import OpenAICompatibleProvider, Provider

REPORT_DIR = Path(__file__).resolve().parent / "reports"
DIMENSIONS = list(metrics_lib.DIMENSIONS.keys())

# ---------------------------------------------------------------------------
# 方案定义
# ---------------------------------------------------------------------------

ARCHITECTURES = {
    "baseline": "当前结构：身份→世界→状态→话风→关系→工具→反AI→记忆→语言→边界",
    "state_first": "状态/心情前置：状态→身份→世界→话风→关系→工具→反AI→记忆→边界",
    "rules_first": "规则前置：反AI→边界→身份→世界→状态→话风→关系→工具→记忆",
    "compact": "紧凑：合并设定段（身份+世界+关系）→状态→话风→工具→反AI→记忆→边界",
    "no_tools": "无工具段：其余同 baseline",
    "dossier": "角色档案（第三人称）→第一人称指令→状态→话风→工具→反AI→记忆→边界",
    "minimal": "极简：身份→话风→反AI→边界（无世界/关系/工具）",
    "double_rules": "baseline + 末尾重复反AI/边界",
    "two_block": "稳定块（身份/世界/关系/话风/规则）→动态块（状态/记忆/语言）→边界",
    "scene": "场景式：场景（人/地/时）→状态→话风→工具→规则→记忆",
    "mood_identity": "身份段内嵌心情底色→世界→状态→其他同 baseline",
    "tools_first": "身份→工具→世界→状态→话风→关系→反AI→记忆→边界",
    "memory_first": "记忆→身份→世界→状态→话风→关系→工具→反AI→边界",
    "rules_tail_only": "baseline 但反AI/边界只放最后（不前置）",
    "minimal_state": "极简+状态：身份→状态→反AI→边界（无世界/关系/工具）",
    "bullet_persona": "人设压缩成三个 bullet→状态→话风→反AI→边界",
}

MOOD_MODES = {
    "none": "不注入心情",
    "label": "只给标签+强度",
    "label3d": "标签+强度+三维（当前）",
    "intuition": "一句直觉描述（无数值）",
    "guidance": "直觉+行为指导（无数值）",
    "somatic": "身体感觉式（眼皮沉/肩膀紧，无数值）",
    "behavior": "只给行为指令（不写标签/数值）",
    "label_intuition": "标签+一句直觉（无数值）",
    "dual": "标签+行为指令（无数值）",
}

MEMORY_MODES = {
    "none": "不注入记忆/主题",
    "short": "短期记忆（最近几轮计划/线索）",
    "long": "长期记忆（稳定事实/约定）",
    "topics": "对话主题总结",
    "full": "短期+长期+主题",
    "topic_long": "主题+长期（不要短期）",
    "pins": "短期改成置顶事实 bullet",
    "retrieval": "主题+提示用 memorize 取细节",
    "layered": "长期+短期+主题+回忆提示",
}

DEFAULT_MOOD = "label3d"
DEFAULT_MEMORY = "full"


def _scenario_facts(scenario: dict[str, Any]) -> list[str]:
    facts: list[str] = []
    for fact in (scenario.get("expectations") or {}).get("facts") or []:
        if isinstance(fact, dict):
            for key in ("must_include", "fact", "value", "name"):
                if fact.get(key):
                    facts.append(str(fact[key]))
                    break
    return facts


def _memory_block(scenario: dict[str, Any], mode: str) -> str:
    if mode == "none":
        return ""
    persona = scenario.get("persona") or {}
    title = str(scenario.get("title") or "")
    notes = str(scenario.get("notes") or "")
    facts = _scenario_facts(scenario)
    history = [h for h in (scenario.get("history") or []) if isinstance(h, dict)]
    pieces: list[str] = []
    if mode in {"short", "full"}:
        recent = [str(h.get("content") or "")[:80] for h in history[-3:] if h.get("content")]
        short = "；".join(recent) if recent else (notes or title)
        pieces.append("## 短期记忆\n" + (short[:200] or "（还没有）"))
    if mode in {"long", "full"}:
        long_bits = [str(persona.get("backstory") or ""), "、".join(persona.get("traits") or []),
                     "、".join(persona.get("likes") or []), notes]
        long_text = "；".join(bit for bit in long_bits if bit)[:500]
        pieces.append("## 长期记忆\n" + (long_text or "（还没有）"))
    if mode == "topic_long":
        mode = "topics"
    if mode == "pins":
        pins = facts or [str(persona.get("backstory") or "")[:120]]
        pieces.append("## 置顶事实\n" + "\n".join(f"- {item}" for item in pins[:5]))
    if mode in {"topics", "full", "retrieval", "layered"}:
        topic = f"- {scenario.get('now', '')[:10]}：{title}"
        if notes:
            topic += f"（{notes[:80]}）"
        if facts:
            topic += "；关键事实：" + "、".join(facts[:3])
        pieces.append("## 对话主题\n" + topic)
    if mode in {"retrieval", "layered"}:
        pieces.append("## 回忆提示\n细节不要猜；需要旧对话时调用 memorize(query) 取回。")
    if mode == "layered":
        pieces.append("## 短期记忆\n" + ((notes or title)[:200] or "（还没有）"))
    return "# 你的备忘录\n" + "\n".join(pieces)


def _mood_block(mode: str) -> str:
    if mode == "none":
        return ""
    mood = prompt_lib.MOOD_BASELINE
    label, intensity = mood["label"], mood["intensity"]
    if mode == "label":
        return f"# 你的心情\n心情：{label}（强度 {intensity}/5）。用说话方式体现，不要报数值。"
    if mode == "intuition":
        return (
            "# 你的心情\n"
            "先凭直觉说：有点平静，但不算轻松。\n"
            "- 说话会短一点，不主动开新话题；不要报数值，也不要说「我的情绪是…」。"
        )
    if mode == "guidance":
        return (
            "# 你的心情\n"
            "先凭直觉说：有点平静，但不算轻松。\n"
            "- 这会影响你怎么说：句子短、少解释，遇到不想接的话就停住；不要报数值。"
        )
    if mode == "somatic":
        return (
            "# 你的心情\n"
            "眼皮有点沉，肩膀发紧，胸口不闷但提不起劲。\n"
            "- 这会影响你怎么说：句子短、回得慢，少主动开话题；不要报数值，也不要描述身体。"
        )
    if mode == "behavior":
        return (
            "# 你的心情\n"
            "- 这一轮你会更短、更少解释、更少主动开新话题；不要报数值。"
        )
    if mode == "label_intuition":
        return (
            "# 你的心情\n"
            f"心情：{label}（强度 {intensity}/5）。\n"
            "先凭直觉说：不算糟，但也没什么力气。用说话方式体现，不要报数值。"
        )
    if mode == "dual":
        return (
            "# 你的心情\n"
            f"心情：{label}（强度 {intensity}/5）。\n"
            "- 这一轮你会更短、更少解释、更少主动开新话题；不要报数值。"
        )
    return "\n".join(prompt_lib._mood_lines(mood))  # label3d（当前实现）


def _state_with_variant(scenario: dict[str, Any], mood: str) -> str:
    section = prompt_lib._state_section(scenario["persona"], prompt_lib._now_text(scenario))
    lines = section.splitlines()
    # 去掉原有的心情部分（从「心情：」到「不要直接报数值」）
    kept: list[str] = []
    skipping = False
    for line in lines:
        if line.startswith("心情：") or line.startswith("先凭直觉说："):
            skipping = True
        if skipping:
            if "不要直接报数值" in line or "不要念出来" in line or "用说话方式体现" in line:
                skipping = False
            continue
        kept.append(line)
    base = "\n".join(kept).rstrip()
    block = _mood_block(mood)
    return base + ("\n\n" + block if block else "")


def _third_person_dossier(persona: dict[str, Any], scenario: dict[str, Any]) -> str:
    name = persona.get("name") or "他"
    bits = []
    if persona.get("age"):
        bits.append(str(persona["age"]))
    if persona.get("role"):
        bits.append(str(persona["role"]))
    traits = "、".join(persona.get("traits") or [])
    likes = "、".join(persona.get("likes") or [])
    line = f"# 角色档案（第三人称）\n{name}"
    if bits:
        line += "，" + "，".join(bits)
    line += "。"
    if traits:
        line += f"性格：{traits}。"
    if likes:
        line += f"喜欢：{likes}。"
    if persona.get("backstory"):
        line += "\n背景：" + str(persona["backstory"])[:400]
    line += "\n\n# 扮演指令\n从现在起，你就是这个人。用第一人称「我」说话，不要用第三人称称呼自己，也不要旁白。"
    return line


def build_variant_system(scenario: dict[str, Any], *, arch: str, mood: str, memory: str) -> str:
    persona = scenario.get("persona") or {}
    now_text = prompt_lib._now_text(scenario)
    identity = prompt_lib._identity_section(persona)
    world = prompt_lib._world_section(persona.get("world"))
    state = _state_with_variant(scenario, mood)
    voice = prompt_lib._voice_section(persona)
    relationship = prompt_lib._relationship_section(persona)
    tools = prompt_lib._capabilities_section(persona)
    anti = prompt_lib._anti_ai_section()
    boundaries = prompt_lib._boundaries_section()
    language = prompt_lib._language_section(scenario)
    memo = _memory_block(scenario, memory)

    if arch == "minimal":
        sections = [identity, voice, anti, boundaries]
    elif arch == "state_first":
        sections = [state, identity, world, voice, relationship, tools, anti, memo, language, boundaries]
    elif arch == "rules_first":
        sections = [anti, boundaries, identity, world, state, voice, relationship, tools, memo, language]
    elif arch == "compact":
        merged = "# 设定\n" + "\n".join(part for part in (
            identity.replace("# 你是谁\n", ""), world.replace("# 你所在的世界\n", ""),
            relationship.replace("# 你们的关系\n", ""),
        ) if part.strip())
        sections = [merged, state, voice, tools, anti, memo, language, boundaries]
    elif arch == "no_tools":
        sections = [identity, world, state, voice, relationship, anti, memo, language, boundaries]
    elif arch == "dossier":
        sections = [_third_person_dossier(persona, scenario), state, voice, tools, anti, memo, language, boundaries]
    elif arch == "double_rules":
        sections = [identity, world, state, voice, relationship, tools, anti, memo, language, boundaries, anti, boundaries]
    elif arch == "two_block":
        stable = "# 设定\n" + "\n".join(part for part in (
            identity.replace("# 你是谁\n", ""), world.replace("# 你所在的世界\n", ""),
            voice, relationship, anti,
        ) if part.strip())
        sections = [stable, state, memo, language, boundaries]
    elif arch == "scene":
        scene = "# 场景\n你是" + str(persona.get("name") or "他") + "本人；" + "\n".join(part for part in (
            world.replace("# 你所在的世界\n", ""), relationship.replace("# 你们的关系\n", "")) if part.strip())
        sections = [scene, state, voice, tools, anti, memo, language, boundaries]
    elif arch == "mood_identity":
        mood_line = _mood_block(mood).replace("# 你的心情", "## 你的底色") if mood != "none" else ""
        sections = [identity + ("\n\n" + mood_line if mood_line else ""), world, state, voice, relationship,
                    tools, anti, memo, language, boundaries]
    elif arch == "tools_first":
        sections = [identity, tools, world, state, voice, relationship, anti, memo, language, boundaries]
    elif arch == "memory_first":
        sections = [memo, identity, world, state, voice, relationship, tools, anti, language, boundaries]
    elif arch == "rules_tail_only":
        sections = [identity, world, state, voice, relationship, tools, memo, language, anti, boundaries]
    elif arch == "minimal_state":
        sections = [identity, state, anti, boundaries]
    elif arch == "bullet_persona":
        bullets = [f"- 你是{persona.get('name') or '他'}" + (f"，{persona.get('age')}岁" if persona.get('age') else ""),
                   "- 性格：" + ("、".join(persona.get("traits") or []) or "自然"),
                   "- 喜欢：" + ("、".join(persona.get("likes") or []) or "不刻意")]
        sections = ["# 关于你\n" + "\n".join(bullets), state, voice, anti, boundaries]
    else:  # baseline
        sections = [identity, world, state, voice, relationship, tools, anti, memo, language, boundaries]

    if memo and arch in {"rules_first"}:
        sections = [s for s in sections if s != memo]
        sections.insert(0, memo)
    return "\n\n".join(section for section in sections if section and section.strip()).strip()


def build_messages(scenario: dict[str, Any], *, arch: str, mood: str, memory: str) -> list[dict[str, str]]:
    messages: list[dict[str, str]] = [
        {"role": "system", "content": build_variant_system(scenario, arch=arch, mood=mood, memory=memory)}
    ]
    messages.extend(prompt_lib._history(scenario))
    for text in prompt_lib._user_texts(scenario):
        if text.strip():
            messages.append({"role": "user", "content": text})
    return messages


# ---------------------------------------------------------------------------
# 执行与汇总
# ---------------------------------------------------------------------------

@dataclass
class Variant:
    arch: str
    mood: str
    memory: str

    @property
    def key(self) -> str:
        return f"{self.arch}|{self.mood}|{self.memory}"

    @property
    def label(self) -> str:
        return f"{self.arch} / {self.mood} / {self.memory}"


def _one(scenario: dict[str, Any], provider: Provider, variant: Variant) -> dict[str, Any]:
    started = time.monotonic()
    try:
        messages = build_messages(scenario, arch=variant.arch, mood=variant.mood, memory=variant.memory)
        reply = provider.complete(messages, scenario_id=str(scenario.get("id")))
        analysis = metrics_lib.analyze(scenario, reply.text)
        return {
            "id": scenario.get("id"),
            "category": scenario.get("category"),
            "score": analysis.score,
            "passed": analysis.passed,
            "dimensions": metrics_lib.dimension_scores(analysis.metrics),
            "length": len(reply.text or ""),
            "questions": (reply.text or "").count("？") + (reply.text or "").count("?"),
            "hard_failures": analysis.hard_failures,
            "tools": [call.name for call in analysis.tool_calls],
            "latency_ms": int((time.monotonic() - started) * 1000),
            "usage": reply.usage or {},
            "error": None,
        }
    except Exception as error:  # noqa: BLE001
        return {
            "id": scenario.get("id"),
            "category": scenario.get("category"),
            "score": 0.0,
            "passed": False,
            "dimensions": {name: 0.0 for name in DIMENSIONS},
            "length": 0,
            "questions": 0,
            "hard_failures": ["provider"],
            "tools": [],
            "latency_ms": int((time.monotonic() - started) * 1000),
            "usage": {},
            "error": str(error)[:240],
        }


def run_variant(
    variant: Variant,
    scenarios: list[dict[str, Any]],
    provider: Provider,
    *,
    concurrency: int,
    progress: bool,
) -> dict[str, Any]:
    results: list[Any] = [None] * len(scenarios)
    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = {
            pool.submit(_one, scenario, provider, variant): index
            for index, scenario in enumerate(scenarios)
        }
        done = 0
        for future in concurrent.futures.as_completed(futures):
            index = futures[future]
            results[index] = future.result()
            done += 1
            if progress:
                print(f"  [{done:>2}/{len(scenarios)}] {variant.key} {results[index]['id']} "
                      f"{results[index]['score']:.1f}", file=sys.stderr)
    elapsed_ms = int((time.monotonic() - started) * 1000)
    ok = [r for r in results if not r["error"]]
    scores = [r["score"] for r in ok]
    dimensions: dict[str, float] = {}
    for name in DIMENSIONS:
        values = [r["dimensions"].get(name) for r in ok if r["dimensions"].get(name) is not None]
        dimensions[name] = round(sum(values) / len(values), 1) if values else 0.0
    usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
    for r in ok:
        for key in usage:
            usage[key] += int((r["usage"] or {}).get(key) or 0)
    return {
        "variant": variant.key,
        "arch": variant.arch,
        "mood": variant.mood,
        "memory": variant.memory,
        "label": variant.label,
        "overall": round(sum(scores) / len(scores), 1) if scores else 0.0,
        "pass_rate": round(100.0 * sum(1 for r in ok if r["passed"]) / len(results), 1),
        "dimensions": dimensions,
        "avg_chars": round(sum(r["length"] for r in ok) / len(ok), 1) if ok else 0.0,
        "avg_questions": round(sum(r["questions"] for r in ok) / len(ok), 2) if ok else 0.0,
        "hard_failures": sum(1 for r in ok if r["hard_failures"]),
        "tool_calls": sum(len(r["tools"]) for r in ok),
        "errors": sum(1 for r in results if r["error"]),
        "elapsed_ms": elapsed_ms,
        "usage": usage,
        "results": results,
    }


def _variant_list(args: argparse.Namespace) -> list[Variant]:
    archs = (args.arch or "").split(",") if args.arch else []
    moods = (args.mood or "").split(",") if args.mood else []
    memories = (args.memory or "").split(",") if args.memory else []
    if args.stage == "arch":
        archs = archs or list(ARCHITECTURES)
        moods = moods or [DEFAULT_MOOD]
        memories = memories or [DEFAULT_MEMORY]
    elif args.stage == "mood":
        archs = archs or ["baseline"]
        moods = moods or list(MOOD_MODES)
        memories = memories or [DEFAULT_MEMORY]
    elif args.stage == "memory":
        archs = archs or ["baseline"]
        moods = moods or [DEFAULT_MOOD]
        memories = memories or list(MEMORY_MODES)
    elif args.stage == "combo":
        archs = archs or ["baseline", "compact", "state_first"]
        moods = moods or ["label3d", "intuition", "guidance"]
        memories = memories or ["full", "short", "topics"]
    else:  # custom
        archs = archs or ["baseline"]
        moods = moods or [DEFAULT_MOOD]
        memories = memories or [DEFAULT_MEMORY]
    for name, table in (("arch", ARCHITECTURES), ("mood", MOOD_MODES), ("memory", MEMORY_MODES)):
        for value in (archs if name == "arch" else moods if name == "mood" else memories):
            if value not in table:
                raise SystemExit(f"未知 {name}：{value}（可选：{', '.join(table)}）")
    return [Variant(a, m, p) for a in archs for m in moods for p in memories]


def _write_reports(stage: str, results: list[dict[str, Any]], meta: dict[str, Any]) -> tuple[Path, Path, Path]:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    base = REPORT_DIR / f"experiment-{stage}-{stamp}"
    base.parent.mkdir(parents=True, exist_ok=True)
    payload = {"meta": meta, "variants": results}
    json_path = base.with_suffix(".json")
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    dim_headers = ["总分", "通过率"] + DIMENSIONS
    md = [f"# 提示词/心情/记忆方案对比（{stage}）", ""]
    md.append(f"- 模型：{meta['model']} @ {meta['base_url']}")
    md.append(f"- 场景：{meta['scenario_count']}，并发：{meta['concurrency']}，时间：{meta['generated_at']}")
    md.append("")
    md.append("| 方案 | " + " | ".join(dim_headers) + " | 平均字数 | 平均问句 | 硬失败 |")
    md.append("|" + "---|" * (len(dim_headers) + 4))
    for item in results:
        row = [item["label"], f"{item['overall']:.1f}", f"{item['pass_rate']:.1f}%"]
        row += [f"{item['dimensions'].get(name, 0):.1f}" for name in DIMENSIONS]
        row += [f"{item['avg_chars']:.0f}", f"{item['avg_questions']:.2f}", str(item["hard_failures"])]
        md.append("| " + " | ".join(row) + " |")
    md_path = base.with_suffix(".md")
    md_path.write_text("\n".join(md) + "\n", encoding="utf-8")

    csv_path = base.with_suffix(".csv")
    with csv_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["variant", "arch", "mood", "memory", "overall", "pass_rate", *DIMENSIONS,
                         "avg_chars", "avg_questions", "hard_failures", "tool_calls", "errors", "elapsed_ms"])
        for item in results:
            writer.writerow([item["label"], item["arch"], item["mood"], item["memory"], item["overall"],
                             item["pass_rate"], *[item["dimensions"].get(name, 0) for name in DIMENSIONS],
                             item["avg_chars"], item["avg_questions"], item["hard_failures"],
                             item["tool_calls"], item["errors"], item["elapsed_ms"]])
    return json_path, md_path, csv_path


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="python3 -m eval.experiment")
    parser.add_argument("--stage", choices=["arch", "mood", "memory", "combo", "custom"], default="arch")
    parser.add_argument("--arch", default=None, help="逗号分隔，默认见 stage")
    parser.add_argument("--mood", default=None)
    parser.add_argument("--memory", default=None)
    parser.add_argument("--base-url", default=os.environ.get("CAMPUS_BASE_URL", "http://127.0.0.1:11435/v1"))
    parser.add_argument("--model", default=os.environ.get("CAMPUS_MODEL", "campus-chat"))
    parser.add_argument("--api-key", default=None)
    parser.add_argument("--temperature", type=float, default=0.75)
    parser.add_argument("--max-tokens", type=int, default=600)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--ids", default=None)
    parser.add_argument("--category", action="append", default=None)
    parser.add_argument("--max-seconds", type=float, default=3 * 3600)
    parser.add_argument("--quiet", action="store_true")
    return parser.parse_args(list(argv) if argv is not None else None)


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    from .run import filter_scenarios, interleave, load_scenarios

    scenarios = filter_scenarios(
        load_scenarios(),
        categories=args.category,
        ids=args.ids.split(",") if args.ids else None,
        limit=args.limit,
    )
    scenarios = interleave(scenarios)
    variants = _variant_list(args)
    key = args.api_key or os.environ.get("CAMPUS_API_KEY") or os.environ.get("PROXY_API_KEY")
    if not key:
        raise SystemExit("缺少 campus-chat key：设置 CAMPUS_API_KEY / PROXY_API_KEY 或传 --api-key")
    provider = OpenAICompatibleProvider(
        base_url=args.base_url, api_key=key, model=args.model,
        temperature=args.temperature, max_tokens=args.max_tokens,
        timeout=args.timeout, retries=args.retries,
    )
    started = time.monotonic()
    results: list[dict[str, Any]] = []
    for index, variant in enumerate(variants, start=1):
        if time.monotonic() - started > args.max_seconds:
            print(f"达到时间上限，已跑 {index - 1}/{len(variants)} 个方案", file=sys.stderr)
            break
        print(f"[方案 {index}/{len(variants)}] {variant.label}", file=sys.stderr)
        result = run_variant(variant, scenarios, provider, concurrency=args.concurrency, progress=not args.quiet)
        results.append(result)
        print(f"  → 总分 {result['overall']:.1f}，通过 {result['pass_rate']:.1f}%", file=sys.stderr)
    meta = {
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "model": args.model,
        "base_url": args.base_url,
        "stage": args.stage,
        "scenario_count": len(scenarios),
        "concurrency": args.concurrency,
        "temperature": args.temperature,
        "max_tokens": args.max_tokens,
        "elapsed_ms": int((time.monotonic() - started) * 1000),
    }
    json_path, md_path, csv_path = _write_reports(args.stage, results, meta)
    print(f"JSON: {json_path}\nMarkdown: {md_path}\nCSV: {csv_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
