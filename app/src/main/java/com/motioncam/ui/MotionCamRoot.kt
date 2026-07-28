package com.motioncam.ui

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motioncam.service.AppState
import com.motioncam.service.CameraService
import com.motioncam.service.KeepScreenMode
import com.motioncam.service.RecorderStateMachine
import com.motioncam.service.TorchMode
import kotlinx.coroutines.delay

private enum class Screen { MAIN, SETTINGS, UPLOADS }

@Composable
fun MotionCamRoot(serviceProvider: () -> CameraService?, activity: MainActivity) {
    val ui by AppState.state.collectAsState()
    val service = serviceProvider()
    var screen by remember { mutableStateOf(Screen.MAIN) }

    when (screen) {
        Screen.SETTINGS -> SettingsScreen(activity = activity, onBack = { screen = Screen.MAIN })
        Screen.UPLOADS -> UploadsScreen(ui = ui, onBack = { screen = Screen.MAIN })
        Screen.MAIN -> MainScreen(
            service = service,
            activity = activity,
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenUploads = { screen = Screen.UPLOADS }
        )
    }
}

@Composable
private fun MainScreen(
    service: CameraService?,
    activity: MainActivity,
    onOpenSettings: () -> Unit,
    onOpenUploads: () -> Unit
) {
    val ui by AppState.state.collectAsState()

    var keepMode by remember { mutableStateOf(KeepScreenMode.OFF) }
    var lastWake by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Keep the phone awake in every mode so it never locks; darkness is handled
    // by a black overlay to avoid burn-in.
    LaunchedEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    // Wake the display whenever motion begins.
    LaunchedEffect(ui.motionActive) {
        if (ui.motionActive) lastWake = System.currentTimeMillis()
    }

    val dark = when (keepMode) {
        KeepScreenMode.ALWAYS -> false
        KeepScreenMode.OFF -> true
        KeepScreenMode.ON_MOTION -> !ui.motionActive
        KeepScreenMode.TIMED -> (nowMs - lastWake) > 60_000L
    }

    // Dim the backlight when dark.
    LaunchedEffect(dark) {
        val lp = activity.window.attributes
        lp.screenBrightness = if (dark) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = lp
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            service = service,
            onTap = { x, y, w, h ->
                if (ui.focusLocked) service?.focusUnlock() else service?.focusTap(x, y, w, h)
            },
            onLongPress = { x, y, w, h -> service?.focusLock(x, y, w, h) },
            modifier = Modifier.fillMaxSize()
        )

        // Top status + warning banners.
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            StatusHeader(
                statusText = ui.statusText,
                recording = ui.isRecording,
                motion = ui.motionActive
            )
            if (ui.storageLow) Banner("Storage low (${ui.storageFreePercent}% free)", MaterialTheme.colorScheme.error)
            if (ui.batteryLow) Banner("Battery low (${ui.batteryPercent}%)", MaterialTheme.colorScheme.error)
            if (ui.cameraError != null) Banner("Camera: ${ui.cameraError}", MaterialTheme.colorScheme.error)
            if (ui.focusLocked) Banner("Focus locked — tap to unlock", MaterialTheme.colorScheme.secondary)
            if (ui.currentFileName != null) {
                Text(
                    text = "${ui.currentFileName}  ·  ${formatElapsed(ui.recordingElapsedMs)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Bottom control bar.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlButton(
                label = "Light: ${ui.torchMode}",
                active = ui.torchOn,
                onClick = { service?.cycleTorch() }
            )
            ControlButton(
                label = "Screen: ${keepMode.short()}",
                active = keepMode != KeepScreenMode.OFF,
                onClick = {
                    keepMode = keepMode.next()
                    lastWake = System.currentTimeMillis()
                    AppState.update { it.copy(keepScreenMode = keepMode) }
                }
            )
            if (ui.recorderState == RecorderStateMachine.State.DISARMED) {
                ControlButton(label = "Re-arm", active = false, onClick = { service?.rearm() })
            } else {
                ControlButton(label = "Stop", active = false, onClick = { service?.stopOrDisarm() })
            }
            ControlButton(label = "Uploads", active = false, onClick = onOpenUploads)
            ControlButton(label = "Settings", active = false, onClick = onOpenSettings)
        }

        // Black overlay when the screen is "asleep": tap to wake / show preview.
        if (dark) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable {
                        lastWake = System.currentTimeMillis()
                        if (keepMode == KeepScreenMode.OFF) keepMode = KeepScreenMode.TIMED
                    },
                contentAlignment = Alignment.TopStart
            ) {
                // Minimal recording indicator so the user can confirm activity without lighting the screen.
                if (ui.isRecording) {
                    Text(
                        "● REC",
                        color = Color(0x66FF5252),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(statusText: String, recording: Boolean, motion: Boolean) {
    val color = when {
        recording -> Color(0xFFFF5252)
        motion -> Color(0xFFFFC107)
        else -> Color(0xFF4FC3F7)
    }
    Surface(color = Color(0xAA000000)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(12.dp).height(12.dp).background(color))
            Spacer(Modifier.width(8.dp))
            Text(statusText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Surface(color = color, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(text, color = Color.White, modifier = Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ControlButton(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else Color(0xFF2A2A2A)
    Surface(color = bg, modifier = Modifier.clickable { onClick() }) {
        Text(
            label,
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
        )
    }
}

private fun KeepScreenMode.next(): KeepScreenMode = when (this) {
    KeepScreenMode.OFF -> KeepScreenMode.TIMED
    KeepScreenMode.TIMED -> KeepScreenMode.ON_MOTION
    KeepScreenMode.ON_MOTION -> KeepScreenMode.ALWAYS
    KeepScreenMode.ALWAYS -> KeepScreenMode.OFF
}

private fun KeepScreenMode.short(): String = when (this) {
    KeepScreenMode.OFF -> "Off"
    KeepScreenMode.TIMED -> "1min"
    KeepScreenMode.ON_MOTION -> "Motion"
    KeepScreenMode.ALWAYS -> "On"
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
