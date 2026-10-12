package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.wear.WearOtpEntry
import com.bitwarden.network.model.CipherRepromptTypeJson
import com.bitwarden.network.model.SyncResponseJson
import com.bitwarden.network.model.createMockCipher
import com.bitwarden.vault.CipherRepromptType
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockCipherView
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

class WearOtpSourceTest {
    private val account = UserState.EMPTY_ACCOUNT.copy(
        userId = "user", isLoggedIn = true, isVaultUnlocked = true, isPremium = true,
    )
    private val users = MutableStateFlow<UserState?>(UserState("user", listOf(account)))
    private val auth = mockk<AuthRepository> { every { userStateFlow } returns users }
    private val locks = mockk<VaultLockManager>()
    private val disk = mockk<VaultDiskSource>()
    private val sdk = mockk<VaultSdkSource>()
    private val icons = mockk<WearOtpIconSource>()
    private val source = WearOtpSourceImpl(
        disk, sdk, WearOtpAccountSourceImpl(auth, locks), icons, FakeDispatcherManager(),
    )

    @BeforeEach
    fun setUp() {
        every { locks.isVaultUnlocked("user") } returns true
        every { icons.getIconUrl(any()) } returns "https://icons.example/github.com/icon.png"
        coEvery { disk.getLoginCiphers("user") } returns listOf(encrypted(1))
        coEvery { sdk.decryptCipher("user", any()) } returns Result.success(createMockCipherView(1))
    }

    @Test
    fun `canceled SDK read propagates cancellation without returning entries`() = runTest {
        coEvery { sdk.decryptCipher("user", any()) } throws CancellationException()
        var canceled = false
        try {
            source.getEntries("user")
        } catch (_: CancellationException) {
            canceled = true
        }
        assertTrue(canceled)
    }

    @Test
    fun `prepares all OTPs regardless of favorites preserving the original format`() = runTest {
        coEvery { disk.getLoginCiphers("user") } returns (1..9).map { encrypted(it) }
        coEvery { sdk.decryptCipher("user", any()) } coAnswers {
            val cipher = secondArg<com.bitwarden.vault.Cipher>()
            val number = requireNotNull(cipher.id).substringAfterLast('-').toInt()
            Result.success(createMockCipherView(number).copy(favorite = number == 1))
        }
        val entries = source.getEntries("user").getOrThrow()
        assertEquals((1..9).map { "mockId-$it" }, entries.map { it.id })
        assertEquals((1..9).map { "mockTotp-$it" }, entries.map { it.secret })
        assertEquals("mockName-1", entries.first().name)
        assertEquals("mockUsername-1", entries.first().username)
        assertEquals("https://icons.example/github.com/icon.png", entries.first().iconUrl)
    }

    @Test
    fun `locked inactive and logged out accounts cannot read vault data`() = runTest {
        every { locks.isVaultUnlocked("user") } returns false
        assertTrue(source.getEntries("user").isFailure)
        every { locks.isVaultUnlocked("user") } returns true
        assertTrue(source.getEntries("other").isFailure)
        users.value = UserState("user", listOf(account.copy(isLoggedIn = false)))
        assertTrue(source.getEntries("user").isFailure)
        users.value = null
        assertTrue(source.getEntries("user").isFailure)
        coVerify(exactly = 0) { disk.getLoginCiphers(any()) }
    }

    @Test
    fun `deleted archived and reprompt items are excluded before decryption`() = runTest {
        coEvery { disk.getLoginCiphers("user") } returns listOf(
            encrypted(1).copy(deletedDate = Instant.EPOCH),
            encrypted(2).copy(archivedDate = Instant.EPOCH),
            encrypted(3).copy(reprompt = CipherRepromptTypeJson.PASSWORD),
            encrypted(4).copy(login = null),
        )
        assertEquals(emptyList<WearOtpEntry>(), source.getEntries("user").getOrThrow())
        coVerify(exactly = 0) { sdk.decryptCipher(any(), any()) }
    }

    @Test
    fun `decrypted eligibility is checked independently of encrypted metadata`() = runTest {
        for (view in listOf(
            createMockCipherView(1, isDeleted = true),
            createMockCipherView(1, isArchived = true),
            createMockCipherView(1, repromptType = CipherRepromptType.PASSWORD),
            createMockCipherView(1, totp = " "),
        )) {
            coEvery { sdk.decryptCipher("user", any()) } returns Result.success(view)
            assertEquals(emptyList<WearOtpEntry>(), source.getEntries("user").getOrThrow())
        }
    }

    @Test
    fun `non premium accounts include only organization TOTP enabled entries`() = runTest {
        users.value = UserState("user", listOf(account.copy(isPremium = false)))
        assertTrue(source.getEntries("user").getOrThrow().isEmpty())
        coEvery { sdk.decryptCipher("user", any()) } returns Result.success(
            createMockCipherView(1).copy(organizationUseTotp = true),
        )
        assertEquals(1, source.getEntries("user").getOrThrow().size)
    }

    @Test
    fun `account switch while disk reads cancels the entire preparation`() = runTest {
        coEvery { disk.getLoginCiphers("user") } coAnswers {
            users.value = UserState("other", listOf(account.copy(userId = "other")))
            listOf(encrypted(1))
        }
        assertTrue(source.getEntries("user").isFailure)
        coVerify(exactly = 0) { sdk.decryptCipher(any(), any()) }
    }

    @Test
    fun `lock during decryption discards prepared data`() = runTest {
        coEvery { sdk.decryptCipher("user", any()) } coAnswers {
            every { locks.isVaultUnlocked("user") } returns false
            Result.success(createMockCipherView(1))
        }
        assertTrue(source.getEntries("user").isFailure)
    }

    @Test
    fun `undecryptable items are skipped without dropping the others`() = runTest {
        coEvery { disk.getLoginCiphers("user") } returns listOf(encrypted(1), encrypted(2))
        coEvery { sdk.decryptCipher("user", any()) } returnsMany listOf(
            Result.success(createMockCipherView(1)), Result.failure(IOException("sdk failure")),
        )
        assertEquals(listOf("mockId-1"), source.getEntries("user").getOrThrow().map { it.id })
    }

    @Test
    fun `missing conflicting or duplicate IDs fail the complete preparation`() = runTest {
        for (id in listOf(null, "", "different")) {
            coEvery { sdk.decryptCipher("user", any()) } returns Result.success(
                createMockCipherView(1).copy(id = id),
            )
            assertTrue(source.getEntries("user").isFailure)
        }
        coEvery { disk.getLoginCiphers("user") } returns listOf(encrypted(1), encrypted(1))
        coEvery { sdk.decryptCipher("user", any()) } returns Result.success(createMockCipherView(1))
        assertTrue(source.getEntries("user").isFailure)
    }

    @Test
    fun `disk errors return a sanitized failure`() = runTest {
        coEvery { disk.getLoginCiphers("user") } throws IOException("sensitive detail")
        val result = source.getEntries("user")
        assertEquals("Wear OTP preparation unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `OTP entries do not expose any fields in string representations`() {
        assertEquals(
            "WearOtpEntry(<redacted>)",
            WearOtpEntry("id", "name", "user", "secret").toString(),
        )
    }

    private fun encrypted(number: Int): SyncResponseJson.Cipher =
        createMockCipher(number, deletedDate = null, archivedDate = null)
}
