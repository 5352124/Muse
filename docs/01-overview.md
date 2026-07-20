# 项目概述

**Muse** 是一款 Android 端 AI 助手应用,定位为"个性化、可扩展的本地 AI 陪伴"。对标 iOS ChatGPT 与 MANUS 的视觉与交互风格,采用 warm-paper 暖纸色主题与现代圆角设计,全应用严禁 Android 原生 Material 默认样式。

核心理念:
- 本地优先:记忆、会话、知识库全离线存储,联网能力按需开启。
- 零依赖启动:不依赖任何第三方服务即可完整运行,默认使用系统 TTS 与本地工具。
- 可扩展:通过 Skill 系统(内置 + `.skill.json` 导入)与 MCP 协议动态扩展能力。
- 多 Provider:预置海外、国产、中转站三类供应商,模型 ID 动态从上游 `/models` 拉取,不在本地硬编码维护。
- 陪伴体验:主动消息机制让助手像真人一样定时主动发起对话。

当前版本:`versionName` 1.97 / `versionCode` 197。项目位于 `d:\1test\1muse\`,包名 `io.zer0.muse`,APK 输出目录 `D:\1test\`(文件名格式 `Muse_v{version}_debug.apk`)。