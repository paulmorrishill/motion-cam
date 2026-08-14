package com.motioncam.service

/**
 * Pure state machine that decides when to start / stop recording based on motion.
 *
 * States:
 *  - ARMED     : monitoring; the next motion starts a recording.
 *  - RECORDING : actively recording; motion is present.
 *  - GRACE     : recording continues but motion has stopped; a countdown runs.
 *                If motion returns we go back to RECORDING; if the countdown
 *                expires we finalise the file and return to ARMED. On finalise
 *                the clip is CANCELLED (discarded) instead of saved when the
 *                cumulative motion during the recording fell below [minMovementMs].
 *  - DISARMED  : user pressed stop. Will NOT record and stays disarmed until the
 *                user explicitly re-arms; motion clearing does not re-arm it.
 *
 * No Android dependencies -> fully unit-testable. Time is passed in as millis.
 */
class RecorderStateMachine(
    private var noMotionTimeoutMs: Long,
    private var minMovementMs: Long = 0L
) {
    enum class State { ARMED, RECORDING, GRACE, DISARMED }

    enum class Action { START_RECORDING, STOP_RECORDING, CANCEL_RECORDING }

    var state: State = State.ARMED
        private set

    /** Absolute millis at which the grace period ends (only meaningful in GRACE). */
    var graceDeadlineMs: Long = 0L
        private set

    /** Cumulative millis that motion was actually present during the current
     *  recording (grace gaps excluded). Decides keep-vs-cancel at finalisation. */
    private var movementMs: Long = 0L

    /** Start of the in-progress motion segment (valid while state == RECORDING). */
    private var segmentStartMs: Long = 0L

    fun setNoMotionTimeout(ms: Long) {
        noMotionTimeoutMs = ms
    }

    /** Minimum cumulative motion required to keep a clip; <= 0 disables the gate. */
    fun setMinMovement(ms: Long) {
        minMovementMs = ms
    }

    /** Millis remaining in the grace countdown, or 0 when not in GRACE. */
    fun graceRemainingMs(nowMs: Long): Long =
        if (state == State.GRACE) (graceDeadlineMs - nowMs).coerceAtLeast(0L) else 0L

    /** Cumulative motion millis so far in the current recording, including the
     *  in-progress segment while motion is present. 0 when not recording. */
    fun currentMovementMs(nowMs: Long): Long = when (state) {
        State.RECORDING -> movementMs + (nowMs - segmentStartMs)
        State.GRACE -> movementMs
        else -> 0L
    }

    /** Report the current motion status. Returns any actions triggered. */
    fun onMotion(present: Boolean, nowMs: Long): List<Action> {
        return when (state) {
            State.ARMED -> if (present) {
                state = State.RECORDING
                movementMs = 0L
                segmentStartMs = nowMs
                listOf(Action.START_RECORDING)
            } else emptyList()

            State.RECORDING -> if (!present) {
                // Bank the motion segment that just ended before pausing into grace.
                movementMs += nowMs - segmentStartMs
                state = State.GRACE
                graceDeadlineMs = nowMs + noMotionTimeoutMs
                emptyList()
            } else emptyList()

            State.GRACE -> if (present) {
                state = State.RECORDING
                segmentStartMs = nowMs
                emptyList()
            } else emptyList()

            // Stays disarmed until the user explicitly re-arms.
            State.DISARMED -> emptyList()
        }
    }

    /** Drives the grace countdown. Call periodically. */
    fun onTick(nowMs: Long): List<Action> {
        if (state == State.GRACE && nowMs >= graceDeadlineMs) {
            state = State.ARMED
            // Keep the clip only if it captured enough cumulative motion; otherwise
            // cancel (discard) it. minMovementMs <= 0 disables the gate.
            val keep = minMovementMs <= 0L || movementMs >= minMovementMs
            return listOf(if (keep) Action.STOP_RECORDING else Action.CANCEL_RECORDING)
        }
        return emptyList()
    }

    /** User pressed stop/disarm. Finalises any recording and disarms. A manual stop
     *  always SAVES whatever was captured (the min-movement gate is not applied). */
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
        movementMs = 0L
    }
}
