package com.x8bit.bitwarden.wear.data.sync.transport

import kotlinx.coroutines.flow.Flow

/** Data Layer access for this watch's key and the snapshot the phone publishes for it. */
interface WatchDataTransport {
    /** Emits whenever a snapshot addressed to any watch is published or deleted. */
    val snapshotChanges: Flow<Unit>

    /** Publishes this watch's public key; publishing the same key again changes nothing. */
    suspend fun publishPublicKey(publicKey: ByteArray): Result<Unit>

    /** Removes this watch's published public key so the phone stops sending codes. */
    suspend fun deletePublicKey(): Result<Unit>

    /** Reads the latest snapshot addressed to this watch, or null if there is none. */
    suspend fun readSnapshot(): Result<WatchSnapshot?>
}

/** An encrypted snapshot and the identifier of the key it was encrypted for. */
class WatchSnapshot(val recipientKeyId: String, val envelope: ByteArray)
