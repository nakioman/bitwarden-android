package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotHeader
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.bitwarden.data.datasource.disk.base.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class WearOutboxManagerTest {
    private val disk = WearOutboxDiskSourceImpl(FakeSharedPreferences(), Json)
    private val manager = WearOutboxManagerImpl(disk, FakeDispatcherManager())

    @Test
    fun `reserved revisions survive reconstruction and failed preparation`() = runTest {
        assertEquals(1L, manager.reserveRevision("watch").getOrThrow())
        val recreated = WearOutboxManagerImpl(disk, FakeDispatcherManager())
        assertEquals(2L, recreated.reserveRevision("watch").getOrThrow())
        assertTrue(recreated.getPending().getOrThrow().isEmpty())
    }

    @Test
    fun `concurrent reservations never reuse revisions`() = runTest {
        val revisions = (1..20).map {
            async { manager.reserveRevision("watch").getOrThrow() }
        }.awaitAll()
        assertEquals((1L..20L).toList(), revisions.sorted())
    }

    @Test
    fun `pending ciphertext survives reconstruction without unlocking the vault`() = runTest {
        val packet = packet(manager.reserveRevision("watch").getOrThrow())
        assertTrue(manager.enqueue("user", "watch", packet).isSuccess)
        val pending = WearOutboxManagerImpl(disk, FakeDispatcherManager())
            .getPending().getOrThrow().single()
        assertEquals("user", pending.userId)
        assertEquals("watch", pending.deviceId)
        assertArrayEquals(packet, pending.packet)
    }

    @Test
    fun `a superseded reservation cannot enqueue an older command`() = runTest {
        val old = manager.reserveRevision("watch").getOrThrow()
        val latest = manager.reserveRevision("watch").getOrThrow()
        assertTrue(manager.enqueue("user", "watch", packet(old)).isFailure)
        assertTrue(manager.enqueue("user", "watch", packet(latest)).isSuccess)
    }

    @Test
    fun `safe duplicates do not resurrect acknowledged packets`() = runTest {
        val packet = packet(manager.reserveRevision("watch").getOrThrow())
        assertTrue(manager.enqueue("user", "watch", packet).isSuccess)
        assertTrue(manager.enqueue("user", "watch", packet).isSuccess)
        assertTrue(manager.acknowledge("watch", packet).isSuccess)
        assertTrue(manager.enqueue("user", "watch", packet).isSuccess)
        assertTrue(manager.getPending().getOrThrow().isEmpty())
        assertEquals(2L, manager.reserveRevision("watch").getOrThrow())
    }

    @Test
    fun `revocation replaces offline snapshot ciphertext and remains pending`() = runTest {
        val snapshot = packet(manager.reserveRevision("watch").getOrThrow())
        manager.enqueue("user", "watch", snapshot).getOrThrow()
        val revoke = packet(manager.reserveRevision("watch").getOrThrow(), revocation = true)
        manager.enqueue("user", "watch", revoke).getOrThrow()
        val pending = manager.getPending().getOrThrow().single()
        assertArrayEquals(revoke, pending.packet)
        assertTrue(WearSnapshotProtocol.decode(pending.packet).getOrThrow().ciphertext.isEmpty())
        assertTrue(manager.acknowledge("watch", snapshot).isFailure)
        assertArrayEquals(revoke, manager.getPending().getOrThrow().single().packet)
    }

    @Test
    fun `new enrollment waits for acknowledgment of the revocation`() = runTest {
        manager.enqueue(
            "user", "watch", packet(manager.reserveRevision("watch").getOrThrow()),
        ).getOrThrow()
        val revoke = packet(manager.reserveRevision("watch").getOrThrow(), revocation = true)
        manager.enqueue("user", "watch", revoke).getOrThrow()
        val new = packet(
            manager.reserveRevision("watch").getOrThrow(), enrollment = SECOND_ID,
            key = "b".repeat(64),
        )
        assertTrue(manager.enqueue("other", "watch", new).isFailure)
        manager.acknowledge("watch", revoke).getOrThrow()
        assertTrue(manager.enqueue("other", "watch", new).isSuccess)
    }

    @Test
    fun `active enrollment cannot be replaced or transferred to another account`() = runTest {
        manager.enqueue(
            "user", "watch", packet(manager.reserveRevision("watch").getOrThrow()),
        ).getOrThrow()
        val revision = manager.reserveRevision("watch").getOrThrow()
        assertTrue(manager.enqueue("other", "watch", packet(revision)).isFailure)
        assertTrue(manager.enqueue("user", "watch", packet(revision, SECOND_ID)).isFailure)
        assertTrue(
            manager.enqueue("user", "watch", packet(revision, key = "b".repeat(64))).isFailure,
        )
    }

    @Test
    fun `conflicting packet with the same revision is rejected`() = runTest {
        val revision = manager.reserveRevision("watch").getOrThrow()
        manager.enqueue("user", "watch", packet(revision)).getOrThrow()
        assertTrue(
            manager.enqueue("user", "watch", packet(revision, body = byteArrayOf(2))).isFailure,
        )
    }

    @Test
    fun `wrong acknowledgment cannot erase another devices pending packet`() = runTest {
        val first = packet(manager.reserveRevision("first").getOrThrow())
        val second = packet(manager.reserveRevision("second").getOrThrow(), body = byteArrayOf(2))
        manager.enqueue("user", "first", first).getOrThrow()
        manager.enqueue("user", "second", second).getOrThrow()
        assertTrue(manager.acknowledge("second", first).isFailure)
        assertEquals(2, manager.getPending().getOrThrow().size)
    }

    @Test
    fun `unreadable ledger never resets a revision counter`() = runTest {
        val unreadable = mockk<WearOutboxDiskSource> {
            every { read() } returns Result.failure(IOException())
        }
        val broken = WearOutboxManagerImpl(unreadable, FakeDispatcherManager())
        assertTrue(broken.reserveRevision("watch").isFailure)
        assertTrue(broken.getPending().isFailure)
    }

    @Test
    fun `write failure is not reported as a successful reservation`() = runTest {
        val failing = mockk<WearOutboxDiskSource> {
            every { read() } returns Result.success(emptyList())
            every { write(any()) } returns Result.failure(IOException())
        }
        assertTrue(
            WearOutboxManagerImpl(failing, FakeDispatcherManager())
                .reserveRevision("watch").isFailure,
        )
    }

    @Test
    fun `failed replacement or acknowledgment preserves the previous pending command`() = runTest {
        val first = packet(manager.reserveRevision("watch").getOrThrow())
        manager.enqueue("user", "watch", first).getOrThrow()
        val second = packet(manager.reserveRevision("watch").getOrThrow())
        val failing = mockk<WearOutboxDiskSource> {
            every { read() } answers { disk.read() }
            every { write(any()) } returns Result.failure(IOException())
        }
        val broken = WearOutboxManagerImpl(failing, FakeDispatcherManager())
        assertTrue(broken.enqueue("user", "watch", second).isFailure)
        assertTrue(broken.acknowledge("watch", first).isFailure)
        assertArrayEquals(first, manager.getPending().getOrThrow().single().packet)
    }

    @Test
    fun `overflow malformed packets and missing reservations fail closed`() = runTest {
        disk.write(listOf(WearOutboxRecord("watch", Long.MAX_VALUE))).getOrThrow()
        assertTrue(manager.reserveRevision("watch").isFailure)
        assertTrue(manager.reserveRevision("").isFailure)
        assertTrue(manager.enqueue("user", "watch", byteArrayOf()).isFailure)
        assertTrue(manager.enqueue("user", "missing", packet(1)).isFailure)
    }

    private fun packet(
        revision: Long,
        enrollment: String = FIRST_ID,
        revocation: Boolean = false,
        key: String = "a".repeat(64),
        body: ByteArray = byteArrayOf(1),
    ): ByteArray = WearSnapshotProtocol.encode(
        WearSnapshotFrame(
            WearSnapshotHeader(enrollment, revision, key, revocation),
            if (revocation) byteArrayOf() else body,
        ),
    ).getOrThrow()
}

private const val FIRST_ID = "00000000-0000-0000-0000-000000000001"
private const val SECOND_ID = "00000000-0000-0000-0000-000000000002"
