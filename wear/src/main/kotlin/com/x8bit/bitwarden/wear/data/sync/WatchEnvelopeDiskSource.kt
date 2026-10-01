package com.x8bit.bitwarden.wear.data.sync

/** Stores ciphertext only, outside Android backup locations. */
interface WatchEnvelopeDiskSource {
    /** Reads a bounded envelope or returns null when no snapshot exists. */
    fun read(): Result<ByteArray?>

    /** Atomically replaces a ciphertext envelope, preserving the previous file on failure. */
    fun write(envelope: ByteArray): Result<Unit>

    /** Removes the cached envelope. */
    fun delete(): Result<Unit>
}
