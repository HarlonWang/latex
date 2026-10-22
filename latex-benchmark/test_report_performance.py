"""通用性能报告的格式、判定和命令行退出码回归。"""

import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("report_performance.py")
SPEC = importlib.util.spec_from_file_location("report_performance", SCRIPT)
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


def result(name="ParserBenchmark.parse", score=1.0, unit="ms/op", allocated=100.0):
    entry = {
        "benchmark": "com.hrm.latex.benchmark." + name,
        "jmhVersion": "1.37", "mode": "avgt", "threads": 1, "forks": 1,
        "jdkVersion": "21.0.10", "vmName": "Test VM", "vmVersion": "21.0.10",
        "jvmArgs": ["-Xms256m", "-Xmx1g"], "warmupIterations": 3,
        "warmupTime": "500 ms", "warmupBatchSize": 1, "measurementIterations": 5,
        "measurementTime": "500 ms", "measurementBatchSize": 1,
        "params": {"input": "medium"},
        "primaryMetric": {"score": score, "scoreError": score * 0.1, "scoreUnit": unit},
        "secondaryMetrics": {
            "gc.count": {"score": 2.0, "scoreUnit": "counts"},
            "gc.time": {"score": 0.01, "scoreUnit": "s"},
        },
    }
    if allocated is not None:
        entry["secondaryMetrics"]["gc.alloc.rate.norm"] = {"score": allocated, "scoreUnit": "B/op"}
    return entry


def budgets(classes=None):
    return {
        "schemaVersion": 1,
        "baselineLimits": {"maxTimeRatio": 1.25, "maxAllocationRatio": 1.25},
        "groups": [{
            "id": "parser", "label": "解析", "classes": classes or ["ParserBenchmark"],
            "maxMeanMs": 2.0, "maxAllocatedBytesPerOp": 200.0,
        }],
    }


class PerformanceReportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def write_json(self, name, value):
        path = self.directory / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def rows(self, entries):
        return REPORT.read_report(self.write_json("input.json", entries))

    def run_script(self, entries, config=None, baseline=None):
        source = self.write_json("input.json", entries)
        config_path = self.write_json("budgets.json", config if config is not None else budgets())
        output = self.directory / "nested" / "summary.md"
        command = [sys.executable, str(SCRIPT), str(source), "--budgets", str(config_path), "--output", str(output)]
        if baseline is not None:
            command += ["--baseline", str(self.write_json("baseline.json", baseline))]
        completed = subprocess.run(command, text=True, capture_output=True, check=False)
        return completed, output.read_text(encoding="utf-8") if output.exists() else ""

    def test_good_and_bad_both_exit_zero(self):
        for score, allocated, verdict in [(1.0, 100.0, "GOOD"), (3.0, 100.0, "BAD"), (1.0, 201.0, "BAD")]:
            with self.subTest(score=score, allocated=allocated):
                completed, text = self.run_script([result(score=score, allocated=allocated)])
                self.assertEqual(0, completed.returncode, completed.stderr)
                self.assertIn(f"| {verdict} |", text)
                self.assertIn("均值 ± 误差", text)
                self.assertIn("2 / 10", text)

    def test_unknown_group_is_not_judged_good(self):
        completed, text = self.run_script([result(name="UnconfiguredBenchmark.run")])
        self.assertEqual(0, completed.returncode)
        self.assertIn("本轮结果：GOOD 0；BAD 0；未评判 1", text)

    def test_group_without_any_budget_remains_unassessed(self):
        config = budgets()
        del config["groups"][0]["maxMeanMs"]
        del config["groups"][0]["maxAllocatedBytesPerOp"]
        completed, text = self.run_script([result()], config)
        self.assertEqual(0, completed.returncode)
        self.assertIn("| 未评判 |", text)

    def test_missing_budget_file_is_an_error(self):
        source = self.write_json("input.json", [result()])
        completed = subprocess.run(
            [sys.executable, str(SCRIPT), str(source), "--budgets", str(self.directory / "missing.json")],
            text=True, capture_output=True, check=False,
        )
        self.assertNotEqual(0, completed.returncode)

    def test_time_units_are_normalized_with_their_errors(self):
        for unit, score in [("ns/op", 1_000_000), ("us/op", 1000), ("ms/op", 1), ("s/op", 0.001)]:
            with self.subTest(unit=unit):
                row = next(iter(self.rows([result(score=score, unit=unit)]).values()))
                self.assertAlmostEqual(1.0, row["mean_ms"])
                self.assertAlmostEqual(0.1, row["error_ms"])

    def test_invalid_scores_fail_and_cli_returns_nonzero(self):
        for score in [0, -1, float("nan"), float("inf"), float("-inf"), True, "1"]:
            with self.subTest(score=score):
                entry = result()
                entry["primaryMetric"]["score"] = score
                with self.assertRaises(ValueError):
                    self.rows([entry])
                completed, _ = self.run_script([entry])
                self.assertNotEqual(0, completed.returncode)

    def test_normalization_overflow_is_rejected(self):
        with self.assertRaises(ValueError):
            self.rows([result(score=1e308, unit="s/op")])

    def test_unavailable_error_is_not_reported_as_zero(self):
        entry = result()
        entry["primaryMetric"]["scoreError"] = "NaN"
        completed, text = self.run_script([entry])
        self.assertEqual(0, completed.returncode)
        self.assertIn("1 ± —", text)
        self.assertIn("样本不足", text)

    def test_missing_critical_fields_and_bad_units_are_rejected(self):
        for field in ["benchmark", "jmhVersion", "measurementIterations", "primaryMetric"]:
            entry = result()
            del entry[field]
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.rows([entry])
        for field in ["score", "scoreError", "scoreUnit"]:
            entry = result()
            del entry["primaryMetric"][field]
            with self.subTest(field=field), self.assertRaises(KeyError):
                self.rows([entry])
        with self.assertRaises(ValueError):
            self.rows([result(unit="ops/s")])

    def test_empty_and_duplicate_cases_are_rejected(self):
        for entries in [[], {}, [result(), result()]]:
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                self.rows(entries)

    def test_baseline_uses_time_and_allocation_growth_limits(self):
        for score, allocated, verdict in [(1.25, 125, "GOOD"), (1.251, 100, "BAD"), (1, 125.1, "BAD")]:
            with self.subTest(score=score, allocated=allocated):
                completed, text = self.run_script([result(score=score, allocated=allocated)], baseline=[result()])
                self.assertEqual(0, completed.returncode, completed.stderr)
                self.assertIn(f"| {verdict} |", text)
                self.assertIn("历史基线", text)
                self.assertIn("≤ 1.25×", text)

    def test_baseline_can_judge_an_unbudgeted_group(self):
        entry = result(name="UnconfiguredBenchmark.run")
        completed, text = self.run_script([entry], baseline=[entry])
        self.assertEqual(0, completed.returncode)
        self.assertIn("| GOOD |", text)

    def test_missing_baseline_case_or_changed_parameters_fails(self):
        changed_params = result()
        changed_params["params"]["input"] = "large"
        for baseline in [[result(), result(name="ParserBenchmark.other")], [changed_params]]:
            with self.subTest(baseline=baseline):
                completed, _ = self.run_script([result()], baseline=baseline)
                self.assertNotEqual(0, completed.returncode)
                self.assertIn("集合不一致", completed.stderr)

    def test_changed_baseline_configuration_and_gc_mode_fail(self):
        for field, value in [("jdkVersion", "17"), ("jvmArgs", ["-Xmx2g"]), ("measurementIterations", 9)]:
            previous = result()
            previous[field] = value
            with self.subTest(field=field):
                completed, _ = self.run_script([result()], baseline=[previous])
                self.assertNotEqual(0, completed.returncode)
                self.assertIn("配置不一致", completed.stderr)
        completed, _ = self.run_script([result()], baseline=[result(allocated=None)])
        self.assertNotEqual(0, completed.returncode)
        self.assertIn("GC 分配采样", completed.stderr)

    def test_invocation_setup_allocation_and_gc_are_hidden_and_not_judged(self):
        name = "IncrementalParserBenchmark.edit"
        entry = result(name=name, allocated=999_999_999)
        config = budgets(["IncrementalParserBenchmark"])
        completed, text = self.run_script([entry], config, baseline=[result(name=name, allocated=1)])
        self.assertEqual(0, completed.returncode)
        self.assertIn("| GOOD |", text)
        data_row = next(line for line in text.splitlines() if line.startswith("| IncrementalParserBenchmark.edit |"))
        self.assertIn("| — | — / — |", data_row)
        self.assertNotIn("999999999", data_row)
        self.assertIn("已隐藏且未参与判定", data_row)

    def test_streaming_batch_keeps_its_own_allocation_and_gc(self):
        completed, text = self.run_script([result(name="StreamingParserBenchmark.parse", allocated=123)],
                                          budgets(["StreamingParserBenchmark"]))
        self.assertEqual(0, completed.returncode)
        data_row = next(line for line in text.splitlines() if line.startswith("| StreamingParserBenchmark.parse |"))
        self.assertIn("| 123 | 2 / 10 |", data_row)

    def test_zero_baseline_allocation_has_defined_comparison(self):
        for allocated, verdict in [(0, "GOOD"), (1, "BAD")]:
            completed, text = self.run_script([result(allocated=allocated)], baseline=[result(allocated=0)])
            self.assertEqual(0, completed.returncode)
            self.assertIn(f"| {verdict} |", text)

    def test_invalid_budgets_are_rejected(self):
        for mutate in [
            lambda config: config.update(schemaVersion=2),
            lambda config: config["baselineLimits"].update(maxTimeRatio=0.9),
            lambda config: config["groups"][0].update(maxMeanMs=float("nan")),
            lambda config: config["groups"].append(copy.deepcopy(config["groups"][0])),
        ]:
            config = budgets()
            mutate(config)
            with self.subTest(config=config), self.assertRaises(ValueError):
                REPORT.read_budgets(self.write_json("invalid-budgets.json", config))


if __name__ == "__main__":
    unittest.main()
