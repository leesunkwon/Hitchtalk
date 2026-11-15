package com.kotlinsun.hitchtalk

import android.os.SystemClock
import com.kotlinsun.hitchtalk.ai.OpenAiConversationClient
import com.kotlinsun.hitchtalk.ai.OpenAiException
import com.kotlinsun.hitchtalk.ai.OpenAiFailure
import com.kotlinsun.hitchtalk.data.TravelerProfile
import com.kotlinsun.hitchtalk.data.UserPreferences
import com.kotlinsun.hitchtalk.domain.DialogueRequest
import com.kotlinsun.hitchtalk.domain.DialogueTurn
import com.kotlinsun.hitchtalk.domain.EnglishScores
import com.kotlinsun.hitchtalk.domain.ProactiveContextPolicy
import com.kotlinsun.hitchtalk.domain.ScoreCalculator
import com.kotlinsun.hitchtalk.domain.SessionAnalysis
import com.kotlinsun.hitchtalk.domain.SilencePromptPolicy
import com.kotlinsun.hitchtalk.domain.SummaryRequest
import com.kotlinsun.hitchtalk.domain.TurnRole
import com.kotlinsun.hitchtalk.domain.VoiceCommand
import com.kotlinsun.hitchtalk.platform.PleosVoiceGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

enum class VoicePhase {
    IDLE, BOARDING, GREETING, LISTENING, THINKING, SPEAKING,
    PAUSED, SAFETY_MUTED, DROPPING_OFF, REPORTING, ERROR
}

data class ConversationStatus(
    val phase: VoicePhase = VoicePhase.IDLE,
    val lastUtterance: String = "",
    val error: Boolean = false,
    val dropoffPending: Boolean = false,
    val safetySettled: Boolean = false,
)

data class CompletedConversation(
    val id: String,
    val traveler: Traveler,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val scores: EnglishScores,
    val analysis: SessionAnalysis?,
    val contextSources: List<String>,
)

class ConversationOrchestrator(
    private val scope: CoroutineScope,
    private val voice: PleosVoiceGateway,
    private val ai: OpenAiConversationClient,
    private val onStatus: (ConversationStatus) -> Unit,
    private val onCompleted: (CompletedConversation) -> Unit,
    private val onAiAuthenticationFailure: () -> Unit = {},
) {
    private var safety = ConversationSafety()
    private var status = ConversationStatus()
    private var activeJob: Job? = null
    private var resumeJob: Job? = null
    private var generation = 0L
    private var sessionId: String? = null
    private var traveler = Traveler.ALEX
    private var preferences = UserPreferences()
    private var profile: TravelerProfile? = null
    private var boarded = false
    private var pausedByUser = false
    private var aiAuthenticationFailed = false
    private var startedAt = 0L
    private var activeMillis = 0L
    private var phaseStartedAt = 0L
    private val proactiveContext = ProactiveContextPolicy()
    private val contextSources = mutableSetOf<String>()
    private val turns = mutableListOf<DialogueTurn>()
    private val silencePolicy = SilencePromptPolicy()
    private var heardSpeech = false
    private var clarifiedOnce = false

    init {
        voice.onSpeechActivity = {
            if (status.phase == VoicePhase.LISTENING && safety.canTalk && !pausedByUser) {
                heardSpeech = true
                silencePolicy.onSpeechActivity(SystemClock.elapsedRealtime())
            }
        }
        voice.onSpeechSegment = { active ->
            if (status.phase == VoicePhase.LISTENING && safety.canTalk && !pausedByUser) {
                if (active) heardSpeech = true
                silencePolicy.onSpeechSegment(active, SystemClock.elapsedRealtime())
            }
        }
    }

    fun updateSafety(next: ConversationSafety, vehicleFacts: Map<String, String>) {
        safety = next
        proactiveContext.observeVehicleFacts(vehicleFacts, SystemClock.elapsedRealtime())
        if (sessionId == null || status.phase == VoicePhase.REPORTING) return
        val parkedOnly = !boarded || status.dropoffPending
        if (!next.canTalk || parkedOnly && !next.canInteract) {
            resumeJob?.cancel()
            resumeJob = null
            if (status.phase == VoicePhase.SAFETY_MUTED) {
                if (status.safetySettled) transition(VoicePhase.SAFETY_MUTED, dropoffPending = status.dropoffPending)
                return
            }
            val droppingOff = status.phase == VoicePhase.DROPPING_OFF || status.dropoffPending
            invalidate()
            if (!boarded) {
                clearSession()
                transition(VoicePhase.IDLE)
            } else {
                transition(VoicePhase.SAFETY_MUTED, dropoffPending = droppingOff)
            }
        } else if (status.phase == VoicePhase.SAFETY_MUTED && !status.safetySettled && resumeJob?.isActive != true) {
            scheduleResume()
        }
    }

    fun board(selected: Traveler, options: UserPreferences, history: TravelerProfile?): Boolean {
        if (!safety.canTalk || !safety.canInteract || sessionId != null) return false
        traveler = selected
        preferences = options
        profile = history
        sessionId = UUID.randomUUID().toString()
        startedAt = System.currentTimeMillis()
        activeMillis = 0L
        turns.clear()
        contextSources.clear()
        pausedByUser = false
        aiAuthenticationFailed = false
        boarded = false
        silencePolicy.reset()
        clarifiedOnce = false
        startBoarding()
        return true
    }

    private fun startBoarding() {
        invalidate()
        val token = generation
        transition(VoicePhase.BOARDING)
        activeJob = scope.launch {
            try {
                voice.initialize()
                ensureAllowed(token, parked = true)
                val doorStartedAt = SystemClock.elapsedRealtime()
                voice.playDoor(open = true)
                delay((2_000 - (SystemClock.elapsedRealtime() - doorStartedAt)).coerceAtLeast(0))
                ensureAllowed(token, parked = true)
                voice.playDoor(open = false)
                ensureAllowed(token, parked = true)
                boarded = true
                if (preferences.greetingEnabled) {
                    transition(VoicePhase.GREETING)
                    speak(
                        if ((profile?.meetingCount ?: 0) > 0) {
                            "Hi, it's great to see you again! How have you been?"
                        } else {
                            "Hi! I'm ${traveler.displayName}. Thanks for the ride!"
                        },
                        token,
                    )
                }
                conversationLoop(token)
            } catch (cancelled: CancellationException) {
                if (token == generation) transition(VoicePhase.ERROR, error = true)
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) transition(VoicePhase.ERROR, error = true)
            }
        }
    }

    fun togglePause() {
        if (!safety.canInteract || !boarded || sessionId == null ||
            status.phase !in setOf(VoicePhase.LISTENING, VoicePhase.THINKING, VoicePhase.SPEAKING, VoicePhase.PAUSED)) return
        if (pausedByUser) {
            pausedByUser = false
            resume()
        } else {
            pausedByUser = true
            invalidate()
            transition(VoicePhase.PAUSED)
            listenForResume()
        }
    }

    private fun listenForResume() {
        if (!safety.canTalk || !boarded) return
        transition(VoicePhase.PAUSED)
        val token = generation
        activeJob = scope.launch {
            try {
                while (isActive && pausedByUser) {
                    val text = withTimeoutOrNull(18_000) { voice.listen() }.orEmpty()
                    ensureAllowed(token)
                    if (VoiceCommand.detect(text) == VoiceCommand.RESUME) {
                        pausedByUser = false
                        resume()
                        return@launch
                    }
                    delay(400)
                }
            } catch (cancelled: CancellationException) {
                if (token == generation) transition(VoicePhase.ERROR, error = true)
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) transition(VoicePhase.ERROR, error = true)
            }
        }
    }

    fun retry() {
        if (!safety.canTalk || sessionId == null || status.phase != VoicePhase.ERROR) return
        if (status.dropoffPending) dropOff()
        else if (aiAuthenticationFailed) return
        else if (!boarded) startBoarding()
        else if (pausedByUser) listenForResume()
        else resume()
    }

    private fun scheduleResume() {
        resumeJob?.cancel()
        val token = generation
        resumeJob = scope.launch {
            delay(3_000)
            if (token != generation || !safety.canTalk || sessionId == null || status.phase != VoicePhase.SAFETY_MUTED) {
                return@launch
            }
            if (status.dropoffPending) {
                if (safety.canInteract) transition(VoicePhase.SAFETY_MUTED, dropoffPending = true, safetySettled = true)
            } else if (aiAuthenticationFailed) {
                transition(VoicePhase.ERROR, error = true)
            } else {
                if (pausedByUser) listenForResume() else resume()
            }
        }
    }

    private fun resume() {
        if (!safety.canTalk || !boarded || pausedByUser || status.dropoffPending || aiAuthenticationFailed) return
        invalidate()
        val token = generation
        activeJob = scope.launch {
            try {
                conversationLoop(token)
            } catch (cancelled: CancellationException) {
                if (token == generation) transition(VoicePhase.ERROR, error = true)
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) transition(VoicePhase.ERROR, error = true)
            }
        }
    }

    private suspend fun conversationLoop(token: Long) {
        var failures = 0
        while (sessionId != null && !pausedByUser) {
            ensureAllowed(token)
            transition(VoicePhase.LISTENING)
            heardSpeech = false
            val result = listenForTurn(token)
            val input = result.text
            ensureAllowed(token)
            if (input.isBlank()) {
                if (result.unclear) {
                    silencePolicy.waitForClearTurn()
                    if (!clarifiedOnce) {
                        clarifiedOnce = true
                        val prompt = "Sorry, could you say that once more?"
                        transition(VoicePhase.SPEAKING, prompt)
                        speak(prompt, token)
                    }
                } else if (!safety.requiresDrivingUi &&
                    silencePolicy.delayUntilPrompt(SystemClock.elapsedRealtime()) == 0L
                ) {
                    requestReply(token, proactive = true)
                    silencePolicy.onPromptFinished(SystemClock.elapsedRealtime())
                }
                delay(400)
                continue
            }
            silencePolicy.onUserTurn(SystemClock.elapsedRealtime())
            clarifiedOnce = false
            if (VoiceCommand.detect(input) == VoiceCommand.PAUSE) {
                pausedByUser = true
                transition(VoicePhase.PAUSED)
                listenForResume()
                return
            }
            turns += DialogueTurn(UUID.randomUUID().toString(), TurnRole.USER, input.take(1_000))
            if (requestReply(token, proactive = false)) failures = 0 else failures++
            if (failures >= 2) {
                transition(VoicePhase.ERROR, error = true)
                return
            }
        }
    }

    private data class ListeningResult(val text: String = "", val unclear: Boolean = false)

    private suspend fun listenForTurn(token: Long): ListeningResult = coroutineScope {
        val listening = async {
            try {
                val text = voice.listen()?.trim().orEmpty()
                ListeningResult(text, unclear = text.isBlank() && heardSpeech)
            } catch (_: TimeoutCancellationException) {
                // A quiet recognition session ending is not a failed conversation.
                ListeningResult(unclear = heardSpeech)
            }
        }
        try {
            while (!listening.isCompleted) {
                ensureAllowed(token)
                if (safety.requiresDrivingUi) return@coroutineScope listening.await()
                val remaining = silencePolicy.delayUntilPrompt(SystemClock.elapsedRealtime())
                    ?: return@coroutineScope listening.await()
                if (remaining == 0L) return@coroutineScope ListeningResult()
                // Timing out the await leaves recognition running, so a recent partial result
                // can move the quiet deadline without canceling the current utterance.
                withTimeoutOrNull(remaining) { listening.await() }?.let { return@coroutineScope it }
            }
            listening.await()
        } finally {
            listening.cancelAndJoin()
        }
    }

    private suspend fun requestReply(token: Long, proactive: Boolean): Boolean {
        transition(VoicePhase.THINKING)
        return try {
            ensureAllowed(token)
            val sentFacts = if (proactive) {
                proactiveContext.select(SystemClock.elapsedRealtime(), localTimeFact())
            } else emptyMap()
            val memory = profile?.memory
            val confirmed = listOfNotNull(
                memory?.userJob?.takeIf(String::isNotBlank)?.let { "User's job: $it" },
                memory?.userLikes?.takeIf(List<String>::isNotEmpty)?.joinToString()?.let { "User's likes: $it" },
                memory?.lastPromise?.takeIf(String::isNotBlank)?.let { "Previous promise: $it" },
            )
            val reply = ai.reply(
                DialogueRequest(
                    personaId = traveler.personaId,
                    turns = turns.toList(),
                    difficulty = preferences.englishLevel,
                    intimacyLevel = profile?.level ?: "acquaintance",
                    confirmedMemories = confirmed,
                    vehicleFacts = sentFacts,
                    proactive = proactive,
                )
            )
            ensureAllowed(token)
            contextSources += sentFacts.keys
            transition(VoicePhase.SPEAKING, reply.replyEn)
            speak(reply.replyEn, token)
            ensureAllowed(token)
            turns += DialogueTurn(UUID.randomUUID().toString(), TurnRole.ASSISTANT, reply.replyEn)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is OpenAiException && error.kind == OpenAiFailure.AUTHENTICATION) {
                if (token == generation) {
                    aiAuthenticationFailed = true
                    onAiAuthenticationFailure()
                }
                throw error
            }
            if (token == generation) {
                transition(VoicePhase.LISTENING, error = true)
            }
            false
        }
    }

    private fun localTimeFact(): String {
        val local = LocalDateTime.now()
        val dayPart = when (local.hour) {
            in 5..11 -> "morning"
            in 12..17 -> "afternoon"
            in 18..21 -> "evening"
            else -> "night"
        }
        return "${local.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)} $dayPart"
    }

    fun dropOff() {
        if (!safety.canInteract || !safety.canTalk || sessionId == null || status.phase == VoicePhase.REPORTING) return
        if (status.phase == VoicePhase.SAFETY_MUTED && !status.safetySettled) return
        if (!boarded) {
            abort()
            return
        }
        invalidate()
        val token = generation
        val id = checkNotNull(sessionId)
        transition(VoicePhase.DROPPING_OFF, dropoffPending = true)
        val duration = (activeMillis / 1_000).toInt()
        activeJob = scope.launch {
            try {
                voice.playDoor(open = true)
                ensureAllowed(token, parked = true)
                speak("Thanks for the ride. See you next time!", token, parked = true)
                ensureAllowed(token, parked = true)
                voice.playDoor(open = false)
                ensureAllowed(token, parked = true)
                transition(VoicePhase.REPORTING)
                val endedAt = System.currentTimeMillis()
                val analysis = try {
                    if (aiAuthenticationFailed || ScoreCalculator.userTurns(turns).isEmpty()) null
                    else ai.summarize(SummaryRequest(traveler.personaId, turns.toList(), duration))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (token != generation) return@launch
                val result = CompletedConversation(
                    id, traveler, startedAt, endedAt,
                    ScoreCalculator.calculate(turns, duration, analysis),
                    analysis, contextSources.toList(),
                )
                clearSession()
                onCompleted(result)
            } catch (cancelled: CancellationException) {
                if (token == generation) transition(VoicePhase.ERROR, error = true, dropoffPending = true)
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) transition(VoicePhase.ERROR, error = true, dropoffPending = true)
            }
        }
    }

    fun abort() {
        invalidate()
        clearSession()
        transition(VoicePhase.IDLE)
    }

    private suspend fun speak(text: String, token: Long, parked: Boolean = false) {
        ensureAllowed(token, parked)
        voice.speak(text, preferences.speechRate)
        ensureAllowed(token, parked)
    }

    private fun ensureAllowed(token: Long, parked: Boolean = false) {
        if (token != generation || !safety.canTalk || (parked && !safety.canInteract)) {
            throw CancellationException("Conversation interrupted")
        }
    }

    private fun invalidate() {
        generation++
        resumeJob?.cancel()
        resumeJob = null
        activeJob?.cancel()
        activeJob = null
        voice.cancel()
    }

    private fun clearSession() {
        sessionId = null
        turns.clear()
        contextSources.clear()
        boarded = false
        pausedByUser = false
        silencePolicy.reset()
    }

    private fun transition(
        phase: VoicePhase,
        text: String = status.lastUtterance,
        error: Boolean = false,
        dropoffPending: Boolean = false,
        safetySettled: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (phase == VoicePhase.LISTENING && !pausedByUser) silencePolicy.beginListening(now)
        else silencePolicy.suspendListening()
        if (status.phase in activePhases) activeMillis += now - phaseStartedAt
        phaseStartedAt = now
        status = ConversationStatus(phase, text, error, dropoffPending, safetySettled)
        onStatus(status)
    }

    private companion object {
        val activePhases = setOf(VoicePhase.LISTENING, VoicePhase.THINKING, VoicePhase.SPEAKING)
    }
}
