package com.x8bit.bitwarden.wear.data.sync

import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

private const val RECORD_VERSION = 1
private const val MAX_SOURCE_LENGTH = 128

/** Atomically persisted source binding and last accepted command, including revocations. */
internal data class WatchSnapshotRecord(val sourceNodeId: String, val frame: WearSnapshotFrame) {
    fun encode(): Result<ByteArray> = recordResult {
        validateSource(sourceNodeId)
        val encodedFrame = WearSnapshotProtocol.encode(frame).getOrThrow()
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use {
                it.writeInt(RECORD_VERSION)
                it.writeUTF(sourceNodeId)
                it.writeInt(encodedFrame.size)
                it.write(encodedFrame)
            }
            output.toByteArray()
        }
    }

    fun matches(packet: ByteArray): Boolean =
        WearSnapshotProtocol.encode(frame).getOrNull()?.contentEquals(packet) == true

    fun allows(incoming: WearSnapshotFrame): Boolean {
        val previous = frame.header
        val next = incoming.header
        if (next.revision <= previous.revision) return false
        if (next.isRevocation) {
            return next.enrollmentId == previous.enrollmentId &&
                next.recipientKeyId == previous.recipientKeyId
        }
        return if (previous.isRevocation) {
            next.enrollmentId != previous.enrollmentId
        } else {
            next.enrollmentId == previous.enrollmentId &&
                next.recipientKeyId == previous.recipientKeyId
        }
    }

    companion object {
        fun decode(bytes: ByteArray): Result<WatchSnapshotRecord> = recordResult {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == RECORD_VERSION)
                val source = input.readUTF().also(::validateSource)
                val length = input.readInt()
                require(length > 0 && length == input.available())
                val frame = WearSnapshotProtocol.decode(
                    ByteArray(length).also(input::readFully),
                ).getOrThrow()
                WatchSnapshotRecord(source, frame)
            }
        }
    }
}

private fun validateSource(source: String) {
    require(source.length in 1..MAX_SOURCE_LENGTH && source.all { it in '!'..'~' })
}

private inline fun <T> recordResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: IOException) {
    Result.failure(error)
} catch (error: IllegalArgumentException) {
    Result.failure(error)
}
