<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 多 Agent 协作 delegate_agent 工具 多助手

## 功能说明
muse 支持多 Agent 协作,主助手可以通过 delegate_agent 工具把任务委托给其他子助手执行。

## 使用场景
- 主助手是通用对话助手,遇到专业任务可以委托给专业子助手
- 例如:写作助手 + 审稿助手 + 翻译助手协作完成一篇文章
- 例如:研究员助手收集资料 + 写手助手整理成文

## 工具调用方式
LLM 在对话中遇到适合委托的任务时,通过 tool_call 调用 delegate_agent:
```json
{
  "name": "delegate_agent",
  "arguments": {
    "assistantId": "writer",
    "task": "把以下要点写成一段话: ...",
    "context": "可选的上下文"
  }
}
```

## 参数说明
- assistantId(必填):子助手 id,可在助手管理页查看。常见值:
  - "default":默认助手
  - 自定义 id:用户在助手管理页创建的助手 id
- task(必填):要委托的任务描述,自然语言
- context(可选):补充上下文

## 子助手执行流程
1. 根据 assistantId 取子助手配置(含 systemPrompt / modelId / temperature / maxTokens)
2. 用子助手的 systemPrompt + 任务描述构造消息
3. 调用 LLM 跑一轮(非流式,独立调用)
4. 返回子助手生成的文本给主助手

## 重要约束
- 子助手不会再调 delegate_agent(避免无限嵌套)
- 子助手独立调用 LLM,不走主对话的流式回环
- 失败时返回错误信息字符串,主助手能看到并决定是否重试

## 用户如何配置多 Agent
1. 在助手管理页创建多个助手(如"研究员"/"写手"/"审稿员"),各自配置 systemPrompt 和模型
2. 在设置→Skill 页确认 delegate_agent 已启用(默认启用)
3. 在对话中告诉主助手"把这个任务委托给研究员助手",LLM 会自动调 delegate_agent

## 触发建议
LLM 应该在以下情况主动调 delegate_agent:
- 用户明确说"委托给 X 助手"/"让 X 助手做"
- 任务超出主助手能力范围(如主助手是通用,任务需要专业知识)
- 用户提到多助手协作场景

不应该调的情况:
- 简单闲聊/问答
- 主助手自己能完成的任务
- 没有合适的子助手(此时应坦白告知用户)
