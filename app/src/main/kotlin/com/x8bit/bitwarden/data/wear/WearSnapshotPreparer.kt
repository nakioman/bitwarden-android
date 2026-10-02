package com.x8bit.bitwarden.data.wear

/** Prepares durable encrypted commands without starting transfers or retaining plaintext. */
interface WearSnapshotPreparer {
    /**
     * Encrypts all eligible OTP entries for the authorized active account and pinned recipient.
     * Rechecks account and consent after reading and encryption before committing the outbox.
     * The sender must revalidate consent independently before transmitting a queued snapshot.
     */
    suspend fun prepareSnapshot(userId: String, deviceId: String): Result<Unit>

    /**
     * Replaces pending ciphertext with a revocation for its recorded owner and enrollment.
     * Does not require an unlocked vault or an online watch. The caller must authorize this
     * operation through explicit user revocation or the trusted account/logout lifecycle.
     */
    suspend fun prepareRevocation(userId: String, deviceId: String): Result<Unit>
}
