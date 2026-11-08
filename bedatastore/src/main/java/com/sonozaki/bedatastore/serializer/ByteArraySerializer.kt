package com.sonozaki.bedatastore.serializer

import androidx.datastore.core.Serializer
import com.sonozaki.bedatastore.encryption.EncryptedSerializer
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream

/** Validates data read from storage while keeping the backing DataStore's values encrypted. */
internal class ByteArraySerializer<T>(
    private val default: T,
    private val encryptedSerializer: EncryptedSerializer<T>, ): Serializer<ByteArray> {
    override val defaultValue: ByteArray
        get() = runBlocking {
            encryptedSerializer.serializeAndEncrypt(default)
        }

    override suspend fun readFrom(input: InputStream): ByteArray {
        val ciphertext = input.readBytes()
        // AndroidX can recover from a CorruptionException only if it sees it during
        // its own read. Validate the payload here and return only the ciphertext.
        encryptedSerializer.decryptAndDeserialize(ciphertext)
        return ciphertext
    }

    override suspend fun writeTo(t: ByteArray, output: OutputStream) {
            output.write(t)
    }
}
