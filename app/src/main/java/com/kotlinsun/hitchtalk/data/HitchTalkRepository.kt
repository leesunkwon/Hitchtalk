package com.kotlinsun.hitchtalk.data

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Firebase Auth and the user's UID subtree are the only cloud services used here. */
class HitchTalkRepository(context: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance().apply {
        // The durable retry queue is app-owned, so cloud cache cannot retain deleted memories.
        firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
    }
    private val pending = PendingSessionStore(context.applicationContext)
    private val mutation = Mutex()

    val isSignedIn: Boolean get() = auth.currentUser != null

    suspend fun signIn(email: String, password: String) = mutation.withLock {
        auth.signInWithEmailAndPassword(email.trim(), password).awaitDurable()
        Unit
    }

    suspend fun signUp(email: String, password: String, profile: UserProfile) = mutation.withLock {
        validate(profile)
        ensurePrivateProfileRules()
        auth.createUserWithEmailAndPassword(email.trim(), password).awaitDurable()
        saveProfile(requireUid(), profile)
    }

    suspend fun resetPassword(email: String) {
        auth.sendPasswordResetEmail(email.trim()).awaitResult()
    }

    suspend fun signOut() = mutation.withLock { auth.signOut() }

    suspend fun ensurePrivateProfileRules() = checkPrivateProfileRules()

    suspend fun reauthenticate(password: String) = mutation.withLock {
        val account = checkNotNull(auth.currentUser)
        val email = checkNotNull(account.email)
        account.reauthenticate(EmailAuthProvider.getCredential(email, password)).awaitResult()
        Unit
    }

    suspend fun loadUserProfile(): UserProfile? {
        val uid = requireUid()
        val local = mutation.withLock { withContext(Dispatchers.IO) { pending.loadProfile(uid) } }
        if (local != null) return local
        return try {
            val document = user(uid).get().awaitResult()
            val profile = if (document.getBoolean("profileComplete") == true) UserProfile(
                document.getString("displayName").orEmpty(),
                document.getString("birthDate").orEmpty(),
                document.getString("gender").orEmpty(),
            ) else null
            mutation.withLock {
                check(requireUid() == uid) { "Account changed" }
                withContext(Dispatchers.IO) {
                    if (profile == null) pending.clearProfileCache(uid) else pending.putProfileCache(uid, profile)
                }
            }
            profile
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            mutation.withLock {
                check(requireUid() == uid) { "Account changed" }
                withContext(Dispatchers.IO) { pending.loadProfileCache(uid) }
            } ?: throw error
        }
    }

    suspend fun saveUserProfile(profile: UserProfile) = mutation.withLock {
        validate(profile)
        saveProfile(requireUid(), profile)
    }

    suspend fun loadPreferences(): UserPreferences {
        val document = user().get().awaitResult()
        return UserPreferences(
            englishLevel = document.getString("englishLevel") ?: "beginner",
            preferredPersonaId = document.getString("preferredPersonaId") ?: "alex",
            personaSelectionMode = document.getString("personaSelectionMode") ?: "recommended",
            speechRate = (document.get("speechRate") as? Number)?.toFloat() ?: 1f,
            greetingEnabled = document.getBoolean("greetingEnabled") ?: true,
            consentVersion = document.getString("consentVersion") ?: "2026-11-v1",
            onboardingCompleted = document.getBoolean("onboardingCompleted") ?: false,
            aiConsentGranted = document.getBoolean("aiConsentGranted") ?: false,
        )
    }

    suspend fun savePreferences(preferences: UserPreferences) = mutation.withLock {
        require(preferences.englishLevel in setOf("beginner", "intermediate", "advanced"))
        requirePersona(preferences.preferredPersonaId)
        require(preferences.personaSelectionMode in setOf("recommended", "manual"))
        require(preferences.speechRate in 0.5f..1.5f)
        val reference = user()
        durableTransaction(timeoutMillis = 12_000) {
            val existing = it.get(reference)
            check(!existing.deletionPending()) { "Data deletion must finish first" }
            val values = mapOf(
                "schemaVersion" to 1,
                "englishLevel" to preferences.englishLevel,
                "preferredPersonaId" to preferences.preferredPersonaId,
                "personaSelectionMode" to preferences.personaSelectionMode,
                "speechRate" to preferences.speechRate,
                "greetingEnabled" to preferences.greetingEnabled,
                "consentVersion" to preferences.consentVersion.take(64),
                "onboardingCompleted" to preferences.onboardingCompleted,
                "aiConsentGranted" to preferences.aiConsentGranted,
                "createdAt" to (existing.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            it.set(reference, values, SetOptions.merge())
        }
    }

    suspend fun loadProfiles(): Map<String, TravelerProfile> {
        val reference = user()
        val intimacyRequest = reference.collection("intimacy").get()
        val memoriesRequest = reference.collection("memories").get()
        val intimacy = intimacyRequest.awaitResult()
        val memories = memoriesRequest.awaitResult()
        return personas.associateWith { personaId ->
            val profile = intimacy.documents.find { it.id == personaId }
            val memory = memories.documents.find { it.id == personaId }
            TravelerProfile(
                personaId = personaId,
                xp = profile?.int("xp") ?: 0,
                meetingCount = profile?.int("meetingCount") ?: 0,
                memory = memory?.toMemory() ?: ConfirmedMemory(),
                lastSessionAtMillis = profile?.getTimestamp("lastSessionAt")?.toDate()?.time,
            )
        }
    }

    suspend fun loadRecentSessions(): List<SessionRecord> {
        val uid = requireUid()
        val queued = loadPendingSessions()
        val sessionRequest = user(uid).collection("sessions")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(20)
            .get()
        val scoreRequest = user(uid).collection("englishScores")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(20)
            .get()
        val documents = sessionRequest.awaitResult()
        val scores = scoreRequest.awaitResult().documents.associateBy { it.id }
        val merged = documents.documents.map { document ->
            document.toSession().copy(scores = scores[document.id]?.toScores() ?: ScoreRecord())
        }.associateBy { it.id }.toMutableMap()
        queued.forEach { merged[it.id] = it }
        return merged.values.sortedByDescending { it.endedAtMillis }.take(20)
    }

    suspend fun loadPendingSessions(): List<SessionRecord> = mutation.withLock {
        val uid = requireUid()
        withContext(Dispatchers.IO) { pending.load(uid).sortedByDescending { it.endedAtMillis }.take(20) }
    }

    suspend fun saveSession(record: SessionRecord): SaveResult = mutation.withLock {
        validate(record)
        val uid = requireUid()
        withContext(Dispatchers.IO) { pending.put(uid, record) }
        try {
            syncSession(uid, record)
            withContext(Dispatchers.IO) { pending.remove(uid, record.id) }
            SaveResult.SYNCED
        } catch (_: TimeoutCancellationException) {
            SaveResult.PENDING
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SaveResult.PENDING
        }
    }

    suspend fun retryPending(): Int = mutation.withLock {
        val uid = requireUid()
        val profile = withContext(Dispatchers.IO) { pending.loadProfile(uid) }
        if (profile != null) saveProfile(uid, profile)
        val records = withContext(Dispatchers.IO) { pending.load(uid) }
        var saved = if (profile != null) 1 else 0
        for (record in records) {
            syncSession(uid, record)
            withContext(Dispatchers.IO) { pending.remove(uid, record.id) }
            saved++
        }
        saved
    }

    suspend fun pendingCount(): Int = mutation.withLock {
        val uid = requireUid()
        withContext(Dispatchers.IO) { pending.load(uid).size + if (pending.loadProfile(uid) != null) 1 else 0 }
    }

    suspend fun confirmMemory(
        personaId: String,
        memory: ConfirmedMemory?,
        sessionId: String? = null,
        previousPromiseDiscussed: String? = null,
    ) = mutation.withLock {
        requirePersona(personaId)
        memory?.let(::validate)
        require(memory != null || sessionId != null && !previousPromiseDiscussed.isNullOrBlank())
        sessionId?.let(::requireRecordId)
        val owner = user()
        val memoryRef = owner.collection("memories").document(personaId)
        val intimacyRef = owner.collection("intimacy").document(personaId)
        val sessionRef = sessionId?.let { owner.collection("sessions").document(it) }
        val awardDate = LocalDate.now().toString()
        durableTransaction(timeoutMillis = 12_000) { transaction ->
            val ownerData = transaction.get(owner)
            check(!ownerData.deletionPending(personaId)) { "Data deletion must finish first" }
            val oldMemory = transaction.get(memoryRef)
            val intimacy = transaction.get(intimacyRef)
            val session = sessionRef?.let { transaction.get(it) }
            if (session != null) {
                check(session.exists() && session.getString("personaId") == personaId) {
                    "Sync the source session before confirming memory"
                }
            }
            val hasContent = memory != null &&
                (memory.userJob.isNotBlank() || memory.userLikes.isNotEmpty() || memory.lastPromise.isNotBlank())
            val changed = memory != null && oldMemory.toMemory() != memory
            val memoryBonus = if (session != null && hasContent && changed && session.getBoolean("memoryBonusAwarded") != true) 2 else 0
            val promiseBonus = if (session != null && !previousPromiseDiscussed.isNullOrBlank() &&
                previousPromiseDiscussed == oldMemory.getString("lastPromise") &&
                session.getBoolean("promiseBonusAwarded") != true
            ) 3 else 0
            val previousAward = session?.int("xpAwarded") ?: 0
            val todayXp = if (intimacy.getString("dailyAwardDate") == awardDate) intimacy.int("dailyXp") else 0
            val bonus = minOf(memoryBonus + promiseBonus, (10 - previousAward).coerceAtLeast(0), (20 - todayXp).coerceAtLeast(0))
            if (memory != null && hasContent) {
                transaction.set(memoryRef, mapOf(
                    "schemaVersion" to 1,
                    "personaId" to personaId,
                    "userJob" to memory.userJob.trim(),
                    "userLikes" to memory.userLikes.map(String::trim).filter(String::isNotEmpty).distinct(),
                    "lastPromise" to memory.lastPromise.trim(),
                    "confirmedByUser" to true,
                    "sourceSessionId" to (sessionId ?: oldMemory.getString("sourceSessionId") ?: "manual"),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ))
            } else if (memory != null) {
                transaction.delete(memoryRef)
            }
            if (sessionRef != null && session != null) {
                transaction.update(sessionRef, mapOf(
                    "memoryBonusAwarded" to (session.getBoolean("memoryBonusAwarded") == true || memoryBonus > 0),
                    "promiseBonusAwarded" to (session.getBoolean("promiseBonusAwarded") == true || promiseBonus > 0),
                    "xpAwarded" to previousAward + bonus,
                    "memoryReviewStatus" to "confirmed",
                    "updatedAt" to FieldValue.serverTimestamp(),
                ))
            }
            if (bonus > 0) {
                transaction.set(intimacyRef, intimacyValues(
                    personaId, intimacy.int("xp") + bonus, intimacy.int("meetingCount"),
                    awardDate, todayXp + bonus,
                    sessionId ?: intimacy.getString("lastSessionId").orEmpty(),
                    intimacy.getTimestamp("lastSessionAt"),
                ), SetOptions.merge())
            }
        }
    }

    /** Removing a traveler also removes their related summaries and corrections. */
    suspend fun deleteTravelerData(personaId: String) = mutation.withLock {
        requirePersona(personaId)
        val uid = requireUid()
        val owner = user(uid)
        // The marker keeps another device's retry transaction from recreating removed records.
        durableTransaction(timeoutMillis = 12_000) { transaction ->
            val existing = transaction.get(owner)
            val values = if (existing.exists()) emptyMap() else defaultUserValues()
            val previousDeletions = (existing.get("sessionsDeletedBefore") as? Map<*, *>)
                ?.entries?.associate { it.key.toString() to it.value }.orEmpty()
            transaction.set(owner, values + mapOf(
                "blockedPersonas" to FieldValue.arrayUnion(personaId),
                "sessionsDeletedBefore" to previousDeletions + (personaId to FieldValue.serverTimestamp()),
                "updatedAt" to FieldValue.serverTimestamp(),
            ), SetOptions.merge())
        }
        withContext(Dispatchers.IO) { pending.removePersona(uid, personaId) }
        deleteCollection(owner.collection("sessions").whereEqualTo("personaId", personaId))
        deleteCollection(owner.collection("englishScores").whereEqualTo("personaId", personaId))
        db.batch().delete(owner.collection("memories").document(personaId))
            .delete(owner.collection("intimacy").document(personaId)).commit().awaitDurable()
        owner.update(mapOf(
            "blockedPersonas" to FieldValue.arrayRemove(personaId),
            "updatedAt" to FieldValue.serverTimestamp(),
        )).awaitDurable()
    }

    suspend fun deleteAllData(deleteAccount: Boolean = false) = mutation.withLock {
        val uid = requireUid()
        val owner = user(uid)
        durableTransaction { transaction ->
            val existing = transaction.get(owner)
            val values = if (existing.exists()) emptyMap() else defaultUserValues()
            transaction.set(owner, values + mapOf(
                "deletionInProgress" to true,
                "sessionsDeletedBefore" to personas.associateWith { FieldValue.serverTimestamp() },
                "updatedAt" to FieldValue.serverTimestamp(),
            ), SetOptions.merge())
        }
        withContext(Dispatchers.IO) {
            if (deleteAccount) pending.clear(uid) else pending.clearSessions(uid)
        }
        for (collection in listOf("sessions", "englishScores", "memories", "intimacy")) {
            deleteCollection(owner.collection(collection))
        }
        if (deleteAccount) {
            owner.delete().awaitDurable()
            auth.currentUser?.delete()?.awaitDurable()
        } else {
            // Clear the deletion lock without replacing the account profile or preferences.
            owner.update(mapOf(
                "deletionInProgress" to FieldValue.delete(),
                "blockedPersonas" to FieldValue.delete(),
                "sessionsDeletedBefore" to personas.associateWith { FieldValue.serverTimestamp() },
                "updatedAt" to FieldValue.serverTimestamp(),
            )).awaitDurable()
        }
    }

    private suspend fun syncSession(uid: String, record: SessionRecord) {
        check(requireUid() == uid) { "Account changed" }
        val owner = user(uid)
        val sessionRef = owner.collection("sessions").document(record.id)
        val scoreRef = owner.collection("englishScores").document(record.id)
        val intimacyRef = owner.collection("intimacy").document(record.personaId)
        val awardDate = LocalDate.now().toString()
        durableTransaction { transaction ->
            val ownerData = transaction.get(owner)
            val existing = transaction.get(sessionRef)
            val score = transaction.get(scoreRef)
            val intimacy = transaction.get(intimacyRef)
            check(!ownerData.deletionPending(record.personaId)) { "Data deletion must finish first" }
            val deletedBefore = (ownerData.get("sessionsDeletedBefore") as? Map<*, *>)
                ?.get(record.personaId) as? Timestamp
            check(deletedBefore == null || record.endedAtMillis > deletedBefore.toDate().time) {
                "This session was removed by a data deletion"
            }
            val firstSave = existing.getBoolean("intimacyAwarded") != true
            val todayXp = if (intimacy.getString("dailyAwardDate") == awardDate) intimacy.int("dailyXp") else 0
            val xp = if (firstSave && record.userTurnCount >= 3) minOf(5, (20 - todayXp).coerceAtLeast(0)) else 0
            if (!ownerData.exists()) transaction.set(owner, defaultUserValues())
            transaction.set(sessionRef, sessionValues(record) + mapOf(
                "createdAt" to (existing.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
                "intimacyAwarded" to true,
                "xpAwarded" to (existing.int("xpAwarded") + xp),
                "memoryBonusAwarded" to (existing.getBoolean("memoryBonusAwarded") ?: false),
                "promiseBonusAwarded" to (existing.getBoolean("promiseBonusAwarded") ?: false),
                "memoryReviewStatus" to (existing.getString("memoryReviewStatus") ?: "pending"),
            ), SetOptions.merge())
            transaction.set(scoreRef, scoreValues(record) + mapOf(
                "createdAt" to (score.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
            ))
            if (firstSave) {
                val previousTime = intimacy.getTimestamp("lastSessionAt")
                val newTime = Timestamp(Date(record.endedAtMillis))
                val isLatest = previousTime == null || newTime > previousTime
                transaction.set(intimacyRef, intimacyValues(
                    record.personaId, intimacy.int("xp") + xp, intimacy.int("meetingCount") + 1,
                    awardDate, todayXp + xp,
                    if (isLatest) record.id else intimacy.getString("lastSessionId").orEmpty(),
                    if (isLatest) newTime else previousTime,
                ))
            }
        }
    }

    private suspend fun saveProfile(uid: String, profile: UserProfile) {
        withContext(Dispatchers.IO) {
            pending.putProfile(uid, profile)
            pending.putProfileCache(uid, profile)
        }
        ensurePrivateProfileRules()
        val owner = user(uid)
        durableTransaction(timeoutMillis = 12_000) { transaction ->
            val existing = transaction.get(owner)
            check(!existing.deletionPending()) { "Data deletion must finish first" }
            val defaults = if (existing.exists()) emptyMap() else defaultUserValues()
            transaction.set(owner, defaults + mapOf(
                "displayName" to profile.displayName.trim(),
                "birthDate" to profile.birthDate,
                "gender" to profile.gender.trim(),
                "profileComplete" to true,
                "updatedAt" to FieldValue.serverTimestamp(),
            ), SetOptions.merge())
        }
        check(requireUid() == uid) { "Account changed" }
        auth.currentUser?.updateProfile(
            UserProfileChangeRequest.Builder().setDisplayName(profile.displayName.trim()).build()
        )?.awaitDurable()
        withContext(Dispatchers.IO) { pending.clearProfile(uid) }
    }

    private suspend fun deleteCollection(query: Query) {
        while (true) {
            val page = query.limit(400).get(Source.SERVER).awaitResult()
            if (page.isEmpty) return
            val batch = db.batch()
            page.documents.forEach { batch.delete(it.reference) }
            batch.commit().awaitDurable()
        }
    }

    private suspend fun durableTransaction(
        timeoutMillis: Long = 15_000,
        block: (com.google.firebase.firestore.Transaction) -> Unit,
    ) {
        // A timed-out write is retried by ID, and deletion markers invalidate late commits.
        withContext(NonCancellable) { db.runTransaction { block(it) }.awaitResult(timeoutMillis) }
    }

    private fun user(uid: String = requireUid()) = db.collection("users").document(uid)

    private fun requireUid(): String = checkNotNull(auth.currentUser) { "Sign in first" }.uid

    private fun requirePersona(personaId: String) = require(personaId in personas)

    private fun requireRecordId(id: String) = require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))

    private fun validate(memory: ConfirmedMemory) {
        require(memory.userJob.length <= 120 && memory.lastPromise.length <= 240)
        require(memory.userLikes.size <= 5 && memory.userLikes.all { it.length <= 100 })
    }

    private fun validate(profile: UserProfile) {
        require(profile.displayName.trim().length in 1..80)
        require(profile.gender.trim().length in 1..40)
        val date = LocalDate.parse(profile.birthDate)
        require(date.toString() == profile.birthDate && !date.isAfter(LocalDate.now()))
    }

    private fun validate(record: SessionRecord) {
        requireRecordId(record.id)
        requirePersona(record.personaId)
        require(record.endedAtMillis >= record.startedAtMillis && record.activeConversationSec >= 0)
        require(record.userTurnCount >= 0 && record.englishWordCount >= 0)
        require(record.summaryKo.length <= 1200 && record.corrections.size <= 3)
        require(record.corrections.all {
            it.original.length <= 400 && it.natural.length <= 400 && it.explanationKo.length <= 240
        })
        require(listOf(record.scores.speechVolumeScore, record.scores.sentenceCompletenessScore,
            record.scores.expressionVarietyScore).all { it == null || it in 0..100 })
    }

    private companion object {
        val personas = listOf("alex", "sara")
    }
}

private fun defaultUserValues(): Map<String, Any> = mapOf(
    "schemaVersion" to 1,
    "englishLevel" to "beginner",
    "preferredPersonaId" to "alex",
    "personaSelectionMode" to "recommended",
    "consentVersion" to "2026-11-v1",
    "onboardingCompleted" to false,
    "aiConsentGranted" to false,
    "createdAt" to FieldValue.serverTimestamp(),
    "updatedAt" to FieldValue.serverTimestamp(),
)

private fun sessionValues(record: SessionRecord): Map<String, Any?> = mapOf(
    "schemaVersion" to 1,
    "personaId" to record.personaId,
    "status" to "completed",
    "startedAt" to Timestamp(Date(record.startedAtMillis)),
    "endedAt" to Timestamp(Date(record.endedAtMillis)),
    "activeConversationSec" to record.activeConversationSec,
    "userTurnCount" to record.userTurnCount,
    "englishWordCount" to record.englishWordCount,
    "summaryKo" to record.summaryKo,
    "corrections" to record.corrections.map {
        mapOf("original" to it.original, "natural" to it.natural, "explanationKo" to it.explanationKo)
    },
    "analysisStatus" to record.analysisStatus,
    "analysisModel" to record.analysisModel,
    "contextSource" to record.contextSources,
    "demoInputMode" to record.demoInputMode,
    "updatedAt" to FieldValue.serverTimestamp(),
)

private fun scoreValues(record: SessionRecord): Map<String, Any?> = mapOf(
    "sessionId" to record.id,
    "personaId" to record.personaId,
    "speechVolumeScore" to record.scores.speechVolumeScore,
    "sentenceCompletenessScore" to record.scores.sentenceCompletenessScore,
    "expressionVarietyScore" to record.scores.expressionVarietyScore,
    "englishWordCount" to record.englishWordCount,
    "userTurnCount" to record.userTurnCount,
    "activeConversationSec" to record.activeConversationSec,
    "completeSentenceCount" to record.scores.completeSentenceCount,
    "evaluableSentenceCount" to record.scores.evaluableSentenceCount,
    "unevaluableSentenceCount" to record.scores.unevaluableSentenceCount,
    "expressionCategories" to record.scores.expressionCategories,
    "sampleSufficient" to record.scores.sampleSufficient,
    "scoringVersion" to record.scores.scoringVersion,
    "analysisPromptVersion" to "summary-v1",
    "analysisModel" to record.analysisModel,
    "updatedAt" to FieldValue.serverTimestamp(),
)

private fun intimacyValues(
    personaId: String, xp: Int, meetingCount: Int, awardDate: String, dailyXp: Int,
    sessionId: String, lastSessionAt: Timestamp?,
): Map<String, Any?> = mapOf(
    "personaId" to personaId,
    "xp" to xp,
    "level" to when { xp >= 60 -> "bestFriend"; xp >= 20 -> "comfortable"; else -> "acquaintance" },
    "meetingCount" to meetingCount,
    "lastSessionId" to sessionId,
    "lastSessionAt" to lastSessionAt,
    "dailyAwardDate" to awardDate,
    "dailyXp" to dailyXp,
    "ruleVersion" to "intimacy-v1",
    "updatedAt" to FieldValue.serverTimestamp(),
)

private fun DocumentSnapshot.int(field: String): Int = (get(field) as? Number)?.toInt() ?: 0

private fun DocumentSnapshot.deletionPending(personaId: String? = null): Boolean =
    getBoolean("deletionInProgress") == true || personaId != null &&
        (get("blockedPersonas") as? List<*>)?.contains(personaId) == true

private fun DocumentSnapshot.toMemory(): ConfirmedMemory = ConfirmedMemory(
    userJob = getString("userJob").orEmpty(),
    userLikes = (get("userLikes") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    lastPromise = getString("lastPromise").orEmpty(),
)

private fun DocumentSnapshot.toScores(): ScoreRecord = ScoreRecord(
    speechVolumeScore = (get("speechVolumeScore") as? Number)?.toInt(),
    sentenceCompletenessScore = (get("sentenceCompletenessScore") as? Number)?.toInt(),
    expressionVarietyScore = (get("expressionVarietyScore") as? Number)?.toInt(),
    completeSentenceCount = int("completeSentenceCount"),
    evaluableSentenceCount = int("evaluableSentenceCount"),
    unevaluableSentenceCount = (get("unevaluableSentenceCount") as? Number)?.toInt()?.takeIf { it >= 0 },
    expressionCategories = (get("expressionCategories") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    sampleSufficient = getBoolean("sampleSufficient") ?: false,
    scoringVersion = getString("scoringVersion") ?: "english-v1",
)

private fun DocumentSnapshot.toSession(): SessionRecord = SessionRecord(
    id = id,
    personaId = getString("personaId") ?: "alex",
    startedAtMillis = getTimestamp("startedAt")?.toDate()?.time ?: 0,
    endedAtMillis = getTimestamp("endedAt")?.toDate()?.time ?: 0,
    activeConversationSec = (get("activeConversationSec") as? Number)?.toLong() ?: 0,
    userTurnCount = int("userTurnCount"),
    englishWordCount = int("englishWordCount"),
    summaryKo = getString("summaryKo").orEmpty(),
    corrections = (get("corrections") as? List<*>)?.mapNotNull { item ->
        val value = item as? Map<*, *> ?: return@mapNotNull null
        val original = value["original"] as? String ?: return@mapNotNull null
        val natural = value["natural"] as? String ?: return@mapNotNull null
        CorrectionRecord(original, natural, value["explanationKo"] as? String ?: "")
    }.orEmpty(),
    analysisStatus = getString("analysisStatus") ?: "unavailable",
    analysisModel = getString("analysisModel").orEmpty(),
    contextSources = (get("contextSource") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    demoInputMode = getString("demoInputMode") ?: "vehicle",
)

private suspend fun <T> Task<T>.awaitResult(timeoutMillis: Long = 12_000): T = withTimeout(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            when {
                task.isSuccessful -> continuation.resume(task.result)
                task.isCanceled -> continuation.cancel()
                else -> continuation.resumeWithException(task.exception ?: IllegalStateException("Cloud request failed"))
            }
        }
    }
}

private suspend fun <T> Task<T>.awaitDurable(): T = withContext(NonCancellable) { awaitResult() }
