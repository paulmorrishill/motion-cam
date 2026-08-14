package com.motioncam.ui

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.motioncam.service.AppState
import com.motioncam.service.CameraService
import com.motioncam.service.KeepScreenMode
import com.motioncam.service.RecorderStateMachine
import com.motioncam.service.TorchMode
import com.motioncam.settings.Settings
import com.motioncam.settings.SettingsCodec
import com.motioncam.settings.SettingsStore
import kotlinx.coroutines.delay

private enum class Screen { MAIN, SETTINGS, UPLOADS, SCAN }

@Composable
fun MotionCamRoot(serviceProvider: () -> CameraService?, activity: MainActivity) {
    val ui by AppState.state.collectAsState()
    val service = serviceProvider()
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    var screen by remember { mutableStateOf(Screen.MAIN) }

    // Settings values seeded from a just-scanned config QR (unsaved, editable). Null
    // means the Settings screen shows the persisted values.
    var scannedDraft by remember { mutableStateOf<Settings?>(null) }
    // Last rejected payload, so a non-config QR held in frame doesn't spam identical
    // toasts as it re-decodes every interval.
    var lastRejected by remember { mutableStateOf<String?>(null) }

    // A successful QR decode arrives via AppState.scannedConfig. Parse it: a valid
    // MotionCam payload seeds the Settings screen for review; anything else (a random
    // QR) is rejected and scanning resumes.
    LaunchedEffect(ui.scannedConfig) {
        val payload = ui.scannedConfig ?: return@LaunchedEffect
        try {
            scannedDraft = SettingsCodec.decode(payload, store.current)
            lastRejected = null
            service?.consumeScannedConfig()
            screen = Screen.SETTINGS
        } catch (e: IllegalArgumentException) {
            if (payload != lastRejected) {
                lastRejected = payload
                Toast.makeText(context, "Not a MotionCam config QR", Toast.LENGTH_SHORT).show()
            }
            service?.beginConfigScan() // clears the bad payload and keeps scanning
        }
    }

    // Keep-screen state lives at the root so it survives navigating to Settings/
    // Uploads and back (otherwise the user's screen choice would reset each time).
    // Default ALWAYS: preview visible at normal brightness; the user opts into the
    // dimmed/black burn-in modes explicitly.
    var keepMode by remember { mutableStateOf(KeepScreenMode.ALWAYS) }
    var lastWake by remember { mutableLongStateOf(System.currentTimeMillis()) }

    Box(Modifier.fillMaxSize()) {
        // The preview screen is ALWAYS mounted so its SurfaceView is never
        // destroyed on navigation (preview returns instantly, and a recording in
        // progress is not interrupted). Settings/Uploads render as opaque overlays
        // on top, covering the SurfaceView.
        MainScreen(
            service = service,
            activity = activity,
            keepMode = keepMode,
            lastWake = lastWake,
            setKeepMode = {
                keepMode = it
                AppState.update { s -> s.copy(keepScreenMode = it) }
            },
            setLastWake = { lastWake = it },
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenUploads = { screen = Screen.UPLOADS }
        )
        when (screen) {
            Screen.SETTINGS -> Surface(
                Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                SettingsScreen(
                    activity = activity,
                    initialOverride = scannedDraft,
                    onScan = {
                        service?.beginConfigScan()
                        screen = Screen.SCAN
                    },
                    onBack = {
                        scannedDraft = null
                        screen = Screen.MAIN
                    }
                )
            }
            Screen.UPLOADS -> Surface(
                Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                UploadsScreen(
                    ui = ui,
                    onBack = { screen = Screen.MAIN },
                    onTestFtp = { service?.testFtp() },
                    onForceUpload = { service?.triggerUploads() }
                )
            }
            // Transparent overlay over the live preview so the user can aim at the QR.
            Screen.SCAN -> ScanOverlay(onCancel = {
                service?.cancelConfigScan()
                screen = Screen.SETTINGS
            })
            Screen.MAIN -> Unit
        }
    }
}

@Composable
private fun ScanOverlay(onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag("scan_overlay")
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center
    ) {
        // Always-visible close (✕) pinned top-right, so the scanner can be dismissed
        // from anywhere on the screen even if the centred controls are off-view.
        Surface(
            color = Color(0xCC000000),
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(12.dp)
                .testTag("scan_close")
                .clickable { onCancel() }
        ) {
            Text(
                "✕",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Point the camera at the config QR code",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
            // Framing reticle.
            Box(
                Modifier
                    .width(220.dp)
                    .height(220.dp)
                    .background(Color(0x22FFFFFF))
            )
            Text(
                "Generate the code from tools/qr-config.html",
                color = Color(0xCCFFFFFF),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Button(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun MainScreen(
    service: CameraService?,
    activity: MainActivity,
    keepMode: KeepScreenMode,
    lastWake: Long,
    setKeepMode: (KeepScreenMode) -> Unit,
    setLastWake: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUploads: () -> Unit
) {
    val ui by AppState.state.collectAsState()

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
        if (ui.motionActive) setLastWake(System.currentTimeMillis())
    }

    val dark = when (keepMode) {
        KeepScreenMode.ALWAYS -> false
        KeepScreenMode.OFF -> true
        KeepScreenMode.ON_MOTION -> !ui.motionActive
        KeepScreenMode.TIMED -> (nowMs - lastWake) > 60_000L
    }

    // Burn-in protection is a black overlay (below); we deliberately do NOT force
    // the backlight to ~0, because some devices treat that as screen-off and then
    // touches can't wake it (it gets stuck off). The overlay shows solid black and
    // any touch wakes it instantly.

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            service = service,
            onTap = { x, y, w, h ->
                if (ui.focusLocked) service?.focusUnlock() else service?.focusTap(x, y, w, h)
            },
            onLongPress = { x, y, w, h -> service?.focusLock(x, y, w, h) },
            onZoom = { factor -> service?.zoomBy(factor) },
            modifier = Modifier.fillMaxSize()
        )

        // Red border: this clip has passed 75% of the no-motion window without enough
        // cumulative motion, so it is on track to be discarded (not saved) at finalise.
        // Purely decorative overlay — no pointerInput, so it never intercepts taps.
        if (ui.willDiscard) {
            Box(Modifier.fillMaxSize().border(6.dp, Color.Red))
        }

        // Top status + warning banners.
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(12.dp)
        ) {
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
                .background(Color(0xCC000000))
                .windowInsetsPadding(WindowInsets.navigationBars)
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
                onClick = { setKeepMode(keepMode.next()) }
            )
            if (ui.lensCount > 1) {
                ControlButton(
                    label = "Lens: ${ui.lensLabel}",
                    active = ui.lensLabel != "Main",
                    onClick = { service?.cycleLens() }
                )
            }
            if (ui.maxZoom > 1f) {
                ControlButton(
                    label = "Zoom ${"%.1f".format(ui.zoomRatio)}x",
                    active = ui.zoomRatio > 1f,
                    onClick = { service?.resetZoom() }
                )
            }
            if (ui.recorderState == RecorderStateMachine.State.DISARMED) {
                ControlButton(label = "Re-arm", active = false, onClick = { service?.rearm() })
            } else {
                ControlButton(label = "Stop", active = false, onClick = { service?.stopOrDisarm() })
            }
            ControlButton(label = "Uploads", active = false, onClick = onOpenUploads)
            ControlButton(label = "Settings", active = false, onClick = onOpenSettings)
        }

        // Black overlay when the screen is "asleep": ANY touch wakes it instantly
        // and keeps it on for the timed window, so it can never get stuck off.
        if (dark) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("sleep_overlay")
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            setLastWake(System.currentTimeMillis())
                            setKeepMode(KeepScreenMode.TIMED)
                        }
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
