package com.hrm.latex.renderer.utils

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class FontResourceCacheTest {
    @Test
    fun repeatedCallsWithTheSameByteArrayReuseOneResource() {
        val created = mutableListOf<FakeResource>()
        val cache = cache(created)
        val bytes = byteArrayOf(1, 2, 3)
        try {
            val first = withLiveResource(cache, bytes)
            repeat(5) {
                assertSame(first, withLiveResource(cache, bytes))
            }
            assertEquals(1, created.size)
            assertEquals(0, first.disposeCalls)
        } finally {
            cache.clear()
        }
        assertEquals(1, created.single().disposeCalls)
    }

    @Test
    fun equalContentsInDifferentByteArraysCreateIndependentResources() {
        val created = mutableListOf<FakeResource>()
        val cache = cache(created)
        val bytes = byteArrayOf(1, 2, 3)
        val copy = bytes.copyOf()
        assertContentEquals(bytes, copy)
        try {
            val first = withLiveResource(cache, bytes)
            val second = withLiveResource(cache, copy)

            assertNotSame(first, second)
            assertSame(first, withLiveResource(cache, bytes))
            assertSame(second, withLiveResource(cache, copy))
            assertEquals(2, created.size)
        } finally {
            cache.clear()
        }
        assertEquals(listOf(1, 1), created.map { it.disposeCalls })
    }

    @Test
    fun lruEvictionAndClearDisposeResourcesOnceAfterTheirCallbacks() {
        val created = mutableListOf<FakeResource>()
        val cache = cache(created)
        val a = byteArrayOf(1)
        val b = byteArrayOf(2)
        val c = byteArrayOf(3)
        try {
            val firstA = withLiveResource(cache, a)
            val firstB = withLiveResource(cache, b)
            assertSame(firstA, withLiveResource(cache, a))

            val firstC = withLiveResource(cache, c)
            assertEquals(1, firstB.disposeCalls)
            assertEquals(0, firstA.disposeCalls)
            assertEquals(0, firstC.disposeCalls)

            val secondB = withLiveResource(cache, b)
            assertNotSame(firstB, secondB)
            assertEquals(1, firstA.disposeCalls)
            assertEquals(4, created.size)

            cache.clear()
            cache.clear()
            assertEquals(listOf(1, 1, 1, 1), created.map { it.disposeCalls })
        } finally {
            cache.clear()
        }
    }

    private fun cache(created: MutableList<FakeResource>) = FontResourceCache(
        capacity = 2,
        create = { _: ByteArray -> FakeResource().also { created += it } },
        dispose = { it.dispose() },
    )

    private fun withLiveResource(cache: FontResourceCache<FakeResource>, bytes: ByteArray): FakeResource =
        assertNotNull(cache.withValue(bytes) { resource ->
            resource.inUse = true
            try {
                assertEquals(0, resource.disposeCalls)
                resource
            } finally {
                resource.inUse = false
            }
        })

    private class FakeResource {
        var inUse = false
        var disposeCalls = 0
            private set

        fun dispose() {
            assertFalse(inUse, "资源使用回调结束前不能释放")
            disposeCalls++
            assertEquals(1, disposeCalls, "每个资源只能释放一次")
        }
    }
}
