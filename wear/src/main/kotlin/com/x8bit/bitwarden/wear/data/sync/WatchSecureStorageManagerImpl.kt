package com.x8bit.bitwarden.wear.data.sync

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Denies plaintext access while locked and discards data if the secure screen lock is removed. */
class WatchSecureStorageManagerImpl(
    private val securityManager: WatchSecurityManager,
    private val keyManager: WatchSyncKeyManager,
    private val diskSource: WatchEnvelopeDiskSource,
    private val dispatcherManager: DispatcherManager,
) : WatchSecureStorageManager {
    private val mutex = Mutex()

    override suspend fun provision(): Result<ByteArray> = withContext(dispatcherManager.io) {
        mutex.withLock {
            if (!hasSecureLock()) return@withLock accessFailure()
            val existing = diskSource.read()
            existing.fold(
                onSuccess = { envelope ->
                    // Never silently replace a missing key while encrypted data still exists.
                    if (envelope != null) {
                        keyManager.getPublicKey()
                    } else {
                        keyManager.createPublicKey()
                    }
                },
                onFailure = { Result.failure(it) },
            )
        }
    }

    override suspend fun receive(envelope: ByteArray): Result<Unit> =
        withContext(dispatcherManager.io) {
            mutex.withLock {
                if (!hasSecureLock()) return@withLock accessFailure()
                if (keyManager.getPublicKey().isFailure) return@withLock accessFailure()
                diskSource.write(envelope)
            }
        }

    override suspend fun read(context: ByteArray): Result<ByteArray?> =
        withContext(dispatcherManager.io) {
            mutex.withLock {
                if (!hasSecureLock()) return@withLock accessFailure()
                if (securityManager.getStatus() != WatchSecurityStatus.UNLOCKED) {
                    return@withLock accessFailure()
                }
                diskSource.read().fold(
                    onSuccess = { envelope ->
                        if (envelope == null) return@fold Result.success(null)
                        keyManager.decrypt(envelope, context).mapCatching { plaintext ->
                            if (securityManager.getStatus() != WatchSecurityStatus.UNLOCKED) {
                                plaintext.fill(0)
                                throw IOException("Watch locked during read")
                            }
                            plaintext
                        }
                    },
                    onFailure = { Result.failure(it) },
                )
            }
        }

    override suspend fun clear(): Result<Unit> = withContext(dispatcherManager.io) {
        mutex.withLock { clearInternal() }
    }

    private fun hasSecureLock(): Boolean = when (securityManager.getStatus()) {
        WatchSecurityStatus.UNLOCKED, WatchSecurityStatus.LOCKED -> true
        WatchSecurityStatus.SCREEN_LOCK_REQUIRED -> {
            clearInternal()
            false
        }

        WatchSecurityStatus.UNVERIFIED -> false
    }

    private fun clearInternal(): Result<Unit> {
        val keyResult = keyManager.deleteKey()
        val diskResult = diskSource.delete()
        return keyResult.fold(onSuccess = { diskResult }, onFailure = { Result.failure(it) })
    }

    private fun <T> accessFailure(): Result<T> = Result.failure(IOException("Watch access denied"))
}
