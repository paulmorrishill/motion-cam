package com.motioncam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.motioncam.util.L
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Camera2 wrapper that runs a continuous low-resolution motion-analysis stream,
 * an optional preview surface and a high-resolution [MediaRecorder] recording
 * stream. Recording uses [MediaRecorder.setNextOutputFile] so that hitting the
 * max file size rolls over to a new file with no dropped frames.
 */
class CameraController(private val context: Context) {

    interface Callbacks {
        /** Delivered on every analysis frame: a COPY of the luma (Y) plane. */
        fun onMotionLuma(luma: ByteArray, width: Int, height: Int, rowStride: Int)

        /** The file that is now actively being written (recording start or rollover). */
        fun onActiveRecordingFile(file: File)

        /** A recording file finished and is complete (rollover or stop). */
        fun onRecordingFileCompleted(file: File)

        /** Recording ended unexpectedly (e.g. max size reached without a rollover). */
        fun onRecordingInterrupted()

        /** Provides a fresh timestamped file for the next segment on rollover. */
        fun nextRecordingFile(): File

        fun onError(message: String)

        /** The camera device has opened and the initial session is being configured. */
        fun onCameraReady() {}
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private lateinit var cameraId: String
    private lateinit var characteristics: CameraCharacteristics

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sessionSurfaces: List<Surface> = emptyList()
    private var sessionGeneration = 0
    private var motionReader: ImageReader? = null
    private var recorder: MediaRecorder? = null

    private var previewSurface: Surface? = null

    private var recording = false
    private var recorderStarted = false
    private var pendingRecorderStart = false
    private val rollover = RolloverCoordinator {
        callbacks?.nextRecordingFile() ?: File(context.cacheDir, "segment-${System.nanoTime()}.mp4")
    }

    // Camera control state.
    private var torchOn = false
    private var focusLocked = false
    private var focusRegion: MeteringRectangle? = null
    private var awaitingFocus = false
    private var zoomRatio = 1f
    var maxZoom = 1f
        private set

    var recordingSize: Size = Size(1920, 1080)
        private set
    var previewSize: Size = Size(1280, 720)
        private set
    var motionSize: Size = Size(640, 480)
        private set

    private var callbacks: Callbacks? = null

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    fun setCallbacks(cb: Callbacks) {
        callbacks = cb
    }

    // ---- lifecycle ----

    // Remembered so a live lens switch can re-select sizes for the new camera.
    private var desiredRecording: Size? = null

    @SuppressLint("MissingPermission")
    fun open(desiredRecording: Size?, startCameraId: String? = null) {
        this.desiredRecording = desiredRecording
        try {
            cameraId = startCameraId ?: pickBackCamera()
            characteristics = cameraManager.getCameraCharacteristics(cameraId)
            selectSizes(desiredRecording)
            createMotionReader()
            cameraManager.openCamera(cameraId, deviceStateCallback, cameraHandler)
        } catch (e: Exception) {
            callbacks?.onError("open: ${e.message}")
        }
    }

    /**
     * Switch to another (back) camera live — used to toggle Main <-> ultra-wide, since
     * ultra-wide is a separate camera, not a sub-1.0 zoom. Finalises any recording in
     * progress, releases the current device and reopens the requested one; the stored
     * preview surface is reused so the preview returns automatically.
     */
    @SuppressLint("MissingPermission")
    fun switchCamera(newCameraId: String) {
        cameraHandler.post {
            if (::cameraId.isInitialized && device != null && newCameraId == cameraId) return@post
            if (recording) finishRecording(interrupted = false, rebuildSession = false)
            releaseDevice()
            try {
                cameraId = newCameraId
                characteristics = cameraManager.getCameraCharacteristics(newCameraId)
                zoomRatio = 1f
                focusLocked = false
                focusRegion = null
                selectSizes(desiredRecording)
                createMotionReader()
                cameraManager.openCamera(newCameraId, deviceStateCallback, cameraHandler)
            } catch (e: Exception) {
                callbacks?.onError("switchCamera: ${e.message}")
            }
        }
    }

    /** Openable back cameras with their shortest focal length, for lens selection. */
    fun backLenses(): List<LensChooser.BackLens> = try {
        cameraManager.cameraIdList.mapNotNull { id ->
            val ch = cameraManager.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                return@mapNotNull null
            }
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
                ?: return@mapNotNull null
            LensChooser.BackLens(id, focal)
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** The device's primary back camera id (the default lens). */
    fun defaultBackCameraId(): String = try {
        pickBackCamera()
    } catch (e: Exception) {
        cameraManager.cameraIdList.firstOrNull() ?: "0"
    }

    private fun pickBackCamera(): String {
        val ids = cameraManager.cameraIdList
        for (id in ids) {
            val facing = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return ids.firstOrNull() ?: throw IllegalStateException("No camera available")
    }

    private fun selectSizes(desiredRecording: Size?) {
        maxZoom = if (android.os.Build.VERSION.SDK_INT >= 30) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
        } else {
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        }.coerceAtLeast(1f)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val recSizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
        recordingSize = when {
            desiredRecording != null && recSizes.contains(desiredRecording) -> desiredRecording
            recSizes.isNotEmpty() -> chooseRecording(recSizes)
            else -> Size(1920, 1080)
        }
        // Preview sizes are queried for SurfaceHolder (the SurfaceView output class),
        // in sensor space (landscape). Pick the largest <= 1080p, preferring 16:9.
        val previewCandidates = map?.getOutputSizes(android.view.SurfaceHolder::class.java)
            ?.filter { it.width <= 1920 && it.height <= 1080 }
            ?.toList().orEmpty()
        previewSize = previewCandidates
            .firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: previewCandidates.filter {
                val r = it.width.toDouble() / it.height; r in 1.7..1.8
            }.maxByOrNull { it.width.toLong() * it.height }
            ?: previewCandidates.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 720)
        val motionCandidates = map?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width <= 800 && it.height <= 800 }
            ?.toList().orEmpty()
        motionSize = motionCandidates.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(640, 480)
    }

    private fun chooseRecording(sizes: List<Size>): Size {
        // Prefer exactly 1080p, else the largest 16:9, else the largest overall.
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
        val widescreen = sizes.filter {
            val r = it.width.toDouble() / it.height
            r in 1.7..1.8
        }
        return (widescreen.ifEmpty { sizes }).maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
    }

    private fun createMotionReader() {
        motionReader?.close()
        val reader = ImageReader.newInstance(
            motionSize.width, motionSize.height, ImageFormat.YUV_420_888, 2
        )
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                callbacks?.onMotionLuma(bytes, image.width, image.height, plane.rowStride)
            } catch (e: Exception) {
                // ignore a dropped frame
            } finally {
                image.close()
            }
        }, cameraHandler)
        motionReader = reader
    }

    private val deviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            createSession()
            callbacks?.onCameraReady()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            callbacks?.onError("camera error $error")
        }
    }

    fun setPreviewSurface(surface: Surface?) {
        cameraHandler.post {
            previewSurface = surface
            if (device != null && !recording) createSession()
        }
    }

    // ---- capture session ----

    private fun createSession() {
        val dev = device ?: return
        try {
            session?.close()
        } catch (_: Exception) {
        }
        val surfaces = buildList {
            previewSurface?.let { add(it) }
            motionReader?.surface?.let { add(it) }
            if (recording) recorder?.surface?.let { add(it) }
        }
        if (surfaces.isEmpty()) return
        val gen = ++sessionGeneration
        @Suppress("DEPRECATION")
        dev.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                // A newer session was requested while this one configured: drop it.
                if (gen != sessionGeneration) {
                    try { configured.close() } catch (_: Exception) {}
                    return
                }
                session = configured
                sessionSurfaces = surfaces
                startRepeating()
                // Start the recorder only once the session (incl. recorder surface)
                // is actually configured, so no leading frames are lost.
                if (recording && pendingRecorderStart) {
                    pendingRecorderStart = false
                    try {
                        recorder?.start()
                        recorderStarted = true
                    } catch (e: Exception) {
                        callbacks?.onError("recorder.start: ${e.message}")
                    }
                }
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                callbacks?.onError("session configure failed")
            }
        }, cameraHandler)
    }

    private fun startRepeating() {
        val dev = device ?: return
        val sess = session ?: return
        try {
            val template = if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = dev.createCaptureRequest(template)
            // Target exactly the surfaces this session was configured with, to avoid
            // "CaptureRequest contains unconfigured Surface" when surfaces changed
            // between session creation and this request.
            sessionSurfaces.forEach { builder.addTarget(it) }
            applyControls(builder)
            sess.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: IllegalStateException) {
            // Session closed/replaced concurrently — a fresh session will restore preview.
            L.w("Camera", "repeating on closed session: ${e.message}")
        } catch (e: CameraAccessException) {
            L.w("Camera", "repeating access error: ${e.message}", e)
        }
    }

    private fun applyControls(builder: CaptureRequest.Builder) {
        if (focusLocked) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
        }
        focusRegion?.let {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
        }
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        applyZoom(builder)
    }

    /** Applies the current zoom ratio via CONTROL_ZOOM_RATIO (API 30+) or a
     *  centered SCALER_CROP_REGION fallback. Also called from the focus request. */
    private fun applyZoom(builder: CaptureRequest.Builder) {
        val z = zoomRatio.coerceIn(1f, maxZoom)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
        } else {
            val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: return
            val cropW = (active.width() / z).toInt()
            val cropH = (active.height() / z).toInt()
            val left = active.left + (active.width() - cropW) / 2
            val top = active.top + (active.height() - cropH) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
        }
    }

    /** Multiply the current zoom by [factor] (pinch gesture). Returns the new ratio. */
    fun zoomBy(factor: Float): Float {
        cameraHandler.post {
            zoomRatio = (zoomRatio * factor).coerceIn(1f, maxZoom)
            startRepeating()
        }
        return (zoomRatio * factor).coerceIn(1f, maxZoom)
    }

    fun setZoom(ratio: Float) {
        cameraHandler.post {
            zoomRatio = ratio.coerceIn(1f, maxZoom)
            startRepeating()
        }
    }

    fun currentZoom(): Float = zoomRatio

    // ---- torch ----

    fun setTorch(on: Boolean) {
        cameraHandler.post {
            if (torchOn == on) return@post
            torchOn = on
            startRepeating()
        }
    }

    // ---- focus ----

    /**
     * Focus at a point in view coordinates. If [lock] is true the focus is held;
     * otherwise it is a momentary tap that resumes continuous AF.
     */
    fun focusAt(viewX: Float, viewY: Float, viewW: Int, viewH: Int, lock: Boolean) {
        cameraHandler.post {
            val region = meteringRectangle(viewX, viewY, viewW, viewH)
            focusRegion = region
            focusLocked = lock
            triggerAutoFocus(lock)
        }
    }

    fun unlockFocus() {
        cameraHandler.post {
            focusLocked = false
            focusRegion = null
            startRepeating()
        }
    }

    /**
     * Tap-to-focus. Crucially this NEVER rebuilds/closes the capture session — it
     * only reprograms the AF regions/trigger on the existing session. The steady
     * repeating request (with the new regions) is submitted FIRST so the preview
     * keeps running, then a single AF trigger is fired; a settle callback restores
     * a clean repeating request so the preview can never be left frozen. All
     * session access is guarded and CameraAccessException / IllegalStateException
     * (session closed concurrently) are swallowed instead of crashing.
     */
    private fun triggerAutoFocus(lock: Boolean) {
        val dev = device ?: return
        val sess = session ?: return
        val region = focusRegion
        val maxAf = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        try {
            val template = if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = dev.createCaptureRequest(template)
            sessionSurfaces.forEach { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            if (region != null && maxAf > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            if (region != null && maxAe > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.FLASH_MODE,
                if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            applyZoom(builder)

            awaitingFocus = true
            // 1) Steady repeating request WITH the new regions but IDLE trigger, so
            //    the preview never stops.
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            sess.setRepeatingRequest(builder.build(), afCaptureCallback, cameraHandler)

            // 2) One-time AF trigger.
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            sess.capture(builder.build(), afCaptureCallback, cameraHandler)
            L.d("Focus", "tap-to-focus lock=$lock region=$region")
        } catch (e: CameraAccessException) {
            L.w("Focus", "focus access error: ${e.message}", e)
        } catch (e: IllegalStateException) {
            // Session was closed concurrently between the guard and the call.
            L.w("Focus", "focus on closed session: ${e.message}")
        }
    }

    private val afCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult
        ) {
            val afState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)
            val settled = afState == null ||
                afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            if (awaitingFocus && settled) {
                awaitingFocus = false // one-shot: restore steady preview exactly once
                if (!focusLocked) focusRegion = null // momentary tap resumes clean continuous AF
                startRepeating() // restore a steady repeating request; never leave preview frozen
            }
        }
    }

    private fun meteringRectangle(
        viewX: Float, viewY: Float, viewW: Int, viewH: Int
    ): MeteringRectangle {
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, recordingSize.width, recordingSize.height)
        val fx = (viewX / max(1, viewW)).coerceIn(0f, 1f)
        val fy = (viewY / max(1, viewH)).coerceIn(0f, 1f)
        val cx = active.left + (fx * active.width()).toInt()
        val cy = active.top + (fy * active.height()).toInt()
        val half = (min(active.width(), active.height()) * 0.075f).toInt().coerceAtLeast(50)
        val left = (cx - half).coerceIn(active.left, active.right - 1)
        val top = (cy - half).coerceIn(active.top, active.bottom - 1)
        val right = (cx + half).coerceIn(left + 1, active.right)
        val bottom = (cy + half).coerceIn(top + 1, active.bottom)
        return MeteringRectangle(left, top, right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    // ---- recording ----

    fun startRecording(file: File, maxBytes: Long, orientationHint: Int) {
        cameraHandler.post {
            if (recording) return@post
            try {
                rollover.begin(file)
                recorderStarted = false
                pendingRecorderStart = true
                recorder = buildRecorder(file, maxBytes, orientationHint)
                recording = true
                callbacks?.onActiveRecordingFile(file)
                createSession() // rebuild with recorder surface; onConfigured starts it
            } catch (e: Exception) {
                recording = false
                pendingRecorderStart = false
                try {
                    recorder?.release()
                } catch (_: Exception) {
                }
                recorder = null
                callbacks?.onError("startRecording: ${e.message}")
            }
        }
    }

    fun stopRecording() {
        cameraHandler.post { finishRecording(interrupted = false) }
    }

    /**
     * Tears down the recorder. Delivers the finished file only if it was actually
     * started and is non-empty; deletes empty/aborted files so they are never
     * uploaded. [interrupted] true means an unexpected end (max size reached).
     */
    private fun finishRecording(interrupted: Boolean, rebuildSession: Boolean = true) {
        if (!recording && !interrupted) return
        recording = false
        pendingRecorderStart = false
        val finished = rollover.currentFile
        var stoppedCleanly = false
        if (recorderStarted) {
            try {
                recorder?.stop()
                stoppedCleanly = true
            } catch (e: Exception) {
                // Stopped almost immediately or already errored: file is unusable.
            }
        }
        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        recorderStarted = false
        // Discard a pre-created next segment that never started recording.
        rollover.pendingFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
        rollover.reset()
        // Rebuild the preview session without the recorder surface. Skipped by a live
        // camera switch, which is about to release the device entirely.
        if (rebuildSession) createSession()

        if (stoppedCleanly && finished != null && finished.length() > 0L) {
            callbacks?.onRecordingFileCompleted(finished)
        } else {
            finished?.let { if (it.exists() && it.length() == 0L) it.delete() }
        }
        if (interrupted) callbacks?.onRecordingInterrupted()
    }

    @Suppress("DEPRECATION")
    private fun buildRecorder(file: File, maxBytes: Long, orientationHint: Int): MediaRecorder {
        val rec = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setOutputFile(file.absolutePath)
        rec.setVideoEncodingBitRate(bitrateFor(recordingSize))
        rec.setVideoFrameRate(30)
        rec.setVideoSize(recordingSize.width, recordingSize.height)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        // Full-quality audio (defaults are very low: ~8kHz/low bitrate).
        rec.setAudioSamplingRate(48_000)
        rec.setAudioChannels(2)
        rec.setAudioEncodingBitRate(192_000)
        rec.setMaxFileSize(maxBytes)
        rec.setOrientationHint(orientationHint)
        rec.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                    val next = rollover.onApproaching()
                    try {
                        recorder?.setNextOutputFile(next)
                    } catch (e: Exception) {
                        callbacks?.onError("setNextOutputFile: ${e.message}")
                    }
                }
                MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                    // Previous file is complete; the next segment is now recording.
                    val (completed, active) = rollover.onNextStarted()
                    active?.let { callbacks?.onActiveRecordingFile(it) }
                    completed?.let { callbacks?.onRecordingFileCompleted(it) }
                }
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                    // Rollover did not take over (e.g. setNextOutputFile failed):
                    // the recorder has stopped writing. Finalise so the file is not
                    // lost and the state machine can re-arm.
                    cameraHandler.post { finishRecording(interrupted = true) }
                }
            }
        }
        rec.prepare()
        return rec
    }

    private fun bitrateFor(size: Size): Int {
        val ratio = (size.width.toLong() * size.height) / (1920.0 * 1080.0)
        return (ratio * 12_000_000).toInt().coerceIn(3_000_000, 60_000_000)
    }

    fun currentRecordingFile(): File? = rollover.currentFile

    fun sensorOrientation(): Int =
        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    /**
     * Orientation hint for [MediaRecorder.setOrientationHint] so video plays upright
     * in any device orientation (portrait and landscape). Formula from Google's
     * camera sample: (sensor - deviceDegrees * sign) mod 360, sign = -1 back / +1 front.
     */
    fun orientationHint(displayRotation: Int): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val facingFront =
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val sign = if (facingFront) 1 else -1
        return (sensor - deviceDegrees * sign + 360) % 360
    }

    fun close() {
        cameraHandler.post { releaseDevice() }
        cameraThread.quitSafely()
    }

    /** Release the camera device, session, recorder and motion reader — WITHOUT
     *  stopping the camera thread, so it can be reused by a live [switchCamera]. */
    private fun releaseDevice() {
        // Invalidate any in-flight capture-session configuration so a stale onConfigured
        // callback for the old device is dropped rather than assigning a dead session.
        ++sessionGeneration
        try {
            if (recording && recorderStarted) recorder?.stop()
        } catch (_: Exception) {
        }
        recording = false
        recorderStarted = false
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        motionReader?.close()
        motionReader = null
    }

    companion object {
        /** Available recording sizes for the default back camera (for settings UI). */
        fun availableRecordingSizes(context: Context): List<Size> {
            return try {
                val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = mgr.cameraIdList.firstOrNull { camId ->
                    mgr.getCameraCharacteristics(camId)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: mgr.cameraIdList.firstOrNull() ?: return emptyList()
                val map = mgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                map?.getOutputSizes(MediaRecorder::class.java)
                    ?.sortedByDescending { it.width.toLong() * it.height }
                    ?.toList().orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
