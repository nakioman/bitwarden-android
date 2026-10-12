package com.x8bit.bitwarden.data.wear

import app.cash.turbine.test
import com.bitwarden.data.datasource.disk.base.FakeSharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WearSyncDiskSourceTest {
    private val diskSource = WearSyncDiskSourceImpl(FakeSharedPreferences())

    @Test
    fun `setting is stored per account and can be removed`() {
        assertFalse(diskSource.isSyncEnabled("user"))
        diskSource.storeSyncEnabled("user", true)
        assertTrue(diskSource.isSyncEnabled("user"))
        assertFalse(diskSource.isSyncEnabled("other"))
        diskSource.storeSyncEnabled("user", null)
        assertFalse(diskSource.isSyncEnabled("user"))
    }

    @Test
    fun `changes emit on subscription and after updates`() = runTest {
        diskSource.changes.test {
            awaitItem()
            diskSource.storeSyncEnabled("user", true)
            awaitItem()
        }
    }
}
