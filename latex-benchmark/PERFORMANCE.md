# 布局缓存基准复现

从仓库根目录执行，需要 JDK 21。性能数字只用于同一机器、同一 JVM、同一组参数下的前后对比，不能直接换算为 Android 真机耗时。

本地生成的 [对照报告](build/reports/layout-cache/layout-cache-comparison.md) 保留全部 26 组结果，原始数据为
[优化前 JSON](build/reports/layout-cache/layout-cache-before.json) 和 [优化后 JSON](build/reports/layout-cache/layout-cache-after.json)。
这些结果位于被 Git 忽略的构建目录，运行以下命令后生成，不随源码提交。

## 测量内容

- `CacheBenchmark`：medium、complex、large、deep 四组输入，分别比较无缓存、每次新建空缓存、已预填的热缓存。三者都调用产品的 `LatexRenderer.measure`，字体加载、解析和热缓存预填在 `@Setup` 完成。冷缓存的创建和填充计入耗时。
- `LayoutCacheIncrementalBenchmark`：medium、large 两组公式追加 ` + \alpha^2`。Trial setup 通过增量解析器生成前后 AST，并检查未变更节点确实保留对象实例；Invocation setup 仅填充追加前的缓存；计时只包含追加后的一次测量，避免后续 invocation 退化为整个公式的热命中。
- `LayoutCacheOperationBenchmark`：拆分节点缓存 hit、miss、替换已有节点、字体集 hash 和 AST hash。只做缓存操作的基准用固定布局值，避免混入测量成本。

所有新增基准均调用产品的 `loadKatexFonts`，从 `latex-fonts-katex` 资源加载实际字体对象和字节；缺失资源会直接失败。默认参与字体内容 hash 的 7 份 TTF 合计 190,208 字节。`deep` 是 96 层花括号分组，用于暴露子树结构 hash 随深度重复遍历的问题。TextMeasurer 保留默认内部缓存，模拟组件持有同一 TextMeasurer 的行为。

旧的 `IncrementalMeasureBenchmark` 仍测量 parse + measure 整条流程，`RendererMeasureBenchmark` 仍使用系统字体；下述专用配置不会包含它们。

## 运行和导出 JSON

专用性能入口只有 `layoutCacheJmh`。它使用 kotlinx-benchmark 生成的标准 JMH 基准，默认 1 个 fork、2 次预热、3 次测量，每次 200 ms，共 26 个参数组合：

```sh
./gradlew :latex-benchmark:layoutCacheJmh
```

JSON 位于 `latex-benchmark/build/reports/layout-cache/jmh.json`。生成本轮 GOOD / BAD 报告，无需历史基线：

```sh
python3 latex-benchmark/report_layout_cache.py \
  latex-benchmark/build/reports/layout-cache/jmh.json \
  --output latex-benchmark/build/reports/layout-cache/summary.md
```

按相同输入比较本轮缓存与无缓存：冷缓存耗时不超过无缓存的 1.5 倍、热缓存不超过 0.5 倍、增量复用不超过 1 倍时判为 GOOD，否则为 BAD。无缓存和独立缓存操作 / hash 基准只作对照。阈值是本项目的报告标准，不保证所有公式和机器均满足。

BAD 仅写入报告，脚本仍以 0 退出，不拦截合入；报告损坏、读取失败或可选基线配置不一致时以非零退出。

可固定输出文件，优化前后使用完全相同的参数：

```sh
./gradlew :latex-benchmark:layoutCacheJmh \
  -PlayoutCacheReport=latex-benchmark/build/reports/layout-cache/layout-cache-before.json

# 修改生产实现后再次执行
./gradlew :latex-benchmark:layoutCacheJmh \
  -PlayoutCacheReport=latex-benchmark/build/reports/layout-cache/layout-cache-after.json
```

更长的确认运行：

```sh
./gradlew :latex-benchmark:layoutCacheJmh \
  -PlayoutCacheWarmups=5 -PlayoutCacheIterations=5 \
  -PlayoutCacheIterationMs=1000 -PlayoutCacheForks=2 \
  -PlayoutCacheReport=latex-benchmark/build/reports/layout-cache/confirmed.json
```

短跑适合定位数量级变化；不要把少量短迭代的均值当成稳定的精确速度比。JMH JSON 同时保存误差和原始迭代样本。

生成对照表（脚本会先检查 benchmark、参数、JVM 和采样配置是否一致）：

```sh
python3 latex-benchmark/report_layout_cache.py \
  latex-benchmark/build/reports/layout-cache/layout-cache-after.json \
  --baseline latex-benchmark/build/reports/layout-cache/layout-cache-before.json \
  --output latex-benchmark/build/reports/layout-cache/layout-cache-comparison.md
```

脚本只读取原始 JSON，不会修改测量数据。

## GC 和分配量

插件的 runner 不支持 profiler 透传，`layoutCacheJmh` 任务使用标准 JMH 的 `-prof gc`。以下命令报告耗时、`gc.alloc.rate.norm`（B/op）和 GC 指标：

```sh
./gradlew :latex-benchmark:layoutCacheJmh \
  -PlayoutCacheGc=true \
  '-PlayoutCacheInclude=com.hrm.latex.benchmark.(CacheBenchmark|LayoutCacheOperationBenchmark).*' \
  -PlayoutCacheReport=latex-benchmark/build/reports/layout-cache/allocation.json
```

增量基准的 Invocation setup 不计入单次测量时间，但 JMH GC profiler 会统计该阶段发生的分配。因此上面的分配量命令排除了增量基准；不要把增量基准的 B/op 解读为仅追加后测量的分配量。

任务支持以下参数：

| Gradle 属性 | 默认值 | 作用 |
|---|---|---|
| `layoutCacheWarmups` | 2 | 预热迭代次数 |
| `layoutCacheIterations` | 3 | 测量迭代次数 |
| `layoutCacheIterationMs` | 200 | 每次迭代目标时长（毫秒） |
| `layoutCacheForks` | 1 | JVM fork 数量 |
| `layoutCacheInclude` | 三个专用 benchmark 类 | JMH 正则筛选 |
| `layoutCacheReport` | `latex-benchmark/build/reports/layout-cache/jmh.json` | 直接 JMH 任务的 JSON 路径 |
| `layoutCacheGc` | false | 直接 JMH 任务启用 GC profiler |
