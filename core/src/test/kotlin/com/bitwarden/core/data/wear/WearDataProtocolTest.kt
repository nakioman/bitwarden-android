package com.bitwarden.core.data.wear

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WearDataProtocolTest {
    private val header = WearSnapshotHeader(
        "22222222-2222-4222-8222-222222222222", 1, "a".repeat(64),
    )
    private val frame = WearSnapshotFrame(header, byteArrayOf(1, 2, 3))
    private val packet = WearSnapshotProtocol.encode(frame).getOrThrow()

    @Test
    fun `paths distinguish targets and receipts without raw node path segments`() {
        val path = WearDataProtocol.packetPath("watch/one")
        assertTrue(WearDataProtocol.isPacketPath(path, "watch/one"))
        assertFalse(WearDataProtocol.isPacketPath(path, "watch/two"))
        assertFalse(WearDataProtocol.isPacketPath(
            path.replace("/data/", "/probe/data/"), "watch/one",
        ))
        assertFalse(path.contains("watch/one"))
        assertNotEquals(path, WearDataProtocol.receiptPath("watch/one"))
        assertFalse(WearDataProtocol.isPacketPath("$path/", "watch/one"))
        assertFalse(WearDataProtocol.isPacketPath(null, "watch/one"))
    }

    @Test
    fun `receipt routes bind canonical production path to the owner`() {
        val path = WearDataProtocol.receiptPath("watch")
        assertTrue(WearDataProtocol.isReceiptPath(path, "watch"))
        assertFalse(WearDataProtocol.isReceiptPath(path, "other"))
        assertFalse(WearDataProtocol.isReceiptPath(path.replace("/data/", "/probe/data/"), "watch"))
        assertFalse(WearDataProtocol.isReceiptPath("$path/", "watch"))
        assertFalse(WearDataProtocol.isReceiptPath(null, "watch"))
        assertFalse(WearDataProtocol.isReceiptPath(path, ""))
        assertFalse(WearDataProtocol.isReceiptPath(path, "a".repeat(129)))
    }

    @Test
    fun `unsafe or unbounded node identifiers cannot become routes`() {
        listOf("", "two words", "line\n", "é", "a".repeat(129)).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                WearDataProtocol.packetPath(it)
            }
            assertFalse(WearDataProtocol.isPacketPath("/", it))
        }
    }

    @Test
    fun `receipt confirms only the exact packet and is deterministic`() {
        val receipt = WearDataProtocol.createReceipt(packet).getOrThrow()
        assertTrue(WearDataProtocol.matchesReceipt(receipt, packet))
        assertArrayEquals(receipt, WearDataProtocol.createReceipt(packet).getOrThrow())
        listOf(
            frame.copy(ciphertext = byteArrayOf(1, 2, 4)),
            frame.copy(header = header.copy(revision = 2)),
            frame.copy(header = header.copy(recipientKeyId = "b".repeat(64))),
            frame.copy(header = header.copy(
                enrollmentId = "33333333-3333-4333-8333-333333333333",
            )),
            frame.copy(header = header.copy(isRevocation = true), ciphertext = byteArrayOf()),
        ).forEach {
            assertFalse(WearDataProtocol.matchesReceipt(
                receipt, WearSnapshotProtocol.encode(it).getOrThrow(),
            ))
        }
    }

    @Test
    fun `unknown truncated oversized or corrupted receipts fail closed`() {
        val receipt = WearDataProtocol.createReceipt(packet).getOrThrow()
        listOf(
            byteArrayOf(), receipt.dropLast(1).toByteArray(), receipt + 0,
            receipt.copyOf().also { it[0] = 2 },
            receipt.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
        ).forEach { assertFalse(WearDataProtocol.matchesReceipt(it, packet)) }
        assertTrue(WearDataProtocol.createReceipt(byteArrayOf()).isFailure)
        assertFalse(WearDataProtocol.matchesReceipt(receipt, packet + 0))
    }

    @Test
    fun `empty revocation frames receive exact confirmations`() {
        val revoke = WearSnapshotProtocol.encode(
            WearSnapshotFrame(header.copy(isRevocation = true), byteArrayOf()),
        ).getOrThrow()
        val receipt = WearDataProtocol.createReceipt(revoke).getOrThrow()
        assertTrue(WearDataProtocol.matchesReceipt(receipt, revoke))
        assertFalse(WearDataProtocol.matchesReceipt(receipt, packet))
    }
}
