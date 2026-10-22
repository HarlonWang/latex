package com.hrm.latex.benchmark

import com.hrm.latex.parser.LatexParser
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

/** 同一段源码每次重新解析并完整测量，覆盖应用接收新公式后的主要同步链路。 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class PipelineBenchmark {
    @Param("medium", "complex", "matrix", "large")
    lateinit var input: String

    private lateinit var fixture: BenchmarkFixture
    private lateinit var parser: LatexParser
    private lateinit var source: String

    @Setup
    fun setup() {
        fixture = BenchmarkFixture()
        parser = LatexParser()
        source = BenchmarkInputs.formula(input)
        fixture.measure(parser.parse(source).children)
    }

    @Benchmark
    fun parseAndMeasure(bh: Blackhole) {
        bh.consume(fixture.measure(parser.parse(source).children))
    }
}
