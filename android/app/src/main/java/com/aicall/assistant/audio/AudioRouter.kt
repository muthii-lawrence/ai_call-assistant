package com.aicall.assistant.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Module 2 of the doc. Two responsibilities:
 *  1. Gate which audio path is live: AI-mode (caller <-> backend TTS/STT)
 *     vs human-mode (caller <-> phone mic/speaker directly).
 *  2. Guarantee no leak: whichever mode is NOT active must be fully muted,
 *     both directions.
 *
 * IMPORTANT (doc section 2.3 / Phase 0):
 * Raw call-audio capture via a VOICE_CALL-equivalent source is restricted
 * or unavailable on many OEM ROMs, Samsung and Vivo included, and its
 * availability is inconsistent across Android versions. The exact API you
 * end up using here (VOICE_CALL audio source vs. an InCallService audio
 * routing trick vs. a vendor-specific accessory route) can only be
 * determined by testing on your real Samsung and Vivo devices -- do that
 * BEFORE building on top of this class. Wherever this file assumes an
 * audio source is available, treat it as the thing to validate first.
 */
object AudioRouter {

    const val CELLULAR_CALL_MEDIA_BRIDGE_AVAILABLE = false

    private var audioManager: AudioManager? = null
    private var currentMode: Mode = Mode.NONE
    private var capture: AudioRecord? = null
    private var playback: AudioTrack? = null
    private var captureThread: Thread? = null
    private val captureRunning = AtomicBoolean(false)

    enum class Mode { NONE, AI, HUMAN }

    private fun manager(context: Context): AudioManager {
        return audioManager ?: (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .also { audioManager = it }
    }

    /** AI is live: caller hears only synthesized TTS, nothing from the phone mic/room. */
    fun routeToAi(context: Context, onAudioFrame: ((ByteArray) -> Unit)? = null) {
        stopPipelines()
        val am = manager(context)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        currentMode = Mode.AI
        if (onAudioFrame != null) startCapture(onAudioFrame)
    }

    /** Rule 2: user has taken over. AI must not hear or speak anything further. */
    fun routeToHuman(context: Context) {
        stopPipelines()
        val am = manager(context)
        am.mode = AudioManager.MODE_IN_CALL
        currentMode = Mode.HUMAN
    }

    fun stop(context: Context) {
        stopPipelines()
        currentMode = Mode.NONE
    }

    fun setMicMuted(context: Context, muted: Boolean) {
        manager(context).isMicrophoneMute = muted
    }

    fun setSpeaker(context: Context, enabled: Boolean) {
        manager(context).isSpeakerphoneOn = enabled
    }

    fun playAiAudio(pcm: ByteArray) {
        if (currentMode != Mode.AI) return
        val track = playback ?: AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.play()
                playback = it
            }
        track.write(pcm, 0, pcm.size)
    }

    private fun startCapture(onAudioFrame: (ByteArray) -> Unit) {
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                16_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_CALL,
                16_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
            capture = record
            record.startRecording()
            captureRunning.set(true)
            captureThread = Thread {
                val buffer = ByteArray(minBuffer)
                while (captureRunning.get()) {
                    val count = record.read(buffer, 0, buffer.size)
                    if (count > 0 && currentMode == Mode.AI) {
                        onAudioFrame(buffer.copyOf(count))
                    }
                }
            }.also { it.start() }
        } catch (_: SecurityException) {
            stopPipelines()
        } catch (_: IllegalArgumentException) {
            stopPipelines()
        }
    }

    private fun stopPipelines() {
        captureRunning.set(false)
        capture?.let {
            try { it.stop() } catch (_: IllegalStateException) { }
            it.release()
        }
        capture = null
        captureThread = null
        playback?.let {
            try { it.stop() } catch (_: IllegalStateException) { }
            it.release()
        }
        playback = null
    }
}
