package com.motioncam.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import com.motioncam.R
import java.util.concurrent.Executors

/**
 * Plays audio cues. Recording start/stop play the user-supplied WAV clips
 * (res/raw) via SoundPool; storage/battery warnings use ToneGenerator patterns.
 */
class Beeper(context: Context) {

    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    private val exec = Executors.newSingleThreadExecutor()

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val startSound = soundPool.load(context, R.raw.start_record, 1)
    private val endSound = soundPool.load(context, R.raw.end, 1)

    /** Recording started — plays start_record.wav. */
    fun recordingStarted() {
        soundPool.play(startSound, 1f, 1f, 1, 0, 1f)
    }

    /** Recording stopped (motion lost) — plays end.wav. */
    fun recordingStopped() {
        soundPool.play(endSound, 1f, 1f, 1, 0, 1f)
    }

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
            soundPool.release()
        } catch (_: Exception) {
        }
    }
}
