#!/usr/bin/env python3
"""汇总各阶段 JMH 性能，按显式预算或可选历史基线报告 GOOD/BAD。"""

import argparse
import json
import math
from pathlib import Path


CONFIG_FIELDS = (
    "jmhVersion", "mode", "threads", "forks", "jdkVersion", "vmName", "vmVersion",
    "jvmArgs", "warmupIterations", "warmupTime", "warmupBatchSize",
    "measurementIterations", "measurementTime", "measurementBatchSize",
)
TIME_FACTORS = {"ns/op": 0.000001, "us/op": 0.001, "ms/op": 1.0, "s/op": 1000.0}
GC_TIME_FACTORS = {unit.removesuffix("/op"): factor for unit, factor in TIME_FACTORS.items()}
DEFAULT_BUDGETS = Path(__file__).with_name("performance-budgets.json")


def number(value, name, *, positive=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{name}: 必须是数值")
    if not math.isfinite(value) or value < 0 or (positive and value == 0):
        raise ValueError(f"{name}: 必须是有限{'正数' if positive else '非负数'}")
    return value


def read_report(path):
    entries = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(entries, list) or not entries:
        raise ValueError(f"JMH 报告必须是非空数组：{path}")
    rows = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("JMH 条目必须是对象")
        for field in (*CONFIG_FIELDS, "benchmark", "primaryMetric"):
            if field not in entry:
                raise ValueError(f"缺少关键字段：{field}")
        name = entry["benchmark"]
        if not isinstance(name, str) or "." not in name:
            raise ValueError("benchmark 必须是完整的类名和方法名")
        if entry["mode"] != "avgt":
            raise ValueError(f"仅接受 AverageTime 均值报告，实际 mode={entry['mode']}")
        params = entry.get("params", {})
        if not isinstance(params, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in params.items()):
            raise ValueError(f"{name}: params 必须是字符串键值对象")
        key = (name, tuple(sorted(params.items())))
        if key in rows:
            raise ValueError(f"重复的 benchmark / 参数：{key}")
        metric = entry["primaryMetric"]
        unit = metric["scoreUnit"]
        if unit not in TIME_FACTORS:
            raise ValueError(f"不支持的耗时单位：{unit}")
        factor = TIME_FACTORS[unit]
        score = number(number(metric["score"], f"{name}.score", positive=True) * factor,
                       f"{name}.meanMs", positive=True)
        error = metric["scoreError"]
        # JMH 样本不足时用 NaN 表示误差不可用，不能把它当作零误差。
        if error == "NaN" or (isinstance(error, float) and math.isnan(error)):
            error = None
        else:
            error = number(error, f"{name}.scoreError") * factor
        secondary = entry.get("secondaryMetrics", {})
        if not isinstance(secondary, dict):
            raise ValueError(f"{name}: secondaryMetrics 必须是对象")

        def secondary_score(metric_name, units):
            value = secondary.get(metric_name)
            if value is None:
                return None
            metric_unit = value["scoreUnit"]
            if metric_unit not in units:
                raise ValueError(f"{name}.{metric_name}: 不支持的单位 {metric_unit}")
            return number(value["score"], f"{name}.{metric_name}") * units[metric_unit]

        rows[key] = {
            "raw": entry,
            "mean_ms": score,
            "error_ms": error,
            "allocation": secondary_score("gc.alloc.rate.norm", {"B/op": 1.0}),
            "gc_count": secondary_score("gc.count", {"counts": 1.0}),
            "gc_time_ms": secondary_score("gc.time", GC_TIME_FACTORS),
        }
    return rows


def read_budgets(path):
    config = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(config, dict) or config.get("schemaVersion") != 1:
        raise ValueError("预算文件必须使用 schemaVersion=1")
    limits = config["baselineLimits"]
    for metric in ("maxTimeRatio", "maxAllocationRatio"):
        if number(limits[metric], metric, positive=True) < 1:
            raise ValueError(f"{metric} 不得小于 1")
    groups = config["groups"]
    if not isinstance(groups, list):
        raise ValueError("预算 groups 必须是数组")
    group_ids, classes = set(), set()
    for group in groups:
        for field in ("id", "label", "classes"):
            if field not in group:
                raise ValueError(f"预算分组缺少字段：{field}")
        if not isinstance(group["id"], str) or not group["id"] or group["id"] in group_ids:
            raise ValueError("预算分组 id 必须非空且唯一")
        if not isinstance(group["label"], str) or not group["label"]:
            raise ValueError("预算分组 label 必须是非空字符串")
        group_ids.add(group["id"])
        if not isinstance(group["classes"], list) or not group["classes"]:
            raise ValueError("预算分组 classes 必须是非空数组")
        for name in group["classes"]:
            if not isinstance(name, str) or not name or name in classes:
                raise ValueError("每个基准类只能归属一个分组")
            classes.add(name)
        for field in ("maxMeanMs", "maxAllocatedBytesPerOp"):
            if field in group:
                number(group[field], field, positive=field == "maxMeanMs")
    return config


def compare(before, after):
    if before.keys() != after.keys():
        raise ValueError("基线与本轮的 benchmark / 参数集合不一致，不能比较缺失或新增用例")
    for key in before:
        old, new = before[key], after[key]
        differences = [field for field in CONFIG_FIELDS if old["raw"][field] != new["raw"][field]]
        if differences:
            raise ValueError(f"{key}: 基线运行配置不一致：{', '.join(differences)}")
        if (old["allocation"] is None) != (new["allocation"] is None):
            raise ValueError(f"{key}: 两轮必须一致启用或关闭 GC 分配采样")


def class_name(key):
    return key[0].rsplit(".", 2)[-2]


def has_invocation_setup(key):
    return class_name(key) == "IncrementalParserBenchmark"


def effective_allocation(key, row):
    # 此类在 Invocation setup 恢复解析器；分配统计包含准备工作，不能归因到单次编辑。
    return None if has_invocation_setup(key) else row["allocation"]


def fmt(value):
    return "—" if value is None else f"{value:.6g}"


def ratio(current, previous):
    return current / previous if previous else (1.0 if current == 0 else math.inf)


def assessment(key, row, group, baseline, limits):
    checks, descriptions = [], []
    allocated = effective_allocation(key, row)
    if baseline is not None:
        old = baseline[key]
        time_ratio = ratio(row["mean_ms"], old["mean_ms"])
        checks.append(time_ratio <= limits["maxTimeRatio"])
        descriptions.append(f"耗时 {fmt(time_ratio)}× ≤ {limits['maxTimeRatio']:g}×")
        old_allocated = effective_allocation(key, old)
        if allocated is not None and old_allocated is not None:
            allocation_ratio = ratio(allocated, old_allocated)
            checks.append(allocation_ratio <= limits["maxAllocationRatio"])
            descriptions.append(f"分配 {fmt(allocation_ratio)}× ≤ {limits['maxAllocationRatio']:g}×")
    elif group is not None:
        if "maxMeanMs" in group:
            checks.append(row["mean_ms"] <= group["maxMeanMs"])
            descriptions.append(f"均值 ≤ {group['maxMeanMs']:g} ms/op")
        if "maxAllocatedBytesPerOp" in group and allocated is not None:
            checks.append(allocated <= group["maxAllocatedBytesPerOp"])
            descriptions.append(f"分配 ≤ {group['maxAllocatedBytesPerOp']:g} B/op")
    if has_invocation_setup(key):
        descriptions.append("分配与 GC 含准备工作，已隐藏且未参与判定")
    elif allocated is None:
        descriptions.append("B/op 未采集")
    if not checks:
        return "未评判", "；".join(["没有可用预算或基线", *descriptions])
    return ("GOOD" if all(checks) else "BAD"), "；".join(descriptions)


def cell(value):
    return str(value).replace("|", "\\|").replace("\n", " ")


def render(rows, report_path, budgets, baseline=None, baseline_path=None):
    groups_by_class = {name: group for group in budgets["groups"] for name in group["classes"]}
    groups = [(group, []) for group in budgets["groups"]]
    unknown = []
    grouped = {group["id"]: entries for group, entries in groups}
    judged = {}
    for key, row in rows.items():
        group = groups_by_class.get(class_name(key))
        (grouped[group["id"]] if group else unknown).append((key, row))
        judged[key] = assessment(key, row, group, baseline, budgets["baselineLimits"])
    counts = {label: sum(value[0] == label for value in judged.values()) for label in ("GOOD", "BAD", "未评判")}
    sample = next(iter(rows.values()))["raw"]
    lines = [
        "# LaTeX 性能报告", "",
        f"原始数据：`{report_path.name}`，共 {len(rows)} 个基准 / 参数组合。",
        f"环境：JMH {sample['jmhVersion']}；{sample['vmName']} {sample['jdkVersion']}；"
        f"{sample['forks']} fork，{sample['threads']} 线程；"
        f"{sample['warmupIterations']} 次预热 × {sample['warmupTime']}，"
        f"{sample['measurementIterations']} 次测量 × {sample['measurementTime']}。", "",
        "本轮结果：" + "；".join(f"{label} {count}" for label, count in counts.items()) + "。", "",
        "耗时为 JMH AverageTime 的均值 ± scoreError，统一为 ms/op；不是单次调用的 p95。"
        "误差为 — 表示样本不足，不能视为零误差。B/op 是 GC profiler 的每次操作 JVM 托管分配量，不包含 Skia/native/GPU 内存；"
        "GC 次数和时间保留 JMH 的统计口径，不当作每次操作指标。", "",
        "GOOD/BAD 只生成报告，均以 0 退出，不拦截合入；格式错误或不可比较的基线以非零退出。"
        "没有可用预算或基线的条目标记未评判，不自动判为 GOOD。", "",
    ]
    if baseline is not None:
        limits = budgets["baselineLimits"]
        lines += [
            f"判定依据：历史基线 `{baseline_path.name}`。benchmark、参数、JVM 与采样配置已检查一致；"
            f"耗时最多为基线的 {limits['maxTimeRatio']:g} 倍，有效 B/op 最多为 {limits['maxAllocationRatio']:g} 倍。"
            "有基线时使用增长标准，组预算只用于无基线的运行。", "",
        ]
    else:
        lines += [
            "判定依据：`performance-budgets.json` 中逐组列出的手工预算。"
            "预算用于发现数量级异常，不是 SLA，也不代表 Android 或 Redmi 8A 的耗时。"
            "阈值应在审阅可复现报告后调整，不能为取得全 GOOD 倒推阈值。", "",
        ]
    lines += [
        "增量解析的单次编辑基准使用 Invocation setup；其 B/op、GC 次数与时间包含解析器恢复工作，已隐藏且不参与判定。"
        "完整流式和 parse→measure 批次没有此准备阶段，其 B/op 保留。所有原始指标仍在 JSON 中。", "",
    ]
    if unknown:
        groups.append(({"id": "unconfigured", "label": "未配置阶段"}, unknown))
    for group, entries in groups:
        if not entries:
            continue
        lines += [f"## {group['label']}", ""]
        if baseline is None:
            lines += [f"预算：均值 {fmt(group.get('maxMeanMs'))} ms/op；有效分配 {fmt(group.get('maxAllocatedBytesPerOp'))} B/op。", ""]
        header = "| 操作 | 输入 / 参数 | 均值 ± 误差 ms/op | B/op | GC 次数 / 时间 ms |"
        divider = "|---|---|---:|---:|---:|"
        if baseline is not None:
            header += " 基线 ms/op / B/op |"
            divider += "---:|"
        lines += [header + " 判定依据 | 结果 |", divider + "---|---|"]
        for key, row in sorted(entries):
            operation = key[0].removeprefix("com.hrm.latex.benchmark.")
            params = ", ".join(f"{k}={v}" for k, v in key[1]) or "—"
            gc = "— / —" if has_invocation_setup(key) else f"{fmt(row['gc_count'])} / {fmt(row['gc_time_ms'])}"
            fields = [operation, params, f"{fmt(row['mean_ms'])} ± {fmt(row['error_ms'])}",
                      fmt(effective_allocation(key, row)), gc]
            if baseline is not None:
                old = baseline[key]
                fields.append(f"{fmt(old['mean_ms'])} / {fmt(effective_allocation(key, old))}")
            label, detail = judged[key]
            lines.append("| " + " | ".join(cell(value) for value in [*fields, detail, label]) + " |")
        lines.append("")
    lines += [
        "## 适用范围", "",
        "同一机器、JVM、输入和采样配置的结果才适合比较。共享 CI 机器和短迭代会有波动，"
        "接近阈值的结果应增加预热、迭代和 fork 后复核。"
        "Invocation setup 还带来逐次计时开销，不应据极短耗时计算精确加速比。", "",
        "离屏绘制衡量实际绘制调用，但不包含屏幕合成、GPU 呈现或滚动帧时间。"
        "字体资源加载受操作系统文件缓存影响，不等于应用冷启动首帧。"
        "本报告不验证 Android / Redmi 8A 的帧时间和 GC 行为。", "",
        "复现方法见仓库中的 `latex-benchmark/PERFORMANCE.md`；报告脚本不修改原始测量数据。", "",
    ]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="本轮 JMH JSON")
    parser.add_argument("--baseline", type=Path, help="可选的同配置历史 JMH JSON")
    parser.add_argument("--budgets", type=Path, default=DEFAULT_BUDGETS, help="显式预算与基线增长阈值")
    parser.add_argument("--output", type=Path, help="Markdown 输出文件；省略时输出到标准输出")
    args = parser.parse_args()
    try:
        rows = read_report(args.report)
        budgets = read_budgets(args.budgets)
        baseline = read_report(args.baseline) if args.baseline else None
        if baseline is not None:
            compare(baseline, rows)
        result = render(rows, args.report, budgets, baseline, args.baseline)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(result, encoding="utf-8")
        else:
            print(result)
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
