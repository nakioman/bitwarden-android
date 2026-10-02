package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.data.datasource.disk.base.FakeSharedPreferences
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WearConsentAtomicityTest {
    private val disk = WearConsentDiskSourceImpl(FakeSharedPreferences(), Json)
    private val initial = WearConsent("user", "watch", "Watch", true)
    private val user = mockk<UserState> { every { activeUserId } returns "user" }
    private val auth = mockk<AuthRepository> {
        every { userStateFlow } returns MutableStateFlow(user)
    }
    private val locks = mockk<VaultLockManager> {
        every { isVaultUnlocked("user") } returns true
    }
    private val devices = mockk<WearDeviceSource> { every { isSupported } returns true }
    private val keys = mockk<WearKeySource>()
    private val manager = WearConsentManagerImpl(
        devices, disk, auth, locks, FakeDispatcherManager(), keys,
    )

    @Test
    fun `stale key verification cannot restore revoked consent`() = runTest {
        assertTrue(disk.compareAndSetConsents(emptyList(), listOf(initial)).isSuccess)
        coEvery { devices.getDevices() } returns Result.success(
            listOf(WearDevice("watch", "Watch")),
        )
        coEvery { keys.getPublicKey("watch") } coAnswers {
            assertTrue(disk.compareAndSetConsents(
                listOf(initial), listOf(initial.copy(isAuthorized = false)),
            ).isSuccess)
            Result.success(byteArrayOf(1, 2, 3))
        }
        assertEquals(WearConsentResult.Error, manager.getDevices("user"))
        assertEquals(listOf(initial.copy(isAuthorized = false)), disk.getConsents().getOrThrow())
    }

    @Test
    fun `authorization started before a concurrent withdrawal cannot overwrite it`() = runTest {
        coEvery { devices.getDevices() } returns Result.success(
            listOf(WearDevice("watch", "Watch")),
        )
        val withdrawn = initial.copy(isAuthorized = false)
        coEvery { keys.getPublicKey("watch") } coAnswers {
            assertTrue(disk.compareAndSetConsents(emptyList(), listOf(withdrawn)).isSuccess)
            Result.success(byteArrayOf(1, 2, 3))
        }
        assertEquals(WearConsentResult.Error, manager.setConsent("user", "watch", true))
        assertEquals(listOf(withdrawn), disk.getConsents().getOrThrow())
    }

    @Test
    fun `stale ledger cannot erase another account decision`() {
        val other = initial.copy(userId = "other", deviceId = "other-watch")
        assertTrue(disk.compareAndSetConsents(emptyList(), listOf(other)).isSuccess)
        assertTrue(disk.compareAndSetConsents(emptyList(), listOf(initial)).isFailure)
        assertEquals(listOf(other), disk.getConsents().getOrThrow())
    }

    @Test
    fun `compare and set persists matching changes and permits unchanged ledgers`() {
        assertTrue(disk.compareAndSetConsents(emptyList(), listOf(initial)).isSuccess)
        assertTrue(disk.compareAndSetConsents(listOf(initial), listOf(initial)).isSuccess)
        val withdrawn = initial.copy(isAuthorized = false)
        assertTrue(disk.compareAndSetConsents(listOf(initial), listOf(withdrawn)).isSuccess)
        assertEquals(listOf(withdrawn), disk.getConsents().getOrThrow())
    }
}
