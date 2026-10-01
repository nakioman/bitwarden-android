package com.x8bit.bitwarden.wear.data.sync

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

private const val MAX_ENVELOPE_BYTES = 2 * 1024 * 1024 + 1024

/** Preserves the previous offline snapshot if a file write is interrupted. */
class WatchEnvelopeDiskSourceImpl(private val file: AtomicFile) : WatchEnvelopeDiskSource {
    override fun read(): Result<ByteArray?> = try {
        Result.success(file.openRead().use(::readBounded))
    } catch (error: FileNotFoundException) {
        if (file.baseFile.exists()) Result.failure(error) else Result.success(null)
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    } catch (error: SecurityException) {
        Result.failure(error)
    }

    override fun write(envelope: ByteArray): Result<Unit> = try {
        require(envelope.size <= MAX_ENVELOPE_BYTES)
        val stream = file.startWrite()
        try {
            stream.write(envelope)
            file.finishWrite(stream)
            read().fold(
                onSuccess = { saved ->
                    if (saved != null && saved.contentEquals(envelope)) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IOException("Companion snapshot not committed"))
                    }
                },
                onFailure = { Result.failure(it) },
            )
        } catch (error: IOException) {
            file.failWrite(stream)
            Result.failure(error)
        }
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    } catch (error: SecurityException) {
        Result.failure(error)
    }

    override fun delete(): Result<Unit> = try {
        file.delete()
        if (file.baseFile.exists()) {
            Result.failure(IOException("Companion snapshot not removed"))
        } else {
            Result.success(Unit)
        }
    } catch (error: SecurityException) {
        Result.failure(error)
    }
    private fun readBounded(input: FileInputStream): ByteArray =
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(buffer)
            while (count >= 0) {
                require(output.size() + count <= MAX_ENVELOPE_BYTES)
                output.write(buffer, 0, count)
                count = input.read(buffer)
            }
            output.toByteArray()
        }
}
