package com.hrm.latex.renderer.font

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.hrm.latex.base.log.HLog
import java.io.File
import java.nio.ByteBuffer

internal actual fun fontFromBytes(
    identity: String,
    bytes: ByteArray,
    weight: FontWeight,
    style: FontStyle,
): Font = MemoryFont(identity, bytes, weight, style)

private class MemoryFont(
    private val identity: String,
    private val bytes: ByteArray,
    override val weight: FontWeight,
    override val style: FontStyle,
) : AndroidFont(FontLoadingStrategy.Blocking, MemoryFontLoader, FontVariation.Settings()) {
    private var attempted = false
    private var typeface: Typeface? = null

    // Compose 可能从多个解析器请求同一字体，平台字体对象也只创建一次。
    @Synchronized
    fun load(context: Context): Typeface? {
        if (!attempted) {
            attempted = true
            typeface = try {
                if (Build.VERSION.SDK_INT >= 26) {
                    val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
                    Typeface.Builder(buffer)
                        .setWeight(weight.weight)
                        .setItalic(style == FontStyle.Italic)
                        .build()
                } else {
                    val file = File.createTempFile("latex-font-", ".ttf", context.cacheDir)
                    try {
                        file.writeBytes(bytes)
                        Typeface.createFromFile(file)
                    } finally {
                        file.delete()
                    }
                }
            } catch (failure: Exception) {
                HLog.e("LatexFonts", "无法创建字体 $identity", failure)
                null
            }
        }
        return typeface
    }
}

private object MemoryFontLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? =
        (font as MemoryFont).load(context)

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? =
        loadBlocking(context, font)
}
