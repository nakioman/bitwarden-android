package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.wear.WearSnapshotHeader
import kotlinx.serialization.Serializable

/** Persists encrypted pending packets and their revision counters as one transaction. */
interface WearOutboxDiskSource {
    /** Returns the complete ledger, failing closed if it cannot be read or validated. */
    fun read(): Result<List<WearOutboxRecord>>

    /** Commits the complete ledger before reporting success. */
    fun write(records: List<WearOutboxRecord>): Result<Unit>
}

/**
 * Persistent delivery state for a companion, containing no plaintext vault contents.
 *
 * @property deviceId Companion node identifier.
 * @property reservedRevision Last revision reserved before packet preparation.
 * @property userId Owner of the last queued command; null before the first command.
 * @property lastHeader Last queued command metadata, retained after acknowledgment.
 * @property lastDigest SHA-256 fingerprint of that command, retained after acknowledgment.
 * @property pendingPacket Base64 encoded wire packet, or null after acknowledgment.
 */
@Serializable
data class WearOutboxRecord(
    val deviceId: String,
    val reservedRevision: Long,
    val userId: String? = null,
    val lastHeader: WearSnapshotHeader? = null,
    val lastDigest: String? = null,
    val pendingPacket: String? = null,
)
