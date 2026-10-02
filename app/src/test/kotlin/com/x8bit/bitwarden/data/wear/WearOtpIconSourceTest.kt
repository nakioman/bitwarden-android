package com.x8bit.bitwarden.data.wear

import com.bitwarden.data.datasource.disk.model.EnvironmentUrlDataJson
import com.bitwarden.data.repository.model.Environment
import com.bitwarden.data.repository.util.baseIconUrl
import com.bitwarden.ui.platform.base.BaseRobolectricTest
import com.x8bit.bitwarden.data.platform.repository.EnvironmentRepository
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockUriView
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class WearOtpIconSourceTest : BaseRobolectricTest() {
    private val environment = mockk<EnvironmentRepository>()
    private val settings = mockk<SettingsRepository>()
    private val source = WearOtpIconSourceImpl(environment, settings)

    @Before
    fun setUp() {
        every { environment.environment } returns Environment.Prod.Us
        every { settings.isIconLoadingDisabled } returns false
    }

    @Test
    fun `icon URL uses only the login host and not its path or query`() {
        val uris = listOf(
            createMockUriView(1).copy(uri = "https://github.com/login?private=value"),
        )
        assertEquals(
            "${Environment.Prod.Us.baseIconUrl}/github.com/icon.png",
            source.getIconUrl(uris),
        )
    }

    @Test
    fun `custom icon endpoint is respected for a self hosted account`() {
        every { environment.environment } returns Environment.SelfHosted(
            EnvironmentUrlDataJson(base = "https://vault.example", icon = "https://icons.example"),
        )
        assertEquals(
            "https://icons.example/github.com/icon.png",
            source.getIconUrl(listOf(createMockUriView(1).copy(uri = "github.com"))),
        )
    }

    @Test
    fun `disabling icons omits the website icon URL`() {
        every { settings.isIconLoadingDisabled } returns true
        assertNull(source.getIconUrl(listOf(createMockUriView(1).copy(uri = "https://github.com"))))
    }

    @Test
    fun `missing website URIs use a local fallback`() {
        assertNull(source.getIconUrl(null))
        assertNull(source.getIconUrl(emptyList()))
        assertNull(source.getIconUrl(listOf(createMockUriView(1).copy(uri = "no-domain"))))
    }

    @Test
    fun `mobile app URIs do not become website icon requests`() {
        for (uri in listOf("androidapp://com.example.app", "iosapp://com.example.app")) {
            assertNull(source.getIconUrl(listOf(createMockUriView(1).copy(uri = uri))))
        }
    }
}
