package com.bitwarden.core.data.wear

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator

class WearDataProtocolTest {
    @Test
    fun `snapshot paths are per watch and do not contain raw node identifiers`() {
        val path = WearDataProtocol.snapshotPath("watch/one")
        assertTrue(path.startsWith(WearDataProtocol.SNAPSHOT_PATH_PREFIX))
        assertFalse(path.contains("watch/one"))
        assertNotEquals(path, WearDataProtocol.snapshotPath("watch/two"))
        assertEquals(path, WearDataProtocol.snapshotPath("watch/one"))
    }

    @Test
    fun `unsafe or unbounded node identifiers cannot become routes`() {
        listOf("", "two words", "line\n", "é", "a".repeat(129)).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                WearDataProtocol.snapshotPath(it)
            }
        }
    }

    @Test
    fun `snapshot context binds the recipient key`() {
        assertFalse(
            WearDataProtocol.snapshotContext("a".repeat(64))
                .contentEquals(WearDataProtocol.snapshotContext("b".repeat(64))),
        )
    }

    @Test
    fun `recipient key id is a stable hex digest`() {
        val id = WearDataProtocol.recipientKeyId(byteArrayOf(1, 2, 3))
        assertEquals(64, id.length)
        assertEquals(id, WearDataProtocol.recipientKeyId(byteArrayOf(1, 2, 3)))
        assertNotEquals(id, WearDataProtocol.recipientKeyId(byteArrayOf(1, 2, 4)))
    }

    @Test
    fun `only supported RSA public keys are valid`() {
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair().public.encoded
        val weak = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().public.encoded
        val ec = KeyPairGenerator.getInstance("EC").generateKeyPair().public.encoded
        assertTrue(WearDataProtocol.isValidPublicKey(rsa))
        assertFalse(WearDataProtocol.isValidPublicKey(weak))
        assertFalse(WearDataProtocol.isValidPublicKey(ec))
        assertFalse(WearDataProtocol.isValidPublicKey(byteArrayOf()))
        assertFalse(WearDataProtocol.isValidPublicKey(byteArrayOf(1, 2, 3)))
    }
}
