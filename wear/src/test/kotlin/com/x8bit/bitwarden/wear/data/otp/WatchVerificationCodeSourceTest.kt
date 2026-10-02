package com.x8bit.bitwarden.wear.data.otp

import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.util.assertCoroutineThrows
import com.bitwarden.core.data.wear.WearOtpEntry
import com.bitwarden.core.data.wear.WearOtpPayloadProtocol
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant

class WatchVerificationCodeSourceTest {
    private val time = Instant.ofEpochSecond(59)
    private val clock = mockk<Clock> { every { instant() } returns time }
    private val security = mockk<WatchSecurityManager> {
        every { getStatus() } returns WatchSecurityStatus.UNLOCKED
    }
    private val snapshots = mockk<WatchSnapshotManager>()
    private val generator = mockk<WatchOtpGenerator> {
        coEvery { generate(any(), time) } returns Result.success(WatchOtpCode("12345678", 60))
    }
    private val entry = WearOtpEntry(
        "item", "GitHub", "user@example.com",
        "otpauth://totp/GitHub?secret=JBSWY3DPEHPK3PXP&digits=8&period=60",
        "https://icons.example.com/github.com/icon.png",
    )
    private val source = WatchVerificationCodeSourceImpl(
        snapshots, security, generator, clock, FakeDispatcherManager(),
    )

    @Test
    fun `offline read preserves all metadata and original OTP input without returning a seed`() =
        runTest {
            val buffers = arrangeSnapshot(listOf(entry))
            val account = WatchVerificationCode(
                entry.id, entry.name, entry.username, entry.iconUrl,
                WatchOtpCode("12345678", 60), time,
            )
            assertEquals(listOf(account), source.read().getOrThrow())
            coVerify(exactly = 1) { generator.generate(entry.secret, time) }
            buffers.forEach { assertArrayEquals(ByteArray(it.size), it) }
            assertFalse(account.toString().contains("12345678"))
            assertFalse(account.toString().contains("user@example.com"))
        }

    @Test
    fun `locked unverified and unsecured watches never read or generate codes`() = runTest {
        WatchSecurityStatus.entries.filter { it != WatchSecurityStatus.UNLOCKED }.forEach {
            every { security.getStatus() } returns it
            assertTrue(source.read().isFailure)
        }
        coVerify(exactly = 0) { snapshots.read() }
        coVerify(exactly = 0) { generator.generate(any(), any()) }
    }

    @Test
    fun `empty storage or revoked snapshot produces an empty list`() = runTest {
        coEvery { snapshots.read() } returns Result.success(null)
        assertEquals(emptyList<WatchVerificationCode>(), source.read().getOrThrow())
        coVerify(exactly = 0) { generator.generate(any(), any()) }
    }

    @Test
    fun `encrypted empty account list is supported`() = runTest {
        val buffers = arrangeSnapshot(emptyList())
        assertEquals(emptyList<WatchVerificationCode>(), source.read().getOrThrow())
        buffers.forEach { assertArrayEquals(ByteArray(it.size), it) }
    }

    @Test
    fun `malformed plaintext returns no partial accounts and erases the buffer`() = runTest {
        val plaintext = byteArrayOf(1, 2, 3)
        coEvery { snapshots.read() } returns Result.success(plaintext)
        assertTrue(source.read().isFailure)
        assertArrayEquals(ByteArray(plaintext.size), plaintext)
        coVerify(exactly = 0) { generator.generate(any(), any()) }
    }

    @Test
    fun `failed protected read is sanitized before reaching presentation`() = runTest {
        coEvery { snapshots.read() } returns Result.failure(IOException("private details"))
        val failure = source.read().exceptionOrNull()
        assertEquals("Offline verification codes unavailable", failure?.message)
        assertNull(failure?.cause)
    }

    @Test
    fun `one invalid OTP keeps the account and does not block other codes`() = runTest {
        val invalid = WearOtpEntry("invalid", "Invalid", null, "invalid-key", null)
        arrangeSnapshot(listOf(invalid, entry))
        coEvery { generator.generate(invalid.secret, time) } returns Result.failure(IOException())
        val accounts = source.read().getOrThrow()
        assertEquals(listOf("invalid", "item"), accounts.map { it.id })
        assertNull(accounts.first().otp)
        assertEquals(WatchOtpCode("12345678", 60), accounts.last().otp)
    }

    @Test
    fun `locking during generation discards codes and wipes the buffers`() = runTest {
        val buffers = arrangeSnapshot(listOf(entry))
        coEvery { generator.generate(any(), any()) } coAnswers {
            every { security.getStatus() } returns WatchSecurityStatus.LOCKED
            Result.success(WatchOtpCode("123456", 30))
        }
        assertTrue(source.read().isFailure)
        buffers.forEach { assertArrayEquals(ByteArray(it.size), it) }
    }

    @Test
    fun `revocation received during generation invalidates the pending display`() = runTest {
        val plaintext = WearOtpPayloadProtocol.encode(listOf(entry)).getOrThrow()
        coEvery { snapshots.read() } returnsMany listOf(
            Result.success(plaintext), Result.success(null),
        )
        assertTrue(source.read().isFailure)
        assertArrayEquals(ByteArray(plaintext.size), plaintext)
    }

    @Test
    fun `replacement received during generation invalidates old accounts and wipes both buffers`() =
        runTest {
            val old = WearOtpPayloadProtocol.encode(listOf(entry)).getOrThrow()
            val replacement = WearOtpPayloadProtocol.encode(listOf(
                WearOtpEntry(entry.id, "New", entry.username, entry.secret, entry.iconUrl),
            ))
                .getOrThrow()
            coEvery { snapshots.read() } returnsMany listOf(
                Result.success(old), Result.success(replacement),
            )
            assertTrue(source.read().isFailure)
            listOf(old, replacement).forEach { assertArrayEquals(ByteArray(it.size), it) }
        }

    @Test
    fun `expired code at the end of generation is not published`() = runTest {
        arrangeSnapshot(listOf(entry))
        every { clock.instant() } returnsMany listOf(time, Instant.ofEpochSecond(60))
        assertTrue(source.read().isFailure)
    }

    @Test
    fun `clock moving backward during generation invalidates generated codes`() = runTest {
        arrangeSnapshot(listOf(entry))
        every { clock.instant() } returnsMany listOf(time, Instant.ofEpochSecond(58))
        assertTrue(source.read().isFailure)
    }

    @Test
    fun `cancelled generation propagates and erases plaintext`() {
        val buffers = arrangeSnapshot(listOf(entry))
        coEvery { generator.generate(any(), any()) } throws CancellationException()
        assertCoroutineThrows<CancellationException> { source.read() }
        assertArrayEquals(ByteArray(buffers.first().size), buffers.first())
    }

    private fun arrangeSnapshot(entries: List<WearOtpEntry>): List<ByteArray> {
        val buffers = List(2) { WearOtpPayloadProtocol.encode(entries).getOrThrow() }
        coEvery { snapshots.read() } returnsMany buffers.map { Result.success(it) }
        return buffers
    }
}
