package com.hrm.latex.benchmark

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.renderer.font.loadKatexFonts
import com.hrm.latex.renderer.layout.LatexRenderer
import com.hrm.latex.renderer.layout.LayoutCache
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.RenderContext
import com.hrm.latex.renderer.model.toContext
import kotlinx.coroutines.runBlocking

/** 直接使用产品加载器和字体资源；资源缺失时让基准失败，避免无声回退到系统字体。 */
internal class BenchmarkFixture {
    val families = runBlocking { loadKatexFonts() }
    val density = Density(2f, 1f)
    val config = LatexConfig(fontSize = 20.sp, fontFamilies = families)
    val context = config.toContext(false, families)
    private val fontResolver = createFontFamilyResolver()
    val textMeasurer = newTextMeasurer()

    fun newTextMeasurer() = TextMeasurer(fontResolver, density, LayoutDirection.Ltr)

    fun measure(
        nodes: List<LatexNode>,
        cache: LayoutCache? = null,
        textMeasurer: TextMeasurer = this.textMeasurer,
        context: RenderContext = this.context
    ) = LatexRenderer.measure(nodes, context, textMeasurer, density, cache = cache)
}
