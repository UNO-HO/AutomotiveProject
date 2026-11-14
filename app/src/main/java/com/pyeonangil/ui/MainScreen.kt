package com.pyeonangil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyeonangil.engine.ChildFeedback
import com.pyeonangil.engine.ComfortReport
import com.pyeonangil.engine.ForecastSegment
import com.pyeonangil.engine.RestSuggestion
import com.pyeonangil.engine.RiskLevel
import com.pyeonangil.engine.RouteForecaster
import kotlin.math.roundToInt

/**
 * 운전자 분산을 줄이기 위한 원칙
 * - 주행 중 조작은 휴식 제안의 큰 버튼 두 개뿐. 나머지 입력은 정차 중에만 보인다.
 * - 글자는 크게, 문장은 짧게. 상태는 색과 숫자 하나로.
 * - 깜빡이거나 계속 움직이는 애니메이션은 쓰지 않는다.
 *
 * Pleos Connect에서 앱은 운전석 주행 화면 옆 앱 영역에 뜨고, 내비와 나란히 두 창으로 나뉠 수 있다.
 * 그래서 창 폭이 좁으면(900dp 미만) 한 줄로 쌓는 간단 배치로 바꾼다.
 */
@Composable
fun MainScreen(
    ui: UiState,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onMode: (SourceMode) -> Unit,
    onAcceptRest: () -> Unit,
    onDismissRest: () -> Unit,
    onFeedback: (ChildFeedback) -> Unit,
    onCloseReport: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 900.dp
            Column(Modifier.fillMaxSize().padding(if (compact) 14.dp else 24.dp)) {
                TopBar(ui, compact, onStart, onEnd, onMode)
                Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val report = ui.report
                    when {
                        report != null -> ReportView(report, ui.history, ui.journey, compact, onCloseReport)
                        !ui.running -> IdleView(ui)
                        else -> DrivingView(ui, compact, onAcceptRest, onDismissRest)
                    }
                    if (ui.askFeedback) FeedbackOverlay(compact, onFeedback)
                }
                CaptionBar(ui)
            }
        }
    }
}

@Composable
private fun TopBar(ui: UiState, compact: Boolean, onStart: () -> Unit, onEnd: () -> Unit, onMode: (SourceMode) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("편안길", fontSize = if (compact) 26.sp else 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            if (!compact) {
                if (!ui.running) ModeChips(ui, onMode) else StatusText(ui, Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            if (ui.running) {
                OutlinedButton(onClick = onEnd, modifier = Modifier.height(60.dp)) { Text("주행 종료", fontSize = 19.sp) }
            } else if (ui.report == null) {
                Button(onClick = onStart, modifier = Modifier.height(60.dp)) { Text("출발", fontSize = 21.sp) }
            }
        }
        if (compact) {
            Spacer(Modifier.height(8.dp))
            if (!ui.running) ModeChips(ui, onMode) else StatusText(ui, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ModeChips(ui: UiState, onMode: (SourceMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip("실차 연동", ui.mode == SourceMode.PLEOS) { onMode(SourceMode.PLEOS) }
        ModeChip("시연 모드", ui.mode == SourceMode.DEMO) { onMode(SourceMode.DEMO) }
    }
}

@Composable
private fun StatusText(ui: UiState, modifier: Modifier) {
    Text(
        listOf(ui.sourceLabel, ui.demoPhase.takeIf { it.isNotBlank() }, ui.status)
            .filterNotNull().joinToString("  ·  "),
        fontSize = 17.sp,
        maxLines = 1,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = modifier,
    )
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.height(52.dp)) { Text(label, fontSize = 18.sp) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.height(52.dp)) { Text(label, fontSize = 18.sp) }
    }
}

@Composable
private fun IdleView(ui: UiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("목적지만 정하고 출발하세요", fontSize = 30.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(
            "앞으로 갈 길과 지금의 흔들림으로 뒷자리 멀미를 미리 알려 드려요.",
            fontSize = 19.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))
        val s = ui.sensitivity
        val label = when {
            s >= 1.3f -> "우리 아이는 멀미에 예민한 편으로 맞춰져 있어요"
            s <= 0.8f -> "우리 아이는 멀미에 강한 편으로 맞춰져 있어요"
            else -> "정차할 때 아이 상태를 알려 주시면 점점 우리 가족에 맞춰져요"
        }
        Text(label, fontSize = 18.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DrivingView(ui: UiState, compact: Boolean, onAcceptRest: () -> Unit, onDismissRest: () -> Unit) {
    if (compact) {
        // 내비와 나란히 띄운 좁은 창: 한 줄로 쌓고 스크롤.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.ride.activeSuggestion?.let { RestCard(it, onAcceptRest, onDismissRest) }
            IndexCard(ui.ride.index, ui.ride.level, compact = true)
            FeelGauge(ui.ride.drivingRms, ui.ride.feel, Modifier.fillMaxWidth())
            ForecastCard(ui.ride.forecast)
            TripStatsRow(ui.ride.elapsedMs, ui.ride.speedKmh, ui.ride.harshEventCount)
            TrendCard(ui.history, Modifier.fillMaxWidth().height(200.dp))
            JourneyCard(ui.journey, Modifier.fillMaxWidth().height(260.dp))
        }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IndexCard(ui.ride.index, ui.ride.level, Modifier.weight(1f))
                FeelGauge(ui.ride.drivingRms, ui.ride.feel, Modifier.weight(1f))
            }
            ForecastCard(ui.ride.forecast)
            ui.ride.activeSuggestion?.let { RestCard(it, onAcceptRest, onDismissRest) }
            TripStatsRow(ui.ride.elapsedMs, ui.ride.speedKmh, ui.ride.harshEventCount)
            TrendCard(ui.history, Modifier.weight(1f).fillMaxWidth())
        }
        JourneyCard(ui.journey, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun IndexCard(index: Int, level: RiskLevel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val color = LevelColors.of(level)
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(if (compact) 92.dp else 120.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)).border(4.dp, color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$index", fontSize = if (compact) 38.sp else 48.sp, fontWeight = FontWeight.Bold, color = color)
            }
            Spacer(Modifier.width(if (compact) 16.dp else 24.dp))
            Column {
                Text("뒷자리 멀미 위험", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Text(
                    when (level) {
                        RiskLevel.CALM -> "편안해요"
                        RiskLevel.MODERATE -> "조금 흔들려요"
                        RiskLevel.HIGH -> "멀미 주의"
                    },
                    fontSize = if (compact) 30.sp else 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun ForecastCard(forecast: List<ForecastSegment>) {
    Panel {
        Text("앞으로의 멀미 예보", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        if (forecast.isEmpty()) {
            Text("내비에서 목적지를 정하면 앞길 예보가 보여요", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            return@Panel
        }
        val total = forecast.last().endM
        Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp))) {
            forecast.forEach { s ->
                Box(
                    Modifier.weight(((s.endM - s.startM) / total).coerceAtLeast(0.001f)).fillMaxHeight()
                        .background(LevelColors.of(s.level))
                        .border(0.5.dp, MaterialTheme.colorScheme.background),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("지금", fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text("${(total / 1000f).roundToInt()}km", fontSize = 15.sp)
        }
        val firstHigh = RouteForecaster.highRuns(forecast).firstOrNull()
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                firstHigh == null -> "앞길은 대체로 편안해요"
                firstHigh.first < 500f -> "구불구불한 길을 지나는 중 · ${(firstHigh.second / 1000f).roundToInt()}km 남음"
                else -> "${(firstHigh.first / 1000f).roundToInt()}km 앞부터 구불구불한 길 ${(firstHigh.second / 1000f).roundToInt()}km"
            },
            fontSize = 20.sp,
        )
    }
}

@Composable
private fun RestCard(s: RestSuggestion, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Panel(border = LevelColors.high) {
        Text("쉬어 갈까요?", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "${(s.highSegmentStartsInM / 1000f).roundToInt()}km 앞부터 구불구불한 길 ${(s.highSegmentLengthM / 1000f).roundToInt()}km. " +
                "그대로 가면 끝날 때쯤 지수 ${s.predictedIndexAtEnd.coerceAtMost(100)}",
            fontSize = 19.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAccept, modifier = Modifier.weight(1f).height(72.dp)) { Text("휴게소 찾기", fontSize = 22.sp) }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(72.dp)) { Text("괜찮아요", fontSize = 22.sp) }
        }
    }
}

@Composable
private fun FeedbackOverlay(compact: Boolean, onFeedback: (ChildFeedback) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, modifier = Modifier.padding(12.dp)) {
            Column(Modifier.padding(if (compact) 20.dp else 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("아이 상태 어때요?", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("알려 주시면 다음 안내가 우리 아이에게 맞춰져요", fontSize = 17.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                val buttons: @Composable (Modifier) -> Unit = { m ->
                    FeedbackButton("괜찮아요", LevelColors.calm, m) { onFeedback(ChildFeedback.FINE) }
                    FeedbackButton("조금 힘들어요", LevelColors.moderate, m) { onFeedback(ChildFeedback.A_LITTLE) }
                    FeedbackButton("많이 힘들어요", LevelColors.high, m) { onFeedback(ChildFeedback.HARD) }
                }
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { buttons(Modifier.fillMaxWidth()) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { buttons(Modifier.width(220.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FeedbackButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        modifier = modifier.height(88.dp),
    ) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ReportView(r: ComfortReport, history: List<Int>, journey: List<LogEntry>, compact: Boolean, onClose: () -> Unit) {
    if (compact) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScorePanel(r, onClose)
            ReportTiles(r)
            FeelShareCard(r)
            TrendCard(history, Modifier.fillMaxWidth().height(200.dp))
            WorstMomentsCard(r)
            JourneyCard(journey, Modifier.fillMaxWidth().height(300.dp))
        }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScorePanel(r, onClose)
            ReportTiles(r)
            FeelShareCard(r)
            WorstMomentsCard(r)
        }
        Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TrendCard(history, Modifier.weight(1f).fillMaxWidth())
            JourneyCard(journey, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun ScorePanel(r: ComfortReport, onClose: () -> Unit) {
    Panel {
        Text("오늘의 탑승자 편안함", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${r.score}", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("점", fontSize = 28.sp, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
            Spacer(Modifier.weight(1f))
            Button(onClick = onClose, modifier = Modifier.height(60.dp)) { Text("닫기", fontSize = 19.sp) }
        }
        Text(
            r.deltaFromLast?.let { d ->
                when {
                    d > 0 -> "지난 주행보다 ${d}점 좋아졌어요"
                    d < 0 -> "지난 주행보다 ${-d}점 낮아요"
                    else -> "지난 주행과 같아요"
                }
            } ?: "첫 주행 기록이에요. 다음 주행부터 비교해 드려요.",
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun ReportTiles(r: ComfortReport) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportTile("주행 시간", "${r.durationMin}분", Modifier.weight(1f))
        ReportTile("최고 멀미 지수", "${r.peakIndex}", Modifier.weight(1f))
        ReportTile("큰 흔들림", "${r.harshEvents}번", Modifier.weight(1f))
    }
}

@Composable
private fun ReportTile(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
        Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

/** 달린 시간 중 부드러움·거침·과격 비율 막대. */
@Composable
private fun FeelShareCard(r: ComfortReport) {
    Panel {
        Text("내 운전은 어땠나", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp))) {
            listOf(r.smoothPct to LevelColors.calm, r.roughPct to LevelColors.moderate, r.harshPct to LevelColors.high)
                .filter { it.first > 0 }
                .forEach { (pct, color) -> Box(Modifier.weight(pct.toFloat()).fillMaxHeight().background(color)) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("부드러움 ${r.smoothPct}%", fontSize = 16.sp, color = LevelColors.calm)
            Text("거침 ${r.roughPct}%", fontSize = 16.sp, color = LevelColors.moderate)
            Text("과격 ${r.harshPct}%", fontSize = 16.sp, color = LevelColors.high)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                r.harshPct >= 5 -> "급커브·급제동에서 조금만 더 부드럽게 달리면 점수가 크게 올라요."
                r.roughPct >= 30 -> "굽은 길에서 속도를 조금 낮추면 뒷자리가 더 편해요."
                else -> "아주 부드럽게 달렸어요!"
            },
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun WorstMomentsCard(r: ComfortReport) {
    Panel {
        Text("가장 흔들렸던 순간", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (r.worstMoments.isEmpty()) Text("크게 흔들린 순간이 없었어요", fontSize = 18.sp)
        r.worstMoments.forEachIndexed { i, m ->
            Text(
                "${i + 1}. 출발 ${formatElapsed(m.elapsedMs)} · ${m.kind} (${"%.1f".format(m.magnitude)} m/s²)",
                fontSize = 18.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

/** 오늘의 여정: 출발부터 일어난 일을 시간순으로 (최근 것이 위). */
@Composable
private fun JourneyCard(entries: List<LogEntry>, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp)) {
        Text("오늘의 여정", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            entries.asReversed().forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(12.dp).clip(CircleShape).background(
                            when (e.kind) {
                                LogKind.ROUTE -> MaterialTheme.colorScheme.primary
                                LogKind.DRIVER -> LevelColors.moderate
                                LogKind.CHILD -> Color(0xFF8E6BD8)
                                LogKind.REST -> LevelColors.calm
                            },
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        formatElapsed(e.elapsedMs),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.width(64.dp),
                    )
                    Text(e.text, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun CaptionBar(ui: UiState) {
    val c = ui.caption ?: return
    Spacer(Modifier.height(12.dp))
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(c.text, fontSize = 22.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp))
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    border: Color? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val base = modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
    Column((if (border != null) base.border(2.dp, border, shape) else base).padding(20.dp), content = content)
}
