package com.kotlinsun.hitchtalk.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.isInvisible
import com.kotlinsun.hitchtalk.R
import com.kotlinsun.hitchtalk.databinding.DialogMicrophoneTestBinding
import java.io.File

/** A short, foreground-only recording that is discarded when this dialog closes. */
internal class MicrophoneTestDialog(
    context: Context,
    private val canTest: () -> Boolean,
) : AlertDialog(context) {
    private enum class Phase { IDLE, RECORDING, READY, PREPARING, PLAYING }

    private val binding = DialogMicrophoneTestBinding.inflate(layoutInflater)
    private val main = Handler(Looper.getMainLooper())
    private val recordingFile = File(context.cacheDir, "microphone-test.m4a")
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var phase = Phase.IDLE
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var startedAt = 0L
    private var heardInput = false
    private var closed = false
    private var hadWindowFocus = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change < 0 && !closed && (recorder != null || player != null)) {
                fail(R.string.microphone_test_interrupted)
            }
        }, main)
        .build()

    private val updateMeter = object : Runnable {
        override fun run() {
            if (closed || phase != Phase.RECORDING) return
            if (!canTest()) {
                dismiss()
                return
            }
            val elapsedMillis = SystemClock.elapsedRealtime() - startedAt
            if (elapsedMillis >= 10_000) {
                stopRecording()
                return
            }
            try {
                val amplitude = recorder?.maxAmplitude ?: 0
                heardInput = heardInput || amplitude > 200
                binding.inputLevel.progress = amplitude
                val seconds = (elapsedMillis / 1_000).toInt()
                binding.elapsed.text = context.getString(R.string.microphone_test_elapsed, seconds)
                main.postDelayed(this, 100)
            } catch (_: RuntimeException) {
                fail(R.string.microphone_test_record_failed)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        recordingFile.delete()
        setTitle(R.string.microphone_test_title)
        setView(binding.root)
        setButton(BUTTON_NEGATIVE, context.getString(R.string.microphone_test_close)) { _, _ -> dismiss() }
        super.onCreate(savedInstanceState)
        binding.emulatorHint.isVisible = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
        binding.record.setOnClickListener {
            if (phase == Phase.RECORDING) stopRecording() else startRecording()
        }
        binding.play.setOnClickListener {
            if (phase == Phase.PLAYING || phase == Phase.PREPARING) stopPlayback() else startPlayback()
        }
        render(R.string.microphone_test_ready)
    }

    private fun startRecording() {
        if (closed || !canTest() || phase == Phase.PREPARING || phase == Phase.PLAYING) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(R.string.microphone_test_permission_required)
            return
        }
        if (!requestFocus()) return
        recordingFile.delete()
        heardInput = false
        try {
            @Suppress("DEPRECATION")
            val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            recorder = next
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioChannels(1)
            next.setAudioSamplingRate(16_000)
            next.setAudioEncodingBitRate(64_000)
            next.setMaxDuration(10_000)
            next.setOutputFile(recordingFile.absolutePath)
            next.setOnInfoListener { current, what, _ ->
                if (recorder === current && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    stopRecording()
                }
            }
            next.setOnErrorListener { current, _, _ ->
                if (recorder === current) fail(R.string.microphone_test_record_failed)
            }
            next.prepare()
            next.start()
            startedAt = SystemClock.elapsedRealtime()
            phase = Phase.RECORDING
            render(R.string.microphone_test_recording)
            main.post(updateMeter)
        } catch (_: Exception) {
            fail(R.string.microphone_test_record_failed)
        }
    }

    private fun stopRecording() {
        if (phase != Phase.RECORDING) return
        main.removeCallbacks(updateMeter)
        val current = recorder ?: return
        recorder = null
        val saved = try {
            current.stop()
            recordingFile.length() > 0
        } catch (_: RuntimeException) {
            false
        } finally {
            runCatching { current.release() }
            abandonFocus()
        }
        if (!saved) {
            fail(R.string.microphone_test_record_failed)
            return
        }
        phase = Phase.READY
        render(if (heardInput) R.string.microphone_test_recorded else R.string.microphone_test_no_input)
    }

    private fun startPlayback() {
        if (closed || !canTest() || phase != Phase.READY) return
        if (!requestFocus()) return
        try {
            val next = MediaPlayer()
            player = next
            phase = Phase.PREPARING
            next.setAudioAttributes(attributes)
            next.setDataSource(recordingFile.absolutePath)
            next.setOnPreparedListener { current ->
                if (player !== current || closed) return@setOnPreparedListener
                if (!canTest()) {
                    dismiss()
                } else {
                    try {
                        current.start()
                        phase = Phase.PLAYING
                        render(R.string.microphone_test_playing)
                    } catch (_: RuntimeException) {
                        fail(R.string.microphone_test_play_failed)
                    }
                }
            }
            next.setOnCompletionListener { current -> if (player === current) stopPlayback() }
            next.setOnErrorListener { current, _, _ ->
                if (player === current) fail(R.string.microphone_test_play_failed)
                true
            }
            render(R.string.microphone_test_preparing)
            next.prepareAsync()
        } catch (_: Exception) {
            fail(R.string.microphone_test_play_failed)
        }
    }

    private fun stopPlayback() {
        val current = player
        player = null
        runCatching { current?.release() }
        abandonFocus()
        phase = Phase.READY
        render(if (heardInput) R.string.microphone_test_recorded else R.string.microphone_test_no_input)
    }

    private fun requestFocus(): Boolean {
        return try {
            if (audioManager?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) true
            else {
                fail(R.string.microphone_test_audio_busy)
                false
            }
        } catch (_: RuntimeException) {
            fail(R.string.microphone_test_audio_busy)
            false
        }
    }

    private fun abandonFocus() {
        runCatching { audioManager?.abandonAudioFocusRequest(focusRequest) }
    }

    private fun releaseOperations() {
        main.removeCallbacks(updateMeter)
        val recording = recorder
        recorder = null
        runCatching { recording?.stop() }
        runCatching { recording?.release() }
        val playing = player
        player = null
        runCatching { playing?.release() }
        abandonFocus()
    }

    private fun fail(@StringRes message: Int) {
        if (closed) return
        releaseOperations()
        recordingFile.delete()
        phase = Phase.IDLE
        render(message)
    }

    private fun render(@StringRes message: Int) = with(binding) {
        status.setText(message)
        status.seedCallout(if (message == R.string.microphone_test_no_input) SeedTone.WARNING else SeedTone.NEUTRAL)
        elapsed.isInvisible = phase != Phase.RECORDING
        inputLevel.isInvisible = phase != Phase.RECORDING
        if (phase != Phase.RECORDING) inputLevel.progress = 0
        record.setText(when (phase) {
            Phase.RECORDING -> R.string.microphone_test_stop_recording
            Phase.READY -> R.string.microphone_test_record_again
            else -> R.string.microphone_test_start_recording
        })
        record.isEnabled = canTest() && phase != Phase.PREPARING && phase != Phase.PLAYING
        play.setText(if (phase == Phase.PREPARING || phase == Phase.PLAYING) {
            R.string.microphone_test_stop_playing
        } else R.string.microphone_test_play)
        play.isEnabled = canTest() && phase in setOf(Phase.READY, Phase.PREPARING, Phase.PLAYING)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hadWindowFocus = true
        else if (hadWindowFocus && !closed && isShowing) dismiss()
    }

    override fun onStop() {
        closed = true
        releaseOperations()
        recordingFile.delete()
        super.onStop()
    }
}
