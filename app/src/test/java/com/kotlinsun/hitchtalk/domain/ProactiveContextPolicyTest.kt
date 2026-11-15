package com.kotlinsun.hitchtalk.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ProactiveContextPolicyTest {
    @Test
    fun recentBatteryChangeTakesPriorityOverStableHvac() {
        val policy = ProactiveContextPolicy()
        policy.observeVehicleFacts(mapOf("hvacOn" to "true", "batteryPercent" to "78"), now = 0)
        policy.observeVehicleFacts(mapOf("hvacOn" to "true", "batteryPercent" to "79"), now = 10_000)

        assertEquals(mapOf("batteryPercent" to "79"), policy.select(now = 10_000, localTime = "Monday morning"))
    }

    @Test
    fun cooldownUsesOtherSensorThenTimeAndLetsTopicReturnAfterFiveMinutes() {
        val policy = ProactiveContextPolicy()
        val vehicleFacts = mapOf("hvacOn" to "true", "batteryPercent" to "78")

        policy.observeVehicleFacts(vehicleFacts, now = 0)
        assertEquals(mapOf("hvacOn" to "true"), policy.select(now = 0, localTime = "Monday morning"))
        policy.observeVehicleFacts(vehicleFacts, now = 60_000)
        assertEquals(mapOf("batteryPercent" to "78"), policy.select(now = 60_000, localTime = "Monday morning"))
        policy.observeVehicleFacts(vehicleFacts, now = 120_000)
        assertEquals(mapOf("localTime" to "Monday morning"), policy.select(now = 120_000, localTime = "Monday morning"))
        policy.observeVehicleFacts(vehicleFacts, now = 180_000)
        assertEquals(emptyMap<String, String>(), policy.select(now = 180_000, localTime = "Monday morning"))
        policy.observeVehicleFacts(vehicleFacts, now = 300_000)
        assertEquals(mapOf("hvacOn" to "true"), policy.select(now = 300_000, localTime = "Monday morning"))
    }

    @Test
    fun absentInvalidAndStaleReadingsAreExcluded() {
        val policy = ProactiveContextPolicy()
        policy.observeVehicleFacts(mapOf("hvacOn" to "true"), now = 0)
        assertEquals(emptyMap<String, String>(), policy.select(now = 30_001, localTime = ""))
        policy.observeVehicleFacts(mapOf("batteryPercent" to "101", "hvacOn" to "unknown"), now = 30_002)

        assertEquals(
            mapOf("localTime" to "Monday afternoon"),
            policy.select(now = 30_002, localTime = "Monday afternoon"),
        )
    }

    @Test
    fun unchangedPollsKeepTheOriginalChangeTime() {
        val policy = ProactiveContextPolicy()
        val original = mapOf("hvacOn" to "true", "batteryPercent" to "78")
        val updated = original + ("batteryPercent" to "79")
        policy.observeVehicleFacts(original, now = 0)
        policy.observeVehicleFacts(updated, now = 1_000)
        policy.observeVehicleFacts(updated, now = 32_000)

        assertEquals(mapOf("hvacOn" to "true"), policy.select(now = 32_000, localTime = "Monday morning"))
    }
}
