package com.x8bit.bitwarden.data.wear

/** Retries durable encrypted commands without extracting OTPs or unlocking the vault. */
interface WearDeliveryManager {
    /**
     * Attempts pending deliveries and exact receipt processing. Unauthorized snapshots remain
     * queued for the lifecycle coordinator to replace with revocations. Revocations can be sent
     * after logout. Success does not imply receipt: disconnected watches keep pending commands.
     */
    suspend fun deliverPending(): Result<Unit>
}
