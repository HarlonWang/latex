package com.hrm.latex.benchmark

import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.renderer.layout.LayoutCache
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

/** 相同 AST、真实 KaTeX 字体、相同 LatexRenderer.measure 入口，仅切换布局缓存。 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class CacheBenchmark {
    @Param("medium", "large")
    lateinit var input: String

    private lateinit var fixture: BenchmarkFixture
    private lateinit var ast: List<LatexNode>
    private lateinit var warmCache: LayoutCache

    @Setup
    fun setup() {
        fixture = BenchmarkFixture()
        ast = LatexParser().parse(BenchmarkInputs.formula(input)).children
        // 字体加载、glyph 初始化和预填全部排除在计时外。
        fixture.measure(ast)
        warmCache = LayoutCache()
        fixture.measure(ast, warmCache)
    }

    @Benchmark
    fun noCache(bh: Blackhole) {
        bh.consume(fixture.measure(ast))
    }

    @Benchmark
    fun cacheCold(bh: Blackhole) {
        bh.consume(fixture.measure(ast, LayoutCache()))
    }

    @Benchmark
    fun cacheWarm(bh: Blackhole) {
        bh.consume(fixture.measure(ast, warmCache))
    }
}
