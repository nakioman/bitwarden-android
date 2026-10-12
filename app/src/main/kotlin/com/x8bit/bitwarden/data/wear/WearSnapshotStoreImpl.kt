package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearDataProtocol
import com.bitwarden.core.data.wear.WearOtpEntry
import com.bitwarden.core.data.wear.WearOtpPayloadProtocol
import com.bitwarden.core.data.wear.WearPayloadCipher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

private const val TAG_SALT_BYTES = 32

/**
 * Serializes publication and removal so a removal is never overtaken by an in-flight write.
 *
 * @property dataSource Data Layer transport.
 * @property dispatcherManager Worker dispatcher for crypto and transport calls.
 */
class WearSnapshotStoreImpl(
    private val dataSource: WearDataSource,
    private val dispatcherManager: DispatcherManager,
) : WearSnapshotStore {
    private val mutex = Mutex()

    // Content tags only need to be stable within a process. A per-process salt keeps them from
    // being a reusable fingerprint of the vault contents.
    private val tagSalt = ByteArray(TAG_SALT_BYTES).also { SecureRandom().nextBytes(it) }

    override suspend fun publish(
        userId: String,
        entries: List<WearOtpEntry>,
        isStillAllowed: () -> Boolean,
    ): Result<Unit> = withContext(dispatcherManager.io) {
        mutex.withLock {
            val keys = dataSource.getWatchKeys().getOrElse { return@withLock it.asFailure() }
            val existing = dataSource.getSnapshots().getOrElse { return@withLock it.asFailure() }
            var failed = false
            existing
                .filter { snapshot -> keys.none { it.nodeId == snapshot.watchNodeId } }
                .forEach { if (dataSource.deleteSnapshot(it.watchNodeId).isFailure) failed = true }
            if (keys.isEmpty()) return@withLock result(failed)
            val owner = ownerTag(userId)
            val plaintext = WearOtpPayloadProtocol.encode(entries)
                .getOrElse { return@withLock it.asFailure() }
            try {
                for (key in keys) {
                    when (publishTo(key, owner, plaintext, existing, isStillAllowed)) {
                        PublishOutcome.PUBLISHED -> Unit
                        PublishOutcome.FAILED -> failed = true
                        PublishOutcome.NOT_ALLOWED -> return@withLock Result.success(Unit)
                    }
                }
            } finally {
                plaintext.fill(0)
            }
            result(failed)
        }
    }

    override suspend fun remove(userId: String): Result<Unit> =
        withContext(dispatcherManager.io) {
            mutex.withLock {
                val owner = ownerTag(userId)
                val snapshots = dataSource.getSnapshots()
                    .getOrElse { return@withLock it.asFailure() }
                var failed = false
                snapshots.filter { it.owner == owner }.forEach {
                    if (dataSource.deleteSnapshot(it.watchNodeId).isFailure) failed = true
                }
                result(failed)
            }
        }

    private suspend fun publishTo(
        key: WearWatchKey,
        owner: String,
        plaintext: ByteArray,
        existing: List<WearSnapshotInfo>,
        isStillAllowed: () -> Boolean,
    ): PublishOutcome {
        val keyId = WearDataProtocol.recipientKeyId(key.publicKey)
        val info = WearSnapshotInfo(
            watchNodeId = key.nodeId,
            owner = owner,
            recipientKeyId = keyId,
            contentTag = contentTag(keyId, owner, plaintext),
        )
        if (info in existing) return PublishOutcome.PUBLISHED
        val envelope = WearPayloadCipher
            .encrypt(key.publicKey, plaintext, WearDataProtocol.snapshotContext(keyId))
            .getOrElse { return PublishOutcome.FAILED }
        if (!isStillAllowed()) return PublishOutcome.NOT_ALLOWED
        return if (dataSource.putSnapshot(info, envelope).isSuccess) {
            PublishOutcome.PUBLISHED
        } else {
            PublishOutcome.FAILED
        }
    }

    private fun contentTag(keyId: String, owner: String, plaintext: ByteArray): String =
        MessageDigest.getInstance("SHA-256").run {
            update(tagSalt)
            update("$keyId:$owner:".toByteArray())
            update(plaintext)
            digest().joinToString("") { "%02x".format(it) }
        }
}

private enum class PublishOutcome { PUBLISHED, FAILED, NOT_ALLOWED }

private fun ownerTag(userId: String): String =
    WearDataProtocol.sha256Hex("bitwarden-wear-owner:$userId".toByteArray())

private fun result(failed: Boolean): Result<Unit> = if (failed) {
    Result.failure(IOException("Wear snapshot update incomplete"))
} else {
    Result.success(Unit)
}

private fun Throwable.asFailure(): Result<Unit> = Result.failure(this)
