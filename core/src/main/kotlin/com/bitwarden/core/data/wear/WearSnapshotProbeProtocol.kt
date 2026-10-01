package com.bitwarden.core.data.wear

/** Synthetic-only debug endpoints; they must never be bound to production keys or snapshots. */
object WearSnapshotProbeProtocol {
    /** Erases only the transport probe's synthetic cache and key. */
    const val RESET_PATH = "/bitwarden/wear/probe/snapshot/v1/reset"
    /** Obtains only the transport probe's public key. */
    const val KEY_PATH = "/bitwarden/wear/probe/snapshot/v1/key"
    /** Delivers a synthetic frame to the isolated probe cache. */
    const val DELIVERY_PATH = "/bitwarden/wear/probe/snapshot/v1/deliver"
    /** Checks synthetic plaintext locally; no plaintext is returned over the transport. */
    const val VERIFY_PATH = "/bitwarden/wear/probe/snapshot/v1/verify"
    /** The bounded list of paths handled by the debug probe service. */
    val paths: Set<String> = setOf(RESET_PATH, KEY_PATH, DELIVERY_PATH, VERIFY_PATH)
    /** Non-sensitive marker used exclusively as test data. */
    const val SYNTHETIC_TEXT = "synthetic-wear-transport-payload"
    /** Successful persistence or reset. */
    const val ACCEPTED: Byte = 1
    /** Rejected packet or failed operation. */
    const val REJECTED: Byte = 2
    /** Secure read unavailable while the watch is locked. */
    const val LOCKED: Byte = 3
    /** Local decrypted data matched the synthetic marker. */
    const val MATCHED: Byte = 4
    /** No snapshot remains after revocation. */
    const val EMPTY: Byte = 5
}
