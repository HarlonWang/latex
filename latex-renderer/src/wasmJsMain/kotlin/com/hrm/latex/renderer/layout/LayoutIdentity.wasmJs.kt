@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.hrm.latex.renderer.layout

import kotlin.js.JsAny
import kotlin.js.toJsReference

private external interface LayoutIdentityWeakMap : JsAny {
    fun get(key: JsAny): Int?
    fun set(key: JsAny, value: Int)
}

private val layoutIdentityHashes: LayoutIdentityWeakMap = js("new WeakMap()")
private var nextLayoutIdentityHash = 1

internal actual fun layoutIdentityHashCode(value: Any): Int {
    // 同一 Kotlin 对象每次转换后保留相同的 JavaScript 引用身份。
    val reference = value.toJsReference()
    layoutIdentityHashes.get(reference)?.let { return it }
    val hash = nextLayoutIdentityHash++
    layoutIdentityHashes.set(reference, hash)
    return hash
}
