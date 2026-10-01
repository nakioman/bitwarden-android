package com.x8bit.bitwarden.data.wear

import java.io.IOException

/** Keeps companion key exchange unavailable without Google Play services. */
class WearKeySourceImpl : WearKeySource {
    override suspend fun getPublicKey(deviceId: String): Result<ByteArray> =
        Result.failure(IOException("Companion keys unavailable"))
}
