# =============================================================================
# latex-renderer 消费方保留规则（Consumer ProGuard Rules）
#
# 该文件随 AAR 一起发布，会在使用本库的 Android 应用进行 R8/ProGuard 优化时自动生效。
#
# 编写原则（参考 Android 官方《库作者优化指南》）：
#  - 仅保留运行时反射 / JNI / 序列化等无法被 R8 静态分析到的入口点。
#  - 严禁包级 -keep（如 -keep com.hrm.latex.renderer.** 会导致下游 App 体积膨胀）。
#  - 严禁全局选项（如 -dontobfuscate / -allowaccessmodification / -ignorewarnings）。
#  - Compose 运行时（compose.runtime / compose.foundation 等）已自带 consumer-rules，
#    本文件不再重复声明。
# =============================================================================

# latex-renderer 不直接使用反射 / JNI / ServiceLoader，
# Measurer 注册表通过 KClass 作为 Map 键，在 R8 优化下行为正确（不依赖类名）。
# Compose Resources（KaTeX 字体）通过固定资源路径读取，不反射可选模块的类，
# Compose Multiplatform 自带 consumer-rules 已覆盖资源加载链路。

# 公共 API 注解（@OptIn / @Stable / @Immutable 等运行时注解）
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# -----------------------------------------------------------------------------
# Compose Desktop / 普通 JVM ProGuard 兼容（仅压制 fatal warning，不阻止 shrink）
# -----------------------------------------------------------------------------
# 与 latex-parser / latex-base 同因：Kotlin 编译器在 `companion object` 中
# `private inline fun` + `private val` 的提升机制，会在某些 KMP/Kotlin 版本组合下
# 触发 `*$Companion.class` 与 outer 类静态字段的引用错位，被 ProGuard 全图静态校验
# 视为 unresolved 并升级为 fatal。运行期 JVM 惰性解析不触发，仅构建期失败。
# 仅压制对应 warning，不使用 `-keep`。
-dontwarn com.hrm.latex.**
