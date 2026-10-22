package com.hrm.latex.benchmark

import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.renderer.layout.measureNode
import com.hrm.latex.renderer.model.RenderContext
import com.hrm.latex.renderer.utils.LayoutUtils
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import java.util.concurrent.TimeUnit

/** 固定 AST，分别观察核心节点、完整测量、文本缓存冷启动和宽度约束。 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class RendererMeasureBenchmark {
    @Param("medium", "complex", "matrix", "large")
    lateinit var input: String

    private lateinit var fixture: BenchmarkFixture
    private lateinit var ast: List<LatexNode>
    private lateinit var root: LatexNode.Group
    private lateinit var wrappedContext: RenderContext

    @Setup
    fun setup() {
        fixture = BenchmarkFixture()
        ast = LatexParser().parse(BenchmarkInputs.formula(input)).children
        root = LatexNode.Group(ast)
        wrappedContext = fixture.context.copy(
            layoutHints = fixture.context.layoutHints.copy(
                maxLineWidth = 320f,
                lineBreakingEnabled = true
            )
        )
        val natural = fixture.measure(ast)
        val wrapped = fixture.measure(ast, context = wrappedContext)
        if (input == "large") {
            check(wrapped.layout.height > natural.layout.height) { "长公式必须实际发生换行" }
        }
    }

    /** 将完整公式包装成根节点，跳过顶层编号初始化与画布内边距计算。 */
    @Benchmark
    fun measureRootNode(bh: Blackhole) {
        LayoutUtils.clearCache()
        bh.consume(measureNode(root, fixture.context, fixture.textMeasurer, fixture.density))
    }

    @Benchmark
    fun measureFullWarmText(bh: Blackhole) {
        bh.consume(fixture.measure(ast))
    }

    /** 字体资源与解析结果已加载，但每次新建空的 TextMeasurer；创建成本计入耗时。 */
    @Benchmark
    fun measureFullColdText(bh: Blackhole) {
        bh.consume(fixture.measure(ast, textMeasurer = fixture.newTextMeasurer()))
    }

    /** 320 像素宽度约束走产品自动断行路径；不可拆分的矩阵允许超宽。 */
    @Benchmark
    fun measureWrapped(bh: Blackhole) {
        bh.consume(fixture.measure(ast, context = wrappedContext))
    }
}
