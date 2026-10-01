package com.bitwarden.core.data.wear

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator

class WearPayloadCipherTest {
    private val recipient = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        .generateKeyPair()
    private val context = "enrollment:account:revision".toByteArray()
    private val plaintext = "synthetic verification data".toByteArray()

    @Test
    fun `a recipient can decrypt the complete payload`() {
        val encrypted = encrypt()
        assertArrayEquals(
            plaintext,
            WearPayloadCipher.decrypt(recipient.private, encrypted, context).getOrThrow(),
        )
    }

    @Test
    fun `each snapshot uses fresh encryption material`() {
        assertFalse(encrypt().contentEquals(encrypt()))
    }

    @Test
    fun `a different enrollment context cannot decrypt the snapshot`() {
        assertTrue(
            WearPayloadCipher.decrypt(
                recipient.private, encrypt(), "other".toByteArray(),
            ).isFailure,
        )
    }

    @Test
    fun `modified ciphertext fails authentication`() {
        val encrypted = encrypt().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        }
        assertTrue(WearPayloadCipher.decrypt(recipient.private, encrypted, context).isFailure)
    }

    @Test
    fun `modified wrapped key cannot be decrypted`() {
        val encrypted = encrypt().apply { this[8] = (this[8].toInt() xor 1).toByte() }
        assertTrue(WearPayloadCipher.decrypt(recipient.private, encrypted, context).isFailure)
    }

    @Test
    fun `another watch cannot decrypt the snapshot`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertTrue(WearPayloadCipher.decrypt(other.private, encrypt(), context).isFailure)
    }

    @Test
    fun `malformed lengths versions and truncated payloads fail closed`() {
        val encrypted = encrypt()
        val invalid = listOf(
            byteArrayOf(),
            encrypted.copyOf(7),
            encrypted.copyOf().apply { this[3] = 2 },
            encrypted.copyOf().apply { this[4] = -1 },
            encrypted.copyOf(272),
        )
        invalid.forEach {
            assertTrue(WearPayloadCipher.decrypt(recipient.private, it, context).isFailure)
        }
    }

    @Test
    fun `oversized plaintext and invalid public keys are rejected`() {
        assertTrue(
            WearPayloadCipher.encrypt(
                recipient.public.encoded,
                ByteArray(2 * 1024 * 1024 + 1),
                context,
            ).isFailure,
        )
        assertTrue(WearPayloadCipher.encrypt(byteArrayOf(1), plaintext, context).isFailure)
    }

    private fun encrypt(): ByteArray =
        WearPayloadCipher.encrypt(recipient.public.encoded, plaintext, context).getOrThrow()
}
