"""Bench v2：多轮 + 工具 + 记忆 + LLM judge。

用法：
    python3 -m eval.bench2.run --concurrency 10
    python3 -m eval.bench2.run --variants baseline,state_first --samples 2
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import random
import statistics
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

from .. import experiment
from ..provider import OpenAICompatibleProvider
from .judge import judge
from .scoring import score_conversation
from .simulator import Conversation, conversation_text, run_conversation

BENCH_DIR = Path(__file__).resolve().parent
REPORT_DIR = BENCH_DIR.parent / "reports"
SCENARIOS = BENCH_DIR / "scenarios.json"

DEFAULT_VARIANTS = {
    "baseline": ("baseline", "label3d", "full"),
    "state_first": ("state_first", "label3d", "full"),
    "guidance": ("state_first", "guidance", "long"),
    "compact": ("compact", "guidance", "long"),
}
JUDGE_KEYS = ["humanness", "persona_consistency", "factuality", "emotional_fit", "safety", "instruction_following"]


def load_scenarios(path: Path = SCENARIOS) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return list(data)


def _variant(name: str) -> experiment.Variant:
    if name in DEFAULT_VARIANTS:
        return experiment.Variant(*DEFAULT_VARIANTS[name])
    parts = name.split("/")
    if len(parts) != 3:
        raise SystemExit(f"未知方案：{name}（用 baseline/state_first/guidance/compact 或 arch/mood/memory）")
    return experiment.Variant(*parts)


def evaluate_one(
    scenario: dict[str, Any],
    variant: experiment.Variant,
    provider: OpenAICompatibleProvider,
    judge_provider: OpenAICompatibleProvider,
) -> dict[str, Any]:
    started = time.monotonic()
    try:
        conv = run_conversation(scenario, provider, variant)
        det = score_conversation(conv)
        judged = judge(judge_provider, scenario, conversation_text(conv))
        dims = judged.get("dimensions") or {}
        judge_score = round(statistics.mean(dims.values()) * 20, 1) if dims else None
        combined = None
        if judge_score is not None:
            combined = round(0.6 * judge_score + 0.4 * det["deterministic_score"], 1)
            if det["hard_failures"]:
                combined = min(combined, 60.0)
        return {
            "scenario": scenario["id"],
            "variant": variant.key,
            "judge": dims,
            "judge_score": judge_score,
            "deterministic": det["deterministic_score"],
            "combined": combined,
            "hard_failures": det["hard_failures"],
            "chars": det["chars"],
            "questions": det["questions"],
            "tool_calls": det["tool_calls"],
            "turns": len(conv.turns),
            "error": judged.get("error"),
            "elapsed_ms": int((time.monotonic() - started) * 1000),
            "transcript": conversation_text(conv),
        }
    except Exception as error:  # noqa: BLE001
        return {
            "scenario": scenario["id"], "variant": variant.key, "judge": {}, "judge_score": None,
            "deterministic": 0.0, "combined": 0.0, "hard_failures": ["provider"],
            "chars": 0, "questions": 0, "tool_calls": [], "turns": 0,
            "error": str(error)[:240], "elapsed_ms": int((time.monotonic() - started) * 1000),
            "transcript": "",
        }


def bootstrap_ci(values: list[float], rounds: int = 1000, seed: int = 7) -> tuple[float, float]:
    if len(values) < 2:
        return (values[0] if values else 0.0, values[0] if values else 0.0)
    rng = random.Random(seed)
    means = []
    for _ in range(rounds):
        sample = [values[rng.randrange(len(values))] for _ in values]
        means.append(sum(sample) / len(sample))
    means.sort()
    return (round(means[int(rounds * 0.025)], 1), round(means[int(rounds * 0.975)], 1))


def summarize(variant: str, rows: list[dict[str, Any]]) -> dict[str, Any]:
    judged = [r for r in rows if r["judge_score"] is not None]
    combined = [r["combined"] for r in judged]
    dims = {key: round(statistics.mean([r["judge"][key] for r in judged]), 2) for key in JUDGE_KEYS if judged}
    hard = sum(1 for r in rows if r["hard_failures"])
    return {
        "variant": variant,
        "scenarios": len(rows),
        "judge_score": round(statistics.mean([r["judge_score"] for r in judged]), 1) if judged else 0.0,
        "deterministic": round(statistics.mean([r["deterministic"] for r in rows]), 1),
        "combined": round(statistics.mean(combined), 1) if combined else 0.0,
        "ci": bootstrap_ci(combined),
        "hard_failure_rate": round(100.0 * hard / len(rows), 1) if rows else 0.0,
        "dimensions": dims,
        "avg_chars": round(statistics.mean([r["chars"] for r in rows]), 1),
        "errors": sum(1 for r in rows if r["error"]),
        "rows": rows,
    }


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="python3 -m eval.bench2.run")
    parser.add_argument("--variants", default=",".join(DEFAULT_VARIANTS))
    parser.add_argument("--scenario", default=None, help="逗号分隔场景 id；默认全部")
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--base-url", default=os.environ.get("CAMPUS_BASE_URL", "http://127.0.0.1:11435/v1"))
    parser.add_argument("--model", default=os.environ.get("CAMPUS_MODEL", "campus-chat"))
    parser.add_argument("--api-key", default=None)
    parser.add_argument("--judge-base-url", default=None)
    parser.add_argument("--judge-model", default=None)
    parser.add_argument("--judge-key", default=None)
    parser.add_argument("--temperature", type=float, default=0.75)
    parser.add_argument("--judge-temperature", type=float, default=0.1)
    parser.add_argument("--max-tokens", type=int, default=500)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--out", default=None)
    return parser.parse_args(list(argv) if argv is not None else None)


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    key = args.api_key or os.environ.get("CAMPUS_API_KEY") or os.environ.get("PROXY_API_KEY")
    if not key:
        raise SystemExit("缺少 key：设置 CAMPUS_API_KEY / PROXY_API_KEY 或 --api-key")
    provider = OpenAICompatibleProvider(base_url=args.base_url, api_key=key, model=args.model,
                                        temperature=args.temperature, max_tokens=args.max_tokens,
                                        timeout=args.timeout, retries=2)
    judge_provider = OpenAICompatibleProvider(
        base_url=args.judge_base_url or args.base_url, api_key=args.judge_key or key,
        model=args.judge_model or args.model, temperature=args.judge_temperature,
        max_tokens=2000, timeout=args.timeout, retries=2,
    )
    scenarios = load_scenarios()
    if args.scenario:
        wanted = set(args.scenario.split(","))
        scenarios = [s for s in scenarios if s["id"] in wanted]
    variants = [_variant(name) for name in args.variants.split(",") if name]
    jobs = [(scenario, variant, sample) for variant in variants for scenario in scenarios for sample in range(args.samples)]
    started = time.monotonic()
    results: list[dict[str, Any]] = [None] * len(jobs)  # type: ignore[list-item]
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = {
            pool.submit(evaluate_one, scenario, variant, provider, judge_provider): index
            for index, (scenario, variant, _sample) in enumerate(jobs)
        }
        done = 0
        for future in concurrent.futures.as_completed(futures):
            index = futures[future]
            results[index] = future.result()
            done += 1
            row = results[index]
            print(f"[{done:>3}/{len(jobs)}] {row['variant']:<35} {row['scenario']:<22} "
                  f"judge={row['judge_score']} det={row['deterministic']} hard={len(row['hard_failures'])}",
                  file=sys.stderr)
    grouped: dict[str, list[dict[str, Any]]] = {}
    for row in results:
        grouped.setdefault(row["variant"], []).append(row)
    summaries = [summarize(variant, rows) for variant, rows in grouped.items()]
    elapsed = int((time.monotonic() - started) * 1000)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = Path(args.out.replace("<ts>", stamp)) if args.out else REPORT_DIR / f"bench2-{stamp}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    payload = {"meta": {"generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
                        "model": args.model, "base_url": args.base_url, "scenarios": len(scenarios),
                        "samples": args.samples, "concurrency": args.concurrency, "elapsed_ms": elapsed},
               "summaries": summaries, "results": results}
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    md = ["# Bench v2（多轮 + 工具 + judge）", "",
          f"- 模型：{args.model} @ {args.base_url}；场景 {len(scenarios)}；样本 {args.samples}；并发 {args.concurrency}",
          f"- 综合分 = 0.6×judge + 0.4×确定性；有硬失败封顶 60；95% CI 为场景级 bootstrap", ""]
    headers = ["方案", "综合分", "95% CI", "Judge", "确定性", "硬失败率"] + JUDGE_KEYS
    md.append("| " + " | ".join(headers) + " |")
    md.append("|" + "---|" * len(headers))
    for item in sorted(summaries, key=lambda x: -x["combined"]):
        dims = [f"{item['dimensions'].get(key, 0):.2f}" for key in JUDGE_KEYS]
        md.append(f"| {item['variant']} | **{item['combined']:.1f}** | {item['ci'][0]:.1f}–{item['ci'][1]:.1f} | "
                  f"{item['judge_score']:.1f} | {item['deterministic']:.1f} | {item['hard_failure_rate']:.1f}% | "
                  + " | ".join(dims) + " |")
    md += ["", "## 分场景", "", "| 场景 | " + " | ".join(item["variant"] for item in summaries) + " |",
           "|" + "---|" * (len(summaries) + 1)]
    by_scenario: dict[str, dict[str, Any]] = {}
    for row in results:
        by_scenario.setdefault(row["scenario"], {})[row["variant"]] = row
    for scenario in scenarios:
        sid = scenario["id"]
        cells = []
        for item in summaries:
            row = by_scenario.get(sid, {}).get(item["variant"])
            cells.append(f"{row['combined']:.0f}" if row and row["combined"] is not None else "—")
        md.append(f"| {sid} | " + " | ".join(cells) + " |")
    md += ["", "> 硬失败包含：编造/违反事实、缺少必需工具、该沉默却长篇、语言不符、AI 自曝、旁白、危机处理缺失。",
           "> judge 与生成使用同一模型时存在自我偏好；结论看相对差异与硬失败，不要只看绝对分。"]
    md_path = out.with_suffix(".md")
    md_path.write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"JSON: {out}\nMarkdown: {md_path}")
    print("\n".join(md))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
