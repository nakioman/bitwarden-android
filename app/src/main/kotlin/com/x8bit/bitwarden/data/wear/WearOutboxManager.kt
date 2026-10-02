package com.x8bit.bitwarden.data.wear

/**
 * Maintains durable revisions and pending encrypted commands independently of connectivity.
 * Callers must authenticate the companion and enforce consent before enqueueing or sending.
 */
interface WearOutboxManager {
    /** Reads validated enrollment metadata under the same lock used for queue mutations. */
    suspend fun getRecord(deviceId: String): Result<WearOutboxRecord?>

    /** Reserves a strictly increasing revision before encryption, even if preparation fails. */
    suspend fun reserveRevision(deviceId: String): Result<Long>

    /**
     * Stores a command using the latest reserved revision. Revocation replaces pending ciphertext.
     * Changing accounts or enrollment requires acknowledged revocation and a fresh recipient key.
     */
    suspend fun enqueue(userId: String, deviceId: String, packet: ByteArray): Result<Unit>

    /** Returns pending commands for retry. Does not consult or unlock the vault. */
    suspend fun getPending(): Result<List<WearPendingPacket>>

    /**
     * Clears only the exact pending packet after an independently authenticated acknowledgment
     * that the watch committed it. A transport send result alone is insufficient.
     */
    suspend fun acknowledge(deviceId: String, packet: ByteArray): Result<Unit>
}

/**
 * An encrypted command awaiting confirmation.
 *
 * @property userId Owner of the command.
 * @property deviceId Target companion node.
 * @property packet Ciphertext or an empty revocation frame, never plaintext OTP data.
 */
class WearPendingPacket(val userId: String, val deviceId: String, val packet: ByteArray)
