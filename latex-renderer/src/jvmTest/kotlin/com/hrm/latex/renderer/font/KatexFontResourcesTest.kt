@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package com.hrm.latex.renderer.font

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.model.systemLatexFontFamilies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.MissingResourceException
import org.jetbrains.compose.resources.readResourceBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KatexFontResourcesTest {
    @Test
    fun should_read_each_resource_once_and_reuse_complete_fonts() = runBlocking<Unit> {
        val reads = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val loader = DefaultFontLoader(scope, load = {
                loadKatexFonts { path ->
                    reads += path
                    readResourceBytes(path)
                }
            })
            val fonts = loader.await()
            repeat(10) { assertSame(fonts, loader.await()) }
            assertEquals(20, reads.size)
            assertEquals(20, reads.toSet().size)
            assertTrue(assertNotNull(fonts.mainBytes).isNotEmpty())
            assertTrue(assertNotNull(fonts.mathBytes).isNotEmpty())
            assertTrue(assertNotNull(fonts.amsBytes).isNotEmpty())
            assertTrue(assertNotNull(fonts.size4Bytes).isNotEmpty())
            val density = Density(1f)
            val measurer = LatexMeasurerState(
                density,
                TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr),
                fonts,
            )
            assertNotNull(measurer.measure("\\mathbb{R} + \\mathcal{F} + \\mathfrak{g}"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun should_fall_back_after_first_missing_resource_without_retrying() = runBlocking<Unit> {
        var reads = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val loader = DefaultFontLoader(scope, load = {
                loadKatexFonts { path -> reads++; throw MissingResourceException(path) }
            })
            repeat(10) { assertEquals(systemLatexFontFamilies(), loader.await()) }
            assertEquals(1, reads)
        } finally {
            scope.cancel()
        }
    }

    /** 同一测试可在含字体及移除字体 JAR 的运行环境执行，覆盖真实平台资源读取与排版。 */
    @Test
    fun should_render_with_available_default_fonts() = runBlocking<Unit> {
        val hasResources = javaClass.classLoader.getResource("${KATEX_RESOURCE_ROOT}katex_main_regular.ttf") != null
        var failures = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val loader = DefaultFontLoader(scope, load = { loadKatexFonts() }, onFailure = { failures++ })
            val fonts = loader.await()
            repeat(10) { assertSame(fonts, loader.await()) }
            if (hasResources) {
                assertTrue(assertNotNull(fonts.mainBytes).isNotEmpty())
                assertEquals(0, failures)
            } else {
                assertEquals(systemLatexFontFamilies(), fonts)
                assertEquals(1, failures)
            }
            val density = Density(1f)
            val measurer = LatexMeasurerState(
                density,
                TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr),
                fonts,
            )
            for (formula in listOf("x^2 + y^2 = z^2", "\\frac{a}{b} + \\sqrt{x}", "\\mathbb{R} + \\mathcal{F}")) {
                assertNotNull(measurer.measure(formula))
            }
        } finally {
            scope.cancel()
        }
    }
}
