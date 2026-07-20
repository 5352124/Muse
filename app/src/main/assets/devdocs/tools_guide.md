<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# ToolRegistry 内置工具

ToolRegistry 是 muse 的本地工具注册表(简化版 MCP 框架),工具可被 LLM 通过 function calling / tool_call 触发,也可被定时任务、Skill、WebServer 复用。当前共 25 个内置工具(以源码 ToolRegistry.kt 的 registerBuiltIn 为准,本文档仅列常用部分)。

## 可用工具列表(常用工具,完整列表见源码)

### 通用工具(原 7 个)
- get_current_time: 获取当前时间(可选 timezone 参数,IANA 时区)
- calculator: 简易计算器,支持加减乘除和括号(参数 expression)
- echo: 回显输入(测试用,参数 text)
- clipboard_read: 读取系统剪贴板文本
- clipboard_write: 写入系统剪贴板(参数 text)
- screen_time: 今日各应用屏幕使用时间 Top 10(需 PACKAGE_USAGE_STATS 特殊权限)
- calendar_today: 今日日历事件列表(需 READ_CALENDAR 运行时权限)

### 手机端工具(v0.47 新增 7 个,Android 系统 API 实现)
- set_alarm: 设置系统闹钟(参数: hour 0-23 必填, minute 0-59 必填, label 可选)
  - 通过 AlarmClock.ACTION_SET_ALARM 拉起系统时钟应用,无需运行时权限
- set_timer: 设置系统倒计时(参数: seconds 必填, label 可选)
  - 通过 AlarmClock.ACTION_SET_TIMER 拉起系统时钟应用,无需运行时权限
- open_app: 打开应用(参数: packageName 应用包名,如 com.tencent.mm)
  - 通过 PackageManager.getLaunchIntentForPackage 启动应用主界面
- share_text: 分享文本(参数: text)
  - 通过 ACTION_SEND + createChooser 弹出系统分享选择器
- get_location: 获取当前粗略位置(返回 纬度/经度/精度)
  - 读取系统最后已知位置,需 ACCESS_COARSE_LOCATION 运行时权限
  - 不主动申请权限、不开启 GPS;真正的实时定位需 LocationCallback + Activity,本期不做
- get_device_info: 获取设备信息(品牌/型号/Android 版本/屏幕分辨率/电量)
  - 无需权限
- get_contacts_count: 获取通讯录联系人数量
  - 需 READ_CONTACTS 运行时权限,只读取数量不读取详情

## 调用方式
LLM 通过 tool_call 触发,arguments 为 JSON 对象:
```json
{
  "name": "set_alarm",
  "arguments": {"hour": 8, "minute": 30, "label": "起床"}
}
```

ChatService 会把 LLM 返回的 tool_call 转交给 `ToolRegistry.executeFromJson(name, argumentsJson)`,该方法解析 JSON 后调 `execute(name, args)`,返回字符串结果回灌给 LLM。

工具定义通过 `ToolRegistry.listToolsAsToolDefinitions()` 生成 OpenAI 兼容的 ToolDefinition 列表,注入对话请求的 tools 字段。

## 权限说明
- get_location 需要 ACCESS_COARSE_LOCATION(已在 AndroidManifest 声明,运行时需用户授权)
- get_contacts_count 需要 READ_CONTACTS(已在 AndroidManifest 声明,运行时需用户授权)
- screen_time 需要 PACKAGE_USAGE_STATS(特殊权限,需在设置中授予)
- calendar_today 需要 READ_CALENDAR
- set_alarm / set_timer / open_app / share_text / get_device_info 无需运行时权限
- 权限不足时工具返回提示字符串,不会崩溃,也不会强制弹权限框

## 调用建议(LLM 选工具的启发式规则)
- 用户说"提醒我 X 点做 Y" / "设个 X 点的闹钟" → set_alarm
- 用户说"X 分钟后提醒我" / "倒计时 X 秒" → set_timer
- 用户说"打开微信/QQ/应用名" → open_app(packageName=com.tencent.mm / com.tencent.mobileqq 等)
- 用户说"分享这段话" / "把这段发出去" → share_text
- 用户问"我在哪" / "我的位置" → get_location
- 用户问"我手机信息" / "设备信息" / "电量多少" → get_device_info
- 用户问"我有多少联系人" / "通讯录多少人" → get_contacts_count

## 常见应用包名参考
- 微信: com.tencent.mm
- QQ: com.tencent.mobileqq
- 支付宝: com.eg.android.AlipayGphone
- 抖音: com.ss.android.ugc.aweme
- 淘宝: com.taobao.taobao
- 设置: com.android.settings

## 不实现的工具及原因
- send_sms: 会主动发短信产生费用,风险高,本期跳过
- add_contact: 会修改用户通讯录数据,风险高,本期跳过
