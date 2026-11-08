package com.sonozaki.bedatastore.testutil

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioStorage
import com.sonozaki.bedatastore.corruptionHandler.encryptedCorruptionHandler
import com.sonozaki.bedatastore.datastore.EncryptedDatastore
import com.sonozaki.bedatastore.datastore.OkioSerializerWrapper
import com.sonozaki.bedatastore.encryption.EncryptedSerializer
import com.sonozaki.bedatastore.encryption.EncryptionManager
import com.sonozaki.bedatastore.migration.EncryptedMigration
import com.sonozaki.bedatastore.serializer.ByteArraySerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Source
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

internal class EncryptedStoreFixture(val file: File) {
    val encryption = TestEncryptionManager()
    val serializer = TestIntSerializer()
    val disk = FaultInjectingFileSystem()
    val encryptedSerializer = EncryptedSerializer(encryption, serializer, "test-key")

    // Use the production storage, migration, and recovery adapters so faults cross the same
    // boundaries as they do through the Android delegate. Only key management is replaced.
    fun open(
        testScope: TestScope,
        migrations: List<DataMigration<Int>> = emptyList(),
        corruptionHandler: ReplaceFileCorruptionHandler<Int>? = null,
    ): OpenStore {
        val parent = testScope.backgroundScope.coroutineContext
        val scope = CoroutineScope(parent + SupervisorJob(parent.job))
        val byteSerializer = ByteArraySerializer(serializer.defaultValue, encryptedSerializer)
        val base = DataStoreFactory.create(
            storage = OkioStorage(disk, OkioSerializerWrapper(byteSerializer)) {
                file.toOkioPath()
            },
            corruptionHandler = encryptedCorruptionHandler(corruptionHandler, encryptedSerializer),
            migrations = migrations.map { EncryptedMigration(it, encryptedSerializer) },
            scope = scope,
        )
        val encryptedStore = EncryptedDatastore(base, encryptedSerializer, scope.coroutineContext)
        return OpenStore(encryptedStore, base, scope)
    }
}

internal class OpenStore(
    val dataStore: DataStore<Int>,
    val ciphertextStore: DataStore<ByteArray>,
    private val scope: CoroutineScope,
) {
    suspend fun close() = scope.coroutineContext.job.cancelAndJoin()
}

internal class TestIntSerializer : Serializer<Int> {
    override val defaultValue = 0
    var writeFailure: IOException? = null

    override suspend fun readFrom(input: InputStream): Int = try {
        DataInputStream(input).readInt()
    } catch (failure: EOFException) {
        throw CorruptionException("Incomplete test value", failure)
    }

    override suspend fun writeTo(t: Int, output: OutputStream) {
        DataOutputStream(output).writeInt(t)
        // Fail after producing bytes to catch partial writes leaking through the wrapper.
        writeFailure?.let { throw it }
    }
}

internal class TestEncryptionManager : EncryptionManager {
    private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
    var decryptFailure: KeyStoreException? = null
    var encryptFailure: KeyStoreException? = null
    var decryptions = 0
        private set

    override fun encrypt(alias: String, bytes: ByteArray): ByteArray {
        encryptFailure?.let { throw it }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(bytes)
    }

    override fun decrypt(alias: String, data: ByteArray): ByteArray {
        decryptions++
        decryptFailure?.let { throw it }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = data.copyOfRange(0, 12)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return cipher.doFinal(data, nonce.size, data.size - nonce.size)
    }
}

internal class FaultInjectingFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {
    var readFailure: IOException? = null
    var bytesBeforeReadFailure = 0L
    var writeFailure: IOException? = null
    var commitFailure: IOException? = null

    override fun source(file: Path): Source = object : ForwardingSource(super.source(file)) {
        private var bytesRemaining = bytesBeforeReadFailure

        override fun read(sink: Buffer, byteCount: Long): Long {
            val failure = readFailure ?: return super.read(sink, byteCount)
            if (bytesRemaining == 0L) throw failure
            val read = super.read(sink, minOf(byteCount, bytesRemaining))
            if (read > 0) bytesRemaining -= read
            return read
        }
    }

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        val handle = super.openReadWrite(file, mustCreate, mustExist)
        val failure = writeFailure ?: return handle
        return object : FileHandle(readWrite = true) {
            override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
                // Leave a partial scratch file just as a write that runs out of space can do.
                handle.write(fileOffset, array, arrayOffset, minOf(byteCount, 1))
                throw failure
            }

            override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
                handle.read(fileOffset, array, arrayOffset, byteCount)

            override fun protectedFlush() = handle.flush()
            override fun protectedResize(size: Long) = handle.resize(size)
            override fun protectedSize() = handle.size()
            override fun protectedClose() = handle.close()
        }
    }

    override fun atomicMove(source: Path, target: Path) {
        commitFailure?.let { throw it }
        super.atomicMove(source, target)
    }
}

internal suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
    val actual = runCatching(block).exceptionOrNull()
    if (actual is T) return actual
    throw AssertionError("Expected ${T::class.simpleName} but got $actual", actual)
}
