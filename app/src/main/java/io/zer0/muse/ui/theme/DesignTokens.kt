package io.zer0.muse.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp

/**
 * Muse 设计令牌 — 动画系统 (对齐 Kelivo iOS 触觉设计语言)。
 *
 * 提供全局统一的动画时长、缓动曲线、阴影规格与触觉反馈语义方法,
 * 与已有的 [MusePaddings] / [MuseCornerRadius] / [MuseElevation] / [MuseIconSizes] 互补,
 * 共同构成完整的设计令牌体系。
 *
 * 设计参考:
 *  - Kelivo (Flutter): iOS 触觉交互,200ms easeOutCubic 颜色渐变按压
 *  - Material 3 Expressive: 弹性动效 expressive motion scheme
 *  - Apple HIG: 触觉反馈分级 (light / medium / heavy)
 */
object MuseAnimation {

    // ── 时长令牌 ──────────────────────────────────────────────────────
    /** 极快过渡 (120ms): 微交互 — 图标切换 / 状态指示灯。 */
    const val FAST_MS = 120

    /** 快速过渡 (180ms): 简单状态切换 — 按钮颜色 / 开关。 */
    const val FAST_NORMAL_MS = 180

    /** 标准过渡 (240ms): 卡片展开 / 面板切换。 */
    const val NORMAL_MS = 240

    /** iOS 触觉标准 (200ms): 按压颜色渐变 (Kelivo 核心节奏)。 */
    const val TACTILE_MS = 200

    /** 慢速过渡 (320ms): 页面转场 / BottomSheet 弹出。 */
    const val SLOW_MS = 320

    /** 极慢过渡 (400ms): 大面积布局重组 / 主题切换渐变。 */
    const val EXTRA_SLOW_MS = 400

    // ── 缓动曲线令牌 ──────────────────────────────────────────────────
    /**
     * iOS 标准缓动 (easeOutCubic): Kelivo 主力曲线,
     * 快入慢出,模拟 iOS 触觉交互的自然减速感。
     */
    val EaseOutCubic: Easing = CubicBezierEasing(0.33f, 1.0f, 0.68f, 1.0f)

    /**
     * 弹性回弹 (easeOutBack): 略微超过终点后回弹,
     * 用于卡片入场 / 元素弹出等需要"活力感"的场景。
     */
    val EaseOutBack: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f)

    /**
     * Material 3 标准缓动: 通用过渡曲线,用于页面切换 / 元素移动。
     * 等同于 [FastOutSlowInEasing],此处命名统一令牌接口。
     */
    val Standard: Easing = FastOutSlowInEasing

    /**
     * 加速曲线 (easeInCubic): 慢入快出,用于元素离场 / 退出动画。
     */
    val EaseInCubic: Easing = CubicBezierEasing(0.32f, 0.0f, 0.67f, 0.0f)
}

/**
 * Muse 设计令牌 — 阴影系统 (iOS 风格柔和投影)。
 *
 * Kelivo 大量使用半透明柔和投影营造卡片"浮起"的层次感,
 * 与 Material 3 elevation 体系不同,iOS 风格投影更柔和、扩散更大、透明度更低。
 *
 * 使用方式:
 * ```
 * Modifier.shadow(
 *     elevation = MuseShadow.soft.elevation,
 *     shape = RoundedCornerShape(MuseCornerRadius.BUTTON.dp),
 * )
 * ```
 *
 * 注: Compose [Modifier.shadow] 不支持 offset / spread,此处仅提供 elevation + 语义规格。
 * 精确 offset 效果需用 [androidx.compose.ui.draw.drawBehind] 手动绘制。
 */
object MuseShadow {

    /** 阴影规格: elevation + 语义偏移量。 */
    data class ShadowSpec(
        val elevation: androidx.compose.ui.unit.Dp,
        val offsetY: androidx.compose.ui.unit.Dp = 0.dp,
    )

    /** 微投影: 紧贴表面,仅暗示层次 (按钮 / 标签)。 */
    val micro = ShadowSpec(elevation = 1.dp)

    /** 低投影: 列表项 / 输入框,轻微浮起感。 */
    val low = ShadowSpec(elevation = 2.dp, offsetY = 1.dp)

    /** 柔投影: 卡片主力阴影,Kelivo 风格 (black@5%, blur=18, offset=6dp)。 */
    val soft = ShadowSpec(elevation = 6.dp, offsetY = 6.dp)

    /** 高投影: 浮动按钮 / 弹出菜单。 */
    val high = ShadowSpec(elevation = 12.dp, offsetY = 8.dp)

    /** 模态投影: 对话框 / BottomSheet,最高层次。 */
    val modal = ShadowSpec(elevation = 24.dp, offsetY = 12.dp)
}

/**
 * Muse 设计令牌 — 触觉反馈语义封装。
 *
 * 将 Compose [HapticFeedback] 的原始 API 包装为语义化方法,
 * 调用方无需关心底层 [HapticFeedbackType] 选型。
 *
 * 用法:
 * ```
 * val haptic = LocalHapticFeedback.current
 * // 列表项点击
 * MuseHaptics.soft(haptic)
 * // 按钮 / 开关
 * MuseHaptics.light(haptic)
 * // 发送消息
 * MuseHaptics.medium(haptic)
 * ```
 *
 * 配合 [ChatPreferences.hapticFeedback] 开关:调用方在调用前检查开关,
 * 此对象本身不持有偏好状态,保持纯函数特性。
 */
object MuseHaptics {
    /** 轻触: 列表项点击 / 卡片展开等日常交互。 */
    fun soft(haptic: HapticFeedback) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** 轻击: 按钮点击 / 开关切换。 */
    fun light(haptic: HapticFeedback) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** 中等: 发送消息 / 确认操作。 */
    fun medium(haptic: HapticFeedback) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** 强击: 删除 / 危险操作确认。 */
    fun heavy(haptic: HapticFeedback) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}
