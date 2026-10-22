package com.hrm.latex.renderer.measure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.hrm.latex.renderer.model.systemLatexFontFamilies
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LatexInlineContentRenderTest {
    private val density = Density(2f)
    private val fonts = systemLatexFontFamilies()
    private val config = LatexConfig(fontFamilies = fonts, theme = LatexTheme.light())
    private val formula = "\\frac{a^2 + b}{\\sqrt{c}} = \\sum_{n=1}^{\\infty} x_n"

    private fun measurer() = LatexMeasurerState(
        density = density,
        textMeasurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr),
        fontFamilies = fonts,
        defaultConfig = config
    )

    @Test
    fun inlineContentDrawsSamePixelsAsLatex() {
        val measurer = measurer()
        val dimensions = assertNotNull(measurer.measure(formula, config))
        val inline = assertNotNull(measurer.inlineContent(formula, config))
        val width = ceil(dimensions.widthPx).toInt()
        val height = ceil(dimensions.heightPx).toInt()

        // 行内内容直接绘制预测量结果，输出必须与 Latex() 完整渲染逐像素一致
        val inlinePixels = renderFrames(width, height, frames = 1) { inline.children("") }
        // Latex() 在后台解析，多渲染几帧确保已稳定
        val latexPixels = renderFrames(width, height, frames = 20) { Latex(formula, config = config, isDarkTheme = false) }
        assertTrue(inlinePixels.hasInk(), "inline content should draw the formula")
        assertEquals(latexPixels.pixels(), inlinePixels.pixels())
    }

    @Test
    fun measureReusesDimensionsFromInlineContent() {
        val measurer = measurer()
        val inline = assertNotNull(measurer.inlineContent(formula, config))
        val dimensions = assertNotNull(measurer.measure(formula, config))
        assertEquals(dimensions.widthPx / density.density, inline.placeholder.width.value)
        assertEquals(dimensions.heightPx / density.density, inline.placeholder.height.value)
    }

    @Test
    fun inlineContentSwappedAtSameSlotDrawsNewFormula() {
        // 流式输出时同一个占位 id 下的公式会变，组合位置不变也必须画新公式
        val measurer = measurer()
        val first = assertNotNull(measurer.inlineContent("x", config))
        val second = assertNotNull(measurer.inlineContent(formula, config))
        val dimensions = assertNotNull(measurer.measure(formula, config))
        val width = ceil(dimensions.widthPx).toInt()
        val height = ceil(dimensions.heightPx).toInt()

        val current = mutableStateOf(first)
        val swappedPixels = ImageComposeScene(width, height, density) { current.value.children("") }.use { scene ->
            scene.render(0)
            current.value = second
            scene.render(16_000_000L).toComposeImageBitmap().toPixelMap()
        }
        val expectedPixels = renderFrames(width, height, frames = 1) { second.children("") }
        assertEquals(expectedPixels.pixels(), swappedPixels.pixels())
    }

    private fun renderFrames(width: Int, height: Int, frames: Int, content: @Composable () -> Unit): PixelMap =
        ImageComposeScene(width, height, density) { content() }.use { scene ->
            var image = scene.render(0)
            for (frame in 1 until frames) {
                Thread.sleep(10)
                image = scene.render(frame * 16_000_000L)
            }
            image.toComposeImageBitmap().toPixelMap()
        }

    private fun PixelMap.pixels(): List<Int> =
        (0 until height).flatMap { y -> (0 until width).map { x -> this[x, y].hashCode() } }

    private fun PixelMap.hasInk(): Boolean =
        (0 until height).any { y -> (0 until width).any { x -> this[x, y].alpha > 0f } }
}
