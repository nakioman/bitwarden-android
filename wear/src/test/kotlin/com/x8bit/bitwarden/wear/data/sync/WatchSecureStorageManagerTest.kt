package com.x8bit.bitwarden.wear.data.sync

import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class WatchSecureStorageManagerTest {
    private val security = mockk<WatchSecurityManager>()
    private val keys = mockk<WatchSyncKeyManager>()
    private val disk = mockk<WatchEnvelopeDiskSource>()
    private val manager = WatchSecureStorageManagerImpl(
        security, keys, disk, FakeDispatcherManager(),
    )
    private val envelope = byteArrayOf(1, 2)
    private val context = byteArrayOf(3)

    @BeforeEach
    fun setUp() {
        every { security.getStatus() } returns WatchSecurityStatus.UNLOCKED
        every { disk.read() } returns Result.success(envelope)
        every { disk.write(any()) } returns Result.success(Unit)
        every { disk.delete() } returns Result.success(Unit)
        every { keys.getPublicKey() } returns Result.success(byteArrayOf(4))
        every { keys.deleteKey() } returns Result.success(Unit)
    }

    @Test
    fun `a locked watch stores ciphertext without decrypting it`() = runTest {
        every { security.getStatus() } returns WatchSecurityStatus.LOCKED
        assertTrue(manager.receive(envelope).isSuccess)
        verify(exactly = 1) { disk.write(envelope) }
        verify(exactly = 0) { keys.decrypt(any(), any()) }
    }

    @Test
    fun `a locked watch never reads or decrypts cached data`() = runTest {
        every { security.getStatus() } returns WatchSecurityStatus.LOCKED
        assertTrue(manager.read(context).isFailure)
        verify(exactly = 0) { disk.read() }
        verify(exactly = 0) { keys.decrypt(any(), any()) }
    }

    @Test
    fun `an unlocked watch decrypts with the validated context`() = runTest {
        val plaintext = byteArrayOf(5, 6)
        every { keys.decrypt(envelope, context) } returns Result.success(plaintext)
        assertArrayEquals(plaintext, manager.read(context).getOrThrow())
    }

    @Test
    fun `a lock during decryption discards the plaintext`() = runTest {
        val plaintext = byteArrayOf(5, 6)
        every { security.getStatus() } returnsMany listOf(
            WatchSecurityStatus.UNLOCKED,
            WatchSecurityStatus.UNLOCKED,
            WatchSecurityStatus.LOCKED,
        )
        every { keys.decrypt(envelope, context) } returns Result.success(plaintext)
        assertTrue(manager.read(context).isFailure)
        assertArrayEquals(byteArrayOf(0, 0), plaintext)
    }

    @Test
    fun `removing the screen lock discards key and cache and denies reception`() = runTest {
        every { security.getStatus() } returns WatchSecurityStatus.SCREEN_LOCK_REQUIRED
        assertTrue(manager.receive(envelope).isFailure)
        verify(exactly = 1) { keys.deleteKey() }
        verify(exactly = 1) { disk.delete() }
        verify(exactly = 0) { disk.write(any()) }
    }

    @Test
    fun `missing security service denies access without destroying data`() = runTest {
        every { security.getStatus() } returns WatchSecurityStatus.UNVERIFIED
        assertTrue(manager.read(context).isFailure)
        verify(exactly = 0) { keys.deleteKey() }
        verify(exactly = 0) { disk.delete() }
    }

    @Test
    fun `an existing snapshot never gets a silently recreated recipient key`() = runTest {
        every { keys.getPublicKey() } returns Result.failure(IOException("Missing key"))
        assertTrue(manager.provision().isFailure)
        verify(exactly = 0) { keys.createPublicKey() }
    }

    @Test
    fun `unreadable cache cannot trigger key generation`() = runTest {
        every { disk.read() } returns Result.failure(IOException("Unreadable"))
        assertTrue(manager.provision().isFailure)
        verify(exactly = 0) { keys.createPublicKey() }
    }

    @Test
    fun `revocation attempts both erasures even if deleting the key fails`() = runTest {
        every { security.getStatus() } returns WatchSecurityStatus.LOCKED
        every { keys.deleteKey() } returns Result.failure(IOException("Unavailable"))
        assertTrue(manager.clear().isFailure)
        verify(exactly = 1) { keys.deleteKey() }
        verify(exactly = 1) { disk.delete() }
    }

    @Test
    fun `a write failure is not reported as a successful reception`() = runTest {
        every { disk.write(envelope) } returns Result.failure(IOException("Full disk"))
        assertTrue(manager.receive(envelope).isFailure)
    }
}
