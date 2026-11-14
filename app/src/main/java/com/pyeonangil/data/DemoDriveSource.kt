package com.pyeonangil.data

import com.pyeonangil.engine.DemoTrip
import com.pyeonangil.engine.Gear
import com.pyeonangil.engine.MotionSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 시연 모드: [DemoTrip] 가상 주행을 [speedup]배 빠르게 재생한다.
 * Pleos 에뮬레이터는 내비 위치가 움직이지 않아 실주행을 재현할 수 없으므로 시연은 이 모드로 한다.
 * 엔진에는 가상 시각을 그대로 넘기므로, 빨리 재생해도 계산 결과는 실제 시간으로 달린 것과 같다.
 */
class DemoDriveSource(
    private val scope: CoroutineScope,
    private val speedup: Double = 15.0,
) : DriveSource {

    private val trip = DemoTrip()

    override val label = "시연 모드 ×${speedup.toInt()}"

    private val _samples = MutableSharedFlow<MotionSample>(extraBufferCapacity = 512)
    override val samples: SharedFlow<MotionSample> = _samples.asSharedFlow()

    private val _gear = MutableStateFlow(Gear.PARK)
    override val gear: StateFlow<Gear> = _gear.asStateFlow()

    private val _route = MutableStateFlow<RouteSnapshot?>(null)
    override val route: StateFlow<RouteSnapshot?> = _route.asStateFlow()

    private val _status = MutableStateFlow("서울 → 강원도 시댁 (가상 주행)")
    override val status: StateFlow<String> = _status.asStateFlow()

    /** 재생이 끝났는지. */
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    /** 지금 구간 이름 (도심, 고속도로, 휴게소, 산길 …). */
    private val _phase = MutableStateFlow("")
    val phase: StateFlow<String> = _phase.asStateFlow()

    private var job: Job? = null

    override fun start() {
        if (job != null) return
        _finished.value = false
        job = scope.launch {
            var virtualSec = 0.0
            var lastRouteSec = -10.0
            val tickMs = 50L
            while (isActive && virtualSec < trip.totalSec) {
                val until = virtualSec + tickMs / 1000.0 * speedup
                // 0.2초 간격 프레임을 빠짐없이 내보내야 엔진의 적분이 정확하다.
                while (virtualSec < until && virtualSec < trip.totalSec) {
                    val f = trip.frameAt(virtualSec)
                    if (_gear.value != f.gear) _gear.value = f.gear
                    if (virtualSec - lastRouteSec >= 5.0) {
                        _route.value = RouteSnapshot(trip.upcomingTurns(f.odometerM), trip.remainingM(f.odometerM))
                        lastRouteSec = virtualSec
                    }
                    _phase.value = f.phase
                    _samples.emit(f.sample)
                    virtualSec += 0.2
                }
                delay(tickMs)
            }
            _gear.value = Gear.PARK
            _finished.value = true
            job = null
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    override fun openRestAreaSearch(): Boolean {
        _status.value = "휴게소를 경유지로 추가했어요 (시연)"
        return true
    }
}
