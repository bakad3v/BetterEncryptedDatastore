package com.sonozaki.bedatastore.datastore

import androidx.datastore.core.DataStore
import com.sonozaki.bedatastore.encryption.EncryptedSerializer
import com.sonozaki.bedatastore.entities.EncryptedData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Encrypted Datastore. Implements [DataStore] interface and add encryption and caching. Cache is overridden with empty value when no one subscribed to data flow.
 */
internal class EncryptedDatastore<T>(
    private val baseDatastore: DataStore<ByteArray>,
    private val encryptedSerializer: EncryptedSerializer<T>,
    private val coroutineContext: CoroutineContext
): DataStore<T> {

    /**
     * Cache for unencrypted data.
     */
    private val cachedFlow = MutableStateFlow<EncryptedData<T>>(EncryptedData.EmptyResult())

    private val mutex = Mutex()

    private val mutex1 = Mutex()


    @OptIn(ExperimentalCoroutinesApi::class)
    override val data: Flow<T> = cachedFlow
        //load new data when someone subscribed and cache is empty
        .onSubscription {
            cachedFlow.getAndUpdate {
                withContext(coroutineContext) {
                    when(it) {
                        is EncryptedData.EmptyResult -> {
                            val resultData = getInitialData()
                            EncryptedData.Result(resultData)
                        }
                        is EncryptedData.Result -> it
                    }
                }
            }
        }
        //override cache with empty value when no subscribers left
        .onCompletion {
            if (cachedFlow.subscriptionCount.value == 0) {
                cachedFlow.emit(EncryptedData.EmptyResult())
            }
        }.filter {
            it is EncryptedData.Result
        }.map {
            (it as EncryptedData.Result).data
        }

    /**
     * load and decrypt data from base DataStore
     */
    private suspend fun getInitialData(): T = mutex1.withLock {
        val encryptedData = baseDatastore.data.first()
        return encryptedSerializer.decryptAndDeserialize(encryptedData)
    }


    /**
     * Transform data and write new data to the file and to the cache. Load data if no data present in the cache.
    */
    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock {
        withContext(coroutineContext) {
            val previous = cachedFlow.value
            val data = when (previous) {
                is EncryptedData.Result -> {
                    previous.data
                }
                is EncryptedData.EmptyResult -> {
                    getInitialData()
                }
            }
            val newData = transform(data)
            if (newData == data) {
                return@withContext data
            }
            baseDatastore.updateData { encryptedSerializer.serializeAndEncrypt(newData) }
            cachedFlow.emit(EncryptedData.Result(newData))
            return@withContext newData
        }
    }
}