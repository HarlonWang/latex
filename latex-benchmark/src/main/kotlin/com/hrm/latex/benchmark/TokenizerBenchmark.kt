package com.hrm.latex.benchmark

import com.hrm.latex.parser.tokenizer.LatexTokenizer
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

/** 单次操作包含创建分词器和完整分词，语料验证不计入耗时。 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class TokenizerBenchmark {
    @Param("simple", "medium", "complex", "matrix", "large", "macro", "deep", "text", "multiline", "malformed")
    lateinit var input: String

    private lateinit var source: String

    @Setup(Level.Trial)
    fun setup() {
        source = BenchmarkInputs.formula(input)
        BenchmarkInputs.validatedDocument(input)
        check(LatexTokenizer(source).tokenize().isNotEmpty()) { "$input 语料未产生 token" }
    }

    @Benchmark
    fun tokenize(bh: Blackhole) {
        bh.consume(LatexTokenizer(source).tokenize())
    }
}
