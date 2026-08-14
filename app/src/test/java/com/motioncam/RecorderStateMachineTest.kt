package com.motioncam

import com.google.common.truth.Truth.assertThat
import com.motioncam.service.RecorderStateMachine
import com.motioncam.service.RecorderStateMachine.Action
import com.motioncam.service.RecorderStateMachine.State
import org.junit.Test

class RecorderStateMachineTest {

    private fun sm(timeoutMs: Long = 60_000L) = RecorderStateMachine(timeoutMs)

    @Test
    fun motionWhileArmedStartsRecording() {
        val m = sm()
        val actions = m.onMotion(present = true, nowMs = 0)
        assertThat(actions).containsExactly(Action.START_RECORDING)
        assertThat(m.state).isEqualTo(State.RECORDING)
    }

    @Test
    fun motionStoppingEntersGraceThenFinalisesAfterTimeout() {
        val m = sm(timeoutMs = 1000)
        m.onMotion(true, 0)                       // -> RECORDING
        assertThat(m.onMotion(false, 100)).isEmpty()
        assertThat(m.state).isEqualTo(State.GRACE)
        // Before timeout: nothing.
        assertThat(m.onTick(500)).isEmpty()
        // After timeout: finalise + re-arm.
        val actions = m.onTick(1200)
        assertThat(actions).containsExactly(Action.STOP_RECORDING)
        assertThat(m.state).isEqualTo(State.ARMED)
    }

    @Test
    fun motionReturningDuringGraceResumesRecording() {
        val m = sm(timeoutMs = 1000)
        m.onMotion(true, 0)
        m.onMotion(false, 100)                    // -> GRACE
        m.onMotion(true, 300)                     // motion back
        assertThat(m.state).isEqualTo(State.RECORDING)
        // The countdown was cancelled: a later tick does not stop recording.
        assertThat(m.onTick(2000)).isEmpty()
        assertThat(m.state).isEqualTo(State.RECORDING)
    }

    @Test
    fun userStopWhileRecordingFinalisesAndDisarms() {
        val m = sm()
        m.onMotion(true, 0)
        val actions = m.onUserStop()
        assertThat(actions).containsExactly(Action.STOP_RECORDING)
        assertThat(m.state).isEqualTo(State.DISARMED)
    }

    @Test
    fun disarmedStaysDisarmedUntilUserReArms() {
        val m = sm()
        m.onMotion(true, 0)
        m.onUserStop()                            // DISARMED, motion still present
        // Motion still present: stays disarmed, no recording.
        assertThat(m.onMotion(true, 100)).isEmpty()
        assertThat(m.state).isEqualTo(State.DISARMED)
        // Motion clearing does NOT re-arm any more.
        m.onMotion(false, 200)
        assertThat(m.state).isEqualTo(State.DISARMED)
        // Motion returning still does nothing.
        assertThat(m.onMotion(true, 300)).isEmpty()
        assertThat(m.state).isEqualTo(State.DISARMED)
        // Only an explicit re-arm brings it back; then motion records.
        m.onUserRearm()
        assertThat(m.state).isEqualTo(State.ARMED)
        assertThat(m.onMotion(true, 400)).containsExactly(Action.START_RECORDING)
    }

    @Test
    fun clipWithTooLittleMotionIsCancelledAtGraceExpiry() {
        val m = sm(timeoutMs = 1000).apply { setMinMovement(4000) }
        m.onMotion(true, 0)                       // RECORDING, segment starts at 0
        m.onMotion(false, 2000)                   // 2s cumulative motion -> GRACE (deadline 3000)
        val actions = m.onTick(3000)
        assertThat(actions).containsExactly(Action.CANCEL_RECORDING)
        assertThat(m.state).isEqualTo(State.ARMED)
    }

    @Test
    fun clipWithEnoughMotionIsSavedAtGraceExpiry() {
        val m = sm(timeoutMs = 1000).apply { setMinMovement(4000) }
        m.onMotion(true, 0)
        m.onMotion(false, 5000)                   // 5s cumulative motion
        assertThat(m.onTick(6000)).containsExactly(Action.STOP_RECORDING)
    }

    @Test
    fun motionAccumulatesAcrossGracePauses() {
        val m = sm(timeoutMs = 1000).apply { setMinMovement(4000) }
        m.onMotion(true, 0)                       // seg1 start 0
        m.onMotion(false, 2500)                   // +2.5s -> GRACE
        m.onMotion(true, 3000)                    // resume: seg2 start 3000
        m.onMotion(false, 5000)                   // +2.0s => 4.5s total -> GRACE (deadline 6000)
        // 4.5s >= 4s threshold, so the clip is kept.
        assertThat(m.onTick(6000)).containsExactly(Action.STOP_RECORDING)
    }

    @Test
    fun minMovementZeroKeepsEveryClip() {
        val m = sm(timeoutMs = 1000)              // gate disabled (default 0)
        m.onMotion(true, 0)
        m.onMotion(false, 100)                    // 0.1s of motion
        assertThat(m.onTick(1200)).containsExactly(Action.STOP_RECORDING)
    }

    @Test
    fun manualStopSavesEvenBelowMinMovement() {
        val m = sm().apply { setMinMovement(4000) }
        m.onMotion(true, 0)
        m.onMotion(false, 500)                    // 0.5s of motion, now in GRACE
        // Manual stop always saves whatever was captured.
        assertThat(m.onUserStop()).containsExactly(Action.STOP_RECORDING)
        assertThat(m.state).isEqualTo(State.DISARMED)
    }

    @Test
    fun currentMovementIncludesInProgressSegment() {
        val m = sm().apply { setMinMovement(4000) }
        m.onMotion(true, 1000)                    // RECORDING from 1000
        assertThat(m.currentMovementMs(3000)).isEqualTo(2000)  // 2s so far
        m.onMotion(false, 4000)                   // banked 3s -> GRACE
        assertThat(m.currentMovementMs(9000)).isEqualTo(3000)  // frozen during grace
    }

    @Test
    fun manualRearmSkipsWaitingForMotionToClear() {
        val m = sm()
        m.onMotion(true, 0)
        m.onUserStop()                            // DISARMED
        m.onUserRearm()                           // manual re-arm even though motion present
        assertThat(m.state).isEqualTo(State.ARMED)
        assertThat(m.onMotion(true, 100)).containsExactly(Action.START_RECORDING)
    }

    @Test
    fun graceRemainingCountsDown() {
        val m = sm(timeoutMs = 10_000)
        m.onMotion(true, 0)
        m.onMotion(false, 1_000)                  // GRACE, deadline = 11_000
        assertThat(m.graceRemainingMs(1_000)).isEqualTo(10_000)
        assertThat(m.graceRemainingMs(6_000)).isEqualTo(5_000)
        assertThat(m.graceRemainingMs(20_000)).isEqualTo(0)
    }
}
