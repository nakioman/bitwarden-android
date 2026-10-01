package com.x8bit.bitwarden.data.wear

/** Obtains a companion's public key without requesting vault data or private keys. */
interface WearKeySource {
    /** Requests and validates a fresh public-key response from exactly [deviceId]. */
    suspend fun getPublicKey(deviceId: String): Result<ByteArray>
}
