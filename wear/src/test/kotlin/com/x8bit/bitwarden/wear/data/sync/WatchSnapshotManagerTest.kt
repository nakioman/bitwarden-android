package com.x8bit.bitwarden.wear.data.sync

import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotHeader
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
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
    private val disk = mockk<WatchEnvelopeDiskSource>()
    private val publicKey = byteArrayOf(1, 2, 3)
    private val header = WearSnapshotHeader(
        ENROLLMENT, 1, WearSnapshotProtocol.recipientKeyId(publicKey),
    )
    private val ciphertext = byteArrayOf(4, 5, 6)
    private var saved: ByteArray? = null
    private val manager = createManager()

    @BeforeEach
    fun setUp() {
        every { security.getStatus() } returns WatchSecurityStatus.UNLOCKED
        every { keys.getPublicKey() } returns Result.success(publicKey)
        every { keys.createPublicKey() } returns Result.success(publicKey)
        every { keys.deleteKey() } returns Result.success(Unit)
        every { disk.read() } answers { Result.success(saved) }
        every { disk.write(any()) } answers {
            saved = firstArg<ByteArray>().copyOf()
            Result.success(Unit)
        }
        every { disk.delete() } answers {
            saved = null
            Result.success(Unit)
        }
    }

    @Test
    fun `initial revocation can erase a current key before any snapshot arrives`() = runTest {
        every { security.getStatus() } returns WatchSecurityStatus.LOCKED
        val revoke = packet(header.copy(revision = 2, isRevocation = true), byteArrayOf())
        assertTrue(manager.receive("phone", revoke).isSuccess)
        assertTrue(WatchSnapshotRecord.decode(requireNotNull(saved)).getOrThrow()
            .frame.header.isRevocation)
        verifyOrder {
            disk.write(any())
            keys.deleteKey()
        }
        assertTrue(manager.receive("phone", packet()).isFailure)
        verify(exactly = 0) { keys.decrypt(any(), any()) }
    }

    @Test
    fun `initial revocation with a different recipient cannot bind or erase a key`() = runTest {
        val revoke = packet(
            header.copy(isRevocation = true, recipientKeyId = "0".repeat(64)), byteArrayOf(),
        )
        assertTrue(manager.receive("phone", revoke).isFailure)
        verify(exactly = 0) { disk.write(any()) }
        verify(exactly = 0) { keys.deleteKey() }
    }

    @Test
    fun `failed initial revocation persistence keeps the recipient key`() = runTest {
        every { disk.write(any()) } returns Result.failure(IOException())
        assertTrue(
            manager.receive("phone", packet(header.copy(isRevocation = true), byteArrayOf()))
                .isFailure,
        )
        verify(exactly = 0) { keys.deleteKey() }
    }

    @Test
    fun `locked reception persists source revision and ciphertext without decryption`() = runTest {
        every { security.getStatus() } returns WatchSecurityStatus.LOCKED
        assertTrue(manager.receive("phone", packet()).isSuccess)
        val record = WatchSnapshotRecord.decode(requireNotNull(saved)).getOrThrow()
        assertTrue(record.sourceNodeId == "phone" && record.frame.header == header)
        assertArrayEquals(ciphertext, record.frame.ciphertext)
        verify(exactly = 0) { keys.decrypt(any(), any()) }
        assertTrue(manager.read().isFailure)
    }

    @Test
    fun `stored source binding rejects another phone including after restart`() = runTest {
        manager.receive("phone", packet())
        assertTrue(createManager().receive("other", packet(header.copy(revision = 2))).isFailure)
        verify(exactly = 1) { disk.write(any()) }
    }

    @Test
    fun `old revisions and conflicting duplicates cannot overwrite data`() = runTest {
        manager.receive("phone", packet(header.copy(revision = 2)))
        assertTrue(manager.receive("phone", packet()).isFailure)
        assertTrue(
            manager.receive("phone", packet(header.copy(revision = 2), byteArrayOf(9))).isFailure,
        )
        verify(exactly = 1) { disk.write(any()) }
    }

    @Test
    fun `exact duplicate is acknowledged without rewriting or decrypting`() = runTest {
        manager.receive("phone", packet())
        assertTrue(createManager().receive("phone", packet()).isSuccess)
        verify(exactly = 1) { disk.write(any()) }
        verify(exactly = 0) { keys.decrypt(any(), any()) }
    }

    @Test
    fun `another enrollment cannot replace an active snapshot`() = runTest {
        manager.receive("phone", packet())
        assertTrue(
            manager.receive(
                "phone", packet(header.copy(enrollmentId = OTHER_ENROLLMENT, revision = 2)),
            ).isFailure,
        )
        verify(exactly = 1) { disk.write(any()) }
    }

    @Test
    fun `a wrong recipient cannot persist data or generate a key`() = runTest {
        assertTrue(
            manager.receive("phone", packet(header.copy(recipientKeyId = "0".repeat(64))))
                .isFailure,
        )
        verify(exactly = 0) { disk.write(any()) }
        verify(exactly = 0) { keys.createPublicKey() }
    }

    @Test
    fun `revocation persists its tombstone before erasing the key while locked`() = runTest {
        manager.receive("phone", packet())
        every { security.getStatus() } returns WatchSecurityStatus.LOCKED
        val revoke = packet(header.copy(revision = 2, isRevocation = true), byteArrayOf())
        assertTrue(manager.receive("phone", revoke).isSuccess)
        verifyOrder { disk.write(any()); disk.write(any()); keys.deleteKey() }
        val record = WatchSnapshotRecord.decode(requireNotNull(saved)).getOrThrow()
        assertTrue(record.frame.header.isRevocation)
        assertArrayEquals(byteArrayOf(), record.frame.ciphertext)
        every { security.getStatus() } returns WatchSecurityStatus.UNLOCKED
        assertNull(manager.read().getOrThrow())
        verify(exactly = 0) { keys.decrypt(any(), any()) }
    }

    @Test
    fun `a revoked enrollment cannot be resurrected by a higher revision`() = runTest {
        manager.receive("phone", packet())
        manager.receive(
            "phone", packet(header.copy(revision = 2, isRevocation = true), byteArrayOf()),
        )
        assertTrue(createManager().receive("phone", packet(header.copy(revision = 3))).isFailure)
    }

    @Test
    fun `failed erasure denies reads and a duplicate revocation retries it`() = runTest {
        manager.receive("phone", packet())
        every { keys.deleteKey() } returns Result.failure(IOException())
        val revoke = packet(header.copy(revision = 2, isRevocation = true), byteArrayOf())
        assertTrue(manager.receive("phone", revoke).isFailure)
        assertNull(createManager().read().getOrThrow())
        every { keys.deleteKey() } returns Result.success(Unit)
        assertTrue(createManager().receive("phone", revoke).isSuccess)
        verify(exactly = 2) { keys.deleteKey() }
    }

    @Test
    fun `a duplicate revocation cannot erase a newly provisioned key`() = runTest {
        manager.receive("phone", packet())
        val revoke = packet(header.copy(revision = 2, isRevocation = true), byteArrayOf())
        manager.receive("phone", revoke)
        every { keys.getPublicKey() } returns Result.success(byteArrayOf(9))
        assertTrue(manager.receive("phone", revoke).isSuccess)
        verify(exactly = 1) { keys.deleteKey() }
    }

    @Test
    fun `a new enrollment can start after revocation with a fresh recipient key`() = runTest {
        manager.receive("phone", packet())
        manager.receive(
            "phone", packet(header.copy(revision = 2, isRevocation = true), byteArrayOf()),
        )
        val newKey = byteArrayOf(9)
        every { keys.getPublicKey() } returns Result.success(newKey)
        val next = header.copy(
            enrollmentId = OTHER_ENROLLMENT,
            revision = 3,
            recipientKeyId = WearSnapshotProtocol.recipientKeyId(newKey),
        )
        assertTrue(manager.receive("phone", packet(next)).isSuccess)
        assertTrue(manager.receive("phone", packet()).isFailure)
    }

    @Test
    fun `unreadable storage cannot generate a key or accept a packet`() = runTest {
        every { disk.read() } returns Result.failure(IOException())
        assertTrue(manager.provision().isFailure)
        assertTrue(manager.receive("phone", packet()).isFailure)
        verify(exactly = 0) { keys.createPublicKey() }
        verify(exactly = 0) { disk.write(any()) }
    }

    @Test
    fun `missing key with an active cache is never regenerated`() = runTest {
        manager.receive("phone", packet())
        every { keys.getPublicKey() } returns Result.failure(IOException())
        assertTrue(manager.provision().isFailure)
        assertTrue(manager.receive("phone", packet()).isFailure)
        verify(exactly = 0) { keys.createPublicKey() }
    }

    @Test
    fun `atomic write failure cannot erase the key or report success`() = runTest {
        manager.receive("phone", packet())
        every { disk.write(any()) } returns Result.failure(IOException())
        assertTrue(
            manager.receive(
                "phone", packet(header.copy(revision = 2, isRevocation = true), byteArrayOf()),
            ).isFailure,
        )
        verify(exactly = 0) { keys.deleteKey() }
    }

    @Test
    fun `an unlocked read uses the stored authenticated header`() = runTest {
        manager.receive("phone", packet())
        val context = WearSnapshotProtocol.context(header).getOrThrow()
        every { keys.decrypt(ciphertext, context) } returns Result.success(byteArrayOf(7, 8))
        assertArrayEquals(byteArrayOf(7, 8), manager.read().getOrThrow())
    }

    @Test
    fun `a lock during decryption zeroes and discards plaintext`() = runTest {
        manager.receive("phone", packet())
        val plaintext = byteArrayOf(7, 8)
        every { keys.decrypt(any(), any()) } returns Result.success(plaintext)
        every { security.getStatus() } returnsMany listOf(
            WatchSecurityStatus.UNLOCKED, WatchSecurityStatus.UNLOCKED, WatchSecurityStatus.LOCKED,
        )
        assertTrue(manager.read().isFailure)
        assertArrayEquals(byteArrayOf(0, 0), plaintext)
    }

    @Test
    fun `removing secure lock erases all data and denies reception`() = runTest {
        manager.receive("phone", packet())
        every { security.getStatus() } returns WatchSecurityStatus.SCREEN_LOCK_REQUIRED
        assertTrue(manager.receive("phone", packet(header.copy(revision = 2))).isFailure)
        assertNull(saved)
        verify { keys.deleteKey() }
    }

    @Test
    fun `unverified lock status denies access without destroying the cache`() = runTest {
        manager.receive("phone", packet())
        every { security.getStatus() } returns WatchSecurityStatus.UNVERIFIED
        assertTrue(manager.read().isFailure)
        assertTrue(manager.receive("phone", packet(header.copy(revision = 2))).isFailure)
        verify(exactly = 0) { disk.delete() }
        verify(exactly = 0) { keys.deleteKey() }
    }

    @Test
    fun `malformed stored record and invalid source cannot be accepted`() = runTest {
        saved = byteArrayOf(1)
        assertTrue(manager.provision().isFailure)
        assertTrue(manager.receive("phone", packet()).isFailure)
        saved = null
        assertTrue(manager.receive("", packet()).isFailure)
        assertTrue(manager.receive("p".repeat(129), packet()).isFailure)
        verify(exactly = 0) { disk.write(any()) }
    }

    private fun createManager() = WatchSnapshotManagerImpl(
        security, keys, disk, FakeDispatcherManager(),
    )

    private fun packet(
        value: WearSnapshotHeader = header,
        bytes: ByteArray = ciphertext,
    ): ByteArray = WearSnapshotProtocol.encode(WearSnapshotFrame(value, bytes)).getOrThrow()
}

private const val ENROLLMENT = "22222222-2222-4222-8222-222222222222"
private const val OTHER_ENROLLMENT = "33333333-3333-4333-8333-333333333333"
