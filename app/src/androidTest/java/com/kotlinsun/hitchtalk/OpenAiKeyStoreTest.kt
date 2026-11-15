package com.kotlinsun.hitchtalk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kotlinsun.hitchtalk.ai.OpenAiKeyStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenAiKeyStoreTest {
    @Test
    fun keyIsEncryptedAndBoundToTheLocalAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val fixture = "sk-synthetic_fixture_key_for_storage_test"
        val directory = File(context.noBackupFilesDir, "openai")
        val store = OpenAiKeyStore(context)
        fun file(uid: String): File {
            val name = MessageDigest.getInstance("SHA-256")
                .digest(uid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            return File(directory, name)
        }
        try {
            assertNull(store.read(owner))
            store.write(owner, fixture)
            assertEquals(fixture, OpenAiKeyStore(context).read(owner))
            assertNull(store.read(other))
            val ciphertext = file(owner).readBytes()
            assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains(fixture))
            file(other).writeBytes(ciphertext)
            assertTrue(runCatching { store.read(other) }.isFailure)
            store.remove(owner)
            assertNull(store.read(owner))
        } finally {
            store.remove(owner)
            store.remove(other)
        }
    }

    @Test
    fun replacementAndRemovalPersistAcrossStoreInstances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = UUID.randomUUID().toString()
        val original = "sk-test-original-" + UUID.randomUUID()
        val replacement = "sk-test-replacement-" + UUID.randomUUID()
        val store = OpenAiKeyStore(context)
        try {
            assertNull(store.read(owner))
            store.write(owner, original)
            assertEquals(original, OpenAiKeyStore(context).read(owner))
            store.write(owner, replacement)
            assertEquals(replacement, OpenAiKeyStore(context).read(owner))
            store.remove(owner)
            assertNull(OpenAiKeyStore(context).read(owner))
            store.remove(owner)
            assertNull(OpenAiKeyStore(context).read(owner))
        } finally {
            store.remove(owner)
        }
    }
}
