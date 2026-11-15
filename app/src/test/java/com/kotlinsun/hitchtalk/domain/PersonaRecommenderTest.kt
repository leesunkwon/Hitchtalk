package com.kotlinsun.hitchtalk.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonaRecommenderTest {
    @Test
    fun weekdayMorningIsHalfOpenAtTen() {
        assertEquals("alex", PersonaRecommender.recommend(LocalDateTime.of(2026, 11, 6, 9, 59), 20, 10))
        assertEquals("sara", PersonaRecommender.recommend(LocalDateTime.of(2026, 11, 6, 10, 0), 20, 10))
    }

    @Test
    fun weekendRecommendationStartsAtEightAndEndsAtTwenty() {
        assertEquals("alex", PersonaRecommender.recommend(LocalDateTime.of(2026, 11, 7, 7, 59)))
        assertEquals("sara", PersonaRecommender.recommend(LocalDateTime.of(2026, 11, 7, 8, 0)))
        assertEquals("sara", PersonaRecommender.recommend(LocalDateTime.of(2026, 11, 8, 19, 59)))
        assertEquals("alex", PersonaRecommender.recommend(LocalDateTime.of(2026, 11, 8, 20, 0)))
    }

    @Test
    fun midnightUsesTheLeastRecentlyMetTraveler() {
        assertEquals("sara", PersonaRecommender.recommend(LocalDateTime.of(2026, 11, 9, 0, 0), 20, null))
    }
}
