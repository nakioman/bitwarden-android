package com.x8bit.bitwarden.data.wear

import java.io.IOException

/** Keeps the distribution without Google Play services free of Wear transport dependencies. */
class WearDataSourceImpl : WearDataSource {
    override suspend fun getWatchKeys(): Result<List<WearWatchKey>> = Result.success(emptyList())

    override suspend fun getSnapshots(): Result<List<WearSnapshotInfo>> =
        Result.success(emptyList())

    override suspend fun putSnapshot(info: WearSnapshotInfo, envelope: ByteArray): Result<Unit> =
        Result.failure(IOException("Wear data unavailable"))

    override suspend fun deleteSnapshot(watchNodeId: String): Result<Unit> = Result.success(Unit)
}
