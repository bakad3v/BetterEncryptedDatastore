package com.sonozaki.bedatastore.example.data

import android.content.Context
import com.sonozaki.bedatastore.datastore.dataStoreDBA
import com.sonozaki.bedatastore.datastore.encryptedDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExampleRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val Context.exampleDatastore by encryptedDataStore(NAME, ExampleSerializer())

    private val Context.dbaDatastore by dataStoreDBA(DBA_NAME, DBASerializer())

    val data = context.exampleDatastore.data

    val dbaData = context.dbaDatastore.data

    suspend fun update(newData: ExampleData) {
        context.dbaDatastore.updateData {
            DBAExampleData(newData.text)
        }
        context.exampleDatastore.updateData {
            newData
        }

    }

    suspend fun increaseDigit() {
        context.exampleDatastore.updateData {
            it.copy(digit = it.digit + 1)
        }
    }

    companion object {
        private const val NAME = "test"
        private const val DBA_NAME = "test2"
    }
}