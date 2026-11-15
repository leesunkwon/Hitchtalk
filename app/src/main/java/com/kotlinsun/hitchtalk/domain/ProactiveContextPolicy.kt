package com.kotlinsun.hitchtalk.domain

internal class ProactiveContextPolicy {
    private data class Observation(val value: String, val observedAt: Long, val changedAt: Long?)

    private val observations = mutableMapOf<String, Observation>()
    private val lastSelectedAt = mutableMapOf<String, Long>()
    private var availableSensors = emptySet<String>()

    fun observeVehicleFacts(facts: Map<String, String>, now: Long) {
        val available = mutableSetOf<String>()
        for (key in sensorPriority) {
            val value = facts[key] ?: continue
            val valid = when (key) {
                "hvacOn" -> value.toBooleanStrictOrNull() != null
                "batteryPercent" -> value.toIntOrNull()?.let { it in 0..100 } == true
                else -> false
            }
            if (!valid) continue
            available += key
            val previous = observations[key]
            val changedAt = if (previous != null && previous.value != value) now else previous?.changedAt
            observations[key] = Observation(value, now, changedAt)
        }
        availableSensors = available
    }

    fun select(now: Long, localTime: String): Map<String, String> {
        val sensor = sensorPriority
            .filter { key ->
                key in availableSensors && canSelect(key, now) &&
                    now - checkNotNull(observations[key]).observedAt in 0..recentMillis
            }
            .maxByOrNull { key ->
                observations[key]?.changedAt
                    ?.takeIf { now - it in 0..recentMillis } ?: Long.MIN_VALUE
            }
        if (sensor != null) {
            lastSelectedAt[sensor] = now
            return mapOf(sensor to checkNotNull(observations[sensor]).value)
        }
        if (localTime.isNotBlank() && canSelect("localTime", now)) {
            lastSelectedAt["localTime"] = now
            return mapOf("localTime" to localTime)
        }
        return emptyMap()
    }

    private fun canSelect(key: String, now: Long): Boolean =
        lastSelectedAt[key]?.let { now - it >= cooldownMillis } ?: true

    private companion object {
        val sensorPriority = listOf("hvacOn", "batteryPercent")
        const val recentMillis = 30_000L
        const val cooldownMillis = 300_000L
    }
}
