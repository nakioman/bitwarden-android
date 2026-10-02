package com.x8bit.bitwarden.wear.data.sync

import kotlinx.coroutines.flow.Flow

/** Enforces one active enrollment and monotonic commands from its authenticated phone. */
interface WatchSnapshotManager {
    /** Signals committed snapshot changes or erasure; subscribers must reread protected data. */
    val changes: Flow<Unit>

    /** Returns a recipient key while secure screen locking is configured. */
    suspend fun provision(): Result<ByteArray>

    /**
     * Accepts a packet from an independently authenticated [sourceNodeId]. Reception is allowed
     * while locked. Enrollment replacement requires revocation of the current enrollment first.
     */
    suspend fun receive(sourceNodeId: String, packet: ByteArray): Result<Unit>

    /** Decrypts the latest offline snapshot only while the watch is unlocked. */
    suspend fun read(): Result<ByteArray?>

    /** Explicitly erases the key, cache and source binding. */
    suspend fun clear(): Result<Unit>
}
