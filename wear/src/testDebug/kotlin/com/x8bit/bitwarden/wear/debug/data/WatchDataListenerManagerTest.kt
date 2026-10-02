package com.x8bit.bitwarden.wear.debug.data

import android.net.Uri
import com.bitwarden.core.data.wear.WearDataChannel
import com.bitwarden.core.data.wear.WearDataProtocol
import com.google.android.gms.wearable.DataItem
import com.x8bit.bitwarden.wear.data.sync.WatchDataReceiver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class WatchDataListenerManagerTest {
    private val transport = mockk<WatchDataTransport>()
    private val receiver = mockk<WatchDataReceiver>()
    private val manager = WatchDataListenerManagerImpl(transport, receiver)
    private val uri = mockk<Uri>()
    private val item = mockk<DataItem> {
        every { uri } returns this@WatchDataListenerManagerTest.uri
    }
    private val path = WearDataProtocol.packetPath(WearDataChannel.PRODUCTION, "watch")
    private val packet = byteArrayOf(1, 2, 3)
    private val receipt = ByteArray(WearDataProtocol.MAX_RECEIPT_BYTES)

    @BeforeEach
    fun setUp() {
        every { uri.scheme } returns "wear"
        every { uri.host } returns "phone"
        every { uri.path } returns path
        coEvery { transport.getLocalNodeId() } returns Result.success("watch")
        coEvery { transport.readPacket(item) } returns Result.success(packet)
        coEvery { receiver.receive("watch", "phone", path, packet) } returns Result.success(receipt)
        coEvery { transport.publishReceipt(WearDataChannel.PRODUCTION, "watch", receipt) } returns
            Result.success(Unit)
    }

    @Test
    fun `targeted packet is persisted before watch-owned receipt publication`() = runTest {
        assertTrue(manager.receive(item).isSuccess)
        coVerifyOrder {
            transport.getLocalNodeId()
            transport.readPacket(item)
            receiver.receive("watch", "phone", path, packet)
            transport.publishReceipt(WearDataChannel.PRODUCTION, "watch", receipt)
        }
    }

    @Test
    fun `other targets synthetic channels and noncanonical paths skip asset download`() = runTest {
        for (invalid in listOf(
            WearDataProtocol.packetPath(WearDataChannel.PRODUCTION, "other-watch"),
            WearDataProtocol.packetPath(WearDataChannel.SYNTHETIC, "watch"),
            "$path/extra", null,
        )) {
            every { uri.path } returns invalid
            assertTrue(manager.receive(item).isFailure)
        }
        coVerify(exactly = 0) { transport.readPacket(any()) }
        coVerify(exactly = 0) { receiver.receive(any(), any(), any(), any()) }
    }

    @Test
    fun `local missing and blank owners skip asset download`() = runTest {
        for (owner in listOf("watch", "", " ", null)) {
            every { uri.host } returns owner
            assertTrue(manager.receive(item).isFailure)
        }
        coVerify(exactly = 0) { transport.readPacket(any()) }
    }

    @Test
    fun `nonwear URI skips asset download`() = runTest {
        every { uri.scheme } returns "https"
        assertTrue(manager.receive(item).isFailure)
        coVerify(exactly = 0) { transport.readPacket(any()) }
    }

    @Test
    fun `unknown local identity skips asset download`() = runTest {
        coEvery { transport.getLocalNodeId() } returns Result.failure(IOException("unavailable"))
        assertTrue(manager.receive(item).isFailure)
        coVerify(exactly = 0) { transport.readPacket(any()) }
    }

    @Test
    fun `failed asset cannot reach persistence or acknowledgment`() = runTest {
        coEvery { transport.readPacket(item) } returns Result.failure(IOException("unavailable"))
        assertTrue(manager.receive(item).isFailure)
        coVerify(exactly = 0) { receiver.receive(any(), any(), any(), any()) }
        coVerify(exactly = 0) { transport.publishReceipt(any(), any(), any()) }
    }

    @Test
    fun `failed durable commit cannot publish receipt`() = runTest {
        coEvery { receiver.receive(any(), any(), any(), any()) } returns
            Result.failure(IOException("unavailable"))
        assertTrue(manager.receive(item).isFailure)
        coVerify(exactly = 0) { transport.publishReceipt(any(), any(), any()) }
    }

    @Test
    fun `receipt failure reports failure after committing so redelivery can retry`() = runTest {
        coEvery { transport.publishReceipt(any(), any(), any()) } returns
            Result.failure(IOException("unavailable"))
        assertTrue(manager.receive(item).isFailure)
        coVerify(exactly = 1) { receiver.receive("watch", "phone", path, packet) }
    }

    @Test
    fun `cancellation while persisting propagates without receipt`() {
        coEvery { receiver.receive(any(), any(), any(), any()) } throws CancellationException()
        assertThrows(CancellationException::class.java) {
            runTest { manager.receive(item) }
        }
        coVerify(exactly = 0) { transport.publishReceipt(any(), any(), any()) }
    }
}
