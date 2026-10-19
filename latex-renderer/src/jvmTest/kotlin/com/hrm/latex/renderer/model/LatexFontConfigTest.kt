package com.hrm.latex.renderer.model

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.export.LatexExporterState
import com.hrm.latex.renderer.font.MathFontProviderFactory
import com.hrm.latex.renderer.font.MathFontRole
import com.hrm.latex.renderer.measure.LatexMeasurerState
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class LatexFontConfigTest {
    private val serif = systemLatexFontFamilies().copy(
        main = FontFamily.Serif,
        math = FontFamily.Serif
    )
    private val monospace = serif.copy(
        main = FontFamily.Monospace,
        math = FontFamily.Monospace
    )
    private val density = Density(1f)
    private val textMeasurer = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultDensity = density,
        defaultLayoutDirection = LayoutDirection.Ltr
    )
    private val formula = "\\text{WWWWiiii}"

    @Test
    fun configuredFontsOverrideFallbackAndItsCachedProvider() {
        val context = LatexConfig(fontFamilies = monospace).toContext(
            isDark = false,
            fontFamilies = serif,
            provider = MathFontProviderFactory.create(serif)
        )

        assertSame(monospace, context.fontFamilies)
        assertEquals(FontFamily.Monospace, context.fontFamily)
        assertEquals(
            FontFamily.Monospace,
            assertNotNull(context.mathFontProvider).fontFamilyFor(MathFontRole.ROMAN)
        )
    }

    @Test
    fun fontOverridesInvalidateMeasurementCacheAndMatchSvgExport() {
        val measurer = LatexMeasurerState(density, textMeasurer, serif)
        val exporter = LatexExporterState(density, textMeasurer, serif)
        val serifConfig = LatexConfig(fontFamilies = serif)
        val monoConfig = serifConfig.copy(fontFamilies = monospace)

        val serifSize = assertNotNull(measurer.measure(formula, serifConfig))
        val monoSize = assertNotNull(measurer.measure(formula, monoConfig))
        assertNotEquals(serifSize.widthPx, monoSize.widthPx)
        assertEquals(serifSize, measurer.measure(formula, serifConfig))

        for ((config, size) in listOf(serifConfig to serifSize, monoConfig to monoSize)) {
            val svg = assertNotNull(exporter.exportSvg(formula, config))
            assertEquals(ceil(size.widthPx).toInt(), svg.width)
            assertEquals(ceil(size.heightPx).toInt(), svg.height)
        }
    }

    @Test
    fun stateOperationsInheritCreationConfigAndAllowExplicitOverrides() {
        val config = LatexConfig(fontSize = 32.sp, fontFamilies = monospace)
        val measurer = LatexMeasurerState(density, textMeasurer, monospace, config)
        val exporter = LatexExporterState(density, textMeasurer, monospace, config)

        assertEquals(measurer.measure(formula, config), measurer.measure(formula))
        assertEquals(exporter.exportSvg(formula, config), exporter.exportSvg(formula))
        assertEquals(
            measurer.measureBatch(listOf(formula), config),
            measurer.measureBatch(listOf(formula))
        )
        val inline = assertNotNull(measurer.inlineContent(formula))
        assertEquals(measurer.inlineContent(formula, config), inline)

        val overrideConfig = config.copy(fontSize = 16.sp, fontFamilies = serif)
        assertNotEquals(measurer.measure(formula), measurer.measure(formula, overrideConfig))
        assertNotEquals(exporter.exportSvg(formula), exporter.exportSvg(formula, overrideConfig))
    }
}
