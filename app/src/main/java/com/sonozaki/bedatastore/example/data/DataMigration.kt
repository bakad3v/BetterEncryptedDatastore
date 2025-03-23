package com.sonozaki.bedatastore.example.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.dataStoreFile
import com.sonozaki.bedatastore.datastore.encryptedDataStore
import kotlinx.coroutines.flow.first

class DataMigration(
    private val context: Context
): DataMigration<NewExampleData> {

    val Context.oldDatastore by encryptedDataStore(OLD_DATASTORE, ExampleSerializer())

    override suspend fun cleanUp() {
        context.dataStoreFile(OLD_DATASTORE).delete()
    }

    override suspend fun migrate(currentData: NewExampleData): NewExampleData {
        val oldData = context.oldDatastore.data.first()
        return NewExampleData(oldData.text + oldData.digit)
    }

    override suspend fun shouldMigrate(currentData: NewExampleData): Boolean {
        return context.dataStoreFile(OLD_DATASTORE).exists()
    }

    companion object {
        private const val OLD_DATASTORE = "test"
    }
}