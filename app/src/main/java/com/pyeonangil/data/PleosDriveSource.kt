package com.pyeonangil.data

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.pyeonangil.engine.Gear
import com.pyeonangil.engine.MotionSample
import com.pyeonangil.engine.TurnWeights
import com.pyeonangil.engine.UpcomingTurn
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
import kotlin.math.sqrt

/**
 * 실차 모드. Pleos "표준 우선" 정책에 따라
 * - 속도·기어: AAOS 표준 Car API (PERF_VEHICLE_SPEED, GEAR_SELECTION)
 * - 회전율(요레이트): 헤드유닛 자이로를 중력 방향으로 투영 (장착 각도와 무관)
 * - 앞으로의 경로: Pleos NaviHelper SDK 3.0.5 (getTBTInfo, getDestinationInfo)
 *
 * NaviHelper 사용법은 공식 문서와, 같은 SDK로 에뮬레이터 동작을 확인한 공개 출품작 코드를 근거로 했다.
 * NaviHelper는 경로 좌표열을 주지 않으므로 TBT(회전 안내) 목록을 경로 예보에 쓴다.
 */
class PleosDriveSource(
    context: Context,
    private val scope: CoroutineScope,
) : DriveSource {

    override val label = "실차 연동"

    private val appContext = context.applicationContext

    private val _samples = MutableSharedFlow<MotionSample>(extraBufferCapacity = 256)
    override val samples: SharedFlow<MotionSample> = _samples.asSharedFlow()

    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    override val gear: StateFlow<Gear> = _gear.asStateFlow()

    private val _route = MutableStateFlow<RouteSnapshot?>(null)
    override val route: StateFlow<RouteSnapshot?> = _route.asStateFlow()

    private val _status = MutableStateFlow("차량에 연결하는 중")
    override val status: StateFlow<String> = _status.asStateFlow()

    /** 내비 연결 성공 여부. 실패하면 앱이 시연 모드를 권한다. */
    private val _naviConnected = MutableStateFlow(false)
    val naviConnected: StateFlow<Boolean> = _naviConnected.asStateFlow()

    // ---- 최신 값 (콜백 스레드에서 쓰고 틱 코루틴에서 읽는다) ----
    @Volatile private var speedMps = 0f
    @Volatile private var yawRate: Float? = null
    @Volatile private var remainingM: Float? = null
    private val gravity = FloatArray(3)
    @Volatile private var hasGravity = false

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var naviHelper: NaviHelper? = null
    private var tickJob: Job? = null
    private var routeJob: Job? = null
    @Volatile private var routeDriving = false

    private val sensorManager = appContext.getSystemService(SensorManager::class.java)

    // ---------------- AAOS Car API ----------------

    private val carCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> speedMps = (value.value as? Float) ?: speedMps
                VehiclePropertyIds.GEAR_SELECTION -> _gear.value = mapGear(value.value as? Int)
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "Car property error ${VehiclePropertyIds.toString(propertyId)}")
        }
    }

    private fun mapGear(raw: Int?): Gear {
        val g = raw ?: return Gear.UNKNOWN
        return when {
            g == VehicleGear.GEAR_PARK -> Gear.PARK
            g == VehicleGear.GEAR_REVERSE -> Gear.REVERSE
            g == VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            g >= VehicleGear.GEAR_DRIVE -> Gear.DRIVE // GEAR_DRIVE(8) 이상은 D 또는 수동 기어 단수
            else -> Gear.UNKNOWN
        }
    }

    private fun connectCar() {
        car = Car.createCar(appContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            if (!ready) {
                propertyManager = null
                _status.value = "차량 서비스 연결이 끊겼어요"
                return@createCar
            }
            val mgr = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = mgr
            runCatching {
                mgr.registerCallback(carCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
            }.onFailure { Log.e(TAG, "speed subscribe failed (CAR_SPEED 권한 확인)", it) }
            runCatching {
                mgr.registerCallback(carCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            }.onFailure { Log.e(TAG, "gear subscribe failed", it) }
        }
    }

    // ---------------- 자이로 (요레이트) ----------------

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GRAVITY -> {
                    e.values.copyInto(gravity, 0, 0, 3)
                    hasGravity = true
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // 차량의 요(yaw)는 중력 축을 도는 회전이다. 헤드유닛이 어떻게 기울어 있든 중력 방향으로 투영하면 된다.
                    yawRate = if (hasGravity) {
                        val g = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
                        if (g > 1f) (e.values[0] * gravity[0] + e.values[1] * gravity[1] + e.values[2] * gravity[2]) / g else null
                    } else {
                        e.values[1] // 중력 센서가 없으면 화면이 세워져 있다고 보고 기기 Y축 회전을 쓴다.
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun startSensors() {
        val sm = sensorManager ?: return
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro == null) {
            Log.w(TAG, "자이로 없음: 횡방향 가속도 없이 종방향만 계산")
        } else {
            sm.registerListener(sensorListener, gyro, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    // ---------------- Pleos NaviHelper ----------------

    private val naviListener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            routeDriving = status == NavigationStatus.ROUTE_DRIVE
            if (!routeDriving) _route.value = null
            _status.value = if (routeDriving) "길안내 중 · 경로 예보 켜짐" else "목적지를 정하면 경로 예보가 켜져요"
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            Log.d(TAG, "onTBTInfo ${tbtInfoList.take(5)} (총 ${tbtInfoList.size})")
            _route.value = RouteSnapshot(TbtMapper.toTurns(tbtInfoList.map { it.type.toString() to (it.distance as Number).toFloat() }), remainingM)
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            remainingM = (destinationInfo.remainDistance as Number).toFloat()
        }

        override fun onRouteCancelled() {
            routeDriving = false
            _route.value = null
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            routeDriving = false
            _route.value = null
            _status.value = "도착했어요"
        }
    }

    private fun connectNavi() {
        val helper = runCatching { NaviHelper(appContext) }.getOrElse {
            Log.w(TAG, "NaviHelper 생성 실패", it)
            _status.value = "내비 연결 실패 · 실시간 지수만 동작"
            return
        }
        naviHelper = helper
        runCatching {
            helper.initialize(
                onConnected = {
                    if (naviHelper === helper) {
                        helper.addListener(naviListener)
                        _naviConnected.value = true
                        _status.value = "목적지를 정하면 경로 예보가 켜져요"
                        runCatching { helper.getRouteStateInfo(); helper.getDestinationInfo() }
                    }
                },
                onFailed = { t ->
                    Log.w(TAG, "NaviHelper 연결 실패", t)
                    _naviConnected.value = false
                    _status.value = "내비 연결 실패 · 실시간 지수만 동작"
                },
            )
        }.onFailure {
            Log.w(TAG, "NaviHelper initialize 예외", it)
            _status.value = "내비 연결 실패 · 실시간 지수만 동작"
        }
    }

    // ---------------- 생명주기 ----------------

    override fun start() {
        if (tickJob != null) return
        connectCar()
        startSensors()
        connectNavi()
        // 속도는 값이 바뀔 때만 오므로, 10Hz로 최신 값을 묶어 엔진에 보낸다.
        tickJob = scope.launch {
            while (isActive) {
                _samples.emit(MotionSample(SystemClock.elapsedRealtime(), speedMps, yawRate))
                delay(100)
            }
        }
        // 길안내 중이면 5초마다 회전 안내·남은 거리를 새로 받는다.
        routeJob = scope.launch {
            while (isActive) {
                val h = naviHelper
                if (h != null && _naviConnected.value && routeDriving) {
                    runCatching { h.getTBTInfo(); h.getDestinationInfo() }
                        .onFailure { Log.w(TAG, "TBT 요청 실패", it) }
                }
                delay(5_000)
            }
        }
    }

    override fun stop() {
        tickJob?.cancel(); tickJob = null
        routeJob?.cancel(); routeJob = null
        sensorManager?.unregisterListener(sensorListener)
        runCatching { propertyManager?.unregisterCallback(carCallback) }
        propertyManager = null
        car?.disconnect(); car = null
        naviHelper?.let { h ->
            runCatching { h.removeListener(naviListener) }
            runCatching { h.release() }
        }
        naviHelper = null
        _naviConnected.value = false
    }

    override fun openRestAreaSearch(): Boolean {
        val h = naviHelper ?: return false
        if (!_naviConnected.value) return false
        return runCatching { h.requestNavigationScreen(NavigationScreenRequest.SearchPlace("휴게소")) }
            .onFailure { Log.w(TAG, "휴게소 검색 화면 요청 실패", it) }
            .isSuccess
    }

    private companion object {
        const val TAG = "PyeonanPleos"
    }
}

/**
 * TBT 목록을 엔진 입력으로 바꾼다. 실차에서 확인이 필요한 두 가지를 여기서만 다룬다.
 * 1) 거리 단위: 미터로 가정. 2) 거리 기준: 현재 위치에서 그 지점까지(누적)로 가정.
 * 에뮬레이터 로그(태그 PyeonanPleos, onTBTInfo)로 확인한 뒤 아래 두 상수만 바꾸면 된다.
 */
object TbtMapper {
    /** TBT distance 값 × 이 값 = 미터. km 단위라면 1000f. */
    var distanceToMeters = 1f

    /** true면 distance가 현재 위치 기준 거리, false면 직전 회전 지점부터의 구간 길이. */
    var distanceIsCumulative = true

    fun toTurns(raw: List<Pair<String, Float>>): List<UpcomingTurn> {
        var acc = 0f
        return raw.map { (type, distance) ->
            val d = distance * distanceToMeters
            val ahead = if (distanceIsCumulative) d else { acc += d; acc }
            UpcomingTurn(
                distanceAheadM = ahead,
                weight = TurnWeights.weightFor(type),
                label = type,
                direction = TurnWeights.directionFor(type),
            )
        }
    }
}
