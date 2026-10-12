package com.bitwarden.core.data.wear

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.KeyPairGenerator

class WearOtpPayloadProtocolTest {
    private val entry = WearOtpEntry(
        "item-one", "GitHub", "user@example.test", "JBSWY3DPEHPK3PXP",
        "https://icons.example.test/github.com/icon.png",
    )

    @Test
    fun `all fields and ordering round trip without changing the secret format`() {
        val otpUri = "otpauth://totp/Example:user?secret=JBSWY3DPEHPK3PXP" +
            "&issuer=Example&digits=8&period=60&algorithm=SHA512"
        val entries = listOf(entry, WearOtpEntry("二", "Correo 😀", "", otpUri))
        val decoded = WearOtpPayloadProtocol.decode(
            WearOtpPayloadProtocol.encode(entries).getOrThrow(),
        ).getOrThrow()
        assertEquals(entries.map(::fields), decoded.map(::fields))
        assertEquals(otpUri, decoded.last().secret)
    }

    @Test
    fun `empty list is a valid complete snapshot`() {
        val packet = WearOtpPayloadProtocol.encode(emptyList()).getOrThrow()
        assertTrue(WearOtpPayloadProtocol.decode(packet).getOrThrow().isEmpty())
    }

    @Test
    fun `null and empty optional fields remain distinct`() {
        val entries = listOf(
            WearOtpEntry("one", "", null, entry.secret, null),
            WearOtpEntry("two", "", "", entry.secret, ""),
        )
        val decoded = WearOtpPayloadProtocol.decode(
            WearOtpPayloadProtocol.encode(entries).getOrThrow(),
        ).getOrThrow()
        assertEquals(entries.map(::fields), decoded.map(::fields))
    }

    @Test
    fun `blank required identifiers secrets and duplicate identifiers fail closed`() {
        for (entries in listOf(
            listOf(WearOtpEntry(" ", "name", null, entry.secret)),
            listOf(WearOtpEntry("id", "name", null, " ")),
            listOf(entry, entry),
        )) {
            assertTrue(WearOtpPayloadProtocol.encode(entries).isFailure)
        }
        val duplicate = rawPayload(listOf(fields(entry), fields(entry)))
        assertTrue(WearOtpPayloadProtocol.decode(duplicate).isFailure)
        listOf(0, 1, 3).forEach { requiredField ->
            val nullRequired = fields(entry).toMutableList().also { it[requiredField] = null }
            assertTrue(WearOtpPayloadProtocol.decode(rawPayload(listOf(nullRequired))).isFailure)
        }
    }

    @Test
    fun `corrupt or unsupported payloads never return a partial list`() {
        val packet = WearOtpPayloadProtocol.encode(listOf(entry)).getOrThrow()
        for (size in packet.indices) {
            assertTrue(WearOtpPayloadProtocol.decode(packet.copyOf(size)).isFailure)
        }
        listOf(
            packet + 0,
            packet.copyOf().also { it[0] = 2 },
            packet.copyOf().also { ByteBuffer.wrap(it).putInt(1, -1) },
            packet.copyOf().also { ByteBuffer.wrap(it).putInt(1, Int.MAX_VALUE) },
            packet.copyOf().also { ByteBuffer.wrap(it).putInt(5, -2) },
            packet.copyOf().also { ByteBuffer.wrap(it).putInt(5, Int.MAX_VALUE) },
            ByteArray(WearPayloadCipher.MAX_PLAINTEXT_BYTES + 1),
        ).forEach { assertTrue(WearOtpPayloadProtocol.decode(it).isFailure) }
    }

    @Test
    fun `invalid UTF8 and unpaired surrogates are rejected without exposing field data`() {
        val packet = WearOtpPayloadProtocol.encode(listOf(entry)).getOrThrow().also {
            it[9] = 0xff.toByte()
        }
        assertTrue(WearOtpPayloadProtocol.decode(packet).isFailure)
        val bad = WearOtpPayloadProtocol.encode(listOf(
            WearOtpEntry("id", "\uD800", null, "private-seed"),
        ))
        assertTrue(bad.isFailure)
        assertFalse(bad.exceptionOrNull().toString().contains("private-seed"))
        assertEquals("WearOtpEntry(<redacted>)", entry.toString())
    }

    @Test
    fun `combined fields are bounded by envelope capacity in bytes not character count`() {
        val maximum = WearPayloadCipher.MAX_PLAINTEXT_BYTES
        val overhead = 1 + Int.SIZE_BYTES + 5 * Int.SIZE_BYTES + "id".length + "seed".length
        val largest = WearOtpEntry("id", "x".repeat(maximum - overhead), null, "seed")
        val bytes = WearOtpPayloadProtocol.encode(listOf(largest)).getOrThrow()
        assertEquals(maximum, bytes.size)
        assertEquals(largest.name, WearOtpPayloadProtocol.decode(bytes).getOrThrow().single().name)
        assertTrue(WearOtpPayloadProtocol.encode(listOf(
            WearOtpEntry("id", largest.name + "x", null, "seed"),
        )).isFailure)
        assertTrue(WearOtpPayloadProtocol.encode(listOf(
            WearOtpEntry("id", "é".repeat(maximum / 2), null, "seed"),
        )).isFailure)
    }

    @Test
    fun `shared payload can be encrypted decrypted and decoded without changing fields`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val plaintext = WearOtpPayloadProtocol.encode(listOf(entry)).getOrThrow()
        try {
            val context = WearDataProtocol.snapshotContext(
                WearDataProtocol.recipientKeyId(keys.public.encoded),
            )
            val ciphertext = WearPayloadCipher.encrypt(keys.public.encoded, plaintext, context)
                .getOrThrow()
            val restored = WearPayloadCipher.decrypt(keys.private, ciphertext, context)
                .getOrThrow()
            try {
                assertArrayEquals(plaintext, restored)
                assertEquals(
                    listOf(fields(entry)),
                    WearOtpPayloadProtocol.decode(restored).getOrThrow().map(::fields),
                )
            } finally {
                restored.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }
}

private fun fields(entry: WearOtpEntry): List<String?> =
    listOf(entry.id, entry.name, entry.username, entry.secret, entry.iconUrl)

private fun rawPayload(entries: List<List<String?>>): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { writer ->
        writer.writeByte(1)
        writer.writeInt(entries.size)
        entries.flatten().forEach { value ->
            val bytes = value?.toByteArray()
            writer.writeInt(bytes?.size ?: -1)
            if (bytes != null) writer.write(bytes)
        }
    }
    return output.toByteArray()
}
