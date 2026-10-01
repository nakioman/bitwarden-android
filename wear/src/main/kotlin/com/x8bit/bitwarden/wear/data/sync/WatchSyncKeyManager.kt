package com.x8bit.bitwarden.wear.data.sync

/** Owns the non-exportable private key used to decrypt companion snapshots. */
interface WatchSyncKeyManager {
    /** Creates a recipient key only as part of explicit provisioning. */
    fun createPublicKey(): Result<ByteArray>

    /** Reads an existing public key, without silently replacing a missing private key. */
    fun getPublicKey(): Result<ByteArray>

    /** Decrypts a snapshot for its previously validated enrollment context. */
    fun decrypt(envelope: ByteArray, context: ByteArray): Result<ByteArray>

    /** Deletes the private key so previously encrypted snapshots cannot be decrypted. */
    fun deleteKey(): Result<Unit>
}
