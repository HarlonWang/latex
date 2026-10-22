package com.hrm.latex.benchmark

import com.hrm.latex.parser.IncrementalLatexParser
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.renderer.layout.LayoutCache
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** 仅计量一次追加后的测量；每次 invocation 都恢复到只缓存追加前 AST 的状态。 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class LayoutCacheIncrementalBenchmark {
    @Param("medium", "large")
    lateinit var input: String

    private lateinit var fixture: LayoutCacheBenchmarkFixture
    private lateinit var previous: List<LatexNode>
    private lateinit var appended: List<LatexNode>
    private lateinit var cache: LayoutCache

    @Setup(Level.Trial)
    fun setup() {
        fixture = LayoutCacheBenchmarkFixture()
        val parser = IncrementalLatexParser()
        parser.append(layoutCacheInput(input))
        previous = parser.getCurrentDocument().children
        parser.append(" + \\alpha^2")
        appended = parser.getCurrentDocument().children
        check(previous.any { before -> appended.any { after -> before === after } }) {
            "Incremental fixture must retain unchanged AST instances"
        }
        fixture.measure(appended)
        cache = LayoutCache()
    }

    @Setup(Level.Invocation)
    fun restorePrefixCache() {
        cache.clear()
        fixture.measure(previous, cache)
    }

    @Benchmark
    fun withCache(bh: Blackhole) {
        bh.consume(fixture.measure(appended, cache))
    }

    @Benchmark
    fun noCache(bh: Blackhole) {
        bh.consume(fixture.measure(appended))
    }
}
