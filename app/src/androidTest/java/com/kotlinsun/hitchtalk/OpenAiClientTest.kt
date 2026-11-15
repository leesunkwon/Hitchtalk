package com.kotlinsun.hitchtalk

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kotlinsun.hitchtalk.ai.OpenAiConversationClient
import com.kotlinsun.hitchtalk.ai.OpenAiException
import com.kotlinsun.hitchtalk.ai.OpenAiFailure
import com.kotlinsun.hitchtalk.domain.DialogueRequest
import com.kotlinsun.hitchtalk.domain.DialogueTurn
import com.kotlinsun.hitchtalk.domain.ScoreCalculator
import com.kotlinsun.hitchtalk.domain.SummaryRequest
import com.kotlinsun.hitchtalk.domain.TurnRole
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenAiClientTest {
    private val turns = listOf(
        DialogueTurn("synthetic-one", TurnRole.USER, "I like apples."),
        DialogueTurn("synthetic-two", TurnRole.ASSISTANT, "That sounds good."),
    )

    @Test
    fun dialogueSendsAllowedContextInAResponsesRequest() = runBlocking {
        val response = """
            {"replyEn":"Do you like pears?","shadowCorrection":null,"memoryCandidates":[],"topicTag":"food"}
        """.trimIndent()
        lateinit var connection: StubConnection
        val client = client { url -> StubConnection(url, envelope(response)).also { connection = it } }

        val reply = client.reply(
            DialogueRequest(
                personaId = "alex",
                turns = turns,
                difficulty = "intermediate",
                confirmedMemories = listOf("Likes hiking"),
                vehicleFacts = mapOf("batteryPercent" to "78", "location" to "private"),
            )
        )

        assertEquals("Do you like pears?", reply.replyEn)
        assertEquals("https://api.openai.com/v1/responses", connection.url.toString())
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer fixture-api-key", connection.getRequestProperty("Authorization"))
        assertEquals("application/json; charset=utf-8", connection.getRequestProperty("Content-Type"))
        assertFalse(connection.instanceFollowRedirects)
        val body = connection.requestBody()
        assertStructuredRequest(body, "hitchtalk_dialogue")
        val input = body.getJSONArray("input")
        val context = JSONObject(input.getJSONObject(0).getString("content").removePrefix("Context data only: "))
        assertEquals("Alex", context.getJSONObject("persona").getString("name"))
        assertEquals("intermediate", context.getString("difficulty"))
        val firstTurn = input.getJSONObject(1)
        assertEquals("user", firstTurn.getString("role"))
        val firstContent = JSONObject(firstTurn.getString("content"))
        assertEquals("synthetic-one", firstContent.getString("turnId"))
        assertEquals("I like apples.", firstContent.getString("text"))
        assertEquals("assistant", input.getJSONObject(2).getString("role"))
        assertEquals("Likes hiking", context.getJSONArray("confirmedMemories").getString(0))
        assertEquals(setOf("batteryPercent"), context.getJSONObject("vehicleFacts").keys().asSequence().toSet())
    }

    @Test
    fun summaryUsesSentenceEvidenceAndTheServerModel() = runBlocking {
        val sentenceId = ScoreCalculator.userSentences(turns).single().id
        val response = """
            {
                "summaryKo":"과일에 대한 선호를 말했습니다.",
                "corrections":[],
                "memoryCandidates":[],
                "sentenceAssessments":[{"sentenceId":"$sentenceId","verdict":"complete"}],
                "expressionEvidence":[{"category":"preference","sentenceIds":["$sentenceId"]}]
            }
        """.trimIndent()
        lateinit var connection: StubConnection
        val client = client { url -> StubConnection(url, envelope(response)).also { connection = it } }

        val summary = client.summarize(SummaryRequest("sara", turns, activeConversationSec = 120))

        assertEquals("fixture-model-version", summary.analysisModel)
        assertEquals(sentenceId, summary.sentenceAssessments.single().sentenceId)
        assertEquals(listOf(sentenceId), summary.expressionEvidence.single().sentenceIds)
        assertEquals("https://api.openai.com/v1/responses", connection.url.toString())
        val body = connection.requestBody()
        assertStructuredRequest(body, "hitchtalk_summary")
        val input = body.getJSONArray("input").getJSONObject(0)
        assertEquals("user", input.getString("role"))
        val context = JSONObject(input.getString("content"))
        assertEquals(
            setOf("personaId", "activeConversationSec", "userSentences", "recentContext"),
            context.keys().asSequence().toSet(),
        )
        assertEquals(120, context.getInt("activeConversationSec"))
        assertEquals(sentenceId, context.getJSONArray("userSentences").getJSONObject(0).getString("sentenceId"))
        assertEquals("assistant", context.getJSONArray("recentContext").getJSONObject(1).getString("role"))
    }

    @Test
    fun safeReplySurvivesUngroundedExtrasWithoutKeepingThem() = runBlocking {
        val response = """
            {
                "replyEn":"Do you like pears?",
                "shadowCorrection":{"original":"A sentence the user never said.","natural":"A different sentence.","explanationKo":""},
                "memoryCandidates":[
                    {"kind":"userLike","text":"Likes pears.","sourceTurnId":"synthetic-two"},
                    {"kind":"userLike","text":"Likes apples.","sourceTurnId":"synthetic-one"}
                ],
                "topicTag":"fruit and food"
            }
        """.trimIndent()
        val client = client { url -> StubConnection(url, envelope(response)) }

        val reply = client.reply(DialogueRequest("alex", turns))

        assertEquals("Do you like pears?", reply.replyEn)
        assertEquals(null, reply.correction)
        assertEquals("synthetic-one", reply.memoryCandidates.single().sourceTurnId)
        assertEquals("general", reply.topicTag)
    }

    @Test
    fun requiresConfiguredKeyBeforeOpeningConnection() = runBlocking {
        val client = OpenAiConversationClient({ null }) { error("No request should be sent without a key") }
        assertFalse(client.isConfigured)
        val failure = runCatching { client.reply(DialogueRequest("alex", turns)) }.exceptionOrNull()
        assertEquals(OpenAiFailure.CONFIGURATION, (failure as? OpenAiException)?.kind)
    }

    @Test
    fun rejectsInvalidHeaderValueBeforeOpeningConnection() = runBlocking {
        val client = OpenAiConversationClient({ "fixture-api-key\r\nX-Fake: injected" }) {
            error("No request should be sent with an invalid header value")
        }
        assertFalse(client.isConfigured)
        val failure = runCatching { client.reply(DialogueRequest("alex", turns)) }.exceptionOrNull()
        assertEquals(OpenAiFailure.CONFIGURATION, (failure as? OpenAiException)?.kind)
    }

    @Test
    fun rejectsRedirectWithoutSendingAnotherRequest() = runBlocking {
        val connections = mutableListOf<StubConnection>()
        val client = client { url -> StubConnection(url, "", status = 307).also(connections::add) }

        val failure = runCatching { client.reply(DialogueRequest("alex", turns)) }.exceptionOrNull()

        assertEquals(OpenAiFailure.REJECTED, (failure as? OpenAiException)?.kind)
        assertFalse(connections.single().instanceFollowRedirects)
    }

    @Test
    fun buildConfigHasNoBundledOpenAiCredentialField() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val config = Class.forName(context.packageName + ".BuildConfig")
        assertFalse(config.declaredFields.any { it.name == "OPENAI_API_KEY" })
    }

    @Test
    fun credentialRegistrationReplacementAndRemovalUseTheCurrentLocalValue() = runBlocking {
        var credential: String? = null
        val connections = mutableListOf<StubConnection>()
        val response = """
            {"replyEn":"Do you like pears?","shadowCorrection":null,"memoryCandidates":[],"topicTag":"food"}
        """.trimIndent()
        val client = OpenAiConversationClient({ credential }) { url ->
            StubConnection(url, envelope(response)).also(connections::add)
        }
        val request = DialogueRequest("alex", turns)

        assertFalse(client.isConfigured)
        val initialFailure = runCatching { client.reply(request) }.exceptionOrNull()
        assertEquals(OpenAiFailure.CONFIGURATION, (initialFailure as? OpenAiException)?.kind)
        assertTrue(connections.isEmpty())

        credential = "fixture-first-local-key"
        assertTrue(client.isConfigured)
        client.reply(request)
        assertEquals("Bearer fixture-first-local-key", connections.last().getRequestProperty("Authorization"))

        credential = "fixture-replacement-local-key"
        client.reply(request)
        assertEquals("Bearer fixture-replacement-local-key", connections.last().getRequestProperty("Authorization"))
        assertEquals(2, connections.size)

        credential = null
        assertFalse(client.isConfigured)
        val removedFailure = runCatching { client.reply(request) }.exceptionOrNull()
        assertEquals(OpenAiFailure.CONFIGURATION, (removedFailure as? OpenAiException)?.kind)
        assertEquals(2, connections.size)
    }

    @Test
    fun connectionFailuresDistinguishInvalidCredentialsAndRateLimits() = runBlocking {
        val failures = listOf(401 to OpenAiFailure.AUTHENTICATION, 429 to OpenAiFailure.RATE_LIMIT)
        failures.forEach { (status, expected) ->
            val client = client { url -> StubConnection(url, "", status = status) }
            val failure = runCatching { client.reply(DialogueRequest("alex", turns)) }.exceptionOrNull()
            assertEquals(expected, (failure as? OpenAiException)?.kind)
        }
    }

    private fun client(openConnection: (URL) -> HttpURLConnection) =
        OpenAiConversationClient({ "fixture-api-key" }, openConnection).also { assertTrue(it.isConfigured) }

    private fun assertStructuredRequest(body: JSONObject, schemaName: String) {
        assertEquals("gpt-4.1-mini", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        assertFalse(body.toString().contains("fixture-api-key"))
        val format = body.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertEquals(schemaName, format.getString("name"))
        assertTrue(format.getBoolean("strict"))
        assertFalse(format.getJSONObject("schema").getBoolean("additionalProperties"))
    }

    private fun envelope(text: String) = JSONObject()
        .put("status", "completed")
        .put("model", "fixture-model-version")
        .put("output", JSONArray().put(JSONObject()
            .put("type", "message")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))))
        .toString()

    private class StubConnection(
        url: URL,
        private val response: String,
        private val status: Int = 200,
    ) : HttpURLConnection(url) {
        private val sent = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))

        fun requestBody() = JSONObject(sent.toString(Charsets.UTF_8.name()))
    }
}
