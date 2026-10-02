package com.x8bit.bitwarden.wear.data.sync

/** Validates addressed companion commands and confirms only durable snapshot reception. */
interface WatchDataReceiver {
    /**
     * Returns an exact receipt only after persisting the command. The transport must supply
     * [sourceNodeId] from the authenticated DataItem owner and filter target paths before asset
     * download. This method never reads or decrypts OTP data and works while the watch is locked.
     */
    suspend fun receive(
        localNodeId: String,
        sourceNodeId: String,
        path: String?,
        packet: ByteArray,
    ): Result<ByteArray>
}
