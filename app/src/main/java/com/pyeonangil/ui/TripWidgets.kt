package com.pyeonangil.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyeonangil.engine.RideEngine
import kotlin.math.roundToInt

/** 주행 시간 · 현재 속도 · 흔들림 횟수. */
@Composable
fun TripStatsRow(elapsedMs: Long, speedKmh: Float, harshEvents: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("주행 시간", formatElapsed(elapsedMs), Modifier.weight(1f))
        StatTile("현재 속도", "${speedKmh.roundToInt()} km/h", Modifier.weight(1f))
        StatTile("큰 흔들림", "${harshEvents}번", Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 출발부터 지금까지 멀미 지수 변화. 40·70 경계에 점선, 구간별 배경색. */
@Composable
fun TrendCard(history: List<Int>, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text("멀미 지수 변화", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("출발부터 지금까지", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(10.dp))
        val lineColor = MaterialTheme.colorScheme.primary
        val guide = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                fun y(v: Int) = h * (1f - v / 100f)
                // 구간 배경: 편안(0~40) / 보통(40~70) / 주의(70~100)
                drawRect(LevelColors.high.copy(alpha = 0.10f), Offset(0f, 0f), Size(w, y(70)))
                drawRect(LevelColors.moderate.copy(alpha = 0.10f), Offset(0f, y(70)), Size(w, y(40) - y(70)))
                drawRect(LevelColors.calm.copy(alpha = 0.10f), Offset(0f, y(40)), Size(w, h - y(40)))
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                drawLine(guide, Offset(0f, y(70)), Offset(w, y(70)), strokeWidth = 2f, pathEffect = dash)
                drawLine(guide, Offset(0f, y(40)), Offset(w, y(40)), strokeWidth = 2f, pathEffect = dash)
                if (history.size >= 2) {
                    // 기록이 적을 때도 화면을 다 쓰도록 최소 40점 폭으로 그린다.
                    val n = maxOf(history.size, 40)
                    val step = w / (n - 1)
                    val path = Path()
                    history.forEachIndexed { i, v ->
                        val x = i * step
                        if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
                    }
                    drawPath(path, lineColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
                    val last = history.last()
                    drawCircle(LevelColors.of(RideEngine.levelForIndex(last)), 9f, Offset((history.size - 1) * step, y(last)))
                }
            }
            if (history.size < 2) {
                Text(
                    "달리기 시작하면 지수 변화가 그려져요",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("출발", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.weight(1f))
            Text("편안 · 보통 · 주의 경계 40 / 70", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}
