package com.sonozaki.bedatastore.corruptionHandler

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.sonozaki.bedatastore.encryption.EncryptedSerializer
import com.sonozaki.bedatastore.testutil.TestEncryptionManager
import com.sonozaki.bedatastore.testutil.TestIntSerializer
import com.sonozaki.bedatastore.testutil.expectFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStoreException

class EncryptedCorruptionHandlerTest {
    private val encryption = TestEncryptionManager()
    private val serializer = EncryptedSerializer(encryption, TestIntSerializer(), "recovery-key")
    private val corruption = CorruptionException("Invalid application data")

    // AndroidX writes the value returned by the handler to disk. The app should receive
    // the original error, and AndroidX should receive ciphertext for the replacement.
    @Test
    fun encryptsTheReplacementReturnedByTheApplicationHandler() = runTest {
        var calls = 0
        val handler = wrapHandler {
            assertSame(corruption, it)
            calls++
            42
        }

        val replacement = handler.handleCorruption(corruption)

        assertEquals(1, calls)
        assertFalse(ByteBuffer.allocate(4).putInt(42).array().contentEquals(replacement))
        assertEquals(42, serializer.decryptAndDeserialize(replacement))
    }

    // The application may need to abort recovery. Its error must reach the storage layer
    // before the wrapper can encrypt or return a replacement.
    @Test
    fun propagatesFailureFromTheApplicationHandler() = runTest {
        val failure = IOException("Recovery source unavailable")
        encryption.encryptFailure = KeyStoreException("Encryption must not be attempted")
        val handler = wrapHandler { throw failure }

        val thrown = expectFailure<IOException> { handler.handleCorruption(corruption) }

        assertEquals(failure.message, thrown.message)
    }

    // A recovered value still needs encryption. If the key is unavailable, the handler
    // must report the failure so AndroidX cannot save the replacement as plaintext.
    @Test
    fun propagatesEncryptionFailureAfterTheApplicationRecoversAValue() = runTest {
        val failure = KeyStoreException("Recovery key unavailable")
        encryption.encryptFailure = failure
        val handler = wrapHandler { 42 }

        val thrown = expectFailure<KeyStoreException> { handler.handleCorruption(corruption) }

        assertEquals(failure.message, thrown.message)
    }

    private fun wrapHandler(recover: (CorruptionException) -> Int) =
        requireNotNull(encryptedCorruptionHandler(ReplaceFileCorruptionHandler(recover), serializer))
}
