package com.hrm.latex.benchmark

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.renderer.benchmark.MeasureBenchmarkHelper
import com.hrm.latex.renderer.font.loadKatexFonts
import com.hrm.latex.renderer.layout.LatexRenderer
import com.hrm.latex.renderer.layout.LayoutCache
import kotlinx.coroutines.runBlocking

/** 直接使用产品加载器和字体资源；资源缺失时让基准失败，避免无声回退到系统字体。 */
internal class LayoutCacheBenchmarkFixture {
    val families = runBlocking { loadKatexFonts() }
    val density = Density(2f, 1f)
    val context = MeasureBenchmarkHelper.createContext(fontFamilies = families)
    val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)

    fun measure(nodes: List<LatexNode>, cache: LayoutCache? = null) =
        LatexRenderer.measure(nodes, context, measurer, density, cache = cache)
}

internal fun layoutCacheInput(name: String): String = when (name) {
    "medium" -> BenchmarkInputs.MEDIUM
    "complex" -> BenchmarkInputs.COMPLEX
    "large" -> BenchmarkInputs.LARGE
    "deep" -> "{".repeat(96) + "x" + "}".repeat(96)
    else -> error("Unknown layout-cache benchmark input: $name")
}
