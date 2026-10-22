package com.hrm.latex.benchmark

import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.parser.util.LatexPrinter
import com.hrm.latex.parser.visitor.AccessibilityVisitor
import com.hrm.latex.parser.visitor.MathMLVisitor
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

/** AST 预先解析；计时只包含访问器创建、遍历和输出字符串构建。 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class VisitorBenchmark {
    @Param("complex", "matrix", "large", "text")
    lateinit var input: String

    private lateinit var document: LatexNode.Document

    @Setup(Level.Trial)
    fun setup() {
        document = BenchmarkInputs.validatedDocument(input)
        check(MathMLVisitor.convert(document).startsWith("<math"))
        check(AccessibilityVisitor.describe(document).isNotBlank())
        check(LatexPrinter().print(document).startsWith("Document"))
    }

    @Benchmark
    fun mathML(bh: Blackhole) {
        bh.consume(MathMLVisitor.convert(document))
    }

    @Benchmark
    fun accessibility(bh: Blackhole) {
        bh.consume(AccessibilityVisitor.describe(document))
    }

    @Benchmark
    fun printer(bh: Blackhole) {
        bh.consume(LatexPrinter().print(document))
    }
}
