# LaTeX 性能验证

统一入口为 `:latex-benchmark:performanceBenchmark`，使用 JDK 21 和 JMH。性能覆盖库的主要阶段；GOOD/BAD 只作报告，不拦截合入。单元测试仍须全部通过。

## 测试矩阵

| 阶段 | 测量范围 | 参数组合数 |
|---|---|---:|
| 分词 | 简单、复杂、矩阵、长公式、宏、深嵌套、文本、多行、错误恢复等 10 类输入 | 10 |
| 解析与诊断 | 同一语料的 parse、parseWithDiagnostics | 20 |
| AST 转换 | MathML、无障碍描述、树结构 Printer；预解析 AST | 12 |
| 单次增量编辑 | 追加、中间替换、删除；中等、长公式、宏；增量与全量对照 | 18 |
| 流式解析 | 中等、长公式；每块 1 或 16 字符；增量与全量对照 | 8 |
| 字体资源加载 | 正常读取真实字体字节并构建字体集 | 1 |
| 布局测量 | 根节点、完整测量、空 TextMeasurer、320px 自动换行 | 16 |
| parse → measure | 中等、复杂、矩阵、长公式的完整链路 | 4 |
| 输入 → 解析 → 测量 | 流式与逐字输入；解析器和布局缓存的二乘二对照 | 8 |
| 离屏绘制 | 已排版的中等公式、矩阵、长公式；清屏并重绘 | 3 |
| 导出 | 中等公式、矩阵的 PNG、SVG 字形路径、SVG 文本 | 6 |
| 编辑器查询 | 100/1000 条布局的坐标命中、源码查找、选区与光标定位 | 12 |
| 布局缓存 | 中等、长公式的无缓存、冷缓存、热缓存 | 6 |
| **合计** | **布局缓存只作为一个普通分组** | **124** |

`BenchmarkInputs.formula()` 统一维护语料。合法语料在 Trial setup 检查诊断，错误语料必须进入恢复路径；增量用例检查最终可见结构和 MathML 与全量结果一致。访问器中的 Printer 输出的是 AST 树结构，不是 LaTeX 源码序列化。

## 字体与计时边界

涉及字体的基准统一使用产品加载器和 `latex-fonts-katex` 中的真实 KaTeX 字体及字节。只测资源齐全、正常加载的路径，不设置系统字体、资源缺失或加载失败的性能场景。分词、解析、AST 转换和预填布局的编辑器查询不依赖字体。

- 除字体加载基准外，字体资源加载和固定 AST 解析均放在 Trial setup，排除初始化成本。复用 TextMeasurer 表示复用产品文本缓存，不保证每个文本片段始终命中。
- 单次增量编辑的首次解析放在 Invocation setup，两条路径处理相同编辑后的输入。其 B/op、GC 次数和时间会包含准备工作，报告隐藏这些指标。流式基准按整批输入计量，包含本批初始化、所有前缀处理和结果消费；不完整前缀的容错策略允许不同。
- 空 TextMeasurer 测量包含创建新测量器的成本，字体资源和字体解析器仍已准备好。根节点测量将整个公式包装为 Group，跳过顶层编号初始化和画布内边距流程。
- 绘制预先创建位图并完成测量，计时只含清屏和离屏重绘；导出包含产品路径中的解析、测量、绘制与编码。字体加载受操作系统文件缓存影响，不等于完整字体解码或应用冷启动首帧。
- 所有结果都被消费，避免只调用后丢弃结果；无效输出应失败，不能把返回空结果的错误路径当作性能收益。

这些是 JVM 基准，不能替代 Android 真机滚动帧、屏幕合成或 GPU 呈现测试。GC profiler 的 B/op 仅覆盖 JVM 托管分配，不包含 Skia/native/GPU 内存。编辑器查询使用预填叶节点，不能代表复合公式内部的光标定位。

## 运行与报告

完整采样默认 1 个 fork、3 次预热、5 次测量，每次 500ms，固定 JVM 堆为 256MiB–1GiB，默认开启 GC profiler：

```sh
./gradlew :latex-benchmark:performanceBenchmark
python3 latex-benchmark/report_performance.py \
  latex-benchmark/build/reports/performance/jmh.json \
  --output latex-benchmark/build/reports/performance/summary.md
```

CI 使用同一入口的短采样，覆盖全部组合。短迭代用于发现明显异常，接近阈值时应增加预热、迭代或 fork 复核：

```sh
./gradlew :latex-benchmark:performanceBenchmark \
  -PbenchmarkWarmups=2 -PbenchmarkIterations=3 -PbenchmarkIterationMs=200
```

可通过 `benchmarkInclude` 按 JMH 名称筛选；例如只排查解析和测量：

```sh
./gradlew :latex-benchmark:performanceBenchmark \
  '-PbenchmarkInclude=com.hrm.latex.benchmark.(ParserBenchmark|RendererMeasureBenchmark).*'
```

报告按阶段展示均值 ± JMH scoreError、有效 B/op、GC 次数与时间，不把迭代均值称为单次调用 p95。原始 JSON、日志和生成报告均位于忽略的 `build/reports/performance/`，不随源码提交。CI 将报告写入 Actions 运行摘要，并上传 `performance-reports` 附件。

## GOOD/BAD 的依据

没有历史基线时，使用 [performance-budgets.json](performance-budgets.json) 中明示的分组耗时与分配预算。预算用于发现数量级异常，不是 SLA，不为取得全 GOOD 倒推阈值；没有可用预算的条目标为“未评判”。

提供历史基线时，先检查用例、参数、JVM 和采样配置一致，再按配置中的增长比例判定，默认耗时或有效分配增长超过 25% 为 BAD。同一机器与相同字体资源的结果才适合比较：

```sh
python3 latex-benchmark/report_performance.py \
  latex-benchmark/build/reports/performance/jmh.json \
  --baseline /path/to/baseline.json \
  --output latex-benchmark/build/reports/performance/comparison.md
```

GOOD 和 BAD 均正常退出；报告损坏或基线不可比较时非零退出。性能任务不作合入门禁，报告工具自身的单元测试仍属于单元测试门禁：

```sh
python3 -m unittest discover -s latex-benchmark -p 'test_report_performance.py'
```

可调整参数：`benchmarkWarmups`、`benchmarkIterations`、`benchmarkIterationMs`、`benchmarkForks`、`benchmarkInclude`、`benchmarkReport`（输出 JSON 路径）、`benchmarkGc`（默认 true）。只有一个专用执行入口和一个报告脚本，不另设缓存专用流程。
