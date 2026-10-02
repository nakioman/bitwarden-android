package com.bitwarden.core.data.wear

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

private const val FORMAT_VERSION = 1
private const val NULL_STRING_LENGTH = -1
private const val HEADER_BYTES = 1 + Int.SIZE_BYTES
private const val FIELD_COUNT = 5
private const val MIN_ENTRY_BYTES = FIELD_COUNT * Int.SIZE_BYTES + 2

/**
 * Versioned, bounded format shared by phone and watch. Encoded bytes contain plaintext secrets:
 * callers must encrypt immediately, never persist or log them, and erase them after use.
 * No UI state or Android parcel serializers are generated for these sensitive models.
 */
object WearOtpPayloadProtocol {
    /** Encodes the complete list without normalizing TOTP URIs or dropping entries. */
    fun encode(entries: List<WearOtpEntry>): Result<ByteArray> = payloadResult {
        require(entries.size <= (WearPayloadCipher.MAX_PLAINTEXT_BYTES - HEADER_BYTES) /
            MIN_ENTRY_BYTES)
        val ids = HashSet<String>()
        val output = SensitiveOutput()
        try {
            val writer = DataOutputStream(output)
            writer.writeByte(FORMAT_VERSION)
            writer.writeInt(entries.size)
            entries.forEach {
                require(it.id.isNotBlank() && it.secret.isNotBlank() && ids.add(it.id))
                writeText(writer, output, it.id)
                writeText(writer, output, it.name)
                writeText(writer, output, it.username)
                writeText(writer, output, it.secret)
                writeText(writer, output, it.iconUrl)
            }
            output.toByteArray()
        } finally {
            output.erase()
        }
    }

    /** Rejects malformed, ambiguous or duplicate entries; never returns a partial list. */
    fun decode(plaintext: ByteArray): Result<List<WearOtpEntry>> = payloadResult {
        require(plaintext.size <= WearPayloadCipher.MAX_PLAINTEXT_BYTES)
        DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            require(input.readUnsignedByte() == FORMAT_VERSION)
            val count = input.readInt()
            require(count >= 0 && count <= input.available() / MIN_ENTRY_BYTES)
            val ids = HashSet<String>()
            val entries = List(count) {
                val id = requireNotNull(readText(input))
                val name = requireNotNull(readText(input))
                val username = readText(input)
                val secret = requireNotNull(readText(input))
                val iconUrl = readText(input)
                require(id.isNotBlank() && secret.isNotBlank() && ids.add(id))
                WearOtpEntry(id, name, username, secret, iconUrl)
            }
            require(input.available() == 0)
            entries
        }
    }

    private fun writeText(writer: DataOutputStream, output: SensitiveOutput, value: String?) {
        val remaining = WearPayloadCipher.MAX_PLAINTEXT_BYTES - output.size() - Int.SIZE_BYTES
        require(remaining >= 0)
        if (value == null) {
            writer.writeInt(NULL_STRING_LENGTH)
            return
        }
        require(value.length <= remaining)
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        try {
            require(bytes.size <= remaining)
            writer.writeInt(bytes.size)
            writer.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readText(input: DataInputStream): String? {
        val size = input.readInt()
        if (size == NULL_STRING_LENGTH) return null
        require(size >= 0 && size <= input.available())
        val bytes = ByteArray(size)
        return try {
            input.readFully(bytes)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }
}

private class SensitiveOutput : ByteArrayOutputStream() {
    override fun write(value: Int) {
        reserve(count + 1)
        super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        reserve(count + length)
        super.write(bytes, offset, length)
    }

    fun erase() {
        buf.fill(0)
    }

    private fun reserve(required: Int) {
        require(required <= WearPayloadCipher.MAX_PLAINTEXT_BYTES)
        if (required <= buf.size) return
        // Erase old buffers when growing rather than leaving secret bytes until GC.
        val previous = buf
        buf = previous.copyOf(
            maxOf(required, previous.size * 2).coerceAtMost(WearPayloadCipher.MAX_PLAINTEXT_BYTES),
        )
        previous.fill(0)
    }
}

private inline fun <T> payloadResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (_: IOException) {
    payloadFailure()
} catch (_: IllegalArgumentException) {
    payloadFailure()
}

private fun <T> payloadFailure(): Result<T> =
    Result.failure(IOException("Wear OTP payload rejected"))
