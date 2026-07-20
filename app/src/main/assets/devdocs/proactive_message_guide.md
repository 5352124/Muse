<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 主动消息 ProactiveMessageRunner 通知 实现

当用户问"主动消息怎么实现""通知怎么弹""为什么没收到主动消息""主动消息间隔"时参考本文档。本文档描述 ProactiveMessageRunner 的实现细节。

调度入口:
- MuseApp.onCreate() 中调用 proactiveMessageRunner.start()(fire-and-forget,失败不阻塞启动)。
- ProactiveMessageRunner.start() 在 appScope 协程中进入 while(isActive) 循环,每 POLL_INTERVAL_MS=60_000L(60 秒)检查一次 checkAndTrigger()。

触发条件(checkAndTrigger):
1. settings.proactiveMessageConfigFlow.first() 读 ProactiveMessageConfig。若 enabled=false 直接返回。
2. now = System.currentTimeMillis()。
3. intervalMs = config.intervalHours * 3600_000L。
4. 若 now - config.lastTriggeredAt < intervalMs,未到点,返回。
5. 取默认助手: assistantRepository.observeAll().first() 中 id="default" 的,否则第一个;都没有则返回。
6. 取第一个会话: sessionRepository.observeSessions().first().firstOrNull();为空则返回。
7. recentContext = targetSession.lastMessagePreview,若空则 sessionRepository.getLastMessage(sessionId)?.content。
8. buildProactivePrompt(assistant, recentContext) 构造 prompt。
9. chatService.completeText(messages, temperature=0.8f, maxTokens=150) 生成。失败则返回(不更新 lastTriggeredAt,下个 tick 重试)。
10. proactiveContent = completion.text.trim(),空则返回。
11. sessionRepository.appendMessage(sessionId, UIMessage(role=ASSISTANT, content, createdAt)) 插入会话。
12. settings.saveProactiveMessageConfig(config.copy(lastTriggeredAt=now)) 更新时间(先更新再通知)。
13. notificationManager.notifyProactiveMessage(assistantName, preview) 弹通知。

Prompt 构造(buildProactivePrompt):
- system 消息内容: "你是「{name}」,用户的朋友。" + 人设参考(取 systemPrompt 前 500 字)。
- 要求: 像真人发微信一样自然简短(20-80字),可问问题/分享/关心/回忆话题,纯文本不用 markdown,不重复用户说过的话,不带"回复:"等前缀。
- 若有 recentContext,追加"最近对话摘要: ..."。

通知(notifyProactiveMessage):
- 渠道: CHANNEL_PROACTIVE_MESSAGE = "proactive_message",IMPORTANCE_HIGH(有声音 + 横幅)。
- 标题: "{assistantName} 来消息了"。
- 正文: 消息预览(截断 100 字)。
- 点击跳转聊天页。

间隔选项(ChatSettingsPage.intervalOptions): 1/2/4/8/12/24 小时。

未收到主动消息的可能原因: enabled 未开 / 还没到间隔 / 默认助手或会话为空 / LLM 调用失败 / 通知权限未授。回答用户排查时应基于上述链路。
