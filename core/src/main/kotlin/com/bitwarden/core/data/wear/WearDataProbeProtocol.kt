package com.bitwarden.core.data.wear

private const val SYNTHETIC_PAYLOAD_BYTES = 128 * 1024

/** Fixed, non-secret payload large enough to exercise asset delivery instead of inline data. */
object WearDataProbeProtocol {
    /** Creates synthetic bytes only; it never accepts vault data. */
    fun createPayload(): ByteArray = ByteArray(SYNTHETIC_PAYLOAD_BYTES).also {
        WearSnapshotProbeProtocol.SYNTHETIC_TEXT.toByteArray().copyInto(it)
    }

    /** Validates either existing small probes or the persistent transport probe. */
    fun isSyntheticPayload(bytes: ByteArray): Boolean =
        bytes.contentEquals(WearSnapshotProbeProtocol.SYNTHETIC_TEXT.toByteArray()) ||
            bytes.contentEquals(createPayload())
}
