package com.pyeonangil.engine

/**
 * 편안길 엔진의 입력·출력 모델. 안드로이드 의존성이 없는 순수 Kotlin이라 JVM 단위 테스트로 검증한다.
 */

/** 차량에서 들어오는 한 번의 주행 측정값. */
data class MotionSample(
    /** 단조 증가 시각 (ms). */
    val timeMs: Long,
    /** 차량 속도 (m/s). AAOS PERF_VEHICLE_SPEED 값 그대로. */
    val speedMps: Float,
    /** 요(yaw) 회전 속도 (rad/s). 자이로나 진행 방향 변화로 구한다. 없으면 null → 횡방향 가속도 0으로 본다. */
    val yawRateRadPerSec: Float? = null,
)

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 내비 TBT(회전 안내) 한 항목을 엔진용으로 바꾼 것. */
data class UpcomingTurn(
    /** 현재 위치에서 그 회전 지점까지 남은 거리 (m). */
    val distanceAheadM: Float,
    /** 회전이 몸에 주는 부담 가중치. 일반 회전 1.0, 급회전·유턴 2~3, 직진 유지 0.3. */
    val weight: Float,
    /** 화면·로그용 이름 (예: TURN_LEFT). */
    val label: String,
    /** 왼쪽 -1, 오른쪽 +1, 알 수 없음 0. 뒷좌석 모션 큐 방향에 쓴다. */
    val direction: Int = 0,
)

enum class RiskLevel { CALM, MODERATE, HIGH }

/** 최근 몇 초 동안의 운전 느낌. 누적 멀미 지수와 달리 지금 운전을 바로 보여 준다. */
enum class DrivingFeel { SMOOTH, ROUGH, HARSH }

/** 앞으로 갈 길을 일정 거리 단위로 자른 멀미 일기예보 한 칸. */
data class ForecastSegment(
    val startM: Float,
    val endM: Float,
    val level: RiskLevel,
    /** km당 가중 회전 수. */
    val turnDensityPerKm: Float,
)

/** 휴식 제안. */
data class RestSuggestion(
    /** 위험 구간이 시작되기까지 남은 거리 (m). */
    val highSegmentStartsInM: Float,
    /** 위험 구간 길이 (m). */
    val highSegmentLengthM: Float,
    /** 그대로 가면 위험 구간 끝에서 예상되는 지수 (0~100+). */
    val predictedIndexAtEnd: Int,
)

/** 뒷좌석 화면 모션 큐 상태. */
enum class MotionCue { NONE, TURN_LEFT_SOON, TURN_RIGHT_SOON, SLOWING }

/** 엔진이 앱에 알리는 일회성 이벤트. */
sealed interface RideEvent {
    /** 운전자에게 짧게 말할 코칭. */
    data class CoachDriver(val message: String) : RideEvent

    /** 위험 구간 직전 휴식 제안. */
    data class SuggestRest(val suggestion: RestSuggestion, val message: String) : RideEvent

    /** 아이에게 말하는 오디오 큐 (창밖 보기 유도). */
    data class ChildAudioCue(val message: String) : RideEvent

    /** EV 회생제동 단계를 낮추자는 제안. */
    data class SuggestLowerRegen(val message: String) : RideEvent

    /** 정차 중 아이 상태를 물어볼 때가 됨. */
    data object AskChildFeedback : RideEvent
}

/** 정차 중 탭으로 받는 아이 상태. */
enum class ChildFeedback { FINE, A_LITTLE, HARD }

/** 화면에 그리는 현재 상태. */
data class RideState(
    /** 멀미 위험 지수 0~100. 가족 민감도를 반영한 값. */
    val index: Int = 0,
    val level: RiskLevel = RiskLevel.CALM,
    val speedKmh: Float = 0f,
    val gear: Gear = Gear.UNKNOWN,
    val forecast: List<ForecastSegment> = emptyList(),
    val cue: MotionCue = MotionCue.NONE,
    val activeSuggestion: RestSuggestion? = null,
    val harshEventCount: Int = 0,
    /** 최근 [Tuning.FEEL_WINDOW_MS] 동안 수평 가속도 RMS (m/s²). */
    val drivingRms: Float = 0f,
    val feel: DrivingFeel = DrivingFeel.SMOOTH,
    val elapsedMs: Long = 0,
)

/** 주행 후 편안함 리포트. */
data class ComfortReport(
    val score: Int,
    val durationMin: Int,
    val harshEvents: Int,
    val peakIndex: Int,
    val worstMoments: List<WorstMoment>,
    /** 이전 주행 대비 점수 변화. 이전 기록이 없으면 null. */
    val deltaFromLast: Int?,
    /** 달린 시간 중 운전 느낌 비율 (%): 부드러움, 거침, 과격. 합은 100. */
    val smoothPct: Int = 100,
    val roughPct: Int = 0,
    val harshPct: Int = 0,
)

data class WorstMoment(
    val elapsedMs: Long,
    val kind: String,
    /** 그 순간 가속도 크기 (m/s²). */
    val magnitude: Float,
)
