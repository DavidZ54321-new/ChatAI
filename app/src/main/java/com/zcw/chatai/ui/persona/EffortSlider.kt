package com.zcw.chatai.ui.persona

import android.animation.ValueAnimator
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import com.zcw.chatai.data.prefs.ReasoningEffort
import com.zcw.chatai.ui.theme.ChatTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlinx.coroutines.isActive

/**
 * 思考强度滑条的纯几何。档位顺序就是 [ReasoningEffort.entries]：
 * 左端跟随服务端，右端最高。松手与拖动中都吸附到最近一档。
 */
internal object EffortSliderMath {
    val stops: List<ReasoningEffort> = ReasoningEffort.entries

    fun indexOf(effort: ReasoningEffort): Int =
        stops.indexOf(effort).let { if (it < 0) 0 else it }

    fun effortAt(index: Int): ReasoningEffort = stops[index.coerceIn(stops.indices)]

    fun fractionOf(index: Int): Float {
        val last = (stops.size - 1).coerceAtLeast(1)
        return index.coerceIn(0, last).toFloat() / last
    }

    fun indexForFraction(fraction: Float): Int {
        val last = stops.size - 1
        return round(fraction.coerceIn(0f, 1f) * last).toInt().coerceIn(0, last)
    }

    /** 拇指中心始终落在轨道内，避免圆头被裁掉。 */
    fun thumbCenterX(fraction: Float, width: Float, thumbRadius: Float, inset: Float): Float {
        val left = inset + thumbRadius
        val right = (width - inset - thumbRadius).coerceAtLeast(left)
        return left + fraction.coerceIn(0f, 1f) * (right - left)
    }

    fun fractionAtX(x: Float, width: Float, thumbRadius: Float, inset: Float): Float {
        val left = inset + thumbRadius
        val right = (width - inset - thumbRadius).coerceAtLeast(left + 1f)
        return ((x - left) / (right - left)).coerceIn(0f, 1f)
    }
}

internal data class SparkSeed(
    val along: Float,
    val across: Float,
    val radiusScale: Float,
    val phase: Float,
    val twinkle: Float,
    val star: Boolean,
    /** 相对基准速度的倍率，粒子不会排成一排同速。 */
    val speedScale: Float,
)

/**
 * 向右流动的速度。位置用 [distance] 对时间积分，避免速度一变粒子就瞬移。
 * 拖尾长度是「当前速度能在 [TAIL_SECONDS] 里跑过的轨道比例」。
 */
internal object EffortFlow {
    const val TAIL_SECONDS = 0.08f
    const val TAIL_MIN = 0.01f
    const val TAIL_MAX = 0.55f
    const val STAR_SPEED = 1.35f
    const val STAR_TAIL = 1.4f

    fun lengthsPerSecond(fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f).toDouble()
        return 0.18f + t.pow(2.4).toFloat() * 5.4f
    }

    fun headAlong(origin: Float, distance: Float): Float {
        val wrapped = (origin + distance) % 1f
        return if (wrapped < 0f) wrapped + 1f else wrapped
    }

    fun seedLengthsPerSecond(fraction: Float, speedScale: Float, star: Boolean): Float {
        val scale = speedScale.coerceAtLeast(0f) * if (star) STAR_SPEED else 1f
        return lengthsPerSecond(fraction) * scale
    }

    fun tailFraction(lengthsPerSecond: Float): Float =
        (lengthsPerSecond * TAIL_SECONDS).coerceIn(TAIL_MIN, TAIL_MAX)

    fun seedTailFraction(fraction: Float, speedScale: Float, star: Boolean): Float {
        val speed = seedLengthsPerSecond(fraction, speedScale, star)
        val stretch = if (star) STAR_TAIL else 1f
        return (speed * TAIL_SECONDS * stretch).coerceIn(TAIL_MIN, TAIL_MAX)
    }
}

/** 固定种子，同一帧序列可复现；粒子在填充段里向右循环。 */
internal fun sparkSeeds(count: Int = 26): List<SparkSeed> {
    var state = 0xC0DE
    fun next(): Float {
        state = state * 1664525 + 1013904223
        return ((state ushr 8) and 0xFFFFFF) / 0xFFFFFF.toFloat()
    }
    return List(count) {
        SparkSeed(
            along = next(),
            across = 0.18f + next() * 0.64f,
            radiusScale = 0.35f + next() * 1.05f,
            phase = next(),
            twinkle = 0.7f + next() * 1.6f,
            star = next() > 0.74f,
            speedScale = 0.65f + next() * 0.75f,
        )
    }
}

internal fun ReasoningEffort.sliderTick(): String = when (this) {
    ReasoningEffort.FOLLOW_DEFAULT -> "跟随"
    ReasoningEffort.OFF -> "关"
    ReasoningEffort.LOW -> "低"
    ReasoningEffort.HIGH -> "高"
    ReasoningEffort.MAX -> "最高"
}

internal fun ReasoningEffort.sliderLabel(): String = when (this) {
    ReasoningEffort.FOLLOW_DEFAULT -> "跟随服务端"
    ReasoningEffort.OFF -> "关"
    ReasoningEffort.LOW -> "低"
    ReasoningEffort.HIGH -> "高"
    ReasoningEffort.MAX -> "最高"
}

private val TrackDeep = Color(0xFF2A1FD0)
private val TrackMid = Color(0xFF5B35E8)
private val TrackBright = Color(0xFF8B4DFF)
private val TrackTip = Color(0xFFC9B6FF)
private val TickActive = Color(0xFF6D4AFF)

/**
 * Codex 风格的思考强度滑条：胶囊渐变轨道、白拇指。
 * 粒子向右流动，越靠右越快，拉满时长出流星尾。拖动连续，数值吸附到五档。
 */
@Composable
internal fun EffortSparkSlider(
    value: ReasoningEffort,
    onValueChange: (ReasoningEffort) -> Unit,
    valueText: String,
    modifier: Modifier = Modifier,
    /** 未填充轨道。默认跟主题芯片色；深色弹层传自己的底色。字色跟 [LocalContentColor]。 */
    trackColor: Color = Color.Unspecified,
) {
    val index = EffortSliderMath.indexOf(value)
    val snapped = EffortSliderMath.fractionOf(index)
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(snapped) }
    val shown by animateFloatAsState(
        targetValue = if (dragging) dragFraction else snapped,
        animationSpec = if (dragging) {
            tween(durationMillis = 0)
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        },
        label = "effortFraction",
    )
    val flow = rememberFlowClock(shown)
    val seeds = remember { sparkSeeds() }
    val contentColor = LocalContentColor.current
    val labelColor = contentColor.copy(alpha = 0.72f)
    val trackRest = if (trackColor == Color.Unspecified) {
        ChatTheme.colors.chipBackground
    } else {
        trackColor
    }
    val onChange by rememberUpdatedState(onValueChange)
    val valueState by rememberUpdatedState(value)
    val haptic = LocalHapticFeedback.current
    val hapticState by rememberUpdatedState(haptic)
    val thumbRadius = 17.dp
    val inset = 2.dp

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "思考强度",
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "思考强度"
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = index.toFloat(),
                        range = 0f..(EffortSliderMath.stops.size - 1).toFloat(),
                        steps = (EffortSliderMath.stops.size - 2).coerceAtLeast(0),
                    )
                    setProgress { target ->
                        onChange(EffortSliderMath.effortAt(round(target).toInt()))
                        true
                    }
                }
                .pointerInput(thumbRadius, inset) {
                    val radiusPx = thumbRadius.toPx()
                    val insetPx = inset.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        val slop = viewConfiguration.touchSlop
                        var pastSlop = false
                        var totalX = 0f
                        var totalY = 0f
                        var announced = valueState
                        fun apply(x: Float) {
                            val fraction = EffortSliderMath.fractionAtX(
                                x = x,
                                width = size.width.toFloat(),
                                thumbRadius = radiusPx,
                                inset = insetPx,
                            )
                            dragFraction = fraction
                            val next = EffortSliderMath.effortAt(
                                EffortSliderMath.indexForFraction(fraction),
                            )
                            if (next != announced) {
                                announced = next
                                hapticState.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onChange(next)
                            }
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (change.changedToUp()) {
                                if (!pastSlop) apply(change.position.x)
                                break
                            }
                            if (change.isConsumed && !pastSlop) break
                            val delta = change.positionChange()
                            totalX += delta.x
                            totalY += delta.y
                            if (!pastSlop) {
                                if (abs(totalY) > slop && abs(totalY) > abs(totalX)) break
                                if (abs(totalX) > slop) {
                                    pastSlop = true
                                    dragging = true
                                }
                            }
                            if (pastSlop) {
                                change.consume()
                                apply(change.position.x)
                            }
                        }
                        dragging = false
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawEffortTrack(
                    fraction = shown,
                    elapsed = flow.elapsed,
                    distance = flow.distance,
                    moving = flow.moving,
                    seeds = seeds,
                    trackRest = trackRest,
                    thumbRadius = thumbRadius.toPx(),
                    inset = inset.toPx(),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            EffortSliderMath.stops.forEach { stop ->
                val selected = stop == value
                Text(
                    text = stop.sliderTick(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) TickActive else labelColor,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private data class FlowClock(val elapsed: Float, val distance: Float, val moving: Boolean)

/** 速度对时间积分。关动画时停住，粒子不再每帧重画成长尾。 */
@Composable
private fun rememberFlowClock(fraction: Float): FlowClock {
    val moving = remember { ValueAnimator.areAnimatorsEnabled() }
    val fractionState = rememberUpdatedState(fraction)
    var elapsed by remember { mutableFloatStateOf(0f) }
    var distance by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last).toFloat() / 1_000_000_000f).coerceIn(0f, 0.05f)
                    elapsed += dt
                    distance += dt * EffortFlow.lengthsPerSecond(fractionState.value)
                }
                last = now
            }
        }
    }
    return FlowClock(elapsed, distance, moving)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEffortTrack(
    fraction: Float,
    elapsed: Float,
    distance: Float,
    moving: Boolean,
    seeds: List<SparkSeed>,
    trackRest: Color,
    thumbRadius: Float,
    inset: Float,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val trackHeight = 28.dp.toPx()
    val top = (size.height - trackHeight) / 2f
    val left = inset
    val trackWidth = (size.width - inset * 2f).coerceAtLeast(0f)
    val radius = trackHeight / 2f
    val thumbX = EffortSliderMath.thumbCenterX(clamped, size.width, thumbRadius, inset)
    val fillRight = if (clamped >= 0.999f) left + trackWidth else thumbX.coerceAtMost(left + trackWidth)

    drawRoundRect(
        color = trackRest,
        topLeft = Offset(left, top),
        size = Size(trackWidth, trackHeight),
        cornerRadius = CornerRadius(radius, radius),
    )
    if (fillRight > left) {
        drawRoundRect(
            color = TrackBright.copy(alpha = 0.28f),
            topLeft = Offset(left, top + 3.dp.toPx()),
            size = Size(fillRight - left, trackHeight),
            cornerRadius = CornerRadius(radius, radius),
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(TrackDeep, TrackMid, TrackBright, TrackTip),
                startX = left,
                endX = fillRight.coerceAtLeast(left + 1f),
            ),
            topLeft = Offset(left, top),
            size = Size(fillRight - left, trackHeight),
            cornerRadius = CornerRadius(radius, radius),
        )
        val fillClip = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = left,
                    top = top,
                    right = fillRight,
                    bottom = top + trackHeight,
                    cornerRadius = CornerRadius(radius, radius),
                ),
            )
        }
        clipPath(fillClip) {
            val glossY = top + 2.2.dp.toPx()
            if (fillRight - left > radius) {
                drawLine(
                    color = Color.White.copy(alpha = 0.28f),
                    start = Offset(left + radius * 0.6f, glossY),
                    end = Offset(fillRight - radius * 0.35f, glossY),
                    strokeWidth = 1.4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            val span = (fillRight - left - radius).coerceAtLeast(0f)
            val bobAmp = 1.2.dp.toPx() * (1f - clamped)
            seeds.forEach { seed ->
                val travel = if (moving) {
                    distance * seed.speedScale * if (seed.star) EffortFlow.STAR_SPEED else 1f
                } else {
                    0f
                }
                val along = EffortFlow.headAlong(seed.along, travel)
                val wave = sin((elapsed * seed.twinkle + seed.phase) * (2.0 * PI)).toFloat()
                val y = top + seed.across * trackHeight + wave * bobAmp * 0.35f
                val tailFrac = if (moving) {
                    EffortFlow.seedTailFraction(clamped, seed.speedScale, seed.star)
                } else {
                    EffortFlow.TAIL_MIN
                }
                val twinkle = 0.72f + 0.28f * (0.5f + 0.5f * wave)
                drawMeteor(
                    head = Offset(left + radius * 0.45f + along * span, y),
                    tailPx = tailFrac * span,
                    radius = (1.15.dp.toPx() * seed.radiusScale).coerceAtMost(trackHeight * 0.18f),
                    alpha = twinkle * (0.4f + 0.6f * clamped),
                )
            }
        }
    }
    val thumbCenter = Offset(thumbX, size.height / 2f)
    drawCircle(
        color = Color.Black.copy(alpha = 0.14f),
        radius = thumbRadius,
        center = thumbCenter + Offset(0f, 1.6.dp.toPx()),
    )
    drawCircle(color = Color.White, radius = thumbRadius, center = thumbCenter)
    drawCircle(
        color = Color(0xFFE7E5E4),
        radius = thumbRadius,
        center = thumbCenter,
        style = Stroke(width = 1.dp.toPx()),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMeteor(
    head: Offset,
    tailPx: Float,
    radius: Float,
    alpha: Float,
) {
    val color = Color.White.copy(alpha = alpha.coerceIn(0f, 1f))
    if (tailPx > 1.5f) {
        drawLine(
            color = color.copy(alpha = color.alpha * 0.35f),
            start = Offset(head.x - tailPx, head.y),
            end = head,
            strokeWidth = radius * 2.2f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color.copy(alpha = color.alpha * 0.85f),
            start = Offset(head.x - tailPx * 0.28f, head.y),
            end = head,
            strokeWidth = radius * 1.15f,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(color = color, radius = radius, center = head)
}
