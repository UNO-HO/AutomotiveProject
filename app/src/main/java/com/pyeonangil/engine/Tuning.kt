package com.pyeonangil.engine

/**
 * 엔진 설계값 모음. 문헌의 개념(ISO 2631-1 MSDV, 승차감 가속도 기준)을 출발점으로 잡은 초기값이며,
 * 실차 주행 기록으로 다시 맞춰야 한다. 값을 바꿀 때는 여기만 고치면 된다.
 */
object Tuning {
    // ---- 멀미 누적량(MSDV 유사) ----
    /** 멀미를 일으키는 저주파 대역 하한 (Hz). 이보다 느린 변화는 걸러낸다. */
    const val BAND_LOW_HZ = 0.08f
    /** 대역 상한 (Hz). 이보다 빠른 진동(노면 떨림)은 멀미 기여가 작아 걸러낸다. */
    const val BAND_HIGH_HZ = 0.63f
    /**
     * 기본 민감도 1.0에서 지수 100에 해당하는 누적량 (m/s^1.5).
     * 기준 탑승자: 도심·고속도로·정체에 굽은 길이 약 17% 섞인 보통 주행에서 1시간 반쯤 타면 멀미가 나는 사람.
     * 이 값이면 그런 주행에서 1시간 15분~1시간 반 사이에 지수 70("멀미 주의"), 2시간 무렵 80 안팎이 된다.
     * 굽은 길만 계속 달리면 15분 안팎에 70을 넘는다.
     */
    const val BASE_THRESHOLD_MSDV = 33f
    /** 정차 중 누적량이 줄어드는 시간 상수 (ms). 정차 8분이면 약 63% 회복. */
    const val RECOVERY_TAU_MS = 8 * 60_000L
    /** 이 속도 미만이면 정차로 본다 (m/s). */
    const val STOP_SPEED_MPS = 0.5f

    // ---- 운전 부드러움 (계기판) ----
    /** 운전 느낌을 판단하는 최근 구간 길이 (ms). */
    const val FEEL_WINDOW_MS = 15_000L
    /** 최근 수평 가속도 RMS가 이 값 이상이면 "조금 거칠어요" (m/s²). */
    const val FEEL_ROUGH_RMS = 0.9f
    /** 이 값 이상이면 "너무 과격해요" (m/s²). */
    const val FEEL_HARSH_RMS = 1.6f
    /** 계기판 바늘의 최대 눈금 (m/s²). */
    const val FEEL_GAUGE_MAX_RMS = 2.5f

    // ---- 운전 코칭 ----
    /** 급가감속으로 볼 종방향 가속도 (m/s²). */
    const val HARSH_LONG_MPS2 = 2.0f
    /** 급회전으로 볼 횡방향 가속도 (m/s²). */
    const val HARSH_LAT_MPS2 = 2.5f
    /** 같은 이벤트로 셀 최소 간격 (ms). */
    const val HARSH_DEBOUNCE_MS = 3_000L
    /** 이 시간 안에 급이벤트가 [COACH_EVENTS_IN_WINDOW]번 나오면 코칭. */
    const val COACH_WINDOW_MS = 60_000L
    const val COACH_EVENTS_IN_WINDOW = 2
    /** 코칭 사이 최소 간격 (ms). 잔소리가 되지 않게. */
    const val COACH_COOLDOWN_MS = 5 * 60_000L
    /** 한 주행에서 코칭 최대 횟수. */
    const val COACH_MAX_PER_TRIP = 4

    // ---- 경로 예보 ----
    /** 예보 한 칸 길이 (m). */
    const val FORECAST_BUCKET_M = 2_000f
    /** 예보 범위 (m). */
    const val FORECAST_RANGE_M = 30_000f
    /** km당 가중 회전 수가 이 값 이상이면 보통. */
    const val DENSITY_MODERATE = 0.6f
    /** km당 가중 회전 수가 이 값 이상이면 위험. */
    const val DENSITY_HIGH = 1.6f
    /** 위험 구간에서 누적량이 쌓이는 속도 ((m/s²)², 즉 초당 dose 증가량). 휴식 제안 예측용. */
    const val HIGH_SEGMENT_DOSE_RATE = 0.65
    /** 위험 구간 예상 평균 속도 (m/s). 약 50km/h. */
    const val HIGH_SEGMENT_SPEED_MPS = 13.9f

    // ---- 휴식 제안 ----
    /** 위험 구간 끝에서 예상 지수가 이 값 이상이면 휴식 제안. */
    const val REST_PREDICTED_INDEX = 75
    /** 위험 구간이 이 거리 안에 있을 때만 제안 (m). 너무 가까우면 들를 곳이 없다. */
    const val REST_MIN_AHEAD_M = 3_000f
    const val REST_MAX_AHEAD_M = 15_000f

    // ---- 뒷좌석 큐 ----
    /** 회전 큐를 보여줄 거리 (m). */
    const val CUE_TURN_AHEAD_M = 300f
    /** 감속 큐를 보여줄 종방향 감속도 (m/s²). */
    const val CUE_SLOWING_MPS2 = -1.2f
    /** 아이 오디오 큐를 말할 위험 구간 직전 거리 (m). */
    const val CHILD_CUE_AHEAD_M = 1_500f

    // ---- 정차 피드백 ----
    /** 정차 후 이 시간이 지나면 아이 상태를 묻는다 (ms). */
    const val FEEDBACK_AFTER_STOP_MS = 20_000L

    // ---- 개인화 ----
    const val SENSITIVITY_MIN = 0.6f
    const val SENSITIVITY_MAX = 1.8f
}
