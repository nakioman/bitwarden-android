package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.wear.WearOtpEntry
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WearSyncManagerTest {
    private val account = UserState.EMPTY_ACCOUNT.copy(userId = "user", isLoggedIn = true)
    private val users = MutableStateFlow<UserState?>(UserState("user", listOf(account)))
    private val auth = mockk<AuthRepository> { every { userStateFlow } returns users }
    private val accountSource = mockk<WearOtpAccountSource>()
    private val diskSource = mockk<WearSyncDiskSource>()
    private val otpSource = mockk<WearOtpSource>()
    private val store = mockk<WearSnapshotStore>()
    private val entries = listOf(WearOtpEntry("id", "name", null, "secret"))
    private val manager = WearSyncManagerImpl(auth, accountSource, diskSource, otpSource, store)

    @BeforeEach
    fun setUp() {
        every { accountSource.getAccount("user") } returns account
        every { diskSource.isSyncEnabled("user") } returns true
        every { diskSource.storeSyncEnabled(any(), any()) } just runs
        coEvery { otpSource.getEntries("user") } returns Result.success(entries)
        coEvery { store.publish(any(), any(), any()) } returns Result.success(Unit)
        coEvery { store.remove(any()) } returns Result.success(Unit)
    }

    @Test
    fun `sync publishes the active account's codes`() = runTest {
        assertTrue(manager.sync().isSuccess)
        coVerify { store.publish("user", entries, any()) }
    }

    @Test
    fun `sync does nothing while sync is off or the vault is locked`() = runTest {
        every { diskSource.isSyncEnabled("user") } returns false
        assertTrue(manager.sync().isSuccess)
        every { diskSource.isSyncEnabled("user") } returns true
        every { accountSource.getAccount("user") } returns null
        assertTrue(manager.sync().isSuccess)
        users.value = null
        assertTrue(manager.sync().isSuccess)
        coVerify(exactly = 0) { store.publish(any(), any(), any()) }
        coVerify(exactly = 0) { store.remove(any()) }
    }

    @Test
    fun `publication stops if the vault locks before writing`() = runTest {
        val allowed = slot<() -> Boolean>()
        coEvery { store.publish(any(), any(), capture(allowed)) } returns Result.success(Unit)
        manager.sync()
        assertTrue(allowed.captured())
        every { accountSource.getAccount("user") } returns null
        assertFalse(allowed.captured())
    }

    @Test
    fun `turning sync off stores the setting and removes the account's codes`() = runTest {
        every { diskSource.isSyncEnabled("user") } returns false
        assertTrue(manager.setSyncEnabled("user", false).isSuccess)
        verify { diskSource.storeSyncEnabled("user", false) }
        coVerify { store.remove("user") }
    }

    @Test
    fun `turning sync on stores the setting and publishes`() = runTest {
        assertTrue(manager.setSyncEnabled("user", true).isSuccess)
        verify { diskSource.storeSyncEnabled("user", true) }
        coVerify { store.publish("user", entries, any()) }
    }
}
