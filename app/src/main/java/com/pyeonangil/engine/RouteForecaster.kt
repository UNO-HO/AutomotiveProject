package com.pyeonangil.engine

import kotlin.math.min

/**
 * 앞으로 남은 회전 안내(TBT)로 경로를 일정 거리 단위로 나눠 멀미 위험을 예보한다.
 * Pleos NaviHelper는 경로 좌표열을 주지 않으므로, 회전 지점의 밀도를 커브·꺾임의 대리 지표로 쓴다.
 */
object RouteForecaster {

    fun forecast(
        turns: List<UpcomingTurn>,
        remainingRouteM: Float?,
        bucketM: Float = Tuning.FORECAST_BUCKET_M,
        rangeM: Float = Tuning.FORECAST_RANGE_M,
    ): List<ForecastSegment> {
        val end = min(rangeM, remainingRouteM ?: rangeM)
        if (end <= 0f) return emptyList()
        val segments = ArrayList<ForecastSegment>()
        var start = 0f
        while (start < end) {
            val stop = min(start + bucketM, end)
            val km = (stop - start) / 1000f
            val weight = turns
                .filter { it.distanceAheadM >= start && it.distanceAheadM < stop }
                .sumOf { it.weight.toDouble() }
                .toFloat()
            // 마지막 칸이 짧으면 밀도가 과장되므로 최소 0.5km로 나눈다.
            val density = weight / maxOf(km, 0.5f)
            segments += ForecastSegment(start, stop, levelFor(density), density)
            start = stop
        }
        return segments
    }

    fun levelFor(density: Float): RiskLevel = when {
        density >= Tuning.DENSITY_HIGH -> RiskLevel.HIGH
        density >= Tuning.DENSITY_MODERATE -> RiskLevel.MODERATE
        else -> RiskLevel.CALM
    }

    /** 이어진 위험 칸들을 하나의 구간으로 묶는다. (시작 m, 길이 m) */
    fun highRuns(segments: List<ForecastSegment>): List<Pair<Float, Float>> {
        val runs = ArrayList<Pair<Float, Float>>()
        var runStart: Float? = null
        var runEnd = 0f
        for (s in segments) {
            if (s.level == RiskLevel.HIGH) {
                if (runStart == null) runStart = s.startM
                runEnd = s.endM
            } else if (runStart != null) {
                runs += runStart to (runEnd - runStart)
                runStart = null
            }
        }
        if (runStart != null) runs += runStart to (runEnd - runStart)
        return runs
    }
}

/** 회전 안내 종류 이름을 몸이 느끼는 부담 가중치로 바꾼다. SDK 버전마다 enum 이름이 달라질 수 있어 문자열로 판단한다. */
object TurnWeights {
    fun weightFor(typeName: String): Float {
        val t = typeName.uppercase()
        return when {
            "U_TURN" in t || "UTURN" in t -> 3.0f
            "ROUNDABOUT" in t || "ROTARY" in t -> 2.0f
            "SHARP" in t -> 2.0f
            "TURN" in t -> 1.0f
            "KEEP" in t || "SLIGHT" in t || "BEAR" in t -> 0.5f
            "EXIT" in t || "RAMP" in t || "MERGE" in t -> 0.5f
            "STRAIGHT" in t -> 0.2f
            "DESTINATION" in t || "ARRIVE" in t || "WAYPOINT" in t -> 0f
            else -> 0.5f
        }
    }

    fun directionFor(typeName: String): Int {
        val t = typeName.uppercase()
        return when {
            "LEFT" in t -> -1
            "RIGHT" in t -> 1
            else -> 0
        }
    }
}
