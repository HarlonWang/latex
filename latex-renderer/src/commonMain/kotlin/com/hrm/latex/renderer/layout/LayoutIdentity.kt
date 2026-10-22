package com.hrm.latex.renderer.layout

/**
 * 根据对象身份计算哈希，不调用对象覆写的 [Any.hashCode]。
 * 哈希可能碰撞，调用方仍须使用 `===` 比较原始对象引用。
 */
internal expect fun layoutIdentityHashCode(value: Any): Int
