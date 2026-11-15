package com.kotlinsun.hitchtalk.domain

enum class TurnRole { USER, ASSISTANT }

data class DialogueTurn(
    val id: String,
    val role: TurnRole,
    val text: String,
)

data class DialogueRequest(
    val personaId: String,
    val turns: List<DialogueTurn>,
    val difficulty: String = "beginner",
    val intimacyLevel: String = "acquaintance",
    val confirmedMemories: List<String> = emptyList(),
    val vehicleFacts: Map<String, String> = emptyMap(),
    val proactive: Boolean = false,
)

data class Correction(
    val original: String,
    val natural: String,
    val explanationKo: String = "",
)

data class MemoryCandidate(
    val kind: String,
    val text: String,
    val sourceTurnId: String,
)

data class DialogueReply(
    val replyEn: String,
    val correction: Correction? = null,
    val memoryCandidates: List<MemoryCandidate> = emptyList(),
    val topicTag: String = "general",
)

data class SummaryRequest(
    val personaId: String,
    val turns: List<DialogueTurn>,
    val activeConversationSec: Int,
)

enum class SentenceVerdict { COMPLETE, PARTIAL, UNASSESSABLE }

data class SentenceAssessment(val sentenceId: String, val verdict: SentenceVerdict)

data class ExpressionEvidence(val category: String, val sentenceIds: List<String>)

data class SessionAnalysis(
    val summaryKo: String,
    val corrections: List<Correction> = emptyList(),
    val memoryCandidates: List<MemoryCandidate> = emptyList(),
    val sentenceAssessments: List<SentenceAssessment> = emptyList(),
    val expressionEvidence: List<ExpressionEvidence> = emptyList(),
    val analysisModel: String = "",
    val promptVersion: String = "summary-v1",
)

data class EnglishScores(
    val speechVolumeScore: Int?,
    val sentenceCompletenessScore: Int?,
    val expressionVarietyScore: Int?,
    val englishWordCount: Int,
    val userTurnCount: Int,
    val activeConversationSec: Int,
    val completeSentenceCount: Int,
    val evaluableSentenceCount: Int,
    val unevaluableSentenceCount: Int,
    val expressionCategories: List<String>,
    val sampleSufficient: Boolean,
    val scoringVersion: String = "english-v1",
    val analysisPromptVersion: String = "summary-v1",
    val analysisModel: String = "",
)

data class UserSentence(val id: String, val turnId: String, val text: String)
