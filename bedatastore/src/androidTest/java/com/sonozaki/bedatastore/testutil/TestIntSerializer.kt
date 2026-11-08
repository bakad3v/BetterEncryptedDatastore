package com.sonozaki.bedatastore.testutil

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

internal object TestIntSerializer : Serializer<Int> {
    override val defaultValue = 0

    override suspend fun readFrom(input: InputStream): Int = try {
        DataInputStream(input).readInt()
    } catch (failure: EOFException) {
        throw CorruptionException("Incomplete test value", failure)
    }

    override suspend fun writeTo(t: Int, output: OutputStream) = DataOutputStream(output).writeInt(t)
}
