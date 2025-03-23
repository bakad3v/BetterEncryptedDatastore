package com.sonozaki.bedatastore.example.data

import android.content.Context
import com.sonozaki.bedatastore.datastore.encryptedDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class NewExampleRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val Context.exampleDatastore by encryptedDataStore(NAME, NewExampleSerializer(), produceMigrations =
        {context -> listOf(DataMigration(context))})

    val data = context.exampleDatastore.data

    companion object {
        private const val NAME: String = "test2"
    }
}