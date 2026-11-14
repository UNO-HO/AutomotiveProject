package com.pyeonangil

import android.car.Car
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyeonangil.ui.MainScreen
import com.pyeonangil.ui.PyeonanTheme
import com.pyeonangil.ui.RideViewModel

class MainActivity : ComponentActivity() {

    private val vm: RideViewModel by viewModels()

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* 거부돼도 시연 모드는 동작 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCarPermissions()
        setContent {
            PyeonanTheme {
                val ui by vm.ui.collectAsStateWithLifecycle()
                MainScreen(
                    ui = ui,
                    onStart = vm::start,
                    onEnd = vm::endTrip,
                    onMode = vm::setMode,
                    onAcceptRest = vm::acceptRest,
                    onDismissRest = vm::dismissRest,
                    onFeedback = vm::feedback,
                    onCloseReport = vm::closeReport,
                )
            }
        }
    }

    /** 차량 속도는 런타임 권한(dangerous)이라 실행 중에 요청한다. */
    private fun requestCarPermissions() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        val needed = listOf(Car.PERMISSION_SPEED, Car.PERMISSION_POWERTRAIN)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionRequest.launch(needed.toTypedArray())
    }
}
