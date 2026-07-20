<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 助手配置 AssistantEntity 字段 系统提示词 temperature

当用户问"助手怎么配置""systemPrompt 怎么写""temperature 怎么设""skillIdsJson 是什么""助手有哪些字段"时参考本文档。

AssistantEntity 关键字段(对应 assistants 表):

基础(Basic 子页):
- id: 主键,唯一标识。默认助手 id="default"。
- name: 助手名称,显示在会话标题/通知。
- sortIndex: 排序权重。
- avatarEmoji: emoji 头像(如 "猫")。
- avatarImageUrl: 图片头像 URL(与 avatarEmoji 二选一,hasImageAvatar() 判断)。
- backgroundUrl / backgroundOpacity / useGradientBackground: 聊天背景图与透明度。

提示词(Prompt 子页):
- systemPrompt: 系统提示词(角色设定/规则),核心字段。
- messageTemplate: 消息模板(含 {{var}} 占位符,TemplateTransformer 后续替换)。
- presetMessagesJson: 预设消息 JSON 数组(开聊前注入的固定上下文)。

模型(Advanced 子页):
- modelId: 模型 ID(对应 ModelProfile)。
- temperature: 温度,null 用 Provider 默认。
- topP: top-p 采样。
- maxTokens: 单次生成最大 token。
- contextMessageSize: 上下文消息条数(默认 20)。
- reasoningLevel: 推理等级 "AUTO"/"LOW"/"MEDIUM"/"HIGH"/"XHIGH"(HIGH=8000 tokens)。
- streamOutput: 是否流式输出(默认 true)。

扩展(Extensions 子页):
- toolIdsJson: 启用的本地工具 ID 数组,默认 "[]"。
- mcpServerIdsJson: MCP 服务器 ID 数组。
- skillIdsJson: 启用的 skill ID 数组。默认 "[]" 表示启用所有 skill;指定如 ["knowledge_search","web_search"] 则只启用子集。
- lorebookIdsJson / quickMessageIdsJson / modeInjectionIdsJson: 关联的 Lorebook/快捷消息/Prompt 注入 ID。
- customHeadersJson / customBodiesJson: 自定义请求头/请求体(JSON)。

记忆(Memory 子页):
- memoryEnabled: 是否注入长期记忆摘要(默认 true)。
- useGlobalMemory: 是否用全局记忆(默认 true)。
- enableRecentChatsReference: 是否注入最近会话摘要(默认 true)。
- enableTimeReminder: 是否启用时间提醒(默认 true)。

助手详情聚合页有 5 个子页入口: Basic / Prompt / Extensions / Memory / Advanced,入口在 设置 → 助手 → 选择助手。用户可在这 5 个子页编辑对应字段。

回答用户配置类问题应基于上述真实字段,不要编造不存在的字段。
