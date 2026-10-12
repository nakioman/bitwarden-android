package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.wear.WearOtpEntry

/** Publishes and removes encrypted verification code snapshots for watches. */
interface WearSnapshotStore {
    /**
     * Encrypts [entries] for every watch that has published a key and replaces each watch's
     * snapshot. Snapshots addressed to watches that no longer publish a key are removed.
     * [isStillAllowed] is checked immediately before each write; once it returns false nothing
     * further is published.
     */
    suspend fun publish(
        userId: String,
        entries: List<WearOtpEntry>,
        isStillAllowed: () -> Boolean,
    ): Result<Unit>

    /** Removes every snapshot containing [userId]'s codes. */
    suspend fun remove(userId: String): Result<Unit>
}
