package com.bitwarden.core.data.wear

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec

private const val KEY_VERSION: Byte = 1
private const val CHALLENGE_BYTES = 32
private const val MAX_PUBLIC_KEY_BYTES = 1024
private const val RESPONSE_HEADER_BYTES = 1 + CHALLENGE_BYTES + 4
private const val MAX_RESPONSE_BYTES = RESPONSE_HEADER_BYTES + MAX_PUBLIC_KEY_BYTES
private const val MIN_RSA_BITS = 2048
private const val MAX_RSA_BITS = 4096

/** Bounded public-key discovery; this protocol does not authorize snapshot delivery. */
object WearKeyProtocol {
    /** RPC endpoint exposing only the watch's public encryption key. */
    const val KEY_PATH = "/bitwarden/wear/keys/v1/public"

    /** Checks a pinned key without performing another network discovery request. */
    fun validatePublicKey(publicKey: ByteArray): Result<Unit> = protocolResult {
        validateKey(publicKey)
    }

    /** Creates a fresh challenge to correlate this request with its response. */
    fun createRequest(): ByteArray = byteArrayOf(KEY_VERSION) +
        ByteArray(CHALLENGE_BYTES).also { SecureRandom().nextBytes(it) }

    /** Whether the request uses a supported version and exact challenge length. */
    fun isValidRequest(request: ByteArray): Boolean =
        request.size == 1 + CHALLENGE_BYTES && request.first() == KEY_VERSION

    /** Echoes a valid request's challenge together with a supported X.509 RSA key. */
    fun createResponse(request: ByteArray, publicKey: ByteArray): Result<ByteArray> =
        protocolResult {
            require(isValidRequest(request))
            validateKey(publicKey)
            ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use {
                    it.write(request)
                    it.writeInt(publicKey.size)
                    it.write(publicKey)
                }
                output.toByteArray()
            }
        }

    /** Rejects malformed keys, trailing bytes and responses to a different challenge. */
    fun readResponse(request: ByteArray, response: ByteArray): Result<ByteArray> =
        protocolResult {
            require(isValidRequest(request))
            require(response.size in RESPONSE_HEADER_BYTES..MAX_RESPONSE_BYTES)
            DataInputStream(ByteArrayInputStream(response)).use { input ->
                val echoed = ByteArray(request.size).also(input::readFully)
                require(echoed.contentEquals(request))
                val length = input.readInt()
                require(length in 1..MAX_PUBLIC_KEY_BYTES && length == input.available())
                ByteArray(length).also(input::readFully).also(::validateKey)
            }
        }

    private fun validateKey(publicKey: ByteArray) {
        require(publicKey.size in 1..MAX_PUBLIC_KEY_BYTES)
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(publicKey)) as? RSAPublicKey
            ?: throw IllegalArgumentException("RSA key required")
        require(key.modulus.bitLength() in MIN_RSA_BITS..MAX_RSA_BITS)
    }

    private inline fun <T> protocolResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    } catch (error: GeneralSecurityException) {
        Result.failure(error)
    } catch (error: IOException) {
        Result.failure(error)
    }
}
