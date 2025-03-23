package com.sonozaki.bedatastore.entities

/**
 * Wrapper for data from encrypted datastore. When data in cache is no longer needed, it's overridden with [EmptyResult].
 */
internal sealed class EncryptedData<T> {
    class EmptyResult<T>: EncryptedData<T>()
    data class Result<T>(val data: T): EncryptedData<T>()
}