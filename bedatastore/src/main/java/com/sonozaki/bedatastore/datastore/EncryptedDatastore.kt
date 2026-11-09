package com.sonozaki.bedatastore.datastore

import androidx.datastore.core.DataStore
import com.sonozaki.bedatastore.encryption.EncryptedSerializer
import com.sonozaki.bedatastore.entities.EncryptedData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Encrypted Datastore. Implements [DataStore] interface and add encryption and caching. Cache is overridden with empty value when no one subscribed to data flow.
 */
internal class EncryptedDatastore<T>(
    private val baseDatastore: DataStore<ByteArray>,
    private val encryptedSerializer: EncryptedSerializer<T>,
    private val coroutineContext: CoroutineContext
): DataStore<T> {

    // Track the value and its readers together so a write cannot repopulate the cache
    // after the last reader has cleared it.
    private val cachedFlow = MutableStateFlow(CacheState<T>(EncryptedData.EmptyResult()))

    private val mutex = Mutex()

    private val readMutex = Mutex()

    init {
        // StateFlow outlives the store's Job. Clear the plaintext and give collectors a
        // terminal value so they can finish without cancelling the collecting coroutine.
        coroutineContext[Job]?.invokeOnCompletion {
            cachedFlow.update { it.copy(value = EncryptedData.Closed) }
        }
    }

    override val data: Flow<T> = flow {
        cachedFlow.update { it.copy(collectors = it.collectors + 1) }
        try {
            initializeCache()
            emitAll(
                cachedFlow.map { it.value }
                    .takeWhile { it != EncryptedData.Closed }
                    .distinctUntilChanged()
                    .filterIsInstance<EncryptedData.Result<T>>()
                    .map { it.data },
            )
        } finally {
            cachedFlow.update { current ->
                val collectors = current.collectors - 1
                val value = if (collectors == 0 && current.value != EncryptedData.Closed) {
                    EncryptedData.EmptyResult()
                } else {
                    current.value
                }
                CacheState(value, collectors)
            }
        }
    }

    private suspend fun initializeCache() {
        if (cachedFlow.value.value !is EncryptedData.EmptyResult) return

        withContext(coroutineContext.minusKey(Job)) {
            readMutex.withLock {
                val beforeRead = cachedFlow.value.value
                if (beforeRead is EncryptedData.EmptyResult) {
                    val loaded = EncryptedData.Result(getInitialData())
                    // A commit or store closure can publish a newer state while the read runs.
                    cachedFlow.update { current ->
                        if (current.value === beforeRead) current.copy(value = loaded) else current
                    }
                }
            }
        }
    }

    /**
     * load and decrypt data from base DataStore
     */
    private suspend fun getInitialData(): T {
        val encryptedData = baseDatastore.data.first()
        return encryptedSerializer.decryptAndDeserialize(encryptedData)
    }


    /**
     * Transform data and write new data to the file and to the cache. Load data if no data present in the cache.
    */
    override suspend fun updateData(transform: suspend (t: T) -> T): T {
        // A transform runs while its store holds the mutex. Check before locking so a
        // nested call cannot wait on itself, even if it reaches this store through another one.
        val parentUpdate = currentCoroutineContext()[UpdateContext]
        check(parentUpdate?.contains(this) != true) {
            "Cannot call updateData on the same DataStore from within its updateData transform."
        }

        val storeContext = coroutineContext + UpdateContext(this, parentUpdate)
        return mutex.withLock {
            withContext(storeContext.minusKey(Job)) {
                val updateContext = currentCoroutineContext()
                // Keep the caller's cancellation during preparation and stop it if the store closes.
                val storeCompletion = storeContext[Job]?.invokeOnCompletion { updateContext.cancel() }
                try {
                    storeContext.ensureActive()
                    val data = when (val previous = cachedFlow.value.value) {
                        is EncryptedData.Result -> previous.data
                        is EncryptedData.EmptyResult -> readMutex.withLock { getInitialData() }
                        EncryptedData.Closed -> throw CancellationException("DataStore is closed")
                    }
                    val newData = transform(data)

                    // Once handed off, the store finishes the write and cache update together.
                    // A cancelled caller could otherwise stop waiting after the disk write started
                    // and leave the plaintext cache stale.
                    withContext(storeContext) {
                        updateContext.ensureActive()
                        if (newData == data) {
                            data
                        } else {
                            baseDatastore.updateData { encryptedSerializer.serializeAndEncrypt(newData) }
                            cachedFlow.update { current ->
                                if (current.collectors > 0 && current.value != EncryptedData.Closed) {
                                    current.copy(value = EncryptedData.Result(newData))
                                } else {
                                    current
                                }
                            }
                            newData
                        }
                    }
                } finally {
                    storeCompletion?.dispose()
                }
            }
        }
    }

    private data class CacheState<T>(val value: EncryptedData<T>, val collectors: Int = 0)

    private class UpdateContext(
        private val store: EncryptedDatastore<*>,
        private val parent: UpdateContext?,
    ) : AbstractCoroutineContextElement(Key) {
        fun contains(store: EncryptedDatastore<*>): Boolean =
            generateSequence(this) { it.parent }.any { it.store === store }

        companion object Key : CoroutineContext.Key<UpdateContext>
    }
}
