package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class WearConsentManagerTest {
    private val user = mockk<UserState> { every { activeUserId } returns "user" }
    private val users = MutableStateFlow<UserState?>(user)
    private val auth = mockk<AuthRepository> { every { userStateFlow } returns users }
    private val locks = mockk<VaultLockManager>()
    private val devices = mockk<WearDeviceSource>()
    private val disk = mockk<WearConsentDiskSource>()
    private val keys = mockk<WearKeySource>()
    private val manager = WearConsentManagerImpl(
        devices, disk, auth, locks, FakeDispatcherManager(), keys,
    )

    @BeforeEach
    fun setUp() {
        coEvery { keys.getPublicKey("watch") } returns Result.success(byteArrayOf(1, 2, 3))
        every { devices.isSupported } returns true
        every { locks.isVaultUnlocked("user") } returns true
        coEvery { devices.getDevices() } returns Result.success(
            listOf(WearDevice("watch", "Watch")),
        )
        every { disk.getConsents() } returns Result.success(emptyList())
        every { disk.compareAndSetConsents(any(), any()) } returns Result.success(Unit)
    }

    @Test
    fun `a locked vault cannot discover or authorize devices`() = runTest {
        every { locks.isVaultUnlocked("user") } returns false
        assertEquals(WearConsentResult.VaultLocked, manager.setConsent("user", "watch", true))
        coVerify(exactly = 0) { devices.getDevices() }
        verify(exactly = 0) { disk.compareAndSetConsents(any(), any()) }
    }

    @Test
    fun `an account switch during discovery cannot authorize the old account`() = runTest {
        coEvery { devices.getDevices() } coAnswers {
            users.value = mockk { every { activeUserId } returns "other" }
            Result.success(listOf(WearDevice("watch", "Watch")))
        }
        assertEquals(WearConsentResult.VaultLocked, manager.setConsent("user", "watch", true))
        verify(exactly = 0) { disk.compareAndSetConsents(any(), any()) }
    }

    @Test
    fun `consent preserves other accounts and is scoped to the selected watch`() = runTest {
        val other = WearConsent("other", "watch", "Watch", true)
        every { disk.getConsents() } returns Result.success(listOf(other))
        assertEquals(
            WearConsentResult.Success(listOf(WearConsentDevice("watch", "Watch", true, true))),
            manager.setConsent("user", "watch", true),
        )
        verify {
            disk.compareAndSetConsents(
                any(), listOf(other, WearConsent("user", "watch", "Watch", true, "AQID")),
            )
        }
    }

    @Test
    fun `revoking an offline watch retains its decision for later delivery`() = runTest {
        coEvery { devices.getDevices() } returns Result.success(emptyList())
        every { disk.getConsents() } returns Result.success(
            listOf(WearConsent("user", "watch", "Watch", true)),
        )
        assertEquals(
            WearConsentResult.Success(listOf(WearConsentDevice("watch", "Watch", false, false))),
            manager.setConsent("user", "watch", false),
        )
        verify {
            disk.compareAndSetConsents(
                any(), listOf(WearConsent("user", "watch", "Watch", false)),
            )
        }
    }

    @Test
    fun `an unknown or unreachable watch cannot be authorized`() = runTest {
        assertEquals(WearConsentResult.Error, manager.setConsent("user", "unknown", true))
        coEvery { devices.getDevices() } returns Result.success(emptyList())
        every { disk.getConsents() } returns Result.success(
            listOf(WearConsent("user", "watch", "Watch", false)),
        )
        assertEquals(WearConsentResult.Error, manager.setConsent("user", "watch", true))
        verify(exactly = 0) { disk.compareAndSetConsents(any(), any()) }
    }

    @Test
    fun `a persistence failure never reports successful authorization`() = runTest {
        every { disk.compareAndSetConsents(any(), any()) } returns Result.failure(IOException())
        assertEquals(WearConsentResult.Error, manager.setConsent("user", "watch", true))
    }

    @Test
    fun `discovery failures cannot prevent local revocation`() = runTest {
        coEvery { devices.getDevices() } returns Result.failure(IOException())
        every { disk.getConsents() } returns Result.success(
            listOf(WearConsent("user", "watch", "Watch", true)),
        )
        assertEquals(
            WearConsentResult.Success(listOf(WearConsentDevice("watch", "Watch", false, false))),
            manager.setConsent("user", "watch", false),
        )
        verify {
            disk.compareAndSetConsents(
                any(), listOf(WearConsent("user", "watch", "Watch", false)),
            )
        }
    }

    @Test
    fun `unreadable consent fails closed without overwriting the ledger`() = runTest {
        every { disk.getConsents() } returns Result.failure(IOException())
        assertEquals(WearConsentResult.Error, manager.setConsent("user", "watch", true))
        verify(exactly = 0) { disk.compareAndSetConsents(any(), any()) }
    }

    @Test
    fun `a changed pinned key is rejected without overwriting consent`() = runTest {
        every { disk.getConsents() } returns Result.success(
            listOf(WearConsent("user", "watch", "Watch", true, "old-key")),
        )
        assertEquals(WearConsentResult.Error, manager.getDevices("user"))
        verify(exactly = 0) { disk.compareAndSetConsents(any(), any()) }
    }

    @Test
    fun `key discovery failure never grants new consent`() = runTest {
        coEvery { keys.getPublicKey("watch") } returns Result.failure(IOException())
        assertEquals(WearConsentResult.Error, manager.setConsent("user", "watch", true))
        verify(exactly = 0) { disk.compareAndSetConsents(any(), any()) }
    }

    @Test
    fun `an account switch during key exchange cannot save the key`() = runTest {
        coEvery { keys.getPublicKey("watch") } coAnswers {
            users.value = mockk { every { activeUserId } returns "other" }
            Result.success(byteArrayOf(1, 2, 3))
        }
        assertEquals(WearConsentResult.VaultLocked, manager.setConsent("user", "watch", true))
        verify(exactly = 0) { disk.compareAndSetConsents(any(), any()) }
    }

    @Test
    fun `locking the vault during key exchange cannot save the key`() = runTest {
        coEvery { keys.getPublicKey("watch") } coAnswers {
            every { locks.isVaultUnlocked("user") } returns false
            Result.success(byteArrayOf(1, 2, 3))
        }
        assertEquals(WearConsentResult.VaultLocked, manager.setConsent("user", "watch", true))
        verify(exactly = 0) { disk.compareAndSetConsents(any(), any()) }
    }

    @Test
    fun `refresh pins an existing consent but does not enroll unauthorized devices`() = runTest {
        assertEquals(
            WearConsentResult.Success(listOf(WearConsentDevice("watch", "Watch", false, true))),
            manager.getDevices("user"),
        )
        coVerify(exactly = 0) { keys.getPublicKey(any()) }
        every { disk.getConsents() } returns Result.success(
            listOf(WearConsent("user", "watch", "Watch", true)),
        )
        assertEquals(
            WearConsentResult.Success(listOf(WearConsentDevice("watch", "Watch", true, true))),
            manager.getDevices("user"),
        )
        verify {
            disk.compareAndSetConsents(
                any(), listOf(WearConsent("user", "watch", "Watch", true, "AQID")),
            )
        }
    }

    @Test
    fun `revocation retains the pinned key without requiring the watch`() = runTest {
        every { disk.getConsents() } returns Result.success(
            listOf(WearConsent("user", "watch", "Watch", true, "AQID")),
        )
        coEvery { devices.getDevices() } returns Result.failure(IOException())
        manager.setConsent("user", "watch", false)
        verify {
            disk.compareAndSetConsents(
                any(), listOf(WearConsent("user", "watch", "Watch", false, "AQID")),
            )
        }
        coVerify(exactly = 0) { keys.getPublicKey(any()) }
    }
}
