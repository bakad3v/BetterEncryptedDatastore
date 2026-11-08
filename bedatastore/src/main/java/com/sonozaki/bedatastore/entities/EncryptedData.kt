package com.sonozaki.bedatastore.entities

/** State of the plaintext cache, including the terminal state after the store closes. */
internal sealed class EncryptedData<out T> {
    class EmptyResult<T>: EncryptedData<T>()
    data class Result<T>(val data: T): EncryptedData<T>()
    data object Closed : EncryptedData<Nothing>()
}
