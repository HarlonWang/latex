# KaTeX 数学字体架构

## 字体解析管线

`LatexConfig.fontFamilies` 显式配置优先；未指定时，renderer 首次使用默认字体会异步读取
可选 `latex-fonts-katex` 模块的资源。加载期间使用系统字体，完整加载成功后更新所有使用默认字体的入口。
无需调用安装或注册 API，字体模块本身不包含注册代码，也不依赖 renderer。

默认加载器使用进程级共享任务：每个资源只读取一次，成功和失败都缓存。资源缺失、读取失败或
字体构建失败时保留系统字体，不向渲染入口抛出异常，也不会在重组、切换页面或后续请求时重试。
共享任务不依附页面生命周期，取消某个等待者不会取消加载。

显式配置 `systemLatexFontFamilies()` 可以跳过默认资源加载。布局、绘制和导出使用同一份
解析后的字体配置；KaTeX 的字形度量适用于配套 TTF，系统字体可能存在排版差异。

## 字体角色

| 内容 | KaTeX 字体 |
|---|---|
| 正体文本、数字、运算符 | Main |
| 数学变量 | Math Italic |
| AMS 符号、黑板粗体 | AMS |
| 花体、哥特体、手写体 | Caligraphic / Fraktur / Script |
| 无衬线、等宽 | SansSerif / Typewriter |
| 大型运算符、定界符、根号 | Main / Size1 / Size2 / Size3 / Size4 |

## 测量规则

- 文本测量和实际绘制必须使用同一字体槽位。
- 墨迹边界从对应 KaTeX TTF 字节精确测量，不以文本行框代替字形边界。
- 定界符按 Main → Size1 → Size2 → Size3 → Size4 选择首个足够高的字形；
  Size4 仍不足时再等比放大字号。
- 大型运算符在行内使用 Size1，展示模式使用 Size2。
- 分数、上下标、根号间距与数学轴对齐遵循 KaTeX 度量和 TeX 规则。
- 重音水平锚点使用 `advance / 2 + skew`，垂直尺寸使用字形真实墨迹边界，
  不再使用按字号估算的下沉偏移。

## 资源要求

字体模块中的 `composeResources/font/katex_*.ttf` 打包在固定路径：
`composeResources/com.hrm.latex.fonts.katex.resources/font/`。
这个路径由字体模块的 `packageOfResClass` 和 renderer 的资源读取代码共同约定；renderer 不引用
可选模块的生成类。新增或替换字体时，必须同步更新字形度量、路由、可视化预览与 renderer 测试。
