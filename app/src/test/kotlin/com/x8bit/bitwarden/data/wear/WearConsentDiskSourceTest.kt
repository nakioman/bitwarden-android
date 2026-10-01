package com.x8bit.bitwarden.data.wear

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
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
        assertTrue(source.storeConsents(emptyList()).isFailure)
    }
}
