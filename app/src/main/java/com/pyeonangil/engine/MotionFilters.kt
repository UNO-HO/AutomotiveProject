package com.pyeonangil.engine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/** 속도·요레이트로 종방향/횡방향 가속도를 구한다. */
class KinematicsEstimator(
    /** 종방향 가속도 평활 시간 상수 (s). 속도 신호의 계단·잡음을 누른다. */
    private val smoothingTauSec: Float = 0.6f,
    /** 이보다 샘플 간격이 벌어지면 끊긴 것으로 보고 새로 시작한다 (ms). */
    private val maxGapMs: Long = 5_000L,
) {
    data class Kinematics(val dtSec: Float, val longMps2: Float, val latMps2: Float)

    private var prev: MotionSample? = null
    private var longSmoothed = 0f

    fun update(s: MotionSample): Kinematics? {
        val p = prev
        prev = s
        if (p == null) return null
        val dtMs = s.timeMs - p.timeMs
        if (dtMs <= 0 || dtMs > maxGapMs) {
            longSmoothed = 0f
            return null
        }
        val dt = dtMs / 1000f
        val rawLong = (s.speedMps - p.speedMps) / dt
        val alpha = dt / (smoothingTauSec + dt)
        longSmoothed += alpha * (rawLong - longSmoothed)
        val lat = s.speedMps * (s.yawRateRadPerSec ?: 0f)
        return Kinematics(dt, longSmoothed, lat)
    }

    fun reset() {
        prev = null
        longSmoothed = 0f
    }
}

/** 1차 고역 + 1차 저역 통과를 이은 대역 통과 필터. 샘플 간격이 일정하지 않아도 된다. */
class BandPass(lowHz: Float, highHz: Float) {
    private val rcHigh = (1.0 / (2 * PI * lowHz)).toFloat()
    private val rcLow = (1.0 / (2 * PI * highHz)).toFloat()
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private var lpOut = 0f
    private var primed = false

    fun filter(x: Float, dt: Float): Float {
        if (!primed) {
            hpPrevIn = x
            primed = true
        }
        val a = rcHigh / (rcHigh + dt)
        val hp = a * (hpPrevOut + x - hpPrevIn)
        hpPrevIn = x
        hpPrevOut = hp
        val b = dt / (rcLow + dt)
        lpOut += b * (hp - lpOut)
        return lpOut
    }
}

/**
 * 멀미 누적량. ISO 2631-1의 멀미 누적량(MSDV = √∫a²dt) 개념을 수평(종·횡) 가속도에 적용했다.
 * 멀미는 순간이 아니라 누적으로 오고, 정차해 쉬면 서서히 회복된다.
 */
class SicknessDose(
    private val bandLowHz: Float = Tuning.BAND_LOW_HZ,
    private val bandHighHz: Float = Tuning.BAND_HIGH_HZ,
    private val recoveryTauMs: Long = Tuning.RECOVERY_TAU_MS,
) {
    private val longFilter = BandPass(bandLowHz, bandHighHz)
    private val latFilter = BandPass(bandLowHz, bandHighHz)

    /** ∫a²dt, 단위 (m/s²)²·s. */
    var dose = 0.0
        private set

    val msdv: Float get() = sqrt(dose).toFloat()

    /** 주행 중 가속도를 누적한다. */
    fun accumulate(k: KinematicsEstimator.Kinematics) {
        val l = longFilter.filter(k.longMps2, k.dtSec)
        val t = latFilter.filter(k.latMps2, k.dtSec)
        dose += (l * l + t * t).toDouble() * k.dtSec
    }

    /** 정차 중 회복. */
    fun recover(dtMs: Long) {
        if (dtMs <= 0) return
        dose *= exp(-dtMs.toDouble() / recoveryTauMs)
    }

    /** 가족 민감도를 반영한 지수 (0~100에 맞춰 자르지 않은 값). */
    fun rawIndex(sensitivity: Float): Float = indexFor(dose, sensitivity)

    companion object {
        fun indexFor(dose: Double, sensitivity: Float): Float {
            val threshold = Tuning.BASE_THRESHOLD_MSDV / sensitivity
            return 100f * sqrt(dose).toFloat() / threshold
        }
    }
}
