package com.sonozaki.bedatastore.recovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DataStoreProcessRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = RecoveryFiles(File(context.cacheDir, "datastore-recovery-${UUID.randomUUID()}"))

    @After
    fun cleanUp() {
        files.directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(files.alias)
    }

    // A dead writer leaves its scratch file behind. A fresh store should read the last
    // committed value and be able to finish a later write despite the abandoned file.
    @Test
    fun processDeathBeforeReplacingTheFilePreservesTheOldValue() = runBlocking<Unit> {
        val original = seedStore()
        crashWorkerAt(CrashPoint.BEFORE_REPLACE)

        assertTrue(files.scratch.exists())
        assertArrayEquals(original, files.store.readBytes())
        withStore { store ->
            assertEquals(OLD_VALUE, store.data.first())
            assertEquals(NEW_VALUE, store.updateData { it.inc() })
        }
        assertFalse(files.scratch.exists())
        assertEquals(NEW_VALUE, withStore { it.data.first() })
    }

    // A write can reach disk before its caller receives the result. Kill the writer at that
    // point and check that a fresh store recovers the committed value with the same key.
    @Test
    fun processDeathAfterReplacingTheFilePreservesTheNewValue() = runBlocking<Unit> {
        val original = seedStore()
        crashWorkerAt(CrashPoint.AFTER_REPLACE)

        assertFalse(files.scratch.exists())
        assertFalse(original.contentEquals(files.store.readBytes()))
        assertEquals(NEW_VALUE, withStore { it.data.first() })
    }

    // Interrupt a cold read after it has consumed only part of the ciphertext. The file
    // should stay intact so a fresh store can decrypt the whole value.
    @Test
    fun processDeathDuringReadLeavesTheEncryptedFileIntact() = runBlocking<Unit> {
        val original = seedStore()
        crashWorkerAt(CrashPoint.DURING_READ)

        assertArrayEquals(original, files.store.readBytes())
        assertEquals(OLD_VALUE, withStore { it.data.first() })
    }

    // A read can trigger a migration that writes to disk. Its source cleanup must wait
    // until the new data is committed, so a restarted process can retry the migration.
    @Test
    fun processDeathDuringMigrationLeavesDataAvailableForRetry() = runBlocking<Unit> {
        val original = seedStore()
        crashWorkerAt(CrashPoint.BEFORE_MIGRATION_REPLACE)

        assertArrayEquals(original, files.store.readBytes())
        assertFalse(files.migrationCleanup.exists())
        assertEquals(NEW_VALUE, withStore(listOf(files.migration)) { it.data.first() })
        assertTrue(files.migrationCleanup.exists())
        assertFalse(files.scratch.exists())
        assertEquals(NEW_VALUE, withStore { it.data.first() })
    }

    private suspend fun seedStore(): ByteArray {
        check(files.directory.mkdir())
        withStore { it.updateData { OLD_VALUE } }
        return files.store.readBytes()
    }

    private suspend fun <T> withStore(
        migrations: List<DataMigration<Int>> = emptyList(),
        block: suspend (DataStore<Int>) -> T,
    ): T {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        try {
            return withTimeout(10_000) { block(files.open(scope, migrations = migrations)) }
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun crashWorkerAt(point: CrashPoint) = withTimeout(15_000) {
        val intent = Intent(context, DataStoreCrashService::class.java)
            .putExtra(EXTRA_DIRECTORY, files.directory.absolutePath)
            .putExtra(EXTRA_CRASH_POINT, point.name)
        val connected = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            val binder = connected.await()
            assertFalse("The crash worker must run outside the test process", binder is Binder)
            val stopped = CompletableDeferred<Unit>()
            val deathRecipient = IBinder.DeathRecipient { stopped.complete(Unit) }
            binder.linkToDeath(deathRecipient, 0)
            try {
                Messenger(binder).send(Message.obtain(null, 0))
                stopped.await()
                assertTrue("The worker died before reaching $point", files.checkpoint.exists())
                assertEquals(point.name, files.checkpoint.readText())
            } finally {
                binder.unlinkToDeath(deathRecipient, 0)
            }
        } finally {
            context.unbindService(connection)
        }
    }
}
