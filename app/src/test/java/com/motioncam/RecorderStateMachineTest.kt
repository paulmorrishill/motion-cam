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
    fun disarmedDoesNotRecordUntilMotionClearsThenReturns() {
        val m = sm()
        m.onMotion(true, 0)
        m.onUserStop()                            // DISARMED, motion still present
        // Motion still present: stays disarmed, no recording.
        assertThat(m.onMotion(true, 100)).isEmpty()
        assertThat(m.state).isEqualTo(State.DISARMED)
        // Motion clears -> auto re-arm.
        m.onMotion(false, 200)
        assertThat(m.state).isEqualTo(State.ARMED)
        // Next motion records again.
        assertThat(m.onMotion(true, 300)).containsExactly(Action.START_RECORDING)
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
