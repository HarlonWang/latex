/*
 * Copyright (c) 2026 huarangmeng
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.hrm.latex.renderer.layout

import androidx.compose.ui.unit.Density
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.renderer.model.MathStyle
import com.hrm.latex.renderer.model.RenderContext
import com.hrm.latex.renderer.utils.AtomType
import com.hrm.latex.renderer.utils.MathSpacing

/**
 * 行宽与原子间距的共享度量。
 *
 * 完整行只计算间距与总宽度；首次查询子行时才创建前缀和与边界索引。
 * 子行查询为 O(1)，首尾二元符号重分类最多修正两个相邻间距。
 */
internal class InlineRowMetrics(
    private val nodes: List<LatexNode>, private val layouts: List<NodeLayout>,
    context: RenderContext, density: Density
) {
    private val isScript = context.mathStyle == MathStyle.SCRIPT || context.mathStyle == MathStyle.SCRIPT_SCRIPT
    private val fontSizePx = with(density) { context.fontSize.toPx() }
    private val spacings = FloatArray(nodes.size)
    private val fullWidth: Float
    private var rangeMetrics: RangeMetrics? = null

    init {
        var width = 0.0
        var spacing = 0.0
        var leftType = if (nodes.isNotEmpty()) MathSpacing.effectiveAtomType(nodes, 0) else AtomType.ORD
        for (i in nodes.indices) {
            width += layouts[i].width
            if (i + 1 < nodes.size) {
                val rightType = MathSpacing.effectiveAtomType(nodes, i + 1)
                spacings[i] = pairSpacing(i, leftType, rightType)
                spacing += spacings[i]
                leftType = rightType
            }
        }
        fullWidth = (width + spacing).toFloat()
    }

    fun width(start: Int, end: Int): Float {
        if (start >= end) return 0f
        if (start == 0 && end == nodes.size) return fullWidth
        val range = subrowMetrics()
        var width = range.widthPrefix[end] - range.widthPrefix[start] +
            range.spacingPrefix[end - 1] - range.spacingPrefix[start]
        val first = range.firstNonSpace[start]
        val last = range.lastNonSpace[end]
        // 首符号右侧、末符号左侧是仅有可能变化的两条边。带显式空白的边本来就是零。
        if (first < end - 1 && first >= start && range.types[first] == AtomType.BIN) {
            width += spacing(first, start, end) - spacings[first]
        }
        val lastEdge = last - 1
        if (lastEdge >= start && range.types[last] == AtomType.BIN &&
            !(lastEdge == first && range.types[first] == AtomType.BIN)
        ) {
            width += spacing(lastEdge, start, end) - spacings[lastEdge]
        }
        return width.toFloat()
    }

    fun spacing(index: Int, start: Int, end: Int): Float {
        if (start == 0 && end == nodes.size) return spacings[index]
        val range = subrowMetrics()
        val first = range.firstNonSpace[start]
        val last = range.lastNonSpace[end]
        fun type(i: Int): AtomType =
            if (range.types[i] == AtomType.BIN && (i == first || i == last)) AtomType.ORD else range.types[i]
        return pairSpacing(index, type(index), type(index + 1))
    }

    private fun subrowMetrics(): RangeMetrics {
        rangeMetrics?.let { return it }
        // Double 前缀和避免长公式相减时的 Float 消减误差改变临界断点。
        val widthPrefix = DoubleArray(nodes.size + 1)
        val spacingPrefix = DoubleArray(nodes.size + 1)
        val firstNonSpace = IntArray(nodes.size + 1) { nodes.size }
        val lastNonSpace = IntArray(nodes.size + 1) { -1 }
        for (i in nodes.indices) {
            widthPrefix[i + 1] = widthPrefix[i] + layouts[i].width
            spacingPrefix[i + 1] = spacingPrefix[i] + spacings[i]
            lastNonSpace[i + 1] = if (isSpace(nodes[i])) lastNonSpace[i] else i
        }
        for (i in nodes.indices.reversed()) {
            firstNonSpace[i] = if (isSpace(nodes[i])) firstNonSpace[i + 1] else i
        }
        return RangeMetrics(
            Array(nodes.size) { MathSpacing.effectiveAtomType(nodes, it) },
            widthPrefix, spacingPrefix, firstNonSpace, lastNonSpace
        ).also { rangeMetrics = it }
    }

    private fun pairSpacing(index: Int, left: AtomType, right: AtomType): Float =
        if (isSpace(nodes[index]) || isSpace(nodes[index + 1])) 0f
        else MathSpacing.spaceBetween(left, right, isScript) * fontSizePx

    private fun isSpace(node: LatexNode): Boolean = node is LatexNode.Space || node is LatexNode.HSpace

    private class RangeMetrics(
        val types: Array<AtomType>, val widthPrefix: DoubleArray, val spacingPrefix: DoubleArray,
        val firstNonSpace: IntArray, val lastNonSpace: IntArray
    )
}
