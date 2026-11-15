package com.kotlinsun.hitchtalk

data class ConversationSafety(
    val gear: Gear = Gear.UNKNOWN,
    val gearFresh: Boolean = false,
    val signalsKnown: Boolean = false,
    val hazardActive: Boolean = false,
    val turnSignalActive: Boolean = false,
    val hardBrake: Boolean = false,
    val foreground: Boolean = false,
    val audioAvailable: Boolean = false,
    val microphoneGranted: Boolean = false,
    val platformRestricted: Boolean? = false,
    val simulatedGear: Boolean = false,
    val drivingVoiceEnabled: Boolean = false,
) {
    val requiresDrivingUi: Boolean get() =
        platformRestricted != false || (!simulatedGear && (!gearFresh || gear != Gear.PARK))
    val canInteract: Boolean get() = gearFresh && gear == Gear.PARK && platformRestricted == false
    val canTalk: Boolean get() =
        gearFresh && (gear == Gear.PARK || gear == Gear.DRIVE) && signalsKnown &&
            platformRestricted != null && (canInteract || drivingVoiceEnabled) &&
            !hazardActive && !turnSignalActive && !hardBrake &&
            foreground && audioAvailable && microphoneGranted
}
