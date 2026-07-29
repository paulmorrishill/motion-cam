package com.motioncam.service

import com.motioncam.upload.RecentFile
import com.motioncam.upload.UploadItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TorchMode { OFF, AUTO, ON }

enum class KeepScreenMode { OFF, TIMED, ON_MOTION, ALWAYS }

/** Everything the UI needs to render, as one immutable snapshot. */
data class UiState(
    val recorderState: RecorderStateMachine.State = RecorderStateMachine.State.ARMED,
    val motionActive: Boolean = false,
    val graceRemainingSec: Int = 0,
    val currentFileName: String? = null,
    val recordingElapsedMs: Long = 0L,
    val torchMode: TorchMode = TorchMode.OFF,
    val torchOn: Boolean = false,
    val keepScreenMode: KeepScreenMode = KeepScreenMode.OFF,
    val screenAsleep: Boolean = false,
    val focusLocked: Boolean = false,
    val zoomRatio: Float = 1f,
    val maxZoom: Float = 1f,
    val storageLow: Boolean = false,
    val batteryLow: Boolean = false,
    val storageFreePercent: Int = 100,
    val batteryPercent: Int = 100,
    val cameraError: String? = null,
    val serviceRunning: Boolean = false,
    val uploadQueue: List<UploadItem> = emptyList(),
    val recentFiles: List<RecentFile> = emptyList()
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
        _state.value = transform(_state.value)
    }

    fun snapshot(): UiState = _state.value
}
