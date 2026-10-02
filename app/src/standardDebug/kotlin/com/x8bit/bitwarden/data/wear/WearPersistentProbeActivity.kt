package com.x8bit.bitwarden.data.wear

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bitwarden.core.data.manager.UuidManager
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearConnectionProtocol
import com.bitwarden.core.data.wear.WearDataProbeProtocol
import com.bitwarden.core.data.wear.WearDataProtocol
import com.bitwarden.core.data.wear.WearKeyProtocol
import com.bitwarden.core.data.wear.WearPayloadCipher
import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotHeader
import com.bitwarden.core.data.wear.WearSnapshotProbeProtocol
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Wearable
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.wear.di.PersistentTransportProbe
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.coroutines.resume
import javax.inject.Inject

private const val REQUEST_TIMEOUT_MILLIS = 15_000L

/** ADB-only persistent transport test; no SDK access or real OTP extraction. */
@AndroidEntryPoint
class WearPersistentProbeActivity : ComponentActivity() {
    /** Reads only active account identity, never vault items. */
    @Inject
    lateinit var authRepository: AuthRepository

    /** Requires the active phone account and unlocked vault for explicit test actions. */
    @Inject
    lateinit var accountSource: WearOtpAccountSource

    /** Requires prior user consent; production keys are not used by the synthetic recipient. */
    @Inject
    lateinit var consentSource: WearConsentDiskSource

    /** Isolated synthetic retry queue. */
    @Inject
    @PersistentTransportProbe
    lateinit var outbox: WearOutboxManager

    /** Isolated ledger retains enrollment metadata after acknowledgment. */
    @Inject
    @PersistentTransportProbe
    lateinit var diskSource: WearOutboxDiskSource

    /** Persistent synthetic routes only. */
    @Inject
    @PersistentTransportProbe
    lateinit var dataSource: WearDataSource

    /** Generates synthetic enrollment identifiers. */
    @Inject
    lateinit var uuidManager: UuidManager

    /** Worker dispatcher for network, disk and crypto operations. */
    @Inject
    lateinit var dispatcherManager: DispatcherManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phase = intent.getStringExtra("phase") ?: "send"
        lifecycleScope.launch {
            val outcome = withContext(dispatcherManager.io) { runProbe(phase) }
            Log.i("WearPersistentProbe", outcome)
            finish()
        }
    }

    private suspend fun runProbe(phase: String): String = try {
        val userId = requireNotNull(authRepository.userStateFlow.value?.activeUserId)
        check(accountSource.getAccount(userId) != null)
        val consent = consentSource.getConsents().getOrThrow().filter {
            it.userId == userId && it.isAuthorized && it.publicKey != null
        }.singleOrNull() ?: throw IOException("Exactly one authorized watch required")
        executePhase(phase, userId, consent.deviceId)
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        "FAIL: synthetic transport, account or consent unavailable"
    } catch (_: GeneralSecurityException) {
        "FAIL: synthetic cipher operation failed"
    } catch (_: SecurityException) {
        "FAIL: synthetic operation unavailable"
    } catch (_: IllegalArgumentException) {
        "FAIL: synthetic input rejected"
    } catch (_: IllegalStateException) {
        "FAIL: synthetic assertion failed"
    }

    private suspend fun executePhase(phase: String, userId: String, deviceId: String): String =
        when (phase) {
            "send" -> send(userId, deviceId)
            "retry" -> {
                publish(userId, deviceId)
                "PASS: existing synthetic packet republished; still awaiting receipt"
            }
            "ack" -> acknowledge(userId, deviceId)
            "revoke" -> revoke(userId, deviceId)
            else -> throw IllegalArgumentException("Unknown synthetic phase")
        }

    private suspend fun send(userId: String, deviceId: String): String {
        val previous = diskSource.read().getOrThrow().singleOrNull { it.deviceId == deviceId }
        check(
            previous?.lastHeader == null ||
                (previous.lastHeader.isRevocation && previous.pendingPacket == null),
        )
        val reset = request(
            userId, deviceId, WearSnapshotProbeProtocol.RESET_PATH,
            WearConnectionProtocol.createPayload(),
        )
        check(reset.contentEquals(byteArrayOf(WearSnapshotProbeProtocol.ACCEPTED)))
        val challenge = WearKeyProtocol.createRequest()
        val publicKey = WearKeyProtocol.readResponse(
            challenge, request(userId, deviceId, WearSnapshotProbeProtocol.KEY_PATH, challenge),
        ).getOrThrow()
        val header = WearSnapshotHeader(
            uuidManager.generateUuid(), outbox.reserveRevision(deviceId).getOrThrow(),
            WearSnapshotProtocol.recipientKeyId(publicKey),
        )
        val plaintext = WearDataProbeProtocol.createPayload()
        val packet = try {
            val ciphertext = WearPayloadCipher.encrypt(
                publicKey, plaintext, WearSnapshotProtocol.context(header).getOrThrow(),
            ).getOrThrow()
            WearSnapshotProtocol.encode(WearSnapshotFrame(header, ciphertext)).getOrThrow()
        } finally {
            plaintext.fill(0)
        }
        requireConsent(userId, deviceId)
        outbox.enqueue(userId, deviceId, packet).getOrThrow()
        publish(userId, deviceId)
        return "PASS: synthetic asset published; durable outbox retained until exact receipt"
    }

    private suspend fun publish(userId: String, deviceId: String) {
        requireConsent(userId, deviceId)
        val pending = outbox.getPending().getOrThrow().single {
            it.userId == userId && it.deviceId == deviceId
        }
        dataSource.publish(deviceId, pending.packet).getOrThrow()
        check(outbox.getPending().getOrThrow().any {
            it.deviceId == deviceId && it.packet.contentEquals(pending.packet)
        })
    }

    private suspend fun acknowledge(userId: String, deviceId: String): String {
        requireConsent(userId, deviceId)
        val pending = outbox.getPending().getOrThrow().single {
            it.userId == userId && it.deviceId == deviceId
        }
        val receipt = dataSource.getReceipt(deviceId).getOrThrow()
            ?: return "WAIT: no watch receipt; synthetic outbox preserved"
        if (!WearDataProtocol.matchesReceipt(receipt, pending.packet)) {
            return "WAIT: receipt does not match latest packet; synthetic outbox preserved"
        }
        requireConsent(userId, deviceId)
        outbox.acknowledge(deviceId, pending.packet).getOrThrow()
        check(outbox.getPending().getOrThrow().none { it.deviceId == deviceId })
        return "PASS: watch-owned exact receipt committed; synthetic outbox cleared"
    }

    private suspend fun revoke(userId: String, deviceId: String): String {
        requireConsent(userId, deviceId)
        val record = diskSource.read().getOrThrow().single { it.deviceId == deviceId }
        check(record.userId == userId)
        val header = requireNotNull(record.lastHeader)
        check(!header.isRevocation)
        val revocation = header.copy(
            revision = outbox.reserveRevision(deviceId).getOrThrow(), isRevocation = true,
        )
        val packet = WearSnapshotProtocol.encode(
            WearSnapshotFrame(revocation, byteArrayOf()),
        ).getOrThrow()
        outbox.enqueue(userId, deviceId, packet).getOrThrow()
        publish(userId, deviceId)
        return "PASS: synthetic revocation published; pending until watch persistence receipt"
    }

    private suspend fun request(
        userId: String,
        deviceId: String,
        path: String,
        data: ByteArray,
    ): ByteArray {
        requireConsent(userId, deviceId)
        val response = Wearable.getMessageClient(this).sendRequest(deviceId, path, data)
            .awaitControl()
        requireConsent(userId, deviceId)
        return response
    }

    private fun requireConsent(userId: String, deviceId: String) {
        check(accountSource.getAccount(userId) != null)
        check(consentSource.getConsents().getOrThrow().any {
            it.userId == userId && it.deviceId == deviceId &&
                it.isAuthorized && it.publicKey != null
        })
    }
}

private suspend fun <T> Task<T>.awaitControl(): T =
    withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine<Result<T>> { continuation ->
            addOnSuccessListener {
                if (continuation.isActive) continuation.resume(Result.success(it))
            }
            addOnFailureListener {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(IOException("Synthetic control failed")))
                }
            }
            addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(IOException("Synthetic control canceled")))
                }
            }
        }
    }?.getOrThrow() ?: throw IOException("Synthetic control timed out")
