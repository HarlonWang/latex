package com.hrm.latex.benchmark

import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.parser.model.SourceRange
import com.hrm.latex.renderer.editor.CursorCalculator
import com.hrm.latex.renderer.layout.LayoutMap
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

/** 固定布局条目规模，单独测量编辑器查询，排除解析、字体与布局构建。 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class EditorBenchmark {
    @Param("100", "1000")
    @JvmField
    final var entries: Int = 0

    private lateinit var map: LayoutMap
    private lateinit var selection: SourceRange
    private var sourceOffset = 0
    private var pointX = 0f
    private var pointY = 0f

    @Setup
    fun setup() {
        map = LayoutMap()
        repeat(entries) { index ->
            map.add(
                node = LatexNode.Text("x", SourceRange(index * 2, index * 2 + 1)),
                relX = (index % 50) * 12f,
                relY = (index / 50) * 24f,
                width = 10f,
                height = 20f,
                baseline = 15f,
            )
        }
        val target = entries * 3 / 4
        sourceOffset = target * 2
        pointX = (target % 50) * 12f + 5f
        pointY = (target / 50) * 24f + 10f
        selection = SourceRange(entries / 2, entries)
        check(map.size == entries)
        check(map.hitTest(pointX, pointY)?.node?.sourceRange?.start == sourceOffset)
        check(map.entryAt(sourceOffset) != null)
        check(map.entriesInRange(selection).isNotEmpty())
        check(CursorCalculator.calculate(sourceOffset, map, 0f, 0f) != null)
    }

    @Benchmark
    fun hitTest(bh: Blackhole) = bh.consume(map.hitTest(pointX, pointY))

    @Benchmark
    fun entryAt(bh: Blackhole) = bh.consume(map.entryAt(sourceOffset))

    @Benchmark
    fun entriesInRange(bh: Blackhole) = bh.consume(map.entriesInRange(selection))

    @Benchmark
    fun cursorPosition(bh: Blackhole) = bh.consume(
        CursorCalculator.calculate(sourceOffset, map, 0f, 0f)
    )

    @Benchmark
    fun cursorHitTest(bh: Blackhole) = bh.consume(
        CursorCalculator.hitTestToOffset(pointX, pointY, map, 0f, 0f, entries * 2)
    )

    @Benchmark
    fun cursorNearest(bh: Blackhole) = bh.consume(
        CursorCalculator.hitTestToOffset(-20f, -20f, map, 0f, 0f, entries * 2)
    )
}
