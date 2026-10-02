package com.x8bit.bitwarden.wear.data.otp

import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.util.assertCoroutineThrows
import com.bitwarden.sdk.BitwardenException
import com.bitwarden.sdk.VaultClientInterface
import com.bitwarden.vault.TotpException
import com.bitwarden.vault.TotpResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class WatchOtpGeneratorTest {
    private val time = Instant.ofEpochSecond(59)
    private val client = mockk<VaultClientInterface>()
    private val provider = mockk<() -> VaultClientInterface> {
        every { this@mockk.invoke() } returns client
    }
    private val generator = WatchOtpGeneratorImpl(FakeDispatcherManager(), provider)

    @Test
    fun `raw key otpauth and Steam inputs reach the SDK unchanged`() = runTest {
        val inputs = listOf(
            "JBSWY3DPEHPK3PXP",
            "otpauth://totp/Test?secret=JBSWY3DPEHPK3PXP&digits=8&period=60&algorithm=SHA256",
            "steam://JBSWY3DPEHPK3PXP",
        )
        val responses = listOf(
            TotpResponse("123456", 30u), TotpResponse("12345678", 60u), TotpResponse("ABCDE", 30u),
        )
        inputs.zip(responses).forEach { (input, response) ->
            every { client.generateTotp(input, time) } returns response
            assertEquals(
                WatchOtpCode(response.code, response.period.toInt()),
                generator.generate(input, time).getOrThrow(),
            )
        }
        verify(exactly = 1) { provider.invoke() }
    }

    @Test
    fun `SDK failure does not expose the seed through its message or cause`() = runTest {
        every { client.generateTotp(any(), any()) } throws
            BitwardenException.Totp(TotpException.InvalidOtpauth("private-seed"))
        val failure = generator.generate("private-seed", time).exceptionOrNull()
        assertEquals("Verification code generation failed", failure?.message)
        assertNull(failure?.cause)
    }

    @Test
    fun `invalid code or unusable period never reaches presentation`() = runTest {
        listOf(
            TotpResponse("", 30u), TotpResponse("123456", 0u),
            TotpResponse("123456", UInt.MAX_VALUE),
        ).forEach { response ->
            every { client.generateTotp(any(), any()) } returns response
            assertTrue(generator.generate("key", time).isFailure)
        }
    }

    @Test
    fun `cancelled SDK call remains cancellation`() {
        every { client.generateTotp(any(), any()) } throws CancellationException()
        assertCoroutineThrows<CancellationException> { generator.generate("key", time) }
    }

    @Test
    fun `code is omitted from automatic string representation`() {
        assertFalse(WatchOtpCode("123456", 30).toString().contains("123456"))
    }
}
