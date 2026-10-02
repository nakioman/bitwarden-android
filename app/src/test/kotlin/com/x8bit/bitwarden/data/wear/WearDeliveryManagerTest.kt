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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Base64

class WearDeliveryManagerTest {
    private val publicKey = byteArrayOf(1, 2, 3)
    private val keyId = WearSnapshotProtocol.recipientKeyId(publicKey)
    private val account = UserState.EMPTY_ACCOUNT.copy(
        userId = "user", isLoggedIn = true, isVaultUnlocked = false,
    )
    private val users = MutableStateFlow<UserState?>(UserState("user", listOf(account)))
    private val auth = mockk<AuthRepository> { every { userStateFlow } returns users }
    private val consents = mockk<WearConsentDiskSource>()
    private val consent = WearConsent(
        "user", "watch", "Watch", true, Base64.getEncoder().encodeToString(publicKey),
    )
    private val transport = mockk<WearDataSource>()
    private val outbox = WearOutboxManagerImpl(
        WearOutboxDiskSourceImpl(FakeSharedPreferences(), Json), FakeDispatcherManager(),
    )
    private val manager = WearDeliveryManagerImpl(
        outbox, consents, auth, transport, FakeDispatcherManager(),
    )

    @BeforeEach
    fun setUp() {
        every { consents.getConsents() } returns Result.success(listOf(consent))
        coEvery { transport.getReceipt(any()) } returns Result.success(null)
        coEvery { transport.publish(any(), any()) } returns Result.success(Unit)
    }

    @Test
    fun `locked vault relays ciphertext and retains its pending packet`() = runTest {
        val packet = enqueue()
        assertTrue(manager.deliverPending().isSuccess)
        coVerify(exactly = 1) { transport.publish("watch", any()) }
        assertArrayEquals(packet, outbox.getPending().getOrThrow().single().packet)
    }

    @Test
    fun `exact receipt clears its packet without redundant publication`() = runTest {
        val packet = enqueue()
        coEvery { transport.getReceipt("watch") } returns Result.success(
            WearDataProtocol.createReceipt(packet).getOrThrow(),
        )
        assertTrue(manager.deliverPending().isSuccess)
        assertTrue(outbox.getPending().getOrThrow().isEmpty())
        coVerify(exactly = 0) { transport.publish(any(), any()) }
    }

    @Test
    fun `old altered or malformed receipts cannot clear a newer command`() = runTest {
        val older = enqueue()
        val current = enqueue()
        for (receipt in listOf(
            WearDataProtocol.createReceipt(older).getOrThrow(), byteArrayOf(),
            WearDataProtocol.createReceipt(current).getOrThrow().also { it[0] = 2 },
        )) {
            coEvery { transport.getReceipt("watch") } returns Result.success(receipt)
            assertTrue(manager.deliverPending().isSuccess)
            assertArrayEquals(current, outbox.getPending().getOrThrow().single().packet)
        }
        coVerify(exactly = 3) { transport.publish("watch", any()) }
    }

    @Test
    fun `revoked consent and changed key prevent snapshots from being sent or acknowledged`() =
        runTest {
            val packet = enqueue()
            coEvery { transport.getReceipt("watch") } returns Result.success(
                WearDataProtocol.createReceipt(packet).getOrThrow(),
            )
            for (decision in listOf(
                consent.copy(isAuthorized = false), consent.copy(publicKey = null),
                consent.copy(publicKey = Base64.getEncoder().encodeToString(byteArrayOf(4))),
            )) {
                every { consents.getConsents() } returns Result.success(listOf(decision))
                assertTrue(manager.deliverPending().isSuccess)
                assertArrayEquals(packet, outbox.getPending().getOrThrow().single().packet)
            }
            coVerify(exactly = 0) { transport.getReceipt(any()) }
            coVerify(exactly = 0) { transport.publish(any(), any()) }
        }

    @Test
    fun `missing inactive or logged out account retains snapshots without transferring them`() =
        runTest {
            val packet = enqueue()
            for (user in listOf(
                null, UserState("other", listOf(account.copy(userId = "other"))),
                UserState("user", listOf(account.copy(isLoggedIn = false))),
            )) {
                users.value = user
                assertTrue(manager.deliverPending().isSuccess)
                assertArrayEquals(packet, outbox.getPending().getOrThrow().single().packet)
            }
            coVerify(exactly = 0) { transport.getReceipt(any()) }
            coVerify(exactly = 0) { transport.publish(any(), any()) }
        }

    @Test
    fun `revocation still publishes and can be acknowledged after logout and lost consent`() =
        runTest {
            enqueue()
            val revoke = enqueue(revocation = true)
            users.value = null
            every { consents.getConsents() } returns Result.failure(IOException())
            assertTrue(manager.deliverPending().isSuccess)
            coVerify(exactly = 1) { transport.publish("watch", any()) }
            assertArrayEquals(revoke, outbox.getPending().getOrThrow().single().packet)
            coEvery { transport.getReceipt("watch") } returns Result.success(
                WearDataProtocol.createReceipt(revoke).getOrThrow(),
            )
            assertTrue(manager.deliverPending().isSuccess)
            assertTrue(outbox.getPending().getOrThrow().isEmpty())
        }

    @Test
    fun `consent change while receipt lookup waits prevents publication and acknowledgment`() =
        runTest {
            val packet = enqueue()
            coEvery { transport.getReceipt("watch") } coAnswers {
                every { consents.getConsents() } returns Result.success(listOf(
                    consent.copy(isAuthorized = false),
                ))
                Result.success(WearDataProtocol.createReceipt(packet).getOrThrow())
            }
            assertTrue(manager.deliverPending().isSuccess)
            coVerify(exactly = 0) { transport.publish(any(), any()) }
            assertArrayEquals(packet, outbox.getPending().getOrThrow().single().packet)
        }

    @Test
    fun `new revocation during receipt lookup prevents stale ciphertext publication`() = runTest {
        enqueue()
        var revocation: ByteArray? = null
        coEvery { transport.getReceipt("watch") } coAnswers {
            revocation = enqueue(revocation = true)
            Result.success(null)
        }
        assertTrue(manager.deliverPending().isSuccess)
        coVerify(exactly = 0) { transport.publish(any(), any()) }
        assertArrayEquals(revocation, outbox.getPending().getOrThrow().single().packet)
    }

    @Test
    fun `late old receipt cannot erase a revocation queued while waiting`() = runTest {
        val old = enqueue()
        var revocation: ByteArray? = null
        coEvery { transport.getReceipt("watch") } coAnswers {
            revocation = enqueue(revocation = true)
            Result.success(WearDataProtocol.createReceipt(old).getOrThrow())
        }
        assertTrue(manager.deliverPending().isFailure)
        assertArrayEquals(revocation, outbox.getPending().getOrThrow().single().packet)
    }

    @Test
    fun `one failed watch does not discard or block delivery to another watch`() = runTest {
        enqueue()
        enqueue("second-watch")
        every { consents.getConsents() } returns Result.success(listOf(
            consent, consent.copy(deviceId = "second-watch"),
        ))
        coEvery { transport.publish("watch", any()) } returns Result.failure(IOException())
        assertTrue(manager.deliverPending().isFailure)
        coVerify(exactly = 1) { transport.publish("second-watch", any()) }
        assertTrue(outbox.getPending().getOrThrow().size == 2)
    }

    @Test
    fun `consent and transport failures preserve ciphertext for retry`() = runTest {
        val packet = enqueue()
        every { consents.getConsents() } returns Result.failure(IOException())
        assertTrue(manager.deliverPending().isFailure)
        every { consents.getConsents() } returns Result.success(listOf(consent))
        coEvery { transport.getReceipt("watch") } returns Result.failure(IOException())
        assertTrue(manager.deliverPending().isFailure)
        assertArrayEquals(packet, outbox.getPending().getOrThrow().single().packet)
    }

    @Test
    fun `cancellation propagates and cannot acknowledge a queued packet`() = runTest {
        val packet = enqueue()
        coEvery { transport.getReceipt("watch") } throws CancellationException()
        var canceled = false
        try {
            manager.deliverPending()
        } catch (_: CancellationException) {
            canceled = true
        }
        assertTrue(canceled)
        assertArrayEquals(packet, outbox.getPending().getOrThrow().single().packet)
    }

    private suspend fun enqueue(
        deviceId: String = "watch",
        revocation: Boolean = false,
    ): ByteArray {
        val header = WearSnapshotHeader(
            "22222222-2222-4222-8222-222222222222", outbox.reserveRevision(deviceId).getOrThrow(),
            keyId, revocation,
        )
        val packet = WearSnapshotProtocol.encode(WearSnapshotFrame(
            header, if (revocation) byteArrayOf() else byteArrayOf(1),
        )).getOrThrow()
        outbox.enqueue("user", deviceId, packet).getOrThrow()
        return packet
    }
}
