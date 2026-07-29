package com.motioncam.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays short distinct audio cues for recording / warning events.
 * Recording start/stop use synthesized two-tone sweeps (rising for start, falling
 * for stop — the conventional "record on / record off" pattern). Warnings use the
 * built-in ToneGenerator patterns.
 */
class Beeper {

    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    private val exec = Executors.newSingleThreadExecutor()

    private val pulseMs = 250
    private val gapMs = 250

    /** Three ascending 250ms pulses: recording started. */
    fun recordingStarted() = playPulses(520, 720, 1000)

    /** Three descending 250ms pulses: recording stopped (motion lost). */
    fun recordingStopped() = playPulses(1000, 720, 520)

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

    // ---- synthesized pulses (pure tones) ----

    private fun playPulses(vararg freqs: Int) {
        exec.execute {
            for ((i, f) in freqs.withIndex()) {
                playTone(f, pulseMs)
                if (i < freqs.size - 1) Thread.sleep(gapMs.toLong())
            }
        }
    }

    private fun playTone(freq: Int, durationMs: Int) {
        val sampleRate = 44100
        val count = sampleRate * durationMs / 1000
        val fade = (sampleRate * 6 / 1000).coerceAtMost(count / 2) // 6ms fade in/out
        val samples = ShortArray(count)
        for (i in 0 until count) {
            var amp = 0.85
            if (i < fade) amp *= i.toDouble() / fade
            else if (i > count - fade) amp *= (count - i).toDouble() / fade
            samples[i] = (sin(2.0 * PI * i * freq / sampleRate) * Short.MAX_VALUE * amp).toInt().toShort()
        }
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep((durationMs + 30).toLong())
            track.stop()
            track.release()
        } catch (_: Exception) {
        }
    }

    // ---- ToneGenerator patterns (warnings) ----

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
