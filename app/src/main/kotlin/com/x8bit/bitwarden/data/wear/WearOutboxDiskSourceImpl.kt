package com.x8bit.bitwarden.data.wear

import android.content.SharedPreferences
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

private const val OUTBOX_KEY = "wearEncryptedOutboxV1"

/**
 * Commits ciphertext and non-secret routing metadata using keystore-encrypted preferences.
 *
 * @property encryptedPreferences Keystore-encrypted storage.
 * @property json Ledger serializer.
 * @property storageKey Separates production state from isolated synthetic tests.
 */
class WearOutboxDiskSourceImpl(
    private val encryptedPreferences: SharedPreferences,
    private val json: Json,
    private val storageKey: String = OUTBOX_KEY,
) : WearOutboxDiskSource {
    override fun read(): Result<List<WearOutboxRecord>> = outboxResult {
        val serialized = encryptedPreferences.getString(storageKey, null)
        if (serialized == null) {
            check(!encryptedPreferences.contains(storageKey))
            emptyList()
        } else {
            require(serialized.length <= MAX_OUTBOX_CHARACTERS)
            json.decodeFromString<List<WearOutboxRecord>>(serialized).also(::validateOutbox)
        }
    }

    override fun write(records: List<WearOutboxRecord>): Result<Unit> = outboxResult {
        validateOutbox(records)
        val serialized = json.encodeToString(records)
        require(serialized.length <= MAX_OUTBOX_CHARACTERS)
        if (!encryptedPreferences.edit().putString(storageKey, serialized).commit()) {
            throw IOException("Wear outbox not saved")
        }
    }
}

private inline fun <T> outboxResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (_: SerializationException) {
    outboxFailure()
} catch (_: IOException) {
    outboxFailure()
} catch (_: GeneralSecurityException) {
    outboxFailure()
} catch (_: ProviderException) {
    outboxFailure()
} catch (_: IllegalArgumentException) {
    outboxFailure()
} catch (_: IllegalStateException) {
    outboxFailure()
} catch (_: ClassCastException) {
    outboxFailure()
}

private fun <T> outboxFailure(): Result<T> = Result.failure(IOException("Wear outbox unavailable"))
