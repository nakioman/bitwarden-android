package com.x8bit.bitwarden.data.wear

/** Reads watch keys and publishes encrypted snapshots over the Wear Data Layer. */
interface WearDataSource {
    /** Returns the public keys currently published by watches running the companion app. */
    suspend fun getWatchKeys(): Result<List<WearWatchKey>>

    /** Returns the snapshots this phone has published. */
    suspend fun getSnapshots(): Result<List<WearSnapshotInfo>>

    /**
     * Publishes or replaces the snapshot for [WearSnapshotInfo.watchNodeId]. The watch receives
     * it when it next connects; success does not mean it has been delivered yet.
     */
    suspend fun putSnapshot(info: WearSnapshotInfo, envelope: ByteArray): Result<Unit>

    /** Deletes the snapshot this phone published for [watchNodeId], if any. */
    suspend fun deleteSnapshot(watchNodeId: String): Result<Unit>
}

/** A watch's published recipient key. */
class WearWatchKey(val nodeId: String, val publicKey: ByteArray)

/**
 * Non-secret metadata of a published snapshot.
 *
 * @property watchNodeId Watch the snapshot is addressed to.
 * @property owner Opaque tag of the account whose codes it contains.
 * @property recipientKeyId Watch key the snapshot was encrypted for.
 * @property contentTag Opaque tag used to skip republishing unchanged content.
 */
data class WearSnapshotInfo(
    val watchNodeId: String,
    val owner: String,
    val recipientKeyId: String,
    val contentTag: String,
)
