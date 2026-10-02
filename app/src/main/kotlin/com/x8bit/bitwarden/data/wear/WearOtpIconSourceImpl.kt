package com.x8bit.bitwarden.data.wear

import com.bitwarden.data.repository.util.baseIconUrl
import com.bitwarden.ui.platform.components.icon.model.IconData
import com.bitwarden.vault.LoginUriView
import com.x8bit.bitwarden.data.platform.repository.EnvironmentRepository
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.ui.vault.feature.vault.util.toLoginIconData

/** Reuses the phone's icon resolution without downloading an image. */
class WearOtpIconSourceImpl(
    private val environmentRepository: EnvironmentRepository,
    private val settingsRepository: SettingsRepository,
) : WearOtpIconSource {
    override fun getIconUrl(uris: List<LoginUriView>?): String? =
        (uris.toLoginIconData(
            isIconLoadingDisabled = settingsRepository.isIconLoadingDisabled,
            baseIconUrl = environmentRepository.environment.baseIconUrl,
            usePasskeyDefaultIcon = false,
        ) as? IconData.Network)?.uri
}
