package com.x8bit.bitwarden.wear.data.sync

/** Separates background ciphertext reception from foreground plaintext access. */
interface WatchSecureStorageManager {
    /** Returns the public recipient key for an explicit provisioning request. */
    suspend fun provision(): Result<ByteArray>

    /** Stores an already authenticated transport envelope, including while the watch is locked. */
    suspend fun receive(envelope: ByteArray): Result<Unit>

    /** Decrypts only on a secure, unlocked watch; enrollment context is validated by the caller. */
    suspend fun read(context: ByteArray): Result<ByteArray?>

    /** Erases the envelope and recipient key. Revocations can run while the watch is locked. */
    suspend fun clear(): Result<Unit>
}
