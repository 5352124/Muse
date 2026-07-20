# 开发规范

### 5.1 硬约束(必须遵守)

1. **严禁 emoji**:所有代码、注释、UI 文案、资源文件不得包含 emoji,用 Material Icons 或纯文字替代
2. **中文注释**:所有代码注释使用中文
3. **warm-paper 主题**:全应用使用 MaterialTheme.colorScheme,不得硬编码颜色(如 `Color(0xFF...)`)
4. **iOS 风格圆角**:大卡片 20dp / 输入框 16dp / 胶囊按钮 24dp
5. **版本号规则**:versionCode = versionName 去小数点(1.12 → 112,1.5 → 105,1.0 → 100)
6. **APK 输出**:必须输出到 `D:\1test\Muse_v{version}_debug.apk`
7. **包名**: `applicationId = "io.zer0.muse"`, `namespace = "io.zer0.muse"`(v1.29 起从 io.mozi/io.muse 重构)
8. **Settings 二级菜单架构**:一级页显示账户卡片 + 4 分组入口卡片;二级页统一用 `SettingsSubPageScaffold`(左返回/中标题/右操作)
9. **按钮优先级**:全局主操作黑色实心胶囊,次操作浅色描边
10. **空状态统一**:灰色图标 + 描述文字 + 操作按钮
11. **过渡动画统一**:右滑入(push),左滑返回
12. **无 Android 原生风格**:禁止原生 AlertDialog/Toast/DropdownMenu/FAB,全部用 Muse 自实现组件
13. **a11y**:所有 IconButton 48dp 触摸目标,contentDescription 必须填写

### 5.2 构建命令

```powershell