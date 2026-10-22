package com.hrm.latex.benchmark

import com.hrm.latex.renderer.font.loadKatexFonts
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * 读取真实字体资源并构建字体集，成本计入每次操作。
 * 这是 JVM 资源读取与字体对象创建，受操作系统文件缓存影响；不等同于冷启动首帧或完整字体解码。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class FontLoadingBenchmark {
    @Benchmark
    fun loadFonts(bh: Blackhole) {
        bh.consume(runBlocking { loadKatexFonts() })
    }
}
