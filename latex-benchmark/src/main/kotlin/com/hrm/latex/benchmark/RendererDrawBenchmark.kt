package com.hrm.latex.benchmark

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.LayoutDirection
import com.hrm.latex.parser.LatexParser
import com.hrm.latex.renderer.layout.LatexRenderResult
import com.hrm.latex.renderer.layout.LatexRenderer
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
import kotlin.math.ceil

/** 已排版公式的离屏清屏与绘制，不包含测量、位图分配、像素回读或编码。 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class RendererDrawBenchmark {
    @Param("medium", "matrix", "large")
    lateinit var input: String

    private lateinit var fixture: BenchmarkFixture
    private lateinit var result: LatexRenderResult
    private lateinit var bitmap: ImageBitmap
    private lateinit var canvas: Canvas
    private lateinit var drawScope: CanvasDrawScope

    @Setup
    fun setup() {
        fixture = BenchmarkFixture()
        result = fixture.measure(LatexParser().parse(BenchmarkInputs.formula(input)).children)
        check(result.canvasWidth > 0f && result.canvasHeight > 0f) { "绘制输入必须具有有效尺寸" }
        bitmap = ImageBitmap(ceil(result.canvasWidth).toInt(), ceil(result.canvasHeight).toInt())
        canvas = Canvas(bitmap)
        drawScope = CanvasDrawScope()
        drawFormula()
    }

    @Benchmark
    fun draw(bh: Blackhole) {
        drawFormula()
        bh.consume(bitmap)
    }

    private fun drawFormula() {
        drawScope.draw(
            density = fixture.density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(result.canvasWidth, result.canvasHeight)
        ) {
            drawRect(Color.Transparent, blendMode = BlendMode.Clear)
            with(LatexRenderer) { draw(result) }
        }
    }
}
