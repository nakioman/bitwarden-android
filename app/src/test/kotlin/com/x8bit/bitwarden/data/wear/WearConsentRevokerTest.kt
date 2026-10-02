package com.x8bit.bitwarden.data.wear

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class WearConsentRevokerTest {
    private val source = mockk<WearConsentDiskSource>()
    private val revoker = WearConsentRevokerImpl(source, isEnabled = true)
    private val consent = WearConsent("user", "watch", "Watch", true, "key")

    @Test
    fun `withdrawal preserves other accounts and pinned keys`() {
        val other = consent.copy(userId = "other")
        every { source.getConsents() } returns Result.success(listOf(consent, other))
        every { source.compareAndSetConsents(any(), any()) } returns Result.success(Unit)
        assertTrue(revoker.revokeAccount("user").isSuccess)
        verify {
            source.compareAndSetConsents(
                listOf(consent, other), listOf(consent.copy(isAuthorized = false), other),
            )
        }
    }

    @Test
    fun `concurrent changes are reloaded before retrying withdrawal`() {
        val other = consent.copy(userId = "other")
        every { source.getConsents() } returnsMany listOf(
            Result.success(listOf(consent)), Result.success(listOf(consent, other)),
        )
        every { source.compareAndSetConsents(any(), any()) } returnsMany listOf(
            Result.failure(IOException()), Result.success(Unit),
        )
        assertTrue(revoker.revokeAccount("user").isSuccess)
        verify {
            source.compareAndSetConsents(
                listOf(consent, other), listOf(consent.copy(isAuthorized = false), other),
            )
        }
    }

    @Test
    fun `unreadable decisions fail without writing`() {
        every { source.getConsents() } returns Result.failure(IOException())
        assertTrue(revoker.revokeAccount("user").isFailure)
        verify(exactly = 0) { source.compareAndSetConsents(any(), any()) }
    }

    @Test
    fun `persistent conflicts have a bounded retry count`() {
        every { source.getConsents() } returns Result.success(listOf(consent))
        every { source.compareAndSetConsents(any(), any()) } returns Result.failure(IOException())
        assertTrue(revoker.revokeAccount("user").isFailure)
        verify(exactly = 3) { source.compareAndSetConsents(any(), any()) }
    }

    @Test
    fun `already withdrawn account needs no extra commit`() {
        every { source.getConsents() } returns
            Result.success(listOf(consent.copy(isAuthorized = false)))
        assertTrue(revoker.revokeAccount("user").isSuccess)
        verify(exactly = 0) { source.compareAndSetConsents(any(), any()) }
    }

    @Test
    fun `disabled variants do not read or modify companion decisions`() {
        assertEquals(
            Result.success(Unit), WearConsentRevokerImpl(source, false).revokeAccount("user"),
        )
        verify(exactly = 0) { source.getConsents() }
    }
}
