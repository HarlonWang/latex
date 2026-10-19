package com.hrm.latex.renderer.font

import androidx.compose.ui.text.font.FontFamily
import com.hrm.latex.renderer.model.systemLatexFontFamilies
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultFontLoaderTest {
    @Test
    fun should_load_once_when_many_callers_request_fonts() = runTest {
        var loads = 0
        val expected = systemLatexFontFamilies().copy(main = FontFamily.Monospace)
        val loader = DefaultFontLoader(backgroundScope, load = {
            loads++
            yield()
            expected
        })
        val results = List(32) { async { loader.await() } }.awaitAll()
        results.forEach { assertSame(expected, it) }
        repeat(10) { loader.start() }
        assertSame(expected, loader.await())
        assertSame(expected, loader.families)
        assertEquals(1, loads)
    }

    @Test
    fun should_cache_failure_and_never_retry() = runTest {
        var loads = 0
        var failures = 0
        val loader = DefaultFontLoader(
            backgroundScope,
            load = { loads++; error("missing font resource") },
            onFailure = { failures++ },
        )
        val fallback = loader.await()
        repeat(32) {
            loader.start()
            assertSame(fallback, loader.await())
        }
        assertEquals(systemLatexFontFamilies(), fallback)
        assertEquals(1, loads)
        assertEquals(1, failures)
    }

    @Test
    fun should_keep_loading_when_a_waiting_view_is_cancelled() = runTest {
        var loads = 0
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val expected = systemLatexFontFamilies().copy(main = FontFamily.Serif)
        val loader = DefaultFontLoader(backgroundScope, load = {
            loads++
            entered.complete(Unit)
            finish.await()
            expected
        })
        val view = launch { loader.await() }
        entered.await()
        view.cancelAndJoin()
        finish.complete(Unit)
        assertSame(expected, loader.await())
        assertEquals(1, loads)
    }

    @Test
    fun should_not_load_until_requested() = runTest {
        var loads = 0
        val loader = DefaultFontLoader(backgroundScope, load = {
            loads++
            systemLatexFontFamilies()
        })
        runCurrent()
        assertEquals(0, loads)
        assertEquals(systemLatexFontFamilies(), loader.families)
    }
}
