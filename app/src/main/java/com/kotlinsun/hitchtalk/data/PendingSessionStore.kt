package com.kotlinsun.hitchtalk.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The repository serializes access and keeps a queue for each Firebase account. */
internal class PendingSessionStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "pending_sessions")

    fun load(uid: String): List<SessionRecord> {
        val file = file(uid)
        if (!file.baseFile.exists()) return emptyList()
        val records = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        return (0 until records.length()).map { sessionFromJson(records.getJSONObject(it)) }
    }

    fun put(uid: String, record: SessionRecord) {
        val records = load(uid).filterNot { it.id == record.id } + record
        write(uid, records)
    }

    fun remove(uid: String, sessionId: String) = write(uid, load(uid).filterNot { it.id == sessionId })

    fun removePersona(uid: String, personaId: String) =
        write(uid, load(uid).filterNot { it.personaId == personaId })

    fun loadProfile(uid: String): UserProfile? = readProfile(profileFile(uid))

    fun loadProfileCache(uid: String): UserProfile? = readProfile(profileFile(uid, cache = true))

    private fun readProfile(file: AtomicFile): UserProfile? {
        if (!file.baseFile.exists()) return null
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        return UserProfile(json.getString("displayName"), json.getString("birthDate"), json.getString("gender"))
    }

    fun putProfile(uid: String, profile: UserProfile) = writeProfile(profileFile(uid), profile)

    fun putProfileCache(uid: String, profile: UserProfile) = writeProfile(profileFile(uid, cache = true), profile)

    private fun writeProfile(file: AtomicFile, profile: UserProfile) {
        check(directory.exists() || directory.mkdirs()) { "Cannot prepare profile queue" }
        val stream = file.startWrite()
        try {
            val json = JSONObject().put("displayName", profile.displayName)
                .put("birthDate", profile.birthDate).put("gender", profile.gender)
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun clearProfile(uid: String) = profileFile(uid).delete()

    fun clearProfileCache(uid: String) = profileFile(uid, cache = true).delete()

    fun clearSessions(uid: String) = file(uid).delete()

    fun clear(uid: String) {
        clearSessions(uid)
        clearProfile(uid)
        clearProfileCache(uid)
    }

    private fun profileFile(uid: String, cache: Boolean = false): AtomicFile {
        require(uid.matches(Regex("[A-Za-z0-9_-]+")))
        val suffix = if (cache) "profile_cache" else "profile"
        return AtomicFile(File(directory, "${uid}_$suffix.json"))
    }

    private fun file(uid: String): AtomicFile {
        require(uid.matches(Regex("[A-Za-z0-9_-]+")))
        return AtomicFile(File(directory, "$uid.json"))
    }

    private fun write(uid: String, records: List<SessionRecord>) {
        check(directory.exists() || directory.mkdirs()) { "Cannot prepare session queue" }
        val file = file(uid)
        val stream = file.startWrite()
        try {
            val array = JSONArray()
            records.forEach { array.put(sessionToJson(it)) }
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

private fun sessionToJson(record: SessionRecord): JSONObject = JSONObject().apply {
    put("id", record.id)
    put("personaId", record.personaId)
    put("startedAtMillis", record.startedAtMillis)
    put("endedAtMillis", record.endedAtMillis)
    put("activeConversationSec", record.activeConversationSec)
    put("userTurnCount", record.userTurnCount)
    put("englishWordCount", record.englishWordCount)
    put("summaryKo", record.summaryKo)
    put("corrections", JSONArray().apply {
        record.corrections.forEach { correction ->
            put(JSONObject()
                .put("original", correction.original)
                .put("natural", correction.natural)
                .put("explanationKo", correction.explanationKo))
        }
    })
    put("scores", JSONObject().apply {
        put("speechVolumeScore", record.scores.speechVolumeScore ?: JSONObject.NULL)
        put("sentenceCompletenessScore", record.scores.sentenceCompletenessScore ?: JSONObject.NULL)
        put("expressionVarietyScore", record.scores.expressionVarietyScore ?: JSONObject.NULL)
        put("completeSentenceCount", record.scores.completeSentenceCount)
        put("evaluableSentenceCount", record.scores.evaluableSentenceCount)
        put("unevaluableSentenceCount", record.scores.unevaluableSentenceCount ?: JSONObject.NULL)
        put("expressionCategories", JSONArray(record.scores.expressionCategories))
        put("sampleSufficient", record.scores.sampleSufficient)
        put("scoringVersion", record.scores.scoringVersion)
    })
    put("analysisStatus", record.analysisStatus)
    put("analysisModel", record.analysisModel)
    put("contextSources", JSONArray(record.contextSources))
    put("demoInputMode", record.demoInputMode)
}

private fun sessionFromJson(json: JSONObject): SessionRecord {
    val scores = json.optJSONObject("scores") ?: JSONObject()
    val corrections = json.optJSONArray("corrections") ?: JSONArray()
    return SessionRecord(
        id = json.getString("id"),
        personaId = json.getString("personaId"),
        startedAtMillis = json.getLong("startedAtMillis"),
        endedAtMillis = json.getLong("endedAtMillis"),
        activeConversationSec = json.optLong("activeConversationSec"),
        userTurnCount = json.optInt("userTurnCount"),
        englishWordCount = json.optInt("englishWordCount"),
        summaryKo = json.optString("summaryKo"),
        corrections = (0 until corrections.length()).map { index ->
            val item = corrections.getJSONObject(index)
            CorrectionRecord(item.getString("original"), item.getString("natural"), item.optString("explanationKo"))
        },
        scores = ScoreRecord(
            speechVolumeScore = scores.nullableInt("speechVolumeScore"),
            sentenceCompletenessScore = scores.nullableInt("sentenceCompletenessScore"),
            expressionVarietyScore = scores.nullableInt("expressionVarietyScore"),
            completeSentenceCount = scores.optInt("completeSentenceCount"),
            evaluableSentenceCount = scores.optInt("evaluableSentenceCount"),
            unevaluableSentenceCount = scores.nullableInt("unevaluableSentenceCount"),
            expressionCategories = scores.optJSONArray("expressionCategories").strings(),
            sampleSufficient = scores.optBoolean("sampleSufficient"),
            scoringVersion = scores.optString("scoringVersion", "english-v1"),
        ),
        analysisStatus = json.optString("analysisStatus", "unavailable"),
        analysisModel = json.optString("analysisModel"),
        contextSources = json.optJSONArray("contextSources").strings(),
        demoInputMode = json.optString("demoInputMode", "vehicle"),
    )
}

private fun JSONObject.nullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }
