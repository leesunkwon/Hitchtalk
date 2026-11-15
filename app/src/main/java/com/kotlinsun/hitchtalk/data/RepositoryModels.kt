package com.kotlinsun.hitchtalk.data

data class UserProfile(
    val displayName: String,
    val birthDate: String,
    val gender: String,
) {
    val ageYears: Int?
        get() = runCatching {
            val birthday = java.time.LocalDate.parse(birthDate)
            val today = java.time.LocalDate.now()
            if (birthday > today) null else java.time.Period.between(birthday, today).years
        }.getOrNull()
}

data class UserPreferences(
    val englishLevel: String = "beginner",
    val preferredPersonaId: String = "alex",
    val personaSelectionMode: String = "recommended",
    val speechRate: Float = 1f,
    val greetingEnabled: Boolean = true,
    val consentVersion: String = "2026-11-v1",
    val onboardingCompleted: Boolean = false,
    val aiConsentGranted: Boolean = false,
)

data class ConfirmedMemory(
    val userJob: String = "",
    val userLikes: List<String> = emptyList(),
    val lastPromise: String = "",
)

data class TravelerProfile(
    val personaId: String,
    val xp: Int = 0,
    val meetingCount: Int = 0,
    val memory: ConfirmedMemory = ConfirmedMemory(),
    val lastSessionAtMillis: Long? = null,
) {
    val level: String get() = when {
        xp >= 60 -> "단짝"
        xp >= 20 -> "편한 사이"
        else -> "지인"
    }
}

data class CorrectionRecord(
    val original: String,
    val natural: String,
    val explanationKo: String = "",
)

data class ScoreRecord(
    val speechVolumeScore: Int? = null,
    val sentenceCompletenessScore: Int? = null,
    val expressionVarietyScore: Int? = null,
    val completeSentenceCount: Int = 0,
    val evaluableSentenceCount: Int = 0,
    val expressionCategories: List<String> = emptyList(),
    val sampleSufficient: Boolean = false,
    val scoringVersion: String = "english-v1",
    val unevaluableSentenceCount: Int? = null,
)

data class SessionRecord(
    val id: String,
    val personaId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val activeConversationSec: Long,
    val userTurnCount: Int,
    val englishWordCount: Int,
    val summaryKo: String = "",
    val corrections: List<CorrectionRecord> = emptyList(),
    val scores: ScoreRecord = ScoreRecord(),
    val analysisStatus: String = "unavailable",
    val analysisModel: String = "",
    val contextSources: List<String> = emptyList(),
    val demoInputMode: String = "vehicle",
)

enum class SaveResult { SYNCED, PENDING }
