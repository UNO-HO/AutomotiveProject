package com.pyeonangil.data

import com.pyeonangil.engine.Gear
import com.pyeonangil.engine.MotionSample
import com.pyeonangil.engine.UpcomingTurn
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 내비에서 받은 앞으로의 경로 정보. */
data class RouteSnapshot(
    val turns: List<UpcomingTurn>,
    /** 목적지까지 남은 거리 (m). 모르면 null. */
    val remainingM: Float?,
)

/** 엔진에 들어가는 차량·경로 신호의 출처. 실차(Pleos)와 시연(데모 재생) 두 가지가 있다. */
interface DriveSource {
    /** 화면에 보일 이름. */
    val label: String

    /** 약 10Hz 주행 측정값. */
    val samples: SharedFlow<MotionSample>
    val gear: StateFlow<Gear>
    val route: StateFlow<RouteSnapshot?>

    /** 연결 상태·안내 문구. */
    val status: StateFlow<String>

    fun start()
    fun stop()

    /** 내비에서 휴게소 검색 화면을 연다. 지원하지 않으면 false. */
    fun openRestAreaSearch(): Boolean
}
