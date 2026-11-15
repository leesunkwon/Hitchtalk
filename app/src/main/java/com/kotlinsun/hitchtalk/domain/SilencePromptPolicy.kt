package com.kotlinsun.hitchtalk.domain

/** Monotonic-clock policy for safe listening time, independent of Android and speech services. */
class SilencePromptPolicy(
    private val silenceMillis: Long = 18_000,
    private val cooldownMillis: Long = 60_000,
    private val maxUnansweredPrompts: Int = 2,
) {
    private var quietSince: Long? = null
    private var lastPromptFinishedAt: Long? = null
    private var unansweredPrompts = 0
    private var speechActive = false

    fun beginListening(now: Long) {
        if (quietSince == null) quietSince = now
    }

    /** Discard partial quiet time across TTS, thinking, safety interruptions and user pauses. */
    fun suspendListening() {
        quietSince = null
        speechActive = false
    }

    /** Partial ASR text proves speech activity but is not a completed user turn. */
    fun onSpeechActivity(now: Long) {
        if (quietSince != null) quietSince = now
    }

    fun onSpeechSegment(active: Boolean, now: Long) {
        if (quietSince == null) return
        speechActive = active
        onSpeechActivity(now)
    }

    fun waitForClearTurn() {
        unansweredPrompts = maxUnansweredPrompts
    }

    fun onUserTurn(now: Long) {
        unansweredPrompts = 0
        onSpeechActivity(now)
    }

    /** Count failed attempts too, so a network failure cannot cause repeated automatic requests. */
    fun onPromptFinished(now: Long) {
        unansweredPrompts++
        lastPromptFinishedAt = now
        if (quietSince != null) quietSince = now
    }

    /** Null means that automatic prompts remain disabled until a completed user turn. */
    fun delayUntilPrompt(now: Long): Long? {
        val listeningSince = quietSince ?: return null
        if (speechActive || unansweredPrompts >= maxUnansweredPrompts) return null
        val quietRemaining = silenceMillis - (now - listeningSince).coerceAtLeast(0)
        val cooldownRemaining = lastPromptFinishedAt?.let {
            cooldownMillis - (now - it).coerceAtLeast(0)
        } ?: 0
        return maxOf(0, quietRemaining, cooldownRemaining)
    }

    fun reset() {
        quietSince = null
        lastPromptFinishedAt = null
        unansweredPrompts = 0
        speechActive = false
    }
}
