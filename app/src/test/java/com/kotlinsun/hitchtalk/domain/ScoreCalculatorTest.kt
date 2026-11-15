package com.kotlinsun.hitchtalk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreCalculatorTest {
    private val turns = listOf(
        DialogueTurn("a", TurnRole.USER, "Hello, I work as a software developer."),
        DialogueTurn("b", TurnRole.USER, "I enjoy hiking because it helps me relax."),
        DialogueTurn("c", TurnRole.USER, "Last weekend I visited a quiet mountain."),
    )

    @Test
    fun practiceThresholdRequiresTimeTurnsAndWordsTogether() {
        assertNull(ScoreCalculator.calculate(turns, 59).speechVolumeScore)
        assertNull(ScoreCalculator.calculate(turns.take(2), 60).speechVolumeScore)
        val shortTurns = turns.map { it.copy(text = "Yes, I do.") }
        assertNull(ScoreCalculator.calculate(shortTurns, 60).speechVolumeScore)
        val scored = ScoreCalculator.calculate(turns, 60)
        assertTrue(scored.sampleSufficient)
        assertEquals(22, scored.englishWordCount)
        assertEquals(88, scored.speechVolumeScore)
    }

    @Test
    fun onlyDistinctUserFinalTurnsContributeToVolume() {
        val input = turns + turns.first() + DialogueTurn(
            "assistant", TurnRole.ASSISTANT, "This generated answer must not count toward the driver's word total.",
        )
        val scores = ScoreCalculator.calculate(input, 120)
        assertEquals(3, scores.userTurnCount)
        assertEquals(22, scores.englishWordCount)
        assertEquals(44, scores.speechVolumeScore)
    }

    @Test
    fun missingAnalysisPreservesRawCountsAndUsesNullForAiMetrics() {
        val scores = ScoreCalculator.calculate(turns, 60)
        assertTrue(scores.sampleSufficient)
        assertNull(scores.sentenceCompletenessScore)
        assertNull(scores.expressionVarietyScore)
        assertEquals(3, scores.unevaluableSentenceCount)
    }

    @Test
    fun sentenceAndExpressionEvidenceMustReferenceUserSentences() {
        val analysis = SessionAnalysis(
            summaryKo = "연습 기록",
            sentenceAssessments = listOf(
                SentenceAssessment("a:1", SentenceVerdict.COMPLETE),
                SentenceAssessment("b:1", SentenceVerdict.PARTIAL),
                SentenceAssessment("assistant:1", SentenceVerdict.COMPLETE),
            ),
            expressionEvidence = listOf(
                ExpressionEvidence("greeting", listOf("a:1")),
                ExpressionEvidence("greeting", listOf("a:1")),
                ExpressionEvidence("question", listOf("assistant:1")),
                ExpressionEvidence("invented", listOf("b:1")),
            ),
        )
        val scores = ScoreCalculator.calculate(turns, 60, analysis)
        assertEquals(50, scores.sentenceCompletenessScore)
        assertEquals(1, scores.completeSentenceCount)
        assertEquals(2, scores.evaluableSentenceCount)
        assertEquals(1, scores.unevaluableSentenceCount)
        assertEquals(listOf("greeting"), scores.expressionCategories)
        assertEquals(13, scores.expressionVarietyScore)
    }

    @Test
    fun separateSentencesAndNonEnglishTurnsDoNotInventSamples() {
        val input = listOf(
            DialogueTurn("one", TurnRole.USER, "Yes, I do. What about you?"),
            DialogueTurn("two", TurnRole.USER, "좋아요."),
        )
        assertEquals(listOf("one:1", "one:2"), ScoreCalculator.userSentences(input).map { it.id })
        assertFalse(ScoreCalculator.calculate(input, 60).sampleSufficient)
    }
}
