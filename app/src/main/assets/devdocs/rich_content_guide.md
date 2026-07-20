<!-- devdoc: 内部开发文档 -->
# 富媒体输出

## 概述

LLM 在回复中通过特殊代码块语言标识触发富媒体卡片渲染,支持 SVG 图形 / HTML 卡片 / 图表。
渲染入口在 `MarkdownText.kt` 的 CodeBlock 分支,由 `RichContentCard.kt` 接管 svg/html/chart 三类。

## 支持的卡片类型

### SVG 图形
用 ```svg 代码块,内容是标准 SVG XML。
LLM 可以生成简单的图标/示意图/流程图。

渲染方式:WebView(包进 HTML,JavaScript 禁用)。

示例:
```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
  <circle cx="50" cy="50" r="40" fill="steelblue"/>
</svg>
```

### HTML 卡片
用 ```html 代码块,内容是简单 HTML。
- 支持 CSS 样式
- JavaScript 已禁用(安全考虑,不要输出 script)
- 不要包含外部资源引用

渲染方式:WebView(JavaScript 禁用,透明背景,支持暗色模式 media query)。

### 图表
用 ```chart 代码块,内容是 JSON:
```json
{
  "type": "bar",
  "data": {
    "labels": ["A", "B", "C"],
    "values": [10, 20, 15]
  }
}
```

渲染方式:本期简化为 JSON 文本展示(等宽字体),后续可接入 Vico 图表库做真正的柱状图/折线图。
type 取值:bar / line / pie。

## 调用建议
- 用户说"画个图"/"可视化" → SVG 或图表
- 用户说"做个卡片" → HTML
- 用户说"柱状图/折线图" → chart(type=bar/line)
- 不要滥用,只在用户明确要求时输出富媒体
