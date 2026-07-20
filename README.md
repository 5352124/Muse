<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="app/src/main/res/drawable/ic_muse_logo.png">
    <img src="app/src/main/res/drawable/ic_muse_logo.png" width="96" height="96" alt="Muse">
  </picture>
</p>

<h1 align="center">Muse</h1>

<p align="center">
  <b>你的 AI 灵感伙伴</b><br>
  <i>4 层记忆 · 多模型 · 离线优先 · 可扩展</i>
</p>

<p align="center">
  <a href="README_EN.md">English</a> · <b>中文</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/许可证-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen" alt="Min SDK">
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple" alt="Kotlin">
  <img src="https://img.shields.io/badge/Compose-Material%203-ff69b4" alt="Compose">
  <img src="https://img.shields.io/badge/构建-通过-success" alt="Build">
  <a href="https://github.com/5352124/Muse/releases"><img src="https://img.shields.io/github/v/release/5352124/Muse" alt="Latest release"></a>
</p>

<p align="center">
  <a href="#-为什么选-muse">为什么选 Muse</a> ·
  <a href="#-功能特性">功能特性</a> ·
  <a href="#-快速开始">快速开始</a> ·
  <a href="#-项目架构">项目架构</a> ·
  <a href="#-技术栈">技术栈</a> ·
  <a href="#-隐私">隐私</a> ·
  <a href="#-许可证">许可证</a>
</p>

---

## 💡 为什么选 Muse

大多数 AI 应用把每次对话当成初次见面——没有记忆、没有延续、没有个性。**Muse 不一样。**

它通过 **4 层渐进式记忆系统** 建立与你持续的关系——记住你的偏好、你的历史、你在意的事。它让你自由选择底层模型（OpenAI、Anthropic、Gemini、DeepSeek，或任意 OpenAI 兼容接口）。它能说话、能搜索、能执行工具，甚至会在久未联系时主动发起对话。

**一切默认离线可用。无需注册账号。数据默认留在本地，联网功能按需开启。**

---

## ✨ 功能特性

### 核心能力

<details open>
<summary><b>🧠 4 层记忆系统</b> — 记住该记住的，忘掉该忘的</summary>

```
对话 → 事实提取 → 滚动摘要
     → 编译聚合 → 深度处理
```

- **关键事实永不衰减**：医疗信息、财务数据、核心身份——重要性 ≥ 2 的事实受保护，不随时间淡出
- **日常信息自然过期**：普通偏好和闲聊信息随使用频率降低自动衰减
- **来源可追溯**：每条记忆标注来源会话和入库时间
- **你完全可控**：可在记忆面板手动调整重要程度、删除、筛选
</details>

<details>
<summary><b>🔌 多模型供应商</b> — 选模型，不选 App</summary>

预置 20+ 供应商，覆盖三大类：

| 类别 | 供应商 |
|------|--------|
| **海外官方** | OpenAI、Anthropic、Gemini、Groq、Together、Mistral、OpenRouter、DeepInfra、Fireworks |
| **国产官方** | DeepSeek、Qwen（千问）、GLM（智谱）、Moonshot（月之暗面）、Doubao（豆包）、Baichuan（百川）、Lingyi（零一）、StepFun（阶跃星辰） |
| **中转站** | OpenCode、API2D、AIHubMix、DeepBricks + 自建模板 |

模型 ID 从各供应商的 `/models` 接口**动态拉取**，本地不做硬编码列表，新模型上线无需更新 App。
</details>

<details>
<summary><b>🤖 多 Agent 协作</b> — 你的助手团队</summary>

创建多个不同性格和专业方向的助手，在对话中随时委派任务：

- 输入栏 `@助手名` 即可委派
- 任务卡片可视化显示每一步委派的执行状态
- 支持团队模式，多助手轮询协作
</details>

<details>
<summary><b>🔧 Skill 系统 + MCP 协议</b> — 无限扩展</summary>

- **20+ 内置工具**：文件读写、联网搜索、知识库、日历、剪贴板、计算器、短信、闹钟、表情包等
- **`.skill.json` 导入**：创建和分享自定义 Skill，支持参数 Schema
- **MCP 协议**：连接外部 MCP Server 动态扩展工具能力（OAuth 鉴权、SSE 传输、自动发现）
</details>

### 交互体验

<details>
<summary><b>🎤 流式语音识别</b> — 边说边转</summary>

- 支持 DashScope Paraformer / Step Whisper API
- 实时显示中间结果，说话的同时文字逐句出现
- 手势操作：长按录音 → 上滑取消
- 录音波形实时可视化
</details>

<details>
<summary><b>🖼️ 多模态输入</b> — 图片、PDF 都能聊</summary>

- **OCR 识别**：ML Kit 离线中文识别，自动提取图片文字送入对话
- **PDF 解析**：pdfbox-android 提取文本，可全文讨论
- **文档支持**：自动识别 TXT、DOCX、EPUB 等格式
- **图片生成**：内置 OpenAI DALL-E / Gemini 绘画能力
</details>

<details>
<summary><b>🌐 联网搜索</b> — 实时信息获取</summary>

- **Jina AI Reader**：免费层可用，直接返回 Markdown 格式摘要
- **Bing 网页搜索**：Jsoup 结构化提取结果，准确度高
- **自定义 API**：兼容 SearXNG、Tavily 或任意自定义端点
- **自动降级**：网页获取失败时自动回退搜索摘要，用户无感知
</details>

<details>
<summary><b>💬 主动消息</b> — Muse 会主动找你聊</summary>

像真正的伙伴一样，在久未联系时主动发消息：

- 发送间隔无极调节（连续滑块，无级档）
- 允许时段控制（默认 8:00-22:00），支持跨午夜
- 仅限 Agent 会话触发，不打扰任务流程
</details>

<details>
<summary><b>🗣️ 文字转语音</b> — 听回复</summary>

- 系统 TTS：零 APK 体积增加
- 云端 TTS（OpenAI / MiniMax / Edge）：更高音质
- 音频输出路由：扬声器 / 听筒 / 蓝牙
- 语速、音高、语言按助手独立配置
</details>

### 视觉与体验

<details>
<summary><b>🎨 6 套完整主题</b> — warm-paper 暖纸风格</summary>

| 主题 | 亮色 | 暗色 |
|:-----|:---:|:----:|
| 暖纸（默认） | ✅ | ✅ |
| 樱花 | ✅ | ✅ |
| 海洋 | ✅ | ✅ |
| 春 | ✅ | ✅ |
| 秋 | ✅ | ✅ |
| AMOLED | ✅ | ✅ |

另有 8 套色盲友好的精选配色用于自定义主题。每套主题完整定义所有 Material 3 颜色角色——没有默认紫色泄漏。
</details>

<details>
<summary><b>📱 Markdown 富文本渲染</b> — 丰富对话展示</summary>

- 代码高亮（20+ 语言语法着色 + 行号）
- KaTeX 数学公式
- Mermaid 流程图（`securityLevel: 'strict'`，防 XSS）
- 纯文本 URL 自动识别，单击二次确认 + 长按直接打开
- 链接防误触：弹出确认弹窗后再跳转
</details>

<details>
<summary><b>📦 表情包库</b> — 让回复更生动</summary>

- 导入 zip 压缩包，按文件夹结构自动分类
- 概率自动发送（0-100% 连续滑块）
- 分类胶囊筛选 + 网格预览
- 长按删除
</details>

<details>
<summary><b>🔒 安全设计</b> — 隐私优先</summary>

- 应用 PIN 锁（指数退避：5 次失败 → 锁 30 秒）
- 敏感配置走 Android Keystore 加密（AES-256-GCM）
- 云备份用户自定义密码加密（PBKDF2 + AES-256-GCM）
- `allowBackup=false`：禁止 ADB 提取应用数据
- WebView 净化 LLM 输出：移除 `<iframe>`、`<form>`、`javascript:` 伪协议
- PIN 锁定期间拦截 Deep Link，防越权操作
</details>

### 平台能力

- **版本更新检查**：通过 GitHub Releases API 自动检测
- **桌面小部件**：基于 Glance Compose，一键新建对话
- **嵌入式 Web 服务器**：Ktor + JWT + mDNS，局域网 API 访问
- **配置导入**：从 CherryStudio / Chatbox 一键迁移
- **备份与恢复**：本地文件 + S3 / WebDAV 云同步
- **全文搜索**：Room FTS5，对话历史即时检索
- **定时任务**：WorkManager 兜底，App 被杀也能由系统拉起
- **使用统计**：消息热力图，按日/周/月维度展示
- **安全模式**：崩溃自动捕获 → 跳过服务初始化 → 一键导出日志

---

## 🏗️ 项目架构

```
┌────────────────────────────────────────────────────────────┐
│                      Muse (Android App)                     │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌──────────────┐  │
│  │  UI     │  │ViewModel│  │ Service │  │    Data      │  │
│  │(Compose)│──│(ChatVM) │──│ (TTS /  │──│ (Room /      │  │
│  │         │  │         │  │  ASR /  │  │  DataStore)  │  │
│  │         │  │         │  │  Web )  │  │              │  │
│  └─────────┘  └─────────┘  └─────────┘  └──────────────┘  │
│      │             │              │             │          │
│  ┌───┴─────────────┴──────────────┴─────────────┴────┐    │
│  │           ai  ·  memory  ·  common                 │    │
│  │  (Provider SDK / 记忆引擎 / 公共工具)               │    │
│  └────────────────────────────────────────────────────┘    │
│              │                       │                      │
│  ┌───────────┴┐             ┌────────┴────────────┐        │
│  │  OkHttp /  │             │  Room / SQLite       │        │
│  │  Ktor /    │             │  FTS5 / DataStore   │        │
│  │  Coil      │             │  Keystore            │        │
│  └───────────┘             └─────────────────────┘        │
└────────────────────────────────────────────────────────────┘
```

### 模块说明

| 模块 | 职责 |
|------|------|
| `app/` | UI（Compose）、ViewModel、平台服务、数据层 |
| `ai/` | AI Provider 抽象（OpenAI / Anthropic / Gemini）、图片生成 |
| `memory/` | 4 层记忆系统（事实提取、摘要、编译） |
| `common/` | 公共工具（日志、JSON、协程辅助） |
| `material3/` | Material 3 颜色工具（`DynamicScheme.toColorScheme()` 扩展） |

### 关键设计约定

- **页面 > 弹窗**：`ModalBottomSheet` 在嵌套滚动 + 输入法场景下会卡死，长表单使用全屏页面
- **模型动态拉取**：模型 ID 不从本地维护，从供应商 `/models` 接口拉取
- **协程容错**：自定义 `resultOf{}` 工具正确重抛 `CancellationException`（stdlib 的 `runCatching` 吞掉它）
- **热配置即时生效**：高频读取的配置通过 `@Volatile` 缓存 + 协程订阅，修改设置立即生效，无需重启
- **纯 Kotlin**：全项目 100% Kotlin，零 Java 文件

---

## 🚀 快速开始

### 前置要求

- Android Studio Hedgehog (2024.1.1) 或更新版本
- JDK 17+
- Android SDK 35
- 一个 AI 供应商的 API Key（OpenAI / Gemini / DeepSeek 等均可）

### 构建安装

```bash
# 克隆
git clone https://github.com/5352124/Muse.git
cd Muse

# 调试构建
./gradlew :app:assembleDebug

# 安装到已连接设备
./gradlew :app:installDebug
```

APK 默认输出到项目根目录：`../Muse_v{version}_debug.apk`

### 首次使用

1. 打开 App → 引导页介绍核心功能
2. 设置你的称呼和助手的名字
3. 添加 AI 供应商 API Key（可使用预置模板快速配置）
4. 开始对话——从现在起 Muse 会记住一切

> **提示**：基本对话可完全离线使用。联网功能（AI 供应商、Web 搜索、云端备份）均为按需开启。

---

## 📸 截图

*准备好后替换为你自己的截图！*

| 对话 | 记忆面板 | 设置 |
|:---:|:--------:|:----:|
| [占位] | [占位] | [占位] |

---

## 📖 技术栈

| 类别 | 选型 |
|------|------|
| **语言** | Kotlin 2.0（100% Kotlin） |
| **UI** | Jetpack Compose + Material 3（1.4.0-alpha04） |
| **架构** | 单 Activity、MVVM、Koin DI |
| **数据库** | Room（SQLite + FTS5）+ DataStore Preferences |
| **DI** | Koin（无反射，模块化） |
| **网络** | OkHttp（SSE 流式）、Ktor（嵌入式服务器） |
| **图片** | Coil（SVG + GIF 解码） |
| **OCR** | ML Kit 中文文字识别 |
| **语音识别** | DashScope Paraformer、Step Whisper |
| **序列化** | kotlinx.serialization |
| **桌面小部件** | Glance Compose |
| **PDF** | pdfbox-android |
| **HTML 解析** | Jsoup |
| **TTS** | Android 系统 TTS + OpenAI / MiniMax / Edge 云端 TTS |

---

## 🔒 隐私

Muse 采用**本地优先**设计：

- ✅ 所有对话、记忆、知识库数据存储在本地 Room 数据库
- ✅ 无需注册账号，无遥测、无分析、无数据收集
- ✅ 联网功能（Web 搜索、云端 ASR、云备份）默认关闭，按需开启
- ✅ 应用 PIN 锁防止未授权访问
- ✅ 云备份用户密码加密（PBKDF2 + AES-256-GCM）
- ✅ 崩溃日志仅存储在本地，安全模式下可手动导出邮件发送

---

## 🤝 贡献

欢迎提交 Issue、功能建议和 Pull Request！

- **报告 Bug** → [创建 Issue](https://github.com/5352124/Muse/issues)
- **建议功能** → [发起讨论](https://github.com/5352124/Muse/discussions)
- **点个 Star** → 帮助更多人发现 Muse

---

## 📄 许可证

自 v1.119 起，Muse 采用 **GNU General Public License v3**（GPL v3）。

- 项目整体（含全部源代码、资源、派生作品）按 GPL v3 授权
- 第三方依赖库各自遵循其原始许可证，完整列表见 [NOTICE](NOTICE)

完整许可证文本：[LICENSE](LICENSE)
