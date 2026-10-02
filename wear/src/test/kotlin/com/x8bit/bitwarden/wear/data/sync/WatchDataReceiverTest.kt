package com.x8bit.bitwarden.wear.data.sync

import com.bitwarden.core.data.wear.WearDataProtocol
import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotHeader
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class WatchDataReceiverTest {
    private val manager = mockk<WatchSnapshotManager>()
    private val receiver = WatchDataReceiverImpl(manager)
    private val header = WearSnapshotHeader(
        "22222222-2222-4222-8222-222222222222", 1, "a".repeat(64),
    )
    private val packet = WearSnapshotProtocol.encode(WearSnapshotFrame(header, byteArrayOf(1)))
        .getOrThrow()
    private val path = WearDataProtocol.packetPath("watch")

    @Test
    fun `receipt is created only after successful durable commit without a plaintext read`() =
        runTest {
            var persisted = false
            coEvery { manager.receive("phone", any()) } coAnswers {
                persisted = true
                Result.success(Unit)
            }
            val receipt = receiver.receive("watch", "phone", path, packet).getOrThrow()
            assertTrue(persisted)
            assertTrue(WearDataProtocol.matchesReceipt(receipt, packet))
            coVerify(exactly = 0) { manager.read() }
        }

    @Test
    fun `failed persistence does not produce an acknowledgment`() = runTest {
        coEvery { manager.receive("phone", any()) } returns Result.failure(IOException())
        assertTrue(receiver.receive("watch", "phone", path, packet).isFailure)
    }

    @Test
    fun `synthetic or unrelated target paths never reach production storage`() = runTest {
        listOf(
            "/bitwarden/wear/probe/data/v1/packets/d2F0Y2g",
            WearDataProtocol.packetPath("other-watch"),
            "$path/", null,
        ).forEach {
            assertTrue(receiver.receive("watch", "phone", it, packet).isFailure)
        }
        coVerify(exactly = 0) { manager.receive(any(), any()) }
    }

    @Test
    fun `self owned empty source and oversized packets are rejected before copying or storage`() =
        runTest {
            assertTrue(receiver.receive("watch", "watch", path, packet).isFailure)
            assertTrue(receiver.receive("watch", " ", path, packet).isFailure)
            assertTrue(receiver.receive("watch", "phone", path,
                ByteArray(WearSnapshotProtocol.MAX_PACKET_BYTES + 1),).isFailure)
            coVerify(exactly = 0) { manager.receive(any(), any()) }
        }

    @Test
    fun `revocations receive exact confirmation after tombstone persistence`() = runTest {
        val revoke = WearSnapshotProtocol.encode(
            WearSnapshotFrame(header.copy(isRevocation = true), byteArrayOf()),
        ).getOrThrow()
        coEvery { manager.receive("phone", any()) } returns Result.success(Unit)
        val receipt = receiver.receive("watch", "phone", path, revoke).getOrThrow()
        assertTrue(WearDataProtocol.matchesReceipt(receipt, revoke))
    }

    @Test
    fun `cancellation cannot turn an unfinished receive into an acknowledgment`() = runTest {
        coEvery { manager.receive("phone", any()) } throws CancellationException()
        var canceled = false
        try {
            receiver.receive("watch", "phone", path, packet)
        } catch (_: CancellationException) {
            canceled = true
        }
        assertTrue(canceled)
    }
}
