package com.bitwarden.core.data.wear

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.ProviderException
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

private const val FORMAT_VERSION = 1
private const val AES_KEY_BITS = 256
private const val AES_KEY_BYTES = 32
private const val NONCE_BYTES = 12
private const val TAG_BITS = 128
private const val TAG_BYTES = 16
private const val MIN_RSA_BITS = 2048
private const val MAX_RSA_BITS = 4096
private const val MAX_WRAPPED_KEY_BYTES = 512
private const val ENVELOPE_HEADER_BYTES = 8
private const val MAX_ENVELOPE_BYTES =
    WearPayloadCipher.MAX_PLAINTEXT_BYTES + MAX_WRAPPED_KEY_BYTES +
        NONCE_BYTES + TAG_BYTES + ENVELOPE_HEADER_BYTES
private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPPadding"

/** Encrypts bounded companion payloads using RSA-OAEP and a fresh AES-GCM key per payload. */
object WearPayloadCipher {
    /** Maximum plaintext size accepted by the companion envelope. */
    const val MAX_PLAINTEXT_BYTES: Int = 2 * 1024 * 1024

    /**
     * Encrypts [plaintext] for a watch's X.509 RSA [publicKey]. [context] must bind the
     * enrollment, account and revision. It must be validated independently by the receiver.
     */
    fun encrypt(publicKey: ByteArray, plaintext: ByteArray, context: ByteArray): Result<ByteArray> =
        cryptoResult {
            require(plaintext.size <= MAX_PLAINTEXT_BYTES)
            require(publicKey.size <= MAX_WRAPPED_KEY_BYTES * 2)
            val recipient = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(publicKey)) as? RSAPublicKey
                ?: throw IllegalArgumentException("RSA recipient required")
            require(recipient.modulus.bitLength() in MIN_RSA_BITS..MAX_RSA_BITS)
            val keyBytes = KeyGenerator.getInstance("AES").apply { init(AES_KEY_BITS) }
                .generateKey().encoded
            try {
                val cipher = Cipher.getInstance(AES_TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
                cipher.updateAAD(authenticatedContext(context))
                val ciphertext = cipher.doFinal(plaintext)
                val wrapper = Cipher.getInstance(RSA_TRANSFORMATION)
                wrapper.init(Cipher.ENCRYPT_MODE, recipient, oaepParameters())
                val wrappedKey = wrapper.doFinal(keyBytes)
                ByteArrayOutputStream().use { output ->
                    DataOutputStream(output).use {
                        it.writeInt(FORMAT_VERSION)
                        it.writeInt(wrappedKey.size)
                        it.write(wrappedKey)
                        it.write(cipher.iv)
                        it.write(ciphertext)
                    }
                    output.toByteArray()
                }
            } finally {
                keyBytes.fill(0)
            }
        }

    /** Decrypts a payload; altered bytes, context, versions and invalid lengths fail closed. */
    fun decrypt(
        privateKey: PrivateKey,
        envelope: ByteArray,
        context: ByteArray,
    ): Result<ByteArray> =
        cryptoResult {
            require(envelope.size <= MAX_ENVELOPE_BYTES)
            DataInputStream(ByteArrayInputStream(envelope)).use { input ->
                require(input.readInt() == FORMAT_VERSION)
                val wrappedSize = input.readInt()
                require(wrappedSize in 1..MAX_WRAPPED_KEY_BYTES)
                require(input.available() >= wrappedSize + NONCE_BYTES + TAG_BYTES)
                val wrappedKey = ByteArray(wrappedSize).also(input::readFully)
                val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
                val ciphertext = ByteArray(input.available()).also(input::readFully)
                val wrapper = Cipher.getInstance(RSA_TRANSFORMATION)
                wrapper.init(Cipher.DECRYPT_MODE, privateKey, oaepParameters())
                val keyBytes = wrapper.doFinal(wrappedKey)
                try {
                    require(keyBytes.size == AES_KEY_BYTES)
                    val cipher = Cipher.getInstance(AES_TRANSFORMATION)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(keyBytes, "AES"),
                        GCMParameterSpec(TAG_BITS, nonce),
                    )
                    cipher.updateAAD(authenticatedContext(context))
                    cipher.doFinal(ciphertext)
                } finally {
                    keyBytes.fill(0)
                }
            }
        }

    private fun authenticatedContext(context: ByteArray): ByteArray =
        byteArrayOf(FORMAT_VERSION.toByte()) + context

    // Android Keystore uses SHA-1 for the OAEP MGF1 digest unless explicitly configured otherwise.
    private fun oaepParameters(): OAEPParameterSpec =
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    private inline fun <T> cryptoResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: GeneralSecurityException) {
        Result.failure(error)
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    } catch (error: ProviderException) {
        Result.failure(error)
    }
}
