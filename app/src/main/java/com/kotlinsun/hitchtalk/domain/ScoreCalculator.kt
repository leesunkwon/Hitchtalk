package com.kotlinsun.hitchtalk.domain

import kotlin.math.roundToInt

object ScoreCalculator {
    val expressionCategories = setOf(
        "greeting", "selfIntroduction", "preference", "reason",
        "question", "pastExperience", "futurePlan", "comparison",
    )
    private val englishWord = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*(?:-[A-Za-z]+)*")
    private val sentenceBoundary = Regex("(?<=[.!?])\\s+")

    fun englishWordCount(text: String): Int = englishWord.findAll(text).count()

    fun userTurns(turns: List<DialogueTurn>): List<DialogueTurn> = turns
        .filter { it.role == TurnRole.USER }
        .distinctBy { it.id }
        .filter { englishWordCount(it.text) > 0 }

    fun userSentences(turns: List<DialogueTurn>): List<UserSentence> = userTurns(turns).flatMap { turn ->
        turn.text.trim().split(sentenceBoundary)
            .filter { englishWordCount(it) > 0 }
            .mapIndexed { index, text -> UserSentence("${turn.id}:${index + 1}", turn.id, text) }
    }

    fun calculate(
        turns: List<DialogueTurn>,
        activeConversationSec: Int,
        analysis: SessionAnalysis? = null,
    ): EnglishScores {
        val validTurns = userTurns(turns)
        val wordCount = validTurns.sumOf { englishWordCount(it.text) }
        val seconds = activeConversationSec.coerceAtLeast(0)
        val sufficient = seconds >= 60 && validTurns.size >= 3 && wordCount >= 20
        val sentenceIds = userSentences(turns).map { it.id }.toSet()
        val assessments = analysis?.sentenceAssessments.orEmpty()
            .filter { it.sentenceId in sentenceIds }
            .distinctBy { it.sentenceId }
        val evaluable = assessments.count { it.verdict != SentenceVerdict.UNASSESSABLE }
        val complete = assessments.count { it.verdict == SentenceVerdict.COMPLETE }
        val categories = analysis?.expressionEvidence.orEmpty()
            .filter { evidence ->
                evidence.category in expressionCategories && evidence.sentenceIds.any { it in sentenceIds }
            }
            .map { it.category }
            .distinct()

        return EnglishScores(
            speechVolumeScore = if (sufficient) {
                (wordCount / maxOf(1.0, seconds / 60.0) / 25.0 * 100).roundToInt().coerceIn(0, 100)
            } else null,
            sentenceCompletenessScore = if (sufficient && evaluable > 0) {
                (complete.toDouble() / evaluable * 100).roundToInt()
            } else null,
            expressionVarietyScore = if (sufficient && analysis != null) {
                (categories.size / 8.0 * 100).roundToInt().coerceIn(0, 100)
            } else null,
            englishWordCount = wordCount,
            userTurnCount = validTurns.size,
            activeConversationSec = seconds,
            completeSentenceCount = complete,
            evaluableSentenceCount = evaluable,
            unevaluableSentenceCount = sentenceIds.size - evaluable,
            expressionCategories = categories,
            sampleSufficient = sufficient,
            analysisPromptVersion = analysis?.promptVersion ?: "summary-v1",
            analysisModel = analysis?.analysisModel.orEmpty(),
        )
    }
}
