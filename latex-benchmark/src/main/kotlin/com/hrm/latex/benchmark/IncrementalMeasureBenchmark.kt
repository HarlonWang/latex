package com.hrm.latex.benchmark

import com.hrm.latex.parser.IncrementalLatexParser
import com.hrm.latex.parser.LatexParser
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

/** 相同输入序列下的解析器 × 布局缓存二乘二对照；每次操作计量整个输入会话。 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class IncrementalMeasureBenchmark {
    @Param("streaming", "typing")
    lateinit var scenario: String

    private lateinit var fixture: BenchmarkFixture
    private lateinit var chunks: List<String>

    @Setup
    fun setup() {
        fixture = BenchmarkFixture()
        chunks = when (scenario) {
            "streaming" -> BenchmarkInputs.INCREMENTAL_CHUNKS
            "typing" -> BenchmarkInputs.formula("medium").map(Char::toString)
            else -> error("未知增量场景：$scenario")
        }
        fixture.measure(LatexParser().parse(chunks.joinToString("")).children)
    }

    @Benchmark
    fun fullParse_noCache(bh: Blackhole) = runFullParse(false, bh)

    @Benchmark
    fun fullParse_withCache(bh: Blackhole) = runFullParse(true, bh)

    @Benchmark
    fun incrementalParse_noCache(bh: Blackhole) = runIncrementalParse(false, bh)

    @Benchmark
    fun incrementalParse_withCache(bh: Blackhole) = runIncrementalParse(true, bh)

    private fun runFullParse(withCache: Boolean, bh: Blackhole) {
        val parser = LatexParser()
        val cache = if (withCache) LayoutCache() else null
        val source = StringBuilder()
        for (chunk in chunks) {
            source.append(chunk)
            bh.consume(fixture.measure(parser.parse(source.toString()).children, cache))
        }
    }

    private fun runIncrementalParse(withCache: Boolean, bh: Blackhole) {
        val parser = IncrementalLatexParser()
        val cache = if (withCache) LayoutCache() else null
        for (chunk in chunks) {
            parser.append(chunk)
            bh.consume(fixture.measure(parser.getCurrentDocument().children, cache))
        }
    }
}
