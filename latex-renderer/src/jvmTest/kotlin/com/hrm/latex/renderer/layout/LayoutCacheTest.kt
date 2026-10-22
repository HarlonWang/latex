package com.hrm.latex.renderer.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.renderer.font.MathFontProviderFactory
import com.hrm.latex.renderer.model.FontVariant
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.MathStyle
import com.hrm.latex.renderer.model.RenderContext
import com.hrm.latex.renderer.model.TextDirection
import com.hrm.latex.renderer.model.systemLatexFontFamilies
import com.hrm.latex.renderer.model.toContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LayoutCacheTest {
    private val density = Density(1f)
    private val textMeasurer = textMeasurer(density)
    private val context = LatexConfig().toContext(false, systemLatexFontFamilies())

    @Test
    fun coldAndWarmLayoutsMatchUncachedDimensionsAndBaseline() {
        for (formula in listOf(
            "x^2 + y_1 = \\frac{a+b}{c}",
            "\\sqrt{1+\\frac{x}{y}} + \\left(\\frac{a}{b}\\right)",
            "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
            "\\mathbf{x} + \\mathit{y} + \\text{hello}",
        )) {
            val nodes = LatexParser().parse(formula).children
            val cache = LayoutCache()
            val expected = measure(nodes)

            assertSameDimensions(expected, measure(nodes, cache = cache), formula)
            assertSameDimensions(expected, measure(nodes, cache = cache), formula)
            assertTrue(cache.hits > 0, "The unchanged AST should reuse cached nodes: $formula")
        }
    }

    @Test
    fun onlyTheSameNodeInstanceReusesACachedLayout() {
        val cache = LayoutCache()
        val node = LatexNode.Text("x")
        val equalButDistinctNode = node.copy()
        val layout = layout(12f)
        assertEquals(node, equalButDistinctNode)
        assertNotSame(node, equalButDistinctNode)

        cache.putNode(node, context, layout)

        assertSame(layout, cache.getNode(node, context))
        assertNull(cache.getNode(equalButDistinctNode, context))
        assertEquals(1, cache.hits)
        assertEquals(1, cache.misses)
    }

    @Test
    fun cacheKeysDoNotHashOrCompareAstChildLists() {
        val children = StructuralAccessCountingList(listOf(LatexNode.Text("x")))
        val equalChildren = StructuralAccessCountingList(listOf(LatexNode.Text("x")))
        val group = LatexNode.Group(children)
        val equalGroup = LatexNode.Group(equalChildren)
        val cache = LayoutCache()
        val layout = layout(12f)

        cache.putNode(group, context, layout)
        repeat(3) { assertSame(layout, cache.getNode(group, context)) }
        val distinctNodeLayout = cache.getNode(equalGroup, context)
        measure(listOf(group), cache = cache)
        measure(listOf(group), cache = cache)

        assertEquals(0, children.hashCalls + equalChildren.hashCalls)
        assertEquals(0, children.equalsCalls + equalChildren.equalsCalls)
        assertNull(distinctNodeLayout)
    }

    @Test
    fun warmHitsDoNotReclassifyAstChildrenInANewMeasureCycle() {
        val children = StructuralAccessCountingList(listOf(LatexNode.Text("x")))
        val group = LatexNode.Group(children)
        val cache = LayoutCache()
        val layout = layout(12f)
        cache.beginMeasure(textMeasurer, density)
        cache.putNode(group, context, layout)
        assertTrue(children.getCalls > 0, "The initial insertion should classify its descendants")

        // 新一轮测量会清空分类记录，已缓存的纯节点仍应直接命中。
        children.resetGetCalls()
        cache.beginMeasure(textMeasurer, density)

        assertSame(layout, cache.getNode(group, context))
        assertEquals(0, children.getCalls)
    }

    @Test
    fun everyRenderContextInputSeparatesCachedLayouts() {
        val fontFamilies = context.fontFamilies!!
        val variants = listOf(
            "fontSize" to context.copy(fontSize = context.fontSize * 1.5f),
            "baseFontSize" to context.copy(baseFontSize = context.baseFontSize * 1.5f),
            "color" to context.copy(color = Color.Red),
            "errorColor" to context.copy(errorColor = Color.Blue),
            "mathStyle" to context.copy(mathStyle = MathStyle.TEXT),
            "isCramped" to context.copy(isCramped = true),
            "fontWeight" to context.copy(fontWeight = FontWeight.Bold),
            "fontStyle" to context.copy(fontStyle = FontStyle.Italic),
            "fontFamily" to context.copy(fontFamily = FontFamily.Serif),
            "fontVariant" to context.copy(fontVariant = FontVariant.BLACKBOARD_BOLD),
            "fontFamilies" to context.copy(fontFamilies = fontFamilies.copy()),
            "isVariantFontFamily" to context.copy(isVariantFontFamily = true),
            "layoutHints.bigOpHeightHint" to context.copy(
                layoutHints = context.layoutHints.copy(bigOpHeightHint = 40f)
            ),
            "layoutHints.maxLineWidth" to context.copy(
                layoutHints = context.layoutHints.copy(maxLineWidth = 200f)
            ),
            "layoutHints.lineBreakingEnabled" to context.copy(
                layoutHints = context.layoutHints.copy(lineBreakingEnabled = true)
            ),
            "mathFontProvider" to context.copy(mathFontProvider = MathFontProviderFactory.create(fontFamilies)),
            "textDirection" to context.copy(textDirection = TextDirection.RTL),
        )
        val node = LatexNode.Text("x")
        val layout = layout(12f)
        val cache = LayoutCache()
        cache.putNode(node, context, layout)

        for ((field, changedContext) in variants) {
            assertNull(cache.getNode(node, changedContext), "A change to $field must invalidate its layout")
            assertSame(layout, cache.getNode(node, context.copy()), "The original $field layout must remain cached")
        }
    }

    @Test
    fun fontResourceIdentityIsIndependentOfItsPublicContentEquality() {
        val resources = systemLatexFontFamilies().copy(mainBytes = byteArrayOf(1, 2, 3))
        val equalResources = resources.copy(mainBytes = resources.mainBytes!!.copyOf())
        assertEquals(resources, equalResources)
        assertEquals(resources.hashCode(), equalResources.hashCode())
        assertNotSame(resources, equalResources)

        val node = LatexNode.Text("x")
        val originalContext = context.copy(fontFamilies = resources)
        val cache = LayoutCache()
        val layout = layout(12f)
        cache.putNode(node, originalContext, layout)

        assertSame(layout, cache.getNode(node, originalContext.copy()))
        assertNull(cache.getNode(node, originalContext.copy(fontFamilies = equalResources)))
    }

    @Test
    fun leastRecentlyUsedEntryIsEvictedAndClearResetsStatistics() {
        val cache = LayoutCache(maxSize = 2)
        val a = LatexNode.Text("a")
        val b = LatexNode.Text("b")
        val c = LatexNode.Text("c")
        val aLayout = layout(1f)
        val bLayout = layout(2f)
        val cLayout = layout(3f)

        cache.putNode(a, context, aLayout)
        cache.putNode(b, context, bLayout)
        assertSame(aLayout, cache.getNode(a, context))
        cache.putNode(c, context, cLayout)

        assertEquals(2, cache.size)
        assertNull(cache.getNode(b, context))
        assertSame(aLayout, cache.getNode(a, context))
        assertSame(cLayout, cache.getNode(c, context))
        assertEquals(3, cache.hits)
        assertEquals(1, cache.misses)

        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0, cache.hits)
        assertEquals(0, cache.misses)
        assertNull(cache.getNode(a, context))
        assertEquals(1, cache.misses)
    }

    @Test
    fun updatingAnExistingEntryReplacesItsValueAndRefreshesAccessOrder() {
        val cache = LayoutCache(maxSize = 2)
        val a = LatexNode.Text("a")
        val b = LatexNode.Text("b")
        val c = LatexNode.Text("c")
        val replacement = layout(4f)
        val cLayout = layout(3f)
        cache.putNode(a, context, layout(1f))
        cache.putNode(b, context, layout(2f))

        cache.putNode(a, context, replacement)
        assertEquals(2, cache.size)
        cache.putNode(c, context, cLayout)

        assertEquals(2, cache.size)
        assertNull(cache.getNode(b, context))
        assertSame(replacement, cache.getNode(a, context))
        assertSame(cLayout, cache.getNode(c, context))
    }

    @Test
    fun zeroCapacityStoresNoEntries() {
        val cache = LayoutCache(maxSize = 0)
        val node = LatexNode.Text("x")

        cache.putNode(node, context, layout(12f))

        assertEquals(0, cache.size)
        assertNull(cache.getNode(node, context))
        assertEquals(0, cache.hits)
        assertEquals(0, cache.misses)
        assertEquals(1, cache.bypasses)
    }

    @Test
    fun negativeCapacityIsRejected() {
        assertFailsWith<IllegalArgumentException> { LayoutCache(maxSize = -1) }
    }

    @Test
    fun appendingATenthEquationDoesNotSkipNumberingInsideCachedAncestors() {
        val prefix = LatexNode.Group((1..9).map(::equation))
        val cache = LayoutCache()
        measure(listOf(prefix), cache = cache)

        // 复用整个前缀子树，编号仍必须递增到 10。
        val appended = listOf(prefix, equation(10))
        assertSameDimensions(measure(appended), measure(appended, cache = cache))
        assertTrue(cache.hits > 0, "Pure descendants should remain reusable inside numbered environments")
    }

    @Test
    fun insertingEquationsBeforeALabelRefreshesRefAndEqRefInsideCachedAncestors() {
        for (reference in listOf(LatexNode.Ref("target"), LatexNode.EqRef("target"))) {
            val target = LatexNode.Environment(
                name = "equation",
                content = listOf(LatexNode.Text("x"), LatexNode.Label("target")),
            )
            val referenceGroup = LatexNode.Group(listOf(reference))
            val cache = LayoutCache()
            measure(listOf(target, referenceGroup), cache = cache)

            // 相同引用此时应解析为 10，其宽度与原编号 1 不同。
            val inserted = (1..9).map(::equation) + target + referenceGroup
            assertSameDimensions(measure(inserted), measure(inserted, cache = cache))
        }
    }

    @Test
    fun changingDensityInvalidatesPixelLayouts() {
        assertEnvironmentChange(Density(2f, 1f))
    }

    @Test
    fun changingFontScaleInvalidatesPixelLayouts() {
        assertEnvironmentChange(Density(1f, 1.5f))
    }

    @Test
    fun changingTextMeasurerDoesNotReuseLayoutsFromItsPreviousFontResolver() {
        val node = LatexNode.Text("WWWWiiii")
        val cache = LayoutCache()
        measure(listOf(node), cache = cache)
        val initialLayout = cache.getNode(node, context)
        assertTrue(initialLayout != null)

        val replacement = textMeasurer(density)
        assertNotSame(textMeasurer, replacement)
        val actual = measure(listOf(node), cache = cache, measurer = replacement)

        assertSameDimensions(measure(listOf(node), measurer = replacement), actual)
        assertNotSame(initialLayout, cache.getNode(node, context))
    }

    @Test
    fun changingFontFamiliesMatchesAnUncachedMeasurement() {
        val node = LatexNode.TextMode("WWWWiiii")
        val nodes = listOf(node)
        val cache = LayoutCache()
        val serif = context.copy(fontFamily = FontFamily.Serif)
        val monospace = context.copy(fontFamily = FontFamily.Monospace)
        val original = measure(nodes, cache = cache, renderContext = serif)
        val expected = measure(nodes, renderContext = monospace)

        assertNotEquals(original.layout.width, expected.layout.width)
        assertSameDimensions(expected, measure(nodes, cache = cache, renderContext = monospace))
    }

    private fun assertEnvironmentChange(changedDensity: Density) {
        // hspace 直接使用 Density 测量，不受 TextMeasurer 默认值影响。
        val nodes = listOf(LatexNode.HSpace("1em"))
        val cache = LayoutCache()
        val original = measure(nodes, cache = cache)
        val expected = measure(nodes, measurementDensity = changedDensity)

        assertTrue(expected.layout.width > original.layout.width)
        assertSameDimensions(expected, measure(nodes, cache = cache, measurementDensity = changedDensity))
    }

    private fun measure(
        nodes: List<LatexNode>,
        cache: LayoutCache? = null,
        measurementDensity: Density = density,
        measurer: TextMeasurer = textMeasurer,
        renderContext: RenderContext = context,
    ): LatexRenderResult = LatexRenderer.measure(
        children = nodes,
        context = renderContext,
        textMeasurer = measurer,
        density = measurementDensity,
        cache = cache,
    )

    private fun equation(index: Int): LatexNode.Environment = LatexNode.Environment(
        name = "equation",
        content = listOf(LatexNode.Text("x$index")),
    )

    private fun layout(width: Float) = NodeLayout(width, 10f, 7f) { _, _ -> }

    private fun textMeasurer(density: Density) = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultDensity = density,
        defaultLayoutDirection = LayoutDirection.Ltr,
    )

    private fun assertSameDimensions(
        expected: LatexRenderResult,
        actual: LatexRenderResult,
        message: String? = null,
    ) {
        assertEquals(expected.layout.width, actual.layout.width, 0.001f, message)
        assertEquals(expected.layout.height, actual.layout.height, 0.001f, message)
        assertEquals(expected.layout.baseline, actual.layout.baseline, 0.001f, message)
        assertEquals(expected.layout.italicCorrection, actual.layout.italicCorrection, 0.001f, message)
        assertEquals(expected.canvasWidth, actual.canvasWidth, 0.001f, message)
        assertEquals(expected.canvasHeight, actual.canvasHeight, 0.001f, message)
    }

    private class StructuralAccessCountingList(
        private val elements: List<LatexNode>,
    ) : AbstractList<LatexNode>() {
        var hashCalls: Int = 0
            private set
        var equalsCalls: Int = 0
            private set
        var getCalls: Int = 0
            private set

        override val size: Int get() = elements.size

        override fun get(index: Int): LatexNode {
            getCalls++
            return elements[index]
        }

        fun resetGetCalls() {
            getCalls = 0
        }

        override fun hashCode(): Int {
            hashCalls++
            return super.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            equalsCalls++
            return super.equals(other)
        }
    }
}
