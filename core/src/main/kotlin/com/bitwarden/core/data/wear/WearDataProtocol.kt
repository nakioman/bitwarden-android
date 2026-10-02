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

private const val ROUTE_PREFIX = "/bitwarden/wear/data/v1"

/** Targeted DataItem paths and bounded receipts; callers must authenticate the DataItem owner. */
object WearDataProtocol {
    /** Capability declared by the companion app; reconnect hints never grant consent. */
    const val COMPANION_CAPABILITY = "bitwarden_wear_companion_v1"

    /** Asset field containing an encrypted snapshot frame or a revocation. */
    const val PACKET_ASSET = "packet"
    /** A new retry identifier makes unchanged packets generate a new delivery event. */
    const val ATTEMPT = "attempt"
    /** Bounds the fixed-size acknowledgment before parsing it. */
    const val MAX_RECEIPT_BYTES: Int = RECEIPT_BYTES

    /** Identifies a target without putting raw node identifiers into URI paths. */
    fun packetPath(nodeId: String): String =
        "$ROUTE_PREFIX/packets/${encodeNode(nodeId)}"

    /** Receipt owned by the watch that persisted the packet. */
    fun receiptPath(nodeId: String): String =
        "$ROUTE_PREFIX/receipts/${encodeNode(nodeId)}"

    /** Rejects unrelated, malformed or noncanonical paths before downloading an asset. */
    fun isPacketPath(path: String?, nodeId: String): Boolean = try {
        path == packetPath(nodeId)
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Rejects receipts whose canonical route does not match their DataItem owner. */
    fun isReceiptPath(path: String?, ownerNodeId: String): Boolean = try {
        path == receiptPath(ownerNodeId)
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
