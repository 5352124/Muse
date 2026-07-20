package io.zer0.muse.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * Phase 12: muse 间距令牌(MusePaddings)。
 *
 * 根治"padding 8/12/14/16/24 混用"的不一致问题。
 * 全项目统一引用 [MusePaddings] 令牌。
 *
 * 令牌层级(iOS 风格留白节奏):
 *  - [screen]:        16.dp — 屏幕水平边距(Scaffold padding)
 *  - [cardInner]:     horizontal=16, vertical=12 — 卡片内边距
 *  - [sectionGap]:    16.dp — section 之间间距
 *  - [itemGap]:       12.dp — 列表项之间间距(LazyColumn spacedBy)
 *  - [contentGap]:    8.dp — 卡片内组件之间间距
 *  - [tightGap]:      4.dp — 紧凑间距(图标 + 文字)
 */
object MusePaddings {
    /** 屏幕水平边距(Scaffold padding)。 */
    val screen = 16.dp
    /** 卡片内边距(统一所有 Card 的 padding)。 */
    val cardInner = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    /** section 之间间距。 */
    val sectionGap = 16.dp
    /** 列表项之间间距(LazyColumn spacedBy)。 */
    val itemGap = 12.dp
    /** 卡片内组件之间间距。 */
    val contentGap = 8.dp
    /** 紧凑间距(图标 + 文字)。 */
    val tightGap = 4.dp
    /**
     * 触摸目标尺寸(MD3 红线,IconButton / 行高最小值)。
     * L-PD1: 统一以 [MuseIconSizes.touchTarget] 为唯一数据源,此处委托引用,
     * 避免两处分别定义 48.dp 造成双数据源漂移。
     */
    val touchTarget: androidx.compose.ui.unit.Dp get() = MuseIconSizes.touchTarget
    /** 输入框内边距。 */
    val inputPadding = 12.dp
    /** 图标内边距(图标与相邻文字间距)。 */
    val iconPadding = 8.dp
    /** M-CS5: 消息间距(聊天列表 LazyColumn spacedBy,iOS 风格呼吸感)。 */
    val messageGap = 20.dp
}
