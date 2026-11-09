package com.sonozaki.bedatastore.recovery

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import com.sonozaki.bedatastore.datastore.EncryptedDatastore
import com.sonozaki.bedatastore.datastore.OkioSerializerWrapper
import com.sonozaki.bedatastore.encryption.EncryptedSerializer
import com.sonozaki.bedatastore.encryption.EncryptionManagerFactory
import com.sonozaki.bedatastore.migration.EncryptedMigration
import com.sonozaki.bedatastore.serializer.ByteArraySerializer
import com.sonozaki.bedatastore.testutil.TestIntSerializer
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

internal const val OLD_VALUE = 41
internal const val NEW_VALUE = 42
internal const val EXTRA_DIRECTORY = "directory"
internal const val EXTRA_CRASH_POINT = "crashPoint"

internal enum class CrashPoint {
    BEFORE_REPLACE,
    AFTER_REPLACE,
    DURING_READ,
    BEFORE_MIGRATION_REPLACE,
}

internal class RecoveryFiles(val directory: File) {
    val store = directory.resolve("values")
    val scratch = directory.resolve("${store.name}.tmp")
    val checkpoint = directory.resolve("checkpoint")
    val migrationCleanup = directory.resolve("migration-cleanup")
    val alias = directory.name

    val migration = object : DataMigration<Int> {
        override suspend fun shouldMigrate(currentData: Int) = currentData == OLD_VALUE

        override suspend fun migrate(currentData: Int) = NEW_VALUE

        override suspend fun cleanUp() = migrationCleanup.writeText("done")
    }

    // Use the same adapters and Android Keystore as the public delegate. The test supplies
    // its own filesystem only to stop the worker at a specific disk operation.
    fun open(
        scope: CoroutineScope,
        fileSystem: FileSystem = FileSystem.SYSTEM,
        migrations: List<DataMigration<Int>> = emptyList(),
    ): DataStore<Int> {
        val serializer = EncryptedSerializer(EncryptionManagerFactory.get(), TestIntSerializer, alias)
        val base = DataStoreFactory.create(
            storage = OkioStorage(
                fileSystem,
                OkioSerializerWrapper(ByteArraySerializer(TestIntSerializer.defaultValue, serializer)),
            ) { store.toOkioPath() },
            migrations = migrations.map { EncryptedMigration(it, serializer) },
            scope = scope,
        )
        return EncryptedDatastore(base, serializer, scope.coroutineContext)
    }
}
