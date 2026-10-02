package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.wear.WearDataProtocol
import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotHeader
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.bitwarden.data.datasource.disk.base.FakeSharedPreferences
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class WearDeliveryEventIntegrationTest {
    private val key = byteArrayOf(1, 2, 3)
    private val users = MutableStateFlow<UserState?>(UserState("user", listOf(
        UserState.EMPTY_ACCOUNT.copy(userId = "user", isLoggedIn = true, isVaultUnlocked = false),
    )))
    private val auth = mockk<AuthRepository> { every { userStateFlow } returns users }
    private val consents = mockk<WearConsentDiskSource> {
        every { getConsents() } returns Result.success(listOf(WearConsent(
            "user", "watch", "Watch", true, Base64.getEncoder().encodeToString(key),
        )))
    }
    private val outbox = WearOutboxManagerImpl(
        WearOutboxDiskSourceImpl(FakeSharedPreferences(), Json), FakeDispatcherManager(),
    )
    private val transport = mockk<WearDataSource>()
    private val delivery = WearDeliveryManagerImpl(
        outbox, consents, auth, transport, FakeDispatcherManager(),
    )
    private val coordinator = mockk<WearSyncCoordinator> {
        coEvery { retry() } coAnswers { delivery.deliverPending() }
    }
    private val manager = WearDeliveryEventManagerImpl(outbox, coordinator, true)
    private val path = WearDataProtocol.receiptPath("watch")

    @Test
    fun `receipt hint clears exact persisted packet with phone vault locked`() = runTest {
        val packet = enqueue()
        coEvery { transport.getReceipt("watch") } returns
            Result.success(WearDataProtocol.createReceipt(packet).getOrThrow())
        assertTrue(manager.receiptChanged("wear", "watch", path).isSuccess)
        assertTrue(outbox.getPending().getOrThrow().isEmpty())
        coVerify(exactly = 0) { transport.publish(any(), any()) }
    }

    @Test
    fun `old receipt hint cannot clear a newer pending command`() = runTest {
        val previous = enqueue()
        val current = enqueue()
        coEvery { transport.getReceipt("watch") } returns
            Result.success(WearDataProtocol.createReceipt(previous).getOrThrow())
        coEvery { transport.publish(any(), any()) } returns Result.success(Unit)
        assertTrue(manager.receiptChanged("wear", "watch", path).isSuccess)
        assertArrayEquals(current, outbox.getPending().getOrThrow().single().packet)
        coVerify(exactly = 1) { transport.publish("watch", any()) }
    }

    @Test
    fun `reconnection delivers committed revocation without any logged-in account`() = runTest {
        enqueue()
        users.value = null
        val packet = enqueue(isRevocation = true)
        coEvery { transport.getReceipt("watch") } returns Result.success(null)
        coEvery { transport.publish(any(), any()) } returns Result.success(Unit)
        assertTrue(manager.capabilityChanged(WearDataProtocol.COMPANION_CAPABILITY, true).isSuccess)
        assertArrayEquals(packet, outbox.getPending().getOrThrow().single().packet)
        coVerify(exactly = 1) { transport.publish("watch", any()) }
    }

    private suspend fun enqueue(isRevocation: Boolean = false): ByteArray {
        val header = WearSnapshotHeader(
            "22222222-2222-4222-8222-222222222222",
            outbox.reserveRevision("watch").getOrThrow(),
            WearSnapshotProtocol.recipientKeyId(key),
            isRevocation,
        )
        val packet = WearSnapshotProtocol.encode(WearSnapshotFrame(
            header, if (isRevocation) byteArrayOf() else byteArrayOf(1, 2, 3),
        )).getOrThrow()
        outbox.enqueue("user", "watch", packet).getOrThrow()
        return packet
    }
}
