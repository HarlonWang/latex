package com.hrm.latex.renderer.layout

import kotlin.js.jsTypeOf

private external interface LayoutIdentityWeakMap {
    fun get(key: Any): Int?
    fun set(key: Any, value: Int)
}

private val layoutIdentityHashes: LayoutIdentityWeakMap = js("new WeakMap()")
private var nextLayoutIdentityHash = 1

internal actual fun layoutIdentityHashCode(value: Any): Int {
    // JavaScript 原始值按值比较身份，不能用作 WeakMap 的键。
    val type = jsTypeOf(value)
    if (type != "object" && type != "function") return value.hashCode()

    layoutIdentityHashes.get(value)?.let { return it }
    val hash = nextLayoutIdentityHash++
    layoutIdentityHashes.set(value, hash)
    return hash
}
