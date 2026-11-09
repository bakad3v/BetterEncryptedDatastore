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

package com.sonozaki.bedatastore.migration

import androidx.datastore.core.DataMigration
import com.sonozaki.bedatastore.testutil.EncryptedStoreFixture
import com.sonozaki.bedatastore.testutil.expectFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

// Adapted from DataMigrationInitializerTest, using DataStoreFactory's public migration API.
// https://github.com/androidx/androidx/blob/ae63f655454c1548483e3f34d8f47d98ae21119b/datastore/datastore-core/src/commonTest/kotlin/androidx/datastore/core/DataMigrationInitializerTest.kt
class DataStoreMigrationTest {
    @get:Rule
    val files = TemporaryFolder()

    // Each migration needs the value produced by the previous one. Cleanup may discard old
    // data, so it should happen only for migrations that ran and whose result is saved.
    @Test
    fun migrationsSeePlaintextInOrderAndCleanUpAfterTheEncryptedCommit() = runTest {
        val fixture = newFixture()
        val first = fixture.open(this)
        first.dataStore.updateData { 10 }
        first.close()
        val cleanups = mutableListOf<String>()
        val migrations = listOf(
            TestMigration(
                shouldMigrate = { it == 10 },
                migrate = { it + 2 },
                cleanUp = {
                    assertEquals(15, fixture.encryptedSerializer.decryptAndDeserialize(fixture.file.readBytes()))
                    cleanups.add("first")
                },
            ),
            TestMigration(
                shouldMigrate = { it == 12 },
                migrate = { it + 3 },
                cleanUp = { cleanups.add("second") },
            ),
            TestMigration(
                shouldMigrate = { false },
                migrate = { error("Migration must be skipped") },
                cleanUp = { error("Skipped migration must not be cleaned up") },
            ),
        )
        val opened = fixture.open(this, migrations)

        assertEquals(15, opened.dataStore.data.first())
        assertEquals(listOf("first", "second"), cleanups)
        opened.close()
        assertEquals(15, fixture.open(this, migrations).dataStore.data.first())
        assertEquals(listOf("first", "second"), cleanups)
    }

    // A migration that fails before producing a value should leave the original file and
    // migration source intact so the next read can try again.
    @Test
    fun failedMigrationDoesNotCleanUpOrReplaceDataAndCanBeRetried() = runTest {
        val fixture = newFixture()
        val first = fixture.open(this)
        first.dataStore.updateData { 10 }
        first.close()
        val original = fixture.file.readBytes()
        var shouldFail = true
        var cleanupCount = 0
        val failure = IOException("Migration could not access its source")
        val migration = TestMigration(
            migrate = {
                if (shouldFail) throw failure
                it + 5
            },
            cleanUp = { cleanupCount++ },
        )
        val store = fixture.open(this, listOf(migration)).dataStore

        assertEquals(failure.message, expectFailure<IOException> { store.data.first() }.message)
        assertEquals(0, cleanupCount)
        assertArrayEquals(original, fixture.file.readBytes())

        shouldFail = false
        assertEquals(15, store.data.first())
        assertEquals(1, cleanupCount)
    }

    // Even after a migration computes a valid result, cleanup must wait for the file write.
    // Otherwise a disk failure could remove the source data before it can be migrated again.
    @Test
    fun failedMigrationWriteDoesNotCleanUpBeforeRetry() = runTest {
        val fixture = newFixture()
        var cleanupCount = 0
        val migration = TestMigration(migrate = { it + 5 }, cleanUp = { cleanupCount++ })
        val store = fixture.open(this, listOf(migration)).dataStore
        fixture.disk.commitFailure = IOException("Could not commit migrated data")

        expectFailure<IOException> { store.data.first() }
        assertEquals(0, cleanupCount)
        assertFalse(fixture.file.exists())

        fixture.disk.commitFailure = null
        assertEquals(5, store.data.first())
        assertEquals(1, cleanupCount)
    }

    // Cleanup runs after the new value is saved. Its failure should reach the caller,
    // while a fresh store should read the committed value without applying migration again.
    @Test
    fun failedMigrationCleanupLeavesTheCommittedCiphertextReadable() = runTest {
        val fixture = newFixture()
        val original = fixture.open(this)
        original.dataStore.updateData { 10 }
        original.close()
        val failure = IOException("Could not remove the migration source")
        var migrationCalls = 0
        val migration = TestMigration(
            shouldMigrate = { it == 10 },
            migrate = {
                migrationCalls++
                it + 5
            },
            cleanUp = { throw failure },
        )
        val opened = fixture.open(this, listOf(migration))

        val thrown = expectFailure<IOException> { opened.dataStore.data.first() }
        assertEquals(failure.message, thrown.message)
        assertEquals(15, fixture.encryptedSerializer.decryptAndDeserialize(fixture.file.readBytes()))
        opened.close()

        assertEquals(15, fixture.open(this, listOf(migration)).dataStore.data.first())
        assertEquals(1, migrationCalls)
    }

    private fun newFixture() = EncryptedStoreFixture(files.root.resolve("migration.store"))

    private class TestMigration(
        private val shouldMigrate: (Int) -> Boolean = { true },
        private val migrate: (Int) -> Int,
        private val cleanUp: suspend () -> Unit,
    ) : DataMigration<Int> {
        override suspend fun shouldMigrate(currentData: Int) = shouldMigrate.invoke(currentData)
        override suspend fun migrate(currentData: Int) = migrate.invoke(currentData)
        override suspend fun cleanUp() = cleanUp.invoke()
    }
}
