package com.sonozaki.bedatastore.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sonozaki.bedatastore.testutil.TestIntSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.properties.ReadOnlyProperty

@RunWith(AndroidJUnit4::class)
class DataStoreDelegateTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val deviceContext = context.createDeviceProtectedStorageContext()
    private val fileName = "delegate-test-${UUID.randomUUID()}"
    private val deviceFile = deviceContext.filesDir.resolve("datastore/$fileName")
    private val credentialFile = context.filesDir.resolve("datastore/$fileName")
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After
    fun cleanUp() {
        deviceFile.delete()
        credentialFile.delete()
        keyStore.deleteEntry(fileName)
    }

    // Both delegates should work with only a filename and serializer. The same filename
    // can hold separate values because the delegates choose different storage locations.
    @Test
    fun defaultDelegatesStoreIndependentValuesInTheirExpectedLocations() = runBlocking<Unit> {
        val plain = StoreHolder(dataStoreDBA(fileName, TestIntSerializer)).get(context)
        val encrypted = StoreHolder(encryptedDataStore(fileName, TestIntSerializer)).get(context)

        assertEquals(0, plain.data.first())
        assertEquals(0, encrypted.data.first())
        assertEquals(41, plain.updateData { 41 })
        assertEquals(42, encrypted.updateData { 42 })

        assertArrayEquals(intBytes(41), deviceFile.readBytes())
        assertFalse(intBytes(42).contentEquals(credentialFile.readBytes()))
        assertTrue(keyStore.entryInstanceOf(fileName, KeyStore.SecretKeyEntry::class.java))
        assertEquals(41, plain.data.first())
        assertEquals(42, encrypted.data.first())
    }

    // A migration that reads old data before unlock needs the device-protected context.
    // A fresh delegate should then open the value persisted by that migration.
    @Test
    fun plainDelegateMigratesDataUsingTheDeviceProtectedContext() = runBlocking<Unit> {
        withStoreScope { scope ->
            plainStore(scope).updateData { 41 }
        }
        var cleanupCalls = 0
        withStoreScope { scope ->
            val delegate = dataStoreDBA(
                fileName,
                TestIntSerializer,
                scope = scope,
                produceMigrations = { migrationContext ->
                    assertTrue(migrationContext.isDeviceProtectedStorage)
                    listOf(incrementMigration {
                        assertArrayEquals(intBytes(42), deviceFile.readBytes())
                        cleanupCalls++
                    })
                },
            )
            assertEquals(42, StoreHolder(delegate).get(context).data.first())
        }

        assertEquals(1, cleanupCalls)
        assertFalse(credentialFile.exists())
        assertFalse(keyStore.containsAlias(fileName))
        withStoreScope { scope -> assertEquals(42, plainStore(scope).data.first()) }
    }

    // The public encrypted delegate must give migrations the chosen storage context and
    // readable values, then encrypt the result before allowing the migration to clean up.
    @Test
    fun encryptedDelegateMigratesAndPersistsCiphertextInDeviceProtectedStorage() = runBlocking<Unit> {
        withStoreScope { scope ->
            val delegate = encryptedDataStore(fileName, TestIntSerializer, scope = scope, isDBA = true)
            StoreHolder(delegate).get(context).updateData { 41 }
        }
        val original = deviceFile.readBytes()
        var cleanupCalls = 0
        withStoreScope { scope ->
            val delegate = encryptedDataStore(
                fileName,
                TestIntSerializer,
                scope = scope,
                isDBA = true,
                produceMigrations = { migrationContext ->
                    assertTrue(migrationContext.isDeviceProtectedStorage)
                    listOf(incrementMigration {
                        val migrated = deviceFile.readBytes()
                        assertFalse(original.contentEquals(migrated))
                        assertFalse(intBytes(42).contentEquals(migrated))
                        cleanupCalls++
                    })
                },
            )
            assertEquals(42, StoreHolder(delegate).get(context).data.first())
        }
        assertEquals(1, cleanupCalls)
        assertFalse(credentialFile.exists())
        withStoreScope { scope ->
            val delegate = encryptedDataStore(fileName, TestIntSerializer, scope = scope, isDBA = true)
            assertEquals(42, StoreHolder(delegate).get(context).data.first())
        }
    }

    // A malformed file should remain available for recovery when the app has no handler.
    // A supplied handler can replace it, and the next open should read that replacement.
    @Test
    fun plainDelegateRecoversCorruptionOnlyWhenGivenAHandler() = runBlocking<Unit> {
        val malformed = byteArrayOf(1)
        deviceFile.parentFile?.mkdirs()
        deviceFile.writeBytes(malformed)

        withStoreScope { scope ->
            val error = runCatching { plainStore(scope).data.first() }.exceptionOrNull()
            assertTrue("Expected corruption, got $error", error is CorruptionException)
            assertArrayEquals(malformed, deviceFile.readBytes())
        }

        var handlerCalls = 0
        withStoreScope { scope ->
            val handler = ReplaceFileCorruptionHandler {
                handlerCalls++
                42
            }
            val delegate = dataStoreDBA(fileName, TestIntSerializer, handler, scope = scope)
            assertEquals(42, StoreHolder(delegate).get(context).data.first())
        }
        assertEquals(1, handlerCalls)
        assertArrayEquals(intBytes(42), deviceFile.readBytes())
        withStoreScope { scope -> assertEquals(42, plainStore(scope).data.first()) }
    }

    // Multiple callers can request the same delegate while its first instance is being
    // created. Only one migration setup and one device-protected store should result.
    @Test
    fun concurrentAccessToThePlainDelegateCreatesOnlyOneStore() = runBlocking<Unit> {
        assertOneStoreUnderContention { scope, migrations ->
            dataStoreDBA(fileName, TestIntSerializer, produceMigrations = migrations, scope = scope)
        }
    }

    // The encrypted delegate must also share its store across concurrent callers so they
    // use the same update lock and plaintext cache.
    @Test
    fun concurrentAccessToTheEncryptedDelegateCreatesOnlyOneStore() = runBlocking<Unit> {
        assertOneStoreUnderContention { scope, migrations ->
            encryptedDataStore(fileName, TestIntSerializer, produceMigrations = migrations, scope = scope)
        }
    }

    private fun plainStore(scope: CoroutineScope): DataStore<Int> =
        StoreHolder(dataStoreDBA(fileName, TestIntSerializer, scope = scope)).get(context)

    private suspend fun withStoreScope(block: suspend (CoroutineScope) -> Unit) {
        val job = SupervisorJob()
        try {
            block(CoroutineScope(Dispatchers.IO + job))
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun assertOneStoreUnderContention(
        delegate: (CoroutineScope, (Context) -> List<DataMigration<Int>>) -> ReadOnlyProperty<Context, DataStore<Int>>,
    ) = withStoreScope { scope ->
        val setupStarted = CountDownLatch(1)
        val finishSetup = CountDownLatch(1)
        val setups = AtomicInteger()
        val holder = StoreHolder(delegate(scope) {
            setups.incrementAndGet()
            setupStarted.countDown()
            check(finishSetup.await(10, SECONDS)) { "Delegate setup was never released" }
            emptyList()
        })
        val first = FutureTask { holder.get(context) }
        val second = FutureTask { holder.get(deviceContext) }
        thread(isDaemon = true) { first.run() }
        try {
            assertTrue("The first caller never entered setup", setupStarted.await(5, SECONDS))
            val waitingCaller = thread(isDaemon = true) { second.run() }
            // Hold the first caller inside setup until the second reaches the delegate lock.
            withTimeout(5_000) {
                while (waitingCaller.state != Thread.State.BLOCKED && !second.isDone) delay(1)
            }
        } finally {
            finishSetup.countDown()
        }

        val instance = first.get(5, SECONDS)
        assertSame(instance, second.get(5, SECONDS))
        assertSame(instance, holder.get(context))
        assertEquals(1, setups.get())
    }

    private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array()

    private fun incrementMigration(cleanup: () -> Unit) = object : DataMigration<Int> {
        override suspend fun shouldMigrate(currentData: Int) = currentData == 41

        override suspend fun migrate(currentData: Int) = currentData + 1

        override suspend fun cleanUp() = cleanup()
    }

    private class StoreHolder(delegate: ReadOnlyProperty<Context, DataStore<Int>>) {
        private val Context.store by delegate

        fun get(context: Context): DataStore<Int> = context.store
    }
}
