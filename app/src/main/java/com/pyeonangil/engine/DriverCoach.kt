package com.pyeonangil.engine

import kotlin.math.abs

/**
 * 급가감속·급회전을 세고, 반복될 때만 운전자에게 짧게 말한다. 잔소리가 되지 않도록 간격과 횟수를 제한한다.
 */
class DriverCoach {
    data class HarshEvent(val elapsedMs: Long, val kind: String, val magnitude: Float)

    private val events = ArrayList<HarshEvent>()
    private var lastEventMs = Long.MIN_VALUE / 2
    /** 임계값을 넘은 상태가 이어지는 동안은 한 번만 센다. */
    private var inExcursion = false
    private var lastCoachMs = Long.MIN_VALUE / 2
    private var coachCount = 0

    val allEvents: List<HarshEvent> get() = events

    /** 급이벤트를 판단하고, 코칭할 때가 되면 문장을 돌려준다. */
    fun onKinematics(elapsedMs: Long, longMps2: Float, latMps2: Float): String? {
        val kind = when {
            longMps2 <= -Tuning.HARSH_LONG_MPS2 -> "급감속"
            longMps2 >= Tuning.HARSH_LONG_MPS2 -> "급가속"
            abs(latMps2) >= Tuning.HARSH_LAT_MPS2 -> "급회전"
            else -> {
                inExcursion = false
                return null
            }
        }
        if (inExcursion || elapsedMs - lastEventMs < Tuning.HARSH_DEBOUNCE_MS) return null
        inExcursion = true
        lastEventMs = elapsedMs
        val magnitude = if (kind == "급회전") abs(latMps2) else abs(longMps2)
        events += HarshEvent(elapsedMs, kind, magnitude)

        val recent = events.count { elapsedMs - it.elapsedMs <= Tuning.COACH_WINDOW_MS }
        val cooled = elapsedMs - lastCoachMs >= Tuning.COACH_COOLDOWN_MS
        if (recent >= Tuning.COACH_EVENTS_IN_WINDOW && cooled && coachCount < Tuning.COACH_MAX_PER_TRIP) {
            lastCoachMs = elapsedMs
            coachCount++
            return if (kind == "급회전") "뒷자리가 많이 흔들려요. 커브에서 조금만 천천히요."
            else "뒷자리가 많이 흔들려요. 조금만 부드럽게요."
        }
        return null
    }

    fun worstMoments(n: Int = 3): List<WorstMoment> =
        events.sortedByDescending { it.magnitude }.take(n).map { WorstMoment(it.elapsedMs, it.kind, it.magnitude) }
}

/**
 * 가족별 멀미 민감도. 정차 중 "괜찮음 / 조금 / 힘듦" 탭으로 조금씩 맞춘다.
 * 지수가 낮았는데 힘들었다면 민감도를 올리고, 지수가 높았는데 괜찮았다면 내린다.
 */
data class FamilyProfile(val sensitivity: Float = 1.0f, val lastComfortScore: Int? = null) {

    fun withFeedback(feedback: ChildFeedback, indexAtAsk: Int): FamilyProfile {
        val factor = when (feedback) {
            ChildFeedback.FINE -> if (indexAtAsk >= 50) 0.9f else 0.98f
            ChildFeedback.A_LITTLE -> if (indexAtAsk < 40) 1.12f else 1.03f
            ChildFeedback.HARD -> if (indexAtAsk < 60) 1.25f else 1.08f
        }
        val next = (sensitivity * factor).coerceIn(Tuning.SENSITIVITY_MIN, Tuning.SENSITIVITY_MAX)
        return copy(sensitivity = next)
    }
}

object ComfortScoring {
    /** 편안함 점수 0~100. 최고 지수와 급이벤트 횟수로 깎는다. */
    fun score(peakIndex: Int, harshEvents: Int): Int =
        (100 - 0.4f * peakIndex - 3f * harshEvents).toInt().coerceIn(0, 100)
}
