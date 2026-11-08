/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sonozaki.bedatastore.datastore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreDataStoreTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fileName = "keystore-test-${UUID.randomUUID()}"
    private val alias = "$fileName-key"
    private val scopes = mutableListOf<CoroutineScope>()
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After
    fun cleanUp() = runBlocking<Unit> {
        for (scope in scopes) scope.coroutineContext.job.cancelAndJoin()
        context.dataStoreFile(fileName, false).delete()
        context.dataStoreFile(fileName, true).delete()
        keyStore.deleteEntry(alias)
    }

    // Check the full path from the public delegate to disk. The file should hide the value,
    // and a fresh store should recover it using a key whose raw bytes the app cannot export.
    @Test
    fun encryptedFileUsesAKeystoreKeyAndCanBeReopened() = runBlocking<Unit> {
        val store = open()
        val secret = "private value stored by the application"
        assertEquals(secret, store.updateData { secret })

        val persisted = context.dataStoreFile(fileName, false).readBytes()
        assertFalse(persisted.toString(Charsets.ISO_8859_1).contains(secret))
        val key = requireNotNull(keyStore.getKey(alias, null))
        assertEquals("AES", key.algorithm)
        assertNull("Android Keystore keys must not expose raw key bytes", key.encoded)

        closeLatest()
        assertEquals(secret, open().data.first())
    }

    // Adapted from DataStoreDelegateTest.testCreateSameTwiceThrowsException.
    // https://github.com/androidx/androidx/blob/ae63f655454c1548483e3f34d8f47d98ae21119b/datastore/datastore/src/androidInstrumentedTest/kotlin/androidx/datastore/DataStoreDelegateTest.kt

    // Two independent stores could overwrite each other's changes. A second delegate for
    // the same file must fail while the first remains usable.
    @Test
    fun rejectsASecondActiveDelegateForTheSameFile() = runBlocking<Unit> {
        val first = open()
        assertEquals("value", first.updateData { "value" })

        val error = runCatching { open().data.first() }.exceptionOrNull()
        assertTrue("Expected the second delegate to fail, got $error", error is IllegalStateException)
        assertEquals("value", first.data.first())
    }

    // Randomizing encryption keeps identical values from producing identical file contents.
    // Compare two actual writes of the same value, then check that it still reads back.
    @Test
    fun repeatedPlaintextWritesProduceDifferentCiphertext() = runBlocking<Unit> {
        val store = open()
        val file = context.dataStoreFile(fileName, false)
        store.updateData { "first value" }
        val firstCiphertext = file.readBytes()

        // Change the value in between so DataStore performs a second write of the same value.
        store.updateData { "another value" }
        store.updateData { "first value" }

        assertFalse(firstCiphertext.contentEquals(file.readBytes()))
        closeLatest()
        assertEquals("first value", open().data.first())
    }

    // Choosing device-protected storage changes the file location. Verify that the new
    // location still holds encrypted data and that reopening it returns the original value.
    @Test
    fun deviceProtectedStorageStillWritesCiphertext() = runBlocking<Unit> {
        val store = open(deviceProtected = true)
        val secret = "private direct boot data"
        store.updateData { secret }

        val deviceContext = context.createDeviceProtectedStorageContext()
        val persisted = deviceContext.filesDir.resolve("datastore/$fileName").readBytes()
        assertFalse(persisted.toString(Charsets.ISO_8859_1).contains(secret))
        assertFalse(context.dataStoreFile(fileName, false).exists())

        closeLatest()
        assertEquals(secret, open(deviceProtected = true).data.first())
    }

    // Upgrading the library must preserve access to data saved by earlier releases.
    // Build the old file and key independently of the current writer to check compatibility.
    @Test
    fun readsCiphertextWrittenInTheLegacyFormat() = runBlocking<Unit> {
        // Recreate the original key and file format without using the current writer. A future
        // writer may use a key with different permissions and still need to open old files.
        val legacyValue = "value saved by an earlier release"
        val legacyKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(

                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                        .build(),
                )
            }
            .generateKey()
        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.ENCRYPT_MODE, legacyKey)
        val legacyFile = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv +
            cipher.doFinal(legacyValue.toByteArray())
        val file = context.dataStoreFile(fileName, false)
        file.parentFile?.mkdirs()
        file.writeBytes(legacyFile)

        assertEquals(legacyValue, open().data.first())
    }

    // Encryption also needs to protect against changes to the file. Modify a newly saved
    // file and check that the reader reports a failure without returning or replacing data.
    @Test
    fun tamperingWithANewWriteDoesNotReturnModifiedPlaintext() = runBlocking<Unit> {
        open().updateData { "sensitive data whose first byte must be authenticated" }
        closeLatest()
        val file = context.dataStoreFile(fileName, false)
        val tampered = file.readBytes()
        // In the CBC format this flips the first byte of the IV, changing plaintext without
        // touching the padding. New writes must detect tampering before returning a value.
        tampered[1] = (tampered[1].toInt() xor 1).toByte()
        file.writeBytes(tampered)

        val error = runCatching { open().data.first() }.exceptionOrNull()
        assertTrue(
            "Expected a decryption or corruption failure, got $error",
            error is GeneralSecurityException || error is IOException,
        )
        assertArrayEquals(tampered, file.readBytes())
    }

    private fun open(deviceProtected: Boolean = false): DataStore<String> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return with(StoreHolder(fileName, alias, deviceProtected, scope)) { context.store }
    }

    private suspend fun closeLatest() = scopes.last().coroutineContext.job.cancelAndJoin()

    private class StoreHolder(
        fileName: String,
        alias: String,
        deviceProtected: Boolean,
        scope: CoroutineScope,
    ) {
        val Context.store by encryptedDataStore(
            fileName,
            StringSerializer,
            scope = scope,
            alias = alias,
            isDBA = deviceProtected,
        )
    }

    private object StringSerializer : Serializer<String> {
        override val defaultValue = ""

        override suspend fun readFrom(input: InputStream) = input.readBytes().decodeToString()

        override suspend fun writeTo(t: String, output: OutputStream) = output.write(t.encodeToByteArray())
    }
}
