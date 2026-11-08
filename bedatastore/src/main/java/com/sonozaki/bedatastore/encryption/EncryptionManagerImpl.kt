package com.sonozaki.bedatastore.encryption

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec

internal class EncryptionManagerImpl : EncryptionManager {
    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    override fun encrypt(alias: String, bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateGcmKey(gcmKeyAlias(alias)))
        val nonce = cipher.iv
        if (nonce?.size != GCM_NONCE_SIZE) {
            throw GeneralSecurityException("Unexpected AES-GCM nonce size")
        }

        // Legacy files have a one-byte header followed by whole AES blocks, so their
        // length is always odd. Keep GCM files even by adding an authenticated header
        // byte when needed. Editing the format byte in place cannot select a legacy read.
        val header = if (bytes.size % 2 == 0) GCM_PADDED_HEADER else GCM_HEADER
        cipher.updateAAD(header)
        return header + nonce + cipher.doFinal(bytes)
    }

    override fun decrypt(alias: String, data: ByteArray): ByteArray =
        when (data.firstOrNull()) {
            GCM_V1 -> decryptGcm(alias, data, GCM_HEADER.size)
            GCM_V1_PADDED -> decryptGcm(alias, data, GCM_PADDED_HEADER.size)
            LEGACY_IV_SIZE.toByte() -> decryptLegacy(alias, data)
            else -> throw IOException("Unknown encrypted file format")
        }

    private fun decryptGcm(alias: String, data: ByteArray, headerSize: Int): ByteArray {
        val ciphertextStart = headerSize + GCM_NONCE_SIZE
        if (data.size < ciphertextStart + GCM_TAG_SIZE || data.size % 2 != 0) {
            throw IOException("Invalid AES-GCM file length")
        }
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        val parameters = GCMParameterSpec(GCM_TAG_SIZE * Byte.SIZE_BITS, data, headerSize, GCM_NONCE_SIZE)
        cipher.init(Cipher.DECRYPT_MODE, requireKey(gcmKeyAlias(alias)), parameters)
        cipher.updateAAD(data, 0, headerSize)
        return cipher.doFinal(data, ciphertextStart, data.size - ciphertextStart)
    }

    private fun decryptLegacy(alias: String, data: ByteArray): ByteArray {
        val ciphertextStart = 1 + LEGACY_IV_SIZE
        val ciphertextSize = data.size - ciphertextStart
        if (ciphertextSize <= 0 || ciphertextSize % LEGACY_IV_SIZE != 0) {
            throw IOException("Invalid legacy encrypted file length")
        }
        val cipher = Cipher.getInstance(LEGACY_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, requireKey(alias), IvParameterSpec(data, 1, LEGACY_IV_SIZE))
        return cipher.doFinal(data, ciphertextStart, ciphertextSize)
    }

    private fun requireKey(alias: String): SecretKey =
        findKey(alias) ?: throw KeyStoreException("Encryption key is missing")

    private fun findKey(alias: String): SecretKey? =
        when (val entry = keyStore.getEntry(alias, null)) {
            null -> null
            is KeyStore.SecretKeyEntry -> entry.secretKey
            else -> throw KeyStoreException("Expected a secret key")
        }

    // The manager is shared in the process. Concurrent first writes for the same alias
    // must reuse the first key so a second generation cannot invalidate saved data.
    @Synchronized
    private fun getOrCreateGcmKey(alias: String): SecretKey {
        findKey(alias)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_NONCE_SIZE = 12
        private const val GCM_TAG_SIZE = 16
        private const val GCM_V1: Byte = 1
        private const val GCM_V1_PADDED: Byte = 2
        private val GCM_HEADER = byteArrayOf(GCM_V1)
        private val GCM_PADDED_HEADER = byteArrayOf(GCM_V1_PADDED, 0)
        private const val LEGACY_TRANSFORMATION = "AES/CBC/PKCS7Padding"
        private const val LEGACY_IV_SIZE = 16

        // Legacy keys were authorized for CBC only. Keep them at their original aliases.
        internal fun gcmKeyAlias(alias: String): String = "$alias.bedatastore-gcm-v1"
    }
}
