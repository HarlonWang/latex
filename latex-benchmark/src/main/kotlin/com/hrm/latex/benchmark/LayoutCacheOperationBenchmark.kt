package com.hrm.latex.benchmark

import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.renderer.layout.LayoutCache
import com.hrm.latex.renderer.layout.NodeLayout
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

/** 拆分字体/AST hash 和缓存操作，辅助定位收益来自哪一层。 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class LayoutCacheOperationBenchmark {
    @Param("medium", "deep")
    lateinit var input: String

    private lateinit var fixture: LayoutCacheBenchmarkFixture
    private lateinit var node: LatexNode
    private lateinit var missingNode: LatexNode
    private lateinit var cache: LayoutCache
    private lateinit var layout: NodeLayout

    @Setup
    fun setup() {
        fixture = LayoutCacheBenchmarkFixture()
        val children = LatexParser().parse(layoutCacheInput(input)).children
        node = LatexNode.Group(children)
        missingNode = LatexNode.Group(children + LatexNode.Symbol("z", "z"))
        layout = NodeLayout(1f, 1f, 1f) { _, _ -> }
        cache = LayoutCache()
        cache.putNode(node, fixture.context, layout)
    }

    @Benchmark
    fun getNodeHit(bh: Blackhole) {
        bh.consume(cache.getNode(node, fixture.context))
    }

    @Benchmark
    fun getNodeMiss(bh: Blackhole) {
        bh.consume(cache.getNode(missingNode, fixture.context))
    }

    @Benchmark
    fun putNodeReplace() = cache.putNode(node, fixture.context, layout)

    @Benchmark
    fun fontFamiliesHash() = fixture.families.hashCode()

    @Benchmark
    fun astHash() = node.hashCode()
}
