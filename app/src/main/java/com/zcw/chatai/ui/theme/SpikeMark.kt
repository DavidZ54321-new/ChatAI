package com.zcw.chatai.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SpikeMark(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = LocalContentColor.current,
) {
    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val outer = this.size.minDimension / 2f
        val inner = outer * 0.17f
        val path = Path()
        for (i in 0 until 4) {
            val angle = Math.toRadians((i * 90).toDouble())
            val nextAngle = Math.toRadians(((i + 1) * 90).toDouble())
            val midAngle = angle + Math.toRadians(45.0)
            val tipX = center.x + outer * cos(angle).toFloat()
            val tipY = center.y + outer * sin(angle).toFloat()
            val ctrlX = center.x + inner * cos(midAngle).toFloat()
            val ctrlY = center.y + inner * sin(midAngle).toFloat()
            val nextX = center.x + outer * cos(nextAngle).toFloat()
            val nextY = center.y + outer * sin(nextAngle).toFloat()
            if (i == 0) path.moveTo(tipX, tipY) else path.lineTo(tipX, tipY)
            path.quadraticTo(ctrlX, ctrlY, nextX, nextY)
        }
        path.close()
        drawPath(path, color)
    }
}
