package com.sonozaki.bedatastore.example.data

import androidx.datastore.core.Serializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream

class ExampleSerializer: Serializer<ExampleData> {
    override val defaultValue: ExampleData
        get() = ExampleData()

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun readFrom(input: InputStream): ExampleData {
        return Json.decodeFromStream<ExampleData>(input)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun writeTo(t: ExampleData, output: OutputStream) {
        Json.encodeToStream(t, output)
    }
}