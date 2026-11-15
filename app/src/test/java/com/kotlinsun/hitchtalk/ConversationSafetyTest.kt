package com.kotlinsun.hitchtalk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSafetyTest {
    private val ready = ConversationSafety(
        gear = Gear.PARK,
        gearFresh = true,
        signalsKnown = true,
        foreground = true,
        audioAvailable = true,
        microphoneGranted = true,
    )

    @Test
    fun unknownInitialStateAllowsNeitherTouchNorVoice() {
        assertFalse(ConversationSafety().canInteract)
        assertFalse(ConversationSafety().canTalk)
    }

    @Test
    fun freshParkAllowsTouchAndVoiceWhenAllInputsAreReady() {
        assertTrue(ready.canInteract)
        assertTrue(ready.canTalk)
    }

    @Test
    fun freshDriveAllowsOnlyVoice() {
        val driving = ready.copy(gear = Gear.DRIVE, drivingVoiceEnabled = true)
        assertFalse(driving.canInteract)
        assertTrue(driving.canTalk)
        assertTrue(driving.requiresDrivingUi)
    }

    @Test
    fun platformRestrictionsTakeEffectBeforeGearUpdates() {
        val restricted = ready.copy(platformRestricted = true, drivingVoiceEnabled = true)
        assertTrue(restricted.requiresDrivingUi)
        assertFalse(restricted.canInteract)
        assertTrue(restricted.canTalk)
        assertFalse(restricted.copy(drivingVoiceEnabled = false).canTalk)
        assertFalse(restricted.copy(platformRestricted = null).canTalk)
    }

    @Test
    fun simulatedGearKeepsPreviewUiOnlyWhenPlatformPermitsIt() {
        val simulatedDrive = ready.copy(gear = Gear.DRIVE, simulatedGear = true, drivingVoiceEnabled = true)
        assertFalse(simulatedDrive.requiresDrivingUi)
        assertTrue(simulatedDrive.copy(platformRestricted = true).requiresDrivingUi)
    }

    @Test
    fun reverseNeutralAndUnknownBlockBothInteractionModes() {
        listOf(Gear.REVERSE, Gear.NEUTRAL, Gear.UNKNOWN).forEach { gear ->
            val state = ready.copy(gear = gear)
            assertFalse(state.canInteract)
            assertFalse(state.canTalk)
        }
    }

    @Test
    fun staleGearBlocksEvenWhenItsLastValueWasParkOrDrive() {
        listOf(Gear.PARK, Gear.DRIVE).forEach { gear ->
            val state = ready.copy(gear = gear, gearFresh = false)
            assertFalse(state.canInteract)
            assertFalse(state.canTalk)
        }
    }

    @Test
    fun missingSafetySignalsBlockVoiceWhileParkedSetupRemainsAvailable() {
        val state = ready.copy(signalsKnown = false)
        assertTrue(state.canInteract)
        assertFalse(state.canTalk)
    }

    @Test
    fun clearingOneOfOverlappingSafetyCausesDoesNotResumeVoice() {
        val both = ready.copy(hazardActive = true, turnSignalActive = true)
        assertFalse(both.canTalk)
        assertFalse(both.copy(hazardActive = false).canTalk)
        assertFalse(both.copy(turnSignalActive = false).canTalk)
        assertTrue(both.copy(hazardActive = false, turnSignalActive = false).canTalk)
        assertFalse(ready.copy(hardBrake = true).canTalk)
    }

    @Test
    fun backgroundAudioFocusLossAndMicrophoneDenialEachBlockVoice() {
        assertFalse(ready.copy(foreground = false).canTalk)
        assertFalse(ready.copy(audioAvailable = false).canTalk)
        assertFalse(ready.copy(microphoneGranted = false).canTalk)
    }
}
