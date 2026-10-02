package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.wear.WearDataChannel
import com.bitwarden.core.data.wear.WearDataProtocol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class WearDeliveryEventManagerTest {
    private val outbox = mockk<WearOutboxManager>()
    private val coordinator = mockk<WearSyncCoordinator> {
        coEvery { retry() } returns Result.success(Unit)
    }
    private val manager = WearDeliveryEventManagerImpl(outbox, coordinator, true)
    private val path = WearDataProtocol.receiptPath(WearDataChannel.PRODUCTION, "watch")
    private val record = WearOutboxRecord("watch", 1, userId = "user", pendingPacket = "encrypted")

    @Test
    fun `known pending receipt only requests independently verified retry`() = runTest {
        coEvery { outbox.getRecord("watch") } returns Result.success(record)
        assertTrue(manager.receiptChanged("wear", "watch", path).isSuccess)
        coVerify(exactly = 1) { coordinator.retry() }
        coVerify(exactly = 0) { outbox.acknowledge(any(), any()) }
        coVerify(exactly = 0) { coordinator.refresh(any()) }
    }

    @Test
    fun `foreign owner synthetic routes and malformed metadata cannot trigger retry`() = runTest {
        for ((scheme, owner, route) in listOf(
            Triple("https", "watch", path),
            Triple("wear", "other-watch", path),
            Triple("wear", null, path),
            Triple("wear", "", path),
            Triple("wear", "watch", "$path/extra"),
            Triple("wear", "watch", null),
            Triple(
                "wear", "watch", WearDataProtocol.receiptPath(WearDataChannel.SYNTHETIC, "watch"),
            ),
        )) {
            assertTrue(manager.receiptChanged(scheme, owner, route).isSuccess)
        }
        coVerify(exactly = 0) { outbox.getRecord(any()) }
        coVerify(exactly = 0) { coordinator.retry() }
    }

    @Test
    fun `unknown or already acknowledged owners do not trigger delivery`() = runTest {
        for (saved in listOf(null, record.copy(pendingPacket = null))) {
            coEvery { outbox.getRecord("watch") } returns Result.success(saved)
            assertTrue(manager.receiptChanged("wear", "watch", path).isSuccess)
        }
        coVerify(exactly = 0) { coordinator.retry() }
    }

    @Test
    fun `unreadable outbox rejects hint without acknowledgment`() = runTest {
        coEvery { outbox.getRecord("watch") } returns Result.failure(IOException())
        assertTrue(manager.receiptChanged("wear", "watch", path).isFailure)
        coVerify(exactly = 0) { coordinator.retry() }
        coVerify(exactly = 0) { outbox.acknowledge(any(), any()) }
    }

    @Test
    fun `companion reachability retries existing commands without granting permission`() = runTest {
        assertTrue(manager.capabilityChanged(WearDataProtocol.COMPANION_CAPABILITY, true).isSuccess)
        coVerify(exactly = 1) { coordinator.retry() }
        coVerify(exactly = 0) { coordinator.refresh(any()) }
    }

    @Test
    fun `unrelated capabilities and disconnect notifications are ignored`() = runTest {
        assertTrue(manager.capabilityChanged("other", true).isSuccess)
        assertTrue(manager.capabilityChanged(
            WearDataProtocol.COMPANION_CAPABILITY, false,
        ).isSuccess)
        coVerify(exactly = 0) { coordinator.retry() }
    }

    @Test
    fun `disabled variants do not read outbox or retry`() = runTest {
        val disabled = WearDeliveryEventManagerImpl(outbox, coordinator, false)
        assertTrue(disabled.receiptChanged("wear", "watch", path).isSuccess)
        assertTrue(disabled.capabilityChanged(
            WearDataProtocol.COMPANION_CAPABILITY, true,
        ).isSuccess)
        coVerify(exactly = 0) { outbox.getRecord(any()) }
        coVerify(exactly = 0) { coordinator.retry() }
    }

    @Test
    fun `retry failure leaves pending commands unchanged`() = runTest {
        coEvery { coordinator.retry() } returns Result.failure(IOException())
        assertTrue(manager.capabilityChanged(WearDataProtocol.COMPANION_CAPABILITY, true).isFailure)
        coVerify(exactly = 0) { outbox.acknowledge(any(), any()) }
    }

    @Test
    fun `cancellation propagates from retry`() {
        coEvery { coordinator.retry() } throws CancellationException()
        assertThrows(CancellationException::class.java) {
            runTest { manager.capabilityChanged(WearDataProtocol.COMPANION_CAPABILITY, true) }
        }
    }
}
