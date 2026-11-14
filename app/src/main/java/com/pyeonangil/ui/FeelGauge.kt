package com.pyeonangil.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyeonangil.engine.DrivingFeel
import com.pyeonangil.engine.Tuning
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 운전 부드러움 계기판. 최근 15초의 흔들림을 반원 눈금 위 바늘 하나와 짧은 문장 하나로 보여 준다.
 * 운전 중 한눈에 읽히도록 바늘은 애니메이션 없이 갱신 때만 움직이고, 판단은 색과 문장 세 단계뿐이다.
 */
@Composable
fun FeelGauge(rms: Float, feel: DrivingFeel, modifier: Modifier = Modifier) {
    val color = when (feel) {
        DrivingFeel.SMOOTH -> LevelColors.calm
        DrivingFeel.ROUGH -> LevelColors.moderate
        DrivingFeel.HARSH -> LevelColors.high
    }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val needle = MaterialTheme.colorScheme.onSurface
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp),
    ) {
        Text("지금 내 운전", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(150.dp).height(84.dp)) {
                val stroke = 18f
                val d = min(size.width, size.height * 2f) - stroke
                val topLeft = Offset((size.width - d) / 2f, stroke / 2f)
                val arc = Size(d, d)
                val max = Tuning.FEEL_GAUGE_MAX_RMS
                fun sweep(v: Float) = 180f * (v / max)
                drawArc(track, 180f, 180f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Butt))
                drawArc(LevelColors.calm, 180f, sweep(Tuning.FEEL_ROUGH_RMS), false, topLeft, arc, style = Stroke(stroke))
                drawArc(LevelColors.moderate, 180f + sweep(Tuning.FEEL_ROUGH_RMS), sweep(Tuning.FEEL_HARSH_RMS - Tuning.FEEL_ROUGH_RMS), false, topLeft, arc, style = Stroke(stroke))
                drawArc(LevelColors.high, 180f + sweep(Tuning.FEEL_HARSH_RMS), sweep(max - Tuning.FEEL_HARSH_RMS), false, topLeft, arc, style = Stroke(stroke))
                val center = Offset(size.width / 2f, topLeft.y + d / 2f)
                val angle = PI * (1.0 - (rms.coerceIn(0f, max) / max).toDouble())
                val len = d / 2f - stroke
                val tip = Offset(center.x + (len * cos(angle)).toFloat(), center.y - (len * sin(angle)).toFloat())
                drawLine(needle, center, tip, strokeWidth = 6f, cap = StrokeCap.Round)
                drawCircle(needle, 9f, center)
            }
            Spacer(Modifier.width(16.dp))
            Text(
                when (feel) {
                    DrivingFeel.SMOOTH -> "부드러워요"
                    DrivingFeel.ROUGH -> "조금 거칠어요"
                    DrivingFeel.HARSH -> "너무 과격해요"
                },
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
