package com.x8bit.bitwarden.wear.data.otp

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearOtpPayloadProtocol
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManager
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock

/**
 * Decodes only a protected snapshot, discarding transient byte buffers on every exit path.
 *
 * @property snapshotManager Protected offline snapshot access.
 * @property securityManager Uncached system lock checks.
 * @property generator Local SDK verification code generation.
 * @property clock Time supplied consistently to all codes in one read.
 * @property dispatcherManager Worker dispatcher for decoding.
 */
class WatchVerificationCodeSourceImpl(
    private val snapshotManager: WatchSnapshotManager,
    private val securityManager: WatchSecurityManager,
    private val generator: WatchOtpGenerator,
    private val clock: Clock,
    private val dispatcherManager: DispatcherManager,
) : WatchVerificationCodeSource {
    override suspend fun read(): Result<List<WatchVerificationCode>> =
        withContext(dispatcherManager.default) {
            if (!isUnlocked()) return@withContext readFailure()
            val plaintext = snapshotManager.read().getOrElse { return@withContext readFailure() }
                ?: return@withContext emptyResult()
            try {
                val entries = WearOtpPayloadProtocol.decode(plaintext).getOrElse {
                    return@withContext readFailure()
                }
                val time = clock.instant()
                val accounts = entries.map { entry ->
                    if (!isUnlocked()) return@withContext readFailure()
                    WatchVerificationCode(
                        id = entry.id,
                        name = entry.name,
                        username = entry.username,
                        iconUrl = entry.iconUrl,
                        otp = generator.generate(entry.secret, time).getOrNull(),
                        generatedAt = time,
                    )
                }
                // A replacement or revocation during generation invalidates these codes.
                if (!isCurrent(plaintext) || !isUnlocked() ||
                    accounts.any { it.isExpired(clock) }
                ) {
                    return@withContext readFailure()
                }
                Result.success(accounts)
            } finally {
                plaintext.fill(0)
            }
        }

    private suspend fun isCurrent(plaintext: ByteArray): Boolean {
        val latest = snapshotManager.read().getOrNull() ?: return false
        return try {
            plaintext.contentEquals(latest)
        } finally {
            latest.fill(0)
        }
    }

    private fun emptyResult(): Result<List<WatchVerificationCode>> =
        if (isUnlocked()) Result.success(emptyList()) else readFailure()

    private fun isUnlocked(): Boolean =
        securityManager.getStatus() == WatchSecurityStatus.UNLOCKED
}

private fun WatchVerificationCode.isExpired(clock: Clock): Boolean {
    val period = otp?.periodSeconds?.toLong() ?: return false
    val now = clock.instant().epochSecond
    return now < generatedAt.epochSecond || now / period != generatedAt.epochSecond / period
}

private fun readFailure(): Result<List<WatchVerificationCode>> =
    Result.failure(IOException("Offline verification codes unavailable"))
