package com.x8bit.bitwarden.data.wear

import android.database.sqlite.SQLiteException
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearOtpEntry
import com.bitwarden.network.model.CipherRepromptTypeJson
import com.bitwarden.network.model.SyncResponseJson
import com.bitwarden.vault.CipherRepromptType
import com.bitwarden.vault.CipherType
import com.bitwarden.vault.CipherView
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.repository.util.toEncryptedSdkCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Reads OTP data without retaining it or creating another vault unlock mechanism.
 *
 * @property diskSource Encrypted vault items scoped to a user.
 * @property sdkSource Cryptographic access to the already unlocked vault.
 * @property accountSource Active unlocked account and TOTP entitlement.
 * @property iconSource Website icon metadata respecting the phone configuration.
 * @property consentSource Account and companion authorization.
 * @property dispatcherManager Dispatchers for vault reads.
 */
class WearOtpSourceImpl(
    private val diskSource: VaultDiskSource,
    private val sdkSource: VaultSdkSource,
    private val accountSource: WearOtpAccountSource,
    private val iconSource: WearOtpIconSource,
    private val consentSource: WearConsentDiskSource,
    private val dispatcherManager: DispatcherManager,
) : WearOtpSource {
    override suspend fun getEntries(
        userId: String,
        deviceId: String,
    ): Result<List<WearOtpEntry>> = withContext(dispatcherManager.io) {
        try {
            loadEntries(userId, deviceId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            failure()
        } catch (_: SQLiteException) {
            failure()
        } catch (_: IllegalStateException) {
            failure()
        } catch (_: IllegalArgumentException) {
            failure()
        }
    }

    private suspend fun loadEntries(
        userId: String,
        deviceId: String,
    ): Result<List<WearOtpEntry>> {
        val account = accountSource.getAccount(userId) ?: return failure()
        val consent = authorizedConsent(userId, deviceId) ?: return failure()
        val ciphers = diskSource.getLoginCiphers(userId).filter { it.isCandidate() }
        val entries = mutableListOf<WearOtpEntry>()
        for (cipher in ciphers) {
            if (!isAuthorized(account, consent)) return failure()
            val decrypted = sdkSource.decryptCipher(userId, cipher.toEncryptedSdkCipher())
                .getOrNull() ?: return failure()
            if (!isAuthorized(account, consent)) return failure()
            if (!decrypted.isEligible(account.isPremium)) continue
            val id = decrypted.id?.takeIf { it.isNotBlank() } ?: return failure()
            if (id != cipher.id || entries.any { it.id == id }) return failure()
            entries.add(
                WearOtpEntry(
                    id = id,
                    name = decrypted.name,
                    username = decrypted.login?.username,
                    secret = requireNotNull(decrypted.login?.totp),
                    iconUrl = iconSource.getIconUrl(decrypted.login?.uris),
                ),
            )
        }
        if (!isAuthorized(account, consent)) return failure()
        return Result.success(entries)
    }

    private fun authorizedConsent(userId: String, deviceId: String): WearConsent? = consentSource
        .getConsents().getOrNull()
        ?.singleOrNull {
            it.userId == userId && it.deviceId == deviceId && it.isAuthorized &&
                !it.publicKey.isNullOrBlank()
        }

    private fun isAuthorized(account: UserState.Account, consent: WearConsent): Boolean =
        accountSource.getAccount(account.userId) == account &&
            authorizedConsent(account.userId, consent.deviceId) == consent

    private fun failure(): Result<List<WearOtpEntry>> =
        Result.failure(IOException("Wear OTP preparation unavailable"))
}

private fun SyncResponseJson.Cipher.isCandidate(): Boolean {
    if (deletedDate != null || archivedDate != null) return false
    return reprompt != CipherRepromptTypeJson.PASSWORD && !login?.totp.isNullOrBlank()
}

private fun CipherView.isEligible(isPremium: Boolean): Boolean =
    type == CipherType.LOGIN && deletedDate == null && archivedDate == null &&
        reprompt == CipherRepromptType.NONE && !login?.totp.isNullOrBlank() &&
        (isPremium || organizationUseTotp)
