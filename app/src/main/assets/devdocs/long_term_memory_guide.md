<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 长期记忆系统 记忆 fact memory 如何使用

当用户问"你记得我吗""你有记忆吗""你能记住什么""长期记忆怎么生效"时,必须参考本文档坦诚回答,不要凭记忆编造。

记忆链路:
1. Fact 提炼 — 对话过程中,DeepMemoryProcessor 在 daily pipeline(由 MemoryTicker 每小时检查触发的 daily check)中从历史消息提炼 Fact,存入 facts.db。
2. 编译 markdown — MemoryCompiler 把 Fact 编译成 markdown 摘要文件。
3. 注入 system prompt — SystemPromptAssembler 的第 5 个 section "长期记忆摘要" 调用 memoryTicker.readCompiledMemoryMarkdown() 读取编译后的 markdown,注入到发给 LLM 的 system prompt。tokenBudget 默认 2500 token,超出会用 LlmBudget.truncateToTokenBudget 软裁剪。

关键事实(必须对用户坦诚):
- 长期记忆以"编译后的 markdown 摘要"形式注入 system prompt,不是逐条 Fact 注入。LLM 看到的是摘要文本,看不到原始 Fact 列表。
- Fact 提炼发生在 daily pipeline 时点,不是实时。用户刚才说的事实,如果还没到 daily pipeline 触发时点(通常需要等几小时),可能确实还没被记住。
- 记忆开关由 AssistantEntity.memoryEnabled 控制(默认 true),可在助手详情 → 记忆子页关闭。
- tokenBudget 可在 设置 → 记忆 调整(影响注入的摘要长度)。

Pinned Memories(固定记忆):
- 存储在 filesDir/pinned_memories.json,每次都注入 system prompt 的第 4 个 section。
- 来源: LLM 通过 pin_memory 工具写入,或用户在 设置 → 助手 → 记忆页手动添加。
- 用途: 把"必须记住"的关键信息固定下来,不依赖 daily pipeline。

当用户问"你记得我吗"且你不确定时,应坦诚回答:
"长期记忆是以编译后的 markdown 摘要注入的,不是逐条 fact。如果你刚说的话还没到 daily pipeline 时点(通常要等几小时),我可能确实还没记住。建议你用 pin_memory 工具固定关键信息,或者去 设置 → 助手 → 记忆页手动添加。"

不要假装记得用户没说过的或还没被编译的事。
