package com.sonozaki.bedatastore.example.data

import androidx.datastore.core.Serializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream

class DBASerializer: Serializer<DBAExampleData> {
    override val defaultValue: DBAExampleData
        get() = DBAExampleData()

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun readFrom(input: InputStream): DBAExampleData {
        return Json.decodeFromStream<DBAExampleData>(input)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun writeTo(t: DBAExampleData, output: OutputStream) {
        Json.encodeToStream(t, output)
    }
}