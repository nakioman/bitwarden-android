package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.UuidManager
import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.wear.WearOtpEntry
import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotHeader
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.bitwarden.data.datasource.disk.base.FakeSharedPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.util.Base64

class WearConsentReenrollmentTest {
    private val previousKey = byteArrayOf(1, 2, 3)
    private val newKey = byteArrayOf(4, 5, 6)
    private val consent = WearConsent(
        "user", "watch", "Watch", false, Base64.getEncoder().encodeToString(previousKey),
    )
    private val disk = WearConsentDiskSourceImpl(FakeSharedPreferences(), Json)
    private val outbox = WearOutboxManagerImpl(
        WearOutboxDiskSourceImpl(FakeSharedPreferences(), Json), FakeDispatcherManager(),
    )
    private val accountSource = mockk<WearOtpAccountSource> {
        every { getAccount("user") } returns
            com.x8bit.bitwarden.data.auth.repository.model.UserState.EMPTY_ACCOUNT.copy(
                userId = "user", isLoggedIn = true, isVaultUnlocked = true,
            )
    }
    private val devices = mockk<WearDeviceSource> {
        every { isSupported } returns true
        coEvery { getDevices() } returns Result.success(listOf(WearDevice("watch", "Watch")))
    }
    private val keys = mockk<WearKeySource> {
        coEvery { getPublicKey("watch") } returns Result.success(newKey)
    }
    private val manager = WearConsentManagerImpl(
        devices, disk, accountSource, FakeDispatcherManager(), keys, outbox,
    )
    private val previousHeader = WearSnapshotHeader(
        "22222222-2222-4222-8222-222222222222", 1,
        WearSnapshotProtocol.recipientKeyId(previousKey),
    )

    @Test
    fun `explicit enable after exact revocation receipt pins a fresh key`() = runTest {
        prepareWithdrawal(acknowledged = true)
        assertTrue(manager.setConsent("user", "watch", true) is WearConsentResult.Success)
        assertEquals(consent.copy(
            isAuthorized = true, publicKey = Base64.getEncoder().encodeToString(newKey),
        ), disk.getConsents().getOrThrow().single())
    }

    @Test
    fun `unacknowledged revocation prevents key exchange and reauthorization`() = runTest {
        prepareWithdrawal(acknowledged = false)
        assertEquals(WearConsentResult.Error, manager.setConsent("user", "watch", true))
        coVerify(exactly = 0) { keys.getPublicKey(any()) }
        assertEquals(consent, disk.getConsents().getOrThrow().single())
    }

    @Test
    fun `same revoked key cannot be authorized again even after receipt`() = runTest {
        prepareWithdrawal(acknowledged = true)
        coEvery { keys.getPublicKey("watch") } returns Result.success(previousKey)
        assertEquals(WearConsentResult.Error, manager.setConsent("user", "watch", true))
        assertEquals(consent, disk.getConsents().getOrThrow().single())
    }

    @Test
    fun `withdrawn consent with active enrollment must wait for durable revocation`() = runTest {
        disk.compareAndSetConsents(emptyList(), listOf(consent)).getOrThrow()
        enqueue(previousHeader, byteArrayOf(1))
        assertEquals(WearConsentResult.Error, manager.setConsent("user", "watch", true))
        coVerify(exactly = 0) { keys.getPublicKey(any()) }
    }

    @Test
    fun `refresh of withdrawn consent never requests or pins a new key`() = runTest {
        prepareWithdrawal(acknowledged = true)
        assertTrue(manager.getDevices("user") is WearConsentResult.Success)
        coVerify(exactly = 0) { keys.getPublicKey(any()) }
        assertEquals(consent, disk.getConsents().getOrThrow().single())
    }

    @Test
    fun `changed key in an authorized enrollment cannot silently replace its pin`() = runTest {
        val authorized = consent.copy(isAuthorized = true)
        disk.compareAndSetConsents(emptyList(), listOf(authorized)).getOrThrow()
        enqueue(previousHeader, byteArrayOf(1))
        assertEquals(WearConsentResult.Error, manager.setConsent("user", "watch", true))
        assertEquals(authorized, disk.getConsents().getOrThrow().single())
    }

    @Test
    fun `unreadable outbox prevents new authorization`() = runTest {
        val unavailable = mockk<WearOutboxManager> {
            coEvery { getRecord(any()) } returns Result.failure(IOException())
        }
        val guarded = WearConsentManagerImpl(
            devices, disk, accountSource, FakeDispatcherManager(), keys, unavailable,
        )
        assertEquals(WearConsentResult.Error, guarded.setConsent("user", "watch", true))
        coVerify(exactly = 0) { keys.getPublicKey(any()) }
    }

    @Test
    fun `outbox change during key exchange prevents reauthorization`() = runTest {
        prepareWithdrawal(acknowledged = true)
        coEvery { keys.getPublicKey("watch") } coAnswers {
            outbox.reserveRevision("watch").getOrThrow()
            Result.success(newKey)
        }
        assertEquals(WearConsentResult.Error, manager.setConsent("user", "watch", true))
        assertEquals(consent, disk.getConsents().getOrThrow().single())
    }

    @Test
    fun `another account active enrollment cannot be replaced by new consent`() = runTest {
        enqueue(previousHeader, byteArrayOf(1), userId = "other")
        assertEquals(WearConsentResult.Error, manager.setConsent("user", "watch", true))
        coVerify(exactly = 0) { keys.getPublicKey(any()) }
        assertTrue(disk.getConsents().getOrThrow().isEmpty())
    }

    @Test
    fun `fresh authorization prepares a new enrollment with a higher revision`() = runTest {
        prepareWithdrawal(acknowledged = true)
        val recipient = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair().public.encoded
        coEvery { keys.getPublicKey("watch") } returns Result.success(recipient)
        assertTrue(manager.setConsent("user", "watch", true) is WearConsentResult.Success)
        val otpSource = mockk<WearOtpSource> {
            coEvery { getEntries("user", "watch") } returns
                Result.success(emptyList<WearOtpEntry>())
        }
        val uuid = mockk<UuidManager> {
            every { generateUuid() } returns "33333333-3333-4333-8333-333333333333"
        }
        val preparer = WearSnapshotPreparerImpl(
            accountSource, disk, otpSource, outbox, uuid, FakeDispatcherManager(),
        )
        assertTrue(preparer.prepareSnapshot("user", "watch").isSuccess)
        val frame = WearSnapshotProtocol.decode(outbox.getPending().getOrThrow().single().packet)
            .getOrThrow()
        assertNotEquals(previousHeader.enrollmentId, frame.header.enrollmentId)
        assertNotEquals(previousHeader.recipientKeyId, frame.header.recipientKeyId)
        assertEquals(WearSnapshotProtocol.recipientKeyId(recipient), frame.header.recipientKeyId)
        assertEquals(3L, frame.header.revision)
    }

    private suspend fun prepareWithdrawal(acknowledged: Boolean) {
        disk.compareAndSetConsents(emptyList(), listOf(consent)).getOrThrow()
        enqueue(previousHeader, byteArrayOf(1))
        val revoke = enqueue(previousHeader.copy(revision = 2, isRevocation = true), byteArrayOf())
        if (acknowledged) outbox.acknowledge("watch", revoke).getOrThrow()
    }

    private suspend fun enqueue(
        header: WearSnapshotHeader,
        ciphertext: ByteArray,
        userId: String = "user",
    ): ByteArray {
        assertEquals(header.revision, outbox.reserveRevision("watch").getOrThrow())
        val packet = WearSnapshotProtocol.encode(WearSnapshotFrame(header, ciphertext)).getOrThrow()
        outbox.enqueue(userId, "watch", packet).getOrThrow()
        return packet
    }
}
