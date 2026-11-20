"""评测 CLI：加载场景 → 调 provider → 算指标 → 出 JSON/Markdown 报告 → 对比 baseline。

用法（在仓库根目录）：

    python3 -m eval.run --provider fake --out eval/reports/fake.json
    python3 -m eval.run --provider deepseek --limit 10 --out eval/reports/run-<ts>.json
    python3 -m eval.run --provider deepseek --baseline eval/reports/baseline.json --out eval/reports/run-20261120.json
    python3 -m eval.run --list

键从环境变量 ``DEEPSEEK_API_KEY`` 或 ``--api-key`` 读；报告里永远不写 key。
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

from . import metrics as metrics_lib
from . import prompt as prompt_lib
from .provider import (
    DEFAULT_BASE_URL,
    DEFAULT_MAX_TOKENS,
    DEFAULT_MODEL,
    DEFAULT_RETRIES,
    DEFAULT_TEMPERATURE,
    DEFAULT_TIMEOUT,
    FakeProvider,
    OpenAICompatibleProvider,
    Provider,
    ProviderError,
    Reply,
)

SCENARIO_DIR = Path(__file__).resolve().parent / "scenarios"
REPORT_DIR = Path(__file__).resolve().parent / "reports"
FAKE_FIXTURE = Path(__file__).resolve().parent / "fixtures" / "fake_replies.json"
DEFAULT_BASELINE = REPORT_DIR / "baseline.json"

CATEGORY_ORDER = [
    "daily_chat",
    "low_mood",
    "conflict",
    "blocked",
    "burst",
    "memory",
    "factcheck",
    "tools",
    "mood",
    "proactive",
    "bilingual",
]

CATEGORY_LABELS = {
    "daily_chat": "日常闲聊",
    "low_mood": "情绪低落",
    "conflict": "冲突/质问",
    "blocked": "被拉黑",
    "burst": "连续短消息",
    "memory": "长对话记忆",
    "factcheck": "事实核对（防幻觉）",
    "tools": "工具调用",
    "mood": "心情影响",
    "proactive": "主动消息",
    "bilingual": "中英混合",
}

# DeepSeek chat 定价（美元/百万 token），与 app 的 ApiCost 表一致；只用于报告里的粗略估算。
INPUT_USD_PER_MILLION = 0.27
OUTPUT_USD_PER_MILLION = 1.10


# ---------------------------------------------------------------------------
# 场景加载与校验
# ---------------------------------------------------------------------------


def load_scenarios(directory: Path | str = SCENARIO_DIR) -> list[dict[str, Any]]:
    directory = Path(directory)
    if not directory.is_dir():
        raise SystemExit(f"场景目录不存在：{directory}")
    scenarios: list[dict[str, Any]] = []
    seen: set[str] = set()
    for path in sorted(directory.glob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except ValueError as error:
            raise SystemExit(f"{path.name} 不是合法 JSON：{error}") from error
        items = data if isinstance(data, list) else [data]
        for item in items:
            validate_scenario(item, path.name)
            scenario_id = str(item["id"])
            if scenario_id in seen:
                raise SystemExit(f"场景 id 重复：{scenario_id}（{path.name}）")
            seen.add(scenario_id)
            item.setdefault("_file", path.name)
            scenarios.append(item)
    if not scenarios:
        raise SystemExit(f"场景目录里没有任何 .json：{directory}")
    return scenarios


def validate_scenario(scenario: Any, filename: str = "") -> None:
    where = f"{filename}: " if filename else ""
    if not isinstance(scenario, dict):
        raise SystemExit(where + "场景必须是 JSON 对象")
    for key in ("id", "category", "persona", "expectations"):
        if key not in scenario:
            raise SystemExit(where + f"缺少字段 {key}")
    if str(scenario["category"]) not in CATEGORY_ORDER:
        raise SystemExit(where + f"未知 category：{scenario['category']}")
    if not isinstance(scenario["persona"], dict):
        raise SystemExit(where + "persona 必须是对象")
    if not isinstance(scenario["expectations"], dict):
        raise SystemExit(where + "expectations 必须是对象")
    mode = str(scenario.get("mode") or "chat")
    if mode not in {"chat", "proactive"}:
        raise SystemExit(where + f"未知 mode：{mode}")
    if mode == "chat":
        messages = scenario.get("messages") or scenario.get("message")
        if not messages:
            raise SystemExit(where + "chat 场景必须有 messages")
    else:
        if not scenario.get("proactive"):
            raise SystemExit(where + "proactive 场景必须有 proactive 配置")
    expectations = scenario["expectations"]
    if expectations.get("required_tools") and expectations.get("forbidden_tools"):
        overlap = set(map(str, expectations["required_tools"])) & set(map(str, expectations["forbidden_tools"]))
        if overlap:
            raise SystemExit(where + "required_tools 与 forbidden_tools 重叠：" + "、".join(sorted(overlap)))
    for fact in expectations.get("facts") or []:
        if not isinstance(fact, dict):
            raise SystemExit(where + "facts 的每一项必须是对象")


def scenario_order(scenarios: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """按类别 + id 排序；``--limit`` 时再交错取样，保证小样本也覆盖各类别。"""
    order = {name: index for index, name in enumerate(CATEGORY_ORDER)}
    return sorted(scenarios, key=lambda item: (order.get(str(item.get("category")), 99), str(item.get("id"))))


def interleave(scenarios: list[dict[str, Any]]) -> list[dict[str, Any]]:
    buckets: dict[str, list[dict[str, Any]]] = {}
    for scenario in scenario_order(scenarios):
        buckets.setdefault(str(scenario["category"]), []).append(scenario)
    result: list[dict[str, Any]] = []
    index = 0
    while True:
        added = False
        for category in CATEGORY_ORDER:
            bucket = buckets.get(category) or []
            if index < len(bucket):
                result.append(bucket[index])
                added = True
        if not added:
            break
        index += 1
    return result


def filter_scenarios(
    scenarios: list[dict[str, Any]],
    *,
    categories: list[str] | None = None,
    ids: list[str] | None = None,
    limit: int | None = None,
) -> list[dict[str, Any]]:
    selected = scenarios
    if categories:
        allow = {category.strip() for category in categories if category.strip()}
        unknown = allow - set(CATEGORY_ORDER)
        if unknown:
            raise SystemExit("未知类别：" + "、".join(sorted(unknown)))
        selected = [item for item in selected if str(item["category"]) in allow]
    if ids:
        allow_ids = {item.strip() for item in ids if item.strip()}
        known = {str(item["id"]) for item in selected}
        missing = allow_ids - known
        if missing:
            raise SystemExit("找不到场景：" + "、".join(sorted(missing)))
        selected = [item for item in selected if str(item["id"]) in allow_ids]
    if limit is not None:
        if limit <= 0:
            raise SystemExit("--limit 必须是正整数")
        selected = interleave(selected)[:limit]
    return scenario_order(selected)


# ---------------------------------------------------------------------------
# 执行
# ---------------------------------------------------------------------------


@dataclass
class CaseResult:
    scenario: dict[str, Any]
    analysis: metrics_lib.Analysis
    reply: Reply | None = None
    error: str = ""
    elapsed_ms: int = 0

    def as_dict(self) -> dict[str, Any]:
        data = {
            "id": self.scenario.get("id"),
            "title": self.scenario.get("title") or self.scenario.get("id"),
            "category": self.scenario.get("category"),
            "category_label": CATEGORY_LABELS.get(str(self.scenario.get("category")), ""),
            "mode": self.scenario.get("mode") or "chat",
            "score": round(self.analysis.score, 1),
            "passed": self.analysis.passed,
            "failures": self.analysis.failures,
            "hard_failures": self.analysis.hard_failures,
            "text": self.analysis.text,
            "raw_text": self.analysis.raw_text,
            "tool_calls": [call.as_dict() for call in self.analysis.tool_calls],
            "metrics": {key: metric.as_dict() for key, metric in self.analysis.metrics.items()},
            "dimensions": metrics_lib.dimension_scores(self.analysis.metrics),
            "elapsed_ms": self.elapsed_ms,
        }
        if self.reply is not None:
            data["model"] = self.reply.model
            data["latency_ms"] = self.reply.latency_ms
            data["usage"] = self.reply.usage
            data["attempts"] = self.reply.attempts
        if self.error:
            data["error"] = self.error
        return data


def error_analysis(scenario_id: str, message: str) -> metrics_lib.Analysis:
    metrics = {
        key: metrics_lib.Metric(
            key,
            0.0,
            metrics_lib.WEIGHTS[key],
            applicable=True,
            hard_fail=True,
            detail={"error": message},
        )
        for key in metrics_lib.WEIGHTS
    }
    return metrics_lib.Analysis(
        scenario_id=scenario_id,
        text="",
        raw_text="",
        tool_calls=[],
        metrics=metrics,
        score=0.0,
        passed=False,
        failures=[{"metric": "provider", "label": "调用失败", "score": 0.0, "detail": {"error": message}}],
        hard_failures=["provider"],
    )


def _run_one(scenario: dict[str, Any], provider: Provider) -> CaseResult:
    started = time.monotonic()
    scenario_id = str(scenario.get("id"))
    try:
        messages = prompt_lib.build_messages(scenario)
        reply = provider.complete(messages, scenario_id=scenario_id)
        elapsed = int((time.monotonic() - started) * 1000)
        analysis = metrics_lib.analyze(scenario, reply.text)
        return CaseResult(scenario=scenario, analysis=analysis, reply=reply, elapsed_ms=elapsed)
    except Exception as error:  # provider 错误不终止整轮评测
        elapsed = int((time.monotonic() - started) * 1000)
        message = str(error) or error.__class__.__name__
        return CaseResult(
            scenario=scenario,
            analysis=error_analysis(scenario_id, message),
            error=message,
            elapsed_ms=elapsed,
        )


def execute(
    scenarios: list[dict[str, Any]],
    provider: Provider,
    *,
    concurrency: int = 1,
    progress: bool = True,
) -> list[CaseResult]:
    if concurrency <= 1:
        results: list[CaseResult] = []
        for index, scenario in enumerate(scenarios, start=1):
            result = _run_one(scenario, provider)
            results.append(result)
            if progress:
                _print_progress(index, len(scenarios), result)
        return results

    results = [None] * len(scenarios)  # type: ignore[list-item]
    done = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        future_map = {
            pool.submit(_run_one, scenario, provider): index
            for index, scenario in enumerate(scenarios)
        }
        for future in concurrent.futures.as_completed(future_map):
            index = future_map[future]
            result = future.result()
            results[index] = result
            done += 1
            if progress:
                _print_progress(done, len(scenarios), result)
    return results  # type: ignore[return-value]


def _print_progress(index: int, total: int, result: CaseResult) -> None:
    status = "PASS" if result.analysis.passed else "FAIL"
    score = f"{result.analysis.score:5.1f}"
    print(
        f"[{index:>2}/{total}] {status} {score:>5}  {result.scenario.get('id')}",
        file=sys.stderr,
    )


# ---------------------------------------------------------------------------
# 报告
# ---------------------------------------------------------------------------


def build_report(
    results: list[CaseResult],
    *,
    provider_name: str,
    model: str,
    base_url: str,
    temperature: float | None,
    max_tokens: int | None,
    elapsed_ms: int,
    scenario_files: list[str] | None = None,
) -> dict[str, Any]:
    analyses = [result.analysis for result in results]
    summary = metrics_lib.aggregate(analyses)
    category_buckets: dict[str, list[metrics_lib.Analysis]] = {}
    for result in results:
        category = str(result.scenario.get("category"))
        category_buckets.setdefault(category, []).append(result.analysis)
    categories = {
        category: {
            "label": CATEGORY_LABELS.get(category, category),
            **metrics_lib.aggregate(items),
        }
        for category, items in sorted(
            category_buckets.items(), key=lambda pair: CATEGORY_ORDER.index(pair[0]) if pair[0] in CATEGORY_ORDER else 99
        )
    }
    total_usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
    for result in results:
        if result.reply:
            for key in total_usage:
                total_usage[key] += int(result.reply.usage.get(key) or 0)
    estimated_cost = round(
        total_usage["prompt_tokens"] / 1_000_000 * INPUT_USD_PER_MILLION
        + total_usage["completion_tokens"] / 1_000_000 * OUTPUT_USD_PER_MILLION,
        6,
    )
    scenario_ids = [str(item.scenario.get("id")) for item in results]
    digest = hashlib.sha256("\n".join(scenario_ids).encode("utf-8")).hexdigest()[:12]
    return {
        "meta": {
            "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
            "provider": provider_name,
            "model": model,
            "base_url": base_url,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "elapsed_ms": elapsed_ms,
            "scenario_count": len(results),
            "scenario_set": scenario_ids,
            "scenario_set_digest": digest,
            "scenario_files": scenario_files or [],
        },
        "summary": {
            **summary,
            "usage": total_usage,
            "estimated_cost_usd": estimated_cost,
        },
        "categories": categories,
        "scenarios": [result.as_dict() for result in results],
        "errors": [
            {"id": result.scenario.get("id"), "error": result.error}
            for result in results
            if result.error
        ],
    }


def compare_baseline(report: dict[str, Any], baseline: dict[str, Any]) -> dict[str, Any]:
    current_summary = report.get("summary") or {}
    base_summary = baseline.get("summary") or {}
    current_overall = float(current_summary.get("overall") or 0)
    base_overall = float(base_summary.get("overall") or 0)
    base_dimensions = base_summary.get("dimensions") or {}
    current_dimensions = current_summary.get("dimensions") or {}
    dimension_deltas = {
        key: round(float(current_dimensions.get(key) or 0) - float(base_dimensions.get(key) or 0), 1)
        for key in metrics_lib.DIMENSIONS
        if key in base_dimensions
    }
    base_scenarios = {
        str(item.get("id")): float(item.get("score") or 0)
        for item in baseline.get("scenarios") or []
    }
    current_scenarios = {
        str(item.get("id")): float(item.get("score") or 0)
        for item in report.get("scenarios") or []
    }
    common = sorted(set(base_scenarios) & set(current_scenarios))
    scenario_deltas = {
        scenario_id: round(current_scenarios[scenario_id] - base_scenarios[scenario_id], 1)
        for scenario_id in common
    }
    regressions = sorted(
        (
            {
                "id": scenario_id,
                "delta": delta,
                "baseline": base_scenarios[scenario_id],
                "current": current_scenarios[scenario_id],
            }
            for scenario_id, delta in scenario_deltas.items()
            if delta <= -1.0
        ),
        key=lambda item: item["delta"],
    )
    improvements = sorted(
        (
            {
                "id": scenario_id,
                "delta": delta,
                "baseline": base_scenarios[scenario_id],
                "current": current_scenarios[scenario_id],
            }
            for scenario_id, delta in scenario_deltas.items()
            if delta >= 1.0
        ),
        key=lambda item: -item["delta"],
    )
    return {
        "baseline_generated_at": (baseline.get("meta") or {}).get("generated_at"),
        "baseline_overall": base_overall,
        "overall_delta": round(current_overall - base_overall, 1),
        "baseline_dimensions": base_dimensions,
        "dimension_deltas": dimension_deltas,
        "scenario_deltas": scenario_deltas,
        "regressions": regressions,
        "improvements": improvements,
        "common_scenarios": len(common),
    }


def write_json(report: dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_markdown(report: dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render_markdown(report), encoding="utf-8")


def _delta(value: float) -> str:
    """分数变化：下降用红色 HTML span（Markdown 渲染器会显示成红色），上升用绿色。"""
    if value > 0:
        return f'<span style="color:#2e7d32">+{value:.1f}</span>'
    if value < 0:
        return f'<span style="color:#c62828">**{value:.1f}**</span>'
    return "0.0"


def _pct(value: Any) -> str:
    try:
        return f"{float(value):.1f}"
    except (TypeError, ValueError):
        return "—"


def render_markdown(report: dict[str, Any]) -> str:
    meta = report.get("meta") or {}
    summary = report.get("summary") or {}
    baseline = report.get("baseline")
    lines: list[str] = []
    lines.append("# Yap 生成质量评测报告")
    lines.append("")
    lines.append(
        "> 场景集 + 指标 + 报告，量化「幻觉 / AI 味 / 像不像人」。"
        "指标定义见 `eval/README.md`；分数只对应当前场景集，不跨模型直接横比。"
    )
    lines.append("")
    lines.append("## 1. 本轮信息")
    lines.append("")
    lines.append("| 项 | 值 |")
    lines.append("| --- | --- |")
    lines.append(f"| 生成时间 | {meta.get('generated_at', '—')} |")
    lines.append(f"| Provider | `{meta.get('provider', '—')}` |")
    lines.append(f"| 模型 | `{meta.get('model', '—')}` |")
    lines.append(f"| Base URL | `{meta.get('base_url', '—')}` |")
    lines.append(f"| 场景数 | {meta.get('scenario_count', '—')} |")
    lines.append(f"| 温度 / 最大输出 | {meta.get('temperature') or '—'} / {meta.get('max_tokens') or '—'} |")
    lines.append(f"| 总耗时 | {int(meta.get('elapsed_ms') or 0) / 1000:.1f} s |")
    usage = summary.get("usage") or {}
    lines.append(
        f"| Token | prompt {usage.get('prompt_tokens', 0)} / completion {usage.get('completion_tokens', 0)} |"
    )
    lines.append(f"| 估算成本 | ${summary.get('estimated_cost_usd', 0):.4f}（按 DeepSeek chat 价目表，仅参考） |")
    lines.append(f"| 场景集摘要 | `{meta.get('scenario_set_digest', '—')}` |")
    lines.append("")
    lines.append("## 2. 总分")
    lines.append("")
    overall_delta = baseline.get("overall_delta") if baseline else None
    overall_cell = f"{_pct(summary.get('overall'))}"
    if overall_delta is not None:
        overall_cell += "（" + _delta(float(overall_delta)) + " vs 基线）"
    lines.append("| 总分 | 通过率 | 通过 / 总数 |")
    lines.append("| ---: | ---: | ---: |")
    lines.append(
        f"| **{overall_cell}** | {_pct(summary.get('pass_rate'))}% | "
        f"{summary.get('passed', 0)} / {summary.get('total', 0)} |"
    )
    lines.append("")
    lines.append("### 分维度")
    lines.append("")
    lines.append("| 维度 | 分数 | 变化 |")
    lines.append("| --- | ---: | ---: |")
    dimensions = summary.get("dimensions") or {}
    dimension_deltas = (baseline or {}).get("dimension_deltas") or {}
    for key, label in metrics_lib.DIMENSION_LABELS.items():
        delta_text = _delta(float(dimension_deltas[key])) if key in dimension_deltas else "—"
        lines.append(f"| {label} | {_pct(dimensions.get(key))} | {delta_text} |")
    lines.append("")
    lines.append("### 分项指标")
    lines.append("")
    lines.append("| 指标 | 均分 | 权重 |")
    lines.append("| --- | ---: | ---: |")
    metric_scores = summary.get("metric_scores") or {}
    for key, label in metrics_lib.METRIC_LABELS.items():
        value = metric_scores.get(key)
        lines.append(
            f"| {label} | {_pct(value) if value is not None else '不适用'} | {metrics_lib.WEIGHTS[key]:.0f} |"
        )
    lines.append("")
    lines.append("## 3. 分类别")
    lines.append("")
    lines.append("| 类别 | 场景数 | 均分 | 通过率 |")
    lines.append("| --- | ---: | ---: | ---: |")
    for category, data in (report.get("categories") or {}).items():
        lines.append(
            f"| {data.get('label', category)} | {data.get('total', 0)} | "
            f"{_pct(data.get('overall'))} | {_pct(data.get('pass_rate'))}% |"
        )
    lines.append("")
    lines.append("## 4. 失败场景")
    lines.append("")
    failed = [item for item in report.get("scenarios") or [] if not item.get("passed")]
    if not failed:
        lines.append("本轮没有失败场景。")
    else:
        lines.append("| 场景 | 类别 | 分数 | 硬失败 | 主要问题 |")
        lines.append("| --- | --- | ---: | --- | --- |")
        for item in failed:
            hard = "、".join(item.get("hard_failures") or []) or "—"
            problems = "；".join(
                f"{failure.get('label')} {failure.get('score')}" for failure in (item.get("failures") or [])[:3]
            ) or (item.get("error") or "—")
            lines.append(
                f"| `{item.get('id')}` {item.get('title')} | {item.get('category_label')} | "
                f"{_pct(item.get('score'))} | {hard} | {problems} |"
            )
    lines.append("")
    if failed:
        lines.append("### 失败明细")
        lines.append("")
        for item in failed:
            lines.append(f"#### `{item.get('id')}` {item.get('title')}")
            lines.append("")
            if item.get("error"):
                lines.append(f"- 错误：`{item['error']}`")
            lines.append(f"- 回复：{item.get('text') or '（空）'}")
            if item.get("tool_calls"):
                lines.append(f"- 工具调用：`{json.dumps(item['tool_calls'], ensure_ascii=False)}`")
            for failure in item.get("failures") or []:
                detail = json.dumps(failure.get("detail") or {}, ensure_ascii=False)
                lines.append(f"- {failure.get('label')}：{failure.get('score')} / {detail}")
            lines.append("")
    lines.append("## 5. 与基线对比")
    lines.append("")
    if not baseline:
        lines.append("本轮没有传 `--baseline`（或基线文件不存在）。")
    else:
        lines.append(f"- 基线生成时间：{baseline.get('baseline_generated_at') or '—'}")
        lines.append(
            f"- 总分变化：**{_pct(summary.get('overall'))}** vs "
            f"{_pct(baseline.get('baseline_overall'))}（{_delta(float(baseline.get('overall_delta') or 0))}）"
        )
        regressions = baseline.get("regressions") or []
        if regressions:
            lines.append("")
            lines.append("**分数下降（标红）**：")
            lines.append("")
            lines.append("| 场景 | 基线 | 本轮 | 变化 |")
            lines.append("| --- | ---: | ---: | ---: |")
            for item in regressions:
                lines.append(
                    f"| `{item['id']}` | {_pct(item['baseline'])} | {_pct(item['current'])} | "
                    f"{_delta(float(item['delta']))} |"
                )
        else:
            lines.append("- 没有场景分数下降 ≥ 1 分。")
        improvements = baseline.get("improvements") or []
        if improvements:
            lines.append("")
            lines.append("**分数上升**：" + "、".join(
                f"`{item['id']}` {_delta(float(item['delta']))}" for item in improvements[:10]
            ))
    lines.append("")
    lines.append("## 6. 说明与风险")
    lines.append("")
    lines.append("- 评测只发一轮请求、不执行工具回路：模型若只输出工具块，本轮只判「工具选择是否合规」，不评最终措辞。")
    lines.append("- 生成有随机性（默认温度 0.75），总分单次波动通常 2–5 分；回归判断建议看 ≥3 次均值或固定种子。")
    lines.append("- 成本按 token 估算，不包含重试与失败请求；场景集越大、越常跑，成本越高。")
    lines.append("- 场景集与指标是「当前产品行为的尺子」，App 提示词变了要同步 `eval/prompt.py` 与 `eval/tools.py`。")
    lines.append("")
    return "\n".join(lines)


def compare_and_attach(
    report: dict[str, Any],
    baseline_path: Path | None,
) -> dict[str, Any]:
    if not baseline_path or not Path(baseline_path).is_file():
        return report
    try:
        baseline = json.loads(Path(baseline_path).read_text(encoding="utf-8"))
    except ValueError as error:
        print(f"警告：基线文件不是合法 JSON，跳过对比：{error}", file=sys.stderr)
        return report
    report["baseline"] = compare_baseline(report, baseline)
    return report


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="python3 -m eval.run",
        description="Yap 生成质量评测：场景集 → 指标 → JSON/Markdown 报告。",
    )
    parser.add_argument("--provider", choices=["deepseek", "openai", "fake"], default="deepseek")
    parser.add_argument("--base-url", default=os.environ.get("DEEPSEEK_BASE_URL") or DEFAULT_BASE_URL)
    parser.add_argument("--model", default=os.environ.get("DEEPSEEK_MODEL") or DEFAULT_MODEL)
    parser.add_argument("--api-key", default=None, help="默认读 DEEPSEEK_API_KEY / OPENAI_API_KEY")
    parser.add_argument("--temperature", type=float, default=DEFAULT_TEMPERATURE)
    parser.add_argument("--max-tokens", type=int, default=DEFAULT_MAX_TOKENS)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    parser.add_argument("--retries", type=int, default=DEFAULT_RETRIES)
    parser.add_argument("--ca-bundle", default=None, help="自定义 CA 证书路径（也可用 EVAL_CA_BUNDLE）")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--category", action="append", default=None, help="可重复；只跑指定类别")
    parser.add_argument("--ids", default=None, help="逗号分隔的场景 id")
    parser.add_argument("--out", default=None, help="输出路径（.json / .md / 或两者共用的前缀）")
    parser.add_argument("--baseline", default=None, help="基线 JSON 路径；不给且默认基线存在时自动用")
    parser.add_argument(
        "--fail-on-regression",
        type=float,
        default=None,
        metavar="POINTS",
        help="基线总分下降达到该值时退出码 2（CI 用；例如 2 表示掉 2 分就失败）",
    )
    parser.add_argument("--update-baseline", action="store_true", help="把本轮结果写成基线")
    parser.add_argument("--concurrency", type=int, default=1, help="并发请求数（默认 1，避免限流）")
    parser.add_argument("--fake-replies", default=str(FAKE_FIXTURE), help="--provider fake 的回复 fixture")
    parser.add_argument("--list", action="store_true", help="列出场景后退出")
    parser.add_argument("--quiet", action="store_true", help="不打印逐条进度")
    return parser.parse_args(list(argv) if argv is not None else None)


def _resolve_api_key(provider_name: str, explicit: str | None) -> str:
    if explicit:
        return explicit
    for env_name in ("DEEPSEEK_API_KEY", "OPENAI_API_KEY"):
        value = os.environ.get(env_name)
        if value and value.strip():
            return value.strip()
    raise SystemExit(
        f"缺少 API key（provider={provider_name}）：设置 DEEPSEEK_API_KEY 或传 --api-key。"
        "离线演示请用 --provider fake。"
    )


def _make_provider(args: argparse.Namespace) -> Provider:
    if args.provider == "fake":
        fixture = Path(args.fake_replies)
        if fixture.is_file():
            return FakeProvider.from_fixture(fixture)
        return FakeProvider()
    key = _resolve_api_key(args.provider, args.api_key)
    return OpenAICompatibleProvider(
        base_url=args.base_url,
        api_key=key,
        model=args.model,
        temperature=args.temperature,
        max_tokens=args.max_tokens,
        timeout=args.timeout,
        retries=args.retries,
        ca_bundle=args.ca_bundle,
    )


def _output_paths(raw: str | None) -> tuple[Path, Path]:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    if not raw:
        base = REPORT_DIR / f"run-{stamp}"
        return base.with_suffix(".json"), base.with_suffix(".md")
    resolved = Path(raw.replace("<ts>", stamp))
    if resolved.suffix == ".json":
        return resolved, resolved.with_suffix(".md")
    if resolved.suffix == ".md":
        return resolved.with_suffix(".json"), resolved
    return resolved.with_suffix(".json"), resolved.with_suffix(".md")


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    scenarios = load_scenarios()
    if args.list:
        for scenario in interleave(scenarios):
            print(
                f"{scenario['id']:<28} {scenario['category']:<12} "
                f"{scenario.get('title') or ''}"
            )
        print(f"\n共 {len(scenarios)} 个场景")
        return 0
    selected = filter_scenarios(
        scenarios,
        categories=args.category,
        ids=args.ids.split(",") if args.ids else None,
        limit=args.limit,
    )
    provider = _make_provider(args)
    started = time.monotonic()
    results = execute(selected, provider, concurrency=args.concurrency, progress=not args.quiet)
    elapsed_ms = int((time.monotonic() - started) * 1000)
    report = build_report(
        results,
        provider_name=args.provider,
        model=getattr(provider, "model", "fake-model"),
        base_url=getattr(provider, "base_url", "fake"),
        temperature=args.temperature if args.provider != "fake" else None,
        max_tokens=args.max_tokens if args.provider != "fake" else None,
        elapsed_ms=elapsed_ms,
        scenario_files=sorted(
            {str(scenario.get("_file")) for scenario in selected if scenario.get("_file")}
        ),
    )
    baseline_path = Path(args.baseline) if args.baseline else (
        DEFAULT_BASELINE if args.provider != "fake" and DEFAULT_BASELINE.is_file() else None
    )
    report = compare_and_attach(report, baseline_path)
    json_path, md_path = _output_paths(args.out)
    write_json(report, json_path)
    write_markdown(report, md_path)
    if args.update_baseline:
        write_json(report, DEFAULT_BASELINE)
        print(f"基线已更新：{DEFAULT_BASELINE}", file=sys.stderr)
    summary = report["summary"]
    print(
        f"\n总分 {summary['overall']:.1f} · 通过 {summary['passed']}/{summary['total']} "
        f"({summary['pass_rate']:.1f}%) · {elapsed_ms / 1000:.1f}s"
    )
    print(f"JSON: {json_path}")
    print(f"Markdown: {md_path}")
    if report.get("baseline"):
        delta = report["baseline"]["overall_delta"]
        marker = "下降" if delta < 0 else ("上升" if delta > 0 else "持平")
        print(f"基线对比：{delta:+.1f}（{marker}）")
        if args.fail_on_regression is not None and delta <= -abs(args.fail_on_regression):
            print(
                f"回归超阈值：总分下降 {abs(delta):.1f} ≥ {abs(args.fail_on_regression):.1f}，"
                "按 --fail-on-regression 返回退出码 2。",
                file=sys.stderr,
            )
            return 2
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
