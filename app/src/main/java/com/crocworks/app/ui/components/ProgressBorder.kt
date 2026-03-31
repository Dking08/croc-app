package com.crocworks.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws an animated progress border around a composable — similar to the Play Store
 * download ring that wraps around an app icon.
 *
 * A faint track border is drawn first, then a sweep-gradient progress border
 * fills clockwise from the top based on [progress] (0f..1f).
 */
fun Modifier.progressBorder(
    progress: Float,
    color: Color,
    trackColor: Color = color.copy(alpha = 0.15f),
    strokeWidth: Dp = 3.dp,
    cornerRadius: Dp = 28.dp
): Modifier = this.drawWithContent {
    drawContent()

    val stroke = strokeWidth.toPx()
    val radius = cornerRadius.toPx()
    val halfStroke = stroke / 2

    val rectTopLeft = Offset(halfStroke, halfStroke)
    val rectSize = Size(size.width - stroke, size.height - stroke)
    val cr = CornerRadius(radius)

    // Track (always visible)
    drawRoundRect(
        color = trackColor,
        topLeft = rectTopLeft,
        size = rectSize,
        cornerRadius = cr,
        style = Stroke(width = stroke)
    )

    // Progress fill via sweep gradient
    if (progress > 0.001f) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val sweepBrush = Brush.sweepGradient(
            colorStops = arrayOf(
                0f to color,
                clampedProgress to color,
                (clampedProgress + 0.005f).coerceAtMost(1f) to Color.Transparent,
                1f to Color.Transparent
            ),
            center = Offset(size.width / 2, size.height / 2)
        )
        drawRoundRect(
            brush = sweepBrush,
            topLeft = rectTopLeft,
            size = rectSize,
            cornerRadius = cr,
            style = Stroke(width = stroke)
        )
    }
}
