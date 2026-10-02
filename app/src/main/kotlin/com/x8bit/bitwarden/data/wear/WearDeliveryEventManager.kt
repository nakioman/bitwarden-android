package com.x8bit.bitwarden.data.wear

/** Handles authenticated Data Layer hints without treating event contents as acknowledgments. */
interface WearDeliveryEventManager {
    /** Requests a fresh receipt lookup for an enrolled owner and its canonical receipt path. */
    suspend fun receiptChanged(scheme: String?, ownerNodeId: String?, path: String?): Result<Unit>

    /** Retries committed commands when the companion capability reports reachable nodes. */
    suspend fun capabilityChanged(name: String, hasReachableNodes: Boolean): Result<Unit>
}
