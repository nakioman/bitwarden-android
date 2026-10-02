package com.x8bit.bitwarden.data.wear

/** Publishes encrypted packets and retrieves acknowledgments from their target companion. */
interface WearDataSource {
    /**
     * Publishes a packet for synchronization, including while the watch is disconnected.
     * Success does not mean the watch received or persisted it.
     */
    suspend fun publish(deviceId: String, packet: ByteArray): Result<Unit>

    /** Returns the receipt owned by the exact companion, or null if none is available. */
    suspend fun getReceipt(deviceId: String): Result<ByteArray?>
}
