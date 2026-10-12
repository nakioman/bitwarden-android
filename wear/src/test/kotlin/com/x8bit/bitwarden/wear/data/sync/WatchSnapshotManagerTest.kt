package com.x8bit.bitwarden.wear.data.sync

import app.cash.turbine.test
import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.wear.WearDataProtocol
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import com.x8bit.bitwarden.wear.data.sync.transport.WatchDataTransport
import com.x8bit.bitwarden.wear.data.sync.transport.WatchSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class WatchSnapshotManagerTest {
    private val security = mockk<WatchSecurityManager>()
    private val keys = mockk<WatchSyncKeyManager>()
    private val snapshotChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val transport = mockk<WatchDataTransport> {
        every { this@mockk.snapshotChanges } returns this@WatchSnapshotManagerTest.snapshotChanges
    }
    private val publicKey = byteArrayOf(1, 2, 3)
    private val keyId = WearDataProtocol.recipientKeyId(publicKey)
    private val manager = WatchSnapshotManagerImpl(
        security, keys, transport, FakeDispatcherManager(),
    )

    @BeforeEach
    fun setUp() {
        every { security.getStatus() } returns WatchSecurityStatus.UNLOCKED
        every { keys.getPublicKey() } returns Result.success(publicKey)
        every { keys.createPublicKey() } returns Result.success(publicKey)
        every { keys.deleteKey() } returns Result.success(Unit)
        coEvery { transport.publishPublicKey(any()) } returns Result.success(Unit)
        coEvery { transport.deletePublicKey() } returns Result.success(Unit)
        coEvery { transport.readSnapshot() } returns Result.success(
            WatchSnapshot(keyId, byteArrayOf(9)),
        )
        every {
            keys.decrypt(any(), any())
        } answers { Result.success(byteArrayOf(7, 8)) }
    }

    @Test
    fun `provision publishes the key while the watch has a secure lock`() = runTest {
        every { security.getStatus() } returns WatchSecurityStatus.LOCKED
        assertTrue(manager.provision().isSuccess)
        coVerify { transport.publishPublicKey(publicKey) }
    }

    @Test
    fun `provision announces a newly created key`() = runTest {
        every { keys.getPublicKey() } returns Result.failure(IOException())
        manager.changes.test {
            manager.provision().getOrThrow()
            awaitItem()
        }
    }

    @Test
    fun `provision withdraws the key without a screen lock`() = runTest {
        every { security.getStatus() } returns WatchSecurityStatus.SCREEN_LOCK_REQUIRED
        assertTrue(manager.provision().isSuccess)
        verify { keys.deleteKey() }
        coVerify { transport.deletePublicKey() }
        coVerify(exactly = 0) { transport.publishPublicKey(any()) }
    }

    @Test
    fun `provision does nothing before security is verified`() = runTest {
        every { security.getStatus() } returns WatchSecurityStatus.UNVERIFIED
        assertTrue(manager.provision().isFailure)
        verify(exactly = 0) { keys.createPublicKey() }
        verify(exactly = 0) { keys.deleteKey() }
    }

    @Test
    fun `read decrypts the snapshot for the current key`() = runTest {
        assertArrayEquals(byteArrayOf(7, 8), manager.read().getOrThrow())
        verify {
            keys.decrypt(
                match { it.contentEquals(byteArrayOf(9)) },
                match { it.contentEquals(WearDataProtocol.snapshotContext(keyId)) },
            )
        }
    }

    @Test
    fun `read returns null without a snapshot`() = runTest {
        coEvery { transport.readSnapshot() } returns Result.success(null)
        assertNull(manager.read().getOrThrow())
    }

    @Test
    fun `read returns null for a snapshot encrypted to an older key`() = runTest {
        coEvery { transport.readSnapshot() } returns Result.success(
            WatchSnapshot("b".repeat(64), byteArrayOf(9)),
        )
        assertNull(manager.read().getOrThrow())
        verify(exactly = 0) { keys.decrypt(any(), any()) }
    }

    @Test
    fun `read returns null when the key is missing`() = runTest {
        every { keys.getPublicKey() } returns Result.failure(IOException())
        assertNull(manager.read().getOrThrow())
    }

    @Test
    fun `read is denied while the watch is locked`() = runTest {
        every { security.getStatus() } returns WatchSecurityStatus.LOCKED
        assertTrue(manager.read().isFailure)
        coVerify(exactly = 0) { transport.readSnapshot() }
    }

    @Test
    fun `read discards plaintext if the watch locks during decryption`() = runTest {
        val plaintext = byteArrayOf(7, 8)
        every { keys.decrypt(any(), any()) } answers {
            every { security.getStatus() } returns WatchSecurityStatus.LOCKED
            Result.success(plaintext)
        }
        assertTrue(manager.read().isFailure)
        assertArrayEquals(byteArrayOf(0, 0), plaintext)
    }

    @Test
    fun `changes follow published snapshot changes`() = runTest {
        manager.changes.test {
            snapshotChanges.emit(Unit)
            awaitItem()
        }
    }
}
