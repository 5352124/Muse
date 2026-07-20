package io.zer0.muse.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import io.zer0.muse.ui.theme.MuseCornerRadius

/**
 * v0.34: 卡片分组组件设计。
 *
 * 一个 [CardGroup] 内的多个 [item] 共享同一个圆角卡片容器:
 *  - 第一项顶部圆角 20dp,最后一项底部圆角 20dp,中间项 4dp 内圆角
 *  - 按下时圆角动画过渡(iOS 风格分组卡片的按压反馈)
 *  - 可选的分组标题(primary 色 + titleSmallEmphasized 字体)
 *
 * 用法(DSL 风格):
 * ```
 * CardGroup(
 *     title = { Text("通用") },
 *     modifier = Modifier.padding(horizontal = 8.dp),
 * ) {
 *     item(
 *         onClick = { navController.navigate(...) },
 *         leadingContent = { Icon(Icons.Outlined.Sun, null) },
 *         headlineContent = { Text("深色模式") },
 *         supportingContent = { Text("跟随系统") },
 *         trailingContent = { Switch(checked=..., onCheckedChange=...) },
 *     )
 * }
 * ```
 *
 * 适配 muse 的 Material Icons(而非 HugeIcons)和 warm-paper 主题。
 */
private val CardGroupCorner = MuseCornerRadius.CARD.dp // 统一数据源:卡片大圆角(20dp,对应 MuseShapes.extraLarge)
private val CardGroupItemSpacing = 2.dp
private val CardGroupInnerCorner = MuseCornerRadius.TINY.dp // 统一数据源:卡片内圆角(4dp,对应 MuseShapes.tiny)

private data class CardGroupItem(
    val key: Any?,
    val onClick: (() -> Unit)?,
    val modifier: Modifier,
    val overlineContent: (@Composable () -> Unit)?,
    val headlineContent: @Composable () -> Unit,
    val supportingContent: (@Composable () -> Unit)?,
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
    val colors: ListItemColors?,
)

@DslMarker
private annotation class CardGroupDsl

@CardGroupDsl
interface CardGroupScope {
    /**
     * 卡片分组内的一项。
     *
     * @param onClick 点击回调(null 则不可点击)
     * @param headlineContent 主标题(必填)
     * @param supportingContent 副标题(灰色小字,可选)
     * @param leadingContent 左侧内容(通常是图标,可选)
     * @param trailingContent 右侧内容(Switch / 箭头 / Select / Slider 等,可选)
     * @param overlineContent 顶行小标题(可选,极少用)
     * @param colors 自定义 ListItem 颜色(可选,默认用主题)
     * @param modifier 该项的 Modifier(默认 Modifier)
     */
    fun item(
        key: Any? = null,
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        overlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        colors: ListItemColors? = null,
        headlineContent: @Composable () -> Unit,
    )
}

private class CardGroupScopeImpl : CardGroupScope {
    val items = mutableListOf<CardGroupItem>()

    override fun item(
        key: Any?,
        onClick: (() -> Unit)?,
        modifier: Modifier,
        overlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        colors: ListItemColors?,
        headlineContent: @Composable () -> Unit,
    ) {
        items.add(
            CardGroupItem(
                key = key,
                onClick = onClick,
                modifier = modifier,
                overlineContent = overlineContent,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors,
            )
        )
    }
}

@Composable
private fun CardGroupListItem(
    item: CardGroupItem,
    count: Int,
    index: Int,
) {
    val isFirst = index == 0
    val isLast = index == count - 1

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 按下时圆角动画过渡(animateDpAsState + motionScheme)
    val topCorner by animateDpAsState(
        targetValue = if (isPressed || count == 1 || isFirst) CardGroupCorner else CardGroupInnerCorner,
        animationSpec = tween(durationMillis = 120),
        label = "card_top_corner",
    )
    val bottomCorner by animateDpAsState(
        targetValue = if (isPressed || count == 1 || isLast) CardGroupCorner else CardGroupInnerCorner,
        animationSpec = tween(durationMillis = 120),
        label = "card_bottom_corner",
    )

    ListItem(
        headlineContent = item.headlineContent,
        modifier = item.modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = topCorner,
                    topEnd = topCorner,
                    bottomStart = bottomCorner,
                    bottomEnd = bottomCorner,
                )
            )
            .then(
                if (item.onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = item.onClick,
                    )
                } else Modifier
            ),
        overlineContent = item.overlineContent,
        supportingContent = item.supportingContent,
        leadingContent = item.leadingContent,
        trailingContent = item.trailingContent,
        // 默认用 surfaceVariant 作为卡片背景(warm-paper 主题下是暖灰色),
        // 卡片容器色
        colors = item.colors ?: ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    )
}

/**
 * v0.34: 卡片分组容器。
 *
 * 用法见文件顶部文档。所有 [content] 内的 [CardGroupScope.item] 调用会被收集,
 * 渲染为一个共享圆角背景的卡片组。
 *
 * @param modifier 整个卡片组的 Modifier
 * @param title 可选的分组标题(primary 色,显示在卡片上方)
 * @param content DSL 内容块,内部用 [CardGroupScope.item] 添加项
 */
@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    content: CardGroupScope.() -> Unit,
) {
    val scope = CardGroupScopeImpl()
    scope.content()

    Column(modifier = modifier) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                ProvideTextStyle(MaterialTheme.typography.titleSmall) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                    ) {
                        title()
                    }
                }
            }
        }
        val count = scope.items.size
        scope.items.fastForEachIndexed { index, item ->
            // 用 key 包裹,使带 key 的列表项在增删/重排时保留 remember 状态(如交互源、展开态)
            key(item.key ?: index) {
                CardGroupListItem(item = item, count = count, index = index)
            }
            if (index != count - 1) {
                Spacer(modifier = Modifier.height(CardGroupItemSpacing))
            }
        }
    }
}
