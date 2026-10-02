package com.x8bit.bitwarden.data.wear

import java.io.IOException

/** Keeps the distribution without Google Play services free of Wear transport dependencies. */
class WearDataSourceImpl : WearDataSource {
    override suspend fun publish(deviceId: String, packet: ByteArray): Result<Unit> = unavailable()

    override suspend fun getReceipt(deviceId: String): Result<ByteArray?> = unavailable()
}

private fun <T> unavailable(): Result<T> = Result.failure(IOException("Wear data unavailable"))
