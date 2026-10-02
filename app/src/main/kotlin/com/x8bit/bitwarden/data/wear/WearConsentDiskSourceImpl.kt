package com.x8bit.bitwarden.data.wear

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

private const val CONSENTS_KEY = "wearCompanionConsentsV1"

/** Stores the consent ledger using the app's keystore-encrypted preferences. */
class WearConsentDiskSourceImpl(
    private val encryptedPreferences: SharedPreferences,
    private val json: Json,
) : WearConsentDiskSource {
    private val mutableChanges = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    override val changes = mutableChanges.asSharedFlow()

    @Synchronized
    override fun getConsents(): Result<List<WearConsent>> = try {
        val serialized = encryptedPreferences.getString(CONSENTS_KEY, null)
        if (serialized == null && encryptedPreferences.contains(CONSENTS_KEY)) {
            Result.failure(IOException("Consent cannot be decrypted"))
        } else {
            Result.success(serialized?.let { json.decodeFromString(it) } ?: emptyList())
        }
    } catch (error: SerializationException) {
        Result.failure(error)
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    } catch (error: ClassCastException) {
        Result.failure(error)
    } catch (error: IllegalStateException) {
        Result.failure(error)
    }

    @Synchronized
    override fun compareAndSetConsents(
        expected: List<WearConsent>,
        updated: List<WearConsent>,
    ): Result<Unit> {
        val current = getConsents().getOrElse { return Result.failure(it) }
        if (current != expected) return Result.failure(IOException("Consent changed"))
        return writeConsents(updated)
    }

    private fun writeConsents(consents: List<WearConsent>): Result<Unit> = try {
        val committed = encryptedPreferences.edit()
            .putString(CONSENTS_KEY, json.encodeToString(consents))
            .commit()
        if (committed) {
            mutableChanges.tryEmit(Unit)
            Result.success(Unit)
        } else {
            Result.failure(IOException("Consent not saved"))
        }
    } catch (error: SerializationException) {
        Result.failure(error)
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    } catch (error: GeneralSecurityException) {
        Result.failure(error)
    } catch (error: ProviderException) {
        Result.failure(error)
    } catch (error: IllegalStateException) {
        Result.failure(error)
    }
}
