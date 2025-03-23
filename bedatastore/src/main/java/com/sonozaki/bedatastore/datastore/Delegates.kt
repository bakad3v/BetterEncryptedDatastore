package com.sonozaki.bedatastore.datastore

import android.content.Context
import androidx.annotation.GuardedBy
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.sonozaki.bedatastore.corruptionHandler.encryptedCorruptionHandler
import com.sonozaki.bedatastore.encryption.EncryptedSerializer
import com.sonozaki.bedatastore.encryption.EncryptionManagerFactory
import com.sonozaki.bedatastore.migration.getEncryptedMigrations
import com.sonozaki.bedatastore.serializer.ByteArraySerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty


/**
 * Delegate for creating singleton instance of Better Encrypted DataStore. Most parameters are same with [androidx.datastore.dataStore]. Additional params:
 * @param alias alias for encryption key. By default the name of DataStore file.
 * @param isDBA is Datastore file stored in device protected storage. By default false.
 */
fun <T> encryptedDataStore(
    fileName: String,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>? = null,
    produceMigrations: (Context) -> List<DataMigration<T>> = { listOf() },
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    alias: String = fileName,
    isDBA: Boolean = false
): ReadOnlyProperty<Context, DataStore<T>> {
    return EncryptedDatastoreSingletonDelegate(
        fileName, serializer, corruptionHandler, produceMigrations, scope, alias, isDBA
    )
}

/**
 * Delegate for creating singleton instance of DataStore with file stored in device protected storage. All parameters are same with [androidx.datastore.dataStore].
 */
fun <T> dataStoreDBA(
    fileName: String,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>? = null,
    produceMigrations: (Context) -> List<DataMigration<T>> = { listOf() },
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): ReadOnlyProperty<Context, DataStore<T>> {
    return DataStoreSingletonDelegateDBA(
        fileName, OkioSerializerWrapper(serializer), corruptionHandler, produceMigrations, scope
    )
}

internal class DataStoreSingletonDelegateDBA<T> internal constructor(
    private val fileName: String,
    private val serializer: OkioSerializer<T>,
    private val corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    private val produceMigrations: (Context) -> List<DataMigration<T>>,
    private val scope: CoroutineScope
) : ReadOnlyProperty<Context, DataStore<T>> {

    private val lock = Any()

    @GuardedBy("lock")
    @Volatile
    private var INSTANCE: DataStore<T>? = null

    /**
     * Gets the instance of the direct boot aware DataStore.
     *
     * @param thisRef must be an instance of [Context]
     * @param property not used
     */
    override fun getValue(thisRef: Context, property: KProperty<*>): DataStore<T> {
        return INSTANCE ?: synchronized(lock) {
            if (INSTANCE == null) {
                val applicationContext = thisRef.applicationContext.createDeviceProtectedStorageContext()
                INSTANCE = DataStoreFactory.create(
                    storage = OkioStorage(FileSystem.SYSTEM, serializer) {
                        applicationContext.dataStoreFile(fileName, true).absolutePath.toPath()
                    },
                    corruptionHandler = corruptionHandler,
                    migrations = produceMigrations(applicationContext),
                    scope = scope
                )
            }
            INSTANCE!!
        }
    }
}


private class EncryptedDatastoreSingletonDelegate<T>(
    private val fileName: String,
    private val serializer: Serializer<T>,
    private val corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    private val produceMigrations: (Context) -> List<DataMigration<T>>,
    private val scope: CoroutineScope,
    private val alias: String,
    private val isDBA: Boolean
) : ReadOnlyProperty<Context, EncryptedDatastore<T>> {

    private val lock = Any()

    @GuardedBy("lock")
    @Volatile
    private var INSTANCE: EncryptedDatastore<T>? = null


    /**
     * Gets the instance of the Better Encrypted DataStore.
     *
     * @param thisRef must be an instance of [Context]
     * @param property not used
     */
    override fun getValue(thisRef: Context, property: KProperty<*>): EncryptedDatastore<T> {
        return INSTANCE ?: synchronized(lock) {
            if (INSTANCE == null) {
                val applicationContext = if (isDBA) {
                    thisRef.applicationContext.createDeviceProtectedStorageContext() //use device protected storage context if datastore file is configured to be stored in device protected storage.
                } else {
                    thisRef.applicationContext
                }
                //get instance of encryption manager
                val encryptionManager = EncryptionManagerFactory.get()
                //get encrypted serializer
                val encryptedSerializer = EncryptedSerializer(encryptionManager, serializer, alias)
                //create wrappers for migrations and corruption handler
                val encryptedCorruptionHandler = encryptedCorruptionHandler(corruptionHandler, encryptedSerializer)
                val encryptedMigrations = getEncryptedMigrations(applicationContext, produceMigrations, encryptedSerializer)
                //create base serializer and base DataStore
                val byteArraySerializer = ByteArraySerializer(serializer.defaultValue, encryptedSerializer)
                val datastore = DataStoreFactory.create(
                    storage = OkioStorage(FileSystem.SYSTEM, OkioSerializerWrapper(byteArraySerializer)) {
                        applicationContext.dataStoreFile(fileName, isDBA).absolutePath.toPath()
                    },
                    corruptionHandler = encryptedCorruptionHandler,
                    migrations = encryptedMigrations,
                    scope = scope
                )
                //create encrypted datastore
                INSTANCE = EncryptedDatastore(datastore, encryptedSerializer, scope.coroutineContext)
            }
            INSTANCE!!
        }
    }
}

/**
* Generate the File object for DataStore based on the provided context and name. Supports device protected storage.
* This is public to allow for testing and backwards compatibility (e.g. if moving from the
* `dataStore` delegate or context.createDataStore to DataStoreFactory).
*
* Do NOT use the file outside of DataStore.
*
* @param this the context of the application used to get the files directory
* @param fileName the file name
* @param isDBA is DataStore file stored in device protected storeage.
*/
fun Context.dataStoreFile(fileName: String, isDBA: Boolean): File {
    val context = if (isDBA) {
        createDeviceProtectedStorageContext()
    } else {
        applicationContext
    }
    return File(context.filesDir, "datastore/$fileName")
}

internal class OkioSerializerWrapper<T>(private val delegate: Serializer<T>) : OkioSerializer<T> {
    override val defaultValue: T
        get() = delegate.defaultValue

    override suspend fun readFrom(source: BufferedSource): T {
        return delegate.readFrom(source.inputStream())
    }

    override suspend fun writeTo(t: T, sink: BufferedSink) {
        delegate.writeTo(t, sink.outputStream())
    }
}