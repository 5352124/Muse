# 已知问题与 TODO

### 高优先级
1. **ChatViewModel 仍较大**:虽然已拆分 ChatOrchestrator,但 ChatViewModel 仍注入较多依赖,建议继续拆分为 ChatStreamVM / SessionVM / ToolVM
2. **国产/第三方绘图适配器**:DashScope、MiniMax、Volcengine 等未接入,当前仅 OpenAI 兼容接口
3. **独立绘图 Provider 选择**:当前复用聊天 Provider 的 API Key,未实现 openhanako 的 Media Provider 抽象

### 中优先级
4. **参考图 MIME 自适应**:当前固定输出 PNG,未根据原图类型自适应
5. **异步任务轮询/重试**:图片生成目前是一次性同步请求,部分厂商可能需要轮询
6. **本地画廊**:生成图片未保存到本地相册,仅显示 URL/base64
7. **values-en/strings.xml 部分键缺失英文翻译**:fallback 到中文,不崩溃
8. **硬编码提示词**:SystemPromptAssembler 的 DECISION_TREE_SECTION 等大段英文提示词已部分外部化到 PromptTemplates.kt,仍可继续清理

### 低优先级
9. **SmsManager.getDefault() 弃用**:API 31+ 推荐 getSystemService 方式
10. **Room 迁移测试**:当前使用 fallbackToDestructiveMigration 兜底,建议补充迁移测试

---