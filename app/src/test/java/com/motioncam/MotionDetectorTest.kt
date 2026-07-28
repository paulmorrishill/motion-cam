package com.motioncam

import com.google.common.truth.Truth.assertThat
import com.motioncam.motion.MotionDetector
import org.junit.Test

class MotionDetectorTest {

    private fun frame(width: Int, height: Int, value: Int): ByteArray =
        ByteArray(width * height) { value.toByte() }

    @Test
    fun firstFrameNeverReportsMotion() {
        val d = MotionDetector()
        val r = d.submit(frame(64, 48, 100), 64, 48)
        assertThat(r.motion).isFalse()
    }

    @Test
    fun identicalFramesReportNoMotion() {
        val d = MotionDetector()
        d.submit(frame(64, 48, 100), 64, 48)
        val r = d.submit(frame(64, 48, 100), 64, 48)
        assertThat(r.motion).isFalse()
        assertThat(r.changedCells).isEqualTo(0)
    }

    @Test
    fun largeBrightnessChangeReportsMotion() {
        val d = MotionDetector(sensitivity = 50)
        d.submit(frame(64, 48, 30), 64, 48)
        val r = d.submit(frame(64, 48, 220), 64, 48)
        assertThat(r.motion).isTrue()
    }

    @Test
    fun tinyChangeBelowThresholdIsIgnored() {
        val d = MotionDetector(sensitivity = 50)
        d.submit(frame(64, 48, 100), 64, 48)
        // Change only a handful of pixels in the corner.
        val f = frame(64, 48, 100)
        f[0] = 255.toByte()
        f[1] = 255.toByte()
        val r = d.submit(f, 64, 48)
        assertThat(r.motion).isFalse()
    }

    @Test
    fun higherSensitivityTriggersOnSmallerChange() {
        // A change to one quadrant.
        val base = frame(64, 48, 100)
        val changed = frame(64, 48, 100)
        for (y in 0 until 48) for (x in 0 until 16) changed[y * 64 + x] = 200.toByte()

        val low = MotionDetector(sensitivity = 5)
        low.submit(base, 64, 48)
        val lowResult = low.submit(changed, 64, 48)

        val high = MotionDetector(sensitivity = 95)
        high.submit(base, 64, 48)
        val highResult = high.submit(changed, 64, 48)

        // The sensitive detector should be at least as likely to fire.
        assertThat(highResult.score).isEqualTo(lowResult.score)
        assertThat(highResult.motion).isTrue()
    }

    @Test
    fun resetClearsBaseline() {
        val d = MotionDetector()
        d.submit(frame(64, 48, 30), 64, 48)
        d.reset()
        // After reset the next frame is a new baseline -> no motion.
        val r = d.submit(frame(64, 48, 220), 64, 48)
        assertThat(r.motion).isFalse()
    }
}
