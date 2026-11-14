package com.pyeonangil.ui

import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pyeonangil.data.DemoDriveSource
import com.pyeonangil.data.DriveSource
import com.pyeonangil.data.PleosDriveSource
import com.pyeonangil.data.ProfileStore
import com.pyeonangil.engine.ChildFeedback
import com.pyeonangil.engine.ComfortReport
import com.pyeonangil.engine.MotionSample
import com.pyeonangil.engine.RideEngine
import com.pyeonangil.engine.RideEvent
import com.pyeonangil.engine.RideState
import com.pyeonangil.voice.Speaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SourceMode { PLEOS, DEMO }

/** 화면 하단에 잠깐 띄우는 음성 안내 자막. 소리가 안 나도 내용은 보인다. */
data class Caption(val text: String, val forChild: Boolean)

/** 오늘의 여정 기록 한 줄. */
data class LogEntry(val elapsedMs: Long, val text: String, val kind: LogKind)

enum class LogKind { ROUTE, DRIVER, CHILD, REST }

data class UiState(
    val mode: SourceMode = SourceMode.DEMO,
    val running: Boolean = false,
    val sourceLabel: String = "",
    val status: String = "",
    val demoPhase: String = "",
    val ride: RideState = RideState(),
    val caption: Caption? = null,
    val askFeedback: Boolean = false,
    val report: ComfortReport? = null,
    val sensitivity: Float = 1f,
    /** 출발 후 멀미 지수 기록 (주행 15초마다 한 점). */
    val history: List<Int> = emptyList(),
    /** 마지막으로 아이에게 들려준 안내. */
    val lastChildCue: String? = null,
    /** 출발부터 일어난 일 (최근 것이 뒤). */
    val journey: List<LogEntry> = emptyList(),
)

class RideViewModel(app: Application) : AndroidViewModel(app) {

    private val profiles = ProfileStore(app)
    private val speaker = Speaker(app)
    private var engine = RideEngine(profiles.load())

    private val isAutomotive = app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    private val _ui = MutableStateFlow(
        UiState(
            mode = SourceMode.DEMO,
            sensitivity = engine.profile.sensitivity,
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var source: DriveSource? = null
    private val jobs = ArrayList<Job>()
    private var lastPublishMs = 0L
    private var lastHistoryMs = -15_000L
    private val speech = Channel<Caption>(capacity = 8)

    init {
        // 음성 안내는 한 번에 하나씩, 앞 문장이 끝날 시간을 두고 말한다.
        viewModelScope.launch {
            for (c in speech) {
                speaker.speak(c.text)
                _ui.update { it.copy(caption = c) }
                delay(2_500L + 90L * c.text.length)
                _ui.update { if (it.caption == c) it.copy(caption = null) else it }
            }
        }
    }

    fun setMode(mode: SourceMode) {
        if (_ui.value.mode == mode) return
        val wasRunning = _ui.value.running
        stopSource()
        _ui.update { it.copy(mode = mode, running = false) }
        if (wasRunning) start()
    }

    fun start() {
        if (_ui.value.running) return
        engine = RideEngine(profiles.load())
        lastHistoryMs = -15_000L
        val src: DriveSource = when (_ui.value.mode) {
            SourceMode.PLEOS -> PleosDriveSource(getApplication<Application>(), viewModelScope)
            SourceMode.DEMO -> DemoDriveSource(viewModelScope)
        }
        source = src
        _ui.update {
            it.copy(running = true, report = null, askFeedback = false, ride = RideState(), sourceLabel = src.label, history = emptyList(), lastChildCue = null,
                journey = listOf(LogEntry(0, "출발", LogKind.ROUTE)))
        }

        jobs += viewModelScope.launch { src.samples.collect { onSample(it) } }
        jobs += viewModelScope.launch { src.gear.collect { engine.onGear(it) } }
        jobs += viewModelScope.launch { src.route.collect { r -> engine.onRoute(r?.turns.orEmpty(), r?.remainingM) } }
        jobs += viewModelScope.launch { src.status.collect { s -> _ui.update { it.copy(status = s) } } }
        if (src is DemoDriveSource) {
            jobs += viewModelScope.launch {
                src.phase.collect { p ->
                    val changed = p.isNotBlank() && p != _ui.value.demoPhase && _ui.value.demoPhase.isNotBlank()
                    _ui.update { it.copy(demoPhase = p) }
                    if (changed) log("$p 구간", LogKind.ROUTE)
                }
            }
            jobs += viewModelScope.launch { src.finished.collect { done -> if (done) endTrip() } }
        }
        src.start()
    }

    private fun onSample(sample: MotionSample) {
        val events = engine.onSample(sample)
        events.forEach(::handle)
        val now = SystemClock.elapsedRealtime()
        if (events.isNotEmpty() || now - lastPublishMs >= 200) {
            lastPublishMs = now
            val ride = engine.state
            val record = ride.elapsedMs - lastHistoryMs >= 15_000L
            if (record) lastHistoryMs = ride.elapsedMs
            _ui.update {
                it.copy(
                    ride = ride,
                    history = if (record) (it.history + ride.index).takeLast(720) else it.history,
                    // 차가 다시 움직이면 질문 카드는 거둔다 (주행 중 조작 금지).
                    askFeedback = it.askFeedback && ride.speedKmh < 2f,
                )
            }
        }
    }

    private fun handle(e: RideEvent) {
        when (e) {
            is RideEvent.CoachDriver -> {
                speech.trySend(Caption(e.message, forChild = false))
                log("운전 코칭 · 흔들림이 반복됨", LogKind.DRIVER)
            }
            is RideEvent.SuggestRest -> {
                speech.trySend(Caption(e.message, forChild = false))
                log("휴식 제안 · 예상 지수 ${e.suggestion.predictedIndexAtEnd.coerceAtMost(100)}", LogKind.REST)
            }
            is RideEvent.CurveAheadNotice -> {
                speech.trySend(Caption(e.message, forChild = true))
                _ui.update { it.copy(lastChildCue = e.message) }
                log("굽은 길 구간 시작 안내", LogKind.CHILD)
            }
            is RideEvent.ComfortCheck -> {
                speech.trySend(Caption(e.message, forChild = true))
                log("안부: ${e.message}", LogKind.CHILD)
            }
            is RideEvent.SuggestLowerRegen -> {
                speech.trySend(Caption(e.message, forChild = false))
                log("회생제동 낮추기 제안", LogKind.DRIVER)
            }
            RideEvent.AskChildFeedback -> {
                _ui.update { it.copy(askFeedback = true) }
                log("정차 · 쉬는 동안 지수 회복", LogKind.REST)
            }
        }
    }

    private fun log(text: String, kind: LogKind) {
        val t = engine.state.elapsedMs
        _ui.update { it.copy(journey = (it.journey + LogEntry(t, text, kind)).takeLast(60)) }
    }

    fun acceptRest() {
        log("휴게소 들르기로 함", LogKind.REST)
        val opened = source?.openRestAreaSearch() == true
        engine.dismissSuggestion()
        _ui.update { it.copy(ride = engine.state) }
        speech.trySend(
            Caption(if (opened) "내비에서 휴게소를 골라 주세요." else "내비에서 휴게소를 검색해 주세요.", forChild = false),
        )
    }

    fun dismissRest() {
        engine.dismissSuggestion()
        _ui.update { it.copy(ride = engine.state) }
    }

    fun feedback(f: ChildFeedback) {
        log(
            "아이 상태: " + when (f) {
                ChildFeedback.FINE -> "괜찮아요"
                ChildFeedback.A_LITTLE -> "조금 힘들어요"
                ChildFeedback.HARD -> "많이 힘들어요"
            },
            LogKind.CHILD,
        )
        engine.onChildFeedback(f)
        profiles.save(engine.profile)
        _ui.update { it.copy(askFeedback = false, sensitivity = engine.profile.sensitivity) }
    }

    fun endTrip() {
        if (!_ui.value.running) return
        log("도착", LogKind.ROUTE)
        val report = engine.report()
        profiles.save(engine.profile)
        stopSource()
        _ui.update { it.copy(running = false, report = report, askFeedback = false) }
    }

    fun closeReport() = _ui.update { it.copy(report = null) }

    private fun stopSource() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        source?.stop()
        source = null
    }

    override fun onCleared() {
        stopSource()
        speaker.release()
    }
}
