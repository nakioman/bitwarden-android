package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class WearSyncCoordinatorTest {
    private val consentSource = mockk<WearConsentDiskSource>()
    private val preparer = mockk<WearSnapshotPreparer>()
    private val outbox = mockk<WearOutboxManager>()
    private val delivery = mockk<WearDeliveryManager>()
    private val coordinator = WearSyncCoordinatorImpl(
        consentSource, preparer, outbox, delivery, FakeDispatcherManager(),
    )
    private val consent = WearConsent("user", "watch", "Watch", true, "pinned-key")
    private var decisions = listOf(consent)

    @BeforeEach
    fun setUp() {
        every { consentSource.getConsents() } answers { Result.success(decisions) }
        every { consentSource.compareAndSetConsents(any(), any()) } answers {
            decisions = secondArg()
            Result.success(Unit)
        }
        coEvery { outbox.getRecord(any()) } answers {
            Result.success(WearOutboxRecord(firstArg(), 1, userId = "user"))
        }
        coEvery { preparer.prepareSnapshot(any(), any()) } returns Result.success(Unit)
        coEvery { preparer.prepareRevocation(any(), any()) } returns Result.success(Unit)
        coEvery { delivery.deliverPending() } returns Result.success(Unit)
    }

    @Test
    fun `refresh prepares only authorized watches of the requested account`() = runTest {
        decisions = listOf(consent, consent.copy(userId = "other", deviceId = "other-watch"))
        assertTrue(coordinator.refresh("user").isSuccess)
        coVerifyOrder {
            preparer.prepareSnapshot("user", "watch")
            delivery.deliverPending()
        }
        coVerify(exactly = 0) { preparer.prepareSnapshot("other", any()) }
    }

    @Test
    fun `retry repairs revoked decisions before delivery without extracting secrets`() = runTest {
        decisions = listOf(consent.copy(isAuthorized = false))
        assertTrue(coordinator.retry().isSuccess)
        coVerifyOrder {
            preparer.prepareRevocation("user", "watch")
            delivery.deliverPending()
        }
        coVerify(exactly = 0) { preparer.prepareSnapshot(any(), any()) }
    }

    @Test
    fun `logout persists withdrawal before preparation and preserves other accounts`() = runTest {
        val other = consent.copy(userId = "other", deviceId = "other-watch")
        decisions = listOf(consent, other)
        coEvery { preparer.prepareRevocation("user", "watch") } answers {
            assertEquals(listOf(consent.copy(isAuthorized = false), other), decisions)
            Result.success(Unit)
        }
        assertTrue(coordinator.revokeAccount("user").isSuccess)
        assertEquals(listOf(consent.copy(isAuthorized = false), other), decisions)
        coVerify(exactly = 0) { preparer.prepareRevocation("other", any()) }
        coVerify(exactly = 0) { preparer.prepareSnapshot(any(), any()) }
    }

    @Test
    fun `failed withdrawal persistence stops preparation and delivery`() = runTest {
        every { consentSource.compareAndSetConsents(any(), any()) } returns failure()
        assertTrue(coordinator.revokeAccount("user").isFailure)
        coVerify(exactly = 0) { preparer.prepareRevocation(any(), any()) }
        coVerify(exactly = 0) { delivery.deliverPending() }
    }

    @Test
    fun `failed preparation is repaired by retry from persisted withdrawal`() = runTest {
        coEvery { preparer.prepareRevocation(any(), any()) } returns failure()
        assertTrue(coordinator.revokeAccount("user").isFailure)
        assertTrue(!decisions.single().isAuthorized)
        coEvery { preparer.prepareRevocation(any(), any()) } returns Result.success(Unit)
        assertTrue(coordinator.retry().isSuccess)
        coVerify(exactly = 2) { preparer.prepareRevocation("user", "watch") }
        verify(exactly = 1) { consentSource.compareAndSetConsents(any(), any()) }
    }

    @Test
    fun `one failed watch does not block another watch or durable delivery`() = runTest {
        decisions = listOf(consent, consent.copy(deviceId = "second"))
        coEvery { preparer.prepareSnapshot("user", "watch") } returns failure()
        assertTrue(coordinator.refresh("user").isFailure)
        coVerify(exactly = 1) { preparer.prepareSnapshot("user", "second") }
        coVerify(exactly = 1) { delivery.deliverPending() }
    }

    @Test
    fun `unreadable consent fails closed without delivery`() = runTest {
        every { consentSource.getConsents() } returns Result.failure(IOException("unreadable"))
        assertTrue(coordinator.retry().isFailure)
        coVerify(exactly = 0) { delivery.deliverPending() }
    }

    @Test
    fun `delivery failure leaves durable withdrawal available for future retries`() = runTest {
        coEvery { delivery.deliverPending() } returns failure()
        assertTrue(coordinator.revokeAccount("user").isFailure)
        assertEquals(consent.copy(isAuthorized = false), decisions.single())
    }

    @Test
    fun `cancellation propagates without starting delivery`() {
        coEvery { preparer.prepareSnapshot(any(), any()) } throws CancellationException()
        assertThrows(CancellationException::class.java) {
            runTest { coordinator.refresh("user") }
        }
        coVerify(exactly = 0) { delivery.deliverPending() }
    }

    @Test
    fun `old withdrawn consent cannot revoke a new account enrollment`() = runTest {
        decisions = listOf(consent.copy(isAuthorized = false))
        coEvery { outbox.getRecord("watch") } returns Result.success(
            WearOutboxRecord("watch", 2, userId = "new-user"),
        )
        assertTrue(coordinator.retry().isSuccess)
        coVerify(exactly = 0) { preparer.prepareRevocation(any(), any()) }
        coVerify(exactly = 1) { delivery.deliverPending() }
    }

    @Test
    fun `unknown enrollment ownership fails without attempting revocation`() = runTest {
        decisions = listOf(consent.copy(isAuthorized = false))
        coEvery { outbox.getRecord("watch") } returns Result.failure(IOException("unreadable"))
        assertTrue(coordinator.retry().isFailure)
        coVerify(exactly = 0) { preparer.prepareRevocation(any(), any()) }
    }

    private fun failure(): Result<Unit> = Result.failure(IOException("unavailable"))
}
