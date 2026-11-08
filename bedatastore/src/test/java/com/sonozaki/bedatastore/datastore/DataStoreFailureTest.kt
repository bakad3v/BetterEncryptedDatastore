/*
 * Copyright 2020 The Android Open Source Project
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

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.sonozaki.bedatastore.testutil.EncryptedStoreFixture
import com.sonozaki.bedatastore.testutil.expectFailure
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException

// Adapts the transient read/write and corruption handler cases from AndroidX.
// https://github.com/androidx/androidx/blob/ae63f655454c1548483e3f34d8f47d98ae21119b/datastore/datastore-core/src/commonTest/kotlin/androidx/datastore/core/SingleProcessDataStoreTest.kt
class DataStoreFailureTest {
    @get:Rule
    val files = TemporaryFolder()

    // A temporary read error can make valid data inaccessible. Reads and updates should
    // surface the error so a retry can use the original value once storage is available again.
    @Test
    fun transientReadFailureReachesCallersWithoutInvokingCorruptionHandler() = runTest {
        val fixture = newFixture()
        val first = fixture.open(this)
        first.dataStore.updateData { 41 }
        first.close()
        val original = fixture.file.readBytes()
        val failure = IOException("Storage temporarily unavailable")
        fixture.disk.readFailure = failure
        val store = fixture.open(this, corruptionHandler = unexpectedRecovery()).dataStore

        assertEquals(failure.message, expectFailure<IOException> { store.data.first() }.message)
        assertEquals(failure.message, expectFailure<IOException> { store.updateData { it.inc() } }.message)
        assertArrayEquals(original, fixture.file.readBytes())

        fixture.disk.readFailure = null
        assertEquals(42, store.updateData { it.inc() })
        assertEquals(42, store.data.first())
    }

    // If a read stops after returning a prefix, the caller should see the I/O error. The
    // original bytes must remain available for a full read once storage is accessible again.
    @Test
    fun partialReadFailurePreservesCiphertextAndAllowsRetry() = runTest {
        val fixture = newFixture()
        val first = fixture.open(this)
        first.dataStore.updateData { 41 }
        first.close()
        val original = fixture.file.readBytes()
        val failure = IOException("Storage disconnected mid-read")
        fixture.disk.bytesBeforeReadFailure = 8
        fixture.disk.readFailure = failure
        val store = fixture.open(this, corruptionHandler = unexpectedRecovery()).dataStore

        val thrown = expectFailure<IOException> { store.data.first() }
        assertEquals(failure.message, thrown.message)
        assertArrayEquals(original, fixture.file.readBytes())

        fixture.disk.readFailure = null
        assertEquals(41, store.data.first())
    }

    // The disk can fill after accepting part of the new ciphertext. The partial scratch
    // file must never replace the old value, and a later write should still be possible.
    @Test
    fun diskFullWhileWritingScratchFilePreservesTheCommittedValue() = runTest {
        val fixture = newFixture()
        val opened = fixture.open(this)
        opened.dataStore.updateData { 41 }
        val original = fixture.file.readBytes()
        val failure = IOException("No space left on device")
        fixture.disk.writeFailure = failure

        val thrown = expectFailure<IOException> { opened.dataStore.updateData { 42 } }
        assertEquals(failure.message, thrown.message)
        assertArrayEquals(original, fixture.file.readBytes())
        assertFalse(fixture.file.resolveSibling("${fixture.file.name}.tmp").exists())

        opened.close()
        fixture.disk.writeFailure = null
        val reopened = fixture.open(this).dataStore
        assertEquals(41, reopened.data.first())
        assertEquals(42, reopened.updateData { it.inc() })
    }

    // Fail the final step of replacing the file. Readers should only see the new value once
    // it is safely on disk, so a failed replacement must leave the old value visible.
    @Test
    fun failedCommitLeavesPreviousValueOnDiskAndInTheObservedCache() = runTest {
        val fixture = newFixture()
        val opened = fixture.open(this)
        val store = opened.dataStore
        store.updateData { 41 }
        val original = fixture.file.readBytes()
        val observed = Channel<Int>(Channel.UNLIMITED)
        val collector = backgroundScope.launch { store.data.collect { observed.send(it) } }
        assertEquals(41, observed.receive())

        fixture.disk.commitFailure = IOException("Could not replace the destination file")
        expectFailure<IOException> { store.updateData { 42 } }
        assertArrayEquals(original, fixture.file.readBytes())
        assertEquals(41, store.data.first())
        collector.cancel()
        collector.join()
        assertFalse(observed.tryReceive().isSuccess)

        fixture.disk.commitFailure = null
        opened.close()
        val reopened = fixture.open(this).dataStore
        assertEquals(41, reopened.data.first())
        assertEquals(42, reopened.updateData { it.inc() })
    }

    // A serializer can fail after writing some bytes. Those bytes must stay out of the store
    // so the last saved value is available for a retry.
    @Test
    fun serializerFailureAfterProducingBytesDoesNotCommitPartialData() = runTest {
        val fixture = newFixture()
        val store = fixture.open(this).dataStore
        store.updateData { 41 }
        val original = fixture.file.readBytes()
        val failure = IOException("Serializer failed after writing")
        fixture.serializer.writeFailure = failure

        assertEquals(failure.message, expectFailure<IOException> { store.updateData { 42 } }.message)
        assertArrayEquals(original, fixture.file.readBytes())
        assertEquals(41, store.data.first())

        fixture.serializer.writeFailure = null
        assertEquals(42, store.updateData { it.inc() })
    }

    // An app can reject a change by throwing from its transform. The store should pass that
    // error back to the caller and let the next update start from the last saved value.
    @Test
    fun transformFailureDoesNotCommitAndTheNextUpdateSucceeds() = runTest {
        val fixture = newFixture()
        val store = fixture.open(this).dataStore
        store.updateData { 41 }
        val original = fixture.file.readBytes()
        val failure = IllegalArgumentException("Invalid update")

        val thrown = expectFailure<IllegalArgumentException> { store.updateData { throw failure } }
        assertEquals(failure.message, thrown.message)
        assertArrayEquals(original, fixture.file.readBytes())
        assertEquals(41, store.data.first())
        assertEquals(42, store.updateData { it.inc() })
    }

    // Simulate the Keystore becoming unavailable during a read and during a write. In both
    // cases the saved ciphertext must survive so work can resume when the key is available.
    @Test
    fun keystoreFailuresPreserveThePreviousCiphertextAndAllowRetry() = runTest {
        val fixture = newFixture()
        val first = fixture.open(this)
        first.dataStore.updateData { 41 }
        first.close()
        val original = fixture.file.readBytes()
        val failure = KeyStoreException("Keystore temporarily unavailable")
        val store = fixture.open(this, corruptionHandler = unexpectedRecovery()).dataStore

        fixture.encryption.decryptFailure = failure
        assertEquals(failure.message, expectFailure<KeyStoreException> { store.data.first() }.message)
        assertArrayEquals(original, fixture.file.readBytes())
        fixture.encryption.decryptFailure = null
        assertEquals(41, store.data.first())

        fixture.encryption.encryptFailure = failure
        assertEquals(failure.message, expectFailure<KeyStoreException> { store.updateData { 42 } }.message)
        assertArrayEquals(original, fixture.file.readBytes())
        fixture.encryption.encryptFailure = null
        assertEquals(42, store.updateData { it.inc() })
    }

    // Successful decryption can still yield invalid application data. The recovery handler
    // should run once and save an encrypted replacement that a new store can read.
    @Test
    fun corruptionHandlerReplacesInvalidPayloadWithEncryptedData() = runTest {
        val fixture = newFixture()
        // Produce valid ciphertext whose decrypted bytes are too short for the application
        // serializer. This isolates payload corruption from a key or storage failure.
        fixture.file.writeBytes(fixture.encryption.encrypt("test-key", byteArrayOf(1)))
        var handlerCalls = 0
        val handler = ReplaceFileCorruptionHandler {
            handlerCalls++
            42
        }
        val opened = fixture.open(this, corruptionHandler = handler)

        assertEquals(42, opened.dataStore.data.first())
        assertEquals(1, handlerCalls)
        val replacement = fixture.file.readBytes()
        assertArrayEquals(replacement, opened.ciphertextStore.data.first())
        assertEquals(42, fixture.encryptedSerializer.decryptAndDeserialize(replacement))
        opened.close()
        assertEquals(42, fixture.open(this).dataStore.data.first())
    }

    // Model the bytes left by a torn write to the data file itself. Reading and updating
    // should report the decryption error and leave those bytes available for recovery.
    @Test
    fun truncatedCiphertextIsNeverSilentlyReplaced() = runTest {
        val fixture = newFixture()
        val first = fixture.open(this)
        first.dataStore.updateData { 41 }
        first.close()
        val original = fixture.file.readBytes()
        val truncated = original.copyOf(original.size - 1)
        fixture.file.writeBytes(truncated)
        val store = fixture.open(this).dataStore

        val readFailure = expectFailure<Exception> { store.data.first() }
        val updateFailure = expectFailure<Exception> { store.updateData { 42 } }
        assertTrue(
            "Unexpected read failure: $readFailure",
            readFailure is CorruptionException || readFailure is GeneralSecurityException,
        )
        assertTrue(
            "Unexpected update failure: $updateFailure",
            updateFailure is CorruptionException || updateFailure is GeneralSecurityException,
        )
        assertArrayEquals(truncated, fixture.file.readBytes())
    }

    // Recovery can compute a valid replacement and still fail when saving it. Keep the
    // original file until the recovery write succeeds, then check that a new store can read it.
    @Test
    fun failedRecoveryWritePreservesTheFileUntilRecoveryCanBeRetried() = runTest {
        val fixture = newFixture()
        val invalid = fixture.encryption.encrypt("test-key", byteArrayOf(1))
        fixture.file.writeBytes(invalid)
        val failure = IOException("Could not commit recovered data")
        fixture.disk.commitFailure = failure
        val opened = fixture.open(this, corruptionHandler = ReplaceFileCorruptionHandler { 42 })

        val thrown = expectFailure<IOException> { opened.dataStore.data.first() }
        assertEquals(failure.message, thrown.message)
        assertArrayEquals(invalid, fixture.file.readBytes())

        fixture.disk.commitFailure = null
        assertEquals(42, opened.dataStore.data.first())
        opened.close()
        assertEquals(42, fixture.open(this).dataStore.data.first())
    }

    private fun unexpectedRecovery() = ReplaceFileCorruptionHandler<Int> {
        throw AssertionError("A temporary failure must never invoke corruption recovery", it)
    }

    private fun newFixture() = EncryptedStoreFixture(files.root.resolve("failure.store"))
}
