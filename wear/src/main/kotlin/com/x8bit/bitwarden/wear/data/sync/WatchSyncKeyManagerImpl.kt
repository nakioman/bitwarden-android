package com.x8bit.bitwarden.wear.data.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bitwarden.core.data.wear.WearPayloadCipher
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val RECIPIENT_KEY_ALIAS = "bitwarden.wear.sync.recipient.v1"
private const val RECIPIENT_KEY_BITS = 2048

/** Uses the system unlock, without introducing a second app credential or authentication timer. */
class WatchSyncKeyManagerImpl(
    private val keyAlias: String = RECIPIENT_KEY_ALIAS,
) : WatchSyncKeyManager {
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    override fun createPublicKey(): Result<ByteArray> = keyResult {
        if (!keyStore.containsAlias(keyAlias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER)
                .apply {
                    initialize(
                        KeyGenParameterSpec.Builder(
                            keyAlias,
                            KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setKeySize(RECIPIENT_KEY_BITS)
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                            .setUnlockedDeviceRequired(true)
                            .build(),
                    )
                }
                .generateKeyPair()
        }
        getPublicKey().getOrThrow()
    }

    override fun getPublicKey(): Result<ByteArray> = keyResult {
        keyStore.getCertificate(keyAlias)?.publicKey?.encoded
            ?: throw IOException("Companion key missing; provision again")
    }

    override fun decrypt(envelope: ByteArray, context: ByteArray): Result<ByteArray> = keyResult {
        val privateKey = keyStore.getKey(keyAlias, null) as? PrivateKey
            ?: throw IOException("Companion key missing; provision again")
        WearPayloadCipher.decrypt(privateKey, envelope, context).getOrThrow()
    }

    override fun deleteKey(): Result<Unit> = keyResult {
        keyStore.deleteEntry(keyAlias)
    }

    private inline fun <T> keyResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: GeneralSecurityException) {
        Result.failure(error)
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: ProviderException) {
        Result.failure(error)
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    }
}
