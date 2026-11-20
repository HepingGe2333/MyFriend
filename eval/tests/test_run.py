"""离线单测：场景集、执行、报告、baseline 对比与 CLI。"""

from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from eval import run as run_module
from eval.provider import FakeProvider, Provider


class ScenarioSetTest(unittest.TestCase):
    def test_at_least_25_and_all_categories(self):
        scenarios = run_module.load_scenarios()
        self.assertGreaterEqual(len(scenarios), 25)
        categories = {str(item["category"]) for item in scenarios}
        self.assertEqual(categories, set(run_module.CATEGORY_ORDER))
        ids = [item["id"] for item in scenarios]
        self.assertEqual(len(ids), len(set(ids)))

    def test_limit_interleaves_categories(self):
        scenarios = run_module.load_scenarios()
        selected = run_module.filter_scenarios(scenarios, limit=11)
        self.assertEqual(len(selected), 11)
        # 11 个类别各取一条时，前 11 条应当覆盖全部类别。
        self.assertEqual({item["category"] for item in selected}, set(run_module.CATEGORY_ORDER))

    def test_filter_by_category_and_id(self):
        scenarios = run_module.load_scenarios()
        only_memory = run_module.filter_scenarios(scenarios, categories=["memory"])
        self.assertTrue(only_memory)
        self.assertTrue(all(item["category"] == "memory" for item in only_memory))
        one = run_module.filter_scenarios(scenarios, ids=["blocked-001"])
        self.assertEqual([item["id"] for item in one], ["blocked-001"])

    def test_validation_rejects_bad_scenario(self):
        with self.assertRaises(SystemExit):
            run_module.validate_scenario({"id": "x"}, "bad.json")


class ExecuteReportTest(unittest.TestCase):
    def _scenarios(self):
        return run_module.filter_scenarios(
            run_module.load_scenarios(), ids=["daily-001", "daily-002", "tool-001"]
        )

    def test_execute_and_report(self):
        provider = FakeProvider(
            {
                "daily-001": "那种外套今年挺多的。你到哪了？",
                "daily-002": "那家我还没去过。咸成什么样？",
                "tool-001": '```yap-tool\n{"name":"web_search","arguments":{"query":"天气"}}\n```',
            }
        )
        scenarios = self._scenarios()
        results = run_module.execute(scenarios, provider, progress=False)
        self.assertEqual(len(results), 3)
        self.assertTrue(all(result.analysis.passed for result in results))
        report = run_module.build_report(
            results,
            provider_name="fake",
            model="fake-model",
            base_url="fake",
            temperature=None,
            max_tokens=None,
            elapsed_ms=12,
        )
        self.assertEqual(report["summary"]["total"], 3)
        self.assertEqual(report["summary"]["passed"], 3)
        self.assertEqual(report["summary"]["overall"], 100)
        self.assertEqual(report["meta"]["scenario_count"], 3)
        self.assertIn("daily_chat", report["categories"])
        with tempfile.TemporaryDirectory() as temp:
            json_path = Path(temp) / "run.json"
            md_path = Path(temp) / "run.md"
            run_module.write_json(report, json_path)
            run_module.write_markdown(report, md_path)
            data = json.loads(json_path.read_text(encoding="utf-8"))
            self.assertEqual(data["summary"]["pass_rate"], 100)
            markdown = md_path.read_text(encoding="utf-8")
            self.assertIn("Yap 生成质量评测报告", markdown)
            self.assertIn("本轮没有失败场景", markdown)

    def test_failure_report(self):
        provider = FakeProvider(
            {
                "daily-001": "首先，你要早点睡。其次，希望你照顾好自己。（叹了口气）",
                "daily-002": "那家我还没去过。",
                "tool-001": "明天晴，25 度。",
            }
        )
        results = run_module.execute(self._scenarios(), provider, progress=False)
        failed = [result for result in results if not result.analysis.passed]
        self.assertGreaterEqual(len(failed), 2)
        report = run_module.build_report(
            results,
            provider_name="fake",
            model="fake-model",
            base_url="fake",
            temperature=None,
            max_tokens=None,
            elapsed_ms=1,
        )
        markdown = run_module.render_markdown(report)
        self.assertIn("失败场景", markdown)
        self.assertIn("失败明细", markdown)
        self.assertIn("**", markdown)

    def test_provider_error_becomes_zero_score(self):
        class Boom(Provider):
            name = "boom"

            def complete(self, messages, *, scenario_id=None):
                raise RuntimeError("接口挂了")

        scenario = run_module.filter_scenarios(run_module.load_scenarios(), ids=["daily-001"])
        results = run_module.execute(scenario, Boom(), progress=False)
        self.assertEqual(results[0].analysis.score, 0)
        self.assertIn("接口挂了", results[0].error)
        report = run_module.build_report(
            results,
            provider_name="boom",
            model="boom",
            base_url="boom",
            temperature=None,
            max_tokens=None,
            elapsed_ms=1,
        )
        self.assertEqual(len(report["errors"]), 1)
        self.assertEqual(report["summary"]["passed"], 0)
        self.assertIn("接口挂了", run_module.render_markdown(report))


class BaselineTest(unittest.TestCase):
    def _report(self, reply: str) -> dict:
        scenario = run_module.filter_scenarios(run_module.load_scenarios(), ids=["daily-001"])
        results = run_module.execute(scenario, FakeProvider({"daily-001": reply}), progress=False)
        return run_module.build_report(
            results,
            provider_name="fake",
            model="fake-model",
            base_url="fake",
            temperature=None,
            max_tokens=None,
            elapsed_ms=1,
        )

    def test_regression_is_marked_red(self):
        baseline = self._report("那种外套今年挺多的。你到哪了？")
        current = self._report("首先，你要早点睡。其次，希望你照顾好自己。希望对你有帮助。")
        comparison = run_module.compare_baseline(current, baseline)
        self.assertLess(comparison["overall_delta"], 0)
        self.assertTrue(comparison["regressions"])
        self.assertEqual(comparison["regressions"][0]["id"], "daily-001")
        self.assertLess(comparison["dimension_deltas"]["anti_ai"], 0)
        current["baseline"] = comparison
        markdown = run_module.render_markdown(current)
        self.assertIn("color:#c62828", markdown)
        self.assertIn("分数下降", markdown)

    def test_no_baseline_renders_note(self):
        report = self._report("刚忙完。")
        markdown = run_module.render_markdown(report)
        self.assertIn("本轮没有传 `--baseline`", markdown)


class CliTest(unittest.TestCase):
    def test_main_writes_both_reports(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp) / "cli-run.json"
            stdout = io.StringIO()
            stderr = io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = run_module.main(
                    [
                        "--provider",
                        "fake",
                        "--limit",
                        "3",
                        "--out",
                        str(out),
                        "--baseline",
                        str(Path(temp) / "missing-baseline.json"),
                        "--quiet",
                    ]
                )
            self.assertEqual(code, 0)
            self.assertTrue(out.is_file())
            self.assertTrue(out.with_suffix(".md").is_file())
            report = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(report["summary"]["total"], 3)
            self.assertIn("总分", stdout.getvalue())

    def test_list_flag(self):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = run_module.main(["--list"])
        self.assertEqual(code, 0)
        self.assertIn(f"共 {len(run_module.load_scenarios())} 个场景", stdout.getvalue())

    def test_fail_on_regression_exit_code(self):
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            baseline = run_module.build_report(
                run_module.execute(
                    run_module.filter_scenarios(run_module.load_scenarios(), ids=["daily-001"]),
                    FakeProvider({"daily-001": "那种外套今年挺多的。你到哪了？"}),
                    progress=False,
                ),
                provider_name="fake",
                model="fake-model",
                base_url="fake",
                temperature=None,
                max_tokens=None,
                elapsed_ms=1,
            )
            baseline_path = temp_path / "baseline.json"
            run_module.write_json(baseline, baseline_path)
            fixture = temp_path / "replies.json"
            fixture.write_text(
                json.dumps({"daily-001": "首先，你要早点睡。其次，希望你照顾好自己。（叹了口气）"}),
                encoding="utf-8",
            )
            stderr = io.StringIO()
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(stderr):
                code = run_module.main(
                    [
                        "--provider",
                        "fake",
                        "--ids",
                        "daily-001",
                        "--fake-replies",
                        str(fixture),
                        "--baseline",
                        str(baseline_path),
                        "--fail-on-regression",
                        "2",
                        "--out",
                        str(temp_path / "run.json"),
                        "--quiet",
                    ]
                )
            self.assertEqual(code, 2)
            self.assertIn("回归超阈值", stderr.getvalue())

    def test_missing_key_is_clear(self):
        import os

        old = {name: os.environ.pop(name, None) for name in ("DEEPSEEK_API_KEY", "OPENAI_API_KEY")}
        try:
            with self.assertRaises(SystemExit) as caught:
                run_module.main(["--provider", "deepseek", "--ids", "daily-001", "--quiet"])
            self.assertIn("DEEPSEEK_API_KEY", str(caught.exception))
        finally:
            for name, value in old.items():
                if value is not None:
                    os.environ[name] = value


if __name__ == "__main__":
    unittest.main()
