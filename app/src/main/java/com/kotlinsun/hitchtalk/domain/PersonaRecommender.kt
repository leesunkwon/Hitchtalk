package com.kotlinsun.hitchtalk.domain

import java.time.DayOfWeek
import java.time.LocalDateTime

object PersonaRecommender {
    fun recommend(
        now: LocalDateTime,
        lastAlexMeetingMillis: Long? = null,
        lastSaraMeetingMillis: Long? = null,
    ): String {
        val weekend = now.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        return when {
            !weekend && now.hour in 6 until 10 -> "alex"
            weekend && now.hour in 8 until 20 -> "sara"
            (lastAlexMeetingMillis ?: Long.MIN_VALUE) <= (lastSaraMeetingMillis ?: Long.MIN_VALUE) -> "alex"
            else -> "sara"
        }
    }
}
