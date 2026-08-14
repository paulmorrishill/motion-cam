package com.motioncam.service

import com.motioncam.upload.RecentFile
import com.motioncam.upload.UploadItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TorchMode { OFF, AUTO, ON }

enum class KeepScreenMode { OFF, TIMED, ON_MOTION, ALWAYS }

/** Everything the UI needs to render, as one immutable snapshot. */
data class UiState(
    val recorderState: RecorderStateMachine.State = RecorderStateMachine.State.ARMED,
    val motionActive: Boolean = false,
    val graceRemainingSec: Int = 0,
    val currentFileName: String? = null,
    val recordingElapsedMs: Long = 0L,
    // True while an active recording is on track to be DISCARDED at finalisation:
    // it has passed 75% of the no-motion window without enough cumulative motion.
    // Drives the red "will be deleted" border around the preview.
    val willDiscard: Boolean = false,
    val torchMode: TorchMode = TorchMode.OFF,
    val torchOn: Boolean = false,
    val keepScreenMode: KeepScreenMode = KeepScreenMode.OFF,
    val screenAsleep: Boolean = false,
    val focusLocked: Boolean = false,
    val zoomRatio: Float = 1f,
    val maxZoom: Float = 1f,
    // Current lens label ("Main"/"Wide") and how many selectable back lenses exist
    // (the toggle button is only shown when > 1).
    val lensLabel: String = "Main",
    val lensCount: Int = 1,
    val storageLow: Boolean = false,
    val batteryLow: Boolean = false,
    val storageFreePercent: Int = 100,
    val batteryPercent: Int = 100,
    val cameraError: String? = null,
    val serviceRunning: Boolean = false,
    // Config-QR scan: true while the service is decoding QR codes off the preview
    // stream; scannedConfig holds the raw payload of the most recent successful decode
    // (consumed by the UI, then cleared).
    val scanning: Boolean = false,
    val scannedConfig: String? = null,
    val uploadQueue: List<UploadItem> = emptyList(),
    val recentFiles: List<RecentFile> = emptyList(),
    // FTP connection test (from the Uploads screen button): true while probing;
    // ftpTestResult holds the last human-readable outcome until dismissed/re-run.
    val ftpTesting: Boolean = false,
    val ftpTestResult: String? = null
) {
    /** Human-readable status shown at the top of the recording screen. */
    val statusText: String
        get() = when (recorderState) {
            RecorderStateMachine.State.ARMED ->
                if (motionActive) "Motion — starting…" else "Armed — waiting for motion"
            RecorderStateMachine.State.RECORDING -> "Recording"
            RecorderStateMachine.State.GRACE ->
                "Recording — motion stopped, finalising in ${graceRemainingSec}s"
            RecorderStateMachine.State.DISARMED ->
                if (motionActive) "Disarmed — waiting for motion to clear"
                else "Disarmed"
        }

    val isRecording: Boolean
        get() = recorderState == RecorderStateMachine.State.RECORDING ||
                recorderState == RecorderStateMachine.State.GRACE
}

/** Process-wide observable app state, updated by the service, read by the UI. */
object AppState {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun update(transform: (UiState) -> UiState) {
        // Atomic read-modify-write: the scan result is delivered from the analysis
        // thread while other threads (battery/storage/torch) also update, and a plain
        // value assignment could drop a concurrent write (losing a scanned payload).
        _state.update(transform)
    }

    fun snapshot(): UiState = _state.value
}
