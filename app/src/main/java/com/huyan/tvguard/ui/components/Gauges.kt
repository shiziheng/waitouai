package com.huyan.tvguard.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 歪头角度仪表。
 *
 * 一条水平基准线，中间是「正」，左右各有刻度。
 * 指针偏到临界区以外时变色，家长一眼就能看出孩子歪了没。
 */
@Composable
fun TiltGauge(
    tiltDeg: Float,
    thresholdDeg: Float,
    modifier: Modifier = Modifier,
    maxDeg: Float = 45f,
) {
    val animatedTilt by animateFloatAsState(
        targetValue = tiltDeg,
        animationSpec = tween(durationMillis = 220),
        label = "tilt",
    )

    val overThreshold = abs(animatedTilt) >= thresholdDeg
    val pointerColor = if (overThreshold) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val safeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
        ) {
            val centerX = size.width / 2f
            val centerY = size.height * 0.62f
            val radius = min(size.width / 2f, size.height) * 0.78f

            val sweepPerSide = 70f
            val degToSweep = sweepPerSide / maxDeg

            // 安全区（阈值以内）
            val safeSweep = thresholdDeg * degToSweep
            drawArc(
                color = safeColor,
                startAngle = 270f - safeSweep,
                sweepAngle = safeSweep * 2,
                useCenter = false,
                topLeft = Offset(centerX - radius, centerY - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
            )

            // 底轨
            drawArc(
                color = trackColor,
                startAngle = 270f - sweepPerSide,
                sweepAngle = sweepPerSide * 2,
                useCenter = false,
                topLeft = Offset(centerX - radius, centerY - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
            )

            // 中心竖线
            drawLine(
                color = trackColor,
                start = Offset(centerX, centerY - radius - 6.dp.toPx()),
                end = Offset(centerX, centerY - radius + 6.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // 指针
            val clamped = animatedTilt.coerceIn(-maxDeg, maxDeg)
            val angleRad = Math.toRadians((270.0 + clamped * degToSweep))
            val lineLength = radius - 2.dp.toPx()
            val endX = centerX + (kotlin.math.cos(angleRad) * lineLength).toFloat()
            val endY = centerY + (kotlin.math.sin(angleRad) * lineLength).toFloat()

            drawLine(
                color = pointerColor,
                start = Offset(centerX, centerY),
                end = Offset(endX, endY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(color = pointerColor, radius = 5.dp.toPx(), center = Offset(centerX, centerY))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(30.dp))
            Text(
                text = "${abs(animatedTilt).roundToInt()}°",
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                color = pointerColor,
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = when {
                    abs(animatedTilt) < 3f -> "基本摆正"
                    overThreshold -> if (animatedTilt < 0) "往左歪" else "往右歪"
                    else -> if (animatedTilt < 0) "略往左" else "略往右"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 简单的横向条形指示，用于靠近程度和视线偏离。
 */
@Composable
fun LevelBar(
    fraction: Float,
    thresholdFraction: Float,
    over: Boolean,
    modifier: Modifier = Modifier,
) {
    val barColor = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val marker = MaterialTheme.colorScheme.outline

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
    ) {
        val h = size.height
        drawRoundRect(
            color = track,
            size = Size(size.width, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
        )
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0f) {
            drawRoundRect(
                color = barColor,
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
            )
        }
        // 阈值刻线
        val tx = size.width * thresholdFraction.coerceIn(0f, 1f)
        drawLine(
            color = marker,
            start = Offset(tx, -2.dp.toPx()),
            end = Offset(tx, h + 2.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

/** 纯色圆点，用来做状态灯 */
@Composable
fun StatusDot(color: Color, size: Int = 9) {
    Canvas(modifier = Modifier.size(size.dp)) {
        drawCircle(color = color)
    }
}
