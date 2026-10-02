package com.x8bit.bitwarden.data.wear

import app.cash.turbine.test
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import com.x8bit.bitwarden.data.vault.manager.model.VaultStateEvent
import com.bitwarden.network.model.SyncResponseJson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WearSyncEventSourceTest {
    private val account = UserState.EMPTY_ACCOUNT.copy(userId = "user", isLoggedIn = true)
    private val users = MutableStateFlow<UserState?>(UserState("user", listOf(account)))
    private val auth = mockk<AuthRepository> { every { userStateFlow } returns users }
    private val ciphers = MutableStateFlow<List<SyncResponseJson.Cipher>>(emptyList())
    private val otherCiphers = MutableStateFlow<List<SyncResponseJson.Cipher>>(emptyList())
    private val disk = mockk<VaultDiskSource> {
        every { getCiphersFlow("user") } returns ciphers
        every { getCiphersFlow("other") } returns otherCiphers
    }
    private val locks = MutableSharedFlow<VaultStateEvent>()
    private val lockManager = mockk<VaultLockManager> {
        every { vaultStateEventFlow } returns locks
    }
    private val changes = MutableSharedFlow<Unit>()
    private val consent = mockk<WearConsentDiskSource> {
        every { changes } returns this@WearSyncEventSourceTest.changes
    }
    private val source = WearSyncEventSourceImpl(auth, disk, lockManager, consent, true)

    @Test
    fun `initial account and local cipher changes request refresh`() = runTest {
        source.events.test {
            assertEquals(WearSyncEvent.Refresh("user"), awaitItem())
            ciphers.value = listOf(mockk())
            assertEquals(WearSyncEvent.Refresh("user"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `null account requests retry without inferring logout`() = runTest {
        users.value = null
        source.events.test {
            assertEquals(WearSyncEvent.Retry, awaitItem())
            changes.emit(Unit)
            assertEquals(WearSyncEvent.Retry, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switching accounts unsubscribes old vault notifications`() = runTest {
        source.events.test {
            assertEquals(WearSyncEvent.Refresh("user"), awaitItem())
            users.value = UserState("other", listOf(account.copy(userId = "other")))
            assertEquals(WearSyncEvent.Refresh("other"), awaitItem())
            ciphers.value = listOf(mockk())
            expectNoEvents()
            otherCiphers.value = listOf(mockk())
            assertEquals(WearSyncEvent.Refresh("other"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unlock requests refresh while locking only retries ciphertext`() = runTest {
        source.events.test {
            awaitItem()
            locks.emit(VaultStateEvent.Unlocked("user"))
            assertEquals(WearSyncEvent.Refresh("user"), awaitItem())
            locks.emit(VaultStateEvent.Locked("user"))
            assertEquals(WearSyncEvent.Retry, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `committed consent changes request the current account`() = runTest {
        source.events.test {
            awaitItem()
            changes.emit(Unit)
            assertEquals(WearSyncEvent.Refresh("user"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `account entitlement changes refresh without switching user`() = runTest {
        source.events.test {
            awaitItem()
            users.value = UserState("user", listOf(account.copy(isPremium = !account.isPremium)))
            assertEquals(WearSyncEvent.Refresh("user"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disabled variants do not observe lifecycle events`() = runTest {
        WearSyncEventSourceImpl(auth, disk, lockManager, consent, false).events.test {
            awaitComplete()
        }
    }
}
