package com.kotlinsun.hitchtalk.data

import com.google.firebase.FirebaseApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class PublicProfileRulesException : IllegalStateException("Private profile rules are required")

/** Reads only the status of a fixed nonpersonal marker, with no Auth header or response body. */
internal suspend fun checkPrivateProfileRules() = withTimeout(5_000) {
    withContext(Dispatchers.IO) {
        val projectId = checkNotNull(FirebaseApp.getInstance().options.projectId)
        val address = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)" +
            "/documents/users/hitchtalk-privacy-check?mask.fieldPaths=schemaVersion"
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 2_500
            connection.readTimeout = 2_500
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            when (connection.responseCode) {
                HttpURLConnection.HTTP_FORBIDDEN -> Unit
                HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_NOT_FOUND -> throw PublicProfileRulesException()
                else -> throw IOException("Profile privacy check unavailable")
            }
        } finally {
            connection.disconnect()
        }
    }
}
