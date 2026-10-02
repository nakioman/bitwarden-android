package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.UuidManager
import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.wear.WearOtpEntry
import com.bitwarden.core.data.wear.WearOtpPayloadProtocol
import com.bitwarden.core.data.wear.WearPayloadCipher
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.bitwarden.data.datasource.disk.base.FakeSharedPreferences
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.ProviderException
import java.util.Base64

class WearSnapshotPreparerTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        .generateKeyPair()
    private val account = UserState.EMPTY_ACCOUNT.copy(
        userId = "user", isLoggedIn = true, isVaultUnlocked = true, isPremium = true,
    )
    private val consent = WearConsent(
        "user", "watch", "Watch", true, Base64.getEncoder().encodeToString(keys.public.encoded),
    )
    private val accountSource = mockk<WearOtpAccountSource>()
    private val consentSource = mockk<WearConsentDiskSource>()
    private val otpSource = mockk<WearOtpSource>()
    private val uuids = mockk<UuidManager>()
    private val disk = WearOutboxDiskSourceImpl(FakeSharedPreferences(), Json)
    private val outbox = WearOutboxManagerImpl(disk, FakeDispatcherManager())
    private val entries = listOf(WearOtpEntry(
        "login", "Example", "user@example.test",
        "otpauth://totp/Test?secret=JBSWY3DPEHPK3PXP&digits=8&period=60&algorithm=SHA512",
        "https://icons.example.test/example.test/icon.png",
    ))
    private val preparer = createPreparer(outbox)

    @BeforeEach
    fun setUp() {
        every { accountSource.getAccount("user") } returns account
        every { consentSource.getConsents() } returns Result.success(listOf(consent))
        every { uuids.generateUuid() } returnsMany listOf(FIRST_ENROLLMENT, SECOND_ENROLLMENT)
        coEvery { otpSource.getEntries("user", "watch") } returns Result.success(entries)
    }

    @Test
    fun `prepares complete ciphertext recoverable only with the pinned recipient key`() = runTest {
        assertTrue(preparer.prepareSnapshot("user", "watch").isSuccess)
        val pending = outbox.getPending().getOrThrow().single()
        assertEquals("user", pending.userId)
        val frame = WearSnapshotProtocol.decode(pending.packet).getOrThrow()
        assertEquals(FIRST_ENROLLMENT, frame.header.enrollmentId)
        assertEquals(1L, frame.header.revision)
        assertEquals(
            WearSnapshotProtocol.recipientKeyId(keys.public.encoded), frame.header.recipientKeyId,
        )
        val plaintext = WearPayloadCipher.decrypt(
            keys.private, frame.ciphertext, WearSnapshotProtocol.context(frame.header).getOrThrow(),
        ).getOrThrow()
        try {
            val decoded = WearOtpPayloadProtocol.decode(plaintext).getOrThrow().single()
            assertEquals(entries.single().secret, decoded.secret)
            assertEquals(entries.single().name, decoded.name)
            assertEquals(entries.single().username, decoded.username)
            assertEquals(entries.single().iconUrl, decoded.iconUrl)
        } finally {
            plaintext.fill(0)
        }
        val serialized = Json.encodeToString(disk.read().getOrThrow())
        assertFalse(serialized.contains("JBSWY3DPEHPK3PXP"))
        assertFalse(serialized.contains("user@example.test"))
    }

    @Test
    fun `updates reuse enrollment with increasing revisions across preparer reconstruction`() =
        runTest {
            preparer.prepareSnapshot("user", "watch").getOrThrow()
            createPreparer(outbox).prepareSnapshot("user", "watch").getOrThrow()
            val header = outbox.getRecord("watch").getOrThrow()?.lastHeader
            assertEquals(FIRST_ENROLLMENT, header?.enrollmentId)
            assertEquals(2L, header?.revision)
        }

    @Test
    fun `locked account and missing consent cannot read OTPs or reserve revisions`() = runTest {
        every { accountSource.getAccount("user") } returns null
        assertTrue(preparer.prepareSnapshot("user", "watch").isFailure)
        every { accountSource.getAccount("user") } returns account
        every { consentSource.getConsents() } returns Result.success(emptyList())
        assertTrue(preparer.prepareSnapshot("user", "watch").isFailure)
        coVerify(exactly = 0) { otpSource.getEntries(any(), any()) }
        assertTrue(disk.read().getOrThrow().isEmpty())
    }

    @Test
    fun `invalid pinned keys are rejected before extracting OTP entries`() = runTest {
        for (key in listOf("not-base64!", "YQ==", "a".repeat(2049))) {
            every { consentSource.getConsents() } returns Result.success(listOf(
                consent.copy(publicKey = key),
            ))
            assertTrue(preparer.prepareSnapshot("user", "watch").isFailure)
        }
        coVerify(exactly = 0) { otpSource.getEntries(any(), any()) }
        assertTrue(disk.read().getOrThrow().isEmpty())
    }

    @Test
    fun `account change or revoked consent during OTP read prevents queueing`() = runTest {
        coEvery { otpSource.getEntries("user", "watch") } coAnswers {
            every { accountSource.getAccount("user") } returns null
            Result.success(entries)
        }
        assertTrue(preparer.prepareSnapshot("user", "watch").isFailure)
        every { accountSource.getAccount("user") } returns account
        coEvery { otpSource.getEntries("user", "watch") } coAnswers {
            every { consentSource.getConsents() } returns Result.success(listOf(
                consent.copy(isAuthorized = false),
            ))
            Result.success(entries)
        }
        assertTrue(preparer.prepareSnapshot("user", "watch").isFailure)
        assertTrue(outbox.getPending().getOrThrow().isEmpty())
    }

    @Test
    fun `changed active key or account cannot replace an existing enrollment`() = runTest {
        preparer.prepareSnapshot("user", "watch").getOrThrow()
        val original = outbox.getRecord("watch").getOrThrow()
        val otherKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair()
        every { consentSource.getConsents() } returns Result.success(listOf(consent.copy(
            publicKey = Base64.getEncoder().encodeToString(otherKeys.public.encoded),
        )))
        assertTrue(preparer.prepareSnapshot("user", "watch").isFailure)
        every { accountSource.getAccount("other") } returns account.copy(userId = "other")
        every { consentSource.getConsents() } returns Result.success(listOf(
            consent.copy(userId = "other"),
        ))
        assertTrue(preparer.prepareSnapshot("other", "watch").isFailure)
        assertEquals(original, outbox.getRecord("watch").getOrThrow())
        coVerify(exactly = 1) { otpSource.getEntries(any(), any()) }
    }

    @Test
    fun `failed vault extraction never queues a partial snapshot`() = runTest {
        coEvery { otpSource.getEntries("user", "watch") } returns Result.failure(IOException())
        assertTrue(preparer.prepareSnapshot("user", "watch").isFailure)
        assertTrue(outbox.getPending().getOrThrow().isEmpty())
    }

    @Test
    fun `account lock after encryption burns revision without queueing ciphertext`() = runTest {
        every { accountSource.getAccount("user") } returnsMany listOf(
            account, account, account, null,
        )
        assertTrue(preparer.prepareSnapshot("user", "watch").isFailure)
        val record = outbox.getRecord("watch").getOrThrow()
        assertEquals(1L, record?.reservedRevision)
        assertEquals(null, record?.lastHeader)
        assertTrue(outbox.getPending().getOrThrow().isEmpty())
    }

    @Test
    fun `empty eligible list produces a valid encrypted empty snapshot`() =
        runTest {
            coEvery { otpSource.getEntries("user", "watch") } returns Result.success(emptyList())
            preparer.prepareSnapshot("user", "watch").getOrThrow()
            val pending = outbox.getPending().getOrThrow().single().packet
            val frame = WearSnapshotProtocol.decode(pending).getOrThrow()
            val plaintext = WearPayloadCipher.decrypt(
                keys.private, frame.ciphertext,
                WearSnapshotProtocol.context(frame.header).getOrThrow(),
            ).getOrThrow()
            try {
                assertTrue(WearOtpPayloadProtocol.decode(plaintext).getOrThrow().isEmpty())
            } finally {
                plaintext.fill(0)
            }
        }

    @Test
    fun `revocation requires recorded owner but works with locked vault and revoked consent`() =
        runTest {
            preparer.prepareSnapshot("user", "watch").getOrThrow()
            assertTrue(preparer.prepareRevocation("other", "watch").isFailure)
            every { accountSource.getAccount("user") } returns null
            every { consentSource.getConsents() } returns Result.success(listOf(
                consent.copy(isAuthorized = false),
            ))
            assertTrue(preparer.prepareRevocation("user", "watch").isSuccess)
            val pending = outbox.getPending().getOrThrow().single()
            val frame = WearSnapshotProtocol.decode(pending.packet).getOrThrow()
            assertTrue(frame.header.isRevocation)
            assertTrue(frame.ciphertext.isEmpty())
            val original = outbox.getRecord("watch").getOrThrow()
            assertTrue(preparer.prepareRevocation("user", "watch").isSuccess)
            assertEquals(original, outbox.getRecord("watch").getOrThrow())
        }

    @Test
    fun `new enrollment requires revocation receipt and a different explicitly pinned key`() =
        runTest {
            preparer.prepareSnapshot("user", "watch").getOrThrow()
            preparer.prepareRevocation("user", "watch").getOrThrow()
            assertTrue(preparer.prepareSnapshot("user", "watch").isFailure)
            val revoke = outbox.getPending().getOrThrow().single().packet
            outbox.acknowledge("watch", revoke).getOrThrow()
            assertTrue(preparer.prepareSnapshot("user", "watch").isFailure)
            val otherKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                .generateKeyPair()
            every { consentSource.getConsents() } returns Result.success(listOf(consent.copy(
                publicKey = Base64.getEncoder().encodeToString(otherKeys.public.encoded),
            )))
            assertTrue(preparer.prepareSnapshot("user", "watch").isSuccess)
            assertEquals(SECOND_ENROLLMENT, outbox.getRecord("watch").getOrThrow()
                ?.lastHeader?.enrollmentId)
        }

    @Test
    fun `caller cancellation propagates without queueing secrets`() = runTest {
        coEvery { otpSource.getEntries("user", "watch") } throws CancellationException()
        var canceled = false
        try {
            preparer.prepareSnapshot("user", "watch")
        } catch (_: CancellationException) {
            canceled = true
        }
        assertTrue(canceled)
        assertTrue(outbox.getPending().getOrThrow().isEmpty())
    }

    @Test
    fun `crypto provider failure is sanitized and never queues an incomplete snapshot`() = runTest {
        coEvery { otpSource.getEntries("user", "watch") } throws ProviderException("private-seed")
        val result = preparer.prepareSnapshot("user", "watch")
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull().toString().contains("private-seed"))
        assertTrue(outbox.getPending().getOrThrow().isEmpty())
    }

    @Test
    fun `failed ciphertext commit never reports successful preparation`() = runTest {
        val rejecting = mockk<WearOutboxManager>()
        coEvery { rejecting.getRecord("watch") } returns Result.success(null)
        coEvery { rejecting.reserveRevision("watch") } returns Result.success(1)
        coEvery { rejecting.enqueue("user", "watch", any()) } returns Result.failure(IOException())
        assertTrue(createPreparer(rejecting).prepareSnapshot("user", "watch").isFailure)
        coVerify(exactly = 1) { rejecting.enqueue("user", "watch", any()) }
    }

    @Test
    fun `unreadable outbox fails closed before vault extraction`() = runTest {
        val brokenDisk = mockk<WearOutboxDiskSource> {
            every { read() } returns Result.failure(IOException())
        }
        val broken = createPreparer(WearOutboxManagerImpl(brokenDisk, FakeDispatcherManager()))
        assertTrue(broken.prepareSnapshot("user", "watch").isFailure)
        assertTrue(broken.prepareRevocation("user", "watch").isFailure)
        coVerify(exactly = 0) { otpSource.getEntries(any(), any()) }
    }

    private fun createPreparer(queue: WearOutboxManager): WearSnapshotPreparer =
        WearSnapshotPreparerImpl(
            accountSource, consentSource, otpSource, queue, uuids, FakeDispatcherManager(),
        )
}

private const val FIRST_ENROLLMENT = "22222222-2222-4222-8222-222222222222"
private const val SECOND_ENROLLMENT = "33333333-3333-4333-8333-333333333333"
