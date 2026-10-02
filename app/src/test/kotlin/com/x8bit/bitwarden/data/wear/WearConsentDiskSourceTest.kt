package com.x8bit.bitwarden.data.wear

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WearConsentDiskSourceTest {
    private val preferences = mockk<SharedPreferences>()
    private val source = WearConsentDiskSourceImpl(preferences, Json)

    @Test
    fun `unreadable existing encrypted data is not treated as an empty ledger`() {
        every { preferences.getString(any(), null) } returns null
        every { preferences.contains(any()) } returns true
        assertTrue(source.getConsents().isFailure)
    }

    @Test
    fun `malformed data is rejected`() {
        every { preferences.getString(any(), null) } returns "invalid-json"
        assertTrue(source.getConsents().isFailure)
    }

    @Test
    fun `a failed commit is reported as a failure`() {
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putString(any(), any()) } returns editor
        every { editor.commit() } returns false
        every { preferences.edit() } returns editor
        every { preferences.getString(any(), null) } returns null
        every { preferences.contains(any()) } returns false
        assertTrue(source.compareAndSetConsents(emptyList(), emptyList()).isFailure)
    }

    @Test
    fun `an unreadable ledger cannot be overwritten by compare and set`() {
        every { preferences.getString(any(), null) } returns "invalid-json"
        assertTrue(source.compareAndSetConsents(emptyList(), emptyList()).isFailure)
        verify(exactly = 0) { preferences.edit() }
    }

    @Test
    fun `existing consent records migrate without silently inventing a key`() {
        every { preferences.getString(any(), null) } returns
            """[{"userId":"user","deviceId":"watch","deviceName":"Watch","isAuthorized":true}]"""
        assertEquals(
            listOf(WearConsent("user", "watch", "Watch", true)),
            source.getConsents().getOrThrow(),
        )
    }
}
