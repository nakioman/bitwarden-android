package com.x8bit.bitwarden.data.wear

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class WearSyncLifecycleManagerTest {
    private val events = MutableSharedFlow<Unit>()
    private val source = mockk<WearSyncEventSource> {
        every { events } returns this@WearSyncLifecycleManagerTest.events
    }
    private val syncManager = mockk<WearSyncManager> {
        coEvery { sync() } returns Result.success(Unit)
    }

    @Test
    fun `initialization syncs once and then on every event`() = runTest {
        val manager = WearSyncLifecycleManagerImpl(source, syncManager, backgroundScope)
        manager.initialize()
        manager.initialize()
        runCurrent()
        coVerify(exactly = 1) { syncManager.sync() }
        events.emit(Unit)
        runCurrent()
        coVerify(exactly = 2) { syncManager.sync() }
    }

    @Test
    fun `a failed sync does not stop later events`() = runTest {
        coEvery { syncManager.sync() } returns Result.failure(IOException())
        WearSyncLifecycleManagerImpl(source, syncManager, backgroundScope).initialize()
        runCurrent()
        events.emit(Unit)
        runCurrent()
        events.emit(Unit)
        runCurrent()
        coVerify(exactly = 3) { syncManager.sync() }
    }
}
