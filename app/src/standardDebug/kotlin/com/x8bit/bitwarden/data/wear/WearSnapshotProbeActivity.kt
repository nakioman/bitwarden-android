package com.x8bit.bitwarden.data.wear

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bitwarden.core.data.manager.UuidManager
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearConnectionProtocol
import com.bitwarden.core.data.wear.WearKeyProtocol
import com.bitwarden.core.data.wear.WearPayloadCipher
import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotHeader
import com.bitwarden.core.data.wear.WearSnapshotProbeProtocol
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.bitwarden.data.datasource.disk.di.KeystoreEncryptedPreferences
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.Wearable
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.coroutines.resume
import javax.inject.Inject

private const val TAG = "WearSnapshotProbe"
private const val PROBE_SESSION_KEY = "wearSyntheticTransportProbeV1"
private const val REQUEST_TIMEOUT_MILLIS = 15_000L
private const val REVOCATION_REVISION = 3L

/** Explicit ADB-only synthetic transport test; never extracts or transfers vault items. */
@AndroidEntryPoint
class WearSnapshotProbeActivity : ComponentActivity() {
    /** Used solely to validate which account is active. */
    @Inject
    lateinit var authRepository: AuthRepository

    /** Requires the existing phone vault unlock before performing this test. */
    @Inject
    lateinit var vaultLockManager: VaultLockManager

    /** Requires existing user consent and a pinned companion key. */
    @Inject
    lateinit var consentDiskSource: WearConsentDiskSource

    /** Stores only synthetic ciphertext and non-secret test session metadata. */
    @Inject
    @KeystoreEncryptedPreferences
    lateinit var preferences: SharedPreferences

    /** Worker dispatcher for the test. */
    @Inject
    lateinit var dispatcherManager: DispatcherManager

    /** Generates an isolated synthetic enrollment. */
    @Inject
    lateinit var uuidManager: UuidManager

    /** Serializes synthetic session metadata only. */
    @Inject
    lateinit var json: Json

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phase = intent.getStringExtra("phase") ?: "send"
        lifecycleScope.launch {
            val outcome = withContext(dispatcherManager.io) { runProbe(phase) }
            Log.i(TAG, outcome)
            finish()
        }
    }

    private suspend fun runProbe(phase: String): String = try {
        executePhase(phase)
    } catch (_: IOException) {
        "FAIL: synthetic transport unavailable; check account unlock, consent and connection"
    } catch (_: ApiException) {
        "FAIL: synthetic transport request failed"
    } catch (_: GeneralSecurityException) {
        "FAIL: synthetic cipher operation failed"
    } catch (_: SerializationException) {
        "FAIL: synthetic session metadata unreadable"
    } catch (_: IllegalArgumentException) {
        "FAIL: synthetic protocol input rejected"
    } catch (_: IllegalStateException) {
        "FAIL: synthetic transport assertion failed"
    } catch (_: ClassCastException) {
        "FAIL: synthetic session metadata unreadable"
    }

    private suspend fun executePhase(phase: String): String {
        val userId = requireNotNull(authRepository.userStateFlow.value?.activeUserId) {
            "No active account"
        }
        checkAccount(userId)
        val consent = consentDiskSource.getConsents().getOrThrow()
            .filter { it.userId == userId && it.isAuthorized && it.publicKey != null }
            .singleOrNull() ?: throw IOException("Exactly one authorized companion required")
        return when (phase) {
            "send" -> sendSyntheticSnapshot(userId, consent.deviceId)
            "verify" -> verifySession(loadSession(userId, consent.deviceId))
            "revoke" -> revokeSession(loadSession(userId, consent.deviceId))
            else -> throw IllegalArgumentException("Unknown probe phase")
        }
    }

    private suspend fun sendSyntheticSnapshot(userId: String, deviceId: String): String {
        expectStatus(
            request(userId, deviceId, WearSnapshotProbeProtocol.RESET_PATH, control()),
            WearSnapshotProbeProtocol.ACCEPTED,
        )
        val challenge = WearKeyProtocol.createRequest()
        val publicKey = WearKeyProtocol.readResponse(
            challenge, request(userId, deviceId, WearSnapshotProbeProtocol.KEY_PATH, challenge),
        ).getOrThrow()
        val header = WearSnapshotHeader(
            uuidManager.generateUuid(), 1, WearSnapshotProtocol.recipientKeyId(publicKey),
        )
        val first = encryptSynthetic(header, publicKey)
        val second = encryptSynthetic(header.copy(revision = 2), publicKey)
        deliver(userId, deviceId, first, WearSnapshotProbeProtocol.ACCEPTED)
        deliver(userId, deviceId, second, WearSnapshotProbeProtocol.ACCEPTED)
        deliver(userId, deviceId, first, WearSnapshotProbeProtocol.REJECTED)
        deliver(userId, deviceId, second, WearSnapshotProbeProtocol.ACCEPTED)
        val wrongRecipient = WearSnapshotProtocol.decode(second).getOrThrow().copy(
            header = header.copy(
                revision = REVOCATION_REVISION,
                recipientKeyId = WearSnapshotProtocol.recipientKeyId(byteArrayOf()),
            ),
        )
        deliver(
            userId, deviceId, WearSnapshotProtocol.encode(wrongRecipient).getOrThrow(),
            WearSnapshotProbeProtocol.REJECTED,
        )
        checkAccount(userId)
        val session = ProbeSession(userId, deviceId, second)
        check(
            preferences.edit().putString(PROBE_SESSION_KEY, json.encodeToString(session)).commit(),
        )
        return "PASS: encrypted delivery; stale/wrong-recipient packets rejected; safe duplicate"
    }

    private suspend fun verifySession(session: ProbeSession): String {
        val response = request(
            session.userId, session.deviceId, WearSnapshotProbeProtocol.VERIFY_PATH, control(),
        )
        return when {
            response.contentEquals(byteArrayOf(WearSnapshotProbeProtocol.MATCHED)) -> {
                "PASS: watch decrypted synthetic data locally"
            }
            response.contentEquals(byteArrayOf(WearSnapshotProbeProtocol.LOCKED)) -> {
                "PASS: synthetic snapshot stored; plaintext access denied while locked"
            }
            else -> throw IOException("Unexpected verification status")
        }
    }

    private suspend fun revokeSession(session: ProbeSession): String {
        val previous = WearSnapshotProtocol.decode(session.packet).getOrThrow()
        val revocation = WearSnapshotProtocol.encode(
            WearSnapshotFrame(
                previous.header.copy(revision = REVOCATION_REVISION, isRevocation = true),
                byteArrayOf(),
            ),
        ).getOrThrow()
        deliver(session.userId, session.deviceId, revocation, WearSnapshotProbeProtocol.ACCEPTED)
        val challenge = WearKeyProtocol.createRequest()
        val newKey = WearKeyProtocol.readResponse(
            challenge,
            request(
                session.userId, session.deviceId, WearSnapshotProbeProtocol.KEY_PATH, challenge,
            ),
        ).getOrThrow()
        check(WearSnapshotProtocol.recipientKeyId(newKey) != previous.header.recipientKeyId)
        deliver(
            session.userId, session.deviceId, session.packet, WearSnapshotProbeProtocol.REJECTED,
        )
        check(preferences.edit().remove(PROBE_SESSION_KEY).commit())
        return "PASS: revocation erased recipient key; replay could not restore synthetic data"
    }

    private fun loadSession(userId: String, deviceId: String): ProbeSession {
        val serialized = preferences.getString(PROBE_SESSION_KEY, null)
            ?: throw IOException("Run send phase first")
        val session = json.decodeFromString<ProbeSession>(serialized)
        require(session.userId == userId && session.deviceId == deviceId)
        return session
    }

    private suspend fun deliver(userId: String, deviceId: String, packet: ByteArray, status: Byte) {
        expectStatus(
            request(userId, deviceId, WearSnapshotProbeProtocol.DELIVERY_PATH, packet), status,
        )
    }

    private suspend fun request(
        userId: String,
        deviceId: String,
        path: String,
        data: ByteArray,
    ): ByteArray {
        checkAccount(userId)
        check(consentDiskSource.getConsents().getOrThrow().any {
            it.userId == userId && it.deviceId == deviceId &&
                it.isAuthorized && it.publicKey != null
        })
        val result: Result<ByteArray> = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                Wearable.getMessageClient(this@WearSnapshotProbeActivity)
                    .sendRequest(deviceId, path, data)
                    .addOnSuccessListener {
                        if (continuation.isActive) continuation.resume(Result.success(it))
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(Result.failure(it))
                    }
                    .addOnCanceledListener {
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(IOException("Probe canceled")))
                        }
                    }
            }
        } ?: throw IOException("Probe timed out")
        checkAccount(userId)
        return result.getOrThrow()
    }

    private fun checkAccount(userId: String) {
        check(authRepository.userStateFlow.value?.activeUserId == userId)
        check(vaultLockManager.isVaultUnlocked(userId))
    }
}

@Serializable
private data class ProbeSession(val userId: String, val deviceId: String, val packet: ByteArray)

private fun encryptSynthetic(header: WearSnapshotHeader, publicKey: ByteArray): ByteArray {
    val plaintext = WearSnapshotProbeProtocol.SYNTHETIC_TEXT.toByteArray()
    return try {
        val ciphertext = WearPayloadCipher.encrypt(
            publicKey, plaintext, WearSnapshotProtocol.context(header).getOrThrow(),
        ).getOrThrow()
        WearSnapshotProtocol.encode(WearSnapshotFrame(header, ciphertext)).getOrThrow()
    } finally {
        plaintext.fill(0)
    }
}

private fun expectStatus(response: ByteArray, expected: Byte) {
    check(response.contentEquals(byteArrayOf(expected)))
}

private fun control(): ByteArray = WearConnectionProtocol.createPayload()
