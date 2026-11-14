package com.pyeonangil.engine

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 시연용 가상 주행: 서울 → 강원도 시댁 (기획서 시나리오를 약 1시간으로 줄인 것).
 * Pleos 에뮬레이터는 내비 위치가 움직이지 않으므로, 이 데이터를 실제 시간보다 빠르게 재생해 시연한다.
 * 같은 입력이면 항상 같은 결과가 나오도록 난수를 쓰지 않는다.
 *
 * 구간: 도심 정체 → 고속도로(중간 정체) → 휴게소 정차 → 국도 산길 → 도착
 */
class DemoTrip {

    data class Frame(
        val virtualSec: Double,
        val sample: MotionSample,
        val gear: Gear,
        val phase: String,
        val odometerM: Double,
    )

    private data class Phase(
        val name: String,
        val durationSec: Double,
        val gear: Gear,
        /** 구간 안 경과 시간(s) → 목표 속도(m/s). */
        val speed: (Double) -> Double,
        /** 구간 안 경과 시간(s) → 요레이트(rad/s). */
        val yaw: (Double) -> Double = { 0.0 },
    )

    private val phases = listOf(
        // 0~6분: 도심. 신호와 정체로 가다 서기를 반복.
        Phase("도심", 6 * 60.0, Gear.DRIVE, speed = { t -> stopGo(t, period = 45.0, top = 11.0) },
            yaw = { t -> 0.05 * sin(2 * PI * t / 70.0) }),
        // 6~16분: 고속도로 정상 주행.
        Phase("고속도로", 10 * 60.0, Gear.DRIVE, speed = { t -> min(27.0, 11.0 + t * 0.3) }),
        // 16~24분: 고속도로 정체. 가다 서기.
        Phase("고속도로 정체", 8 * 60.0, Gear.DRIVE, speed = { t -> if (t < 40) 27.0 - 18.0 * t / 40 else stopGo(t, period = 38.0, top = 9.0) }),
        // 24~29분: 정체가 풀리고 휴게소로.
        Phase("고속도로", 5 * 60.0, Gear.DRIVE, speed = { t -> if (t < 240) min(25.0, 8.0 + t * 0.4) else max(0.0, 25.0 - (t - 240) * 0.45) }),
        // 29~37분: 휴게소 정차 (기어 P).
        Phase("휴게소", 8 * 60.0, Gear.PARK, speed = { 0.0 }),
        // 37~39분: 휴게소에서 국도로.
        Phase("국도", 2 * 60.0, Gear.DRIVE, speed = { t -> min(18.0, t * 0.6) }),
        // 39~57분: 산길. 좌우 커브가 이어지고 가끔 급한 커브.
        Phase("산길", 18 * 60.0, Gear.DRIVE,
            speed = { t -> 13.0 + 2.5 * sin(2 * PI * t / 50.0) },
            yaw = { t -> 0.11 * sin(2 * PI * t / 9.0) + if ((t % 150.0) in 60.0..63.0 || (t % 150.0) in 80.0..83.0) 0.12 else 0.0 }),
        // 57~59분: 마을길로 내려와 도착.
        Phase("도착", 2 * 60.0, Gear.DRIVE, speed = { t -> max(0.0, 9.0 - t * 0.08) }),
    )

    val totalSec: Double = phases.sumOf { it.durationSec }

    /** 산길 시작 지점 (누적 m). 회전 안내를 만드는 데 쓴다. */
    val mountainStartM: Double
    val mountainEndM: Double
    val tripLengthM: Double

    /** 회전 지점 (누적 m, 종류 이름). */
    val turnPoints: List<Pair<Double, String>>

    private val frames: List<Frame>

    init {
        val out = ArrayList<Frame>()
        val dt = 0.2
        var odo = 0.0
        var t0 = 0.0
        var v = 0.0
        var mStart = 0.0
        var mEnd = 0.0
        for (p in phases) {
            if (p.name == "산길") mStart = odo
            var t = 0.0
            while (t < p.durationSec) {
                val target = p.speed(t)
                // 실제 차처럼 목표 속도를 천천히 따라간다 (최대 ±2.5 m/s²).
                v += (target - v).coerceIn(-2.5 * dt, 2.5 * dt)
                v = max(0.0, v)
                odo += v * dt
                val yaw = if (v > 0.5) p.yaw(t) else 0.0
                val ms = ((t0 + t) * 1000).toLong()
                out += Frame(t0 + t, MotionSample(ms, v.toFloat(), yaw.toFloat()), p.gear, p.name, odo)
                t += dt
            }
            if (p.name == "산길") mEnd = odo
            t0 += p.durationSec
        }
        frames = out
        mountainStartM = mStart
        mountainEndM = mEnd
        tripLengthM = odo

        val turnsOut = ArrayList<Pair<Double, String>>()
        // 도심 회전 몇 개, 고속도로 진출입.
        listOf(400.0 to "TURN_RIGHT", 1500.0 to "TURN_LEFT", 2600.0 to "TURN_RIGHT", 3200.0 to "ENTER_HIGHWAY")
            .forEach { turnsOut += it }
        // 휴게소 진입 직전, 고속도로 나들목.
        turnsOut += (mStart - 3_000.0) to "EXIT_RIGHT"
        turnsOut += (mStart - 800.0) to "TURN_LEFT"
        // 산길: 약 240m마다 좌우 교대 커브, 다섯 번에 한 번은 급커브.
        var d = mStart + 150.0
        var i = 0
        while (d < mEnd) {
            val side = if (i % 2 == 0) "LEFT" else "RIGHT"
            turnsOut += d to (if (i % 5 == 4) "SHARP_$side" else "TURN_$side")
            d += 240.0
            i++
        }
        turnsOut += (odo - 50.0) to "DESTINATION"
        turnPoints = turnsOut
    }

    /** 가상 시각(s)에 해당하는 프레임. */
    fun frameAt(virtualSec: Double): Frame {
        val idx = (virtualSec / 0.2).toInt().coerceIn(0, frames.size - 1)
        return frames[idx]
    }

    /** 현재 누적 주행거리 기준 앞으로의 회전 목록. */
    fun upcomingTurns(odometerM: Double): List<UpcomingTurn> =
        turnPoints.filter { it.first >= odometerM }.map { (pos, type) ->
            UpcomingTurn(
                distanceAheadM = (pos - odometerM).toFloat(),
                weight = TurnWeights.weightFor(type),
                label = type,
                direction = TurnWeights.directionFor(type),
            )
        }

    fun remainingM(odometerM: Double): Float = max(0.0, tripLengthM - odometerM).toFloat()

    private companion object {
        /** 가다 서기: 한 주기 안에서 가속 → 정속 → 감속 → 정지. */
        fun stopGo(t: Double, period: Double, top: Double): Double {
            val x = (t % period) / period
            return when {
                x < 0.25 -> top * (x / 0.25)
                x < 0.55 -> top
                x < 0.8 -> top * (1 - (x - 0.55) / 0.25)
                else -> 0.0
            }
        }
    }
}
