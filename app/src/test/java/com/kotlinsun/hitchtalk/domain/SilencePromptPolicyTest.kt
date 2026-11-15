package com.kotlinsun.hitchtalk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SilencePromptPolicyTest {
    @Test
    fun firstPromptWaitsForEighteenSecondsOfSafeListening() {
        val policy = SilencePromptPolicy()
        assertNull(policy.delayUntilPrompt(0))
        policy.beginListening(100)
        assertEquals(1L, policy.delayUntilPrompt(18_099))
        assertEquals(0L, policy.delayUntilPrompt(18_100))
    }

    @Test
    fun recognitionCyclesDoNotResetAnOtherwiseSilentListeningWindow() {
        val policy = SilencePromptPolicy()
        policy.beginListening(0)
        policy.beginListening(9_000)
        assertEquals(0L, policy.delayUntilPrompt(18_000))
    }

    @Test
    fun partialSpeechExtendsTheDeadlineWhileAUserIsStillTalking() {
        val policy = SilencePromptPolicy()
        policy.beginListening(0)
        policy.onSpeechActivity(17_000)
        assertEquals(17_000L, policy.delayUntilPrompt(18_000))
        policy.onSpeechActivity(30_000)
        assertEquals(1L, policy.delayUntilPrompt(47_999))
        assertEquals(0L, policy.delayUntilPrompt(48_000))
    }

    @Test
    fun thinkingAndTtsDoNotCountAsListeningAndCooldownStartsAfterOutputFinishes() {
        val policy = SilencePromptPolicy()
        policy.beginListening(0)
        policy.suspendListening()
        assertNull(policy.delayUntilPrompt(90_000))
        policy.onPromptFinished(90_000)
        policy.beginListening(90_000)
        assertEquals(42_000L, policy.delayUntilPrompt(108_000))
        assertEquals(1L, policy.delayUntilPrompt(149_999))
        assertEquals(0L, policy.delayUntilPrompt(150_000))
    }

    @Test
    fun safetyAndUserPauseRequireAFreshSafeListeningWindow() {
        val policy = SilencePromptPolicy()
        policy.beginListening(0)
        policy.suspendListening()
        policy.onSpeechActivity(30_000)
        assertNull(policy.delayUntilPrompt(80_000))
        policy.beginListening(80_000)
        assertEquals(1L, policy.delayUntilPrompt(97_999))
        assertEquals(0L, policy.delayUntilPrompt(98_000))
    }

    @Test
    fun twoUnansweredPromptsStopUntilACompletedUserTurn() {
        val policy = SilencePromptPolicy()
        policy.beginListening(0)
        policy.onPromptFinished(18_000)
        assertEquals(0L, policy.delayUntilPrompt(78_000))
        policy.onPromptFinished(78_000)
        assertNull(policy.delayUntilPrompt(500_000))
        policy.onSpeechActivity(500_000)
        assertNull(policy.delayUntilPrompt(520_000))
        policy.onUserTurn(520_000)
        assertEquals(0L, policy.delayUntilPrompt(538_000))
    }

    @Test
    fun userSpeechDoesNotBypassCooldownFromTheLastAutomaticPrompt() {
        val policy = SilencePromptPolicy()
        policy.beginListening(0)
        policy.onPromptFinished(18_000)
        policy.onUserTurn(20_000)
        assertEquals(40_000L, policy.delayUntilPrompt(38_000))
        assertEquals(0L, policy.delayUntilPrompt(78_000))
    }

    @Test
    fun aNewSessionResetsThePreviousSessionsPromptLimit() {
        val policy = SilencePromptPolicy()
        policy.beginListening(0)
        policy.onPromptFinished(18_000)
        policy.onPromptFinished(78_000)
        policy.reset()
        policy.beginListening(80_000)
        assertEquals(0L, policy.delayUntilPrompt(98_000))
    }

    @Test
    fun speechEndpointProtectsLongSpeechEvenBeforePartialTextArrives() {
        val policy = SilencePromptPolicy()
        policy.beginListening(0)
        policy.onSpeechSegment(true, 17_950)
        assertNull(policy.delayUntilPrompt(18_000))
        assertNull(policy.delayUntilPrompt(40_000))
        policy.onSpeechSegment(false, 41_000)
        assertEquals(1L, policy.delayUntilPrompt(58_999))
        assertEquals(0L, policy.delayUntilPrompt(59_000))
    }

    @Test
    fun unclearSpeechWaitsForCompletedTurnBeforeProactiveDialogue() {
        val policy = SilencePromptPolicy()
        policy.beginListening(0)
        policy.waitForClearTurn()
        assertNull(policy.delayUntilPrompt(80_000))
        policy.onUserTurn(90_000)
        assertEquals(0L, policy.delayUntilPrompt(108_000))
    }
}
