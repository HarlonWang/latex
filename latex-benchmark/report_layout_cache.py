#!/usr/bin/env python3
"""汇总 JMH 布局缓存结果，可选对照历史基线；仅依赖 Python 标准库。"""

import argparse
import json
import math
from pathlib import Path


CONFIG_FIELDS = (
    "jmhVersion", "mode", "threads", "forks", "jdkVersion", "vmName", "vmVersion",
    "jvmArgs", "warmupIterations", "warmupTime", "warmupBatchSize",
    "measurementIterations", "measurementTime", "measurementBatchSize",
)


def read_report(path):
    entries = json.loads(path.read_text())
    if not entries:
        raise ValueError(f"Empty JMH report: {path}")
    rows = {}
    for entry in entries:
        key = (entry["benchmark"], tuple(sorted(entry.get("params", {}).items())))
        if key in rows:
            raise ValueError(f"Duplicate benchmark and parameters: {key}")
        metric = entry["primaryMetric"]
        if not math.isfinite(metric["score"]) or metric["score"] <= 0:
            raise ValueError(f"Invalid timing score: {key}")
        if metric["scoreUnit"] != "ms/op":
            raise ValueError(f"Expected ms/op, got {metric['scoreUnit']}: {key}")
        rows[key] = entry
    return rows


def compare(before, after):
    if before.keys() != after.keys():
        raise ValueError(
            f"Benchmark sets differ; missing after={before.keys() - after.keys()}, "
            f"missing before={after.keys() - before.keys()}"
        )
    for key in before:
        differences = [field for field in CONFIG_FIELDS if before[key].get(field) != after[key].get(field)]
        if differences:
            raise ValueError(f"Run configuration differs for {key}: {', '.join(differences)}")
        before_gc = "gc.alloc.rate.norm" in before[key].get("secondaryMetrics", {})
        after_gc = "gc.alloc.rate.norm" in after[key].get("secondaryMetrics", {})
        if before_gc != after_gc:
            raise ValueError(f"GC profiler must be enabled consistently: {key}")


def allocation(entry):
    # 每次调用前的准备阶段不计入耗时，但会进入 GC 分配统计。
    if ".LayoutCacheIncrementalBenchmark." in entry["benchmark"]:
        return "—"
    metric = entry.get("secondaryMetrics", {}).get("gc.alloc.rate.norm")
    return f"{metric['score']:.1f}" if metric else "—"


def assessment(key, rows):
    name, params = key
    class_name, method = name.rsplit(".", 1)
    if class_name.endswith(".CacheBenchmark"):
        limit = {"cacheCold": 1.5, "cacheWarm": 0.5}.get(method)
    elif class_name.endswith(".LayoutCacheIncrementalBenchmark"):
        limit = 1.0 if method == "withCache" else None
    else:
        limit = None
    if limit is None:
        return "对照", None
    reference = rows.get((class_name + ".noCache", params))
    if reference is None:
        return "未评判", None
    ratio = rows[key]["primaryMetric"]["score"] / reference["primaryMetric"]["score"]
    return ("GOOD" if ratio <= limit else "BAD"), ratio


def row_label(key):
    name = key[0].removeprefix("com.hrm.latex.benchmark.")
    params = ", ".join(f"{k}={v}" for k, v in key[1])
    return f"{name} | {params}"


def render(rows, report_path, baseline, baseline_path):
    sample = next(iter(rows.values()))
    assessments = {key: assessment(key, rows) for key in rows}
    counts = {label: sum(value[0] == label for value in assessments.values())
              for label in ("GOOD", "BAD", "对照", "未评判")}
    lines = [
        "# LayoutCache 性能报告", "",
        f"环境：JMH {sample['jmhVersion']}；{sample['vmName']} {sample['jdkVersion']}；"
        f"{sample['forks']} fork，{sample['threads']} 线程；"
        f"{sample['warmupIterations']} 次预热 × {sample['warmupTime']}，"
        f"{sample['measurementIterations']} 次测量 × {sample['measurementTime']}。", "",
        f"原始数据：`{report_path.name}`，共 {len(rows)} 组 benchmark / 参数。", "",
        "复现方法见仓库中的 `latex-benchmark/PERFORMANCE.md`。", "",
        "本轮判定：" + "；".join(f"{label} {count}" for label, count in counts.items()) + "。", "",
        "报告标准：相同参数下，冷缓存耗时 / 无缓存耗时 ≤ 1.5 判为 GOOD；"
        "热缓存 ≤ 0.5；增量复用 ≤ 1.0。超过标准判为 BAD。"
        "无缓存和独立缓存操作 / hash 基准只作对照；缺少配对的无缓存结果时不评判。"
        "这些阈值是本项目的报告标准，不是任意公式和机器上的通用保证。"
        "BAD 只生成报告，脚本仍以 0 退出，不拦截合入。", "",
        "所有整式基准使用实际 KaTeX 字体，统一调用 `LatexRenderer.measure`。"
        "热缓存提前填充；增量解析和初始缓存填充不计入测量时间。", "",
        "当前生产实现保留 `LatexFontFamilies.hashCode()` 的原有行为，"
        "通过缓存键的设计绕过字体内容和 AST 子树的递归 hash。"
        "`noCache`、独立的 `fontFamiliesHash`、`astHash` 基准用于对照，不能把它们的波动解释为直接优化收益。", "",
        "B/op 来自 JMH GC profiler。增量基准的 Invocation setup 会进入分配统计，"
        "因此表中隐藏其 B/op，原始 JSON 仍保留全部指标。", "",
        "| 基准 | 输入 | 本轮 ms/op | 相对本轮无缓存耗时 | 本轮 B/op | 判定 |",
        "|---|---|---:|---:|---:|---|",
    ]
    for key, entry in rows.items():
        label, ratio = assessments[key]
        relative = f"{ratio:.3f}×" if ratio is not None else "—"
        lines.append(
            f"| {row_label(key)} | {entry['primaryMetric']['score']:.6g} | "
            f"{relative} | {allocation(entry)} | {label} |"
        )
    if baseline is not None:
        lines += [
            "", "## 与基线对照", "",
            f"基线：`{baseline_path.name}`。两份报告的 benchmark、参数、JVM 与采样配置已通过一致性检查。"
            "速度比 = 基线耗时 / 本轮耗时；大于 1 表示更快。", "",
            "| 基准 | 输入 | 基线 ms/op | 本轮 ms/op | 速度比 | 基线 B/op | 本轮 B/op |",
            "|---|---|---:|---:|---:|---:|---:|",
        ]
        for key, old in baseline.items():
            new = rows[key]
            ratio = old["primaryMetric"]["score"] / new["primaryMetric"]["score"]
            lines.append(
                f"| {row_label(key)} | {old['primaryMetric']['score']:.6g} | "
                f"{new['primaryMetric']['score']:.6g} | {ratio:.2f}× | {allocation(old)} | {allocation(new)} |"
            )
    lines += [
        "", "## 适用范围", "",
        "本次结果来自 JVM 测量；基线对照应在同一机器、同一 JVM 和相同参数下执行。"
        "单 fork、短迭代的结果适合确认数量级变化，"
        "不足以证明接近的耗时有稳定差异；误差与原始迭代数据请查看 JSON。"
        "增量基准使用 Invocation setup，还存在逐次计时的固定开销，"
        "极短耗时下不要据此计算精确收益。", "",
        "尚未测量 Android / Redmi 8A。JVM 数据不能换算为该设备的毫秒数，"
        "也不代表已验证滚动帧时间或真机 GC 行为。", "",
    ]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="本轮 JMH JSON 报告")
    parser.add_argument("--baseline", type=Path, help="可选的同配置 JMH 基线报告")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        rows = read_report(args.report)
        baseline = read_report(args.baseline) if args.baseline else None
        if baseline is not None:
            compare(baseline, rows)
        result = render(rows, args.report, baseline, args.baseline)
    except (OSError, ValueError, TypeError, KeyError) as error:
        parser.error(str(error))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result)
    else:
        print(result)


if __name__ == "__main__":
    main()
