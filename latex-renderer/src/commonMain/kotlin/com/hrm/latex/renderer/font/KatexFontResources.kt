@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package com.hrm.latex.renderer.font

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.hrm.latex.renderer.model.LatexFontFamilies
import org.jetbrains.compose.resources.readResourceBytes

// 与 latex-fonts-katex 的 packageOfResClass 一起构成资源契约，不引用可选模块的 Kotlin 类。
internal const val KATEX_RESOURCE_ROOT = "composeResources/com.hrm.latex.fonts.katex.resources/font/"

private data class FontSpec(
    val name: String,
    val weight: FontWeight = FontWeight.Normal,
    val style: FontStyle = FontStyle.Normal,
)

private val fontGroups = linkedMapOf(
    "main" to listOf(
        FontSpec("katex_main_regular"),
        FontSpec("katex_main_bold", FontWeight.Bold),
        FontSpec("katex_main_italic", style = FontStyle.Italic),
        FontSpec("katex_main_bolditalic", FontWeight.Bold, FontStyle.Italic),
    ),
    "math" to listOf(
        FontSpec("katex_math_italic", style = FontStyle.Italic),
        FontSpec("katex_math_bolditalic", FontWeight.Bold, FontStyle.Italic),
    ),
    "ams" to listOf(FontSpec("katex_ams_regular")),
    "sansSerif" to listOf(
        FontSpec("katex_sansserif_regular"),
        FontSpec("katex_sansserif_bold", FontWeight.Bold),
        FontSpec("katex_sansserif_italic", style = FontStyle.Italic),
    ),
    "monospace" to listOf(FontSpec("katex_typewriter_regular")),
    "caligraphic" to listOf(
        FontSpec("katex_caligraphic_regular"),
        FontSpec("katex_caligraphic_bold", FontWeight.Bold),
    ),
    "fraktur" to listOf(
        FontSpec("katex_fraktur_regular"),
        FontSpec("katex_fraktur_bold", FontWeight.Bold),
    ),
    "script" to listOf(FontSpec("katex_script_regular")),
    "size1" to listOf(FontSpec("katex_size1_regular")),
    "size2" to listOf(FontSpec("katex_size2_regular")),
    "size3" to listOf(FontSpec("katex_size3_regular")),
    "size4" to listOf(FontSpec("katex_size4_regular")),
)

/** 全部资源和字体对象准备成功后一次性发布，任何失败都交由一次性加载器回退。 */
internal suspend fun loadKatexFonts(
    readBytes: suspend (String) -> ByteArray = ::readResourceBytes,
): LatexFontFamilies {
    val bytes = mutableMapOf<String, ByteArray>()
    val families = fontGroups.mapValues { (_, specs) ->
        FontFamily(specs.map { spec ->
            val path = "$KATEX_RESOURCE_ROOT${spec.name}.ttf"
            val data = readBytes(path)
            bytes[spec.name] = data
            fontFromBytes(path, data, spec.weight, spec.style)
        })
    }
    return LatexFontFamilies(
        main = families.getValue("main"),
        math = families.getValue("math"),
        ams = families.getValue("ams"),
        sansSerif = families.getValue("sansSerif"),
        monospace = families.getValue("monospace"),
        caligraphic = families.getValue("caligraphic"),
        fraktur = families.getValue("fraktur"),
        script = families.getValue("script"),
        size1 = families.getValue("size1"),
        size2 = families.getValue("size2"),
        size3 = families.getValue("size3"),
        size4 = families.getValue("size4"),
        mainBytes = bytes.getValue("katex_main_regular"),
        mathBytes = bytes.getValue("katex_math_italic"),
        amsBytes = bytes.getValue("katex_ams_regular"),
        size1Bytes = bytes.getValue("katex_size1_regular"),
        size2Bytes = bytes.getValue("katex_size2_regular"),
        size3Bytes = bytes.getValue("katex_size3_regular"),
        size4Bytes = bytes.getValue("katex_size4_regular"),
    )
}

internal expect fun fontFromBytes(
    identity: String,
    bytes: ByteArray,
    weight: FontWeight,
    style: FontStyle,
): Font
