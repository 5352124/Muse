package io.zer0.muse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Muse 配色系统(对齐前端设计完整方案 §2)。
 *
 * 设计哲学:90% 黑白灰 + <5% 品牌色。品牌绿是稀缺资源,只用在
 * 交互元素(发送按钮 / Tab 选中 / 开关激活 / 深色模式用户气泡),
 * 绝不大面积铺底。
 *
 * 浅色模式:暖白底(#FAFAF8,非纯白,长时间不刺眼)
 *           用户气泡由 colorScheme.primary 承载(浅色=品牌绿,见 PresetTheme)
 *           AI 气泡暖灰(#F0F0EC,比背景深一点,让气泡浮起来)
 * 深色模式:OLED 纯黑底(#000000,省电 + 对比度最高)
 *           用户气泡由 colorScheme.primary 承载(品牌绿,用颜色区分用户和 AI)
 *           AI 气泡深灰(#1C1C1E,比背景亮一点,刚好看出边界)
 *
 * 注(M-PT1):历史上此文件曾定义 LightUserBubble / DarkUserBubble /
 * LightOnUserBubble / DarkOnUserBubble 等气泡专用色,但全项目气泡实际通过
 * MaterialTheme.colorScheme.primary / onPrimary 渲染(见 PresetTheme),这些
 * 色值从未被引用,已删除以消除死代码。气泡配色统一在 PresetTheme 中维护。
 */

// ── 品牌色 ──────────────────────────────────────────────────────────────
/** 月桂绿:主品牌色。来源于缪斯的月桂叶象征,偏深偏沉的墨绿。 */
val LaurelGreen = Color(0xFF2D8C5F)
/** 品牌绿浅容器(浅色模式选中态背景)。 */
val LaurelGreenLightContainer = Color(0xFFD4EBDD)
/** 品牌绿深文字(浅色模式 onContainer)。 */
val LaurelGreenDark = Color(0xFF0F3D2A)
/** 品牌绿浅文字(深色模式 onContainer)。 */
val LaurelGreenLight = Color(0xFFA8DBC4)
/** 品牌绿深容器(深色模式选中态背景)。 */
val LaurelGreenDarkContainer = Color(0xFF1F4D38)

/** 星光金:辅助强调色。仅用于 Logo / 会员标识 / 里程碑徽章,日常界面不出现。 */
val StarGold = Color(0xFFF5C842)

// ── 浅色模式色板 ────────────────────────────────────────────────────────
/** 页面背景:暖白,非纯白,带极微暖黄调。 */
val LightBg = Color(0xFFFAFAF8)
/** AI 气泡:暖灰,比背景深一点让气泡浮起来。用户气泡改用 colorScheme.primary。 */
val LightAiBubble = Color(0xFFF0F0EC)
/** 正文文字:近黑。 */
val Ink = Color(0xFF1A1A1A)
/** 次要文字:时间戳 / 模型名 / 提示。 */
val Secondary = Color(0xFF8E8E93)
/** 分割线:极浅,存在但不抢眼。 */
val Divider = Color(0xFFE8E8E4)
/** 危险色:删除 / 停止生成。 */
val Danger = Color(0xFFFF3B30)
/** 危险色浅容器。 */
val DangerLightContainer = Color(0xFFFFE0DE)

// ── 深色模式色板 ────────────────────────────────────────────────────────
/** 深色页面背景:OLED 纯黑,省电 + 对比度最高。 */
val DarkBg = Color(0xFF000000)
/** 深色 AI 气泡:深灰,比背景亮一点。用户气泡改用 colorScheme.primary。 */
val DarkAiBubble = Color(0xFF1C1C1E)
/** 深色正文:略偏暖的白,不用纯白减少对比度疲劳。 */
val DarkInk = Color(0xFFE8E8E8)
/** 深色分割线:极暗。 */
val DarkDivider = Color(0xFF2C2C2E)
/** 深色危险色容器。 */
val DangerDarkContainer = Color(0xFF4A1F1F)

// ── 情感色板(关系可视化 / 记忆回顾 / 情绪轨迹)───────────────────────────
/** 珊瑚粉:关怀、心动。用于里程碑徽章、AI 主动关怀卡片。 */
val CoralWhisper = Color(0xFFFF6B6B)
/** 薰衣草:梦境、回忆。用于记忆回顾时间轴节点、回忆录封面。 */
val LavenderDream = Color(0xFF9B8EC4)
/** 琥珀橙:温暖、怀旧。用于关系时长进度条、年度回顾高亮。 */
val AmberWarmth = Color(0xFFE8A838)
/** 鼠尾草绿:平静、成长。用于情绪轨迹正面区域。 */
val SageCalm = Color(0xFF7DB88F)
/** 雾灰:中性、过渡。用于空状态插画、分割线、禁用态。 */
val MistGray = Color(0xFFC7C7CC)

// ── 会话列表首字母头像 8 色循环 ────────────────────────────────────────
/** 首字母头像预设色板,从 [MaterialTheme.colorScheme] 动态生成,不再用固定 8 色调色板。 */
@Composable
fun avatarColors(): List<Color> = buildList {
    val scheme = MaterialTheme.colorScheme
    add(scheme.primary.copy(alpha = 0.8f))
    add(scheme.secondary.copy(alpha = 0.8f))
    add(scheme.tertiary.copy(alpha = 0.8f))
    add(scheme.error.copy(alpha = 0.8f))
    add(scheme.primaryContainer)
    add(scheme.secondaryContainer)
    add(scheme.tertiaryContainer)
    add(scheme.inversePrimary.copy(alpha = 0.7f))
}
