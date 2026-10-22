package com.hrm.latex.benchmark

import com.hrm.latex.renderer.export.ExportConfig
import com.hrm.latex.renderer.export.ImageFormat
import com.hrm.latex.renderer.export.LatexExporterState
import com.hrm.latex.renderer.export.SvgExportConfig
import com.hrm.latex.renderer.export.SvgTextMode
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

/** 产品导出完整链路：解析、测量、离屏绘制与编码；三个格式均使用相同字号和一倍倍率。 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class ExportBenchmark {
    @Param("medium", "matrix")
    lateinit var input: String

    private lateinit var source: String
    private lateinit var exporter: LatexExporterState
    private val pngConfig = ExportConfig(scale = 1f, format = ImageFormat.PNG, transparentBackground = true)
    private val pathConfig = SvgExportConfig(textMode = SvgTextMode.PATH, prettyPrint = false)
    private val textConfig = SvgExportConfig(textMode = SvgTextMode.TEXT, prettyPrint = false)

    @Setup
    fun setup() {
        val fixture = BenchmarkFixture()
        source = BenchmarkInputs.formula(input)
        exporter = LatexExporterState(fixture.density, fixture.textMeasurer, fixture.families, fixture.config)
        // 先触发字体与编码器初始化；无效导出直接失败，避免把返回 null 的错误路径当成性能收益。
        check(checkNotNull(exporter.export(source, exportConfig = pngConfig)).bytes?.isNotEmpty() == true) {
            "PNG 初始化导出失败"
        }
        check(checkNotNull(exporter.exportSvg(source, exportConfig = pathConfig)).svg.contains("<svg")) {
            "SVG 字形路径初始化导出失败"
        }
        check(checkNotNull(exporter.exportSvg(source, exportConfig = textConfig)).svg.contains("<svg")) {
            "SVG 文本初始化导出失败"
        }
    }

    @Benchmark
    fun png(bh: Blackhole) {
        bh.consume(checkNotNull(exporter.export(source, exportConfig = pngConfig)))
    }

    @Benchmark
    fun svgPath(bh: Blackhole) {
        bh.consume(checkNotNull(exporter.exportSvg(source, exportConfig = pathConfig)))
    }

    @Benchmark
    fun svgText(bh: Blackhole) {
        bh.consume(checkNotNull(exporter.exportSvg(source, exportConfig = textConfig)))
    }
}
