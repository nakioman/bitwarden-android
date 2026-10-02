package com.x8bit.bitwarden.wear.data.otp

import com.bitwarden.core.ClientManagedTokens
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.sdk.BitwardenException
import com.bitwarden.sdk.Client
import com.bitwarden.sdk.ManagedSettingsBindingClient
import com.bitwarden.sdk.VaultClientInterface
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/**
 * Uses the same SDK generator as Authenticator, with no account tokens or vault crypto context.
 *
 * @property dispatcherManager Worker dispatcher for native generation.
 * @property clientProvider Lazily creates the isolated SDK vault generator.
 */
class WatchOtpGeneratorImpl(
    private val dispatcherManager: DispatcherManager,
    private val clientProvider: () -> VaultClientInterface = {
        Client(
            tokenProvider = object : ClientManagedTokens {
                override suspend fun getAccessToken(): String? = null
            },
            settings = null,
            managedSettings = ManagedSettingsBindingClient(),
        ).vault()
    },
) : WatchOtpGenerator {
    private val mutex = Mutex()
    private var client: VaultClientInterface? = null

    override suspend fun generate(secret: String, time: Instant): Result<WatchOtpCode> =
        withContext(dispatcherManager.default) {
            mutex.withLock {
                try {
                    val generator = client ?: clientProvider().also { client = it }
                    val response = generator.generateTotp(key = secret, time = time)
                    if (response.code.isBlank() || response.period == 0u ||
                        response.period > Int.MAX_VALUE.toUInt()
                    ) {
                        return@withLock generationFailure()
                    }
                    Result.success(WatchOtpCode(response.code, response.period.toInt()))
                } catch (_: BitwardenException) {
                    // SDK errors can contain the input URI; do not expose their message or cause.
                    generationFailure()
                } catch (_: IllegalArgumentException) {
                    generationFailure()
                }
            }
        }
}

private fun generationFailure(): Result<WatchOtpCode> =
    Result.failure(IOException("Verification code generation failed"))
