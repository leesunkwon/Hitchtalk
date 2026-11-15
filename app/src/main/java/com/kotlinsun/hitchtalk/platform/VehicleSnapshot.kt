package com.kotlinsun.hitchtalk.platform

import com.kotlinsun.hitchtalk.Gear

data class VehicleSnapshot(
    val gear: Gear = Gear.UNKNOWN,
    val gearObservedAt: Long = 0L,
    val turnSignalActive: Boolean? = null,
    val hazardActive: Boolean? = null,
    val brakePressed: Boolean? = null,
    val hvacOn: Boolean? = null,
    val batteryPercent: Int? = null,
    val observedAt: Long = 0L,
    val source: String = "pleos",
    val turnObservedAt: Long = 0L,
    val hazardObservedAt: Long = 0L,
    val hvacObservedAt: Long = 0L,
    val batteryObservedAt: Long = 0L,
    val gearSource: String? = null,
)
