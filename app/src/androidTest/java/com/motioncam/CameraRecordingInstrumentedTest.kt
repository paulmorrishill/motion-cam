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
        val outFile = File(outDir, "record-${System.currentTimeMillis()}.mp4")

        val ready = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val completedFile = AtomicReference<File?>(null)
        val errors = mutableListOf<String>()

        val controller = CameraController(ctx)
        controller.setCallbacks(object : CameraController.Callbacks {
            override fun onMotionLuma(luma: ByteArray, width: Int, height: Int, rowStride: Int) {}
            override fun onActiveRecordingFile(file: File) {}
            override fun onRecordingFileCompleted(file: File) {
                completedFile.set(file)
                completed.countDown()
            }
            override fun onRecordingInterrupted() {}
            override fun nextRecordingFile(): File = File(outDir, "next-${System.currentTimeMillis()}.mp4")
            override fun onError(message: String) {
                synchronized(errors) { errors.add(message) }
            }
            override fun onCameraReady() {
                ready.countDown()
            }
        })

        try {
            controller.open(null) // auto-select best supported size
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue()

            controller.startRecording(outFile, maxBytes = 512L * 1024 * 1024, orientationHint = 90)
            // Record a few seconds of the emulated scene.
            Thread.sleep(4_000)
            controller.stopRecording()

            assertThat(completed.await(15, TimeUnit.SECONDS)).isTrue()
        } finally {
            controller.close()
        }

        val file = completedFile.get()
        assertThat(file).isNotNull()
        assertThat(file!!.exists()).isTrue()
        assertThat(file.length()).isGreaterThan(0L)

        // Validate it is a real, playable MP4 with a positive duration and a video track.
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
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
}
