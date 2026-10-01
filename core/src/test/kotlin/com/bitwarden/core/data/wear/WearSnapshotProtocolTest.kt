package com.bitwarden.core.data.wear

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.KeyPairGenerator

class WearSnapshotProtocolTest {
    private val header = WearSnapshotHeader(ENROLLMENT, 1, "a".repeat(64))
    private val frame = WearSnapshotFrame(header, byteArrayOf(1, 2, 3))

    @Test
    fun `snapshot and revocation round trip preserve exact metadata and bytes`() {
        val snapshot = WearSnapshotProtocol.decode(WearSnapshotProtocol.encode(frame).getOrThrow())
            .getOrThrow()
        assertEquals(header, snapshot.header)
        assertArrayEquals(frame.ciphertext, snapshot.ciphertext)
        val revoke = WearSnapshotFrame(header.copy(isRevocation = true), byteArrayOf())
        val decoded = WearSnapshotProtocol.decode(WearSnapshotProtocol.encode(revoke).getOrThrow())
            .getOrThrow()
        assertEquals(revoke.header, decoded.header)
        assertArrayEquals(byteArrayOf(), decoded.ciphertext)
    }

    @Test
    fun `invalid revisions enrollments and recipient identifiers fail closed`() {
        listOf(
            header.copy(revision = 0),
            header.copy(revision = -1),
            header.copy(enrollmentId = "invalid"),
            header.copy(enrollmentId = "1-1-1-1-1"),
            header.copy(recipientKeyId = "a"),
            header.copy(recipientKeyId = "G".repeat(64)),
        ).forEach {
            assertTrue(WearSnapshotProtocol.context(it).isFailure)
            assertTrue(WearSnapshotProtocol.encode(frame.copy(header = it)).isFailure)
        }
    }

    @Test
    fun `snapshots require ciphertext and revocations forbid it`() {
        assertTrue(WearSnapshotProtocol.encode(frame.copy(ciphertext = byteArrayOf())).isFailure)
        assertTrue(
            WearSnapshotProtocol.encode(frame.copy(header = header.copy(isRevocation = true)))
                .isFailure,
        )
    }

    @Test
    fun `truncated oversized and ambiguous frames fail closed`() {
        val encoded = WearSnapshotProtocol.encode(frame).getOrThrow()
        val contextSize = WearSnapshotProtocol.context(header).getOrThrow().size
        val negative = encoded.copyOf().also { ByteBuffer.wrap(it).putInt(contextSize, -1) }
        val huge = encoded.copyOf().also { ByteBuffer.wrap(it).putInt(contextSize, Int.MAX_VALUE) }
        val version = encoded.copyOf().also { it[0] = 2 }
        val operation = encoded.copyOf().also { it[1] = 0 }
        listOf(
            byteArrayOf(), encoded.dropLast(1).toByteArray(), encoded + 0,
            negative, huge, version, operation, ByteArray(3 * 1024 * 1024),
        ).forEach { assertTrue(WearSnapshotProtocol.decode(it).isFailure) }
        assertTrue(
            WearSnapshotProtocol.encode(frame.copy(ciphertext = ByteArray(3 * 1024 * 1024)))
                .isFailure,
        )
    }

    @Test
    fun `enrollment revision recipient and operation are cryptographically authenticated`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair()
        val recipient = header.copy(
            recipientKeyId = WearSnapshotProtocol.recipientKeyId(keyPair.public.encoded),
        )
        val payload = byteArrayOf(4, 5, 6)
        val encrypted = WearPayloadCipher.encrypt(
            keyPair.public.encoded, payload, WearSnapshotProtocol.context(recipient).getOrThrow(),
        ).getOrThrow()
        assertArrayEquals(
            payload,
            WearPayloadCipher.decrypt(
                keyPair.private, encrypted, WearSnapshotProtocol.context(recipient).getOrThrow(),
            ).getOrThrow(),
        )
        listOf(
            recipient.copy(revision = 2),
            recipient.copy(enrollmentId = OTHER_ENROLLMENT),
            recipient.copy(recipientKeyId = "b".repeat(64)),
            recipient.copy(isRevocation = true),
        ).forEach {
            assertTrue(
                WearPayloadCipher.decrypt(
                    keyPair.private, encrypted, WearSnapshotProtocol.context(it).getOrThrow(),
                ).isFailure,
            )
        }
    }
}

private const val ENROLLMENT = "22222222-2222-4222-8222-222222222222"
private const val OTHER_ENROLLMENT = "33333333-3333-4333-8333-333333333333"
