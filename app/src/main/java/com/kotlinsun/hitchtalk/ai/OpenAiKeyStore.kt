package com.kotlinsun.hitchtalk.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local to this installation and Firebase account. No cloud or backup writes. */
internal class OpenAiKeyStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "openai")

    @WorkerThread
    @Synchronized
    fun read(uid: String): String? {
        val bytes = try {
            file(uid).openRead().use { input ->
                val buffer = ByteArray(maxKeyLength + 1 + ivLength + tagLength + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                buffer.copyOf(count)
            }
        } catch (_: FileNotFoundException) {
            return null
        }
        if (bytes.size !in (1 + ivLength + tagLength + minKeyLength)..(1 + ivLength + tagLength + maxKeyLength) ||
            bytes[0] != version
        ) return null
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(create = false) ?: return null, GCMParameterSpec(128, bytes, 1, ivLength))
        cipher.updateAAD(uid.toByteArray(Charsets.UTF_8))
        val plaintext = cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength)
        return try {
            plaintext.toString(Charsets.US_ASCII).takeIf(::isValid)
        } finally {
            plaintext.fill(0)
        }
    }

    @WorkerThread
    @Synchronized
    fun write(uid: String, value: String) {
        require(isValid(value))
        check(directory.isDirectory || directory.mkdirs())
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, checkNotNull(encryptionKey(create = true)))
        cipher.updateAAD(uid.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == ivLength)
        val plaintext = value.toByteArray(Charsets.US_ASCII)
        val encrypted = try {
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val file = file(uid)
        val output = file.startWrite()
        try {
            output.write(version.toInt())
            output.write(cipher.iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @WorkerThread
    @Synchronized
    fun remove(uid: String) {
        val file = file(uid)
        file.delete()
        check(!file.baseFile.exists())
    }

    private fun file(uid: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
        val suffix = digest.joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, suffix))
    }

    private fun encryptionKey(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(provider).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    companion object {
        private const val provider = "AndroidKeyStore"
        private const val alias = "hitchtalk.openai.aes.v1"
        private const val transformation = "AES/GCM/NoPadding"
        private const val version: Byte = 1
        private const val ivLength = 12
        private const val tagLength = 16
        private const val minKeyLength = 16
        private const val maxKeyLength = 512
        private val keyPattern = Regex("sk-[A-Za-z0-9_-]+")

        fun isValid(value: String): Boolean = value.length in minKeyLength..maxKeyLength && keyPattern.matches(value)
    }
}
