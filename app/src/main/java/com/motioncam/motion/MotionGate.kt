package com.motioncam.motion

/**
 * Temporal debounce over raw per-frame motion decisions from [MotionDetector].
 *
 * The raw detector fires on a single frame, so in low light a sensor-noise spike (or
 * one flickering frame) reads as "motion" and — with only a hold timer downstream —
 * was enough to start a recording, producing spurious record/stop/record loops with
 * nothing actually moving.
 *
 * This gate only reports motion "present" once raw motion has been **sustained** for
 * [onsetMs] (a run of motion frames with no gap longer than [gapMs]); a lone spike or
 * a couple of flickers never reaches the onset and is ignored. Once confirmed, motion
 * stays present until [holdMs] elapses with no further motion frame (falling-edge
 * smoothing, unchanged in spirit from before).
 *
 * Pure Kotlin, single-threaded (call from one analysis thread), fully unit-testable.
 *
 * @param onsetMs how long raw motion must persist before it counts as present.
 * @param holdMs  how long motion stays present after the last motion frame.
 * @param gapMs   a gap between motion frames longer than this breaks the current run.
 */
class MotionGate(
    private val onsetMs: Long = 400,
    private val holdMs: Long = 800,
    private val gapMs: Long = 350
) {
    // Far in the past so nothing is "present" until a real motion frame arrives.
    private var lastMotionMs = -10_000_000L
    private var runStartMs = 0L

    /** Feed one frame's raw motion decision. Returns the debounced "present" state. */
    fun onFrame(motion: Boolean, nowMs: Long): Boolean {
        if (motion) {
            if (nowMs - lastMotionMs > gapMs) runStartMs = nowMs // gap broke the run
            lastMotionMs = nowMs
        }
        return present(nowMs)
    }

    /** Re-evaluate present (for the periodic tick / falling-edge decay) with no new frame. */
    fun present(nowMs: Long): Boolean {
        val recent = (nowMs - lastMotionMs) < holdMs
        val sustained = (lastMotionMs - runStartMs) >= onsetMs
        return recent && sustained
    }

    fun reset() {
        lastMotionMs = -10_000_000L
        runStartMs = 0L
    }
}
