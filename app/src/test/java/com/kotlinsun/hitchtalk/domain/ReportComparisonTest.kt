package com.kotlinsun.hitchtalk.domain

import com.kotlinsun.hitchtalk.data.ScoreRecord
import com.kotlinsun.hitchtalk.data.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportComparisonTest {
    private val metric: (SessionRecord) -> Int? = { it.scores.sentenceCompletenessScore }

    @Test
    fun comparesOnlyFiveLatestEligibleSessionsBeforeSelectedReport() {
        val report = record(100, 60)
        val recent = record(96, 90)
        val history = listOf(
            record(91, 0),
            record(94, null),
            record(95, 20),
            record(101, 100),
            record(100, 100).copy(id = "same-time"),
            report.copy(endedAtMillis = 99),
            record(98, 100, version = "english-v2"),
            record(97, 100, sufficient = false),
            record(93, 50),
            recent,
            recent,
            record(92, 40),
        )

        assertEquals(45, ReportComparison.previousMedian(report, history, metric))
        assertEquals(15, ReportComparison.change(report, history, metric))
    }

    @Test
    fun scoreCanDeclineAgainstAnOddSizedBaseline() {
        val report = record(100, 20)
        val history = listOf(record(90, 0), record(91, 70), record(92, 50))

        assertEquals(-30, ReportComparison.change(report, history, metric))
    }

    @Test
    fun missingMetricAndInsufficientSamplesDoNotProduceAChange() {
        val history = listOf(record(90, 30))
        assertNull(ReportComparison.change(record(100, null), history, metric))
        assertNull(ReportComparison.change(record(100, 60, sufficient = false), history, metric))
        assertNull(ReportComparison.change(record(100, 60), listOf(record(90, null)), metric))
        assertNull(ReportComparison.change(record(100, 60), emptyList(), metric))
    }

    private fun record(
        endedAtMillis: Long,
        score: Int?,
        sufficient: Boolean = true,
        version: String = "english-v1",
    ) = SessionRecord(
        id = "session-$endedAtMillis",
        personaId = "alex",
        startedAtMillis = 0,
        endedAtMillis = endedAtMillis,
        activeConversationSec = 120,
        userTurnCount = 4,
        englishWordCount = 40,
        scores = ScoreRecord(
            sentenceCompletenessScore = score,
            sampleSufficient = sufficient,
            scoringVersion = version,
        ),
    )
}
