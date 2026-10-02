package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.wear.WearSnapshotProtocol
import java.security.MessageDigest
import java.util.Base64

internal const val MAX_OUTBOX_CHARACTERS = 8 * 1024 * 1024
internal const val MAX_OUTBOX_ID_LENGTH = 128
private const val MAX_COMPANIONS = 64
private const val DIGEST_LENGTH = 64

internal fun validateOutbox(records: List<WearOutboxRecord>) {
    require(records.size <= MAX_COMPANIONS)
    require(records.map { it.deviceId }.distinct().size == records.size)
    require(records.sumOf { it.pendingPacket?.length?.toLong() ?: 0 } <= MAX_OUTBOX_CHARACTERS)
    records.forEach { it.validate() }
}

internal fun validateDeviceId(deviceId: String) {
    require(deviceId.length in 1..MAX_OUTBOX_ID_LENGTH && deviceId.all { it in '!'..'~' })
}

internal fun packetDigest(packet: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(packet).joinToString("") { "%02x".format(it) }

private fun WearOutboxRecord.validate() {
    validateDeviceId(deviceId)
    require(reservedRevision > 0)
    val header = lastHeader
    if (header == null) {
        require(userId == null && lastDigest == null && pendingPacket == null)
        return
    }
    require(!userId.isNullOrBlank() && requireNotNull(userId).length <= MAX_OUTBOX_ID_LENGTH)
    require(WearSnapshotProtocol.context(header).isSuccess)
    require(header.revision <= reservedRevision)
    val digest = requireNotNull(lastDigest)
    require(digest.length == DIGEST_LENGTH && digest.all { it in '0'..'9' || it in 'a'..'f' })
    pendingPacket?.let { encoded ->
        require(encoded.length <= MAX_OUTBOX_CHARACTERS)
        val packet = Base64.getDecoder().decode(encoded)
        val frame = WearSnapshotProtocol.decode(packet).getOrThrow()
        require(frame.header == header && packetDigest(packet) == digest)
    }
}
