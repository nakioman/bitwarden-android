package com.bitwarden.core.data.wear

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.KeyPairGenerator

class WearKeyProtocolTest {
    private val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        .generateKeyPair().public.encoded

    @Test
    fun `public key round trip matches the request challenge`() {
        val request = WearKeyProtocol.createRequest()
        val response = WearKeyProtocol.createResponse(request, publicKey).getOrThrow()
        assertArrayEquals(publicKey, WearKeyProtocol.readResponse(request, response).getOrThrow())
    }

    @Test
    fun `a response to another request is rejected`() {
        val first = WearKeyProtocol.createRequest()
        val second = WearKeyProtocol.createRequest()
        val response = WearKeyProtocol.createResponse(first, publicKey).getOrThrow()
        assertFalse(first.contentEquals(second))
        assertTrue(WearKeyProtocol.readResponse(second, response).isFailure)
    }

    @Test
    fun `unknown versions and truncated requests are rejected`() {
        val request = WearKeyProtocol.createRequest()
        assertFalse(WearKeyProtocol.isValidRequest(byteArrayOf()))
        assertFalse(WearKeyProtocol.isValidRequest(request.dropLast(1).toByteArray()))
        request[0] = 2
        assertFalse(WearKeyProtocol.isValidRequest(request))
        assertTrue(WearKeyProtocol.createResponse(request, publicKey).isFailure)
    }

    @Test
    fun `malformed response lengths and trailing data are rejected`() {
        val request = WearKeyProtocol.createRequest()
        val response = WearKeyProtocol.createResponse(request, publicKey).getOrThrow()
        val negative = response.copyOf().also {
            ByteBuffer.wrap(it).putInt(request.size, -1)
        }
        val oversized = response.copyOf().also {
            ByteBuffer.wrap(it).putInt(request.size, Int.MAX_VALUE)
        }
        listOf(byteArrayOf(), response.dropLast(1).toByteArray(), response + 0, negative, oversized)
            .forEach { assertTrue(WearKeyProtocol.readResponse(request, it).isFailure) }
    }

    @Test
    fun `unsupported and oversized public keys are rejected`() {
        val request = WearKeyProtocol.createRequest()
        val smallKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().public.encoded
        listOf(byteArrayOf(), ByteArray(2048), smallKey).forEach {
            assertTrue(WearKeyProtocol.createResponse(request, it).isFailure)
        }
        val response = WearKeyProtocol.createResponse(request, publicKey).getOrThrow()
        response[response.lastIndex] = (response.last() + 1).toByte()
        // Altering the encoded exponent may still produce a valid RSA key; reject invalid encoding.
        response[request.size + 4] = 0
        assertTrue(WearKeyProtocol.readResponse(request, response).isFailure)
    }
}
