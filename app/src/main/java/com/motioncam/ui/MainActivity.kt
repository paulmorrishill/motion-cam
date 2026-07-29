package com.motioncam.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.motioncam.service.CameraService

/**
 * Single activity: obtains permissions, starts + binds the camera service and
 * hosts the Compose UI. Window flags keep the screen awake and the app visible
 * over the lock screen so the phone can run unattended.
 */
class MainActivity : ComponentActivity() {

    private val serviceState = mutableStateOf<CameraService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? CameraService.LocalBinder
            serviceState.value = local?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceState.value = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = requiredPermissions().all { result[it] == true }
        if (granted) startAndBindService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContent {
            MotionCamTheme {
                MotionCamRoot(
                    serviceProvider = { serviceState.value },
                    activity = this
                )
            }
        }

        if (hasAllPermissions()) {
            startAndBindService()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
        // Auto-request battery-optimisation exemption in real use, but not under
        // instrumentation (the system screen would cover the app and break UI tests).
        if (!isRunningUnderTest()) requestIgnoreBatteryOptimizations()
    }

    /** True when an instrumentation test runner is on the classpath (androidTest only). */
    private fun isRunningUnderTest(): Boolean = try {
        Class.forName("androidx.test.espresso.Espresso")
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    override fun onStart() {
        super.onStart()
        if (hasAllPermissions()) {
            bindService(Intent(this, CameraService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            serviceState.value?.clearPreview()
            unbindService(connection)
        } catch (_: Exception) {
        }
    }

    private fun startAndBindService() {
        CameraService.start(this)
        bindService(Intent(this, CameraService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        return perms.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                @Suppress("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        } catch (_: Exception) {
        }
    }

    fun openBatteryOptSettings() = requestIgnoreBatteryOptimizations()
}
