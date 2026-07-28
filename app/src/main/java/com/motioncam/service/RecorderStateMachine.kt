package com.motioncam.service

/**
 * Pure state machine that decides when to start / stop recording based on motion.
 *
 * States:
 *  - ARMED     : monitoring; the next motion starts a recording.
 *  - RECORDING : actively recording; motion is present.
 *  - GRACE     : recording continues but motion has stopped; a countdown runs.
 *                If motion returns we go back to RECORDING; if the countdown
 *                expires we finalise the file and return to ARMED.
 *  - DISARMED  : user pressed stop. Will NOT record. Auto re-arms once motion
 *                has cleared (so a later motion event records again), or the
 *                user can re-arm immediately.
 *
 * No Android dependencies -> fully unit-testable. Time is passed in as millis.
 */
class RecorderStateMachine(
    private var noMotionTimeoutMs: Long
) {
    enum class State { ARMED, RECORDING, GRACE, DISARMED }

    enum class Action { START_RECORDING, STOP_RECORDING }

    var state: State = State.ARMED
        private set

    /** Absolute millis at which the grace period ends (only meaningful in GRACE). */
    var graceDeadlineMs: Long = 0L
        private set

    fun setNoMotionTimeout(ms: Long) {
        noMotionTimeoutMs = ms
    }

    /** Millis remaining in the grace countdown, or 0 when not in GRACE. */
    fun graceRemainingMs(nowMs: Long): Long =
        if (state == State.GRACE) (graceDeadlineMs - nowMs).coerceAtLeast(0L) else 0L

    /** Report the current motion status. Returns any actions triggered. */
    fun onMotion(present: Boolean, nowMs: Long): List<Action> {
        return when (state) {
            State.ARMED -> if (present) {
                state = State.RECORDING
                listOf(Action.START_RECORDING)
            } else emptyList()

            State.RECORDING -> if (!present) {
                state = State.GRACE
                graceDeadlineMs = nowMs + noMotionTimeoutMs
                emptyList()
            } else emptyList()

            State.GRACE -> if (present) {
                state = State.RECORDING
                emptyList()
            } else emptyList()

            State.DISARMED -> if (!present) {
                // Motion has cleared: re-arm so a subsequent motion records again.
                state = State.ARMED
                emptyList()
            } else emptyList()
        }
    }

    /** Drives the grace countdown. Call periodically. */
    fun onTick(nowMs: Long): List<Action> {
        if (state == State.GRACE && nowMs >= graceDeadlineMs) {
            state = State.ARMED
            return listOf(Action.STOP_RECORDING)
        }
        return emptyList()
    }

    /** User pressed stop/disarm. Finalises any recording and disarms. */
    fun onUserStop(): List<Action> {
        val wasRecording = state == State.RECORDING || state == State.GRACE
        state = State.DISARMED
        return if (wasRecording) listOf(Action.STOP_RECORDING) else emptyList()
    }

    /** User manually re-arms while disarmed (skips waiting for motion to clear). */
    fun onUserRearm(): List<Action> {
        if (state == State.DISARMED) state = State.ARMED
        return emptyList()
    }

    /** Force back to ARMED after an unexpected recorder end (no STOP action needed). */
    fun forceArm() {
        state = State.ARMED
        graceDeadlineMs = 0L
    }
}
