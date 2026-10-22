package com.hrm.latex.renderer.utils

/**
 * 按字体字节数组身份复用原生资源，最近使用的条目放在末尾。
 *
 * 字体数量很少，容量固定有界，直接比较引用即可避免扫描整份字体或维护身份哈希。
 * 缓存期间字体字节须保持不变，更新字体时应使用新的数组。
 * 创建成功的资源由缓存独占；调用方须保证串行访问，回调不重入缓存、不释放资源。
 */
internal class FontResourceCache<T : Any>(
    private val capacity: Int = 32,
    private val create: (ByteArray) -> T?,
    private val dispose: (T) -> Unit
) {
    private class Entry<T>(val bytes: ByteArray, val value: T)

    private val entries = ArrayList<Entry<T>>(capacity + 1)

    init {
        require(capacity > 0) { "字体缓存容量必须大于 0" }
    }

    fun <R> withValue(bytes: ByteArray, block: (T) -> R): R? {
        for (index in entries.lastIndex downTo 0) {
            val entry = entries[index]
            if (entry.bytes === bytes) {
                if (index != entries.lastIndex) {
                    entries.removeAt(index)
                    entries.add(entry)
                }
                return block(entry.value)
            }
        }

        val value = create(bytes) ?: return null
        entries.add(Entry(bytes, value))
        if (entries.size > capacity) {
            val oldest = entries.removeAt(0)
            dispose(oldest.value)
        }
        return block(value)
    }

    fun clear() {
        // 先移出缓存再释放，避免释放失败后重复关闭同一原生对象。
        var failure: Throwable? = null
        while (entries.isNotEmpty()) {
            val entry = entries.removeAt(entries.lastIndex)
            try {
                dispose(entry.value)
            } catch (error: Throwable) {
                if (failure == null) failure = error
            }
        }
        failure?.let { throw it }
    }
}
