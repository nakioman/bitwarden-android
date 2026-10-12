package com.x8bit.bitwarden.wear.data.sync

import kotlinx.coroutines.flow.Flow

/** Gives the phone this watch's key and decrypts the codes the phone publishes for it. */
interface WatchSnapshotManager {
    /** Emits when the published snapshot or this watch's key changes; subscribers must reread. */
    val changes: Flow<Unit>

    /**
     * Ensures this watch has a key and that the phone can see it. Without a secure screen lock
     * the key is deleted and withdrawn, so the phone stops sending codes. Safe to call repeatedly.
     */
    suspend fun provision(): Result<Unit>

    /**
     * Decrypts the latest snapshot while the watch is unlocked. Returns null when there is no
     * snapshot for this watch's current key yet.
     */
    suspend fun read(): Result<ByteArray?>
}
