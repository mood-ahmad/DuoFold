package dev.duofold.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.catalog.components.LiquidSlider
import com.kyant.backdrop.catalog.components.LiquidToggle
import com.kyant.shapes.Capsule
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * Kyant backdrop：只录制「背景艺术层」，所有液态玻璃组件从这里取景。
 * 玻璃元素绝不能位于被录制的节点内部，否则渲染树自引用导致 RenderThread 栈溢出闪退。
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop> {
    com.kyant.backdrop.backdrops.emptyBackdrop()
}

/** 液态控件拖动期间锁定页面滚动，避免拖动条/开关带着页面一起滑走 */
object PagerScrollGate {
    var enabled by androidx.compose.runtime.mutableStateOf(true)
}

/** 按下即锁、抬手即解锁（不消费事件，不影响控件自身手势） */
fun Modifier.pagerDragLock(): Modifier = pointerInput(Unit) {
    PointerEventPass.Initial.let { pass ->
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = pass)
            PagerScrollGate.enabled = false
            while (true) {
                val event = awaitPointerEvent(pass)
                if (event.changes.all { !it.pressed }) break
            }
            PagerScrollGate.enabled = true
        }
    }
}

/** 页面内液态玻璃控件的取景层：纯 surface 打底，玻璃控件采样这一静态层，无自引用。 */
@Composable
fun GlassBackdropHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val surfaceBackdrop = rememberLayerBackdrop()

    CompositionLocalProvider(
        LocalGlassBackdrop provides surfaceBackdrop,
    ) {
        Box(modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(surfaceBackdrop)
                    .background(surfaceColor)
            )
            content()
        }
    }
}

/** 卡片：MiuiX Card（与 BG Next / KSU 同款参数） */
@Composable
fun DuoCard(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    showIndication: Boolean = false,
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    onClick: (() -> Unit)? = null,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    content: @Composable ColumnScope.() -> Unit,
) {
    val defaultColor = CardDefaults.defaultColors().color
    Card(
        modifier = modifier,
        cornerRadius = cornerRadius,
        insideMargin = insideMargin,
        colors = CardDefaults.defaultColors(color = containerColor ?: defaultColor),
        showIndication = showIndication,
        pressFeedbackType = pressFeedbackType,
        onClick = onClick,
        content = content,
    )
}

/** 裁剪按钮按压位移溢出（染色层不再漏出胶囊），调用链需 clip 后不加 padding */
private fun Modifier.clipCapsule(): Modifier = this.clip(Capsule())

/** 液态玻璃滑条行：标题 + 当前值 + LiquidSlider（BG Next 同款：拖动中内部 state 实时刷新，260ms 防抖提交） */
@Composable
fun DuoSlider(
    label: String,
    initial: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    suffix: String = "",
) {
    val backdrop = LocalGlassBackdrop.current
    var dragging by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(initial) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackground)
            Text(
                text = "${(if (dragging) current else current).toInt()}$suffix",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        LiquidSlider(
            value = { current },
            onValueChange = {
                dragging = true
                current = it
            },
            valueRange = valueRange,
            visibilityThreshold = (valueRange.endInclusive - valueRange.start) / 200f,
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .pagerDragLock()
                .padding(top = 4.dp),
        )
    }
    // 防抖提交（拖动结束 260ms 后写回持久化）
    LaunchedEffect(current, dragging) {
        if (dragging) {
            delay(260)
            dragging = false
            if (current != initial) onValueChange(current)
        }
    }
}

/** 液态玻璃开关行 */
@Composable
fun DuoToggle(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val backdrop = LocalGlassBackdrop.current
    BasicComponent(
        title = title,
        summary = summary,
        startAction = null,
        endActions = {
            LiquidToggle(
                selected = { checked },
                onSelect = onChange,
                backdrop = backdrop,
                modifier = Modifier
                    .pagerDragLock()
                    .padding(start = 12.dp),
            )
        },
    )
}

/** 液态玻璃胶囊按钮（宽度 = 卡片 90%，居中） */
@Composable
fun DuoButton(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val backdrop = LocalGlassBackdrop.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        LiquidButton(
            onClick = onClick,
            backdrop = backdrop,
            tint = tint ?: MiuixTheme.colorScheme.primary,
            translationEnabled = false,
            modifier = modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth(0.9f)
                .height(54.dp)
                .pagerDragLock()
                .clipCapsule(),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = text,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** BG Next / KSU 原样：设备支持纹理模糊才创建 backdrop，否则返回 null（顶栏降级为纯色） */
@Composable
fun rememberBlurBackdrop(enableBlur: Boolean = true): LayerBackdrop? {
    if (!enableBlur || !isRenderEffectSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return top.yukonga.miuix.kmp.blur.rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/** BG Next / KSU 原样：顶栏毛玻璃（textureBlur 25f + 87% surface 混合） */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = if (blurActive && backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = androidx.compose.ui.graphics.RectangleShape,
                blurRadius = 25f,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha = 0.87f)),
                    ),
                ),
            )
        } else {
            Modifier
        },
    ) {
        content()
    }
}