# 图片生成子系统

### 8.1 核心组件

- `ai/image/ImageGenParams.kt`(内联于 ImageService.kt) — 绘图参数数据类
- `ai/image/ImageService.kt` — 图片生成服务
- `ai/image/ImageModelCatalog.kt` — 预设模型元数据
- `app/data/SettingsRepository.kt` — `ImageGenConfig` 持久化
- `app/ui/settings/ImageGenSection.kt` — 设置页模型/参数选择
- `app/ui/InputBar.kt` — 聊天页临时参数面板
- `app/ui/ChatViewModel.kt` — 参数状态同步与参考图清理

### 8.2 数据流

```
设置页 ImageGenSection
    ↓ 保存 ImageGenConfig
SettingsRepository.imageGenConfigFlow
    ↓ ChatViewModel init 订阅
ChatUiState.imageGenParams
    ↓ InputBar 显示/临时覆盖
imageService.generate(prompt, params)
    ↓ validateParams() 校验
OpenAI /images/generations 或 /images/edits
```

### 8.3 模型能力

| 模型 | 尺寸 | 质量 | 风格 | 参考图 | 最大 n |
|---|---|---|---|---|---|
| dall-e-2 | 256/512/1024 | - | - | 是 | 10 |
| dall-e-3 | 1024x1024 / 1792x1024 / 1024x1792 | standard/hd | vivid/natural | 否 | 1 |
| gpt-image-1 | 1024/1536 组合 | high/medium/low/auto | - | 否 | 1 |
| 通义万相 | 1024x1024 / 720x1280 / 1280x720 | standard/high | - | 否 | 1 |
| MiniMax 绘图 | 1024x1024 | - | - | 否 | 1 |

---