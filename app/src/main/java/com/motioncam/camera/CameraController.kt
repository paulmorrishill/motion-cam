package com.motioncam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
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
    private var motionReader: ImageReader? = null
    private var recorder: MediaRecorder? = null

    private var previewSurface: Surface? = null

    private var recording = false
    private var recorderStarted = false
    private var pendingRecorderStart = false
    private var currentFile: File? = null
    private var pendingNextFile: File? = null

    // Camera control state.
    private var torchOn = false
    private var focusLocked = false
    private var focusRegion: MeteringRectangle? = null

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

    @SuppressLint("MissingPermission")
    fun open(desiredRecording: Size?) {
        try {
            cameraId = pickBackCamera()
            characteristics = cameraManager.getCameraCharacteristics(cameraId)
            selectSizes(desiredRecording)
            createMotionReader()
            cameraManager.openCamera(cameraId, deviceStateCallback, cameraHandler)
        } catch (e: Exception) {
            callbacks?.onError("open: ${e.message}")
        }
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
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val recSizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
        recordingSize = when {
            desiredRecording != null && recSizes.contains(desiredRecording) -> desiredRecording
            recSizes.isNotEmpty() -> chooseRecording(recSizes)
            else -> Size(1920, 1080)
        }
        val previewCandidates = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.filter { it.width <= 1920 && it.height <= 1080 }
            ?.toList().orEmpty()
        previewSize = previewCandidates.maxByOrNull { it.width.toLong() * it.height }
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
        @Suppress("DEPRECATION")
        dev.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
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
            previewSurface?.let { builder.addTarget(it) }
            motionReader?.surface?.let { builder.addTarget(it) }
            if (recording) recorder?.surface?.let { builder.addTarget(it) }
            applyControls(builder)
            sess.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            callbacks?.onError("repeating: ${e.message}")
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
    }

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

    private fun triggerAutoFocus(lock: Boolean) {
        val dev = device ?: return
        val sess = session ?: return
        try {
            val template = if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = dev.createCaptureRequest(template)
            previewSurface?.let { builder.addTarget(it) }
            motionReader?.surface?.let { builder.addTarget(it) }
            if (recording) recorder?.surface?.let { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            focusRegion?.let {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
            }
            builder.set(CaptureRequest.FLASH_MODE,
                if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

            if (lock) {
                // Wait for AF to converge, then latch the locked repeating request so
                // the focus actually holds at the tapped point.
                sess.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        val afState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)
                        if (afState == null ||
                            afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                        ) {
                            startRepeating() // applyControls() holds focus while locked
                        }
                    }
                }, cameraHandler)
            } else {
                // Momentary tap: trigger once, then resume continuous AF.
                sess.capture(builder.build(), null, cameraHandler)
                startRepeating()
            }
        } catch (e: Exception) {
            callbacks?.onError("focus: ${e.message}")
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
                currentFile = file
                pendingNextFile = null
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
    private fun finishRecording(interrupted: Boolean) {
        if (!recording && !interrupted) return
        recording = false
        pendingRecorderStart = false
        val finished = currentFile
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
        pendingNextFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
        pendingNextFile = null
        currentFile = null
        createSession() // rebuild without the recorder surface

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
        rec.setMaxFileSize(maxBytes)
        rec.setOrientationHint(orientationHint)
        rec.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                    val next = callbacks?.nextRecordingFile()
                    if (next != null) {
                        pendingNextFile = next
                        try {
                            recorder?.setNextOutputFile(next)
                        } catch (e: Exception) {
                            callbacks?.onError("setNextOutputFile: ${e.message}")
                        }
                    }
                }
                MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                    // Previous file is complete; the next segment is now recording.
                    val completed = currentFile
                    currentFile = pendingNextFile
                    pendingNextFile = null
                    currentFile?.let { callbacks?.onActiveRecordingFile(it) }
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

    fun currentRecordingFile(): File? = currentFile

    fun sensorOrientation(): Int =
        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    fun close() {
        cameraHandler.post {
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
        cameraThread.quitSafely()
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
