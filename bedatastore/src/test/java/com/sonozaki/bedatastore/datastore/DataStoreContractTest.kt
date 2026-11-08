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

import com.sonozaki.bedatastore.testutil.EncryptedStoreFixture
import com.sonozaki.bedatastore.testutil.expectFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Adapted from AndroidX SingleProcessDataStoreTest for the encrypted wrapper and Okio storage.
// https://github.com/androidx/androidx/blob/ae63f655454c1548483e3f34d8f47d98ae21119b/datastore/datastore-core/src/commonTest/kotlin/androidx/datastore/core/SingleProcessDataStoreTest.kt
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreContractTest {
    @get:Rule
    val files = TemporaryFolder()

    // Reading a store for the first time should return the serializer's default without
    // creating a file. The file should only appear once there is something to save.
    @Test
    fun testDefaultValueUsedWhenNoDataOnDisk() = runTest {
        val fixture = newFixture()
        val store = fixture.open(this).dataStore

        assertEquals(0, store.data.first())
        assertFalse(fixture.file.exists())
    }

    // Close the first store and open the same file with a fresh instance. Its first read
    // checks that the value can be recovered from disk.
    @Test
    fun testReadWithNewInstance() = runTest {
        val fixture = newFixture()
        val first = fixture.open(this)

        assertEquals(1, first.dataStore.updateData { 1 })
        first.close()

        assertEquals(1, fixture.open(this).dataStore.data.first())
    }

    // A slow update can wait on application work. Readers should still get the last saved
    // value while it waits and see the new value after the write finishes.
    @Test
    fun testWritesDontBlockReadsInSameProcess() = runTest {
        val store = newFixture().open(this).dataStore
        val transformStarted = CompletableDeferred<Unit>()
        val continueTransform = CompletableDeferred<Unit>()

        val slowUpdate = async {
            store.updateData {
                transformStarted.complete(Unit)
                continueTransform.await()
                it.inc()
            }
        }

        try {
            transformStarted.await()
            assertEquals(0, withTimeout(1_000) { store.data.first() })
        } finally {
            continueTransform.complete(Unit)
        }
        assertEquals(1, slowUpdate.await())
        assertEquals(1, store.data.first())
    }

    // Two parts of the app can observe the same store independently. Both should receive
    // the initial value and each saved update they are ready to receive.
    @Test
    fun testMultipleFlowsReceiveData() = runTest {
        val store = newFixture().open(this).dataStore
        val first = Channel<Int>(Channel.UNLIMITED)
        val second = Channel<Int>(Channel.UNLIMITED)
        backgroundScope.launch { store.data.collect { first.send(it) } }
        backgroundScope.launch { store.data.collect { second.send(it) } }

        for (expected in 0..3) {
            if (expected > 0) store.updateData { it.inc() }
            // Wait for both collectors before the next update. DataStore may conflate emissions
            // when a collector is slower than the writer.
            assertEquals(expected, first.receive())
            assertEquals(expected, second.receive())
        }
    }

    // Each increment must start from the result of the preceding update. Checking the
    // total again after reopening catches updates lost either in memory or on disk.
    @Test
    fun testConcurrentReadModifyWriteUpdatesAreAtomic() = runTest {
        val fixture = newFixture()
        val first = fixture.open(this)
        coroutineScope {
            repeat(20) { launch { first.dataStore.updateData { it.inc() } } }
        }
        assertEquals(20, first.dataStore.data.first())
        first.close()
        assertEquals(20, fixture.open(this).dataStore.data.first())
    }

    // Encrypting the same value again would produce different bytes. Compare the file before
    // and after a no-op update to check that the wrapper keeps DataStore's skip-write behavior.
    @Test
    fun testWriteSameValueSkipDisk() = runTest {
        val fixture = newFixture()
        val store = fixture.open(this).dataStore
        store.updateData { 10 }
        val ciphertext = fixture.file.readBytes()

        assertEquals(10, store.updateData { it })
        assertArrayEquals(ciphertext, fixture.file.readBytes())
    }

    // Cancelling the caller while its transform is paused should stop that write before it
    // saves anything. The store should remain available for a later update.
    @Test
    fun testCancellingCallerScopePropagatesToWrites() = runTest {
        val store = newFixture().open(this).dataStore
        val transformStarted = CompletableDeferred<Unit>()
        val continueTransform = CompletableDeferred<Unit>()
        val update = launch {
            store.updateData {
                transformStarted.complete(Unit)
                continueTransform.await()
                it.inc()
            }
        }

        transformStarted.await()
        update.cancel()
        runCurrent()
        val stoppedWithCaller = update.isCompleted

        // Release the transform even on a regression, so a detached write cannot hang the test.
        continueTransform.complete(Unit)
        update.join()
        assertEquals("A cancelled transform committed a value", 0, store.data.first())
        assertTrue("The write outlived the coroutine that called updateData", stoppedWithCaller)
        assertEquals(1, store.updateData { it.inc() })
    }

    @Test
    fun cancellingCallerDuringCommitKeepsDiskAndCacheInSync() = runTest {
        val fixture = newFixture()
        val opened = fixture.open(this)
        val store = opened.dataStore
        assertEquals(1, store.updateData { 1 })

        val observed = Channel<Int>(Channel.UNLIMITED)
        val collector = backgroundScope.launch { store.data.collect { observed.send(it) } }
        assertEquals(1, observed.receive())

        val writeStarted = CompletableDeferred<Unit>()
        val continueWrite = CompletableDeferred<Unit>()
        fixture.serializer.beforeWrite = {
            writeStarted.complete(Unit)
            continueWrite.await()
        }
        val update = launch { store.updateData { 2 } }
        withTimeout(1_000) { writeStarted.await() }
        update.cancel()
        runCurrent()
        continueWrite.complete(Unit)
        update.join()

        assertEquals(2, withTimeout(1_000) { observed.receive() })
        collector.cancelAndJoin()
        opened.close()
        assertEquals(2, fixture.open(this).dataStore.data.first())
    }

    // Close the store after a collector receives its first value. This checks that shutdown
    // also releases subscribers that are already waiting for further updates.
    @Test
    fun testScopeCancelledWithActiveFlow() = runTest {
        val opened = newFixture().open(this)
        val collecting = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch {
            opened.dataStore.data.collect { collecting.complete(Unit) }
        }
        collecting.await()

        opened.close()

        val finished = withTimeoutOrNull(1_000) {
            collector.join()
            true
        }
        assertEquals("A collector remained active after the store closed", true, finished)
    }

    // A transform that updates the same store would wait for itself. The inner call should
    // fail promptly so the outer write and later transactions can still finish.
    @Test
    fun nestedUpdateCallsShouldntDeadlock() = runTest {
        val store = newFixture().open(this).dataStore

        assertEquals(1, store.updateData {
            val failure = expectFailure<IllegalStateException> {
                withTimeout(1_000) { store.updateData { 2 } }
            }
            assertFalse("Nested updateData blocked until the timeout", failure is TimeoutCancellationException)
            1
        })
        assertEquals(1, store.data.first())
        assertEquals(3, store.updateData { 3 })
    }

    @Test
    fun nestedUpdatesAllowAnotherStoreButRejectReturningToTheFirst() = runTest {
        val first = newFixture().open(this).dataStore
        val second = EncryptedStoreFixture(files.root.resolve("second.store")).open(this).dataStore

        assertEquals(1, first.updateData {
            assertEquals(2, second.updateData {
                val failure = expectFailure<IllegalStateException> {
                    withTimeout(1_000) { first.updateData { 3 } }
                }
                assertFalse("Nested updateData blocked until the timeout", failure is TimeoutCancellationException)
                2
            })
            1
        })
        assertEquals(1, first.data.first())
        assertEquals(2, second.data.first())
    }

    private fun newFixture() = EncryptedStoreFixture(files.root.resolve("contract.store"))
}
