package com.pyeonangil.engine

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 편안길의 중심. 차량 신호와 경로 정보를 받아 멀미 위험 지수, 일기예보, 이벤트를 만든다.
 * 스레드 안전하지 않으므로 한 스레드(뷰모델의 코루틴)에서만 호출한다.
 *
 * @param isEv 전기차면 회생제동 단계 제안을 한다.
 */
class RideEngine(
    profile: FamilyProfile = FamilyProfile(),
    private val isEv: Boolean = true,
) {
    var profile: FamilyProfile = profile
        private set

    private val kinematics = KinematicsEstimator()
    private val dose = SicknessDose()
    private val coach = DriverCoach()

    private var startMs: Long? = null
    private var lastSampleMs: Long? = null
    private var odometerM = 0.0
    private var stoppedSinceMs: Long? = null
    private var askedThisStop = false
    private var peakIndex = 0
    private var lastLongMps2 = 0f
    /** 최근 구간의 (경과 ms, 가속도²) 기록. 운전 부드러움 계산용. */
    private val feelWindow = ArrayDeque<Pair<Long, Float>>()
    /** 달린 시간을 운전 느낌별로 나눠 센다 (ms). */
    private val feelMs = LongArray(3)

    private var turns: List<UpcomingTurn> = emptyList()
    private var remainingRouteM: Float? = null
    /** 이미 휴식을 제안한 위험 구간의 끝(누적 m). 그 전까지 시작하는 위험 구간에는 다시 제안하지 않는다. */
    private var restHandledUntilOdoM = -1.0
    /** 이미 아이 오디오 큐를 말한 위험 구간의 끝(누적 m). */
    private var childCuedUntilOdoM = -1.0
    /** 최근 주행 속도 평균 (m/s). 남은 시간 계산용. */
    private var avgSpeedMps = 0f
    /** 휴식 제안 카드를 거둘 누적 주행거리. 위험 구간에 들어서면 제안은 의미가 없다. */
    private var suggestionExpiresAtOdoM: Double? = null

    var state = RideState()
        private set

    /** 주행 측정값 하나를 넣는다. 그 결과로 생긴 일회성 이벤트를 돌려준다. */
    fun onSample(s: MotionSample): List<RideEvent> {
        val events = ArrayList<RideEvent>()
        val start = startMs ?: s.timeMs.also { startMs = it }
        val elapsed = s.timeMs - start
        val prevMs = lastSampleMs
        lastSampleMs = s.timeMs
        val dtMs = if (prevMs == null) 0L else (s.timeMs - prevMs).coerceAtLeast(0L)
        odometerM += s.speedMps * dtMs / 1000.0
        if (s.speedMps >= Tuning.STOP_SPEED_MPS) {
            val a = (dtMs / 120_000f).coerceIn(0f, 1f) // 약 2분 평균
            avgSpeedMps += a * (s.speedMps - avgSpeedMps)
        }

        val stopped = s.speedMps < Tuning.STOP_SPEED_MPS
        val k = kinematics.update(s)
        if (stopped) {
            dose.recover(dtMs)
            if (stoppedSinceMs == null) stoppedSinceMs = s.timeMs
        } else {
            stoppedSinceMs = null
            askedThisStop = false
            if (k != null) {
                dose.accumulate(k)
                lastLongMps2 = k.longMps2
                feelWindow.addLast(elapsed to (k.longMps2 * k.longMps2 + k.latMps2 * k.latMps2))
                coach.onKinematics(elapsed, k.longMps2, k.latMps2)?.let { events += RideEvent.CoachDriver(it) }
            }
        }

        // 정차가 이어지면 아이 상태를 한 번 묻는다 (출발 직후 정차는 제외).
        val since = stoppedSinceMs
        if (since != null && !askedThisStop && elapsed > 5 * 60_000L &&
            s.timeMs - since >= Tuning.FEEDBACK_AFTER_STOP_MS
        ) {
            askedThisStop = true
            events += RideEvent.AskChildFeedback
        }

        // 경로 거리는 TBT 갱신 사이에도 줄어들도록 이번 샘플에서 달린 만큼 당긴다.
        advanceRoute((s.speedMps * dtMs / 1000f))
        val forecast = RouteForecaster.forecast(turns, remainingRouteM)
        events += routeEvents(forecast)

        while (feelWindow.isNotEmpty() && elapsed - feelWindow.first().first > Tuning.FEEL_WINDOW_MS) feelWindow.removeFirst()
        val rms = if (feelWindow.isEmpty()) 0f else sqrt(feelWindow.sumOf { it.second.toDouble() } / feelWindow.size).toFloat()

        val rawIndex = dose.rawIndex(profile.sensitivity)
        val index = rawIndex.roundToInt().coerceIn(0, 100)
        peakIndex = maxOf(peakIndex, index)

        state = state.copy(
            index = index,
            level = levelForIndex(index),
            speedKmh = s.speedMps * 3.6f,
            forecast = forecast,
            cue = cueFor(stopped),
            harshEventCount = coach.allEvents.size,
            drivingRms = rms,
            feel = feelFor(rms),
            elapsedMs = elapsed,
        )
        if (!stopped) feelMs[state.feel.ordinal] += dtMs
        if (suggestionExpiresAtOdoM?.let { odometerM >= it } == true) {
            suggestionExpiresAtOdoM = null
            state = state.copy(activeSuggestion = null)
        }
        events.filterIsInstance<RideEvent.SuggestRest>().lastOrNull()?.let {
            suggestionExpiresAtOdoM = odometerM + it.suggestion.highSegmentStartsInM
            state = state.copy(activeSuggestion = it.suggestion)
        }
        return events
    }

    fun onGear(gear: Gear) {
        state = state.copy(gear = gear)
    }

    /** 내비 TBT가 새로 오면 바꾼다. [remainingM]은 목적지까지 남은 거리. */
    fun onRoute(newTurns: List<UpcomingTurn>, remainingM: Float?) {
        turns = newTurns.sortedBy { it.distanceAheadM }
        remainingRouteM = remainingM
    }

    fun onChildFeedback(feedback: ChildFeedback) {
        profile = profile.withFeedback(feedback, state.index)
    }

    fun dismissSuggestion() {
        suggestionExpiresAtOdoM = null
        state = state.copy(activeSuggestion = null)
    }

    fun report(): ComfortReport {
        val score = ComfortScoring.score(peakIndex, coach.allEvents.size)
        val r = ComfortReport(
            score = score,
            durationMin = (state.elapsedMs / 60_000L).toInt(),
            harshEvents = coach.allEvents.size,
            peakIndex = peakIndex,
            worstMoments = coach.worstMoments(),
            deltaFromLast = profile.lastComfortScore?.let { score - it },
            roughPct = pct(feelMs[1]),
            harshPct = pct(feelMs[2]),
        ).let { it.copy(smoothPct = if (feelMs.sum() == 0L) 100 else 100 - it.roughPct - it.harshPct) }
        profile = profile.copy(lastComfortScore = score)
        return r
    }

    private fun pct(ms: Long): Int {
        val total = feelMs.sum()
        return if (total == 0L) 0 else (100.0 * ms / total).roundToInt()
    }

    private fun advanceRoute(meters: Float) {
        if (meters <= 0f) return
        turns = turns.map { it.copy(distanceAheadM = it.distanceAheadM - meters) }.filter { it.distanceAheadM > -50f }
        remainingRouteM = remainingRouteM?.let { (it - meters).coerceAtLeast(0f) }
    }

    private fun routeEvents(forecast: List<ForecastSegment>): List<RideEvent> {
        val out = ArrayList<RideEvent>()
        for ((startAhead, length) in RouteForecaster.highRuns(forecast)) {
            val absStart = odometerM + startAhead
            val absEnd = absStart + length

            if (absStart > restHandledUntilOdoM && startAhead in Tuning.REST_MIN_AHEAD_M..Tuning.REST_MAX_AHEAD_M) {
                val seconds = length / Tuning.HIGH_SEGMENT_SPEED_MPS
                val predictedDose = dose.dose + Tuning.HIGH_SEGMENT_DOSE_RATE * seconds
                val predicted = SicknessDose.indexFor(predictedDose, profile.sensitivity).roundToInt()
                if (predicted >= Tuning.REST_PREDICTED_INDEX) {
                    restHandledUntilOdoM = absEnd
                    val speed = maxOf(avgSpeedMps, 8f)
                    val minutes = (startAhead / speed / 60f).roundToInt().coerceAtLeast(1)
                    val lenMin = (seconds / 60f).roundToInt().coerceAtLeast(1)
                    out += RideEvent.SuggestRest(
                        RestSuggestion(startAhead, length, predicted),
                        "약 ${minutes}분 뒤 구불구불한 길이 ${lenMin}분쯤 이어져요. 그 전에 쉬어 갈까요?",
                    )
                }
            }

            if (absStart > childCuedUntilOdoM && startAhead <= Tuning.CHILD_CUE_AHEAD_M) {
                childCuedUntilOdoM = absEnd
                out += RideEvent.ChildAudioCue("곧 꼬불꼬불 길이야! 화면은 잠깐 내려놓고, 창밖에 산이 몇 개 보이는지 세어 볼까?")
                if (isEv) out += RideEvent.SuggestLowerRegen("구불구불한 길이 시작돼요. 회생제동을 한 단계 낮추면 뒷자리가 편해요.")
            }
        }
        return out
    }

    private fun cueFor(stopped: Boolean): MotionCue {
        if (stopped) return MotionCue.NONE
        val next = turns.firstOrNull { it.distanceAheadM in 0f..Tuning.CUE_TURN_AHEAD_M && it.direction != 0 }
        return when {
            next != null && next.direction < 0 -> MotionCue.TURN_LEFT_SOON
            next != null && next.direction > 0 -> MotionCue.TURN_RIGHT_SOON
            lastLongMps2 <= Tuning.CUE_SLOWING_MPS2 -> MotionCue.SLOWING
            else -> MotionCue.NONE
        }
    }

    companion object {
        fun feelFor(rms: Float): DrivingFeel = when {
            rms >= Tuning.FEEL_HARSH_RMS -> DrivingFeel.HARSH
            rms >= Tuning.FEEL_ROUGH_RMS -> DrivingFeel.ROUGH
            else -> DrivingFeel.SMOOTH
        }

        fun levelForIndex(index: Int): RiskLevel = when {
            index >= 70 -> RiskLevel.HIGH
            index >= 40 -> RiskLevel.MODERATE
            else -> RiskLevel.CALM
        }
    }
}
