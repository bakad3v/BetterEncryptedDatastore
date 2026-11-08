package com.sonozaki.bedatastore.datastore

import com.sonozaki.bedatastore.testutil.EncryptedStoreFixture
import kotlinx.coroutines.cancelAndJoin
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
import java.nio.ByteBuffer

class PlaintextCacheTest {
    @get:Rule
    val files = TemporaryFolder()

    // Encrypting the file alone would still leave the underlying DataStore's long-lived cache
    // exposed. Compare the bytes from that cache with the file and check that they decrypt.
    @Test
    fun backingDataStoreCachesTheSameCiphertextThatItPersists() = runTest {
        val fixture = newFixture()
        val store = fixture.open(this)
        val secret = 0x12345678

        store.dataStore.updateData { secret }

        val persisted = fixture.file.readBytes()
        val cached = store.ciphertextStore.data.first()
        assertArrayEquals(persisted, cached)
        assertFalse(ByteBuffer.allocate(4).putInt(secret).array().contentEquals(cached))
        assertEquals(secret, fixture.encryptedSerializer.decryptAndDeserialize(cached))
    }

    // Count decryptions to distinguish a cache hit from a fresh read. The first cancellation
    // should leave the shared value available, while the last one should release it.
    @Test
    fun plaintextCacheIsSharedAndReleasedAfterTheLastCollectorIsCancelled() = runTest {
        val fixture = newFixture()
        val opened = fixture.open(this)
        opened.dataStore.updateData { 10 }
        opened.close()
        val store = fixture.open(this).dataStore
        val observed = Channel<Int>(Channel.UNLIMITED)

        val beforeFirstCollector = fixture.encryption.decryptions
        val first = backgroundScope.launch { store.data.collect { observed.send(it) } }
        assertEquals(10, observed.receive())
        val whileObserved = fixture.encryption.decryptions
        assertTrue(whileObserved > beforeFirstCollector)

        val second = backgroundScope.launch { store.data.collect { observed.send(it) } }
        assertEquals(10, observed.receive())
        first.cancelAndJoin()
        assertEquals(10, store.data.first())
        assertEquals(whileObserved, fixture.encryption.decryptions)

        second.cancelAndJoin()
        assertEquals(10, store.data.first())
        assertTrue(fixture.encryption.decryptions > whileObserved)
    }

    // A call to first() ends its subscription as soon as it gets a value. A second call
    // should decrypt again because the first call has no further need for the plaintext.
    @Test
    fun oneShotReadsReleaseThePlaintextCache() = runTest {
        val fixture = newFixture()
        val store = fixture.open(this).dataStore
        assertEquals(0, store.data.first())
        val afterFirstRead = fixture.encryption.decryptions

        assertEquals(0, store.data.first())

        assertTrue(fixture.encryption.decryptions > afterFirstRead)
    }

    // A write needs plaintext while it runs. With no subscribers waiting for the result,
    // the next reader should have to decrypt the saved value again.
    @Test
    fun updateWithoutCollectorsDoesNotLeavePlaintextInTheCache() = runTest {
        val fixture = newFixture()
        val store = fixture.open(this).dataStore
        assertEquals(10, store.updateData { 10 })
        val afterUpdate = fixture.encryption.decryptions

        // A fresh read must decrypt again if the write released its plaintext value.
        assertEquals(10, store.data.first())
        assertTrue("An unobserved update retained plaintext", fixture.encryption.decryptions > afterUpdate)
    }

    private fun newFixture() = EncryptedStoreFixture(files.root.resolve("cache.store"))
}
