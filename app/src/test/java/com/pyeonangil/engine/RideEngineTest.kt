package com.pyeonangil.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RideEngineTest {

    private fun drive(
        engine: RideEngine,
        seconds: Int,
        startMs: Long = 0,
        speed: (Double) -> Double,
        yaw: (Double) -> Double = { 0.0 },
    ): List<RideEvent> {
        val events = ArrayList<RideEvent>()
        var t = 0.0
        while (t < seconds) {
            val ms = startMs + (t * 1000).toLong()
            events += engine.onSample(MotionSample(ms, speed(t).toFloat(), yaw(t).toFloat()))
            t += 0.2
        }
        return events
    }

    @Test
    fun steadyHighwayKeepsIndexLow() {
        val engine = RideEngine()
        drive(engine, 15 * 60, speed = { 27.0 })
        assertTrue("정속 주행은 지수가 낮아야 한다: ${engine.state.index}", engine.state.index < 10)
        assertEquals(RiskLevel.CALM, engine.state.level)
    }

    @Test
    fun curvyRoadRaisesIndexOverTime() {
        val engine = RideEngine()
        drive(engine, 5 * 60, speed = { 13.0 }, yaw = { t -> 0.12 * sin(2 * PI * t / 9.0) })
        val after5 = engine.state.index
        drive(engine, 10 * 60, startMs = 5 * 60_000L, speed = { 13.0 }, yaw = { t -> 0.12 * sin(2 * PI * t / 9.0) })
        val after15 = engine.state.index
        assertTrue("산길에서는 지수가 쌓여야 한다: $after5 → $after15", after15 > after5 && after15 >= 50)
    }

    @Test
    fun restingAtStopRecoversIndex() {
        val engine = RideEngine()
        drive(engine, 10 * 60, speed = { 13.0 }, yaw = { t -> 0.12 * sin(2 * PI * t / 9.0) })
        val beforeRest = engine.state.index
        drive(engine, 8 * 60, startMs = 10 * 60_000L, speed = { 0.0 })
        assertTrue("정차하면 회복돼야 한다: $beforeRest → ${engine.state.index}", engine.state.index < beforeRest * 0.7)
    }

    @Test
    fun moreSensitiveFamilyGetsHigherIndex() {
        val normal = RideEngine(FamilyProfile(sensitivity = 1.0f))
        val sensitive = RideEngine(FamilyProfile(sensitivity = 1.5f))
        val yaw = { t: Double -> 0.1 * sin(2 * PI * t / 9.0) }
        drive(normal, 8 * 60, speed = { 13.0 }, yaw = yaw)
        drive(sensitive, 8 * 60, speed = { 13.0 }, yaw = yaw)
        assertTrue(sensitive.state.index > normal.state.index)
    }

    @Test
    fun suggestsRestOnceBeforeCurvySection() {
        val engine = RideEngine()
        // 10km 앞부터 약 14km 동안 240m마다 회전.
        val turns = (0 until 60).map { i ->
            UpcomingTurn(10_000f + i * 240f, 1f, "TURN", if (i % 2 == 0) -1 else 1)
        }
        engine.onRoute(turns, 30_000f)
        val events = drive(engine, 60, speed = { 25.0 })
        val rest = events.filterIsInstance<RideEvent.SuggestRest>()
        assertEquals("휴식 제안은 한 번만", 1, rest.size)
        assertTrue(rest.first().suggestion.highSegmentStartsInM in 3_000f..15_000f)
        assertTrue(engine.state.activeSuggestion != null)
    }

    @Test
    fun childCueOnceWhenCurvySectionIsNear() {
        val engine = RideEngine(isEv = true)
        val turns = (0 until 20).map { i -> UpcomingTurn(1_000f + i * 240f, 1f, "TURN", 1) }
        engine.onRoute(turns, 10_000f)
        val events = drive(engine, 120, speed = { 13.0 })
        assertEquals(1, events.filterIsInstance<RideEvent.CurveAheadNotice>().size)
        assertEquals(1, events.filterIsInstance<RideEvent.SuggestLowerRegen>().size)
    }

    @Test
    fun harshBrakingRepeatedTriggersOneCoaching() {
        val engine = RideEngine()
        // 20초마다 3 m/s²로 2초간 급감속.
        val speed = { t: Double ->
            val x = t % 20.0
            if (x < 2.0) 20.0 - 3.0 * x else 20.0 - 6.0 + minOf(6.0, (x - 2.0) * 0.5)
        }
        val events = drive(engine, 120, speed = speed)
        val coaching = events.filterIsInstance<RideEvent.CoachDriver>()
        assertEquals("쿨다운 동안은 한 번만 말한다", 1, coaching.size)
        assertTrue(engine.state.harshEventCount >= 3)
    }

    @Test
    fun asksFeedbackOnceDuringLongStop() {
        val engine = RideEngine()
        drive(engine, 6 * 60, speed = { 15.0 })
        val events = drive(engine, 90, startMs = 6 * 60_000L, speed = { 0.0 })
        assertEquals(1, events.count { it == RideEvent.AskChildFeedback })
    }

    @Test
    fun cueShowsUpcomingTurnDirection() {
        val engine = RideEngine()
        engine.onRoute(listOf(UpcomingTurn(200f, 1f, "TURN_LEFT", -1)), 5_000f)
        engine.onSample(MotionSample(0, 10f))
        engine.onSample(MotionSample(200, 10f))
        assertEquals(MotionCue.TURN_LEFT_SOON, engine.state.cue)
    }

    @Test
    fun demoTripTellsTheWholeStory() {
        val trip = DemoTrip()
        val engine = RideEngine()
        val events = ArrayList<RideEvent>()
        var t = 0.0
        var lastRoute = -10.0
        while (t < trip.totalSec) {
            val f = trip.frameAt(t)
            engine.onGear(f.gear)
            if (t - lastRoute >= 5) {
                engine.onRoute(trip.upcomingTurns(f.odometerM), trip.remainingM(f.odometerM))
                lastRoute = t
            }
            events += engine.onSample(f.sample)
            t += 0.2
        }
        assertEquals(1, events.filterIsInstance<RideEvent.SuggestRest>().size)
        assertEquals(1, events.filterIsInstance<RideEvent.CurveAheadNotice>().size)
        assertTrue(events.any { it == RideEvent.AskChildFeedback })
        assertTrue(events.any { it is RideEvent.CoachDriver })
        val report = engine.report()
        assertTrue(report.peakIndex >= 70)
        assertEquals(3, report.worstMoments.size)
    }
}

class FamilyProfileTest {
    @Test
    fun hardAtLowIndexRaisesSensitivity() {
        val p = FamilyProfile().withFeedback(ChildFeedback.HARD, indexAtAsk = 30)
        assertTrue(p.sensitivity > 1.2f)
    }

    @Test
    fun fineAtHighIndexLowersSensitivity() {
        val p = FamilyProfile().withFeedback(ChildFeedback.FINE, indexAtAsk = 70)
        assertTrue(p.sensitivity < 1.0f)
    }

    @Test
    fun sensitivityStaysInRange() {
        var p = FamilyProfile()
        repeat(30) { p = p.withFeedback(ChildFeedback.HARD, 10) }
        assertEquals(Tuning.SENSITIVITY_MAX, p.sensitivity, 0.001f)
    }
}

class RouteForecasterTest {
    @Test
    fun denseTurnsAreHigh() {
        val turns = (0 until 10).map { UpcomingTurn(it * 200f, 1f, "TURN") }
        val f = RouteForecaster.forecast(turns, 10_000f)
        assertEquals(RiskLevel.HIGH, f.first().level)
        assertEquals(RiskLevel.CALM, f.last().level)
    }

    @Test
    fun forecastStopsAtDestination() {
        val f = RouteForecaster.forecast(emptyList(), 5_000f)
        assertEquals(5_000f, f.last().endM, 0.1f)
    }

    @Test
    fun turnWeightsFromTypeNames() {
        assertEquals(3f, TurnWeights.weightFor("U_TURN"), 0f)
        assertEquals(1f, TurnWeights.weightFor("TURN_LEFT"), 0f)
        assertEquals(-1, TurnWeights.directionFor("SHARP_LEFT"))
        assertEquals(0f, TurnWeights.weightFor("DESTINATION"), 0f)
    }
}

class DrivingFeelTest {
    private fun run(engine: RideEngine, seconds: Int, speed: (Double) -> Double, yaw: (Double) -> Double = { 0.0 }) {
        var t = 0.0
        while (t < seconds) {
            engine.onSample(MotionSample((t * 1000).toLong(), speed(t).toFloat(), yaw(t).toFloat()))
            t += 0.2
        }
    }

    @Test
    fun steadyDrivingIsSmooth() {
        val e = RideEngine()
        run(e, 60, speed = { 25.0 })
        assertEquals(DrivingFeel.SMOOTH, e.state.feel)
    }

    @Test
    fun repeatedHardBrakingIsHarsh() {
        val e = RideEngine()
        // 6초마다 3초간 ±2.5 m/s² 가감속 반복
        run(e, 60, speed = { t -> val x = t % 6.0; if (x < 3) 20.0 - 2.5 * x else 12.5 + 2.5 * (x - 3) })
        assertEquals(DrivingFeel.HARSH, e.state.feel)
    }

    @Test
    fun feelRecoversAfterSmoothStretch() {
        val e = RideEngine()
        var t = 0.0
        while (t < 30) { e.onSample(MotionSample((t * 1000).toLong(), (20 + 6 * kotlin.math.sin(t)).toFloat())); t += 0.2 }
        while (t < 60) { e.onSample(MotionSample((t * 1000).toLong(), 20f)); t += 0.2 }
        assertEquals(DrivingFeel.SMOOTH, e.state.feel)
    }
}

class ComfortCheckTest {
    private fun run(engine: RideEngine, seconds: Int, yaw: Double): List<RideEvent> {
        val out = ArrayList<RideEvent>()
        var t = 0.0
        while (t < seconds) {
            out += engine.onSample(MotionSample((t * 1000).toLong(), 13f, (yaw * kotlin.math.sin(2 * kotlin.math.PI * t / 9.0)).toFloat()))
            t += 0.2
        }
        return out
    }

    @Test
    fun calmDrivingHasNoCheckIns() {
        val events = run(RideEngine(), 40 * 60, yaw = 0.0)
        assertEquals(0, events.count { it is RideEvent.ComfortCheck })
    }

    @Test
    fun curvyRoadGetsSpacedRotatingCheckIns() {
        val events = run(RideEngine(), 45 * 60, yaw = 0.12).filterIsInstance<RideEvent.ComfortCheck>()
        assertTrue("안부가 있어야 한다", events.size >= 2)
        assertTrue("10분 간격이므로 많지 않다", events.size <= 4)
        assertEquals(Tuning.CHECKIN_MESSAGES[0], events[0].message)
        assertEquals(Tuning.CHECKIN_MESSAGES[1], events[1].message)
    }
}
