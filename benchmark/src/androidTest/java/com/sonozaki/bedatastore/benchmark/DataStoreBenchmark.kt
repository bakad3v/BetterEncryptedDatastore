package com.sonozaki.bedatastore.benchmark

import android.content.Context
import androidx.benchmark.junit4.BenchmarkRule
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.test.platform.app.InstrumentationRegistry
import com.sonozaki.bedatastore.datastore.dataStoreFile
import com.sonozaki.bedatastore.datastore.encryptedDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.UUID
import kotlin.random.Random

class DataStoreBenchmark(sizeBytes: Int) : DataStoreBenchmarks(sizeBytes, encrypted = true)

class AndroidXDataStoreBenchmark(sizeBytes: Int) : DataStoreBenchmarks(sizeBytes, encrypted = false)

@RunWith(Parameterized::class)
abstract class DataStoreBenchmarks(sizeBytes: Int, private val encrypted: Boolean) {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fileName = "datastore-benchmark-${UUID.randomUUID()}"
    private val file = context.dataStoreFile(fileName, isDBA = false)
    private val initial = Random(0).nextBytes(sizeBytes)

    @Before
    fun seedFile() = runBlocking<Unit> {
        withStore { store -> store.updateData { initial } }
    }

    @After
    fun deleteFileAndKey() {
        file.delete()
        file.resolveSibling("$fileName.tmp").delete()
        if (encrypted) {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("$fileName.bedatastore-gcm-v1")
        }
    }

    @Test
    fun coldRead() = runBlocking<Unit> {
        // The file is already populated. Each sample starts with empty DataStore caches.
        // Use the state loop so one coroutine spans all samples, without timing runBlocking.
        val state = benchmarkRule.getState()
        var opened: OpenStore? = null
        var result: ByteArray? = null
        try {
            while (state.keepRunning()) {
                state.pauseTiming()
                opened?.close()
                val fresh = openStore()
                opened = fresh
                state.resumeTiming()

                result = fresh.dataStore.data.first()
            }
            assertArrayEquals(initial, result)
        } finally {
            opened?.close()
        }
    }

    @Test
    fun warmReadWithoutCollectors() = runBlocking<Unit> {
        withStore { store ->
            // first() leaves AndroidX's cache primed and releases the decrypted cache, if any.
            assertArrayEquals(initial, store.data.first())
            measureReads(store)
        }
    }

    @Test
    fun hotReadWithCollector() = runBlocking<Unit> {
        withStore { store ->
            val ready = CompletableDeferred<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                store.data.collect { ready.complete(Unit) }
            }
            try {
                ready.await()
                measureReads(store)
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun updateAndCommit() = runBlocking<Unit> {
        val alternate = initial.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        var lastWritten = initial
        withStore { store ->
            assertArrayEquals(initial, store.data.first())
            val state = benchmarkRule.getState()
            while (state.keepRunning()) {
                // Reuse two values of the same size. Each iteration takes the actual write path.
                val next = if (lastWritten === initial) alternate else initial
                store.updateData { next }
                lastWritten = next
            }
        }
        withStore { store -> assertArrayEquals(lastWritten, store.data.first()) }
    }

    private suspend fun measureReads(store: DataStore<ByteArray>) {
        val state = benchmarkRule.getState()
        var result: ByteArray? = null
        while (state.keepRunning()) {
            result = store.data.first()
        }
        assertArrayEquals(initial, result)
    }

    private suspend inline fun withStore(block: (DataStore<ByteArray>) -> Unit) {
        val opened = openStore()
        try {
            block(opened.dataStore)
        } finally {
            opened.close()
        }
    }

    private fun openStore(): OpenStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = if (encrypted) {
            with(StoreHolder(fileName, scope)) { context.store }
        } else {
            // Match the encrypted delegate's storage backend and serializer I/O so the
            // comparison measures the wrapper and Keystore overhead on the same workload.
            DataStoreFactory.create(
                storage = OkioStorage(FileSystem.SYSTEM, BytesSerializer) { file.toOkioPath() },
                scope = scope,
            )
        }
        return OpenStore(store, scope)
    }

    private class OpenStore(val dataStore: DataStore<ByteArray>, private val scope: CoroutineScope) {
        suspend fun close() = scope.coroutineContext.job.cancelAndJoin()
    }

    private class StoreHolder(fileName: String, scope: CoroutineScope) {
        val Context.store by encryptedDataStore(fileName, BytesSerializer, scope = scope)
    }

    private object BytesSerializer : Serializer<ByteArray>, OkioSerializer<ByteArray> {
        override val defaultValue = ByteArray(0)

        override suspend fun readFrom(input: InputStream): ByteArray = input.readBytes()

        override suspend fun writeTo(t: ByteArray, output: OutputStream) = output.write(t)

        override suspend fun readFrom(source: BufferedSource): ByteArray = readFrom(source.inputStream())

        override suspend fun writeTo(t: ByteArray, sink: BufferedSink) = writeTo(t, sink.outputStream())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "sizeBytes={0,number,0}")
        fun payloadSizes(): List<Int> = listOf(64, 4 * 1024, 64 * 1024, 1024 * 1024)
    }
}
