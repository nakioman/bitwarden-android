package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearSnapshotHeader
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Base64

/** Serializes reservations, replacement and acknowledgments without caching ledger state. */
class WearOutboxManagerImpl(
    private val diskSource: WearOutboxDiskSource,
    private val dispatcherManager: DispatcherManager,
) : WearOutboxManager {
    private val mutex = Mutex()

    override suspend fun reserveRevision(deviceId: String): Result<Long> = serialized {
        validateDeviceId(deviceId)
        val records = load()
        val previous = records.singleOrNull { it.deviceId == deviceId }
        val reserved = previous?.reservedRevision ?: 0
        require(reserved < Long.MAX_VALUE)
        val next = previous?.copy(reservedRevision = reserved + 1)
            ?: WearOutboxRecord(deviceId, reserved + 1)
        replace(records, next)
        next.reservedRevision
    }

    override suspend fun enqueue(
        userId: String,
        deviceId: String,
        packet: ByteArray,
    ): Result<Unit> {
        if (packet.size > MAX_OUTBOX_CHARACTERS) return unavailable()
        val copy = packet.copyOf()
        return serialized {
            require(userId.isNotBlank() && userId.length <= MAX_OUTBOX_ID_LENGTH)
            val frame = WearSnapshotProtocol.decode(copy).getOrThrow()
            val records = load()
            val previous = records.single { it.deviceId == deviceId }
            val digest = packetDigest(copy)
            if (previous.userId == userId && previous.lastHeader == frame.header &&
                previous.lastDigest == digest
            ) {
                return@serialized Unit
            }
            require(previous.reservedRevision == frame.header.revision)
            require(previous.allows(userId, frame.header))
            replace(
                records,
                previous.copy(
                    userId = userId,
                    lastHeader = frame.header,
                    lastDigest = digest,
                    pendingPacket = Base64.getEncoder().encodeToString(copy),
                ),
            )
        }
    }

    override suspend fun getPending(): Result<List<WearPendingPacket>> = serialized {
        load().mapNotNull { record ->
            record.pendingPacket?.let {
                WearPendingPacket(
                    requireNotNull(record.userId), record.deviceId, Base64.getDecoder().decode(it),
                )
            }
        }
    }

    override suspend fun acknowledge(deviceId: String, packet: ByteArray): Result<Unit> {
        if (packet.size > MAX_OUTBOX_CHARACTERS) return unavailable()
        val digest = packetDigest(packet.copyOf())
        return serialized {
            val records = load()
            val record = records.single { it.deviceId == deviceId }
            require(record.lastDigest == digest)
            if (record.pendingPacket != null) {
                replace(records, record.copy(pendingPacket = null))
            }
        }
    }

    private fun replace(
        records: List<WearOutboxRecord>,
        replacement: WearOutboxRecord,
    ) {
        diskSource.write(records.filterNot { it.deviceId == replacement.deviceId } + replacement)
            .getOrElse { throw IOException("Wear outbox write failed") }
    }

    private fun load(): List<WearOutboxRecord> = diskSource.read()
        .getOrElse { throw IOException("Wear outbox read failed") }.also(::validateOutbox)

    private suspend fun <T> serialized(block: () -> T): Result<T> =
        withContext(dispatcherManager.io) {
            mutex.withLock {
                try {
                    Result.success(block())
                } catch (_: IOException) {
                    unavailable()
                } catch (_: IllegalArgumentException) {
                    unavailable()
                } catch (_: IllegalStateException) {
                    unavailable()
                } catch (_: NoSuchElementException) {
                    unavailable()
                }
            }
        }
}

private fun WearOutboxRecord.allows(user: String, next: WearSnapshotHeader): Boolean {
    val previous = lastHeader ?: return !next.isRevocation
    if (next.revision <= previous.revision) return false
    val sameEnrollment = next.enrollmentId == previous.enrollmentId &&
        next.recipientKeyId == previous.recipientKeyId && userId == user
    return when {
        next.isRevocation -> !previous.isRevocation && sameEnrollment
        previous.isRevocation -> {
            pendingPacket == null && next.enrollmentId != previous.enrollmentId &&
                next.recipientKeyId != previous.recipientKeyId
        }
        else -> sameEnrollment
    }
}

private fun <T> unavailable(): Result<T> = Result.failure(IOException("Wear outbox unavailable"))
