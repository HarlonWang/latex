package com.hrm.latex.renderer.font

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hrm.latex.base.log.HLog
import com.hrm.latex.renderer.model.LatexFontFamilies
import com.hrm.latex.renderer.model.systemLatexFontFamilies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** 进程级的一次性加载。成功、失败均缓存；取消某个等待者不会取消共享任务。 */
internal class DefaultFontLoader(
    private val scope: CoroutineScope,
    private val load: suspend () -> LatexFontFamilies,
    private val onFailure: (Exception) -> Unit = {},
) {
    private val fallback = systemLatexFontFamilies()
    var families: LatexFontFamilies by mutableStateOf(fallback)
        private set

    private val request by lazy {
        scope.async {
            val loaded = try {
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
                fallback
            }
            families = loaded
            loaded
        }
    }

    fun start() {
        request
    }

    suspend fun await(): LatexFontFamilies = request.await()
}

// 字体属于进程资源，不依附某个 Composable 的协程作用域。
private val defaultFonts = DefaultFontLoader(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    load = { loadKatexFonts() },
    onFailure = { HLog.i("LatexFonts", "KaTeX 字体不可用，后续使用系统字体：${it.message}") },
)

@Composable
internal fun rememberDefaultLatexFonts(): LatexFontFamilies {
    LaunchedEffect(Unit) { defaultFonts.start() }
    return defaultFonts.families
}
