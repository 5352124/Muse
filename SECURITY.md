# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| 1.124+  | :white_check_mark: |
| < 1.124 | :x:                |

## Reporting a Vulnerability

如果您发现了安全漏洞，请不要公开提交 Issue。

请发送邮件至项目维护者，或通过 GitHub 的 Security Advisory 功能私下报告。

我们会尽快确认并修复，修复后发布安全更新并致谢报告者。

## Security Features

Muse 内置以下安全机制：

- 数据本地存储：所有对话、记忆数据存储在本地 Room 数据库
- 应用 PIN 锁：防止未授权访问
- 云备份加密：PBKDF2 + AES-256-GCM 加密
- 离线优先：联网功能默认关闭，按需开启

## Dependency Security

- 所有依赖通过 Gradle Version Catalog 集中管理
- 定期检查已知 CVE 并更新
- 使用 Dependabot 自动检测依赖安全更新

## Responsible Disclosure

请给予合理的时间进行修复和发布。我们承诺在收到报告后 7 天内确认，并在 30 天内修复。
