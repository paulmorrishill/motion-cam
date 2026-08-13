package com.motioncam

import com.google.common.truth.Truth.assertThat
import com.motioncam.motion.MotionGate
import org.junit.Test

class MotionGateTest {

    private fun gate() = MotionGate(onsetMs = 400, holdMs = 800, gapMs = 350)

    @Test
    fun singleNoiseSpike_doesNotConfirmMotion() {
        val g = gate()
        // One lone motion frame (sensor-noise spike), then quiet.
        assertThat(g.onFrame(true, 1_000)).isFalse()
        assertThat(g.onFrame(false, 1_033)).isFalse()
        assertThat(g.onFrame(false, 1_100)).isFalse()
        assertThat(g.present(1_500)).isFalse()
    }

    @Test
    fun twoBriefSpikes_stillDoNotConfirm() {
        val g = gate()
        assertThat(g.onFrame(true, 1_000)).isFalse()
        assertThat(g.onFrame(true, 1_100)).isFalse() // only 100ms of run, < onset
        assertThat(g.present(1_150)).isFalse()
    }

    @Test
    fun sustainedMotion_confirmsAfterOnset() {
        val g = gate()
        var t = 1_000L
        var present = false
        // Motion frames every 33ms; must confirm once the run reaches onsetMs.
        repeat(20) {
            present = g.onFrame(true, t)
            t += 33
        }
        assertThat(present).isTrue()
    }

    @Test
    fun confirmedMotion_notReachedBeforeOnset() {
        val g = gate()
        assertThat(g.onFrame(true, 1_000)).isFalse()
        assertThat(g.onFrame(true, 1_200)).isFalse() // 200ms run < 400ms onset
        assertThat(g.onFrame(true, 1_399)).isFalse() // 399ms < 400ms
        assertThat(g.onFrame(true, 1_401)).isTrue()  // 401ms >= onset
    }

    @Test
    fun motionHeldThroughShortFrameGaps_thenClearsAfterHold() {
        val g = gate()
        var t = 1_000L
        repeat(20) { g.onFrame(true, t); t += 33 } // confirmed present
        assertThat(g.present(t)).isTrue()
        // Motion stops; still present within hold window...
        assertThat(g.present(t + 700)).isTrue()
        // ...cleared after holdMs with no further motion frames.
        assertThat(g.present(t + 900)).isFalse()
    }

    @Test
    fun gapLongerThanGapMs_resetsTheRun() {
        val g = gate()
        // Build up almost to onset...
        assertThat(g.onFrame(true, 1_000)).isFalse()
        assertThat(g.onFrame(true, 1_300)).isFalse()
        // ...a gap longer than gapMs breaks the run; the clock restarts.
        assertThat(g.onFrame(true, 1_800)).isFalse() // new run starts here
        assertThat(g.onFrame(true, 2_000)).isFalse() // only 200ms into new run
        assertThat(g.onFrame(true, 2_201)).isTrue()  // 401ms into new run
    }
}
