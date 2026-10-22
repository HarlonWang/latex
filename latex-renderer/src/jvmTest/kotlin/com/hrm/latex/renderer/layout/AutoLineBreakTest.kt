package com.hrm.latex.renderer.layout

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.parser.model.SourceRange
import com.hrm.latex.renderer.editor.CursorCalculator
import com.hrm.latex.renderer.export.ImageFormat
import com.hrm.latex.renderer.export.encodeToFormat
import com.hrm.latex.renderer.font.MathFontProvider
import com.hrm.latex.renderer.font.loadKatexFonts
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.RenderContext
import com.hrm.latex.renderer.model.TextDirection
import com.hrm.latex.renderer.model.toContext
import com.hrm.latex.renderer.utils.lineSpacingPx
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutoLineBreakTest {
    private val density = Density(1f)
    private val textMeasurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
    private val context = LatexConfig(fontSize = 20.sp, fontFamilies = fonts).toContext(false, fonts)

    @Test
    fun fittingContentMeasuresEachLetterOnlyOnce() {
        val nodes = textNodes("a+b=c+d")
        val expected = measure(nodes)
        val provider = CountingMathFontProvider(assertNotNull(context.mathFontProvider))

        val actual = measure(nodes, wrappingContext(expected.width + 1f).copy(mathFontProvider = provider))

        assertSameDimensions(expected, actual)
        assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1, "d" to 1), provider.letterMeasurements)
    }

    @Test
    fun wrappingMeasuresEachLetterOnlyOnceWithoutLayoutCache() {
        val nodes = textNodes("a+b=c+d=e+f")
        val unwrapped = measure(nodes)
        val provider = CountingMathFontProvider(assertNotNull(context.mathFontProvider))

        // 不提供 LayoutCache，避免缓存命中掩盖同轮先预测量、再分行复测的问题。
        val actual = measure(nodes, wrappingContext(unwrapped.width * 0.4f).copy(mathFontProvider = provider))

        assertTrue(actual.height > unwrapped.height, "The fixture must really wrap")
        assertEquals(('a'..'f').associate { it.toString() to 1 }, provider.letterMeasurements)
    }

    @Test
    fun texSpacingParticipatesInTheWidthLimit() {
        val nodes = textNodes("a+b")
        val unwrapped = measure(nodes)
        val rawNodeWidth = nodes.sumOf { measureNode(it, context, textMeasurer, density).width.toDouble() }.toFloat()
        assertTrue(unwrapped.width > rawNodeWidth + EPSILON, "The fixture must have implicit TeX spacing")
        val limit = (rawNodeWidth + unwrapped.width) / 2f

        val wrapped = measure(nodes, wrappingContext(limit))

        assertTrue(wrapped.height > unwrapped.height, "Implicit spacing must trigger wrapping")
        assertTrue(wrapped.width <= limit + EPSILON, "Every splittable line must fit the width limit")
    }

    @Test
    fun anOperatorStartingANewLineUsesThatLinesSpacing() {
        val nodes = textNodes("a+b")
        val firstLine = measure(nodes.take(1))
        val secondLine = measure(nodes.drop(1))
        val limit = maxOf(firstLine.width, secondLine.width) + 0.1f
        assertTrue(limit < measure(nodes).width, "The fixture must need a break before plus")

        val actual = measure(nodes, wrappingContext(limit))

        // 新行开头的加号应按一元符号处理，不能沿用原整行的二元符号间距。
        assertEquals(maxOf(firstLine.width, secondLine.width), actual.width, EPSILON)
        assertEquals(firstLine.height + lineSpacingPx(context, density) + secondLine.height, actual.height, EPSILON)
        assertEquals(firstLine.baseline, actual.baseline, EPSILON)
    }

    @Test
    fun wrappingAdvancesEquationNumbersOnlyOnce() {
        val environments = (1..10).map { index ->
            LatexNode.Environment(
                name = "equation",
                content = listOf(LatexNode.Text("x"), LatexNode.Label("eq$index")),
            )
        }
        val state = EquationNumberingState(EquationNumbering.buildLabelMap(environments))
        val numberedContext = wrappingContext(90f).copy(equationNumbering = state)

        val layout = measureGroup(environments, numberedContext, textMeasurer, density)

        assertTrue(layout.height > measure(listOf(environments.first())).height)
        assertEquals("11", state.nextNumber(), "Each environment must consume exactly one number")
        assertEquals("9", state.resolveLabel("eq9"))
        assertEquals("10", state.resolveLabel("eq10"))
    }

    @Test
    fun wrappingPreservesReferencesAndManualNumberingRules() {
        val numbered = LatexNode.Environment("equation", listOf(LatexNode.Text("x"), LatexNode.Label("target")))
        val starred = LatexNode.Environment("equation*", listOf(LatexNode.Text("y")))
        val tagged = LatexNode.Environment("equation", listOf(LatexNode.Text("z"), LatexNode.Tag(LatexNode.Text("custom"))))
        val reference = LatexNode.Ref("target")
        val equationReference = LatexNode.EqRef("target")
        val nodes = listOf(numbered, starred, tagged, reference, equationReference)
        val state = EquationNumberingState(EquationNumbering.buildLabelMap(nodes))
        val numberedContext = wrappingContext(90f).copy(equationNumbering = state)

        measureGroup(nodes, numberedContext, textMeasurer, density)

        assertEquals("2", state.nextNumber(), "Starred and manually tagged environments must not consume numbers")
        assertSameDimensions(
            measureNode(LatexNode.Text("1"), numberedContext, textMeasurer, density),
            measureNode(reference, numberedContext, textMeasurer, density),
        )
        assertSameDimensions(
            measureNode(LatexNode.Text("(1)"), numberedContext, textMeasurer, density),
            measureNode(equationReference, numberedContext, textMeasurer, density),
        )
    }

    @Test
    fun explicitLinesKeepTheirCoordinatesAndHitTesting() {
        val firstNodes = textNodes("a+b")
        val secondNodes = textNodes("c=d", sourceStart = 4)
        val nodes = firstNodes + LatexNode.NewLine(sourceRange = SourceRange(3, 4)) + secondNodes
        val map = LayoutMap()
        val result = LatexRenderer.measure(nodes, context, textMeasurer, density, layoutMap = map)
        val firstLine = measure(firstNodes)
        val secondLine = measure(secondNodes)

        assertLineEntries(firstNodes, map, context, top = 0f)
        assertLineEntries(secondNodes, map, context, top = firstLine.height + lineSpacingPx(context, density))
        assertEquals(firstNodes.size + secondNodes.size, map.size)
        assertEquals(firstLine.height + lineSpacingPx(context, density) + secondLine.height, result.layout.height, EPSILON)
        assertHitTesting(nodes.filterNot { it is LatexNode.NewLine }, map, result)
    }

    @Test
    fun automaticLinesKeepAllCoordinatesWithinTheirOwnRows() {
        val nodes = textNodes("a+b=c+d=e+f")
        val limit = measure(nodes).width * 0.4f
        val wrapping = wrappingContext(limit)
        val map = LayoutMap()
        val result = LatexRenderer.measure(nodes, wrapping, textMeasurer, density, layoutMap = map)

        val rows = rows(map)
        assertTrue(rows.size > 1, "The fixture must really wrap")
        assertEquals(nodes.size, map.size)
        var top = 0f
        for (row in rows) {
            val rowNodes = nodes.filter { node -> row.any { it.node === node } }
            val expectedLine = measure(rowNodes)
            assertTrue(expectedLine.width <= limit + EPSILON)
            assertLineEntries(rowNodes, map, context, top)
            top += expectedLine.height + lineSpacingPx(context, density)
        }
        assertEquals(top - lineSpacingPx(context, density), result.layout.height, EPSILON)
        assertHitTesting(nodes, map, result)
    }

    @Test
    fun explicitLinesCanEachWrapWithoutMeasuringLettersAgain() {
        val first = textNodes("a+b=c+d")
        val second = textNodes("e+f=g+h", sourceStart = 8)
        val nodes = first + LatexNode.NewLine(sourceRange = SourceRange(7, 8)) + second
        val limit = minOf(measure(first).width, measure(second).width) * 0.55f
        val provider = CountingMathFontProvider(assertNotNull(context.mathFontProvider))
        val wrapping = wrappingContext(limit).copy(mathFontProvider = provider)
        val map = LayoutMap()

        val result = LatexRenderer.measure(nodes, wrapping, textMeasurer, density, layoutMap = map)

        assertTrue(rows(map).size >= 4, "Both explicit lines must also wrap")
        assertEquals(('a'..'h').associate { it.toString() to 1 }, provider.letterMeasurements)
        assertEquals(first.size + second.size, map.size)
        assertHitTesting(first + second, map, result)
    }

    @Test
    fun rtlCoordinatesMirrorEachLineAndRemainHitTestable() {
        val nodes = textNodes("a+b=c+d=e+f")
        val limit = measure(nodes).width * 0.4f
        val wrapping = wrappingContext(limit)
        val ltrMap = LayoutMap()
        val rtlMap = LayoutMap()
        val ltr = LatexRenderer.measure(nodes, wrapping, textMeasurer, density, layoutMap = ltrMap)
        val rtl = LatexRenderer.measure(
            nodes, wrapping.copy(textDirection = TextDirection.RTL), textMeasurer, density, layoutMap = rtlMap,
        )

        assertSameDimensions(ltr.layout, rtl.layout)
        assertEquals(nodes.size, rtlMap.size)
        assertTrue(rows(ltrMap).size > 1)
        for (row in rows(ltrMap)) {
            val lineWidth = row.maxOf { it.relX + it.width }
            for (entry in row) {
                val mirrored = assertNotNull(rtlMap.entries().find { it.node === entry.node })
                // 每行内部从右向左排列；多行容器继续遵循现有的左对齐约定。
                assertEquals(lineWidth - entry.relX - entry.width, mirrored.relX, EPSILON)
                assertEquals(entry.relY, mirrored.relY, EPSILON)
                assertEquals(entry.width, mirrored.width, EPSILON)
            }
        }
        assertHitTesting(nodes, rtlMap, rtl)
    }

    @Test
    fun structuredAtomsKeepTheirNaturalSizeAndMayOccupyAnOverflowLine() {
        fun group(text: String) = LatexNode.Group(textNodes(text))
        val atoms = listOf(
            LatexNode.Fraction(group("a+b+c+d"), group("e+f+g+h")),
            LatexNode.Root(group("a+b+c+d")),
            LatexNode.Superscript(group("a+b+c"), group("d+e+f")),
            LatexNode.Subscript(group("a+b+c"), group("d+e+f")),
            LatexNode.Matrix(listOf(listOf(group("a+b+c"), group("d+e+f")))),
        )
        for (atom in atoms) {
            val natural = measureNode(atom, context, textMeasurer, density)
            val wrapping = wrappingContext(natural.width * 0.4f)

            // 宽度限制只作用于外层公式流，不能把分子、根号内容或矩阵单元格拆成多行。
            assertSameDimensions(natural, measureNode(atom, wrapping, textMeasurer, density))
            val nodes = listOf(atom, LatexNode.Text("+"), LatexNode.Text("z"))
            val map = LayoutMap()
            val result = LatexRenderer.measure(nodes, wrapping, textMeasurer, density, layoutMap = map)
            val atomEntry = assertNotNull(map.entries().find { it.node === atom })
            assertEquals(natural.width, atomEntry.width, EPSILON)
            assertEquals(natural.height, atomEntry.height, EPSILON)
            assertEquals(0f, atomEntry.relX, EPSILON)
            assertTrue(result.layout.width >= natural.width - EPSILON)
            val atomRow = rows(map).single { row -> row.any { it.node === atom } }
            assertEquals(1, atomRow.size, "An oversized atom must occupy its own line")
        }
    }

    @Test
    fun transparentGroupsStylesAndDisplayMathStillWrap() {
        val body = textNodes("a+b=c+d=e+f")
        val wrappers = listOf(
            LatexNode.Group(body),
            LatexNode.Style(body, LatexNode.Style.StyleType.ROMAN),
            LatexNode.DisplayMath(body),
            LatexNode.Group(listOf(LatexNode.DisplayMath(listOf(LatexNode.Style(body, LatexNode.Style.StyleType.BOLD))))),
        )
        for (wrapper in wrappers) {
            val natural = measureNode(wrapper, context, textMeasurer, density)
            val limit = natural.width * 0.4f

            val wrapped = measureNode(wrapper, wrappingContext(limit), textMeasurer, density)

            assertTrue(wrapped.height > natural.height, "Transparent wrappers must preserve formula flow")
            assertTrue(wrapped.width <= limit + EPSILON)
        }
    }

    @Test
    fun bitmapPixelsAgreeWithLayoutMapInLtrAndRtlRows() {
        val colors = listOf("red" to Color.Red, "blue" to Color.Blue, "green" to Color.Green)
        val rectangles = colors.mapIndexed { index, (name, _) ->
            LatexNode.Color(
                content = listOf(LatexNode.Layout(
                    layoutType = LatexNode.Layout.LayoutType.RULE,
                    width = listOf("1em", "1.4em", "0.8em")[index],
                    height = "0.6em",
                )),
                color = name,
                sourceRange = SourceRange(index, index + 1),
            )
        }
        for (explicit in listOf(false, true)) {
            val nodes = if (explicit) rectangles.take(2) + LatexNode.NewLine() + rectangles.drop(2) else rectangles
            for (direction in TextDirection.entries) {
                val renderContext = (if (explicit) context else wrappingContext(55f)).copy(textDirection = direction)
                val map = LayoutMap()
                val result = LatexRenderer.measure(nodes, renderContext, textMeasurer, density, layoutMap = map)
                val bitmap = drawBitmap(result)
                val pixels = bitmap.toPixelMap()
                assertEquals(2, rows(map).size)
                for ((index, rectangle) in rectangles.withIndex()) {
                    val entry = assertNotNull(map.entries().find { it.node === rectangle })
                    val x = (result.horizontalPadding + entry.relX + entry.width / 2f).toInt()
                    val y = (result.verticalPadding + entry.relY + entry.height / 2f).toInt()
                    // 采样实心矩形内部，避开抗锯齿边界，以实际 draw 验证映射坐标。
                    assertEquals(colors[index].second, pixels[x, y], "The bitmap and map must locate the same rectangle")
                }
                saveBitmap("rectangles-${if (explicit) "explicit" else "automatic"}-${direction.name.lowercase()}", bitmap)
            }
        }
    }

    @Test
    fun rendersRealFontSamplesForVisualInspection() {
        val criticalNodes = textNodes("a+b")
        val criticalWidth = (
            criticalNodes.sumOf { measureNode(it, context, textMeasurer, density).width.toDouble() }.toFloat() +
                measure(criticalNodes).width
            ) / 2f
        val parser = LatexParser()
        val integral = parser.parse("\\int_0^1 \\frac{1}{\\sqrt{1+x^2}} dx + \\int_a^b f(x) dx = c+d+e+f").children
        val explicit = textNodes("a+b=c") + LatexNode.NewLine() + textNodes("d+e=f", sourceStart = 6)
        val samples = listOf(
            Triple("spacing-boundary", criticalNodes, wrappingContext(criticalWidth)),
            Triple("integrals", integral, wrappingContext(150f)),
            Triple("explicit-lines", explicit, wrappingContext(90f)),
            Triple("rtl-lines", textNodes("a+b=c+d=e+f"), wrappingContext(90f).copy(textDirection = TextDirection.RTL)),
        )
        for ((name, nodes, renderContext) in samples) {
            val result = LatexRenderer.measure(nodes, renderContext, textMeasurer, density)
            val bitmap = drawBitmap(result)
            val pixels = bitmap.toPixelMap()
            assertTrue((0 until bitmap.height).any { y ->
                (0 until bitmap.width).any { x -> pixels[x, y] != Color.White }
            }, "A real-font sample must contain visible drawing")
            saveBitmap(name, bitmap)
        }
    }

    private fun measure(nodes: List<LatexNode>, renderContext: RenderContext = context): NodeLayout =
        LatexRenderer.measure(nodes, renderContext, textMeasurer, density).layout

    private fun wrappingContext(maxWidth: Float): RenderContext = context.copy(
        layoutHints = context.layoutHints.copy(maxLineWidth = maxWidth, lineBreakingEnabled = true),
    )

    private fun textNodes(text: String, sourceStart: Int = 0): List<LatexNode> =
        text.mapIndexed { index, character ->
            LatexNode.Text(character.toString(), sourceRange = SourceRange(sourceStart + index, sourceStart + index + 1))
        }

    private fun assertLineEntries(nodes: List<LatexNode>, map: LayoutMap, renderContext: RenderContext, top: Float) {
        val expectedMap = LayoutMap()
        val expected = LatexRenderer.measure(nodes, renderContext, textMeasurer, density, layoutMap = expectedMap)
        for (node in nodes) {
            val entry = assertNotNull(map.entries().find { it.node === node }, "Every visible node needs a final layout entry")
            val expectedEntry = assertNotNull(expectedMap.entries().find { it.node === node })
            assertEquals(expectedEntry.relX, entry.relX, EPSILON)
            assertEquals(top + expectedEntry.relY, entry.relY, EPSILON)
            assertEquals(expectedEntry.width, entry.width, EPSILON)
            assertEquals(expectedEntry.height, entry.height, EPSILON)
            assertTrue(entry.relX + entry.width <= expected.layout.width + EPSILON)
        }
    }

    private fun assertHitTesting(nodes: List<LatexNode>, map: LayoutMap, result: LatexRenderResult) {
        for (node in nodes) {
            val entry = assertNotNull(map.entries().find { it.node === node })
            assertTrue(entry.width > 0f && entry.height > 0f)
            val x = entry.relX + entry.width * 0.5f
            val y = entry.relY + entry.height * 0.5f
            assertSame(node, assertNotNull(map.hitTest(x, y)).node)
            val range = assertNotNull(node.sourceRange)
            assertSame(node, assertNotNull(map.entryAt(range.start)).node)
            assertEquals(
                range.start,
                CursorCalculator.hitTestToOffset(
                    x + result.horizontalPadding,
                    y + result.verticalPadding,
                    map,
                    result.horizontalPadding,
                    result.verticalPadding,
                    nodes.maxOf { assertNotNull(it.sourceRange).end },
                ),
            )
        }
    }

    private fun rows(map: LayoutMap): List<List<NodeLayoutEntry>> {
        val rows = mutableListOf<MutableList<NodeLayoutEntry>>()
        for (entry in map.entries().sortedBy { it.relY + it.baseline }) {
            val baseline = entry.relY + entry.baseline
            val row = rows.lastOrNull()?.takeIf { abs(it.first().relY + it.first().baseline - baseline) <= EPSILON }
                ?: mutableListOf<NodeLayoutEntry>().also { rows.add(it) }
            row.add(entry)
        }
        return rows
    }

    private fun assertSameDimensions(expected: NodeLayout, actual: NodeLayout) {
        assertEquals(expected.width, actual.width, EPSILON)
        assertEquals(expected.height, actual.height, EPSILON)
        assertEquals(expected.baseline, actual.baseline, EPSILON)
    }

    private fun drawBitmap(result: LatexRenderResult): ImageBitmap {
        val bitmap = ImageBitmap(ceil(result.canvasWidth).toInt().coerceAtLeast(1), ceil(result.canvasHeight).toInt().coerceAtLeast(1))
        CanvasDrawScope().draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
        ) {
            drawRect(Color.White)
            with(LatexRenderer) { draw(result) }
        }
        return bitmap
    }

    private fun saveBitmap(name: String, bitmap: ImageBitmap) {
        val directory = File("build/reports/auto-line-break")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").writeBytes(assertNotNull(bitmap.encodeToFormat(ImageFormat.PNG)))
    }

    private class CountingMathFontProvider(private val delegate: MathFontProvider) : MathFontProvider by delegate {
        val letterMeasurements = linkedMapOf<String, Int>()

        override fun italicCorrection(glyphChar: String, fontSizePx: Float): Float {
            letterMeasurements[glyphChar] = letterMeasurements.getOrDefault(glyphChar, 0) + 1
            return delegate.italicCorrection(glyphChar, fontSizePx)
        }
    }

    companion object {
        private const val EPSILON = 0.01f

        // 与产品加载路径相同；缺少真实字体时直接失败，不回退到系统字体。
        private val fonts by lazy { runBlocking { loadKatexFonts() } }
    }
}
