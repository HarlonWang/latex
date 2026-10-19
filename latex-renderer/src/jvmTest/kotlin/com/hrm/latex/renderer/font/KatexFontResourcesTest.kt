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
                    javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
                        ?: throw MissingResourceException(path)
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
}
