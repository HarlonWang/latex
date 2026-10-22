package com.hrm.latex.renderer.layout

import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutIdentityTest {
    @Test
    fun sameObjectHasStableIdentityHash() {
        val value = Any()
        val alias = value
        val expected = layoutIdentityHashCode(value)

        repeat(100) {
            assertEquals(expected, layoutIdentityHashCode(alias))
        }
    }

    @Test
    fun identityHashDoesNotInvokeOverriddenHashCodeOrEquals() {
        val first = StructuralOperationsForbidden()
        val second = StructuralOperationsForbidden()
        val firstHash = layoutIdentityHashCode(first)
        val secondHash = layoutIdentityHashCode(second)

        // 交替访问两个对象，验证弱映射仍按各自引用返回稳定值。
        // 不要求不同对象的哈希不同，因为身份哈希允许碰撞。
        repeat(100) {
            assertEquals(firstHash, layoutIdentityHashCode(first))
            assertEquals(secondHash, layoutIdentityHashCode(second))
        }
    }

    private class StructuralOperationsForbidden {
        override fun hashCode(): Int = error("身份哈希不得调用对象覆写的 hashCode")

        override fun equals(other: Any?): Boolean = error("身份哈希不得调用对象覆写的 equals")
    }
}
