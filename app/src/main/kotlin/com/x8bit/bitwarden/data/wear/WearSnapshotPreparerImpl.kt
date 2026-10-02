package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.UuidManager
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearKeyProtocol
import com.bitwarden.core.data.wear.WearOtpPayloadProtocol
import com.bitwarden.core.data.wear.WearPayloadCipher
import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotHeader
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64

private const val MAX_ENCODED_PUBLIC_KEY_LENGTH = 2048

/**
 * Serializes command preparation; outbox revisions also protect against concurrent revocation.
 *
 * @property accountSource Active unlocked account eligibility.
 * @property consentSource Explicit companion consent and pinned public key.
 * @property otpSource Transient OTP extraction from the existing unlocked vault.
 * @property outbox Validated enrollment metadata and durable encrypted command queue.
 * @property uuidManager Fresh enrollment identifiers.
 * @property dispatcherManager Worker dispatcher for crypto and disk operations.
 */
class WearSnapshotPreparerImpl(
    private val accountSource: WearOtpAccountSource,
    private val consentSource: WearConsentDiskSource,
    private val otpSource: WearOtpSource,
    private val outbox: WearOutboxManager,
    private val uuidManager: UuidManager,
    private val dispatcherManager: DispatcherManager,
) : WearSnapshotPreparer {
    private val mutex = Mutex()

    override suspend fun prepareSnapshot(userId: String, deviceId: String): Result<Unit> = prepare {
        val account = requireNotNull(accountSource.getAccount(userId))
        val consent = authorizedConsent(userId, deviceId)
        val encodedKey = requireNotNull(consent.publicKey)
        require(encodedKey.length <= MAX_ENCODED_PUBLIC_KEY_LENGTH)
        val publicKey = Base64.getDecoder().decode(encodedKey)
        WearKeyProtocol.validatePublicKey(publicKey).getOrThrow()
        val keyId = WearSnapshotProtocol.recipientKeyId(publicKey)
        val record = outbox.getRecord(deviceId).getOrThrow()
        val enrollment = enrollmentId(record, userId, keyId)
        val entries = otpSource.getEntries(userId, deviceId).getOrThrow()
        checkAuthorization(account, consent)
        val header = WearSnapshotHeader(
            enrollment, outbox.reserveRevision(deviceId).getOrThrow(), keyId,
        )
        checkAuthorization(account, consent)
        val plaintext = WearOtpPayloadProtocol.encode(entries).getOrThrow()
        val packet = try {
            val encrypted = WearPayloadCipher.encrypt(
                publicKey, plaintext, WearSnapshotProtocol.context(header).getOrThrow(),
            ).getOrThrow()
            WearSnapshotProtocol.encode(WearSnapshotFrame(header, encrypted)).getOrThrow()
        } finally {
            plaintext.fill(0)
        }
        checkAuthorization(account, consent)
        outbox.enqueue(userId, deviceId, packet).getOrThrow()
    }

    override suspend fun prepareRevocation(
        userId: String,
        deviceId: String,
    ): Result<Unit> = prepare {
        val record = outbox.getRecord(deviceId).getOrThrow() ?: return@prepare Unit
        val header = record.lastHeader ?: return@prepare Unit
        require(record.userId == userId)
        if (header.isRevocation) return@prepare Unit
        val revocation = header.copy(
            revision = outbox.reserveRevision(deviceId).getOrThrow(), isRevocation = true,
        )
        val packet = WearSnapshotProtocol.encode(
            WearSnapshotFrame(revocation, byteArrayOf()),
        ).getOrThrow()
        outbox.enqueue(userId, deviceId, packet).getOrThrow()
    }

    private fun enrollmentId(record: WearOutboxRecord?, userId: String, keyId: String): String {
        val previous = record?.lastHeader ?: return uuidManager.generateUuid()
        if (previous.isRevocation) {
            require(record.pendingPacket == null && previous.recipientKeyId != keyId)
            return uuidManager.generateUuid().also { require(it != previous.enrollmentId) }
        }
        require(record.userId == userId && previous.recipientKeyId == keyId)
        return previous.enrollmentId
    }

    private fun authorizedConsent(userId: String, deviceId: String): WearConsent = consentSource
        .getConsents().getOrThrow().single {
            it.userId == userId && it.deviceId == deviceId && it.isAuthorized &&
                !it.publicKey.isNullOrBlank()
        }

    private fun checkAuthorization(account: UserState.Account, consent: WearConsent) {
        require(accountSource.getAccount(account.userId) == account)
        require(authorizedConsent(account.userId, consent.deviceId) == consent)
    }

    private suspend fun prepare(block: suspend () -> Unit): Result<Unit> =
        withContext(dispatcherManager.io) {
            mutex.withLock {
                try {
                    block()
                    Result.success(Unit)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: IOException) {
                    unavailable()
                } catch (_: GeneralSecurityException) {
                    unavailable()
                } catch (_: ProviderException) {
                    unavailable()
                } catch (_: IllegalArgumentException) {
                    unavailable()
                } catch (_: IllegalStateException) {
                    unavailable()
                } catch (_: NoSuchElementException) {
                    unavailable()
                }
            }
        }
}

private fun unavailable(): Result<Unit> =
    Result.failure(IOException("Wear snapshot preparation unavailable"))
