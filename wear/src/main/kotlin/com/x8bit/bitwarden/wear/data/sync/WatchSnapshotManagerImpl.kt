package com.x8bit.bitwarden.wear.data.sync

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Persists ciphertext and anti-replay state together; revocation commits before key erasure. */
class WatchSnapshotManagerImpl(
    private val securityManager: WatchSecurityManager,
    private val keyManager: WatchSyncKeyManager,
    private val diskSource: WatchEnvelopeDiskSource,
    private val dispatcherManager: DispatcherManager,
) : WatchSnapshotManager {
    private val mutex = Mutex()

    override suspend fun provision(): Result<ByteArray> = withContext(dispatcherManager.io) {
        mutex.withLock {
            if (!hasSecureLock()) return@withLock denied()
            loadRecord().fold(
                onSuccess = { record ->
                    when {
                        record == null -> keyManager.createPublicKey()
                        record.frame.header.isRevocation -> {
                            eraseRevokedKey(record.frame).fold(
                                onSuccess = { keyManager.createPublicKey() },
                                onFailure = { Result.failure(it) },
                            )
                        }
                        else -> keyManager.getPublicKey()
                    }
                },
                onFailure = { Result.failure(it) },
            )
        }
    }

    override suspend fun receive(sourceNodeId: String, packet: ByteArray): Result<Unit> =
        withContext(dispatcherManager.io) {
            mutex.withLock {
                if (!hasSecureLock()) return@withLock denied()
                val frame = WearSnapshotProtocol.decode(packet).getOrElse {
                    return@withLock Result.failure(it)
                }
                val record = loadRecord().getOrElse { return@withLock Result.failure(it) }
                if (record != null && record.sourceNodeId != sourceNodeId) return@withLock denied()
                if (!frame.header.isRevocation && !keyManager.isCurrentRecipient(frame)) {
                    return@withLock denied()
                }
                if (record?.matches(packet) == true) return@withLock completeCommand(frame)
                if (!(record?.allows(frame) ?: !frame.header.isRevocation)) return@withLock denied()
                val encoded = WatchSnapshotRecord(sourceNodeId, frame).encode().getOrElse {
                    return@withLock Result.failure(it)
                }
                diskSource.write(encoded).fold(
                    onSuccess = { completeCommand(frame) },
                    onFailure = { Result.failure(it) },
                )
            }
        }

    override suspend fun read(): Result<ByteArray?> = withContext(dispatcherManager.io) {
        mutex.withLock {
            if (!hasSecureLock() || securityManager.getStatus() != WatchSecurityStatus.UNLOCKED) {
                return@withLock denied()
            }
            loadRecord().fold(
                onSuccess = { record ->
                    if (record == null || record.frame.header.isRevocation) {
                        Result.success(null)
                    } else {
                        decrypt(record.frame)
                    }
                },
                onFailure = { Result.failure(it) },
            )
        }
    }

    override suspend fun clear(): Result<Unit> = withContext(dispatcherManager.io) {
        mutex.withLock { eraseAll() }
    }

    private fun loadRecord(): Result<WatchSnapshotRecord?> = diskSource.read().fold(
        onSuccess = { bytes ->
            bytes?.let { WatchSnapshotRecord.decode(it) } ?: Result.success(null)
        },
        onFailure = { Result.failure(it) },
    )

    private fun completeCommand(frame: WearSnapshotFrame): Result<Unit> =
        if (frame.header.isRevocation) eraseRevokedKey(frame) else Result.success(Unit)

    private fun eraseRevokedKey(frame: WearSnapshotFrame): Result<Unit> =
        keyManager.getPublicKey().fold(
            onSuccess = { key ->
                // A delayed duplicate revocation must not delete a newly provisioned key.
                if (WearSnapshotProtocol.recipientKeyId(key) == frame.header.recipientKeyId) {
                    keyManager.deleteKey()
                } else {
                    Result.success(Unit)
                }
            },
            onFailure = { keyManager.deleteKey() },
        )

    private fun decrypt(frame: WearSnapshotFrame): Result<ByteArray?> =
        WearSnapshotProtocol.context(frame.header).fold(
            onSuccess = { context ->
                keyManager.decrypt(frame.ciphertext, context).mapCatching { plaintext ->
                    if (securityManager.getStatus() != WatchSecurityStatus.UNLOCKED) {
                        plaintext.fill(0)
                        throw IOException("Watch locked during snapshot read")
                    }
                    plaintext
                }
            },
            onFailure = { Result.failure(it) },
        )

    private fun hasSecureLock(): Boolean = when (securityManager.getStatus()) {
        WatchSecurityStatus.UNLOCKED, WatchSecurityStatus.LOCKED -> true
        WatchSecurityStatus.SCREEN_LOCK_REQUIRED -> {
            eraseAll()
            false
        }
        WatchSecurityStatus.UNVERIFIED -> false
    }

    private fun eraseAll(): Result<Unit> {
        val keyResult = keyManager.deleteKey()
        val diskResult = diskSource.delete()
        return keyResult.fold(onSuccess = { diskResult }, onFailure = { Result.failure(it) })
    }
}

private fun WatchSyncKeyManager.isCurrentRecipient(frame: WearSnapshotFrame): Boolean =
    getPublicKey().getOrNull()?.let {
        WearSnapshotProtocol.recipientKeyId(it) == frame.header.recipientKeyId
    } == true

private fun <T> denied(): Result<T> = Result.failure(IOException("Snapshot access denied"))
