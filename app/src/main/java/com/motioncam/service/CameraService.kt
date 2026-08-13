package com.motioncam.service

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.motioncam.MotionCamApp
import com.motioncam.camera.CameraController
import com.motioncam.camera.QrScanner
import com.motioncam.motion.MotionDetector
import com.motioncam.settings.SettingsStore
import com.motioncam.ui.MainActivity
import com.motioncam.upload.UploadManager
import com.motioncam.util.Beeper
import com.motioncam.util.Filenames
import com.motioncam.util.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * Foreground service that owns the camera and drives the whole motion-record
 * loop. It runs continuously (START_STICKY + wake lock) so the OS does not kill
 * it, previews the camera, detects motion, records, controls the torch, raises
 * storage/battery warnings and feeds finished files to the upload manager.
 *
 * All state-machine / detector access is confined to a single analysis thread so
 * no locking is required; camera operations are internally posted to the camera
 * thread by [CameraController].
 */
class CameraService : Service(), CameraController.Callbacks {

    private lateinit var controller: CameraController
    private lateinit var settings: SettingsStore
    private lateinit var beeper: Beeper
    private lateinit var uploads: UploadManager
    private lateinit var recordingsDir: File
    private lateinit var wakeLock: PowerManager.WakeLock

    private val detector = MotionDetector()
    private val stateMachine = RecorderStateMachine(60_000L)

    private val qrScanner = QrScanner()
    // Set on the UI thread, read on the analysis thread; volatile is enough (single
    // writer flips it, the analysis loop only reads and flips it back off on a hit).
    @Volatile private var scanningConfig = false
    // Throttle QR decoding: at preview frame rate a TRY_HARDER decode per frame would
    // load the single analysis thread; one attempt per interval is plenty to catch a
    // held-up code. Accessed only on the analysis thread.
    private var lastScanAttemptMs = 0L
    private val scanIntervalMs = 150L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analysis = Executors.newSingleThreadExecutor()

    // Motion smoothing: a motion frame keeps "present" true for this long.
    private val motionHoldMs = 800L
    private var lastMotionTime = 0L
    private var motionPresent = false

    private var recordingStartTime = 0L
    private var lastStorageBeep = 0L
    private var lastBatteryBeep = 0L
    private val warnBeepIntervalMs = 5 * 60 * 1000L

    private var batteryPercent = 100
    private var torchMode = TorchMode.OFF

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: CameraService get() = this@CameraService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryPercent = (level * 100) / scale
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore.get(this)
        beeper = Beeper(this)
        recordingsDir = (getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            ?: filesDir).apply { mkdirs() }
        uploads = UploadManager(this, recordingsDir, settings, scope)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotionCam:capture").apply {
            setReferenceCounted(false)
            acquire()
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        startForegroundWithNotification()

        detector.setSensitivity(settings.current.motionSensitivity)
        stateMachine.setNoMotionTimeout(settings.current.noMotionTimeoutSec * 1000L)

        controller = CameraController(this)
        controller.setCallbacks(this)
        val s = settings.current
        val desired = if (s.resolutionIsAuto) null
        else android.util.Size(s.resolutionWidth, s.resolutionHeight)
        controller.open(desired)

        uploads.start()
        startTicker()

        // Apply settings changes live (sensitivity, no-motion timeout, ...).
        scope.launch {
            settings.flow.collect { reloadSettings() }
        }

        AppState.update { it.copy(serviceRunning = true) }
        L.i("Service", "camera service created; recordings in ${recordingsDir.absolutePath}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // ---- ticker: drives grace countdown, elapsed time, warnings ----

    private fun startTicker() {
        scope.launch {
            while (true) {
                analysis.execute { onTick() }
                delay(400)
            }
        }
    }

    private fun onTick() {
        val now = System.currentTimeMillis()
        // Recompute motion presence (it decays even without new frames).
        val present = (now - lastMotionTime) < motionHoldMs
        if (present != motionPresent) applyMotionChange(present, now)

        handleActions(stateMachine.onTick(now), now)

        val grace = (stateMachine.graceRemainingMs(now) / 1000).toInt()
        val elapsed = if (AppState.snapshot().isRecording) now - recordingStartTime else 0L
        checkWarnings(now)
        AppState.update {
            it.copy(
                recorderState = stateMachine.state,
                graceRemainingSec = grace,
                recordingElapsedMs = elapsed,
                batteryPercent = batteryPercent
            )
        }
    }

    // ---- motion pipeline ----

    override fun onMotionLuma(luma: ByteArray, width: Int, height: Int, rowStride: Int) {
        analysis.execute {
            if (scanningConfig) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastScanAttemptMs >= scanIntervalMs) {
                    lastScanAttemptMs = nowMs
                    val text = qrScanner.decode(luma, width, height, rowStride)
                    if (text != null) {
                        scanningConfig = false
                        L.i("Scan", "config QR decoded (${text.length} chars)")
                        AppState.update { it.copy(scanning = false, scannedConfig = text) }
                    }
                }
            }
            val result = detector.submit(luma, width, height, rowStride)
            val now = System.currentTimeMillis()
            if (result.motion) lastMotionTime = now
            val present = (now - lastMotionTime) < motionHoldMs
            if (present != motionPresent) applyMotionChange(present, now)
        }
    }

    private fun applyMotionChange(present: Boolean, now: Long) {
        motionPresent = present
        L.d("Motion", "motion ${if (present) "detected" else "cleared"} state=${stateMachine.state}")
        AppState.update { it.copy(motionActive = present) }
        applyTorchForMotion(present)
        handleActions(stateMachine.onMotion(present, now), now)
    }

    private fun applyTorchForMotion(present: Boolean) {
        when (torchMode) {
            TorchMode.OFF -> controller.setTorch(false)
            TorchMode.ON -> controller.setTorch(true)
            TorchMode.AUTO -> controller.setTorch(present)
        }
        AppState.update {
            it.copy(torchOn = torchMode == TorchMode.ON || (torchMode == TorchMode.AUTO && present))
        }
    }

    private fun handleActions(actions: List<RecorderStateMachine.Action>, now: Long) {
        for (action in actions) when (action) {
            RecorderStateMachine.Action.START_RECORDING -> startRecording(now)
            RecorderStateMachine.Action.STOP_RECORDING -> stopRecording()
        }
    }

    private fun startRecording(now: Long) {
        val file = makeRecordingFile()
        recordingStartTime = now
        val maxBytes = settings.current.maxFileSizeMb.toLong() * 1024L * 1024L
        val configuredRot = settings.current.videoRotationDegrees
        val hint = if (configuredRot in intArrayOf(0, 90, 180, 270)) configuredRot
        else controller.orientationHint(currentDisplayRotation())
        L.i(
            "Rec",
            "start ${file.name} size=${controller.recordingSize.width}x${controller.recordingSize.height} " +
                "hint=$hint (configured=$configuredRot) max=${settings.current.maxFileSizeMb}MB"
        )
        controller.startRecording(file, maxBytes, hint)
        beeper.recordingStarted()
        AppState.update {
            it.copy(currentFileName = file.name, recorderState = stateMachine.state)
        }
        updateNotification()
    }

    private fun stopRecording() {
        L.i("Rec", "stop recording (state=${stateMachine.state})")
        controller.stopRecording()
        beeper.recordingStopped()
        AppState.update { it.copy(currentFileName = null, recorderState = stateMachine.state) }
        updateNotification()
    }

    // ---- CameraController.Callbacks ----

    override fun onCameraReady() {
        AppState.update { it.copy(maxZoom = controller.maxZoom) }
        L.i("Camera", "ready; maxZoom=${controller.maxZoom}")
    }

    override fun onActiveRecordingFile(file: File) {
        // Track the file currently being written (updated on rollover too) so the
        // uploader never uploads/deletes the in-progress segment.
        AppState.update { it.copy(currentFileName = file.name) }
    }

    override fun onRecordingFileCompleted(file: File) {
        // Finished (either rollover or stop) -> queue for upload + run retention.
        uploads.trigger()
    }

    override fun onRecordingInterrupted() {
        // Recorder ended unexpectedly (max size reached without rollover).
        L.w("Rec", "recording interrupted (max size reached without rollover)")
        analysis.execute {
            stateMachine.forceArm()
            AppState.update {
                it.copy(currentFileName = null, recorderState = stateMachine.state)
            }
        }
        beeper.recordingStopped()
        uploads.trigger()
        updateNotification()
    }

    override fun nextRecordingFile(): File = makeRecordingFile()

    private fun makeRecordingFile(): File {
        val name = Filenames.videoFileName(settings.current.deviceName, System.currentTimeMillis())
        return File(recordingsDir, name)
    }

    /** Current default-display rotation (Surface.ROTATION_*), for recording orientation. */
    private fun currentDisplayRotation(): Int {
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: android.view.Surface.ROTATION_0
        } catch (e: Exception) {
            android.view.Surface.ROTATION_0
        }
    }

    override fun onError(message: String) {
        L.e("Camera", "camera error: $message")
        AppState.update { it.copy(cameraError = message) }
    }

    // ---- warnings (storage / battery) ----

    private fun checkWarnings(now: Long) {
        val s = settings.current
        val free = recordingsDir.usableSpace
        val total = recordingsDir.totalSpace.coerceAtLeast(1)
        val freePercent = ((free * 100) / total).toInt()
        val storageLow = freePercent <= s.storageLowPercent
        val batteryLow = batteryPercent < s.batteryLowPercent
        val recording = AppState.snapshot().isRecording

        // Storage-low cue repeats only while recording (per spec).
        if (storageLow && recording && now - lastStorageBeep >= warnBeepIntervalMs) {
            lastStorageBeep = now
            beeper.storageLow()
        }
        if (batteryLow && now - lastBatteryBeep >= warnBeepIntervalMs) {
            lastBatteryBeep = now
            beeper.batteryLow()
        }
        AppState.update {
            it.copy(
                storageLow = storageLow,
                batteryLow = batteryLow,
                storageFreePercent = freePercent
            )
        }
    }

    // ---- commands from the UI (via binder) ----

    fun setPreviewSurface(surface: Surface) {
        controller.setPreviewSurface(surface)
    }

    fun clearPreview() {
        controller.setPreviewSurface(null)
    }

    /** Camera preview buffer size (sensor space) for the AutoFitSurfaceView aspect. */
    fun previewSize(): android.util.Size = controller.previewSize

    fun focusTap(x: Float, y: Float, w: Int, h: Int) = controller.focusAt(x, y, w, h, lock = false)

    fun focusLock(x: Float, y: Float, w: Int, h: Int) {
        controller.focusAt(x, y, w, h, lock = true)
        AppState.update { it.copy(focusLocked = true) }
    }

    fun focusUnlock() {
        controller.unlockFocus()
        AppState.update { it.copy(focusLocked = false) }
    }

    /** Pinch zoom: multiply current zoom by [factor]. */
    fun zoomBy(factor: Float) {
        val z = controller.zoomBy(factor)
        AppState.update { it.copy(zoomRatio = z, maxZoom = controller.maxZoom) }
    }

    fun resetZoom() {
        controller.setZoom(1f)
        AppState.update { it.copy(zoomRatio = 1f, maxZoom = controller.maxZoom) }
    }

    /** Start decoding config QR codes off the preview stream. */
    fun beginConfigScan() {
        scanningConfig = true
        AppState.update { it.copy(scanning = true, scannedConfig = null) }
    }

    /** Stop scanning without a result (user cancelled). Also drop any payload a decode
     *  may have published in the same instant, so a cancelled scan never bounces the
     *  user into a QR-seeded Settings screen. */
    fun cancelConfigScan() {
        scanningConfig = false
        AppState.update { it.copy(scanning = false, scannedConfig = null) }
    }

    /** Clear a delivered scan payload once the UI has consumed it. */
    fun consumeScannedConfig() {
        AppState.update { it.copy(scannedConfig = null) }
    }

    fun stopOrDisarm() {
        analysis.execute { handleActions(stateMachine.onUserStop(), System.currentTimeMillis()) }
    }

    fun rearm() {
        analysis.execute { handleActions(stateMachine.onUserRearm(), System.currentTimeMillis()) }
    }

    fun cycleTorch() {
        torchMode = when (torchMode) {
            TorchMode.OFF -> TorchMode.AUTO
            TorchMode.AUTO -> TorchMode.ON
            TorchMode.ON -> TorchMode.OFF
        }
        AppState.update { it.copy(torchMode = torchMode) }
        applyTorchForMotion(motionPresent)
    }

    /** User asked to fully stop the app: leave the foreground, stop the service
     *  (cleanup — camera/recorder/wake lock release — happens in onDestroy). */
    fun shutdown() {
        L.i("Service", "user requested shutdown")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun reloadSettings() {
        val s = settings.current
        detector.setSensitivity(s.motionSensitivity)
        stateMachine.setNoMotionTimeout(s.noMotionTimeoutSec * 1000L)
    }

    // ---- notification ----

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when launched from the
            // background on Android 14+. Stop cleanly instead of crash-looping; the
            // user relaunches the app to start recording.
            stopSelf()
        }
    }

    private fun updateNotification() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val pending = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, MotionCamApp.CHANNEL_ID)
            .setContentTitle("Motion Camera")
            .setContentText(AppState.snapshot().statusText)
            .setSmallIcon(com.motioncam.R.drawable.ic_stat_camera)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppState.update { it.copy(serviceRunning = false) }
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
        controller.close()
        beeper.release()
        analysis.shutdownNow()
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
    }

    companion object {
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, CameraService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
