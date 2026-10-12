package com.x8bit.bitwarden.data.wear

import app.cash.turbine.test
import com.bitwarden.network.model.SyncResponseJson
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import com.x8bit.bitwarden.data.vault.manager.model.VaultStateEvent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
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
    private val settingChanges = MutableSharedFlow<Unit>()
    private val settings = mockk<WearSyncDiskSource> {
        every { changes } returns settingChanges
    }
    private val source = WearSyncEventSourceImpl(auth, disk, lockManager, settings, true)

    @Test
    fun `initial account and local cipher changes request an update`() = runTest {
        source.events.test {
            awaitItem()
            ciphers.value = listOf(mockk())
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switching accounts unsubscribes old vault notifications`() = runTest {
        source.events.test {
            awaitItem()
            users.value = UserState("other", listOf(account.copy(userId = "other")))
            awaitItem()
            ciphers.value = listOf(mockk())
            expectNoEvents()
            otherCiphers.value = listOf(mockk())
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unlock requests an update while lock does not`() = runTest {
        source.events.test {
            awaitItem()
            locks.emit(VaultStateEvent.Locked("user"))
            expectNoEvents()
            locks.emit(VaultStateEvent.Unlocked("user"))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setting changes request an update`() = runTest {
        source.events.test {
            awaitItem()
            settingChanges.emit(Unit)
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `premium changes request an update`() = runTest {
        source.events.test {
            awaitItem()
            users.value = UserState("user", listOf(account.copy(isPremium = !account.isPremium)))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disabled variants do not observe anything`() = runTest {
        WearSyncEventSourceImpl(auth, disk, lockManager, settings, false).events.test {
            awaitComplete()
        }
    }
}
