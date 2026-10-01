package com.bitwarden.core.data.wear

/** Fixed, non-sensitive messages used to check the companion connection in debug builds. */
object WearConnectionProtocol {
    /** Endpoint for a connection check. */
    const val PING_PATH = "/bitwarden/wear/connection/v1/ping"

    /** Endpoint for the response to a connection check. */
    const val PONG_PATH = "/bitwarden/wear/connection/v1/pong"

    private const val PROTOCOL_VERSION: Byte = 1

    /** Returns a new payload containing only the protocol version. */
    fun createPayload(): ByteArray = byteArrayOf(PROTOCOL_VERSION)

    /** Whether the incoming message is a supported connection check. */
    fun isPing(path: String, data: ByteArray): Boolean =
        path == PING_PATH && data.contentEquals(createPayload())

    /** Whether the incoming message is a supported connection check response. */
    fun isPong(path: String, data: ByteArray): Boolean =
        path == PONG_PATH && data.contentEquals(createPayload())
}
