package com.hrm.latex.benchmark

import com.hrm.latex.parser.LatexParser
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** 两个公开解析入口均包含分词和 AST 构建，使用完全相同的输入。 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class ParserBenchmark {
    @Param("simple", "medium", "complex", "matrix", "large", "macro", "deep", "text", "multiline", "malformed")
    lateinit var input: String

    private lateinit var source: String
    private lateinit var parser: LatexParser

    @Setup(Level.Trial)
    fun setup() {
        source = BenchmarkInputs.formula(input)
        parser = LatexParser()
        BenchmarkInputs.validatedDocument(input, parser)
    }

    @Benchmark
    fun parse(bh: Blackhole) {
        bh.consume(parser.parse(source))
    }

    @Benchmark
    fun parseWithDiagnostics(bh: Blackhole) {
        bh.consume(parser.parseWithDiagnostics(source))
    }
}
