package com.kotlinsun.hitchtalk.ai

import com.kotlinsun.hitchtalk.domain.Correction
import com.kotlinsun.hitchtalk.domain.DialogueReply
import com.kotlinsun.hitchtalk.domain.DialogueRequest
import com.kotlinsun.hitchtalk.domain.DialogueTurn
import com.kotlinsun.hitchtalk.domain.ExpressionEvidence
import com.kotlinsun.hitchtalk.domain.MemoryCandidate
import com.kotlinsun.hitchtalk.domain.ScoreCalculator
import com.kotlinsun.hitchtalk.domain.SentenceAssessment
import com.kotlinsun.hitchtalk.domain.SentenceVerdict
import com.kotlinsun.hitchtalk.domain.SessionAnalysis
import com.kotlinsun.hitchtalk.domain.SummaryRequest
import com.kotlinsun.hitchtalk.domain.TurnRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class OpenAiFailure { CONFIGURATION, AUTHENTICATION, RATE_LIMIT, NETWORK, TIMEOUT, INVALID_RESPONSE, UNAVAILABLE, REJECTED }

class OpenAiException(val kind: OpenAiFailure) : Exception(kind.name)

/** Uses only text from finalized recognition results. No request or response bodies are logged. */
class OpenAiConversationClient internal constructor(
    private val apiKeyProvider: () -> String?,
    private val openConnection: (URL) -> HttpURLConnection,
) {
    constructor(apiKeyProvider: () -> String?) : this(apiKeyProvider, { it.openConnection() as HttpURLConnection })

    val isConfigured: Boolean get() = configuredApiKey() != null

    private fun configuredApiKey(): String? = apiKeyProvider()?.takeIf { key ->
        key.isNotEmpty() && key.all { it in '!'..'~' }
    }

    suspend fun reply(request: DialogueRequest): DialogueReply {
        val turns = request.turns.filter { it.text.isNotBlank() }.distinctBy { it.id }.takeLast(8)
        val payload = JSONObject()
            .put("personaId", request.personaId)
            .put("turns", JSONArray(turns.map { turn ->
                JSONObject()
                    .put("id", turn.id)
                    .put("role", turn.role.name.lowercase(Locale.ROOT))
                    .put("text", turn.text.take(1000))
            }))
            .put("difficulty", request.difficulty.takeIf { it in difficulties } ?: "beginner")
            .put("intimacyLevel", request.intimacyLevel.take(40))
            .put("confirmedMemories", JSONArray(request.confirmedMemories.take(5).map { it.take(240) }))
            .put("vehicleFacts", JSONObject(request.vehicleFacts.filterKeys { it in allowedFacts }.mapValues { it.value.take(80) }))
            .put("proactive", request.proactive)
        val reply = execute(OpenAiRequestPayload.dialogue(payload), 10_000) { json, _ -> parseReply(json, turns) }
        return if (request.proactive) reply.copy(correction = null, memoryCandidates = emptyList()) else reply
    }

    suspend fun summarize(request: SummaryRequest): SessionAnalysis {
        val sentences = ScoreCalculator.userSentences(request.turns).takeLast(80)
        val payload = JSONObject()
            .put("personaId", request.personaId)
            .put("activeConversationSec", request.activeConversationSec.coerceAtLeast(0))
            .put("userSentences", JSONArray(sentences.map {
                JSONObject().put("sentenceId", it.id).put("turnId", it.turnId).put("text", it.text.take(800))
            }))
            .put("recentContext", JSONArray(request.turns.takeLast(8).map {
                JSONObject().put("role", it.role.name.lowercase(Locale.ROOT)).put("text", it.text.take(1000))
            }))
        val sentenceIds = sentences.map { it.id }.toSet()
        return execute(OpenAiRequestPayload.summary(payload), 17_000) { json, envelope ->
            parseAnalysis(json, request.turns, sentenceIds, text(envelope, "model", 128))
        }
    }

    private suspend fun <T> execute(
        payload: JSONObject,
        timeoutMs: Long,
        parse: (JSONObject, JSONObject) -> T,
    ): T {
        val requestId = UUID.randomUUID().toString()
        return withTimeoutOrNull(timeoutMs) {
            val apiKey = withContext(Dispatchers.IO) { configuredApiKey() }
                ?: throw OpenAiException(OpenAiFailure.CONFIGURATION)
            var attempt = 0
            while (true) {
                try {
                    val response = post(apiKey, payload, timeoutMs.toInt(), requestId)
                    return@withTimeoutOrNull parse(JSONObject(responseText(response)), response)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val failure = when (error) {
                        is OpenAiException -> error
                        is IOException -> OpenAiException(OpenAiFailure.NETWORK)
                        is JSONException, is IllegalArgumentException -> OpenAiException(OpenAiFailure.INVALID_RESPONSE)
                        else -> throw error
                    }
                    if (attempt == 1 || failure.kind !in retryableFailures) throw failure
                    attempt++
                    delay(250)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw OpenAiException(OpenAiFailure.UNAVAILABLE)
        } ?: throw OpenAiException(OpenAiFailure.TIMEOUT)
    }

    private suspend fun post(apiKey: String, payload: JSONObject, timeoutMs: Int, requestId: String): JSONObject =
        suspendCancellableCoroutine { continuation ->
            val activeConnection = AtomicReference<HttpURLConnection?>()
            continuation.invokeOnCancellation { activeConnection.getAndSet(null)?.disconnect() }
            CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                try {
                    if (!continuation.isActive) return@launch
                    val connection = openConnection(responsesUrl)
                    activeConnection.set(connection)
                    if (!continuation.isActive) return@launch
                    connection.requestMethod = "POST"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = timeoutMs
                    connection.readTimeout = timeoutMs
                    connection.doOutput = true
                    connection.useCaches = false
                    connection.setRequestProperty("Authorization", "Bearer $apiKey")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("X-Client-Request-Id", requestId)
                    val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bodyBytes.size)
                    connection.outputStream.use { it.write(bodyBytes) }
                    val status = connection.responseCode
                    if (status !in 200..299) throw OpenAiException(
                        when (status) {
                            401, 403 -> OpenAiFailure.AUTHENTICATION
                            429 -> OpenAiFailure.RATE_LIMIT
                            in 300..499 -> OpenAiFailure.REJECTED
                            in 500..599 -> OpenAiFailure.UNAVAILABLE
                            else -> OpenAiFailure.INVALID_RESPONSE
                        }
                    )
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val result = StringBuilder()
                        val buffer = CharArray(4096)
                        var count = reader.read(buffer)
                        while (count >= 0) {
                            result.append(buffer, 0, count)
                            if (result.length > 100_000) invalid()
                            count = reader.read(buffer)
                        }
                        result.toString()
                    }
                    if (continuation.isActive) continuation.resume(JSONObject(body))
                } catch (cancelled: CancellationException) {
                    continuation.cancel(cancelled)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    activeConnection.getAndSet(null)?.disconnect()
                }
            }
        }

    private fun responseText(response: JSONObject): String {
        if (response.optString("status") != "completed") invalid()
        val result = StringBuilder()
        val output = response.getJSONArray("output")
        for (index in 0 until output.length()) {
            val item = output.getJSONObject(index)
            if (item.optString("type") != "message") continue
            val content = item.getJSONArray("content")
            for (partIndex in 0 until content.length()) {
                val part = content.getJSONObject(partIndex)
                if (part.optString("type") == "refusal") throw OpenAiException(OpenAiFailure.REJECTED)
                if (part.optString("type") == "output_text") result.append(part.getString("text"))
            }
        }
        if (result.isBlank()) invalid()
        return result.toString()
    }

    private fun parseReply(json: JSONObject, turns: List<DialogueTurn>): DialogueReply {
        requireFields(json, setOf("replyEn", "shadowCorrection", "memoryCandidates", "topicTag"))
        val reply = text(json, "replyEn", 420)
        if (countSentences(reply) !in 1..2 || reply.count { it == '?' } > 1 || ScoreCalculator.englishWordCount(reply) !in 1..60 ||
            reply.any { it in '\uAC00'..'\uD7A3' } || unsafeReply.containsMatchIn(reply)
        ) invalid()
        val correction = json.optJSONObject("shadowCorrection")?.let { optionalModelData { parseCorrection(it, turns) } }
        val topic = optionalModelData { text(json, "topicTag", 40) }?.takeIf(topicIdentifier::matches) ?: "general"
        val memories = json.optJSONArray("memoryCandidates")?.let { parseMemories(it, turns) }.orEmpty()
        return DialogueReply(reply, correction, memories, topic)
    }

    private fun countSentences(text: String): Int {
        val iterator = BreakIterator.getSentenceInstance(Locale.US)
        iterator.setText(text)
        var count = 0
        var boundary = iterator.first()
        var next = iterator.next()
        while (next != BreakIterator.DONE) {
            if (text.substring(boundary, next).isNotBlank()) count++
            boundary = next
            next = iterator.next()
        }
        return count
    }

    private fun parseAnalysis(
        json: JSONObject,
        turns: List<DialogueTurn>,
        sentenceIds: Set<String>,
        analysisModel: String,
    ): SessionAnalysis {
        requireFields(json, setOf("summaryKo", "corrections", "memoryCandidates", "sentenceAssessments", "expressionEvidence"))
        val corrections = json.getJSONArray("corrections")
        val assessments = json.getJSONArray("sentenceAssessments")
        val expressions = json.getJSONArray("expressionEvidence")
        if (corrections.length() > 3 || assessments.length() > 80 || expressions.length() > 8) invalid()
        val parsedAssessments = (0 until assessments.length()).map { index ->
            val assessment = assessments.getJSONObject(index)
            requireFields(assessment, setOf("sentenceId", "verdict"))
            val id = text(assessment, "sentenceId", 120)
            if (id !in sentenceIds) invalid()
            SentenceAssessment(id, when (assessment.getString("verdict")) {
                "complete" -> SentenceVerdict.COMPLETE
                "partial" -> SentenceVerdict.PARTIAL
                "unassessable" -> SentenceVerdict.UNASSESSABLE
                else -> invalid()
            })
        }
        if (parsedAssessments.map { it.sentenceId }.distinct().size != parsedAssessments.size) invalid()
        val parsedExpressions = (0 until expressions.length()).map { index ->
            val expression = expressions.getJSONObject(index)
            requireFields(expression, setOf("category", "sentenceIds"))
            val category = text(expression, "category", 40)
            val evidence = expression.getJSONArray("sentenceIds")
            if (category !in ScoreCalculator.expressionCategories || evidence.length() !in 1..80) invalid()
            val ids = (0 until evidence.length()).map { evidence.getString(it) }
            if (ids.any { it !in sentenceIds }) invalid()
            ExpressionEvidence(category, ids.distinct())
        }
        return SessionAnalysis(
            summaryKo = text(json, "summaryKo", 600),
            corrections = (0 until corrections.length()).mapNotNull { index ->
                optionalModelData { parseCorrection(corrections.getJSONObject(index), turns) }
            },
            memoryCandidates = parseMemories(json.getJSONArray("memoryCandidates"), turns),
            sentenceAssessments = parsedAssessments,
            expressionEvidence = parsedExpressions,
            analysisModel = analysisModel,
        )
    }

    private fun parseCorrection(json: JSONObject, turns: List<DialogueTurn>): Correction {
        requireFields(json, setOf("original", "natural", "explanationKo"))
        val original = text(json, "original", 300)
        if (turns.none { it.role == TurnRole.USER && it.text.contains(original) }) invalid()
        val natural = text(json, "natural", 300)
        val explanation = text(json, "explanationKo", 240, allowEmpty = true)
        if (listOf(original, natural, explanation).any(sensitiveMemory::containsMatchIn)) invalid()
        return Correction(original, natural, explanation)
    }

    private fun parseMemories(array: JSONArray, turns: List<DialogueTurn>): List<MemoryCandidate> {
        if (array.length() > 3) return emptyList()
        val userIds = turns.filter { it.role == TurnRole.USER }.map { it.id }.toSet()
        return (0 until array.length()).mapNotNull { index ->
            optionalModelData {
                val item = array.getJSONObject(index)
                requireFields(item, setOf("kind", "text", "sourceTurnId"))
                val kind = text(item, "kind", 40)
                val maxLength = when (kind) {
                    "userJob" -> 120
                    "userLike" -> 100
                    "lastPromise" -> 240
                    else -> invalid()
                }
                val value = text(item, "text", maxLength)
                val sourceId = text(item, "sourceTurnId", 120)
                if (sourceId !in userIds || sensitiveMemory.containsMatchIn(value)) invalid()
                MemoryCandidate(kind, value, sourceId)
            }
        }.distinctBy { it.kind to it.text }
    }

    private inline fun <T> optionalModelData(parse: () -> T): T? = try {
        parse()
    } catch (_: JSONException) {
        null
    } catch (error: OpenAiException) {
        if (error.kind != OpenAiFailure.INVALID_RESPONSE) throw error
        null
    }

    private fun text(json: JSONObject, name: String, maxLength: Int, allowEmpty: Boolean = false): String {
        val value = json.getString(name).trim()
        if (value.length > maxLength || (!allowEmpty && value.isBlank()) || value.any { it == '\u0000' }) invalid()
        return value
    }

    private fun requireFields(json: JSONObject, fields: Set<String>) {
        if (json.keys().asSequence().toSet() != fields) invalid()
    }

    private fun invalid(): Nothing = throw OpenAiException(OpenAiFailure.INVALID_RESPONSE)

    private companion object {
        val responsesUrl = URL("https://api.openai.com/v1/responses")
        val difficulties = setOf("beginner", "intermediate", "advanced")
        val topicIdentifier = Regex("[a-zA-Z][a-zA-Z0-9_-]*")
        val allowedFacts = setOf("hvacOn", "temperatureC", "batteryPercent", "hvac", "battery", "localTime")
        val retryableFailures = setOf(OpenAiFailure.NETWORK, OpenAiFailure.RATE_LIMIT, OpenAiFailure.UNAVAILABLE, OpenAiFailure.INVALID_RESPONSE)
        val unsafeReply = Regex(
            "\\b(tap|click|swipe|screen|keyboard|speed up|drive faster|disable (the )?(airbag|brake)|turn off (the )?(airbag|headlights))\\b",
            RegexOption.IGNORE_CASE,
        )
        val sensitiveMemory = Regex(
            "\\d{3}|@|https?://|password|api.?key|secret|credit.?card|bank.?account|diagnos|medical|health.?condition|exact.?address|ignore previous|system.?prompt|비밀번호|주민|계좌|카드번호|주소|진단|질병|건강|지시|명령",
            RegexOption.IGNORE_CASE,
        )

    }
}
