package com.bitwarden.core.data.wear

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

private const val RECEIPT_VERSION = 1
private const val DIGEST_BYTES = 32
private const val RECEIPT_BYTES = 1 + Long.SIZE_BYTES + DIGEST_BYTES
private const val MAX_NODE_LENGTH = 128

/**
 * Separate routes prevent synthetic tests from reaching production state.
 *
 * @property prefix Versioned route namespace.
 */
enum class WearDataChannel(val prefix: String) {
    /** Reserved for the authorized companion pipeline. */
    PRODUCTION("/bitwarden/wear/data/v1"),
    /** Isolated debug tests only. */
    SYNTHETIC("/bitwarden/wear/probe/data/v1"),
}

/** Targeted DataItem paths and bounded receipts; callers must authenticate the DataItem owner. */
object WearDataProtocol {
    /** Asset field containing an encrypted snapshot frame or a revocation. */
    const val PACKET_ASSET = "packet"
    /** A new retry identifier makes unchanged packets generate a new delivery event. */
    const val ATTEMPT = "attempt"
    /** Bounds the fixed-size acknowledgment before parsing it. */
    const val MAX_RECEIPT_BYTES: Int = RECEIPT_BYTES

    /** Identifies a target without putting raw node identifiers into URI paths. */
    fun packetPath(channel: WearDataChannel, nodeId: String): String =
        "${channel.prefix}/packets/${encodeNode(nodeId)}"

    /** Receipt owned by the watch that persisted the packet. */
    fun receiptPath(channel: WearDataChannel, nodeId: String): String =
        "${channel.prefix}/receipts/${encodeNode(nodeId)}"

    /** Rejects unrelated, malformed or noncanonical paths before downloading an asset. */
    fun isPacketPath(channel: WearDataChannel, path: String?, nodeId: String): Boolean = try {
        path == packetPath(channel, nodeId)
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Must be called only after the receiver commits the exact packet. Contains no vault data. */
    fun createReceipt(packet: ByteArray): Result<ByteArray> = formatResult {
        val frame = WearSnapshotProtocol.decode(packet).getOrThrow()
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use {
                it.writeByte(RECEIPT_VERSION)
                it.writeLong(frame.header.revision)
                it.write(digest(packet))
            }
            output.toByteArray()
        }
    }

    /** Checks revision and SHA-256 of the complete frame, including ciphertext and routing. */
    fun matchesReceipt(receipt: ByteArray, packet: ByteArray): Boolean = formatResult {
        require(receipt.size == RECEIPT_BYTES)
        val frame = WearSnapshotProtocol.decode(packet).getOrThrow()
        DataInputStream(ByteArrayInputStream(receipt)).use {
            require(it.readUnsignedByte() == RECEIPT_VERSION)
            require(it.readLong() == frame.header.revision)
            val receivedDigest = ByteArray(DIGEST_BYTES).also(it::readFully)
            MessageDigest.isEqual(receivedDigest, digest(packet))
        }
    }.getOrDefault(false)

    private fun encodeNode(nodeId: String): String {
        require(nodeId.length in 1..MAX_NODE_LENGTH && nodeId.all { it in '!'..'~' })
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nodeId.toByteArray())
    }

    private fun digest(packet: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(packet)
}

private inline fun <T> formatResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: IOException) {
    Result.failure(error)
} catch (error: IllegalArgumentException) {
    Result.failure(error)
}
