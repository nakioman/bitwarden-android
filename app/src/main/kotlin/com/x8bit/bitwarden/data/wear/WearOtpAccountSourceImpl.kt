package com.x8bit.bitwarden.data.wear

import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager

/** Applies the existing phone account and vault lock checks to OTP access. */
class WearOtpAccountSourceImpl(
    private val authRepository: AuthRepository,
    private val lockManager: VaultLockManager,
) : WearOtpAccountSource {
    override fun getAccount(userId: String): UserState.Account? = authRepository
        .userStateFlow.value
        ?.takeIf { it.activeUserId == userId && lockManager.isVaultUnlocked(userId) }
        ?.activeAccount
        ?.takeIf { it.isLoggedIn }
}
