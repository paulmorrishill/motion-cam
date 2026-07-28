package com.motioncam.util

import android.media.AudioManager
import android.media.ToneGenerator
import java.util.concurrent.Executors

/**
 * Plays short distinct audio cues for recording / warning events.
 * Each cue is a recognisably different pattern so they can be told apart by ear.
 */
class Beeper {

    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    private val exec = Executors.newSingleThreadExecutor()

    /** Rising single beep — recording started. */
    fun recordingStarted() = sequence(
        Step(ToneGenerator.TONE_PROP_BEEP, 150)
    )

    /** Descending double beep — recording stopped (motion lost). */
    fun recordingStopped() = sequence(
        Step(ToneGenerator.TONE_SUP_INTERCEPT_ABBREV, 180),
        Step(ToneGenerator.TONE_CDMA_LOW_L, 220, gapAfter = 60)
    )

    /** Three quick mid beeps — storage low. */
    fun storageLow() = sequence(
        Step(ToneGenerator.TONE_PROP_ACK, 120, gapAfter = 90),
        Step(ToneGenerator.TONE_PROP_ACK, 120, gapAfter = 90),
        Step(ToneGenerator.TONE_PROP_ACK, 120)
    )

    /** Two long low beeps — battery low. */
    fun batteryLow() = sequence(
        Step(ToneGenerator.TONE_SUP_ERROR, 300, gapAfter = 120),
        Step(ToneGenerator.TONE_SUP_ERROR, 300)
    )

    private data class Step(val tone: Int, val durationMs: Int, val gapAfter: Int = 0)

    private fun sequence(vararg steps: Step) {
        exec.execute {
            for (s in steps) {
                try {
                    tone.startTone(s.tone, s.durationMs)
                    Thread.sleep((s.durationMs + s.gapAfter).toLong())
                } catch (_: Exception) {
                }
            }
        }
    }

    fun release() {
        try {
            exec.shutdownNow()
            tone.release()
        } catch (_: Exception) {
        }
    }
}
