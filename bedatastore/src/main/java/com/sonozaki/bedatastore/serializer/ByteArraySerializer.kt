package com.sonozaki.bedatastore.serializer

import androidx.datastore.core.Serializer
import com.sonozaki.bedatastore.encryption.EncryptedSerializer
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream

/**
 * "Serializer" for base datastore. Doesn't serialize anything in fact.
 */
internal class ByteArraySerializer<T>(
    private val default: T,
    private val encryptedSerializer: EncryptedSerializer<T>, ): Serializer<ByteArray> {
    override val defaultValue: ByteArray
        get() = runBlocking {
            encryptedSerializer.serializeAndEncrypt(default)
        }

    // Storage supplies defaultValue when the file is absent. Propagate read errors so
    // a later update cannot replace existing data based on a temporary fallback value.
    override suspend fun readFrom(input: InputStream): ByteArray = input.readBytes()

    override suspend fun writeTo(t: ByteArray, output: OutputStream) {
            output.write(t)
    }
}
