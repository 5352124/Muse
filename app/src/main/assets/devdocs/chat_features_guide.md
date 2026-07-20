<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 聊天页面 ChatScreen 特性 深度思考 主动消息 联网搜索 知识库

当用户问"聊天页有什么功能""深度思考怎么开""联网搜索怎么用""主动消息怎么设置""知识库在哪"时参考本文档。

深度思考:
- 开关位置: 聊天输入栏的 + 号菜单(Icons.Default.Psychology,标签"深度思考"或"深度思考(已开启)")。
- 行为: 开启后该次对话用 ReasoningLevel.HIGH(8000 tokens)推理,覆盖助手默认 reasoningLevel。
- 状态: 仅运行时内存状态,不持久化。切换会话或重启 app 后恢复助手默认 reasoningLevel。

联网搜索:
- 开关位置: 聊天输入栏 + 号菜单。
- 默认 provider: Bing,抓 cn.bing.com/search + regex 解析 <li class="b_algo">,不需要 API key。
- 其他 provider: SearXNG(自托管,无需 key)/ Tavily(需 API key)。在 设置 → 模型与服务 → Web 搜索 配置,或在 设置 → 聊天 → 默认搜索引擎 切换。
- 启用后每次对话先搜前 N 条结果作为上下文注入 system prompt。

主动消息:
- 入口: 设置 → 聊天 → 主动消息。
- 配置: enabled 开关 + 发送间隔(1/2/4/8/12/24 小时)。
- 运行: ProactiveMessageRunner 每 60 秒轮询,到点后用默认助手 + 第一个会话上下文生成消息,插入会话并弹通知(IMPORTANCE_HIGH,标题"X 来消息了")。
- 风格: system prompt 要求像真人发微信,短纯文本,不带前缀,temperature=0.8 maxTokens=150。

长消息折叠:
- 长回复有折叠 + 渐变遮罩,点击展开。

消息气泡操作菜单:
- MessageBubble 点击或长按弹出操作菜单(复制/重发/删除等)。

知识库:
- 入口: 设置 → Skill 页可启用/禁用 knowledge_search skill;设置 → 知识库 可导入文档。
- 注意: fileType="devdoc" 的条目是内部开发文档(本文件即其一),只供 LLM 通过 knowledge_search 查询,不在知识库 UI 列表显示。

回答用户时应基于上述真实特性,不要编造不存在的功能入口。
