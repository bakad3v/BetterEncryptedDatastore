package com.sonozaki.bedatastore.example.data

import androidx.datastore.core.Serializer
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream

class NewExampleSerializer: Serializer<NewExampleData> {
    override val defaultValue: NewExampleData
        get() = NewExampleData()

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun readFrom(input: InputStream): NewExampleData {
        delay(2000)
        return Json.decodeFromStream<NewExampleData>(input)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun writeTo(
        t: NewExampleData,
        output: OutputStream
    ) {
        Json.encodeToStream(t, output)

    }
}