package com.bitwarden.core.data.wear

import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

private const val SNAPSHOT_VERSION = 1
private const val SNAPSHOT_OPERATION = 1
private const val REVOCATION_OPERATION = 2
private const val MAX_CIPHERTEXT_BYTES = 2 * 1024 * 1024 + 548
private const val MAX_FRAME_BYTES = MAX_CIPHERTEXT_BYTES + 128
private const val KEY_ID_LENGTH = 64

/** Non-secret routing information, authenticated by the payload cipher as associated data. */
@Serializable
data class WearSnapshotHeader(
    val enrollmentId: String,
    val revision: Long,
    val recipientKeyId: String,
    val isRevocation: Boolean = false,
)

/** A snapshot contains ciphertext only. A revocation has no payload. */
data class WearSnapshotFrame(val header: WearSnapshotHeader, val ciphertext: ByteArray)

/** Bounded wire format; callers must separately authenticate the source and enforce revisions. */
object WearSnapshotProtocol {
    /** Identifies a recipient without transmitting its key in every snapshot. */
    fun recipientKeyId(publicKey: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(publicKey).joinToString("") { "%02x".format(it) }

    /** Canonical associated data binding the recipient, enrollment, operation and revision. */
    fun context(header: WearSnapshotHeader): Result<ByteArray> = formatResult {
        validateHeader(header)
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { writeHeader(it, header) }
            output.toByteArray()
        }
    }

    /** Encodes an encrypted snapshot or an empty revocation. */
    fun encode(frame: WearSnapshotFrame): Result<ByteArray> = formatResult {
        validateHeader(frame.header)
        require(frame.ciphertext.size <= MAX_CIPHERTEXT_BYTES)
        require(frame.header.isRevocation == frame.ciphertext.isEmpty())
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use {
                writeHeader(it, frame.header)
                it.writeInt(frame.ciphertext.size)
                it.write(frame.ciphertext)
            }
            output.toByteArray()
        }
    }

    /** Rejects unsupported versions, invalid identifiers, lengths and trailing bytes. */
    fun decode(bytes: ByteArray): Result<WearSnapshotFrame> = formatResult {
        require(bytes.size <= MAX_FRAME_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readUnsignedByte() == SNAPSHOT_VERSION)
            val operation = input.readUnsignedByte()
            require(operation == SNAPSHOT_OPERATION || operation == REVOCATION_OPERATION)
            val header = WearSnapshotHeader(
                enrollmentId = input.readUTF(),
                revision = input.readLong(),
                recipientKeyId = input.readUTF(),
                isRevocation = operation == REVOCATION_OPERATION,
            )
            validateHeader(header)
            val length = input.readInt()
            require(length in 0..MAX_CIPHERTEXT_BYTES && length == input.available())
            require(header.isRevocation == (length == 0))
            WearSnapshotFrame(header, ByteArray(length).also(input::readFully))
        }
    }

    private fun writeHeader(output: DataOutputStream, header: WearSnapshotHeader) {
        output.writeByte(SNAPSHOT_VERSION)
        output.writeByte(if (header.isRevocation) REVOCATION_OPERATION else SNAPSHOT_OPERATION)
        output.writeUTF(header.enrollmentId)
        output.writeLong(header.revision)
        output.writeUTF(header.recipientKeyId)
    }

    private fun validateHeader(header: WearSnapshotHeader) {
        require(UUID.fromString(header.enrollmentId).toString() == header.enrollmentId)
        require(header.revision > 0)
        require(header.recipientKeyId.length == KEY_ID_LENGTH)
        require(header.recipientKeyId.all { it in '0'..'9' || it in 'a'..'f' })
    }

    private inline fun <T> formatResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    }
}
