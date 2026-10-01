package com.bitwarden.core.data.wear

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WearConnectionProtocolTest {
    @Test
    fun `messages must match their endpoint and protocol version`() {
        val payload = WearConnectionProtocol.createPayload()
        assertTrue(WearConnectionProtocol.isPing(WearConnectionProtocol.PING_PATH, payload))
        assertTrue(WearConnectionProtocol.isPong(WearConnectionProtocol.PONG_PATH, payload))
        assertFalse(WearConnectionProtocol.isPing(WearConnectionProtocol.PONG_PATH, payload))
        assertFalse(WearConnectionProtocol.isPong(WearConnectionProtocol.PING_PATH, payload))
        assertFalse(WearConnectionProtocol.isPing("/unknown", payload))
    }

    @Test
    fun `malformed or unsupported payloads are ignored`() {
        listOf(byteArrayOf(), byteArrayOf(2), byteArrayOf(1, 0)).forEach { payload ->
            assertFalse(WearConnectionProtocol.isPing(WearConnectionProtocol.PING_PATH, payload))
            assertFalse(WearConnectionProtocol.isPong(WearConnectionProtocol.PONG_PATH, payload))
        }
    }

    @Test
    fun `mutating a payload cannot change the protocol`() {
        val payload = WearConnectionProtocol.createPayload()
        payload[0] = 2
        assertFalse(WearConnectionProtocol.isPing(WearConnectionProtocol.PING_PATH, payload))
        assertTrue(
            WearConnectionProtocol.isPing(
                WearConnectionProtocol.PING_PATH,
                WearConnectionProtocol.createPayload(),
            ),
        )
    }
}
