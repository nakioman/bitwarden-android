package com.x8bit.bitwarden.wear.data.sync

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearDataProtocol
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import com.x8bit.bitwarden.wear.data.sync.transport.WatchDataTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The Data Layer is the only copy of the snapshot, so there is no local state to fall out of
 * sync with the phone. If the key is ever replaced, publishing the new key makes the phone
 * re-encrypt for it.
 *
 * @property securityManager Uncached system lock checks.
 * @property keyManager Non-exportable recipient key.
 * @property transport Data Layer access.
 * @property dispatcherManager Worker dispatcher for key and transport operations.
 */
class WatchSnapshotManagerImpl(
    private val securityManager: WatchSecurityManager,
    private val keyManager: WatchSyncKeyManager,
    private val transport: WatchDataTransport,
    private val dispatcherManager: DispatcherManager,
) : WatchSnapshotManager {
    private val mutex = Mutex()
    private val keyChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val changes: Flow<Unit> = merge(transport.snapshotChanges, keyChanges)

    override suspend fun provision(): Result<Unit> = withContext(dispatcherManager.io) {
        mutex.withLock {
            when (securityManager.getStatus()) {
                WatchSecurityStatus.UNLOCKED, WatchSecurityStatus.LOCKED -> publishKey()
                WatchSecurityStatus.SCREEN_LOCK_REQUIRED -> withdrawKey()
                WatchSecurityStatus.UNVERIFIED -> denied()
            }
        }
    }

    override suspend fun read(): Result<ByteArray?> = withContext(dispatcherManager.io) {
        mutex.withLock {
            if (!isUnlocked()) return@withLock denied()
            val snapshot = transport.readSnapshot().getOrElse { return@withLock Result.failure(it) }
                ?: return@withLock Result.success(null)
            // A missing key or a snapshot for an older key means the phone has not caught up yet.
            val keyId = keyManager.getPublicKey().getOrNull()
                ?.let(WearDataProtocol::recipientKeyId)
            if (keyId == null || keyId != snapshot.recipientKeyId) {
                return@withLock Result.success(null)
            }
            keyManager.decrypt(snapshot.envelope, WearDataProtocol.snapshotContext(keyId))
                .mapCatching { plaintext ->
                    if (!isUnlocked()) {
                        plaintext.fill(0)
                        throw IOException("Watch locked during snapshot read")
                    }
                    plaintext
                }
        }
    }

    private suspend fun publishKey(): Result<Unit> {
        val hadKey = keyManager.getPublicKey().isSuccess
        val key = keyManager.createPublicKey().getOrElse { return Result.failure(it) }
        return transport.publishPublicKey(key).onSuccess {
            if (!hadKey) keyChanges.tryEmit(Unit)
        }
    }

    private suspend fun withdrawKey(): Result<Unit> {
        val keyResult = keyManager.deleteKey()
        val transportResult = transport.deletePublicKey()
        keyChanges.tryEmit(Unit)
        return keyResult.fold(onSuccess = { transportResult }, onFailure = { Result.failure(it) })
    }

    private fun isUnlocked(): Boolean =
        securityManager.getStatus() == WatchSecurityStatus.UNLOCKED
}

private fun <T> denied(): Result<T> = Result.failure(IOException("Snapshot access denied"))
