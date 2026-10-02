package com.x8bit.bitwarden.wear.debug.data

import com.bitwarden.core.data.wear.WearSnapshotProtocol
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class WatchPacketReaderTest {
    @Test
    fun `maximum frame is read completely`() {
        val packet = ByteArray(WearSnapshotProtocol.MAX_PACKET_BYTES) { (it % 127).toByte() }
        assertArrayEquals(packet, readBoundedPacket(ByteArrayInputStream(packet)))
    }

    @Test
    fun `oversized frame is rejected instead of truncated`() {
        val packet = ByteArray(WearSnapshotProtocol.MAX_PACKET_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedPacket(ByteArrayInputStream(packet))
        }
    }

    @Test
    fun `stream failure prevents returning a partial packet`() {
        val stream = object : InputStream() {
            override fun read(): Int = throw IOException("unavailable")
        }
        assertThrows(IOException::class.java) { readBoundedPacket(stream) }
    }
}
