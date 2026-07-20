# 工具体系总览

### 7.1 SkillExecutor(数据库驱动,20 个)

| 工具 | 参数 | 功能 |
|---|---|---|
| read_file | path, offset, length, encoding | 读文件(支持分段) |
| write_file | path, content, append, create_dirs | 写文件(支持创建目录) |
| list_dir | path | 列目录 |
| delete_file | path | 删除文件 |
| file_exists | path | 判断存在 |
| file_download | url, path, timeout | 从 URL 下载到沙盒 |
| read_public_file | uri, encoding | 读取公共目录 URI 文件 |
| save_to_downloads | content, filename, mime_type | 保存到 Download 目录 |
| list_public_files | directory, limit | 列出公共目录文件 |
| web_search | query, max_results, date_range, time_period | 联网搜索 |
| web_fetch | url, headers, max_length, truncate | 抓取网页 |
| arxiv_search | query, max_results, category, date_from, date_to | arXiv 论文 |
| knowledge_search | query, top_k, threshold | 知识库搜索 |
| http_get | url, headers, timeout, max_size | HTTP GET |
| http_post | url, body, content_type, headers, timeout | HTTP POST |
| delegate_agent | assistantId, task, context, timeout, response_format | 多 Agent 委托 |
| install_skill | skill_json | 安装 Skill |
| list_skills | category | 列出 Skill |
| uninstall_skill | id, name | 卸载 Skill |
| disable_skill | id | 禁用 Skill |

### 7.2 ToolRegistry(内存驱动,23 个)

| 工具 | 参数 | 功能 |
|---|---|---|
| get_current_time | timezone, format | 当前时间 |
| calculator | expression | 四则运算 |
| echo | text | 回显 |
| clipboard_read | - | 读剪贴板 |
| clipboard_write | text, label | 写剪贴板 |
| screen_time | - | 屏幕使用时间 |
| calendar_today | date, days | 日历事件 |
| set_alarm | hour, minute, label, days_of_week | 闹钟(支持重复) |
| set_timer | seconds, label | 倒计时 |
| open_app | packageName, action, data_uri | 打开应用(支持 Deep Link) |
| open_system_setting | category | 跳转系统设置页(10 个分类) |
| toggle_wifi | action | WiFi 状态查询/开关 |
| toggle_bluetooth | action | 蓝牙状态查询/开关 |
| send_email | to, subject, body | 发送邮件 |
| get_battery_info | - | 电池电量/充电状态 |
| get_recent_notifications | limit, package_name | 查询最近通知 |
| share_text | text, mime_type, title | 分享文本 |
| get_location | provider, timeout | 位置 |
| get_device_info | - | 设备信息 |
| get_contacts_count | filter | 联系人数量 |
| get_contacts_list | filter, limit | 联系人列表 |
| send_sms | phone, body, slot | 发短信 |
| add_contact | name, phone, email | 添加联系人 |

### 7.3 开发文档(LLM 可查询,用户不可见)

`app/src/main/assets/devdocs/` 下 9 份:

- app_features_overview.md
- skill_system_guide.md
- long_term_memory_guide.md
- assistant_config_guide.md
- chat_features_guide.md
- proactive_message_guide.md
- web_search_guide.md
- multi_agent_guide.md
- tools_guide.md

启动时 `MuseApp.seedDevDocs()` 幂等 upsert 进 `knowledge_docs` 表(fileType="devdoc"),KnowledgeScreen 过滤 devdoc 不显示给用户。

---