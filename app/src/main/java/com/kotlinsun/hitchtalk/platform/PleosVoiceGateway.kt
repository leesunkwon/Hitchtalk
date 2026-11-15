package com.kotlinsun.hitchtalk.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitializeListener
import com.kotlinsun.hitchtalk.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class VoiceUnavailableException(message: String) : IllegalStateException(message)

/** Pleos-only, half-duplex bridge. Call operations sequentially from the orchestrator. */
class PleosVoiceGateway(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                cancel()
                onAudioInterruption?.invoke()
            }
        }, main)
        .build()
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    private var initialization: CompletableDeferred<Unit>? = null
    private var operation: CompletableDeferred<*>? = null
    private var generation = 0L
    private var player: MediaPlayer? = null
    private var released = false
    private val operationMutex = Mutex()

    val rateSupported: Boolean = false
    var onAudioInterruption: (() -> Unit)? = null
    var onListeningStarted: (() -> Unit)? = null
    var onSpeechActivity: (() -> Unit)? = null
    var onSpeechSegment: ((Boolean) -> Unit)? = null
    var onRecognitionEvent: ((String) -> Unit)? = null

    suspend fun initialize(): Unit = withContext(Dispatchers.Main.immediate) {
        check(!released) { "Voice gateway has been released" }
        initialization?.let { withTimeout(8_000) { it.await() }; return@withContext }
        val ready = CompletableDeferred<Unit>()
        initialization = ready
        try {
            val speakerReady = CompletableDeferred<Unit>()
            val listenerReady = CompletableDeferred<Unit>()
            val speaker = TextToSpeech(context, TtsMode.ON_DEVICE)
            val listener = SpeechToText(context, SttMode.ON_DEVICE)
            tts = speaker
            stt = listener
            speaker.initialize(object : TtsInitializeListener {
                override fun onSuccess() {
                    if (released || tts !== speaker) return
                    runCatching {
                        speaker.setLocale(Locale.US)
                        speaker.setAudioAttributes(attributes)
                    }.fold(
                        onSuccess = { speakerReady.complete(Unit) },
                        onFailure = { speakerReady.completeExceptionally(it) },
                    )
                }
                override fun onFailure(errorType: TtsError) {
                    speakerReady.completeExceptionally(VoiceUnavailableException("Pleos TTS 연결 실패: ${errorType.name}"))
                }
            })
            listener.initialize(object : SttInitializeListener {
                override fun onSuccess() {
                    if (released || stt !== listener) return
                    runCatching { listener.setLocale(Locale.US) }.fold(
                        onSuccess = { listenerReady.complete(Unit) },
                        onFailure = { listenerReady.completeExceptionally(it) },
                    )
                }
                override fun onFailure(errorType: SttError) {
                    listenerReady.completeExceptionally(VoiceUnavailableException("Pleos STT 연결 실패: ${errorType.name}"))
                }
            })
            withTimeout(8_000) {
                speakerReady.await()
                listenerReady.await()
            }
            ready.complete(Unit)
        } catch (error: Exception) {
            ready.completeExceptionally(error)
            initialization = null
            runCatching { tts?.release() }
            runCatching { stt?.release() }
            tts = null
            stt = null
            throw error
        }
    }

    suspend fun speak(text: String, rate: Float = 1f) = withContext(Dispatchers.Main.immediate) {
        operationMutex.withLock {
            require(rate == 1f) { "Pleos TTS 3.3.1 does not expose a speech rate API" }
            initialize()
            check(operation == null) { "Voice operations must be sequential" }
            requestFocus()
            val requestGeneration = ++generation
            val done = CompletableDeferred<Unit>()
            operation = done
            val speaker = checkNotNull(tts)
            val events = object : EventListener {
                override fun onReady() = Unit
                override fun onStart() = Unit
                override fun onUpdatedRms(rms: Double) = Unit
                override fun onDone() {
                    if (generation == requestGeneration) done.complete(Unit)
                }
                override fun onStop() {
                    if (generation == requestGeneration) done.cancel()
                }
                override fun onError(errorType: TtsError) {
                    if (generation == requestGeneration) {
                        done.completeExceptionally(VoiceUnavailableException("Pleos TTS 오류: ${errorType.name}"))
                    }
                }
            }
            try {
                speaker.addEventListener(events)
                speaker.speak(text.take(700), true)
                withTimeout(30_000) { done.await() }
            } finally {
                speaker.removeEventListener(events)
                if (operation === done) operation = null
                runCatching { speaker.stop() }
                abandonFocus()
            }
        }
    }

    suspend fun listen(): String? = withContext(Dispatchers.Main.immediate) {
        operationMutex.withLock {
            initialize()
            check(operation == null) { "Voice operations must be sequential" }
            requestFocus()
            val requestGeneration = ++generation
            val result = CompletableDeferred<String?>()
            operation = result
            val recognizer = checkNotNull(stt)
            var segmentActive = false
            val events = object : ResultListener {
                override fun onReady() { recognitionEvent("ready") }
                override fun onStartedRecognition() {
                    recognitionEvent("started")
                    main.post {
                        if (generation == requestGeneration && operation === result && !result.isCompleted) {
                            // SDK 3.3.1 emits this callback for EPD_ON.
                            segmentActive = true
                            onSpeechSegment?.invoke(true)
                            onListeningStarted?.invoke()
                        }
                    }
                }
                override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                override fun onUpdatedRms(rms: Double) = Unit
                override fun onUpdated(stt: String, completed: Boolean) {
                    val text = stt.trim().take(700)
                    if (text.isNotEmpty() || completed) main.post {
                        if (generation != requestGeneration || operation !== result || result.isCompleted) return@post
                        if (text.isNotEmpty()) onSpeechActivity?.invoke()
                        if (completed) result.complete(text.ifBlank { null })
                    }
                }
                override fun onEndedRecognition() {
                    recognitionEvent("ended")
                    main.post {
                        if (generation == requestGeneration && operation === result && segmentActive) {
                            segmentActive = false
                            onSpeechSegment?.invoke(false)
                        }
                    }
                    // In SDK 3.3.1 this is EPD_OFF, not completion of the final ASR result.
                    // Keep awaiting onUpdated(completed=true) to avoid dropping the user's turn.
                }
                override fun onError(errorType: SttError) {
                    recognitionEvent("error_${errorType.name}")
                    if (generation == requestGeneration) {
                        result.completeExceptionally(VoiceUnavailableException("Pleos STT 오류: ${errorType.name}"))
                    }
                }
            }
            try {
                recognizer.addListener(events)
                recognizer.request()
                withTimeout(25_000) { result.await() }
            } finally {
                if (segmentActive) onSpeechSegment?.invoke(false)
                recognizer.removeListener(events)
                if (operation === result) operation = null
                runCatching { recognizer.stop() }
                abandonFocus()
            }
        }
    }

    suspend fun playDoor(open: Boolean) = withContext(Dispatchers.Main.immediate) {
        operationMutex.withLock {
            check(!released)
            check(operation == null) { "Voice operations must be sequential" }
            requestFocus()
            // Original synthesized sound effects, 0.8 s opening and 0.65 s closing.
            val audio = MediaPlayer.create(
                context, if (open) R.raw.door_open else R.raw.door_close, attributes, 0,
            )
            if (audio == null) {
                abandonFocus()
                throw VoiceUnavailableException("문 효과음을 열 수 없어요")
            }
            val done = CompletableDeferred<Unit>()
            operation = done
            player = audio
            try {
                audio.setOnCompletionListener { done.complete(Unit) }
                audio.setOnErrorListener { _, _, _ ->
                    done.completeExceptionally(VoiceUnavailableException("문 효과음을 재생할 수 없어요"))
                    true
                }
                audio.start()
                withTimeout(3_000) { done.await() }
            } finally {
                if (operation === done) operation = null
                if (player === audio) player = null
                audio.release()
                abandonFocus()
            }
        }
    }

    fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cancel() }
            return
        }
        generation++
        operation?.cancel(CancellationException("Voice operation interrupted"))
        operation = null
        runCatching { stt?.stop() }
        runCatching { tts?.stop() }
        runCatching { player?.stop() }
        abandonFocus()
    }

    fun release() {
        if (released) return
        cancel()
        released = true
        initialization?.cancel()
        runCatching { stt?.release() }
        runCatching { tts?.release() }
        stt = null
        tts = null
        onListeningStarted = null
        onSpeechActivity = null
        onSpeechSegment = null
        onAudioInterruption = null
        onRecognitionEvent = null
    }

    private fun requestFocus() {
        if (audioManager?.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw VoiceUnavailableException("다른 오디오가 재생 중이에요")
        }
    }

    private fun abandonFocus() {
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    private fun recognitionEvent(event: String) {
        main.post { if (!released) onRecognitionEvent?.invoke(event) }
    }
}
