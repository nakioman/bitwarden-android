package com.x8bit.bitwarden.wear.data.sync

/** Owns the non-exportable private key used to decrypt companion snapshots. */
interface WatchSyncKeyManager {
    /** Returns the public key, creating a new key pair first if none exists. */
    fun createPublicKey(): Result<ByteArray>

    /** Reads the existing public key; fails if there is none. */
    fun getPublicKey(): Result<ByteArray>

    /** Decrypts a snapshot envelope bound to [context]. */
    fun decrypt(envelope: ByteArray, context: ByteArray): Result<ByteArray>

    /** Deletes the private key so previously encrypted snapshots cannot be decrypted. */
    fun deleteKey(): Result<Unit>
}
