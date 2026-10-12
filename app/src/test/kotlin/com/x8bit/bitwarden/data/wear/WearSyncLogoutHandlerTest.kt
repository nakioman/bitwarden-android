package com.x8bit.bitwarden.data.wear

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearSyncLogoutHandlerTest {
    private val diskSource = mockk<WearSyncDiskSource> {
        every { storeSyncEnabled(any(), any()) } just runs
    }
    private val store = mockk<WearSnapshotStore> {
        coEvery { remove(any()) } returns Result.success(Unit)
    }

    @Test
    fun `hard logout removes codes and forgets the setting`() = runTest {
        WearSyncLogoutHandlerImpl(diskSource, store, backgroundScope, true)
            .onLogout("user", isHardLogout = true)
        runCurrent()
        verify { diskSource.storeSyncEnabled("user", null) }
        coVerify { store.remove("user") }
    }

    @Test
    fun `soft logout removes codes but keeps the setting`() = runTest {
        WearSyncLogoutHandlerImpl(diskSource, store, backgroundScope, true)
            .onLogout("user", isHardLogout = false)
        runCurrent()
        verify(exactly = 0) { diskSource.storeSyncEnabled(any(), any()) }
        coVerify { store.remove("user") }
    }

    @Test
    fun `disabled builds do nothing`() = runTest {
        WearSyncLogoutHandlerImpl(diskSource, store, backgroundScope, false)
            .onLogout("user", isHardLogout = true)
        runCurrent()
        verify(exactly = 0) { diskSource.storeSyncEnabled(any(), any()) }
        coVerify(exactly = 0) { store.remove(any()) }
    }
}
