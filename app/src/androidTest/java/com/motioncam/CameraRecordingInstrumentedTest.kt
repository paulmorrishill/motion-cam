package com.motioncam

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.motioncam.camera.CameraController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Real camera-to-file recording test on the emulator's emulated camera. Opens
 * the camera through the production [CameraController], records for a few
 * seconds and asserts a valid, non-empty MP4 with a positive duration is
 * produced — exercising the actual Camera2 + MediaRecorder pipeline.
 */
@RunWith(AndroidJUnit4::class)
class CameraRecordingInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun recordsValidMp4FromCamera() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = File(ctx.cacheDir, "rec-test").apply { mkdirs() }

        // The emulated camera can be slow/flaky to open in headless CI, so retry
        // the open+record cycle with a fresh controller before failing.
        var file: File? = null
        val diagnostics = StringBuilder()
        for (attempt in 1..3) {
            file = tryRecordOnce(ctx, outDir, attempt, diagnostics)
            if (file != null) break
        }

        assertWithDiagnostics(file != null, "No MP4 produced", diagnostics)
        val f = file!!
        assertThat(f.exists()).isTrue()
        assertThat(f.length()).isGreaterThan(0L)

        // Validate it is a real, playable MP4 with a positive duration and a video track.
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(f.absolutePath)
            val durationMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val hasVideo =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            assertThat(durationMs).isNotNull()
            assertThat(durationMs!!).isGreaterThan(0L)
            assertThat(hasVideo).isEqualTo("yes")
        } finally {
            retriever.release()
        }
    }

    /**
     * Verifies the seamless size rollover: with a small max file size the
     * recorder must roll over to new files (via setNextOutputFile) producing
     * multiple valid MP4 segments, so a real 2GB rollover would not lose action.
     */
    @Test
    fun rollsOverToNewSegmentsAtMaxSize() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = File(ctx.cacheDir, "roll-test").apply { mkdirs() }
        val completed = java.util.Collections.synchronizedList(mutableListOf<File>())
        val diag = StringBuilder()

        var rolledOver = false
        for (attempt in 1..3) {
            completed.clear()
            val ready = CountDownLatch(1)
            val errors = StringBuilder()
            val controller = CameraController(ctx)
            controller.setCallbacks(object : CameraController.Callbacks {
                override fun onMotionLuma(luma: ByteArray, width: Int, height: Int, rowStride: Int) {}
                override fun onActiveRecordingFile(file: File) {}
                override fun onRecordingFileCompleted(file: File) { completed.add(file) }
                override fun onRecordingInterrupted() {}
                override fun nextRecordingFile(): File = File(outDir, "seg-${System.nanoTime()}.mp4")
                override fun onError(message: String) {
                    synchronized(errors) { errors.append(message).append("; ") }
                }
                override fun onCameraReady() { ready.countDown() }
            })
            try {
                controller.open(null)
                if (!ready.await(25, TimeUnit.SECONDS)) {
                    diag.append("attempt $attempt: not ready ($errors)\n"); continue
                }
                // Small 1.5 MB segments roll over within seconds even at low emulator
                // bitrate; record a fixed span to accumulate several rollovers.
                controller.startRecording(
                    File(outDir, "seg-${System.nanoTime()}.mp4"),
                    maxBytes = 1_500L * 1024,
                    orientationHint = 90
                )
                Thread.sleep(25_000)
                controller.stopRecording()
                Thread.sleep(2_000) // let the final segment finalise
            } finally {
                controller.close()
                Thread.sleep(500)
            }
            // Two or more completed files means at least one seamless rollover.
            rolledOver = completed.size >= 2
            diag.append("attempt $attempt: segments=${completed.size} ($errors)\n")
            if (rolledOver) break
        }

        if (!rolledOver) throw AssertionError("No rollover observed.\n$diag")
        // Every produced segment must be a valid, playable MP4.
        for (seg in ArrayList(completed)) {
            assertThat(seg.exists()).isTrue()
            assertThat(seg.length()).isGreaterThan(0L)
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(seg.absolutePath)
                val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                assertThat(hasVideo).isEqualTo("yes")
            } finally {
                r.release()
            }
        }
    }

    private fun assertWithDiagnostics(condition: Boolean, message: String, diag: StringBuilder) {
        if (!condition) throw AssertionError("$message\nAttempts:\n$diag")
    }

    /** One open+record+stop cycle with a fresh controller. Returns the completed
     *  file, or null if the camera failed to open or finalise within the timeouts. */
    private fun tryRecordOnce(
        ctx: android.content.Context,
        outDir: File,
        attempt: Int,
        diag: StringBuilder
    ): File? {
        val outFile = File(outDir, "record-$attempt-${System.nanoTime()}.mp4")
        val ready = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val completedFile = AtomicReference<File?>(null)
        val errors = StringBuilder()

        val controller = CameraController(ctx)
        controller.setCallbacks(object : CameraController.Callbacks {
            override fun onMotionLuma(luma: ByteArray, width: Int, height: Int, rowStride: Int) {}
            override fun onActiveRecordingFile(file: File) {}
            override fun onRecordingFileCompleted(file: File) {
                completedFile.set(file); completed.countDown()
            }
            override fun onRecordingInterrupted() {}
            override fun nextRecordingFile(): File = File(outDir, "next-${System.nanoTime()}.mp4")
            override fun onError(message: String) {
                synchronized(errors) { errors.append(message).append("; ") }
            }
            override fun onCameraReady() { ready.countDown() }
        })

        try {
            controller.open(null) // auto-select best supported size
            if (!ready.await(25, TimeUnit.SECONDS)) {
                diag.append("attempt $attempt: camera not ready (errors: $errors)\n")
                return null
            }
            controller.startRecording(outFile, maxBytes = 512L * 1024 * 1024, orientationHint = 90)
            Thread.sleep(5_000) // record a few seconds of the emulated scene
            controller.stopRecording()
            if (!completed.await(25, TimeUnit.SECONDS)) {
                diag.append("attempt $attempt: recording not finalised (errors: $errors)\n")
                return null
            }
            diag.append("attempt $attempt: ok (errors: $errors)\n")
            return completedFile.get()
        } finally {
            controller.close()
            Thread.sleep(500) // let the camera device release before any retry
        }
    }
}
